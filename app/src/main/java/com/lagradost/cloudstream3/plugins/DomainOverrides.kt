package com.lagradost.cloudstream3.plugins

import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import java.util.concurrent.ConcurrentHashMap

/**
 * CloudStream Dark: per-plugin domain overrides.
 *
 * Priority: the user's own address > the admin list (Firebase "adresler")
 * > the address built into the plugin. Overrides are applied when a
 * provider registers (APIHolder.mainUrlOverride) and live when changed.
 * Saved bookmarks that still carry an old domain are rewritten on load.
 */
object DomainOverrides {
    private const val USER_KEY = "DARK_DOMAIN_OVERRIDES"
    private const val ADMIN_KEY = "DARK_ADMIN_DOMAINS"
    private const val HISTORY_KEY = "DARK_DOMAIN_HISTORY"
    const val AUTO_CHECK_KEY = "DARK_DOMAIN_AUTO_CHECK"

    /** Provider class name -> the address the plugin itself shipped with. */
    private val originals = ConcurrentHashMap<String, String>()

    fun keyOf(api: MainAPI): String = api.javaClass.name

    private fun clean(url: String) = url.trim().trimEnd('/')

    fun userMap(): Map<String, String> = getKey<Map<String, String>>(USER_KEY) ?: emptyMap()
    fun adminMap(): Map<String, String> = getKey<Map<String, String>>(ADMIN_KEY) ?: emptyMap()
    private fun history(): Map<String, List<String>> =
        getKey<Map<String, List<String>>>(HISTORY_KEY) ?: emptyMap()

    var autoCheck: Boolean
        get() = getKey<Boolean>(AUTO_CHECK_KEY) ?: true
        set(v) { setKey(AUTO_CHECK_KEY, v) }

    fun originalOf(api: MainAPI): String = originals[keyOf(api)] ?: api.mainUrl

    /** Admin addresses are keyed by provider name or class name, any case. */
    fun adminUrlFor(api: MainAPI): String? {
        val m = adminMap()
        if (m.isEmpty()) return null
        val names = setOf(api.name.lowercase(), api.javaClass.simpleName.lowercase(), keyOf(api).lowercase())
        return m.entries.firstOrNull { it.key.trim().lowercase() in names }?.value?.let(::clean)
            ?.takeIf { it.startsWith("http") }
    }

    fun userUrlFor(api: MainAPI): String? = userMap()[keyOf(api)]

    fun effectiveUrl(api: MainAPI): String =
        userUrlFor(api) ?: adminUrlFor(api) ?: originalOf(api)

    fun install() {
        APIHolder.mainUrlOverride = { api ->
            val key = keyOf(api)
            originals.putIfAbsent(key, clean(api.mainUrl))
            if (isEditable(api)) (userMap()[key] ?: adminUrlFor(api)) else null
        }
    }

    /** Only real CloudStream plugins with a web address are listed. */
    fun isEditable(api: MainAPI): Boolean {
        val pkg = api.javaClass.name
        if (pkg.startsWith("com.lagradost.cloudstream3.stremio") ||
            pkg.startsWith("com.lagradost.cloudstream3.nuvio") ||
            pkg.startsWith("com.lagradost.cloudstream3.sky")
        ) return false
        return api.mainUrl.startsWith("http")
    }

    fun editableApis(): List<MainAPI> =
        APIHolder.allProviders.withLock { APIHolder.allProviders.toList() }
            .filter { isEditable(it) }
            .distinctBy { keyOf(it) }
            .sortedBy { it.name.lowercase() }

    private fun remember(key: String, vararg urls: String?) {
        val h = history().toMutableMap()
        val list = (h[key] ?: emptyList()).toMutableList()
        urls.filterNotNull().map(::clean).forEach { if (it !in list) list.add(it) }
        h[key] = list.takeLast(20)
        setKey(HISTORY_KEY, h)
    }

    /** Sets (or with null clears) the user's address for [api] and applies it now. */
    fun setUserUrl(api: MainAPI, url: String?) {
        val key = keyOf(api)
        val before = api.mainUrl
        val m = userMap().toMutableMap()
        val value = url?.let(::clean)?.takeIf { it.isNotBlank() }
        if (value == null || value == originalOf(api)) m.remove(key) else m[key] = value
        setKey(USER_KEY, m)
        remember(key, originalOf(api), before, value)
        reapply()
    }

    fun clearAllUser() {
        editableApis().forEach { remember(keyOf(it), originalOf(it), it.mainUrl) }
        setKey(USER_KEY, emptyMap<String, String>())
        reapply()
    }

    fun setAdminMap(map: Map<String, String>) {
        if (map == adminMap()) return
        editableApis().forEach { remember(keyOf(it), originalOf(it), it.mainUrl) }
        setKey(ADMIN_KEY, map)
        reapply()
    }

    /** Pushes the effective address into every live provider instance. */
    fun reapply() {
        APIHolder.allProviders.withLock { APIHolder.allProviders.toList() }
            .filter { isEditable(it) || originals.containsKey(keyOf(it)) }
            .forEach { api ->
                originals.putIfAbsent(keyOf(api), clean(api.mainUrl))
                try { api.mainUrl = effectiveUrl(api) } catch (_: Throwable) { }
            }
    }

    /** Replaces an old domain of this provider inside [data] with the current one. */
    fun rewrite(api: MainAPI, data: String): String {
        if (!data.contains("http")) return data
        val now = clean(api.mainUrl)
        val olds = (history()[keyOf(api)] ?: emptyList()) + listOfNotNull(originals[keyOf(api)])
        var out = data
        olds.distinct().filter { it != now && it.startsWith("http") }.forEach { old ->
            if (out.contains(old)) out = out.replace(old, now)
        }
        return out
    }

