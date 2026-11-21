package com.streamer.app

import android.content.Context
import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class CatalogDef(
    val type: String,
    val id: String,
    val name: String,
    val hasSearch: Boolean,
    val extraRequired: Boolean,
    val genres: List<String> = emptyList()
)

data class Addon(
    val base: String,
    val name: String,
    val resourcesJson: String = "[]",
    val types: List<String> = emptyList(),
    val idPrefixes: List<String>? = null,
    val catalogs: List<CatalogDef> = emptyList(),
    val loaded: Boolean = false
)

data class JsPlugin(
    val id: String,
    val name: String,
    val version: String,
    val scriptUrl: String,
    val repoUrl: String,
    val enabled: Boolean = true
)

data class CsPluginInfo(
    val name: String,
    val url: String,
    val tvTypes: String
)

data class VideoEp(
    val id: String,
    val title: String,
    val season: Int,
    val episode: Int,
    val thumbnail: String? = null,
    val overview: String? = null
)

data class MetaItem(
    val id: String,
    val type: String,
    val name: String,
    val poster: String? = null,
    val background: String? = null,
    val description: String? = null,
    val releaseInfo: String? = null,
    val runtime: String? = null,
    val imdbRating: String? = null,
    val genres: List<String> = emptyList(),
    val videos: List<VideoEp> = emptyList(),
    val source: String? = null,
    val tmdbId: String? = null
)

data class StreamItem(
    val url: String?,
    val externalUrl: String?,
    val title: String,
    val addonName: String,
    val filename: String? = null,
    val notWebReady: Boolean = false,
    val headers: Map<String, String> = emptyMap()
)

data class SubItem(val url: String, val lang: String, val id: String)
data class SubCue(val startMs: Long, val endMs: Long, val text: String)

data class SubPrefs(
    val sizeSp: Int = 18,
    val colorHex: Long = 0xFFFFFFFF,
    val bgStyle: Int = 0, // 0=Box, 1=Outline, 2=Clear
    val bottomDp: Int = 24
)

data class HistoryItem(
    val id: String,
    val type: String,
    val name: String,
    val poster: String?,
    val vid: String,
    val lab: String,
    val posMs: Long,
    val durMs: Long
)

class WebViewCookieJar : CookieJar {
    private val cm: CookieManager? get() = runCatching { CookieManager.getInstance() }.getOrNull()
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val m = cm ?: return
        cookies.forEach { m.setCookie(url.toString(), it.toString()) }
        m.flush()
    }
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val raw = cm?.getCookie(url.toString()) ?: return emptyList()
        return raw.split(";").mapNotNull { part ->
            val eq = part.indexOf('=')
            if (eq <= 0) null else Cookie.Builder()
                .name(part.substring(0, eq).trim())
                .value(part.substring(eq + 1).trim())
                .domain(url.host)
                .build()
        }
    }
}

object Engine {
    @Volatile var userAgent: String = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

    val http: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(WebViewCookieJar())
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    fun toBase(u: String): String = u.trim()
        .replace(Regex("^stremio://", RegexOption.IGNORE_CASE), "https://")
        .replace(Regex("/manifest\\.json.*$", RegexOption.IGNORE_CASE), "")
        .trimEnd('/')

