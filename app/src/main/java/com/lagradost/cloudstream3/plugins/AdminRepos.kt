package com.lagradost.cloudstream3.plugins

import android.app.Activity
import android.util.Log
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.MainActivity.Companion.afterPluginsLoadedEvent
import com.lagradost.cloudstream3.PROVIDER_STATUS_DOWN
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.plugins.PluginManager.getPluginPath
import com.lagradost.cloudstream3.ui.settings.extensions.RepositoryData

/**
 * CloudStream Dark: admin-managed repository list.
 *
 * On every start the app reads [LIST_URL] (a Firebase Realtime Database
 * value). Every repository link found in it is added to the user's
 * repositories and all of its plugins are installed, so the repos show up
 * as "added + installed" without the user doing anything.
 *
 * The value may be a plain string (one link per line, or separated by
 * spaces/commas), a JSON array, or a JSON object — every
 * `https://…` / `cloudstreamrepo://…` link inside it is used.
 *
 * Each repository and plugin is auto-added only ONCE: if the user removes
 * one later it is not forced back on the next start. Plugins the admin adds
 * to a listed repository later are still installed automatically.
 */
object AdminRepos {
    private const val TAG = "AdminRepos"
    const val LIST_URL =
        "https://darknes-lord-paste-default-rtdb.firebaseio.com/pastes/Csadmineklenti.json"

    private const val SEEN_REPOS_KEY = "DARK_ADMIN_SEEN_REPOS"
    private const val SEEN_PLUGINS_KEY = "DARK_ADMIN_SEEN_PLUGINS"

    private val LINK_REGEX =
        Regex("""(?:https?|cloudstreamrepo)://[^\s"'<>,\\\[\]{}]+""", RegexOption.IGNORE_CASE)

    /** Extracts repository links from whatever the database value contains. */
    fun extractLinks(body: String): List<String> =
        LINK_REGEX.findAll(body.replace("\\/", "/").replace("\\n", "\n"))
            .map { it.value.trim().trimEnd('.', ';', ')') }
            .filter { it.length > 10 }
            .distinct()
            .toList()

    private fun keyOf(w: PluginWrapper) = w.repositoryData.url + "|" + w.plugin.internalName

    suspend fun sync(activity: Activity) {
        try {
            val body = app.get(LIST_URL, cacheTime = 0).text
            val links = extractLinks(body)
            if (links.isEmpty()) return

            val seenRepos = (getKey<Array<String>>(SEEN_REPOS_KEY) ?: emptyArray()).toMutableSet()
            val seenPlugins =
                (getKey<Array<String>>(SEEN_PLUGINS_KEY) ?: emptyArray()).toMutableSet()
            var changed = false

            val repos = links.amap { link ->
                val url = RepositoryManager.parseRepoUrl(link) ?: return@amap null
                val existing = RepositoryManager.getRepositories().firstOrNull { it.url == url }
                if (existing != null) {
                    seenRepos.add(url)
                    return@amap existing
                }
                // Removed by the user after an earlier auto-add: respect that.
                if (seenRepos.contains(url)) return@amap null
                val repo = RepositoryManager.parseRepository(url) ?: return@amap null
                val data = RepositoryData(repo.iconUrl, repo.name, url)
                RepositoryManager.addRepository(data)
                seenRepos.add(url)
                changed = true
                data
            }.filterNotNull()

            val toInstall = repos.amap { RepositoryManager.getRepoPlugins(it) ?: emptyList() }
                .flatten()
                .distinctBy { it.plugin.url }
                .filter { w ->
                    val key = keyOf(w)
                    if (w.plugin.url.isBlank() || seenPlugins.contains(key)) return@filter false
                    if (getPluginPath(activity, w.plugin.internalName, w.repositoryData.url).exists()) {
                        seenPlugins.add(key)
                        return@filter false
                    }
                    true
                }

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
                if (ok) synchronized(seenPlugins) { seenPlugins.add(keyOf(w)) }
                ok
            }.count { it }

            setKey(SEEN_REPOS_KEY, seenRepos.toTypedArray())
            setKey(SEEN_PLUGINS_KEY, seenPlugins.toTypedArray())
            Log.i(TAG, "repos=${repos.size} newRepo=$changed installed=$installed")
            if (changed || installed > 0) afterPluginsLoadedEvent.invoke(false)
        } catch (t: Throwable) {
            logError(t)
        }
    }
}
