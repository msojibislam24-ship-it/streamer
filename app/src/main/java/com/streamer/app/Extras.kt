package com.streamer.app

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.*
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/* ---------------- 1. Nuvio-Compatible JS Scraper Runner (WebView V8 + OkHttp) ---------------- */

class JsRunner(context: Context) {
    private val appCtx = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var webView: WebView? = null
    private val pendingRuns = ConcurrentHashMap<String, CompletableDeferred<String>>()

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun ensureWebView(): WebView = withContext(Dispatchers.Main) {
        webView?.let { return@withContext it }
        WebView(appCtx).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            Engine.userAgent = settings.userAgentString
            addJavascriptInterface(Bridge(), "_native")
            loadDataWithBaseURL("https://localhost/", "<!doctype html><html><body></body></html>", "text/html", "utf-8", null)
            webView = this
        }
    }

    inner class Bridge {
        @JavascriptInterface
        fun httpRequest(reqId: String, url: String, method: String, headersJson: String, body: String?) {
            scope.launch {
                val payload = JSONObject()
                try {
                    val rb = Request.Builder().url(url).header("User-Agent", Engine.userAgent)
                    val hdrs = runCatching { JSONObject(headersJson) }.getOrNull()
                    hdrs?.keys()?.forEach { k -> rb.header(k, hdrs.optString(k)) }
                    if (method.equals("POST", true)) {
                        val b = (body ?: "").toRequestBody("application/x-www-form-urlencoded".toMediaType())
                        rb.post(b)
                    }
                    Engine.http.newCall(rb.build()).execute().use { res ->
                        payload.put("ok", res.isSuccessful)
                        payload.put("status", res.code)
                        payload.put("url", res.request.url.toString())
                        payload.put("body", res.body?.string() ?: "")
                    }
                } catch (e: Exception) {
                    payload.put("ok", false)
                    payload.put("status", 0)
                    payload.put("error", e.message ?: "Network error")
                    payload.put("body", "")
                }
                val b64 = Base64.encodeToString(payload.toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                withContext(Dispatchers.Main) {
                    webView?.evaluateJavascript("window.__nativeResolve('$reqId','$b64');", null)
                }
            }
        }

        @JavascriptInterface
        fun finishRun(runId: String, jsonResult: String) {
            pendingRuns.remove(runId)?.complete(jsonResult)
        }
    }

    suspend fun runScraper(plugin: JsPlugin, meta: MetaItem, season: Int, episode: Int): List<StreamItem> {
        val wv = ensureWebView()
        val script = runCatching { Engine.fetchText(plugin.scriptUrl) }.getOrNull() ?: return emptyList()
        val runId = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<String>()
        pendingRuns[runId] = deferred

        val tmdb = meta.tmdbId ?: meta.id
        val mediaType = if (meta.type == "series") "tv" else "movie"
        val scriptB64 = Base64.encodeToString(script.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val titleJson = JSONObject.quote(meta.name)

        val js = """
            (function(){
              function b64dec(b){
                const bin = atob(b);
                const bytes = new Uint8Array(bin.length);
                for(let i=0;i<bin.length;i++) bytes[i]=bin.charCodeAt(i);
                return new TextDecoder('utf-8').decode(bytes);
              }
              window.__pending = window.__pending || {};
              window.__nativeResolve = function(id, b64){
                const cb = window.__pending[id];
                if(!cb) return;
                delete window.__pending[id];
                try {
                  const res = JSON.parse(b64dec(b64));
                  cb.resolve({
                    ok: !!res.ok,
                    status: res.status || 0,
                    url: res.url || '',
                    text: () => Promise.resolve(res.body || ''),
                    json: () => Promise.resolve(JSON.parse(res.body || '{}'))
                  });
                } catch(e){ cb.reject(e); }
              };
              window.fetch = function(url, opts){
                opts = opts || {};
                const id = 'f_' + Math.random().toString(36).slice(2);
                return new Promise((resolve, reject) => {
                  window.__pending[id] = {resolve, reject};
                  _native.httpRequest(id, String(url), (opts.method||'GET').toUpperCase(), JSON.stringify(opts.headers||{}), opts.body ? String(opts.body) : null);
                });
              };
              function wrapNodes(nodes){
                const arr = Array.from(nodes || []);
                const api = {
                  length: arr.length,
                  get: (i) => i == null ? arr : arr[i],
                  eq: (i) => wrapNodes(arr[i] ? [arr[i]] : []),
                  first: () => wrapNodes(arr[0] ? [arr[0]] : []),
                  last: () => wrapNodes(arr.length ? [arr[arr.length-1]] : []),
                  find: (sel) => {
                    const out = [];
                    arr.forEach(n => { if(n && n.querySelectorAll) out.push(...n.querySelectorAll(sel)); });
                    return wrapNodes(out);
                  },
                  children: () => {
                    const out = [];
                    arr.forEach(n => { if(n && n.children) out.push(...n.children); });
                    return wrapNodes(out);
                  },
                  parent: () => wrapNodes(arr.map(n => n && n.parentElement).filter(Boolean)),
                  text: () => arr.map(n => n.textContent || '').join(''),
                  html: () => arr[0] ? arr[0].innerHTML : '',
                  attr: (k) => arr[0] ? arr[0].getAttribute(k) : undefined,
                  each: (fn) => { arr.forEach((n,i)=>fn.call(wrapNodes([n]), i, n)); return api; },
                  map: (fn) => { const r = arr.map((n,i)=>fn.call(wrapNodes([n]), i, n)); return { get: () => r, toArray: () => r }; }
                };
                return api;
              }
              const cheerio = {
                load: function(html){
                  const doc = new DOMParser().parseFromString(String(html||''), 'text/html');
                  const $ = function(sel, ctx){
                    if(!sel) return wrapNodes([]);
                    if(typeof sel === 'string') return wrapNodes((ctx && ctx.querySelectorAll ? ctx : doc).querySelectorAll(sel));
                    if(sel.nodeType) return wrapNodes([sel]);
                    return sel;
                  };
                  $.html = () => doc.documentElement.innerHTML;
                  return $;
                }
              };
              window.require = function(name){
                if(String(name).includes('cheerio')) return cheerio;
                return {};
              };
              window.cheerio = cheerio;
              try {
                var module = { exports: {} };
                var exports = module.exports;
                var getStreams;
                var TMDB_ID = ${JSONObject.quote(tmdb)};
                var MEDIA_TITLE = $titleJson;
                eval(b64dec('$scriptB64'));
                const fn = (module.exports && (module.exports.getStreams || module.exports.default)) || exports.getStreams || getStreams;
                if(typeof fn !== 'function'){
                  _native.finishRun('$runId', '[]');
                  return;
                }
                Promise.resolve(fn(${JSONObject.quote(tmdb)}, '$mediaType', $season, $episode))
                  .then(res => _native.finishRun('$runId', JSON.stringify(Array.isArray(res) ? res : [])))
                  .catch(() => _native.finishRun('$runId', '[]'));
              } catch(e){
                _native.finishRun('$runId', '[]');
              }
            })();
        """.trimIndent()

        withContext(Dispatchers.Main) { wv.evaluateJavascript(js, null) }
        val raw = withTimeoutOrNull(14_000L) { deferred.await() } ?: run {
            pendingRuns.remove(runId)
            return emptyList()
        }
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        val out = mutableListOf<StreamItem>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val u = o.optString("url")
            if (u.isBlank()) continue
            val hdrs = mutableMapOf<String, String>()
            o.optJSONObject("headers")?.let { ho -> ho.keys().forEach { k -> hdrs[k] = ho.optString(k) } }
            val lbl = listOf(o.optString("name"), o.optString("title"), o.optString("quality")).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "Stream" }
            out += StreamItem(url = u, externalUrl = null, title = lbl, addonName = "JS: ${plugin.name}", headers = hdrs)
        }
        return out
    }
}

