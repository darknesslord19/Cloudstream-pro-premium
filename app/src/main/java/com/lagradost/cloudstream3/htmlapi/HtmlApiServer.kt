package com.lagradost.cloudstream3.htmlapi

import android.content.Context
import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.LiveStreamLoadResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TorrentLoadResponse
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mvvm.Resource
import com.lagradost.cloudstream3.ui.APIRepository
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import com.lagradost.cloudstream3.utils.ExtractorLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URLDecoder
import java.util.Collections
import kotlin.concurrent.thread

/**
 * CloudStream HTML API
 *
 * Uygulama açıkken yerel ağda küçük bir HTTP sunucusu çalıştırır. Böylece başka bir
 * cihazdaki tarayıcı (ya da tek bir HTML dosyası) CloudStream'e kurulu eklentileri kullanabilir.
 *
 *   GET /                         → HTML ön yüz (assets/htmlapi/index.html)
 *   GET /api/info                 → sürüm, adresler, eklenti sayısı
 *   GET /api/providers            → kurulu eklentiler
 *   GET /api/home?provider=&page= → eklentinin ana sayfa listeleri
 *   GET /api/search?q=&provider=  → arama (provider boşsa tüm eklentiler)
 *   GET /api/load?provider=&url=  → detay + bölümler
 *   GET /api/links?provider=&data=→ video bağlantıları + altyazılar
 *   GET /proxy?u=&h=              → başlık ekleyerek akışı aktarır (CORS/Referer sorunu için)
 */
object HtmlApiServer {
    private const val TAG = "HtmlApiServer"
    const val PORT = 8686

    @Volatile
    private var server: ServerSocket? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var appContext: Context? = null

    fun isRunning() = server?.isClosed == false

    fun start(context: Context) {
        if (isRunning()) return
        appContext = context.applicationContext
        thread(name = TAG, isDaemon = true) {
            try {
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress(PORT))
                server = ss
                Log.i(TAG, "Dinleniyor: ${addresses()}")
                while (!ss.isClosed) {
                    val socket = ss.accept()
                    scope.launch { handle(socket) }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Sunucu durdu", t)
                server = null
            }
        }
    }

    fun stop() {
        try { server?.close() } catch (_: Throwable) { }
        server = null
    }

