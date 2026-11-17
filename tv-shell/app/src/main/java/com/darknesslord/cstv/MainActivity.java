package com.darknesslord.cstv;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * CloudStream TV kabuğu.
 * Arayüz: telefondaki CloudStream'in sunduğu HTML (http://IP:8686).
 * Oynatma: bu cihazdaki ExoPlayer (PlayerActivity) — tarayıcı sınırları yok.
 */
public class MainActivity extends Activity {
    static final String UA =
            "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36";
    static final int PORT = 8686;
    private static final int REQ_PLAY = 7;
    private static final String START = "file:///android_asset/start.html";

    private WebView web;
    private SharedPreferences prefs;
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private long lastBack = 0;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("cstv", MODE_PRIVATE);

        web = new WebView(this);
        web.setBackgroundColor(0xFF0B0B0F);
        web.setFocusable(true);
        web.setFocusableInTouchMode(true);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setUserAgentString(s.getUserAgentString() + " CloudStreamTV");

        web.setWebViewClient(new WebViewClient() {
            @SuppressWarnings("deprecation")
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return false;
            }

            @SuppressWarnings("deprecation")
            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                // Telefona ulaşılamadı: başlangıç ekranına dön, ağı yeniden tara
                if (failingUrl != null && !failingUrl.startsWith("file:")) {
                    showStart("Telefona ulaşılamadı (" + description + ")");
                }
            }
        });
        web.setWebChromeClient(new WebChromeClient());
        web.addJavascriptInterface(new Bridge(), "CSShell");
        web.loadUrl(START);
        web.requestFocus();
    }

    private void showStart(String msg) {
        runOnUiThread(() -> web.loadUrl(START + "?msg=" + Uri_encode(msg)));
    }

    private static String Uri_encode(String s) {
        try {
            return java.net.URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    private void js(String code) {
        runOnUiThread(() -> web.evaluateJavascript(code, null));
    }

    // ---------------------------------------------------------------- köprü
    class Bridge {
        @JavascriptInterface
        public boolean isShell() { return true; }

        @JavascriptInterface
        public String lastServer() { return prefs.getString("server", ""); }

        /** Kayıtlı adresi dener; sonuç window.__checkResult(url, ok) */
        @JavascriptInterface
        public void check(final String url) {
            pool.execute(() -> {
                boolean ok = probe(url, 2500);
                js("window.__checkResult&&window.__checkResult(" + JSONObject.quote(url) + "," + ok + ")");
            });
        }

        /** Yerel ağda CloudStream'i arar; sonuç window.__scanResult([...], "yerel ip") */
        @JavascriptInterface
        public void scan() {
            pool.execute(() -> {
                List<String> found = scanLan();
                js("window.__scanResult&&window.__scanResult(" + new JSONArray(found) + ","
                        + JSONObject.quote(android.text.TextUtils.join(", ", localIps())) + ")");
            });
        }

        @JavascriptInterface
        public void connect(final String url) {
            String u = url.replaceAll("/+$", "");
            prefs.edit().putString("server", u).apply();
            runOnUiThread(() -> web.loadUrl(u + "/"));
        }

        /** Sunucu seçme ekranına dön */
        @JavascriptInterface
        public void home() {
            runOnUiThread(() -> web.loadUrl(START + "?manual=1"));
        }

        /** Yerel oynatıcıyı açar. json: {key,title,start,sources:[{name,url,headers,type}],subtitles:[{lang,url}]} */
        @JavascriptInterface
        public void play(final String json) {
            runOnUiThread(() -> {
                Intent i = new Intent(MainActivity.this, PlayerActivity.class);
                i.putExtra("data", json);
                startActivityForResult(i, REQ_PLAY);
            });
        }

        @JavascriptInterface
        public void exit() {
            runOnUiThread(MainActivity.this::finish);
        }
    }

    // ---------------------------------------------------------------- ağ
    static boolean probe(String base, int timeoutMs) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(base.replaceAll("/+$", "") + "/api/info").openConnection();
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs);
            if (c.getResponseCode() != 200) return false;
            InputStream in = c.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[2048];
            int n;
            while ((n = in.read(buf)) > 0 && out.size() < 8192) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8).contains("CloudStream");
        } catch (Exception e) {
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    static List<String> localIps() {
        List<String> ips = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
            while (ifs != null && ifs.hasMoreElements()) {
                NetworkInterface ni = ifs.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                Enumeration<InetAddress> as = ni.getInetAddresses();
                while (as.hasMoreElements()) {
                    InetAddress a = as.nextElement();
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) ips.add(a.getHostAddress());
                }
            }
        } catch (Exception ignored) { }
        return ips;
    }

    /** Aynı /24 ağındaki 254 adresi paralel dener (birkaç saniye sürer). */
    static List<String> scanLan() {
        Set<String> prefixes = new LinkedHashSet<>();
        List<String> own = localIps();
        for (String ip : own) prefixes.add(ip.substring(0, ip.lastIndexOf('.') + 1));
        final List<String> found = Collections.synchronizedList(new ArrayList<>());
        ExecutorService ex = Executors.newFixedThreadPool(64);
        for (String p : prefixes) {
            for (int i = 1; i < 255; i++) {
                final String host = p + i;
                if (own.contains(host)) continue;
                ex.execute(() -> {
                    String url = "http://" + host + ":" + PORT;
                    if (probe(url, 700)) found.add(url);
                });
            }
        }
        ex.shutdown();
        try {
            ex.awaitTermination(15, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) { }
        return new ArrayList<>(found);
    }

    // ---------------------------------------------------------------- yaşam döngüsü
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PLAY || data == null) return;
        String key = data.getStringExtra("key");
        if (key == null) return;
        long pos = data.getLongExtra("pos", 0);
        long dur = data.getLongExtra("dur", 0);
        web.evaluateJavascript("window.__onPlayerClosed&&window.__onPlayerClosed("
                + JSONObject.quote(key) + "," + pos + "," + dur + ")", null);
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        web.evaluateJavascript("(window.__onBack&&window.__onBack())?'y':'n'", v -> {
            if (v != null && v.contains("y")) return;
            long now = System.currentTimeMillis();
            if (now - lastBack < 2000) {
                finish();
            } else {
                lastBack = now;
                Toast.makeText(this, "Çıkmak için tekrar Geri'ye bas", Toast.LENGTH_SHORT).show();
            }
        });
    }

    @Override
    protected void onDestroy() {
        pool.shutdownNow();
        if (web != null) web.destroy();
        super.onDestroy();
    }
}