/* ---------------- 2. Cloudflare / Bot-Check WebView Dialog ---------------- */

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun CloudflareSolverDialog(initialUrl: String, onClose: () -> Unit) {
    var currentUrl by remember { mutableStateOf(initialUrl) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().padding(12.dp), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(10.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Solve Bot Check & Tap Done", style = MaterialTheme.typography.titleSmall)
                    Button(onClick = {
                        CookieManager.getInstance().flush()
                        onClose()
                    }) { Text("Done") }
                }
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            CookieManager.getInstance().setAcceptCookie(true)
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            Engine.userAgent = settings.userAgentString
                            webViewClient = object : WebViewClient() {
                                override fun onPageFinished(view: WebView?, url: String?) {
                                    if (url != null) currentUrl = url
                                    CookieManager.getInstance().flush()
                                }
                            }
                            loadUrl(initialUrl)
                        }
                    },
                    modifier = Modifier.weight(1f).fillMaxWidth()
                )
            }
        }
    }
}

/* ---------------- 3. Watch Party (ntfy.sh OkHttp Streaming Relay) ---------------- */

class PartyClient {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var listenCall: Call? = null
    val senderId: String = UUID.randomUUID().toString().take(8)
    var code by mutableStateOf<String?>(null)
    var isHost by mutableStateOf(false)

