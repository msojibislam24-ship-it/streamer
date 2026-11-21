package com.streamer.app

import android.content.Context
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.ExtractorLink
import dalvik.system.PathClassLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipFile

data class CsRepoPlugin(
    val name: String,
    val internalName: String,
    val url: String,
    val version: Int,
    val tvTypes: List<String>,
    val iconUrl: String?,
    val description: String?,
    val repoUrl: String
)

data class CsLoadedProvider(
    val pluginKey: String,
    val api: MainAPI
)

data class CsMediaDetail(
    val name: String,
    val url: String,
    val posterUrl: String?,
    val backgroundUrl: String?,
    val plot: String?,
    val year: Int?,
    val score: String?,
    val tags: List<String>,
    val isMovie: Boolean,
    val episodes: List<VideoEp>
)

object CsEngine {
    val loadedProviders = mutableListOf<CsLoadedProvider>()

    suspend fun resolveRepoInput(raw: String): String = withContext(Dispatchers.IO) {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) error("Empty input")
        if (!trimmed.contains("://") && !trimmed.contains("/")) {
            val noRedirectClient = Engine.http.newBuilder()
                .followRedirects(false)
                .followSslRedirects(false)
                .build()
            val req = Request.Builder()
                .url("https://cutt.ly/$trimmed")
                .header("User-Agent", Engine.userAgent)
                .build()
            noRedirectClient.newCall(req).execute().use { res ->
                val loc = res.header("Location")
                    ?: error("Shortcode '$trimmed' not found")
                if (loc.startsWith("https://cutt.ly/404") || loc.trimEnd('/') == "https://cutt.ly") {
                    error("Invalid shortcode '$trimmed'")
                }
                return@withContext loc.replace(Regex("^cloudstreamrepo://", RegexOption.IGNORE_CASE), "https://")
            }
        }
        trimmed.replace(Regex("^cloudstreamrepo://", RegexOption.IGNORE_CASE), "https://")
    }

    suspend fun fetchRepoPlugins(rawInput: String): Pair<String, List<CsRepoPlugin>> = withContext(Dispatchers.IO) {
        val repoUrl = resolveRepoInput(rawInput)
        val j = JSONObject(Engine.fetchText(repoUrl))
        val repoName = j.optString("name").ifBlank { "CloudStream Repo" }
        val lists = j.optJSONArray("pluginLists") ?: JSONArray()
        val out = mutableListOf<CsRepoPlugin>()
        for (i in 0 until lists.length()) {
            val listUrl = lists.optString(i).ifBlank { continue }
            runCatching {
                val arr = JSONArray(Engine.fetchText(listUrl))
                for (k in 0 until arr.length()) {
                    val p = arr.optJSONObject(k) ?: continue
                    val u = p.optString("url")
                    if (u.isBlank() || p.optInt("status", 1) == 0) continue
                    val typesArr = p.optJSONArray("tvTypes") ?: JSONArray()
                    val types = List(typesArr.length()) { typesArr.optString(it) }.filter { it.isNotBlank() }
                    out += CsRepoPlugin(
                        name = p.optString("name").ifBlank { p.optString("internalName") },
                        internalName = p.optString("internalName").ifBlank { p.optString("name") },
                        url = u,
                        version = p.optInt("version", 1),
                        tvTypes = types,
                        iconUrl = p.optString("iconUrl").ifBlank { null },
                        description = p.optString("description").ifBlank { null },
                        repoUrl = repoUrl
                    )
                }
            }
        }
        repoName to out
    }

    private fun pluginDir(ctx: Context): File = File(ctx.filesDir, "cs3_plugins").apply { mkdirs() }

    fun pluginFile(ctx: Context, internalName: String): File {
        val safe = internalName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        return File(pluginDir(ctx), "$safe.cs3")
    }

    fun isInstalled(ctx: Context, internalName: String): Boolean = pluginFile(ctx, internalName).exists()

    fun deletePlugin(ctx: Context, internalName: String) {
        val f = pluginFile(ctx, internalName)
        if (f.exists()) f.delete()
        loadedProviders.removeAll { it.pluginKey == internalName }
    }

    suspend fun installAndLoad(ctx: Context, plugin: CsRepoPlugin): Result<List<MainAPI>> = withContext(Dispatchers.IO) {
        runCatching {
            val file = pluginFile(ctx, plugin.internalName)
            if (file.exists()) file.setWritable(true)
            val req = Request.Builder().url(plugin.url).header("User-Agent", Engine.userAgent).build()
            Engine.http.newCall(req).execute().use { res ->
                if (!res.isSuccessful) error("HTTP ${res.code} downloading ${plugin.name}")
                val bytes = res.body?.bytes() ?: error("Empty .cs3 file")
                file.writeBytes(bytes)
            }
            loadCs3File(ctx, plugin.internalName, file)
        }
    }

    fun loadAllInstalled(ctx: Context) {
        val dir = pluginDir(ctx)
        dir.listFiles { f -> f.extension == "cs3" }?.forEach { f ->
            val key = f.nameWithoutExtension
            if (loadedProviders.none { it.pluginKey == key }) {
                runCatching { loadCs3File(ctx, key, f) }
            }
        }
    }

    private fun loadCs3File(ctx: Context, pluginKey: String, file: File): List<MainAPI> {
        file.setReadOnly()
        val className = ZipFile(file).use { zip ->
            val entry = zip.getEntry("manifest.json") ?: error("No manifest.json in .cs3")
            val txt = zip.getInputStream(entry).bufferedReader().readText()
            JSONObject(txt).optString("pluginClassName").ifBlank { error("Missing pluginClassName") }
        }

        val before = APIHolder.allProviders.toSet()
        val loader = PathClassLoader(file.absolutePath, ctx.classLoader)
        val cls = Class.forName(className, true, loader)
        val instance = cls.getDeclaredConstructor().newInstance() as? Plugin
            ?: error("$className is not a CloudStream Plugin")
        instance.load(ctx)

        val added = APIHolder.allProviders.filter { it !in before }
        runCatching { APIHolder.initAll() }

        loadedProviders.removeAll { it.pluginKey == pluginKey }
        added.forEach { api ->
            loadedProviders += CsLoadedProvider(pluginKey, api)
        }
        return added
    }

    suspend fun getMainSections(api: MainAPI, page: Int = 1): List<Pair<String, List<SearchResponse>>> = withContext(Dispatchers.IO) {
        val pages = api.mainPage
        val out = mutableListOf<Pair<String, List<SearchResponse>>>()
        for (mp in pages.take(6)) {
            runCatching {
                val res = api.getMainPage(page, MainPageRequest(mp.name, mp.data, false))
                res?.items?.forEach { hl ->
                    if (hl.list.isNotEmpty()) out += hl.name to hl.list
                }
            }
        }
        out
    }

    suspend fun search(api: MainAPI, query: String): List<SearchResponse> = withContext(Dispatchers.IO) {
        runCatching { api.search(query).orEmpty() }.getOrDefault(emptyList())
    }

    suspend fun loadDetail(api: MainAPI, url: String): CsMediaDetail? = withContext(Dispatchers.IO) {
        val lr = api.load(url) ?: return@withContext null
        val eps = when (lr) {
            is MovieLoadResponse -> listOf(VideoEp(id = lr.dataUrl, title = lr.name, season = 1, episode = 1))
            is TvSeriesLoadResponse -> lr.episodes.mapIndexed { idx, ep ->
                VideoEp(
                    id = ep.data,
                    title = ep.name ?: "Episode ${ep.episode ?: (idx + 1)}",
                    season = ep.season ?: 1,
                    episode = ep.episode ?: (idx + 1),
                    thumbnail = ep.posterUrl,
                    overview = ep.description
                )
            }
            is AnimeLoadResponse -> lr.episodes.entries.flatMap { (dub, list) ->
                list.mapIndexed { idx, ep ->
                    VideoEp(
                        id = ep.data,
                        title = "[${dub.name}] ${ep.name ?: "Episode ${ep.episode ?: (idx + 1)}"}",
                        season = ep.season ?: 1,
                        episode = ep.episode ?: (idx + 1),
                        thumbnail = ep.posterUrl,
                        overview = ep.description
                    )
                }
            }
            else -> emptyList()
        }
        CsMediaDetail(
            name = lr.name,
            url = lr.url,
            posterUrl = lr.posterUrl,
            backgroundUrl = lr.backgroundPosterUrl ?: lr.posterUrl,
            plot = lr.plot,
            year = lr.year,
            score = lr.score?.toString(),
            tags = lr.tags.orEmpty(),
            isMovie = lr is MovieLoadResponse,
            episodes = eps
        )
    }

    suspend fun loadLinks(api: MainAPI, data: String): Pair<List<StreamItem>, List<SubItem>> = withContext(Dispatchers.IO) {
        val streams = mutableListOf<StreamItem>()
        val subs = mutableListOf<SubItem>()
        runCatching {
            api.loadLinks(
                data = data,
                isCasting = false,
                subtitleCallback = { sub ->
                    if (sub.url.isNotBlank()) subs += SubItem(sub.url, sub.lang, sub.lang)
                },
                callback = { link: ExtractorLink ->
                    val hdrs = HashMap(link.headers)
                    if (link.referer.isNotBlank() && !hdrs.containsKey("Referer") && !hdrs.containsKey("referer")) {
                        hdrs["Referer"] = link.referer
                    }
                    streams += StreamItem(
                        url = link.url,
                        externalUrl = null,
                        title = "${link.source} · ${link.name}",
                        addonName = "CS3: ${api.name}",
                        headers = hdrs
                    )
                }
            )
        }
        streams.distinctBy { it.url } to subs.distinctBy { it.url }
    }
}