    fun addresses(): List<String> = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .map { "http://${it.hostAddress}:$PORT" }
    } catch (_: Throwable) {
        emptyList()
    }

    // ------------------------------------------------------------------ HTTP

    private class Req(val method: String, val path: String, val query: Map<String, String>, val headers: Map<String, String>)

    private fun readRequest(input: InputStream): Req? {
        val head = ByteArrayOutputStream()
        var last4 = 0
        while (true) {
            val b = input.read()
            if (b == -1) return null
            head.write(b)
            last4 = (last4 shl 8) or b
            if (last4 == 0x0D0A0D0A) break
            if (head.size() > 32 * 1024) return null
        }
        val lines = head.toString("ISO-8859-1").split("\r\n")
        val parts = lines.firstOrNull()?.split(" ") ?: return null
        if (parts.size < 2) return null
        val target = parts[1]
        val path = target.substringBefore('?')
        val query = mutableMapOf<String, String>()
        if (target.contains('?')) {
            target.substringAfter('?').split('&').forEach { kv ->
                if (kv.isEmpty()) return@forEach
                val k = URLDecoder.decode(kv.substringBefore('='), "UTF-8")
                val v = URLDecoder.decode(kv.substringAfter('=', ""), "UTF-8")
                query[k] = v
            }
        }
        val headers = mutableMapOf<String, String>()
        lines.drop(1).forEach { l ->
            val i = l.indexOf(':')
            if (i > 0) headers[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim()
        }
        return Req(parts[0].uppercase(), path, query, headers)
    }

    private fun writeHead(out: OutputStream, status: Int, headers: Map<String, String>) {
        val sb = StringBuilder("HTTP/1.1 $status ${statusText(status)}\r\n")
        sb.append("Access-Control-Allow-Origin: *\r\n")
        sb.append("Access-Control-Allow-Headers: *\r\n")
        sb.append("Access-Control-Allow-Methods: GET, HEAD, OPTIONS\r\n")
        sb.append("Access-Control-Allow-Private-Network: true\r\n")
        sb.append("Access-Control-Expose-Headers: Content-Length, Content-Range\r\n")
        sb.append("Connection: close\r\n")
        headers.forEach { (k, v) -> sb.append(k).append(": ").append(v).append("\r\n") }
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(Charsets.UTF_8))
    }

    private fun statusText(s: Int) = when (s) {
        200 -> "OK"; 204 -> "No Content"; 206 -> "Partial Content"; 400 -> "Bad Request"
        404 -> "Not Found"; 500 -> "Internal Server Error"; 502 -> "Bad Gateway"; else -> "Status"
    }

    private fun send(out: OutputStream, status: Int, type: String, body: ByteArray, headOnly: Boolean = false) {
        writeHead(out, status, mapOf("Content-Type" to type, "Content-Length" to body.size.toString(), "Cache-Control" to "no-store"))
        if (!headOnly) out.write(body)
        out.flush()
    }

    private fun sendJson(out: OutputStream, json: Any, status: Int = 200) =
        send(out, status, "application/json; charset=utf-8", json.toString().toByteArray(Charsets.UTF_8))

    private fun sendError(out: OutputStream, status: Int, msg: String) =
        sendJson(out, JSONObject().put("error", msg), status)

    private suspend fun handle(socket: Socket) {
        socket.use { s ->
            try {
                s.soTimeout = 30_000
                val input = BufferedInputStream(s.getInputStream())
                val out = s.getOutputStream()
                val req = readRequest(input) ?: return
                if (req.method == "OPTIONS") {
                    writeHead(out, 204, mapOf("Content-Length" to "0")); out.flush(); return
                }
                s.soTimeout = 0
                route(req, out)
            } catch (t: Throwable) {
                Log.w(TAG, "İstek hatası: ${t.message}")
            }
        }
    }

    private suspend fun route(req: Req, out: OutputStream) {
        val q = req.query
        try {
            when (req.path) {
                "/", "/index.html" -> {
                    val bytes = appContext?.assets?.open("htmlapi/index.html")?.use { it.readBytes() }
                        ?: return sendError(out, 404, "index.html yok")
                    send(out, 200, "text/html; charset=utf-8", bytes, req.method == "HEAD")
                }
                "/api/info" -> sendJson(out, JSONObject()
                    .put("app", "CloudStream")
                    .put("addresses", JSONArray(addresses()))
                    .put("providers", providers().size))
                "/api/providers" -> sendJson(out, JSONArray().apply {
                    providers().forEach { api ->
                        put(JSONObject()
                            .put("name", api.name)
                            .put("mainUrl", api.mainUrl)
                            .put("lang", api.lang)
                            .put("hasMainPage", api.hasMainPage)
                            .put("types", JSONArray(api.supportedTypes.map { it.name })))
                    }
                })
                "/api/home" -> {
                    val api = apiOf(q["provider"]) ?: return sendError(out, 404, "Eklenti bulunamadı")
                    sendJson(out, home(api, q["page"]?.toIntOrNull() ?: 1))
                }
                "/api/search" -> {
                    val query = q["q"]?.trim().orEmpty()
                    if (query.isEmpty()) return sendError(out, 400, "q boş")
                    sendJson(out, search(query, q["provider"]))
                }
                "/api/load" -> {
                    val api = apiOf(q["provider"]) ?: return sendError(out, 404, "Eklenti bulunamadı")
                    val url = q["url"] ?: return sendError(out, 400, "url yok")
                    when (val res = repo(api).load(url)) {
                        is Resource.Success -> sendJson(out, loadJson(res.value))
                        is Resource.Failure -> sendError(out, 502, res.errorString)
                        else -> sendError(out, 502, "İçerik yüklenemedi")
                    }
                }
                "/api/links" -> {
                    val api = apiOf(q["provider"]) ?: return sendError(out, 404, "Eklenti bulunamadı")
                    val data = q["data"] ?: return sendError(out, 400, "data yok")
                    sendJson(out, links(api, data))
                }
                "/proxy" -> proxy(req, out)
                else -> sendError(out, 404, "Bulunamadı")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "${req.path}: $t")
            try { sendError(out, 500, t.message ?: t.javaClass.simpleName) } catch (_: Throwable) { }
        }
    }

    // ------------------------------------------------------------------ CloudStream

    private fun providers(): List<MainAPI> =
        APIHolder.apis.withLock { APIHolder.apis.toList() }

    /** CloudStream'in kendi ekranlarının kullandığı katman: alan adı yönlendirmesi, fixUrl, eklentiye özel zaman aşımı. */
    private val repos = ConcurrentHashMap<String, APIRepository>()
    private fun repo(api: MainAPI): APIRepository {
        val cur = repos[api.name]
        if (cur != null && cur.api === api) return cur
        return APIRepository(api).also { repos[api.name] = it }
    }

    /** Oynatılan bağlantılar: eklentinin video interceptor'ı proxy'de uygulanabilsin diye. */
    private val linkSeq = AtomicLong(0)
    private val linkMap = ConcurrentHashMap<String, Pair<MainAPI, ExtractorLink>>()

    private fun apiOf(name: String?): MainAPI? =
        if (name.isNullOrBlank()) null else providers().firstOrNull { it.name == name }

    private suspend fun home(api: MainAPI, page: Int): JSONObject {
        val lists = JSONArray()
        var error: String? = null
        if (api.hasMainPage) {
            val results = when (val res = repo(api).getMainPage(page)) {
                is Resource.Success -> res.value
                is Resource.Failure -> { error = res.errorString; emptyList() }
                else -> emptyList()
            }
            results.filterNotNull().forEach { r ->
                r.items.forEach { hl ->
                    lists.put(JSONObject()
                        .put("name", hl.name)
                        .put("horizontal", hl.isHorizontalImages)
                        .put("hasNext", r.hasNext)
                        .put("items", JSONArray(hl.list.map { searchJson(it) })))
                }
            }
        }
        return JSONObject().put("provider", api.name).put("page", page).put("lists", lists).put("error", error)
    }

    private suspend fun search(query: String, provider: String?): JSONArray {
        val targets = if (provider.isNullOrBlank()) providers() else listOfNotNull(apiOf(provider))
        val results = targets.map { api ->
            scope.async {
                val items = try {
                    withTimeoutOrNull(30_000) {
                        (repo(api).search(query, 1) as? Resource.Success)?.value?.items
                    }
                } catch (t: Throwable) {
                    null
                }
                if (items.isNullOrEmpty()) null
                else JSONObject().put("provider", api.name).put("items", JSONArray(items.map { searchJson(it) }))
            }
        }.awaitAll()
        return JSONArray(results.filterNotNull())
    }

    private fun searchJson(s: SearchResponse): JSONObject = JSONObject()
        .put("name", s.name)
        .put("url", s.url)
        .put("provider", s.apiName)
        .put("type", s.type?.name)
        .put("poster", s.posterUrl)
        .put("posterHeaders", s.posterHeaders?.let { JSONObject(it) })
        .put("quality", s.quality?.name)

    private fun episodeJson(e: Episode, dub: String? = null): JSONObject = JSONObject()
        .put("data", e.data)
        .put("name", e.name)
        .put("season", e.season)
        .put("episode", e.episode)
        .put("poster", e.posterUrl)
        .put("description", e.description)
        .put("runTime", e.runTime)
        .put("dub", dub)

    private fun loadJson(r: LoadResponse): JSONObject {
        val o = JSONObject()
            .put("name", r.name)
            .put("url", r.url)
            .put("provider", r.apiName)
            .put("type", r.type.name)
            .put("poster", r.posterUrl)
            .put("posterHeaders", r.posterHeaders?.let { JSONObject(it) })
            .put("backdrop", r.backgroundPosterUrl)
            .put("year", r.year)
            .put("plot", r.plot)
            .put("tags", r.tags?.let { JSONArray(it) })
            .put("duration", r.duration)
            .put("recommendations", r.recommendations?.let { list -> JSONArray(list.map { searchJson(it) }) })
        when (r) {
            is MovieLoadResponse -> o.put("kind", "movie").put("data", r.dataUrl)
            is LiveStreamLoadResponse -> o.put("kind", "live").put("data", r.dataUrl)
            is TvSeriesLoadResponse -> o.put("kind", "series")
                .put("episodes", JSONArray(r.episodes.map { episodeJson(it) }))
            is AnimeLoadResponse -> o.put("kind", "series")
                .put("episodes", JSONArray(r.episodes.flatMap { (dub, eps) -> eps.map { episodeJson(it, dub.name) } }))
            is TorrentLoadResponse -> o.put("kind", "torrent")
            else -> o.put("kind", "other")
        }
        return o
    }

    private suspend fun links(api: MainAPI, data: String): JSONObject {
        val links = Collections.synchronizedList(mutableListOf<ExtractorLink>())
        val subs = Collections.synchronizedList(mutableListOf<SubtitleFile>())
        try {
            repo(api).loadLinks(data, false, { subs.add(it) }, { links.add(it) })
        } catch (t: Throwable) {
            Log.w(TAG, "loadLinks: $t")
        }
        if (linkMap.size > 500) linkMap.clear()
        val linkArr = JSONArray()
        synchronized(links) {
            links.distinctBy { it.url }.sortedByDescending { it.quality }.forEach { l ->
                val headers = l.getAllHeaders()
                val id = linkSeq.incrementAndGet().toString()
                linkMap[id] = api to l
                linkArr.put(JSONObject()
                    .put("name", l.name)
                    .put("source", l.source)
                    .put("url", l.url)
                    .put("quality", l.quality)
                    .put("type", l.type.name)
                    .put("headers", JSONObject(headers))
                    .put("proxy", proxyPath(l.url, headers, id) + if (l.type.name == "M3U8") "&m=1" else ""))
            }
        }
        val subArr = JSONArray()
        synchronized(subs) {
            subs.distinctBy { it.url }.forEach { s ->
                val h = s.headers ?: emptyMap()
                subArr.put(JSONObject()
                    .put("lang", s.lang)
                    .put("url", s.url)
                    .put("proxy", proxyPath(s.url, h) + "&vtt=1"))
            }
        }
        return JSONObject().put("links", linkArr).put("subtitles", subArr)
    }

    // ------------------------------------------------------------------ Proxy

    private fun b64(s: String) =
        Base64.encodeToString(s.toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    private fun unb64(s: String) = String(Base64.decode(s, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING), Charsets.UTF_8)

    private fun proxyPath(url: String, headers: Map<String, String>, linkId: String? = null): String =
        "/proxy?u=" + b64(url) +
            (if (headers.isEmpty()) "" else "&h=" + b64(JSONObject(headers).toString())) +
            (if (linkId == null) "" else "&l=$linkId")

    private fun proxy(req: Req, out: OutputStream) {
        val url = req.query["u"]?.let { unb64(it) } ?: return sendError(out, 400, "u yok")
        val hdrParam = req.query["h"]
        val headers = mutableMapOf<String, String>()
        if (hdrParam != null) {
            val j = JSONObject(unb64(hdrParam))
            j.keys().forEach { k -> headers[k] = j.optString(k) }
        }
        val rb = Request.Builder().url(url)
        if (headers.keys.none { it.equals("user-agent", true) }) rb.header("User-Agent", USER_AGENT)
        headers.forEach { (k, v) -> rb.header(k, v) }
        req.headers["range"]?.let { rb.header("Range", it) }

        val linkId = req.query["l"]
        val client = linkId?.let { linkMap[it] }?.let { (api, link) ->
            try { api.getVideoInterceptor(link) } catch (_: Throwable) { null }
        }?.let { app.baseClient.newBuilder().addInterceptor(it).build() } ?: app.baseClient

        client.newCall(rb.build()).execute().use { resp ->
            val body = resp.body ?: return sendError(out, 502, "Boş yanıt")
            val type = resp.header("Content-Type") ?: ""
            val path = url.substringBefore('?').lowercase()
            // Uzantısı .m3u8 olmayan HLS listelerini de içeriğine bakarak tanı
            val looksHls = type.contains("mpegurl", true) || path.endsWith(".m3u8") || path.endsWith(".m3u") ||
                (resp.isSuccessful && req.query["m"] == "1") ||
                (resp.isSuccessful && !type.startsWith("video/") && !type.startsWith("audio/") &&
                    try { resp.peekBody(16).string().trimStart().startsWith("#EXTM3U") } catch (_: Throwable) { false })

            if (req.query["vtt"] == "1") {
                var text = body.string()
                if (!text.trimStart().startsWith("WEBVTT")) {
                    text = "WEBVTT\n\n" + text.replace("\r", "")
                        .replace(Regex("(\\d{2}:\\d{2}:\\d{2}),(\\d{3})"), "$1.$2")
                }
                return send(out, resp.code, "text/vtt; charset=utf-8", text.toByteArray(Charsets.UTF_8))
            }

            if (looksHls && resp.isSuccessful) {
                val finalUrl = resp.request.url.toString()
                val text = body.string()
                if (text.trimStart().startsWith("#EXTM3U")) {
                    val rewritten = rewritePlaylist(text, finalUrl, headers, linkId)
                    return send(out, 200, "application/vnd.apple.mpegurl", rewritten.toByteArray(Charsets.UTF_8))
                }
                return send(out, resp.code, type.ifEmpty { "application/octet-stream" }, text.toByteArray(Charsets.UTF_8))
            }

            val h = mutableMapOf("Content-Type" to type.ifEmpty { "application/octet-stream" }, "Accept-Ranges" to "bytes")
            resp.header("Content-Length")?.let { h["Content-Length"] = it }
            resp.header("Content-Range")?.let { h["Content-Range"] = it }
            writeHead(out, resp.code, h)
            if (req.method != "HEAD") body.byteStream().use { it.copyTo(out, 64 * 1024) }
            out.flush()
        }
    }

    private val uriAttr = Regex("URI=\"([^\"]+)\"")

    private fun rewritePlaylist(text: String, base: String, headers: Map<String, String>, linkId: String?): String {
        val baseUri = URI(base)
        fun abs(u: String) = try { baseUri.resolve(u.trim()).toString() } catch (_: Throwable) { u }
        return text.lines().joinToString("\n") { line ->
            val t = line.trim()
            when {
                t.isEmpty() -> line
                t.startsWith("#") -> uriAttr.replace(line) { m -> "URI=\"" + proxyPath(abs(m.groupValues[1]), headers, linkId) + "\"" }
                else -> proxyPath(abs(t), headers, linkId)
            }
        }
    }
}