    private fun topic(cd: String) = "https://ntfy.sh/strmwp26-${cd.uppercase()}"

    fun start(roomCode: String, host: Boolean, onEvent: (JSONObject) -> Unit) {
        leave()
        val clean = roomCode.trim().uppercase()
        code = clean
        isHost = host
        scope.launch {
            while (isActive && code == clean) {
                try {
                    val client = Engine.http.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).build()
                    val call = client.newCall(Request.Builder().url("${topic(clean)}/json").build())
                    listenCall = call
                    call.execute().use { res ->
                        val src = res.body?.source() ?: return@use
                        while (!src.exhausted() && code == clean) {
                            val line = src.readUtf8Line() ?: break
                            if (line.isBlank()) continue
                            val env = runCatching { JSONObject(line) }.getOrNull() ?: continue
                            if (env.optString("event") != "message") continue
                            val msg = runCatching { JSONObject(env.optString("message")) }.getOrNull() ?: continue
                            if (msg.optString("sid") == senderId) continue
                            withContext(Dispatchers.Main) { onEvent(msg) }
                        }
                    }
                } catch (_: Exception) {
                    delay(2000L)
                }
            }
        }
    }

    fun send(payload: JSONObject) {
        val cd = code ?: return
        scope.launch {
            runCatching {
                payload.put("sid", senderId)
                val body = payload.toString().toRequestBody("application/json".toMediaType())
                Engine.http.newCall(Request.Builder().url(topic(cd)).post(body).build()).execute().close()
            }
        }
    }

    fun leave() {
        code = null
        isHost = false
        listenCall?.cancel()
        listenCall = null
    }
}

/* ---------------- 4. Android DownloadManager Helper ---------------- */

data class DownloadEntry(
    val id: Long,
    val title: String,
    val status: Int,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val localUri: String?
)

object DownloadHelper {
    fun enqueue(ctx: Context, title: String, stream: StreamItem): Long {
        val url = stream.url ?: return -1L
        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val ext = when {
            url.contains(".mp4", true) -> "mp4"
            url.contains(".mkv", true) -> "mkv"
            url.contains(".webm", true) -> "webm"
            else -> "mp4"
        }
        val safe = title.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(55)
        val req = DownloadManager.Request(Uri.parse(url))
            .setTitle(title)
            .setDescription(stream.addonName)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(ctx, Environment.DIRECTORY_DOWNLOADS, "${safe}_${System.currentTimeMillis() % 10000}.$ext")
        stream.headers.forEach { (k, v) -> req.addRequestHeader(k, v) }
        return dm.enqueue(req)
    }

    fun list(ctx: Context): List<DownloadEntry> {
        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val out = mutableListOf<DownloadEntry>()
        dm.query(DownloadManager.Query())?.use { c ->
            val iId = c.getColumnIndex(DownloadManager.COLUMN_ID)
            val iTitle = c.getColumnIndex(DownloadManager.COLUMN_TITLE)
            val iStatus = c.getColumnIndex(DownloadManager.COLUMN_STATUS)
            val iDown = c.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
            val iTot = c.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
            val iUri = c.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
            while (c.moveToNext()) {
                out += DownloadEntry(
                    id = if (iId >= 0) c.getLong(iId) else 0L,
                    title = if (iTitle >= 0) c.getString(iTitle) ?: "Video" else "Video",
                    status = if (iStatus >= 0) c.getInt(iStatus) else 0,
                    downloadedBytes = if (iDown >= 0) c.getLong(iDown) else 0L,
                    totalBytes = if (iTot >= 0) c.getLong(iTot) else 0L,
                    localUri = if (iUri >= 0) c.getString(iUri) else null
                )
            }
        }
        return out.sortedByDescending { it.id }
    }

    fun remove(ctx: Context, id: Long) {
        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        dm.remove(id)
    }
}