    suspend fun fetchText(url: String): String = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).header("User-Agent", userAgent).build()
        http.newCall(req).execute().use { res ->
            if (!res.isSuccessful) error("HTTP ${res.code}")
            res.body?.string() ?: ""
        }
    }

    suspend fun fetchManifest(base: String): Addon {
        val j = JSONObject(fetchText("$base/manifest.json"))
        if (!j.has("resources") && (j.has("scrapers") || j.has("providers") || j.has("plugins"))) {
            error("JS_REPO")
        }
        val types = j.optJSONArray("types").toStringList()
        val prefixes = j.optJSONArray("idPrefixes")?.toStringList()
        val cats = mutableListOf<CatalogDef>()
        val ca = j.optJSONArray("catalogs") ?: JSONArray()
        for (i in 0 until ca.length()) {
            val c = ca.optJSONObject(i) ?: continue
            val extras = c.optJSONArray("extra") ?: JSONArray()
            var hasSearch = false
            var reqExtra = (c.optJSONArray("extraRequired")?.length() ?: 0) > 0
            val genres = mutableListOf<String>()
            for (k in 0 until extras.length()) {
                val ex = extras.optJSONObject(k) ?: continue
                if (ex.optString("name") == "search") hasSearch = true
                if (ex.optBoolean("isRequired")) reqExtra = true
                if (ex.optString("name") == "genre") {
                    genres += ex.optJSONArray("options").toStringList()
                }
            }
            if ("search" in c.optJSONArray("extraSupported").toStringList()) hasSearch = true
            cats += CatalogDef(
                type = c.optString("type"),
                id = c.optString("id"),
                name = c.optString("name").ifBlank { c.optString("id") },
                hasSearch = hasSearch,
                extraRequired = reqExtra,
                genres = genres
            )
        }
        return Addon(
            base = base,
            name = j.optString("name").ifBlank { base },
            resourcesJson = (j.optJSONArray("resources") ?: JSONArray()).toString(),
            types = types,
            idPrefixes = prefixes,
            catalogs = cats,
            loaded = true
        )
    }

    suspend fun fetchJsRepo(repoUrl: String): List<JsPlugin> {
        val clean = repoUrl.trim()
        val j = JSONObject(fetchText(clean))
        val arr = j.optJSONArray("scrapers") ?: j.optJSONArray("providers") ?: j.optJSONArray("plugins") ?: JSONArray()
        val baseDir = clean.substringBeforeLast('/')
        val out = mutableListOf<JsPlugin>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val fn = o.optString("filename").ifBlank { o.optString("url") }.ifBlank { o.optString("file") }
            if (fn.isBlank()) continue
            val cleanFn = fn.removePrefix("./").trimStart('/')
            val scriptUrl = if (fn.startsWith("http", true)) fn else "$baseDir/$cleanFn"
            out += JsPlugin(
                id = o.optString("id").ifBlank { o.optString("name") },
                name = o.optString("name").ifBlank { o.optString("id") },
                version = o.optString("version", "1.0"),
                scriptUrl = scriptUrl,
                repoUrl = clean,
                enabled = i < 8
            )
        }
        return out
    }

    suspend fun inspectCloudStreamRepo(rawUrl: String): Pair<String, List<CsPluginInfo>> {
        val url = rawUrl.trim().replace(Regex("^cloudstreamrepo://", RegexOption.IGNORE_CASE), "https://")
        val j = JSONObject(fetchText(url))
        val repoName = j.optString("name").ifBlank { "CloudStream Repo" }
        val lists = j.optJSONArray("pluginLists").toStringList()
        val plugins = mutableListOf<CsPluginInfo>()
        for (plUrl in lists.take(3)) {
            runCatching {
                val arr = JSONArray(fetchText(plUrl))
                for (i in 0 until arr.length()) {
                    val p = arr.optJSONObject(i) ?: continue
                    plugins += CsPluginInfo(
                        name = p.optString("name").ifBlank { p.optString("internalName") },
                        url = p.optString("url"),
                        tvTypes = p.optJSONArray("tvTypes").toStringList().joinToString(", ")
                    )
                }
            }
        }
        return repoName to plugins
    }

    fun can(a: Addon, resName: String, type: String? = null, id: String? = null): Boolean {
        val arr = runCatching { JSONArray(a.resourcesJson) }.getOrNull() ?: return false
        for (i in 0 until arr.length()) {
            val raw = arr.opt(i)
            val name = (raw as? String) ?: (raw as? JSONObject)?.optString("name") ?: continue
            if (name != resName) continue
            val obj = raw as? JSONObject
            val rTypes = obj?.optJSONArray("types")?.toStringList() ?: a.types
            val rPre = obj?.optJSONArray("idPrefixes")?.toStringList() ?: a.idPrefixes
            val typeOk = type == null || rTypes.isEmpty() || type in rTypes
            val preOk = rPre == null || id == null || rPre.any { id.startsWith(it) }
            if (typeOk && preOk) return true
        }
        return false
    }

    suspend fun fetchCatalog(
        base: String,
        type: String,
        id: String,
        query: String? = null,
        genre: String? = null,
        skip: Int = 0
    ): List<MetaItem> {
        val extras = mutableListOf<String>()
        if (!query.isNullOrBlank()) extras += "search=${enc(query)}"
        if (!genre.isNullOrBlank() && genre != "All") extras += "genre=${enc(genre)}"
        if (skip > 0) extras += "skip=$skip"
        val path = if (extras.isEmpty()) "/catalog/${enc(type)}/${enc(id)}.json"
        else "/catalog/${enc(type)}/${enc(id)}/${extras.joinToString("&")}.json"
        val j = JSONObject(fetchText(base + path))
        val arr = j.optJSONArray("metas") ?: return emptyList()
        return List(arr.length()) { i -> parseMeta(arr.optJSONObject(i)) }.filterNotNull()
    }

    suspend fun fetchMeta(base: String, type: String, id: String): MetaItem? {
        val j = JSONObject(fetchText("$base/meta/${enc(type)}/${enc(id)}.json"))
        return parseMeta(j.optJSONObject("meta"))
    }

    suspend fun fetchStreams(addons: List<Addon>, type: String, vid: String): List<StreamItem> {
        val matching = addons.filter { can(it, "stream", type, vid) }
        val out = mutableListOf<StreamItem>()
        for (a in matching) {
            runCatching {
                val j = JSONObject(fetchText("${a.base}/stream/${enc(type)}/${enc(vid)}.json"))
                val arr = j.optJSONArray("streams") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val s = arr.optJSONObject(i) ?: continue
                    val hints = s.optJSONObject("behaviorHints")
                    val reqHeaders = hints?.optJSONObject("proxyHeaders")?.optJSONObject("request")
                    val hdrMap = mutableMapOf<String, String>()
                    reqHeaders?.keys()?.forEach { k -> hdrMap[k] = reqHeaders.optString(k) }
                    val title = listOf(
                        s.optString("name"),
                        s.optString("title"),
                        s.optString("description"),
                        hints?.optString("filename") ?: ""
                    ).filter { it.isNotBlank() }.distinct().joinToString("\n").ifBlank { "Stream" }
                    out += StreamItem(
                        url = s.optString("url").ifBlank { null },
                        externalUrl = s.optString("externalUrl").ifBlank { null },
                        title = title,
                        addonName = a.name,
                        filename = hints?.optString("filename")?.ifBlank { null },
                        notWebReady = hints?.optBoolean("notWebReady") == true,
                        headers = hdrMap
                    )
                }
            }
        }
        return out
    }

    suspend fun fetchSubtitles(addons: List<Addon>, type: String, vid: String): List<SubItem> {
        val out = mutableListOf<SubItem>()
        for (a in addons.filter { can(it, "subtitles", type, vid) }) {
            runCatching {
                val j = JSONObject(fetchText("${a.base}/subtitles/${enc(type)}/${enc(vid)}.json"))
                val arr = j.optJSONArray("subtitles") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val s = arr.optJSONObject(i) ?: continue
                    val u = s.optString("url")
                    if (u.isNotBlank()) out += SubItem(u, s.optString("lang").ifBlank { "und" }, s.optString("id"))
                }
            }
        }
        return out.sortedWith(compareByDescending<SubItem> { it.lang == "eng" || it.lang == "en" }.thenBy { it.lang })
    }

    fun parseSubtitles(txt: String): List<SubCue> {
        val blocks = txt.removePrefix("\uFEFF").split(Regex("\\r?\\n\\s*\\r?\\n"))
        val cues = mutableListOf<SubCue>()
        for (blk in blocks) {
            val lines = blk.trim().split(Regex("\\r?\\n"))
            val idx = lines.indexOfFirst { it.contains("-->") }
            if (idx < 0) continue
            val parts = lines[idx].split("-->")
            if (parts.size < 2) continue
            val start = parseSubMs(parts[0])
            val end = parseSubMs(parts[1].trim().split(" ").first())
            val body = lines.drop(idx + 1).joinToString("\n")
                .replace(Regex("<[^>]+>"), "").replace(Regex("\\{\\\\[^}]+\\}"), "").trim()
            if (body.isNotBlank() && end > start) cues += SubCue(start, end, body)
        }
        return cues
    }

    private fun parseSubMs(s: String): Long {
        val p = s.trim().replace(',', '.').split(':')
        return runCatching {
            when (p.size) {
                3 -> ((p[0].toLong() * 3600 + p[1].toLong() * 60) * 1000) + (p[2].toDouble() * 1000).toLong()
                2 -> (p[0].toLong() * 60 * 1000) + (p[1].toDouble() * 1000).toLong()
                else -> 0L
            }
        }.getOrDefault(0L)
    }

    private fun parseMeta(j: JSONObject?): MetaItem? {
        j ?: return null
        val id = j.optString("id")
        if (id.isBlank()) return null
        val vids = mutableListOf<VideoEp>()
        val va = j.optJSONArray("videos") ?: JSONArray()
        for (i in 0 until va.length()) {
            val v = va.optJSONObject(i) ?: continue
            val vid = v.optString("id")
            if (vid.isBlank()) continue
            vids += VideoEp(
                id = vid,
                title = v.optString("title").ifBlank { v.optString("name").ifBlank { "Episode" } },
                season = v.optInt("season", 0),
                episode = if (v.has("episode")) v.optInt("episode") else v.optInt("number", 0),
                thumbnail = v.optString("thumbnail").ifBlank { null },
                overview = v.optString("overview").ifBlank { null }
            )
        }
        val tmdb = j.opt("moviedb_id")?.toString()?.takeIf { it.isNotBlank() && it != "0" && it != "null" }
        return MetaItem(
            id = id,
            type = j.optString("type").ifBlank { "movie" },
            name = j.optString("name").ifBlank { id },
            poster = j.optString("poster").ifBlank { null },
            background = j.optString("background").ifBlank { null },
            description = j.optString("description").ifBlank { null },
            releaseInfo = j.optString("releaseInfo").ifBlank { null },
            runtime = j.optString("runtime").ifBlank { null },
            imdbRating = j.optString("imdbRating").ifBlank { null },
            genres = j.optJSONArray("genres").toStringList(),
            videos = vids.sortedWith(compareBy({ it.season }, { it.episode })),
            tmdbId = tmdb
        )
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return List(length()) { optString(it) }.filter { it.isNotBlank() }
    }
}

