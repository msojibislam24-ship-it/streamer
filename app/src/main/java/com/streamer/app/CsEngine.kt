package com.streamer.app

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import com.lagradost.api.setContext
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.InternalAPI
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.MovieLoadResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.plugins.PluginManager
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.nicehttp.Requests
import com.lagradost.nicehttp.ResponseParser
import dalvik.system.PathClassLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import kotlin.reflect.KClass

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

/**
 * Bootstraps the CloudStream runtime pieces that extensions expect but the
 * published library does not provide (app-module classes), and wires the
 * library's global HTTP client to the host client so extensions share the
 * WebView cookie jar (Cloudflare solutions solved in the host UI keep working
 * inside providers).
 */
object CsRuntime {
    @Volatile
    private var initialized = false
    private val lock = Any()

    fun init(ctx: Context) {
        if (initialized) return
        synchronized(lock) {
            if (initialized) return
            initialized = true

            val appCtx = ctx.applicationContext
            runCatching { setContext(appCtx) }
            runCatching { CloudStreamApp.init(appCtx) }

            // Track the foreground activity for extension toasts/dialogs.
            runCatching {
                (appCtx as? Application)?.registerActivityLifecycleCallbacks(
                    object : Application.ActivityLifecycleCallbacks {
                        override fun onActivityResumed(activity: Activity) {
                            CommonActivity.setActivityInstance(activity)
                        }

                        override fun onActivityPaused(activity: Activity) {}
                        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
                        override fun onActivityStarted(activity: Activity) {}
                        override fun onActivityStopped(activity: Activity) {}
                        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
                        override fun onActivityDestroyed(activity: Activity) {}
                    }
                )
            }

            // Swap the library's default networking for the host client, so
            // extension requests share cookies (Cloudflare) and sane timeouts.
            runCatching {
                val parser = object : ResponseParser {
                    @OptIn(InternalAPI::class)
                    override fun <T : Any> parse(text: String, kClass: KClass<T>): T =
                        AppUtils.parseJson(text, kClass)

                    override fun <T : Any> parseSafe(text: String, kClass: KClass<T>): T? =
                        runCatching { parse(text, kClass) }.getOrNull()

                    @OptIn(InternalAPI::class)
                    override fun writeValueAsString(obj: Any): String =
                        with(AppUtils) { obj.toJson() }
                }
                val client = Engine.http.newBuilder()
                    .connectTimeout(20, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .build()
                app = Requests(
                    baseClient = client,
                    responseParser = parser
                ).apply {
                    defaultHeaders = mapOf("user-agent" to Engine.userAgent)
                }
            }

            // Let the PluginManager compat shim load files through the host loader.
            PluginManager.externalLoader = { context, internalName, file ->
                runCatching {
                    CsEngine.loadCs3File(context, internalName, file)
                    true
                }.getOrDefault(false)
            }
        }
    }
}

object CsEngine {
    val loadedProviders = mutableListOf<CsLoadedProvider>()
    val loadErrors = mutableMapOf<String, String>()

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
                val loc = res.header("Location") ?: error("Shortcode '$trimmed' not found")
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
        runCatching { PluginManager.unloadPlugin(f.absolutePath) }
        if (f.exists()) f.delete()
        loadedProviders.removeAll { it.pluginKey == internalName }
        loadErrors.remove(internalName)
    }

