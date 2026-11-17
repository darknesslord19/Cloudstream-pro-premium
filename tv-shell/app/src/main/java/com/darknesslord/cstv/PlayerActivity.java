package com.darknesslord.cstv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.ui.PlayerView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Yerel oynatıcı (ExoPlayer / Media3).
 * - Kaynağın istediği başlıklar (Referer, User-Agent…) eklenir
 * - Bir kaynak açılmazsa sıradakine geçer; "Kaynak" düğmesiyle elle de seçilir
 * - Kalite / ses / altyazı seçimi oynatıcının ayarlar menüsünden
 * - Kumanda: sol/sağ 10 sn sarma, OK oynat/duraklat menüsü
 */
@OptIn(markerClass = UnstableApi.class)
public class PlayerActivity extends Activity {
    private ExoPlayer player;
    private PlayerView pv;
    private LinearLayout topBar;
    private TextView info;
    private Button srcBtn;
    private JSONArray sources = new JSONArray();
    private JSONArray subtitles = new JSONArray();
    private int idx = 0;
    private String key = "";
    private String title = "";
    private boolean finished = false;
    private final List<String> errors = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        hideSystemUi();

        float dp = getResources().getDisplayMetrics().density;
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        pv = new PlayerView(this);
        pv.setKeepScreenOn(true);
        pv.setShowSubtitleButton(true);
        pv.setShowNextButton(false);
        pv.setShowPreviousButton(false);
        pv.setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING);
        root.addView(pv, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        int pad = (int) (14 * dp);
        topBar.setPadding(pad, pad, pad, pad);
        GradientDrawable shade = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xCC000000, 0x00000000});
        topBar.setBackground(shade);

        info = new TextView(this);
        info.setTextColor(Color.WHITE);
        info.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        info.setMaxLines(2);
        topBar.addView(info, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        srcBtn = new Button(this);
        srcBtn.setAllCaps(false);
        srcBtn.setTextColor(Color.WHITE);
        srcBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(20 * dp);
        bg.setColor(0x66FFFFFF);
        srcBtn.setBackground(bg);
        srcBtn.setPadding((int) (16 * dp), 0, (int) (16 * dp), 0);
        srcBtn.setOnClickListener(v -> chooseSource());
        srcBtn.setOnFocusChangeListener((v, has) -> bg.setColor(has ? 0xFFE50914 : 0x66FFFFFF));
        topBar.addView(srcBtn, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, (int) (40 * dp)));

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.TOP;
        root.addView(topBar, lp);
        setContentView(root);

        pv.setControllerVisibilityListener((PlayerView.ControllerVisibilityListener) visibility -> {
            topBar.setVisibility(visibility);
            if (visibility != View.VISIBLE) hideSystemUi();
        });

        long start = 0;
        try {
            JSONObject d = new JSONObject(getIntent().getStringExtra("data"));
            key = d.optString("key", "");
            title = d.optString("title", "");
            start = d.optLong("start", 0);
            JSONArray s = d.optJSONArray("sources");
            if (s != null) sources = s;
            JSONArray sub = d.optJSONArray("subtitles");
            if (sub != null) subtitles = sub;
        } catch (Exception e) {
            Toast.makeText(this, "Geçersiz oynatma verisi", Toast.LENGTH_LONG).show();
        }
        if (sources.length() == 0) {
            finish();
            return;
        }
        srcBtn.setVisibility(sources.length() > 1 ? View.VISIBLE : View.GONE);

        // Donanım çözücü başarısız olursa yazılım çözücüye düş (ör. bazı HEVC akışları)
        DefaultRenderersFactory rf = new DefaultRenderersFactory(this)
                .setEnableDecoderFallback(true)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER);
        // Daha büyük tampon: dalgalı bağlantıda takılmayı azaltır
        DefaultLoadControl lc = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(30_000, 120_000, 2_000, 4_000)
                .build();
        player = new ExoPlayer.Builder(this, rf).setLoadControl(lc).build();
        pv.setPlayer(player);
        player.addListener(new Player.Listener() {
            @Override
            public void onPlayerError(@NonNull PlaybackException error) {
                long pos = player.getCurrentPosition();
                while (errors.size() <= idx) errors.add(null);
                errors.set(idx, error.getErrorCodeName());
                if (idx + 1 < sources.length()) {
                    Toast.makeText(PlayerActivity.this,
                            name(idx) + ": " + error.getErrorCodeName() + " → sıradaki deneniyor",
                            Toast.LENGTH_SHORT).show();
                    startSource(idx + 1, pos);
                } else {
                    Toast.makeText(PlayerActivity.this,
                            "Hiçbir kaynak oynatılamadı. Kaynak düğmesinden tekrar deneyebilirsin.",
                            Toast.LENGTH_LONG).show();
                    pv.showController();
                }
            }

            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_ENDED) finishWithResult();
            }
        });
        startSource(0, start);
    }

    private String name(int i) {
        JSONObject s = sources.optJSONObject(i);
        return s == null ? "Kaynak" : s.optString("name", "Kaynak " + (i + 1));
    }

    private void chooseSource() {
        String[] names = new String[sources.length()];
        for (int i = 0; i < names.length; i++) {
            String err = i < errors.size() ? errors.get(i) : null;
            names[i] = name(i) + (err != null ? "  ✕ " + err : "");
        }
        final long pos = player == null ? 0 : player.getCurrentPosition();
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Kaynak seç")
                .setSingleChoiceItems(names, idx, (dlg, which) -> {
                    dlg.dismiss();
                    startSource(which, pos);
                })
                .setNegativeButton("Kapat", null)
                .show();
    }

    private void startSource(int i, long pos) {
        idx = i;
        JSONObject s = sources.optJSONObject(i);
        if (s == null) return;
        String url = s.optString("url", "");

        String ua = MainActivity.UA;
        Map<String, String> hdr = new HashMap<>();
        JSONObject h = s.optJSONObject("headers");
        if (h != null) {
            Iterator<String> it = h.keys();
            while (it.hasNext()) {
                String k = it.next();
                String v = h.optString(k);
                if (k.equalsIgnoreCase("user-agent")) ua = v;
                else hdr.put(k, v);
            }
        }
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent(ua)
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(15000)
                .setReadTimeoutMs(25000)
                .setDefaultRequestProperties(hdr);
        DefaultDataSource.Factory ds = new DefaultDataSource.Factory(this, http);

        MediaItem.Builder mb = new MediaItem.Builder().setUri(Uri.parse(url));
        String type = s.optString("type", "").toUpperCase();
        String lower = url.toLowerCase();
        if (type.equals("M3U8") || type.equals("HLS") || (type.isEmpty() && lower.contains(".m3u8"))) {
            mb.setMimeType(MimeTypes.APPLICATION_M3U8);
        } else if (type.equals("DASH") || (type.isEmpty() && lower.contains(".mpd"))) {
            mb.setMimeType(MimeTypes.APPLICATION_MPD);
        }

        if (subtitles.length() > 0) {
            List<MediaItem.SubtitleConfiguration> list = new ArrayList<>();
            for (int j = 0; j < subtitles.length(); j++) {
                JSONObject sub = subtitles.optJSONObject(j);
                if (sub == null) continue;
                String su = sub.optString("url", "");
                if (su.isEmpty()) continue;
                String lang = sub.optString("lang", "");
                list.add(new MediaItem.SubtitleConfiguration.Builder(Uri.parse(su))
                        .setMimeType(MimeTypes.TEXT_VTT) // telefon tarafı altyazıyı VTT'ye çevirir
                        .setLanguage(lang)
                        .setLabel(lang.isEmpty() ? "Altyazı " + (j + 1) : lang)
                        .setSelectionFlags(j == 0 ? C.SELECTION_FLAG_DEFAULT : 0)
                        .build());
            }
            mb.setSubtitleConfigurations(list);
        }

        MediaSource ms = new DefaultMediaSourceFactory(this)
                .setDataSourceFactory(ds)
                .createMediaSource(mb.build());

        info.setText(title);
        srcBtn.setText("Kaynak: " + name(i) + "  (" + (i + 1) + "/" + sources.length() + ")");

        player.setMediaSource(ms);
        player.prepare();
        if (pos > 0) player.seekTo(pos);
        player.setPlayWhenReady(true);
    }

    private void finishWithResult() {
        if (finished) return;
        finished = true;
        Intent r = new Intent();
        r.putExtra("key", key);
        if (player != null) {
            long dur = player.getDuration();
            r.putExtra("pos", player.getCurrentPosition());
            r.putExtra("dur", dur == C.TIME_UNSET ? 0 : dur);
        }
        setResult(RESULT_OK, r);
        finish();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (player != null && e.getAction() == KeyEvent.ACTION_DOWN && !pv.isControllerFullyVisible()) {
            switch (e.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    player.seekTo(Math.max(0, player.getCurrentPosition() - 10000));
                    pv.showController();
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    player.seekTo(player.getCurrentPosition() + 10000);
                    pv.showController();
                    return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    pv.showController();
                    return true;
                default:
                    break;
            }
        }
        if (e.getKeyCode() == KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(e);
        if (srcBtn.hasFocus()) return super.dispatchKeyEvent(e);
        return pv.dispatchKeyEvent(e) || super.dispatchKeyEvent(e);
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        if (pv.isControllerFullyVisible()) {
            pv.hideController();
            return;
        }
        finishWithResult();
    }

    @SuppressWarnings("deprecation")
    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (player != null) player.pause();
    }

    @Override
    protected void onDestroy() {
        if (player != null) {
            player.release();
            player = null;
        }
        super.onDestroy();
    }
}