class Store(ctx: Context) {
    private val sp = ctx.getSharedPreferences("streamer_prefs", Context.MODE_PRIVATE)

    fun getAddonBases(): List<String> {
        val raw = sp.getString("addons", null) ?: return listOf("https://v3-cinemeta.strem.io")
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return listOf("https://v3-cinemeta.strem.io")
        val list = List(arr.length()) { arr.optString(it) }.filter { it.isNotBlank() }
        return list.ifEmpty { listOf("https://v3-cinemeta.strem.io") }
    }
    fun saveAddonBases(bases: List<String>) = sp.edit().putString("addons", JSONArray(bases).toString()).apply()

    fun getJsPlugins(): List<JsPlugin> {
        val arr = runCatching { JSONArray(sp.getString("js_plugins", "[]")) }.getOrNull() ?: return emptyList()
        return List(arr.length()) { i ->
            val o = arr.optJSONObject(i) ?: return@List null
            JsPlugin(o.optString("id"), o.optString("name"), o.optString("ver"), o.optString("url"), o.optString("repo"), o.optBoolean("on", true))
        }.filterNotNull()
    }
    fun saveJsPlugins(list: List<JsPlugin>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("id", it.id).put("name", it.name).put("ver", it.version).put("url", it.scriptUrl).put("repo", it.repoUrl).put("on", it.enabled))
        }
        sp.edit().putString("js_plugins", arr.toString()).apply()
    }

    fun getFavs(): List<MetaItem> {
        val arr = runCatching { JSONArray(sp.getString("favs", "[]")) }.getOrNull() ?: return emptyList()
        return List(arr.length()) { i ->
            val o = arr.optJSONObject(i) ?: return@List null
            MetaItem(o.optString("id"), o.optString("type"), o.optString("name"), o.optString("poster").ifBlank { null }, imdbRating = o.optString("rate").ifBlank { null })
        }.filterNotNull()
    }
    fun saveFavs(list: List<MetaItem>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("id", it.id).put("type", it.type).put("name", it.name).put("poster", it.poster ?: "").put("rate", it.imdbRating ?: "")) }
        sp.edit().putString("favs", arr.toString()).apply()
    }

    fun getHistory(): List<HistoryItem> {
        val arr = runCatching { JSONArray(sp.getString("hist", "[]")) }.getOrNull() ?: return emptyList()
        return List(arr.length()) { i ->
            val o = arr.optJSONObject(i) ?: return@List null
            HistoryItem(o.optString("id"), o.optString("type"), o.optString("name"), o.optString("poster").ifBlank { null }, o.optString("vid"), o.optString("lab"), o.optLong("pos"), o.optLong("dur"))
        }.filterNotNull()
    }
    fun saveHistory(list: List<HistoryItem>) {
        val arr = JSONArray()
        list.take(25).forEach { arr.put(JSONObject().put("id", it.id).put("type", it.type).put("name", it.name).put("poster", it.poster ?: "").put("vid", it.vid).put("lab", it.lab).put("pos", it.posMs).put("dur", it.durMs)) }
        sp.edit().putString("hist", arr.toString()).apply()
    }

    fun getSubPrefs(): SubPrefs = SubPrefs(
        sizeSp = sp.getInt("sub_sz", 18),
        colorHex = sp.getLong("sub_col", 0xFFFFFFFF),
        bgStyle = sp.getInt("sub_bg", 0),
        bottomDp = sp.getInt("sub_pos", 24)
    )
    fun saveSubPrefs(p: SubPrefs) {
        sp.edit().putInt("sub_sz", p.sizeSp).putLong("sub_col", p.colorHex).putInt("sub_bg", p.bgStyle).putInt("sub_pos", p.bottomDp).apply()
    }

    fun getPos(vid: String): Long = sp.getLong("pos_$vid", 0L)
    fun setPos(vid: String, ms: Long) = sp.edit().putLong("pos_$vid", ms).apply()
    fun isDone(vid: String): Boolean = sp.getBoolean("done_$vid", false)
    fun setDone(vid: String) = sp.edit().putBoolean("done_$vid", true).apply()

    fun getRecentSearches(): List<String> {
        val arr = runCatching { JSONArray(sp.getString("rec_q", "[]")) }.getOrNull() ?: return emptyList()
        return List(arr.length()) { arr.optString(it) }.filter { it.isNotBlank() }
    }
    fun addRecentSearch(q: String) {
        val cur = (listOf(q) + getRecentSearches().filter { it != q }).take(10)
        sp.edit().putString("rec_q", JSONArray(cur).toString()).apply()
    }

    fun exportBackupJson(): String = JSONObject()
        .put("addons", JSONArray(getAddonBases()))
        .put("js_plugins", JSONArray(sp.getString("js_plugins", "[]")))
        .put("favs", JSONArray(sp.getString("favs", "[]")))
        .put("hist", JSONArray(sp.getString("hist", "[]")))
        .toString()

    fun importBackupJson(raw: String) {
        val j = JSONObject(raw)
        j.optJSONArray("addons")?.let { sp.edit().putString("addons", it.toString()).apply() }
        j.optJSONArray("js_plugins")?.let { sp.edit().putString("js_plugins", it.toString()).apply() }
        j.optJSONArray("favs")?.let { sp.edit().putString("favs", it.toString()).apply() }
        j.optJSONArray("hist")?.let { sp.edit().putString("hist", it.toString()).apply() }
    }
}