    suspend fun installAndLoad(ctx: Context, plugin: CsRepoPlugin): Result<List<MainAPI>> = withContext(Dispatchers.IO) {
        CsRuntime.init(ctx)
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
        }.onFailure { err ->
            loadErrors[plugin.internalName] = err.javaClass.simpleName + ": " + (err.message ?: "Load failed")
        }
    }

    fun loadAllInstalled(ctx: Context) {
        CsRuntime.init(ctx)
        runCatching { setContext(ctx.applicationContext) }
        val dir = pluginDir(ctx)
        dir.listFiles { f -> f.extension == "cs3" }?.forEach { f ->
            val key = f.nameWithoutExtension
            if (loadedProviders.none { it.pluginKey == key }) {
                runCatching { loadCs3File(ctx, key, f) }
                    .onFailure { err ->
                        loadErrors[key] = err.javaClass.simpleName + ": " + (err.message ?: "Load failed")
                    }
            }
        }
        runCatching {
            com.lagradost.cloudstream3.MainActivity.Companion.afterPluginsLoadedEvent.invoke(false)
        }
    }

    /**
     * Loads a downloaded .cs3 plugin dex, instantiates its Plugin entry class
     * and returns the MainAPI providers it registered.
     */
    fun loadCs3File(ctx: Context, pluginKey: String, file: File): List<MainAPI> {
        CsRuntime.init(ctx)
        runCatching { setContext(ctx.applicationContext) }
        file.setReadOnly()
        val className = ZipFile(file).use { zip ->
            val entry = zip.getEntry("manifest.json") ?: error("No manifest.json in .cs3")
            val txt = zip.getInputStream(entry).bufferedReader().readText()
            JSONObject(txt).optString("pluginClassName").ifBlank { error("Missing pluginClassName") }
        }

        PluginManager.currentlyLoading = pluginKey
        try {
            val before = APIHolder.allProviders.toSet()
            val loader = PathClassLoader(file.absolutePath, ctx.classLoader)
            val cls = Class.forName(className, true, loader)
            val instance = cls.getDeclaredConstructor().newInstance() as? BasePlugin
                ?: error("$className is not a CloudStream Plugin")
            instance.filename = file.absolutePath
            runCatching { PluginManager.registerPlugin(file.absolutePath, instance) }

            when (instance) {
                is Plugin -> instance.load(ctx)
                else -> instance.load()
            }

            val added = APIHolder.allProviders.filter { it !in before }
            runCatching { APIHolder.initAll() }

            loadedProviders.removeAll { it.pluginKey == pluginKey }
            if (added.isEmpty()) {
                loadErrors[pluginKey] = "0 providers registered (may be an extractor-only plugin)"
            } else {
                loadErrors.remove(pluginKey)
                added.forEach { api -> loadedProviders += CsLoadedProvider(pluginKey, api) }
            }
            return added
        } finally {
            PluginManager.currentlyLoading = null
        }
    }

    suspend fun getMainSections(api: MainAPI, page: Int = 1): Pair<List<Pair<String, List<SearchResponse>>>, String?> = withContext(Dispatchers.IO) {
        val pages = api.mainPage
        if (pages.isEmpty() || api.mainUrl == "NONE") {
            return@withContext emptyList<Pair<String, List<SearchResponse>>>() to "${api.name} has no home catalog (try Search or install a movie/anime provider like MultiMovies / HiAnime / StreamPlay)."
        }
        val out = mutableListOf<Pair<String, List<SearchResponse>>>()
        var lastErr: String? = null
        for (mp in pages.take(6)) {
            runCatching {
                val res = api.getMainPage(page, MainPageRequest(mp.name, mp.data, false))
                res?.items?.forEach { hl -> if (hl.list.isNotEmpty()) out += hl.name to hl.list }
            }.onFailure { e ->
                lastErr = e.message ?: e.javaClass.simpleName
            }
        }
        out to (if (out.isEmpty()) lastErr ?: "No items returned" else null)
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

    // Automatically searches installed .cs3 providers by title when tapping Find Streams on Home/Cinemeta
    suspend fun findStreamsFromProviders(meta: MetaItem, season: Int, episode: Int): Pair<List<StreamItem>, List<SubItem>> = coroutineScope {
        val activeApis = loadedProviders.map { it.api }.filter { it.mainUrl != "NONE" && it.mainUrl.isNotBlank() }.take(6)
        if (activeApis.isEmpty()) return@coroutineScope emptyList<StreamItem>() to emptyList<SubItem>()
        val cleanTitle = meta.name.lowercase().replace(Regex("[^a-z0-9 ]"), " ").trim()
        val jobs = activeApis.map { api ->
            async(Dispatchers.IO) {
                withTimeoutOrNull(12_000L) {
                    val hits = search(api, meta.name)
                    val match = hits.firstOrNull {
                        it.name.lowercase().replace(Regex("[^a-z0-9 ]"), " ").trim() == cleanTitle
                    } ?: hits.firstOrNull {
                        it.name.contains(meta.name, ignoreCase = true)
                    } ?: return@withTimeoutOrNull null

                    val detail = loadDetail(api, match.url) ?: return@withTimeoutOrNull null
                    val targetEp = if (meta.type == "series" && !detail.isMovie) {
                        detail.episodes.firstOrNull { it.season == season && it.episode == episode }
                            ?: detail.episodes.firstOrNull { it.episode == episode }
                    } else {
                        detail.episodes.firstOrNull()
                    } ?: return@withTimeoutOrNull null

                    loadLinks(api, targetEp.id)
                }
            }
        }
        val allStreams = mutableListOf<StreamItem>()
        val allSubs = mutableListOf<SubItem>()
        jobs.forEach { job ->
            job.await()?.let { (ss, sb) ->
                allStreams += ss
                allSubs += sb
            }
        }
        allStreams.distinctBy { it.url } to allSubs.distinctBy { it.url }
    }
}
