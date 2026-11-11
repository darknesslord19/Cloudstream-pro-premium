package com.lagradost.cloudstream3.plugins

import android.app.Activity
import android.content.Context
import android.util.Log
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.MainActivity.Companion.afterPluginsLoadedEvent
import com.lagradost.cloudstream3.PROVIDER_STATUS_DOWN
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.plugins.PluginManager.getPluginPath
import com.lagradost.cloudstream3.ui.settings.extensions.RepositoryData
import com.lagradost.cloudstream3.utils.Coroutines.main
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.util.Collections

/**
 * CloudStream Dark: admin-managed repository list.
 *
 * On every start the app reads [LIST_URL] (a Firebase Realtime Database
 * value). Every repository link in it is added to the user's repositories
 * and all of its plugins are installed.
 *
 * Accepted formats:
 *  - `{"silinemez": true, "depolar": ["https://…/repo.json", …]}`
 *    `silinemez` (also `locked`/`kilitli`) is the on/off switch. Each entry
 *    may also be an object `{"url": "…", "silinemez": false}` to override it.
 *  - a plain JSON array / string of links (treated as `silinemez: false`).
 *
 * Locked (silinemez) repos are pinned to the top of the repository list,
 * cannot be removed (the user gets a toast), their plugins cannot be
 * removed either, and anything missing is re-added/re-installed on every
 * start. Unlocked repos are auto-added only once: if the user removes one
 * it is not forced back.
 */
object AdminRepos {
    private const val TAG = "AdminRepos"
    const val LIST_URL =
        "https://darknes-lord-paste-default-rtdb.firebaseio.com/pastes/Csadmineklenti.json"

    private const val SEEN_REPOS_KEY = "DARK_ADMIN_SEEN_REPOS"
    private const val SEEN_PLUGINS_KEY = "DARK_ADMIN_SEEN_PLUGINS"
    private const val LOCKED_REPOS_KEY = "DARK_ADMIN_LOCKED_REPOS"

    data class Entry(val link: String, val locked: Boolean)

    private val LINK_REGEX =
        Regex("""(?:https?|cloudstreamrepo)://[^\s"'<>,\\\[\]{}]+""", RegexOption.IGNORE_CASE)

    private val LOCK_NAMES = listOf("silinemez", "locked", "kilitli", "lock")
    private val LIST_NAMES = listOf("depolar", "repos", "repolar", "links", "linkler")

    private fun links(text: String): List<String> =
        LINK_REGEX.findAll(text.replace("\\/", "/").replace("\\n", "\n"))
            .map { it.value.trim().trimEnd('.', ';', ')') }
            .filter { it.length > 10 }
            .toList()

    private fun JSONObject.lockFlag(): Boolean? =
        LOCK_NAMES.firstOrNull { has(it) }?.let { name ->
            when (val v = opt(name)) {
                is Boolean -> v
                is Number -> v.toInt() != 0
                is String -> v.trim().lowercase() in setOf("true", "1", "evet", "acik", "açık", "on")
                else -> null
            }
        }

    private fun fromArray(arr: JSONArray, locked: Boolean, out: MutableList<Entry>) {
        for (i in 0 until arr.length()) {
            when (val item = arr.opt(i)) {
                is String -> links(item).forEach { out.add(Entry(it, locked)) }
                is JSONObject -> {
                    val itemLocked = item.lockFlag() ?: locked
                    val url = item.optString("url").ifBlank { item.optString("link") }
                    if (url.isNotBlank()) links(url).forEach { out.add(Entry(it, itemLocked)) }
                    else fromObject(item, itemLocked, out)
                }
                is JSONArray -> fromArray(item, locked, out)
            }
        }
    }

    private fun fromObject(obj: JSONObject, inherited: Boolean, out: MutableList<Entry>) {
        val locked = obj.lockFlag() ?: inherited
        val listName = LIST_NAMES.firstOrNull { obj.has(it) }
        if (listName != null) {
            when (val v = obj.opt(listName)) {
                is JSONArray -> fromArray(v, locked, out)
                is JSONObject -> fromObject(v, locked, out)
                is String -> links(v).forEach { out.add(Entry(it, locked)) }
            }
            return
        }
        // Firebase stores lists added by hand as {"0": "...", "1": "..."}.
        obj.keys().forEach { k ->
            if (k in LOCK_NAMES) return@forEach
            when (val v = obj.opt(k)) {
                is String -> links(v).forEach { out.add(Entry(it, locked)) }
                is JSONArray -> fromArray(v, locked, out)
                is JSONObject -> fromObject(v, locked, out)
            }
        }
    }