    // ------------------------------------------------------------------
    // Finding a moved domain
    // ------------------------------------------------------------------

    enum class Source { REDIRECT, NUMBER, TLD, ADMIN }
    data class Candidate(val url: String, val source: Source, val ms: Long)
    enum class Status { OK, DOWN, PROTECTED }
    data class CheckResult(val status: Status, val candidates: List<Candidate>)

    private val TLDS = listOf(
        ".com", ".net", ".org", ".tv", ".to", ".co", ".me", ".cc", ".live", ".xyz",
        ".site", ".pro", ".nl", ".de", ".ws", ".io", ".club", ".top", ".sh", ".pw",
        ".app", ".vip", ".lol", ".info", ".biz", ".mx", ".link", ".fun", ".cx"
    )

    private val URL_PARTS = Regex("""^(https?://)([^/]+?)(\.[a-z0-9-]+)(/.*)?$""", RegexOption.IGNORE_CASE)

    /** "https://www.dizipal1103.com" -> ("https://www.dizipal1103", ".com"). */
    fun splitTld(url: String): Pair<String, String> {
        val m = URL_PARTS.find(clean(url)) ?: return clean(url) to ""
        return (m.groupValues[1] + m.groupValues[2]) to m.groupValues[3]
    }

    private fun label(url: String): String =
        splitTld(url).first.substringAfter("://").removePrefix("www.").substringAfterLast('.')
            .replace(Regex("""\d+$"""), "").lowercase()

    private fun hostOf(url: String) = clean(url).substringAfter("://").substringBefore('/').lowercase()

    private fun looksLike(api: MainAPI, ref: String, body: String): Boolean {
        val b = body.lowercase().replace(" ", "")
        val n = api.name.lowercase().replace(" ", "")
        val l = label(ref)
        return (n.length >= 3 && b.contains(n)) || (l.length >= 3 && b.contains(l))
    }

    private data class Probe(val code: Int, val finalUrl: String, val body: String, val ms: Long, val cf: Boolean)

    private suspend fun probe(url: String): Probe? = try {
        val t = System.currentTimeMillis()
        val r = app.get(url, timeout = 8L, cacheTime = 0)
        val server = r.headers["server"]?.lowercase() ?: ""
        Probe(
            r.code,
            r.okhttpResponse.request.url.toString(),
            r.text.take(200_000),
            System.currentTimeMillis() - t,
            server.contains("cloudflare") && (r.code == 403 || r.code == 503)
        )
    } catch (_: Throwable) {
        null
    }

    private fun originOf(url: String): String {
        val u = clean(url)
        val scheme = u.substringBefore("://")
        return "$scheme://" + u.substringAfter("://").substringBefore('/')
    }

    /** Last check result per provider class (in memory, shown on the screen). */
    val lastResults = ConcurrentHashMap<String, CheckResult>()

    suspend fun checkOne(api: MainAPI): CheckResult =
        check(api).also { lastResults[keyOf(api)] = it }

    /** Checks every editable plugin, a few at a time. Returns how many moved with a new address found. */
    suspend fun checkAll(): Int {
        editableApis().chunked(6).forEach { batch -> batch.amap { checkOne(it) } }
        return editableApis().count { api ->
            val r = lastResults[keyOf(api)]
            r != null && r.status == Status.DOWN && r.candidates.any { it.url != clean(api.mainUrl) }
        }
    }

    /** Milliseconds until [url] answered with a non-error page, or null. */
    suspend fun ping(url: String): Long? {
        val p = probe(url) ?: return null
        return if (p.code < 400 && !p.cf) p.ms else null
    }

    suspend fun check(api: MainAPI): CheckResult {
        val current = clean(api.mainUrl)
        val found = mutableListOf<Candidate>()

        adminUrlFor(api)?.takeIf { it != current }?.let { found.add(Candidate(it, Source.ADMIN, 0)) }

        val p = probe(current)
        val status = when {
            p == null -> Status.DOWN
            p.cf -> Status.PROTECTED
            p.code >= 400 -> Status.DOWN
            hostOf(p.finalUrl) != hostOf(current) -> {
                // Moved: the old address forwards somewhere else.
                if (looksLike(api, current, p.body))
                    found.add(Candidate(originOf(p.finalUrl), Source.REDIRECT, p.ms))
                Status.DOWN
            }
            looksLike(api, current, p.body) -> Status.OK
            else -> Status.DOWN
        }
        if (status != Status.DOWN) return CheckResult(status, found.distinctBy { it.url })

        val (base, tld) = splitTld(current)
        val guesses = mutableListOf<Pair<String, Source>>()
        Regex("""^(.*?)(\d+)$""").find(base)?.let { m ->
            val head = m.groupValues[1]
            val num = m.groupValues[2]
            val n = num.toLongOrNull()
            if (n != null) for (i in 1..6) {
                guesses.add((head + (n + i).toString().padStart(num.length, '0') + tld) to Source.NUMBER)
            }
        }
        TLDS.filter { it != tld.lowercase() }.forEach { guesses.add((base + it) to Source.TLD) }

        found += guesses.amap { (url, src) ->
            val r = probe(url) ?: return@amap null
            if (r.code >= 400 || r.cf) return@amap null
            if (!looksLike(api, current, r.body)) return@amap null
            Candidate(originOf(r.finalUrl), if (hostOf(r.finalUrl) != hostOf(url)) Source.REDIRECT else src, r.ms)
        }.filterNotNull()

        return CheckResult(
            Status.DOWN,
            found.distinctBy { it.url }.sortedWith(compareBy({ it.source.ordinal.let { o -> if (it.source == Source.ADMIN) -1 else o } }, { it.ms }))
        )
    }
}