    fun parse(body: String): List<Entry> {
        val out = mutableListOf<Entry>()
        try {
            when (val v = JSONTokener(body.trim()).nextValue()) {
                is JSONObject -> fromObject(v, false, out)
                is JSONArray -> fromArray(v, false, out)
                is String -> links(v).forEach { out.add(Entry(it, false)) }
            }
        } catch (_: Throwable) {
            links(body).forEach { out.add(Entry(it, false)) }
        }
        // A link listed twice is locked if any of its entries is.
        return out.groupBy { it.link }.map { (link, e) -> Entry(link, e.any { it.locked }) }
    }

    // ---- lock state (cached, readable from the UI thread) ----

    fun lockedRepoUrls(): Set<String> =
        (getKey<Array<String>>(LOCKED_REPOS_KEY) ?: emptyArray()).toSet()

    fun isLocked(repoUrl: String?): Boolean =
        repoUrl != null && lockedRepoUrls().contains(repoUrl)

    /** True when [file] is a plugin file inside a locked repository's folder. */
    fun isLockedFile(context: Context, file: File): Boolean {
        val dir = file.absoluteFile.parentFile ?: return false
        return lockedRepoUrls().any { url ->
            getPluginPath(context, "x", url).absoluteFile.parentFile == dir
        }
    }

    fun showLockedToast() {
        main { showToast(R.string.admin_repo_locked) }
    }

    private fun keyOf(w: PluginWrapper) = w.repositoryData.url + "|" + w.plugin.internalName

    suspend fun sync(activity: Activity) {
        try {
            val body = app.get(LIST_URL, cacheTime = 0).text
            val entries = parse(body)

            val seenRepos = Collections.synchronizedSet(
                (getKey<Array<String>>(SEEN_REPOS_KEY) ?: emptyArray()).toMutableSet()
            )
            val seenPlugins = Collections.synchronizedSet(
                (getKey<Array<String>>(SEEN_PLUGINS_KEY) ?: emptyArray()).toMutableSet()
            )
            val lockedUrls = Collections.synchronizedSet(mutableSetOf<String>())
            var changed = false

            val repos = entries.amap { entry ->
                val url = RepositoryManager.parseRepoUrl(entry.link) ?: return@amap null
                if (entry.locked) lockedUrls.add(url)
                val existing = RepositoryManager.getRepositories().firstOrNull { it.url == url }
                if (existing != null) {
                    seenRepos.add(url)
                    return@amap existing to entry.locked
                }
                // Unlocked + removed by the user after an earlier auto-add: respect that.
                if (!entry.locked && seenRepos.contains(url)) return@amap null
                val repo = RepositoryManager.parseRepository(url) ?: return@amap null
                val data = RepositoryData(repo.iconUrl, repo.name, url)
                RepositoryManager.addRepository(data)
                seenRepos.add(url)
                changed = true
                data to entry.locked
            }.filterNotNull()

            // Store the lock state first so the UI reflects it immediately.
            if (lockedUrls != lockedRepoUrls()) changed = true
            setKey(LOCKED_REPOS_KEY, lockedUrls.toTypedArray())

            val toInstall = repos.amap { (repo, locked) ->
                (RepositoryManager.getRepoPlugins(repo) ?: emptyList()).map { it to locked }
            }
                .flatten()
                .distinctBy { it.first.plugin.url }
                .filter { (w, locked) ->
                    if (w.plugin.url.isBlank()) return@filter false
                    if (getPluginPath(activity, w.plugin.internalName, w.repositoryData.url).exists()) {
                        seenPlugins.add(keyOf(w))
                        return@filter false
                    }
                    // Locked repos: always reinstall. Unlocked: only once.
                    locked || !seenPlugins.contains(keyOf(w))
                }
                .map { it.first }

            val installed = toInstall.amap { w ->
                val ok = PluginManager.downloadPlugin(
                    activity,
                    w.plugin.url,
                    w.plugin.fileHash,
                    w.plugin.internalName,
                    w.repositoryData.url,
                    w.plugin.status != PROVIDER_STATUS_DOWN
                )
                // Only remember successful installs, so a failed download
                // is retried on the next start.
                if (ok) seenPlugins.add(keyOf(w))
                ok
            }.count { it }

            setKey(SEEN_REPOS_KEY, seenRepos.toTypedArray())
            setKey(SEEN_PLUGINS_KEY, seenPlugins.toTypedArray())
            Log.i(TAG, "repos=${repos.size} locked=${lockedUrls.size} installed=$installed")
            if (changed || installed > 0) afterPluginsLoadedEvent.invoke(false)
        } catch (t: Throwable) {
            logError(t)
        }
    }
}
