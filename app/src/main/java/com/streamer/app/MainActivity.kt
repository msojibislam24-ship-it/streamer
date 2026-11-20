@file:OptIn(androidx.media3.common.util.UnstableApi::class, ExperimentalMaterial3Api::class)

package com.streamer.app

import android.app.Activity
import android.app.DownloadManager
import android.app.PictureInPictureParams
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.Coil
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.disk.DiskCache
import coil.memory.MemoryCache
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import kotlin.math.abs

private val BgDark = Color(0xFF0A0A0F)
private val CardDark = Color(0xFF1A1A26)
private val TextWhite = Color(0xFFF4F2F8)
private val TextMut = Color(0xFF9490A8)
private val AccentPink = Color(0xFFFF3D7F)
private val AccentPurple = Color(0xFF7B5CFF)

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun fmtMs(ms: Long): String {
    if (ms <= 0L) return "0:00"
    val s = ms / 1000
    val m = s / 60
    val r = s % 60
    return if (m >= 60) "%d:%02d:%02d".format(m / 60, m % 60, r) else "%d:%02d".format(m, r)
}

private fun fmtBytes(b: Long): String = when {
    b <= 0L -> "0 MB"
    b >= 1_073_741_824L -> "%.2f GB".format(b / 1_073_741_824.0)
    else -> "%.1f MB".format(b / 1_048_576.0)
}

private fun extractStreamBadges(s: StreamItem): List<Pair<String, Color>> {
    val txt = "${s.title} ${s.filename ?: ""}"
    val out = mutableListOf<Pair<String, Color>>()
    Regex("\\b(2160p|4K|1080p|720p|480p)\\b", RegexOption.IGNORE_CASE).find(txt)?.let {
        out += it.value.uppercase() to AccentPurple
    }
    Regex("\\b(\\d+(?:\\.\\d+)?\\s*(?:GB|MB))\\b", RegexOption.IGNORE_CASE).find(txt)?.let {
        out += it.value.uppercase() to Color(0xFF0EA5E9)
    }
    Regex("\\b(HDR10\\+?|DV|Dolby\\s*Vision|HEVC|x265|10bit|IMAX)\\b", RegexOption.IGNORE_CASE)
        .findAll(txt).map { it.value.uppercase() }.distinct().take(2).forEach {
            out += it to Color(0xFF34D399)
        }
    return out
}

data class PlaySession(
    val stream: StreamItem,
    val title: String,
    val meta: MetaItem,
    val vid: String,
    val epLabel: String,
    val allStreams: List<StreamItem>,
    val episodes: List<VideoEp>,
    val fromParty: Boolean = false
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Fast Memory + Disk Cache for Posters & Thumbnails
        val loader = ImageLoader.Builder(applicationContext)
            .okHttpClient(Engine.http)
            .memoryCache { MemoryCache.Builder(applicationContext).maxSizePercent(0.25).build() }
            .diskCache { DiskCache.Builder().directory(cacheDir.resolve("img_cache")).maxSizeBytes(80L * 1024 * 1024).build() }
            .crossfade(true)
            .build()
        Coil.setImageLoader(loader)

        val store = Store(applicationContext)
        val jsRunner = JsRunner(applicationContext)
        val party = PartyClient()

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = AccentPink,
                    secondary = AccentPurple,
                    background = BgDark,
                    surface = CardDark,
                    onPrimary = Color(0xFF19040C),
                    onBackground = TextWhite,
                    onSurface = TextWhite
                )
            ) {
                Surface(Modifier.fillMaxSize(), color = BgDark, contentColor = TextWhite) {
                    StreamerApp(store, jsRunner, party)
                }
            }
        }
    }
}

@Composable
fun StreamerApp(store: Store, jsRunner: JsRunner, party: PartyClient) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var showSplash by remember { mutableStateOf(true) }
    var tab by remember { mutableStateOf("home") }
    var addons by remember { mutableStateOf(store.getAddonBases().map { Addon(it, it) }) }
    var jsPlugins by remember { mutableStateOf(store.getJsPlugins()) }
    var favs by remember { mutableStateOf(store.getFavs()) }
    var history by remember { mutableStateOf(store.getHistory()) }
    var selectedItem by remember { mutableStateOf<MetaItem?>(null) }
    var activePlayer by remember { mutableStateOf<PlaySession?>(null) }
    var showPartyDialog by remember { mutableStateOf(false) }
    var cfUrl by remember { mutableStateOf<String?>(null) }
    var partyIncomingSync by remember { mutableStateOf<Pair<String, Long>?>(null) }
    var partyEmoji by remember { mutableStateOf<String?>(null) }

    val refreshAllAddons: () -> Unit = {
        scope.launch {
            addons = addons.map { a -> runCatching { Engine.fetchManifest(a.base) }.getOrElse { a } }
        }
    }

    LaunchedEffect(Unit) {
        refreshAllAddons()
        delay(650L)
        showSplash = false
    }

    val handlePartyEvent: (JSONObject) -> Unit = { msg ->
        when (msg.optString("t")) {
            "play" -> {
                val u = msg.optString("url")
                if (u.isNotBlank()) {
                    val m = MetaItem(msg.optString("id"), msg.optString("type", "movie"), msg.optString("name"), msg.optString("poster").ifBlank { null })
                    val s = StreamItem(u, null, msg.optString("title"), "Party")
                    activePlayer = PlaySession(s, msg.optString("title"), m, msg.optString("vid"), msg.optString("lab"), listOf(s), emptyList(), fromParty = true)
                }
            }
            "sync" -> partyIncomingSync = msg.optString("state") to msg.optLong("time")
            "emo" -> partyEmoji = msg.optString("e")
        }
    }

    if (showSplash) {
        Box(Modifier.fillMaxSize().background(BgDark), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier.size(72.dp).clip(RoundedCornerShape(22.dp)).background(Brush.linearGradient(listOf(AccentPink, AccentPurple))),
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.size(44.dp)) }
                Spacer(Modifier.height(14.dp))
                Text("Streamer", fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, color = TextWhite)
            }
        }
        return
    }

    if (cfUrl != null) {
        CloudflareSolverDialog(cfUrl!!) { cfUrl = null }
    }

    if (showPartyDialog) {
        PartyDialog(
            party = party,
            onHost = { code -> party.start(code, true, handlePartyEvent) },
            onJoin = { code -> party.start(code, false, handlePartyEvent) },
            onLeave = { party.leave() },
            onDismiss = { showPartyDialog = false }
        )
    }

    if (activePlayer != null) {
        val session = activePlayer!!
        LaunchedEffect(session.stream.url, session.fromParty) {
            if (party.code != null && !session.fromParty && session.stream.url != null) {
                party.send(
                    JSONObject()
                        .put("t", "play")
                        .put("url", session.stream.url)
                        .put("title", session.title)
                        .put("id", session.meta.id)
                        .put("type", session.meta.type)
                        .put("name", session.meta.name)
                        .put("poster", session.meta.poster ?: "")
                        .put("vid", session.vid)
                        .put("lab", session.epLabel)
                )
            }
        }
        PlayerScreen(
            session = session,
            addons = addons,
            jsRunner = jsRunner,
            jsPlugins = jsPlugins,
            store = store,
            party = party,
            incomingSync = partyIncomingSync,
            incomingEmoji = partyEmoji,
            onConsumeSync = { partyIncomingSync = null },
            onConsumeEmoji = { partyEmoji = null },
            onSwitchSession = { activePlayer = it },
            onSaveProgress = { posMs, durMs ->
                store.setPos(session.vid, posMs)
                val rest = history.filter { it.id != session.meta.id }
                val updated = if (durMs > 0 && posMs.toFloat() / durMs > 0.92f) {
                    store.setDone(session.vid)
                    val idx = session.episodes.indexOfFirst { it.id == session.vid }
                    val nx = if (session.meta.type == "series" && idx in 0 until session.episodes.lastIndex) session.episodes[idx + 1] else null
                    if (nx != null) listOf(HistoryItem(session.meta.id, session.meta.type, session.meta.name, session.meta.poster, nx.id, "S${nx.season}E${nx.episode}", 0L, 0L)) + rest
                    else rest
                } else {
                    listOf(HistoryItem(session.meta.id, session.meta.type, session.meta.name, session.meta.poster, session.vid, session.epLabel, posMs, durMs)) + rest
                }
                history = updated
                store.saveHistory(updated)
            },
            onClose = { activePlayer = null }
        )
        return
    }

    if (selectedItem != null) {
        BackHandler { selectedItem = null }
        DetailScreen(
            initial = selectedItem!!,
            addons = addons,
            jsPlugins = jsPlugins,
            jsRunner = jsRunner,
            store = store,
            isFav = favs.any { it.id == selectedItem!!.id },
            contItem = history.find { it.id == selectedItem!!.id },
            onToggleFav = { m ->
                favs = if (favs.any { it.id == m.id }) favs.filter { it.id != m.id } else listOf(m) + favs
                store.saveFavs(favs)
            },
            onClearHist = { id ->
                history = history.filter { it.id != id }
                store.saveHistory(history)
            },
            onPlay = { activePlayer = it },
            onSolveCf = { cfUrl = it },
            onBack = { selectedItem = null }
        )
        return
    }

    Scaffold(
        containerColor = BgDark,
        contentColor = TextWhite,
        topBar = {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(30.dp).clip(RoundedCornerShape(9.dp)).background(Brush.linearGradient(listOf(AccentPink, AccentPurple))),
                        contentAlignment = Alignment.Center
                    ) { Icon(Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.size(18.dp)) }
                    Spacer(Modifier.width(10.dp))
                    Text("Streamer", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = TextWhite)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (party.code != null) {
                        AssistChip(
                            onClick = { showPartyDialog = true },
                            label = { Text("Party ${party.code}") },
                            leadingIcon = { Icon(Icons.Rounded.Groups, null, modifier = Modifier.size(16.dp)) }
                        )
                    } else {
                        IconButton(onClick = { showPartyDialog = true }) { Icon(Icons.Rounded.Groups, "Watch Party", tint = TextWhite) }
                    }
                }
            }
        },
        bottomBar = {
            NavigationBar(containerColor = Color(0xFF0E0E16)) {
                NavigationBarItem(selected = tab == "home", onClick = { tab = "home" }, icon = { Icon(Icons.Rounded.Home, null) }, label = { Text("Home") })
                NavigationBarItem(selected = tab == "discover", onClick = { tab = "discover" }, icon = { Icon(Icons.Rounded.Explore, null) }, label = { Text("Discover") })
                NavigationBarItem(selected = tab == "search", onClick = { tab = "search" }, icon = { Icon(Icons.Rounded.Search, null) }, label = { Text("Search") })
                NavigationBarItem(selected = tab == "downloads", onClick = { tab = "downloads" }, icon = { Icon(Icons.Rounded.Download, null) }, label = { Text("Saved") })
                NavigationBarItem(selected = tab == "addons", onClick = { tab = "addons" }, icon = { Icon(Icons.Rounded.Extension, null) }, label = { Text("Addons") })
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                "home" -> HomeScreen(addons, history, favs, onOpen = { selectedItem = it }, onGoAddons = { tab = "addons" })
                "discover" -> DiscoverScreen(addons, onOpen = { selectedItem = it })
                "search" -> SearchScreen(addons, store, onOpen = { selectedItem = it })
                "downloads" -> DownloadsScreen(onPlayOffline = { entry ->
                    val s = StreamItem(entry.localUri, null, entry.title, "Offline")
                    val m = MetaItem(entry.localUri ?: "dl_${entry.id}", "movie", entry.title)
                    activePlayer = PlaySession(s, entry.title, m, m.id, "", listOf(s), emptyList())
                })
                "addons" -> AddonsScreen(
                    addons = addons,
                    jsPlugins = jsPlugins,
                    store = store,
                    onAddStremio = { raw ->
                        val base = Engine.toBase(raw)
                        if (addons.any { it.base == base }) return@AddonsScreen "Addon already installed."
                        val loaded = Engine.fetchManifest(base)
                        addons = addons + loaded
                        store.saveAddonBases(addons.map { it.base })
                        null
                    },
                    onAddJsRepo = { repoUrl ->
                        val found = Engine.fetchJsRepo(repoUrl)
                        if (found.isEmpty()) return@AddonsScreen "No JS scrapers found in that manifest."
                        val merged = (jsPlugins.filter { p -> found.none { it.id == p.id && it.repoUrl == p.repoUrl } } + found)
                        jsPlugins = merged
                        store.saveJsPlugins(merged)
                        null
                    },
                    onToggleJs = { idx ->
                        jsPlugins = jsPlugins.toMutableList().also { it[idx] = it[idx].copy(enabled = !it[idx].enabled) }
                        store.saveJsPlugins(jsPlugins)
                    },
                    onRemoveJs = { idx ->
                        jsPlugins = jsPlugins.toMutableList().also { it.removeAt(idx) }
                        store.saveJsPlugins(jsPlugins)
                    },
                    onRemoveStremio = { idx ->
                        addons = addons.toMutableList().also { it.removeAt(idx) }
                        store.saveAddonBases(addons.map { it.base })
                    },
                    onMoveStremio = { from, to ->
                        val m = addons.toMutableList()
                        val item = m.removeAt(from)
                        m.add(to, item)
                        addons = m
                        store.saveAddonBases(addons.map { it.base })
                    },
                    onSolveCf = { cfUrl = it },
                    onRestored = {
                        addons = store.getAddonBases().map { Addon(it, it) }
                        jsPlugins = store.getJsPlugins()
                        favs = store.getFavs()
                        history = store.getHistory()
                        refreshAllAddons()
                    },
                    onRefresh = {
                        refreshAllAddons()
                        Toast.makeText(ctx, "Refreshing addons…", Toast.LENGTH_SHORT).show()
                    }
                )
            }
        }
    }
}

@Composable
fun PartyDialog(party: PartyClient, onHost: (String) -> Unit, onJoin: (String) -> Unit, onLeave: () -> Unit, onDismiss: () -> Unit) {
    var codeInp by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Watch Party") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (party.code != null) {
                    Text("Connected to Room: ${party.code}", fontWeight = FontWeight.Bold, color = AccentPink)
                    Text(if (party.isHost) "Start any stream and friends in this room will load & sync with you." else "Wait for the host to start a video, or start one to sync.")
                    Button(onClick = { onLeave(); onDismiss() }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626), contentColor = Color.White)) {
                        Text("Leave Room")
                    }
                } else {
                    Button(onClick = {
                        val chars = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
                        val code = (1..4).map { chars.random() }.joinToString("")
                        onHost(code)
                    }, modifier = Modifier.fillMaxWidth()) { Text("Host a New Room") }
                    HorizontalDivider()
                    OutlinedTextField(value = codeInp, onValueChange = { codeInp = it.uppercase().take(4) }, label = { Text("4-Letter Room Code") }, singleLine = true)
                    OutlinedButton(onClick = { if (codeInp.length == 4) onJoin(codeInp) }, modifier = Modifier.fillMaxWidth()) { Text("Join Room") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

@Composable
fun HomeScreen(
    addons: List<Addon>,
    history: List<HistoryItem>,
    favs: List<MetaItem>,
    onOpen: (MetaItem) -> Unit,
    onGoAddons: () -> Unit
) {
    val catalogs = remember(addons) { addons.flatMap { a -> a.catalogs.filter { !it.extraRequired }.map { a to it } }.take(10) }
    val rows = remember { mutableStateMapOf<String, List<MetaItem>>() }
    var featured by remember { mutableStateOf<MetaItem?>(null) }

    LaunchedEffect(catalogs) {
        for ((a, c) in catalogs) {
            val key = "${a.base}_${c.type}_${c.id}"
            if (rows.containsKey(key)) continue
            launch {
                runCatching { Engine.fetchCatalog(a.base, c.type, c.id) }
                    .onSuccess { list ->
                        rows[key] = list.take(25)
                        if (featured == null && list.isNotEmpty()) {
                            featured = list.firstOrNull { it.background != null || it.poster != null }
                        }
                    }
            }
        }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item(key = "featured_hero") {
            val f = featured
            if (f == null) {
                Box(Modifier.padding(horizontal = 14.dp, vertical = 8.dp).fillMaxWidth().height(220.dp).clip(RoundedCornerShape(18.dp)).background(CardDark))
            } else {
                Box(
                    Modifier.padding(horizontal = 14.dp, vertical = 8.dp).fillMaxWidth().height(230.dp)
                        .clip(RoundedCornerShape(18.dp)).background(CardDark).clickable { onOpen(f) }
                ) {
                    AsyncImage(model = f.background ?: f.poster, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC0A0A0F), BgDark))))
                    Column(Modifier.align(Alignment.BottomStart).padding(16.dp)) {
                        Text("FEATURED ${f.type.uppercase()}", color = AccentPink, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Text(f.name, color = TextWhite, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        f.description?.let { Text(it, fontSize = 12.sp, color = Color(0xFFD0CDE0), maxLines = 2, overflow = TextOverflow.Ellipsis) }
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { onOpen(f) }, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)) {
                            Icon(Icons.Rounded.PlayArrow, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp)); Text("Watch / Details")
                        }
                    }
                }
            }
        }

        if (history.isNotEmpty()) {
            item { SectionTitle("Continue Watching", "${history.size} items") }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(history, key = { it.id }) { h ->
                        PosterCard(h.name, h.poster, h.lab.ifBlank { null }, if (h.durMs > 0) h.posMs.toFloat() / h.durMs else 0f) {
                            onOpen(MetaItem(h.id, h.type, h.name, h.poster))
                        }
                    }
                }
            }
        }

        if (favs.isNotEmpty()) {
            item { SectionTitle("My List", "${favs.size} saved") }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(favs, key = { "${it.type}_${it.id}" }) { m ->
                        PosterCard(m.name, m.poster, m.imdbRating?.let { "★ $it" }) { onOpen(m) }
                    }
                }
            }
        }

        if (catalogs.isEmpty()) {
            item {
                Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Loading catalogs… or add an addon in Addons.", color = TextMut)
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = onGoAddons) { Text("Open Addons") }
                }
            }
        } else {
            items(catalogs, key = { "${it.first.base}_${it.second.type}_${it.second.id}" }) { (a, c) ->
                val list = rows["${a.base}_${c.type}_${c.id}"].orEmpty()
                SectionTitle(c.name, c.type)
                if (list.isEmpty()) {
                    Text("Loading…", color = TextMut, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
                } else {
                    LazyRow(contentPadding = PaddingValues(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(list, key = { "${it.type}_${it.id}" }) { m ->
                            PosterCard(m.name, m.poster, m.imdbRating?.let { "★ $it" }) { onOpen(m) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DiscoverScreen(addons: List<Addon>, onOpen: (MetaItem) -> Unit) {
    val catalogs = remember(addons) { addons.flatMap { a -> a.catalogs.filter { !it.extraRequired }.map { a to it } } }
    var selIdx by remember { mutableIntStateOf(0) }
    var selGenre by remember { mutableStateOf("All") }
    var selYear by remember { mutableStateOf("All") }
    var items by remember { mutableStateOf<List<MetaItem>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }

    val current = catalogs.getOrNull(selIdx)
    val genres = remember(current) {
        val g = current?.second?.genres.orEmpty()
        listOf("All") + (if (g.isNotEmpty()) g else listOf("Action", "Adventure", "Animation", "Comedy", "Crime", "Drama", "Horror", "Sci-Fi", "Thriller"))
    }

    val loadPage: suspend (Int, Boolean) -> Unit = { skip, append ->
        if (current != null) {
            loading = true
            runCatching { Engine.fetchCatalog(current.first.base, current.second.type, current.second.id, genre = selGenre, skip = skip) }
                .onSuccess { res -> items = if (append) (items + res).distinctBy { it.type + it.id } else res }
            loading = false
        }
    }

    LaunchedEffect(selIdx, selGenre) { loadPage(0, false) }

    Column(Modifier.fillMaxSize()) {
        if (catalogs.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No catalogs available.", color = TextMut) }
            return
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(catalogs.size) { i ->
                val c = catalogs[i].second
                FilterChip(selected = selIdx == i, onClick = { selIdx = i; selGenre = "All" }, label = { Text("${c.name} (${c.type})") })
            }
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(genres) { g -> FilterChip(selected = selGenre == g, onClick = { selGenre = g }, label = { Text(g) }) }
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 14.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(listOf("All", "2026", "2025", "2024", "2020s", "2010s")) { y ->
                FilterChip(selected = selYear == y, onClick = { selYear = y }, label = { Text(if (y == "All") "Any Year" else y) })
            }
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp))

        val filtered = items.filter { m ->
            val r = m.releaseInfo ?: ""
            when (selYear) {
                "All" -> true
                "2020s" -> r.startsWith("202")
                "2010s" -> r.startsWith("201")
                else -> r.contains(selYear)
            }
        }

        val scope = rememberCoroutineScope()
        LazyVerticalGrid(
            columns = GridCells.Adaptive(108.dp),
            contentPadding = PaddingValues(14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.weight(1f)
        ) {
            items(filtered, key = { "${it.type}_${it.id}" }) { m ->
                PosterCard(m.name, m.poster, m.releaseInfo ?: m.imdbRating?.let { "★ $it" }) { onOpen(m) }
            }
            if (items.isNotEmpty() && !loading) {
                item {
                    Box(Modifier.fillMaxWidth().padding(vertical = 20.dp), contentAlignment = Alignment.Center) {
                        OutlinedButton(onClick = { scope.launch { loadPage(items.size, true) } }) { Text("Load More") }
                    }
                }
            }
        }
    }
}

@Composable
fun DownloadsScreen(onPlayOffline: (DownloadEntry) -> Unit) {
    val ctx = LocalContext.current
    var entries by remember { mutableStateOf(DownloadHelper.list(ctx)) }

    LaunchedEffect(Unit) {
        while (true) {
            entries = DownloadHelper.list(ctx)
            delay(1500L)
        }
    }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 14.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { SectionTitle("Downloads", "${entries.size} files") }
        if (entries.isEmpty()) {
            item { Text("No downloads yet. Open any movie/episode and tap Download on a stream.", color = TextMut, modifier = Modifier.padding(top = 12.dp)) }
        } else {
            items(entries, key = { it.id }) { e ->
                val pct = if (e.totalBytes > 0) (e.downloadedBytes.toFloat() / e.totalBytes).coerceIn(0f, 1f) else 0f
                val done = e.status == DownloadManager.STATUS_SUCCESSFUL && !e.localUri.isNullOrBlank()
                Card(colors = CardDefaults.cardColors(containerColor = CardDark), modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text(e.title, fontWeight = FontWeight.Bold, color = TextWhite, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(4.dp))
                        val statusTxt = when (e.status) {
                            DownloadManager.STATUS_SUCCESSFUL -> "Completed · ${fmtBytes(e.totalBytes)}"
                            DownloadManager.STATUS_FAILED -> "Failed"
                            DownloadManager.STATUS_PAUSED -> "Paused · ${(pct * 100).toInt()}%"
                            else -> "Downloading ${(pct * 100).toInt()}% (${fmtBytes(e.downloadedBytes)} / ${fmtBytes(e.totalBytes)})"
                        }
                        Text(statusTxt, fontSize = 12.sp, color = if (done) Color(0xFF34D399) else TextMut)
                        if (!done && e.totalBytes > 0) {
                            Spacer(Modifier.height(6.dp))
                            LinearProgressIndicator(progress = { pct }, modifier = Modifier.fillMaxWidth())
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (done) {
                                Button(onClick = { onPlayOffline(e) }, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)) {
                                    Text("▶ Play Offline")
                                }
                            }
                            OutlinedButton(onClick = {
                                DownloadHelper.remove(ctx, e.id)
                                entries = DownloadHelper.list(ctx)
                            }) { Text("Delete", color = TextWhite) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SectionTitle(title: String, tag: String) {
    Row(
        Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 16.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = TextWhite)
        Text(tag.uppercase(), fontSize = 11.sp, color = TextMut)
    }
}

@Composable
fun PosterCard(name: String, poster: String?, sub: String? = null, progress: Float = 0f, onClick: () -> Unit) {
    Column(Modifier.width(116.dp).clickable(onClick = onClick)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(12.dp))
                .background(CardDark).border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
        ) {
            Text(name, fontSize = 11.sp, color = TextMut, textAlign = TextAlign.Center, modifier = Modifier.align(Alignment.Center).padding(8.dp))
            AsyncImage(model = poster, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            if (progress > 0f) {
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color.White.copy(alpha = 0.2f))) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(progress.coerceIn(0f, 1f)).background(AccentPink))
                }
            }
        }
        Spacer(Modifier.height(5.dp))
        Text(name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TextWhite, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (!sub.isNullOrBlank()) Text(sub, fontSize = 11.sp, color = TextMut, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun SearchScreen(addons: List<Addon>, store: Store, onOpen: (MetaItem) -> Unit) {
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf("all") }
    var results by remember { mutableStateOf<List<MetaItem>>(emptyList()) }
    var recents by remember { mutableStateOf(store.getRecentSearches()) }

    val runSearch: (String) -> Unit = { q ->
        val trimmed = q.trim()
        if (trimmed.isNotEmpty()) {
            store.addRecentSearch(trimmed)
            recents = store.getRecentSearches()
            searching = true
            scope.launch {
                val cats = addons.flatMap { a -> a.catalogs.filter { it.hasSearch }.map { a to it } }
                val seen = HashSet<String>()
                val found = mutableListOf<MetaItem>()
                for ((a, c) in cats) {
                    runCatching { Engine.fetchCatalog(a.base, c.type, c.id, query = trimmed) }
                        .onSuccess { list ->
                            for (m in list) {
                                val k = "${m.type}:${m.id}"
                                if (seen.add(k)) found += m.copy(source = a.name)
                            }
                        }
                }
                results = found
                searching = false
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search movies, series, or tt IMDb ID") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { runSearch(query) }),
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(14.dp)
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = { runSearch(query) }) { Text("Go") }
        }

        if (Regex("^tt\\d{5,}$", RegexOption.IGNORE_CASE).matches(query.trim())) {
            val tt = query.trim()
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                Button(onClick = { onOpen(MetaItem(tt, "movie", tt)) }) { Text("Open $tt as Movie") }
                OutlinedButton(onClick = { onOpen(MetaItem(tt, "series", tt)) }) { Text("As Series") }
            }
        }

        if (results.isEmpty() && !searching && recents.isNotEmpty()) {
            Text("Recent Searches", fontWeight = FontWeight.Bold, color = TextWhite, modifier = Modifier.padding(top = 8.dp, bottom = 6.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(recents) { rq -> AssistChip(onClick = { query = rq; runSearch(rq) }, label = { Text(rq) }) }
            }
        }

        if (results.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                listOf("all" to "All (${results.size})", "movie" to "Movies", "series" to "Series").forEach { (k, lbl) ->
                    FilterChip(selected = filter == k, onClick = { filter = k }, label = { Text(lbl) })
                }
            }
        }
        if (searching) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))

        val filtered = if (filter == "all") results else results.filter { it.type == filter }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(108.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(filtered, key = { "${it.type}_${it.id}" }) { m ->
                PosterCard(m.name, m.poster, m.source ?: m.releaseInfo) { onOpen(m) }
            }
        }
    }
}

@Composable
fun DetailScreen(
    initial: MetaItem,
    addons: List<Addon>,
    jsPlugins: List<JsPlugin>,
    jsRunner: JsRunner,
    store: Store,
    isFav: Boolean,
    contItem: HistoryItem?,
    onToggleFav: (MetaItem) -> Unit,
    onClearHist: (String) -> Unit,
    onPlay: (PlaySession) -> Unit,
    onSolveCf: (String) -> Unit,
    onBack: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var meta by remember { mutableStateOf(initial) }
    var selectedSeason by remember { mutableIntStateOf(1) } // -1 = ALL
    var rangeIdx by remember { mutableIntStateOf(0) }
    var epJump by remember { mutableStateOf("") }
    var loadingStreams by remember { mutableStateOf(false) }
    var streamList by remember { mutableStateOf<List<StreamItem>?>(null) }
    var streamFilter by remember { mutableStateOf("ALL") }
    var activeVid by remember { mutableStateOf(initial.id) }
    var activeEpLabel by remember { mutableStateOf("") }

    LaunchedEffect(initial.id) {
        for (a in addons.filter { Engine.can(it, "meta", initial.type, initial.id) }) {
            val fetched = runCatching { Engine.fetchMeta(a.base, initial.type, initial.id) }.getOrNull()
            if (fetched != null) {
                meta = fetched
                val seasons = fetched.videos.map { it.season }.distinct().sorted()
                selectedSeason = if (fetched.videos.size > 120) -1 else (seasons.firstOrNull { it > 0 } ?: seasons.firstOrNull() ?: 0)
                break
            }
        }
    }

    val findStreams: (String, String, Int, Int) -> Unit = { vid, epLab, sNum, eNum ->
        activeVid = vid
        activeEpLabel = epLab
        loadingStreams = true
        streamList = null
        streamFilter = "ALL"
        scope.launch {
            val stremioJob = async { Engine.fetchStreams(addons, meta.type, vid) }
            val jsJobs = jsPlugins.filter { it.enabled }.map { p ->
                async { runCatching { jsRunner.runScraper(p, meta, sNum, eNum) }.getOrDefault(emptyList()) }
            }
            streamList = stremioJob.await() + jsJobs.flatMap { it.await() }
            loadingStreams = false
        }
    }

    // Episode list after Season + Range Chunking (1-100, 101-200...) + Jump filter
    val seasonEps = remember(meta.videos, selectedSeason) {
        if (selectedSeason == -1) meta.videos else meta.videos.filter { it.season == selectedSeason }
    }
    val chunkedEps = remember(seasonEps, rangeIdx, epJump) {
        val j = epJump.trim().toIntOrNull()
        if (j != null) {
            seasonEps.filterIndexed { idx, ep -> ep.episode == j || (idx + 1) == j }
        } else if (seasonEps.size > 50) {
            val start = rangeIdx * 100
            seasonEps.drop(start).take(100)
        } else seasonEps
    }

    LazyColumn(Modifier.fillMaxSize().background(BgDark), contentPadding = PaddingValues(bottom = 32.dp)) {
        item {
            Box(Modifier.fillMaxWidth()) {
                AsyncImage(model = meta.background ?: meta.poster, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize().blur(18.dp))
                Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Color(0x660A0A0F), BgDark))))
                Column(Modifier.statusBarsPadding().padding(14.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        IconButton(onClick = onBack, modifier = Modifier.background(Color.Black.copy(0.55f), CircleShape)) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Color.White)
                        }
                        if (jsPlugins.any { it.enabled }) {
                            IconButton(onClick = { onSolveCf("https://google.com") }, modifier = Modifier.background(Color.Black.copy(0.55f), CircleShape)) {
                                Icon(Icons.Rounded.Shield, "Bot Check", tint = Color.White)
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        AsyncImage(
                            model = meta.poster, contentDescription = null, contentScale = ContentScale.Crop,
                            modifier = Modifier.width(114.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(12.dp)).background(CardDark)
                        )
                        Column(Modifier.weight(1f)) {
                            Text(meta.name, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = TextWhite)
                            val info = listOfNotNull(meta.imdbRating?.let { "★ $it" }, meta.releaseInfo, meta.runtime).joinToString(" · ")
                            if (info.isNotBlank()) Text(info, color = Color(0xFFFBBF24), fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                            if (meta.genres.isNotEmpty()) Text(meta.genres.take(3).joinToString(", "), color = Color(0xFFD0CDE0), fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                            Spacer(Modifier.height(10.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = {
                                    if (contItem != null) {
                                        val ep = meta.videos.find { it.id == contItem.vid }
                                        findStreams(contItem.vid, contItem.lab, ep?.season ?: 1, ep?.episode ?: 1)
                                    } else if (meta.videos.isNotEmpty()) {
                                        val first = meta.videos.firstOrNull { it.season > 0 } ?: meta.videos.first()
                                        findStreams(first.id, "S${first.season}E${first.episode}", first.season, first.episode)
                                    } else findStreams(meta.id, "", 0, 0)
                                }) {
                                    Icon(Icons.Rounded.PlayArrow, null, modifier = Modifier.size(18.dp))
                                    Text(if (contItem != null) "Continue ${contItem.lab}" else "Find Streams")
                                }
                                IconButton(onClick = { onToggleFav(meta) }, modifier = Modifier.background(Color.White.copy(0.14f), CircleShape)) {
                                    Icon(if (isFav) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, null, tint = if (isFav) AccentPink else Color.White)
                                }
                            }
                            if (contItem != null) {
                                TextButton(onClick = { onClearHist(meta.id) }, contentPadding = PaddingValues(0.dp)) {
                                    Text("Clear progress", fontSize = 12.sp, color = TextMut)
                                }
                            }
                        }
                    }
                    meta.description?.let { Spacer(Modifier.height(10.dp)); Text(it, fontSize = 13.sp, color = Color(0xFFCBC7DC)) }
                }
            }
        }

        if (loadingStreams) {
            item {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("Fetching streams $activeEpLabel…", color = TextWhite)
                }
            }
        }

        streamList?.let { list ->
            val playable = list.filter { it.url != null }
            item { SectionTitle("Streams $activeEpLabel", "${list.size} found") }
            if (list.size > 3) {
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(listOf("ALL", "4K", "1080P", "720P")) { tag ->
                            FilterChip(selected = streamFilter == tag, onClick = { streamFilter = tag }, label = { Text(tag) })
                        }
                    }
                }
            }
            val shown = when (streamFilter) {
                "4K" -> list.filter { it.title.contains("2160p", true) || it.title.contains("4k", true) }
                "1080P" -> list.filter { it.title.contains("1080p", true) }
                "720P" -> list.filter { it.title.contains("720p", true) }
                else -> list
            }
            if (shown.isEmpty()) {
                item { Text("No streams match $streamFilter.", color = TextMut, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) }
            } else {
                items(shown) { s ->
                    val badges = remember(s) { extractStreamBadges(s) }
                    Card(colors = CardDefaults.cardColors(containerColor = CardDark), modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 5.dp)) {
                        Column(Modifier.padding(12.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text(s.addonName.uppercase(), color = AccentPink, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                    badges.forEach { (bTxt, bCol) ->
                                        Text(
                                            bTxt, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = bCol,
                                            modifier = Modifier.clip(RoundedCornerShape(5.dp)).background(bCol.copy(alpha = 0.18f)).padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.height(5.dp))
                            Text(s.title, fontSize = 13.sp, color = TextWhite)
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (s.url != null) {
                                    val playTitle = if (activeEpLabel.isNotBlank()) "${meta.name} $activeEpLabel" else meta.name
                                    Button(onClick = { onPlay(PlaySession(s, playTitle, meta, activeVid, activeEpLabel, playable, meta.videos)) }, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)) {
                                        Text("▶ Play")
                                    }
                                    OutlinedButton(onClick = {
                                        DownloadHelper.enqueue(ctx, playTitle, s)
                                        Toast.makeText(ctx, "Downloading in background…", Toast.LENGTH_SHORT).show()
                                    }) { Text("⬇ Save", color = TextWhite) }
                                    OutlinedButton(onClick = {
                                        val i = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(s.url), "video/*")
                                        runCatching { ctx.startActivity(i) }
                                    }) { Text("External", color = TextWhite) }
                                } else if (s.externalUrl != null) {
                                    OutlinedButton(onClick = { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(s.externalUrl))) } }) {
                                        Text("Open Link", color = TextWhite)
                                    }
                                } else {
                                    Text("Torrent/P2P stream (Needs Debrid addon)", fontSize = 12.sp, color = Color(0xFFFBBF24))
                                }
                            }
                        }
                    }
                }
            }
        }

        if (meta.type == "series" && meta.videos.isNotEmpty()) {
            val seasons = meta.videos.map { it.season }.distinct().sorted()
            item {
                SectionTitle("Episodes", "${meta.videos.size} total")
                LazyRow(contentPadding = PaddingValues(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (meta.videos.size > 40) {
                        item {
                            FilterChip(selected = selectedSeason == -1, onClick = { selectedSeason = -1; rangeIdx = 0 }, label = { Text("All (${meta.videos.size})") })
                        }
                    }
                    items(seasons) { sn ->
                        FilterChip(selected = selectedSeason == sn, onClick = { selectedSeason = sn; rangeIdx = 0 }, label = { Text(if (sn == 0) "Specials" else "Season $sn") })
                    }
                }
            }

            // One Piece / Long Anime 1-100, 101-200 Range Chips + Jump Box
            if (seasonEps.size > 50) {
                item {
                    val chunks = (seasonEps.size + 99) / 100
                    LazyRow(contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(chunks) { cIdx ->
                            val lo = cIdx * 100 + 1
                            val hi = minOf((cIdx + 1) * 100, seasonEps.size)
                            FilterChip(
                                selected = epJump.isBlank() && rangeIdx == cIdx,
                                onClick = { epJump = ""; rangeIdx = cIdx },
                                label = { Text("$lo–$hi") }
                            )
                        }
                    }
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = epJump,
                            onValueChange = { epJump = it.filter { ch -> ch.isDigit() }.take(5) },
                            placeholder = { Text("Jump to Ep # (e.g. 1015)") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        )
                        if (epJump.isNotEmpty()) {
                            Spacer(Modifier.width(8.dp))
                            TextButton(onClick = { epJump = "" }) { Text("Clear") }
                        }
                    }
                }
            }

            items(chunkedEps, key = { it.id }) { ep ->
                val done = store.isDone(ep.id)
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 5.dp)
                        .clip(RoundedCornerShape(12.dp)).background(CardDark)
                        .clickable { findStreams(ep.id, "S${ep.season}E${ep.episode}", ep.season, ep.episode) }
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.width(104.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)).background(BgDark), contentAlignment = Alignment.Center) {
                        Text("S${ep.season}E${ep.episode}", fontSize = 11.sp, color = TextMut)
                        AsyncImage(model = ep.thumbnail, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (done) {
                                Icon(Icons.Rounded.Check, null, tint = Color(0xFF34D399), modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                            }
                            Text(
                                if (selectedSeason == -1) "S${ep.season}E${ep.episode} · ${ep.title}" else "${ep.episode}. ${ep.title}",
                                fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = TextWhite, maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                        }
                        ep.overview?.let { Text(it, fontSize = 12.sp, color = TextMut, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                    }
                }
            }
        }
    }
}

@Composable
fun AddonsScreen(
    addons: List<Addon>,
    jsPlugins: List<JsPlugin>,
    store: Store,
    onAddStremio: suspend (String) -> String?,
    onAddJsRepo: suspend (String) -> String?,
    onToggleJs: (Int) -> Unit,
    onRemoveJs: (Int) -> Unit,
    onRemoveStremio: (Int) -> Unit,
    onMoveStremio: (Int, Int) -> Unit,
    onSolveCf: (String) -> Unit,
    onRestored: () -> Unit,
    onRefresh: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var csPreview by remember { mutableStateOf<Pair<String, List<CsPluginInfo>>?>(null) }
    var cfInput by remember { mutableStateOf("") }
    var backupBox by remember { mutableStateOf("") }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 14.dp), contentPadding = PaddingValues(bottom = 28.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Addons & Scraper Repos", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = TextWhite)
                IconButton(onClick = onRefresh) { Icon(Icons.Rounded.Refresh, "Refresh", tint = TextWhite) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = { Text("Stremio manifest.json or JS Scraper Repo URL") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = {
                    val v = input.trim()
                    if (v.isEmpty()) return@Button
                    status = "Checking…"
                    csPreview = null
                    scope.launch {
                        if (v.startsWith("cloudstreamrepo:", true) || v.endsWith("/repo.json", true)) {
                            runCatching { Engine.inspectCloudStreamRepo(v) }
                                .onSuccess {
                                    csPreview = it
                                    status = "Loaded repo '${it.first}' (${it.second.size} plugins). Note: .cs3 plugins are CloudStream-only binaries; use Nuvio JS repos or Stremio addons to stream here."
                                }
                                .onFailure { status = "Could not inspect repo: ${it.message}" }
                        } else if (v.endsWith("manifest.json", true) || v.startsWith("stremio:", true)) {
                            val stErr = runCatching { onAddStremio(v) }.getOrElse { it.message ?: "Err" }
                            if (stErr == null) { input = ""; status = "Stremio addon added!" }
                            else {
                                // Try as Nuvio JS scraper repo manifest.json
                                val jsErr = runCatching { onAddJsRepo(v) }.getOrElse { it.message ?: stErr }
                                if (jsErr == null) { input = ""; status = "JS Scraper plugins installed!" } else status = jsErr
                            }
                        } else {
                            val jsErr = runCatching { onAddJsRepo(v) }.getOrElse { it.message ?: "Failed" }
                            if (jsErr == null) { input = ""; status = "JS Scraper plugins installed!" } else status = jsErr
                        }
                    }
                }) { Icon(Icons.Rounded.Add, null); Text("Add") }
            }
            if (status.isNotBlank()) Text(status, fontSize = 13.sp, color = AccentPink, modifier = Modifier.padding(top = 6.dp))
        }

        csPreview?.let { (repoName, list) ->
            item {
                SectionTitle("$repoName (Inspector)", "${list.size} .cs3")
            }
            items(list.take(12)) { p ->
                Card(colors = CardDefaults.cardColors(containerColor = CardDark), modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Column(Modifier.padding(10.dp)) {
                        Text(p.name, fontWeight = FontWeight.Bold, color = TextWhite)
                        if (p.tvTypes.isNotBlank()) Text("${p.tvTypes} · .cs3 binary", fontSize = 11.sp, color = TextMut)
                    }
                }
            }
        }

        if (jsPlugins.isNotEmpty()) {
            item { SectionTitle("JS Scraper Plugins", "${jsPlugins.size} installed") }
            items(jsPlugins.size) { i ->
                val p = jsPlugins[i]
                Card(colors = CardDefaults.cardColors(containerColor = CardDark), modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(p.name, fontWeight = FontWeight.Bold, color = TextWhite)
                            Text("v${p.version} · JS Scraper", fontSize = 11.sp, color = TextMut)
                        }
                        Switch(checked = p.enabled, onCheckedChange = { onToggleJs(i) })
                        IconButton(onClick = { onRemoveJs(i) }) { Icon(Icons.Rounded.Delete, "Remove", tint = TextWhite) }
                    }
                }
            }
        }

        item { SectionTitle("Stremio Addons", "${addons.size} installed") }
        items(addons.size) { i ->
            val a = addons[i]
            Card(colors = CardDefaults.cardColors(containerColor = CardDark), modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(a.name, fontWeight = FontWeight.Bold, color = TextWhite, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(if (a.loaded) "${a.catalogs.size} catalogs" else a.base, fontSize = 12.sp, color = TextMut, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (i > 0) IconButton(onClick = { onMoveStremio(i, i - 1) }) { Text("↑", color = TextWhite) }
                    if (i < addons.lastIndex) IconButton(onClick = { onMoveStremio(i, i + 1) }) { Text("↓", color = TextWhite) }
                    IconButton(onClick = { onRemoveStremio(i) }) { Icon(Icons.Rounded.Delete, "Remove", tint = TextWhite) }
                }
            }
        }

        item {
            SectionTitle("Cloudflare / Bot Bypass", "Cookies")
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(value = cfInput, onValueChange = { cfInput = it }, placeholder = { Text("https://site-url.com") }, singleLine = true, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp))
                Spacer(Modifier.width(8.dp))
                Button(onClick = { if (cfInput.isNotBlank()) onSolveCf(if (cfInput.startsWith("http")) cfInput else "https://$cfInput") }) {
                    Icon(Icons.Rounded.Shield, null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Open")
                }
            }

            SectionTitle("Backup & Restore", "JSON")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val data = store.exportBackupJson()
                    backupBox = data
                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("streamer_backup", data))
                    Toast.makeText(ctx, "Backup copied!", Toast.LENGTH_SHORT).show()
                }) { Text("Copy Backup") }
                OutlinedButton(onClick = {
                    runCatching { store.importBackupJson(backupBox) }
                        .onSuccess { onRestored(); Toast.makeText(ctx, "Restored!", Toast.LENGTH_SHORT).show() }
                        .onFailure { Toast.makeText(ctx, "Invalid backup JSON", Toast.LENGTH_SHORT).show() }
                }) { Text("Restore", color = TextWhite) }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(value = backupBox, onValueChange = { backupBox = it }, placeholder = { Text("Paste backup JSON here") }, minLines = 2, maxLines = 4)
        }
    }
}

@Composable
fun PlayerScreen(
    session: PlaySession,
    addons: List<Addon>,
    jsRunner: JsRunner,
    jsPlugins: List<JsPlugin>,
    store: Store,
    party: PartyClient,
    incomingSync: Pair<String, Long>?,
    incomingEmoji: String?,
    onConsumeSync: () -> Unit,
    onConsumeEmoji: () -> Unit,
    onSwitchSession: (PlaySession) -> Unit,
    onSaveProgress: (Long, Long) -> Unit,
    onClose: () -> Unit
) {
    val ctx = LocalContext.current
    val view = LocalView.current
    val activity = remember(ctx) { ctx.findActivity() }
    val audioMgr = remember { ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val scope = rememberCoroutineScope()

    var showControls by remember { mutableStateOf(true) }
    var locked by remember { mutableStateOf(false) }
    var resizeMode by remember { mutableIntStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT) }
    var speed by remember { mutableFloatStateOf(1f) }
    var posMs by remember { mutableLongStateOf(0L) }
    var durMs by remember { mutableLongStateOf(0L) }
    var isPlaying by remember { mutableStateOf(true) }
    var isSeeking by remember { mutableStateOf(false) }
    var hudText by remember { mutableStateOf<String?>(null) }
    var errMsg by remember { mutableStateOf<String?>(null) }
    var tracks by remember { mutableStateOf(Tracks.EMPTY) }
    var sheetMode by remember { mutableStateOf<String?>(null) }
    var epRangeIdx by remember { mutableIntStateOf(0) }

    var extSubs by remember { mutableStateOf<List<SubItem>>(emptyList()) }
    var activeCues by remember { mutableStateOf<List<SubCue>>(emptyList()) }
    var activeExtLabel by remember { mutableStateOf("None") }
    var subOffsetMs by remember { mutableLongStateOf(0L) }
    var subPrefs by remember { mutableStateOf(store.getSubPrefs()) }
    var playerSize by remember { mutableStateOf(IntSize(1080, 720)) }
    var ignorePartyUntil by remember { mutableLongStateOf(0L) }

    val localSubPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                val txt = ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
                activeCues = Engine.parseSubtitles(txt)
                activeExtLabel = "Local File"
                sheetMode = null
            }
        }
    }

    val exoPlayer = remember(session.stream.url) {
        val dsFactory = OkHttpDataSource.Factory(Engine.http).apply {
            if (session.stream.headers.isNotEmpty()) setDefaultRequestProperties(session.stream.headers)
        }
        ExoPlayer.Builder(ctx)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dsFactory))
            .build().apply {
                setMediaItem(MediaItem.fromUri(session.stream.url ?: ""))
                prepare()
                val saved = store.getPos(session.vid)
                if (saved > 15_000L && !session.fromParty) seekTo(saved)
                playWhenReady = true
            }
    }

    LaunchedEffect(incomingSync) {
        val sync = incomingSync ?: return@LaunchedEffect
        ignorePartyUntil = System.currentTimeMillis() + 900L
        if (abs(exoPlayer.currentPosition - sync.second) > 1600L) exoPlayer.seekTo(sync.second)
        if (sync.first == "play") exoPlayer.play() else exoPlayer.pause()
        onConsumeSync()
    }

    LaunchedEffect(incomingEmoji) {
        val em = incomingEmoji ?: return@LaunchedEffect
        hudText = em
        onConsumeEmoji()
    }

    LaunchedEffect(session.vid) {
        extSubs = Engine.fetchSubtitles(addons, session.meta.type, session.vid)
    }

    DisposableEffect(exoPlayer) {
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        val w = activity?.window
        val ctrl = w?.let { WindowCompat.getInsetsController(it, view) }
        ctrl?.hide(WindowInsetsCompat.Type.systemBars())
        ctrl?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        val listener = object : Player.Listener {
            override fun onTracksChanged(t: Tracks) { tracks = t }
            override fun onIsPlayingChanged(playing: Boolean) {
                if (party.code != null && System.currentTimeMillis() > ignorePartyUntil) {
                    party.send(JSONObject().put("t", "sync").put("state", if (playing) "play" else "pause").put("time", exoPlayer.currentPosition))
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                errMsg = error.localizedMessage ?: "Playback failed"
            }
        }
        exoPlayer.addListener(listener)

        onDispose {
            onSaveProgress(exoPlayer.currentPosition, exoPlayer.duration)
            exoPlayer.removeListener(listener)
            exoPlayer.release()
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            activity?.window?.attributes = activity?.window?.attributes?.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
            ctrl?.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    LaunchedEffect(exoPlayer) {
        var lastSave = 0L
        while (true) {
            if (!isSeeking) {
                posMs = exoPlayer.currentPosition.coerceAtLeast(0L)
                durMs = exoPlayer.duration.coerceAtLeast(0L)
            }
            isPlaying = exoPlayer.isPlaying
            if (durMs > 0 && System.currentTimeMillis() - lastSave > 5000L) {
                lastSave = System.currentTimeMillis()
                onSaveProgress(posMs, durMs)
            }
            delay(250L)
        }
    }

    LaunchedEffect(showControls, isPlaying, sheetMode) {
        if (showControls && isPlaying && sheetMode == null) {
            delay(3500L)
            showControls = false
        }
    }

    LaunchedEffect(hudText) {
        if (hudText != null) {
            delay(950L)
            hudText = null
        }
    }

    BackHandler { if (sheetMode != null) sheetMode = null else onClose() }

    val sendSeek: (Long) -> Unit = { target ->
        exoPlayer.seekTo(target)
        if (party.code != null && System.currentTimeMillis() > ignorePartyUntil) {
            party.send(JSONObject().put("t", "sync").put("state", if (exoPlayer.isPlaying) "play" else "pause").put("time", target))
        }
    }

    val loadEpisode: (VideoEp) -> Unit = { ep ->
        scope.launch {
            hudText = "Loading S${ep.season}E${ep.episode}…"
            val ss = (Engine.fetchStreams(addons, session.meta.type, ep.id) +
                jsPlugins.filter { it.enabled }.flatMap { runCatching { jsRunner.runScraper(it, session.meta, ep.season, ep.episode) }.getOrDefault(emptyList()) })
                .filter { it.url != null }
            val pick = ss.firstOrNull { it.addonName == session.stream.addonName } ?: ss.firstOrNull()
            if (pick != null) {
                val lab = "S${ep.season}E${ep.episode}"
                onSwitchSession(PlaySession(pick, "${session.meta.name} $lab", session.meta, ep.id, lab, ss, session.episodes))
            } else hudText = "No stream found"
        }
    }

    Surface(Modifier.fillMaxSize(), color = Color.Black, contentColor = Color.White) {
        Box(Modifier.fillMaxSize().onSizeChanged { playerSize = it }) {
            AndroidView(
                factory = { c -> PlayerView(c).apply { useController = false; keepScreenOn = true; player = exoPlayer; this.resizeMode = resizeMode } },
                update = { v -> v.player = exoPlayer; v.resizeMode = resizeMode },
                modifier = Modifier.fillMaxSize()
            )

            var dragMode by remember { mutableStateOf<String?>(null) }
            var dragStartX by remember { mutableFloatStateOf(0f) }
            var dragAccumY by remember { mutableFloatStateOf(0f) }
            var dragAccumX by remember { mutableFloatStateOf(0f) }
            var startPos by remember { mutableLongStateOf(0L) }
            var startBright by remember { mutableFloatStateOf(0.5f) }
            var startVol by remember { mutableIntStateOf(5) }

            Box(
                Modifier.fillMaxSize()
                    .pointerInput(locked) {
                        detectTapGestures(
                            onTap = { if (sheetMode != null) sheetMode = null else showControls = !showControls },
                            onDoubleTap = { offset ->
                                if (!locked) {
                                    val delta = if (offset.x < playerSize.width / 2f) -10_000L else 10_000L
                                    val target = (exoPlayer.currentPosition + delta).coerceIn(0L, durMs.coerceAtLeast(1L))
                                    sendSeek(target)
                                    hudText = if (delta < 0) "⏪ -10s" else "+10s ⏩"
                                }
                            }
                        )
                    }
                    .pointerInput(locked) {
                        if (locked) return@pointerInput
                        detectDragGestures(
                            onDragStart = { start ->
                                dragMode = null; dragStartX = start.x; dragAccumX = 0f; dragAccumY = 0f
                                startPos = exoPlayer.currentPosition
                                val curB = activity?.window?.attributes?.screenBrightness ?: -1f
                                startBright = if (curB < 0f) 0.5f else curB
                                startVol = audioMgr.getStreamVolume(AudioManager.STREAM_MUSIC)
                            },
                            onDrag = { change, amount ->
                                change.consume(); dragAccumX += amount.x; dragAccumY += amount.y
                                if (dragMode == null && (abs(dragAccumX) > 20f || abs(dragAccumY) > 20f)) {
                                    dragMode = if (abs(dragAccumX) > abs(dragAccumY)) "seek"
                                    else if (dragStartX < playerSize.width / 2f) "bright" else "vol"
                                }
                                when (dragMode) {
                                    "seek" -> {
                                        val deltaMs = (dragAccumX * 120f).toLong()
                                        val target = (startPos + deltaMs).coerceIn(0L, durMs.coerceAtLeast(1L))
                                        posMs = target; isSeeking = true
                                        hudText = "${fmtMs(target)} (${if (deltaMs >= 0) "+" else ""}${deltaMs / 1000}s)"
                                    }
                                    "bright" -> {
                                        val nb = (startBright - dragAccumY / 450f).coerceIn(0.05f, 1f)
                                        activity?.window?.attributes = activity?.window?.attributes?.apply { screenBrightness = nb }
                                        hudText = "☀ ${(nb * 100).toInt()}%"
                                    }
                                    "vol" -> {
                                        val maxV = audioMgr.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                                        val nv = (startVol - (dragAccumY / 35f).toInt()).coerceIn(0, maxV)
                                        audioMgr.setStreamVolume(AudioManager.STREAM_MUSIC, nv, 0)
                                        hudText = "🔊 ${(nv * 100 / maxV)}%"
                                    }
                                }
                            },
                            onDragEnd = { if (dragMode == "seek") { sendSeek(posMs); isSeeking = false }; dragMode = null },
                            onDragCancel = { isSeeking = false; dragMode = null }
                        )
                    }
            )

            // Customizable External Subtitle Overlay
            if (activeCues.isNotEmpty()) {
                val t = posMs - subOffsetMs
                val text = activeCues.filter { t in it.startMs..it.endMs }.joinToString("\n") { it.text }
                if (text.isNotBlank()) {
                    val subCol = Color(subPrefs.colorHex)
                    val bgCol = when (subPrefs.bgStyle) {
                        0 -> Color.Black.copy(alpha = 0.74f)
                        else -> Color.Transparent
                    }
                    val shadow = if (subPrefs.bgStyle == 1) Shadow(Color.Black, blurRadius = 6f) else null
                    Text(
                        text,
                        color = subCol,
                        fontSize = subPrefs.sizeSp.sp,
                        textAlign = TextAlign.Center,
                        style = TextStyle(shadow = shadow),
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(horizontal = 24.dp, vertical = if (showControls) (subPrefs.bottomDp + 72).dp else subPrefs.bottomDp.dp)
                            .background(bgCol, RoundedCornerShape(8.dp))
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                    )
                }
            }

            hudText?.let {
                Surface(color = Color.Black.copy(alpha = 0.8f), contentColor = Color.White, shape = CircleShape, modifier = Modifier.align(Alignment.TopCenter).padding(top = 64.dp)) {
                    Text(it, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                }
            }

            if (showControls) {
                if (locked) {
                    IconButton(onClick = { locked = false }, modifier = Modifier.align(Alignment.TopStart).padding(16.dp).background(Color.Black.copy(0.65f), CircleShape)) {
                        Icon(Icons.Rounded.LockOpen, "Unlock", tint = Color.White)
                    }
                } else {
                    Column(
                        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent, Color(0xD8000000)))).padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Close", tint = Color.White) }
                            Text(session.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            if (party.code != null) {
                                listOf("❤️", "😂", "😮", "👏").forEach { em ->
                                    TextButton(onClick = { party.send(JSONObject().put("t", "emo").put("e", em)); hudText = em }) { Text(em, fontSize = 16.sp) }
                                }
                            }
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                IconButton(onClick = {
                                    activity?.enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build())
                                }) { Icon(Icons.Rounded.PictureInPictureAlt, "PiP", tint = Color.White) }
                            }
                            IconButton(onClick = { locked = true }) { Icon(Icons.Rounded.Lock, "Lock", tint = Color.White) }
                        }

                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { sendSeek((exoPlayer.currentPosition - 10_000L).coerceAtLeast(0L)) }, modifier = Modifier.size(52.dp).background(Color.Black.copy(0.55f), CircleShape)) {
                                Icon(Icons.Rounded.Replay10, "-10s", tint = Color.White, modifier = Modifier.size(28.dp))
                            }
                            Spacer(Modifier.width(28.dp))
                            IconButton(onClick = { if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play() }, modifier = Modifier.size(66.dp).background(AccentPink, CircleShape)) {
                                Icon(if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Play/Pause", tint = Color(0xFF19040C), modifier = Modifier.size(36.dp))
                            }
                            Spacer(Modifier.width(28.dp))
                            IconButton(onClick = { sendSeek(exoPlayer.currentPosition + 10_000L) }, modifier = Modifier.size(52.dp).background(Color.Black.copy(0.55f), CircleShape)) {
                                Icon(Icons.Rounded.Forward10, "+10s", tint = Color.White, modifier = Modifier.size(28.dp))
                            }
                        }

                        Column {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(fmtMs(posMs), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                PlayerSeekBar(posMs, durMs, { isSeeking = true; posMs = it }, { sendSeek(it); posMs = it; isSeeking = false }, Modifier.weight(1f).padding(horizontal = 12.dp))
                                Text(fmtMs(durMs), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                            }
                            Spacer(Modifier.height(4.dp))
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                item { PlayerPill("+85s", null) { sendSeek(exoPlayer.currentPosition + 85_000L); hudText = "+85s" } }
                                item { PlayerPill("Audio", Icons.Rounded.Audiotrack) { sheetMode = "audio" } }
                                item { PlayerPill("Quality", Icons.Rounded.HighQuality) { sheetMode = "quality" } }
                                item { PlayerPill("Subs", Icons.Rounded.Subtitles) { sheetMode = "subs" } }
                                if (session.allStreams.size > 1) item { PlayerPill("Sources (${session.allStreams.size})", null) { sheetMode = "sources" } }
                                if (session.episodes.isNotEmpty()) {
                                    val idx = session.episodes.indexOfFirst { it.id == session.vid }
                                    val nx = if (idx in 0 until session.episodes.lastIndex) session.episodes[idx + 1] else null
                                    if (nx != null) item { PlayerPill("Next", Icons.Rounded.SkipNext) { loadEpisode(nx) } }
                                    item { PlayerPill("Episodes", Icons.AutoMirrored.Rounded.List) { sheetMode = "episodes" } }
                                }
                                item {
                                    PlayerPill("${speed}x", Icons.Rounded.Speed) {
                                        val sp = listOf(1f, 1.25f, 1.5f, 2f, 0.75f)
                                        speed = sp[(sp.indexOf(speed) + 1) % sp.size]
                                        exoPlayer.setPlaybackSpeed(speed); hudText = "${speed}x"
                                    }
                                }
                                item {
                                    PlayerPill("Fit", Icons.Rounded.AspectRatio) {
                                        val modes = listOf(AspectRatioFrameLayout.RESIZE_MODE_FIT to "Fit", AspectRatioFrameLayout.RESIZE_MODE_ZOOM to "Zoom", AspectRatioFrameLayout.RESIZE_MODE_FILL to "Stretch")
                                        val nxt = modes[(modes.indexOfFirst { it.first == resizeMode } + 1) % modes.size]
                                        resizeMode = nxt.first; hudText = nxt.second
                                    }
                                }
                            }
                        }
                    }
                }
            }

            errMsg?.let { em ->
                Column(Modifier.fillMaxSize().background(Color.Black.copy(0.88f)).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Icon(Icons.Rounded.Warning, null, tint = AccentPink, modifier = Modifier.size(40.dp))
                    Spacer(Modifier.height(10.dp))
                    Text("Stream Failed: $em", color = Color.White, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (session.allStreams.size > 1) Button(onClick = { errMsg = null; sheetMode = "sources" }) { Text("Switch Source") }
                        OutlinedButton(onClick = { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(session.stream.url), "video/*")) } }) { Text("Open in VLC", color = Color.White) }
                        OutlinedButton(onClick = onClose) { Text("Back", color = Color.White) }
                    }
                }
            }

            sheetMode?.let { mode ->
                Surface(color = Color(0xF5141420), contentColor = Color.White, shape = RoundedCornerShape(16.dp), modifier = Modifier.align(Alignment.CenterEnd).padding(14.dp).widthIn(max = 380.dp).fillMaxHeight(0.88f)) {
                    Column(Modifier.padding(14.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(mode.uppercase(), fontWeight = FontWeight.Bold, color = AccentPink)
                            IconButton(onClick = { sheetMode = null }) { Icon(Icons.Rounded.Close, "Close", tint = Color.White) }
                        }
                        HorizontalDivider(color = Color.White.copy(0.14f))
                        Spacer(Modifier.height(8.dp))

                        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            when (mode) {
                                "audio" -> {
                                    val audioGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
                                    if (audioGroups.isEmpty()) item { Text("No switchable audio tracks found.", color = TextMut) }
                                    else audioGroups.forEach { g ->
                                        items(g.length) { i ->
                                            val f = g.getTrackFormat(i)
                                            val lbl = listOfNotNull(f.label, f.language?.uppercase(), f.codecs, "${f.channelCount}ch").joinToString(" · ")
                                            OptionRow(lbl.ifBlank { "Audio Track ${i + 1}" }, g.isTrackSelected(i)) {
                                                exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false).setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i)).build()
                                                sheetMode = null
                                            }
                                        }
                                    }
                                }
                                "quality" -> {
                                    item {
                                        OptionRow("Auto (Best Available)", exoPlayer.trackSelectionParameters.overrides.isEmpty()) {
                                            exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_VIDEO).build()
                                            sheetMode = null
                                        }
                                    }
                                    tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }.forEach { g ->
                                        items(g.length) { i ->
                                            val f = g.getTrackFormat(i)
                                            val lbl = "${if (f.height > 0) "${f.height}p" else "Video $i"} ${if (f.bitrate > 0) "· ${f.bitrate / 1000} kbps" else ""}"
                                            OptionRow(lbl, g.isTrackSelected(i)) {
                                                exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon().setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i)).build()
                                                sheetMode = null
                                            }
                                        }
                                    }
                                }
                                "subs" -> {
                                    item {
                                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                                OutlinedButton(onClick = { subOffsetMs -= 500L }) { Text("-0.5s", color = Color.White) }
                                                OutlinedButton(onClick = { subOffsetMs += 500L }) { Text("+0.5s", color = Color.White) }
                                                Text("${subOffsetMs / 1000f}s", color = Color.White, fontSize = 12.sp)
                                            }
                                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                OutlinedButton(onClick = {
                                                    val nextSz = listOf(14, 18, 22, 26).let { it[(it.indexOf(subPrefs.sizeSp) + 1) % it.size] }
                                                    subPrefs = subPrefs.copy(sizeSp = nextSz).also { store.saveSubPrefs(it) }
                                                }) { Text("Size ${subPrefs.sizeSp}sp", color = Color.White) }
                                                OutlinedButton(onClick = {
                                                    val cols = listOf(0xFFFFFFFF, 0xFFFFEA00, 0xFF00E5FF, 0xFF69F0AE)
                                                    val nc = cols[(cols.indexOf(subPrefs.colorHex) + 1) % cols.size]
                                                    subPrefs = subPrefs.copy(colorHex = nc).also { store.saveSubPrefs(it) }
                                                }) { Text("Color", color = Color(subPrefs.colorHex)) }
                                                OutlinedButton(onClick = {
                                                    subPrefs = subPrefs.copy(bgStyle = (subPrefs.bgStyle + 1) % 3).also { store.saveSubPrefs(it) }
                                                }) { Text(listOf("Box", "Outline", "Clear")[subPrefs.bgStyle], color = Color.White) }
                                            }
                                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                OutlinedButton(onClick = {
                                                    val posList = listOf(24, 52, 84)
                                                    val np = posList[(posList.indexOf(subPrefs.bottomDp) + 1) % posList.size]
                                                    subPrefs = subPrefs.copy(bottomDp = np).also { store.saveSubPrefs(it) }
                                                }) { Text("Pos: ${if (subPrefs.bottomDp == 24) "Low" else if (subPrefs.bottomDp == 52) "Mid" else "High"}", color = Color.White) }
                                            }
                                        }
                                    }
                                    item {
                                        OptionRow("Turn All Subtitles Off", activeExtLabel == "None" && exoPlayer.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)) {
                                            activeCues = emptyList(); activeExtLabel = "None"
                                            exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
                                            sheetMode = null
                                        }
                                    }
                                    item { OptionRow("Load local .srt / .vtt from phone", activeExtLabel == "Local File") { localSubPicker.launch("*/*") } }
                                    tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }.forEach { g ->
                                        items(g.length) { i ->
                                            val f = g.getTrackFormat(i)
                                            val lbl = "Embedded: ${listOfNotNull(f.label, f.language?.uppercase()).joinToString(" ")}"
                                            OptionRow(lbl, g.isTrackSelected(i) && activeExtLabel == "None") {
                                                activeCues = emptyList(); activeExtLabel = "None"
                                                exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i)).build()
                                                sheetMode = null
                                            }
                                        }
                                    }
                                    items(extSubs) { sub ->
                                        val lbl = "${sub.lang.uppercase()} · ${sub.id.take(18)}"
                                        OptionRow(lbl, activeExtLabel == lbl) {
                                            scope.launch {
                                                runCatching { Engine.fetchText(sub.url) }.onSuccess {
                                                    activeCues = Engine.parseSubtitles(it); activeExtLabel = lbl
                                                    exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
                                                    sheetMode = null
                                                }.onFailure { hudText = "Subtitle failed" }
                                            }
                                        }
                                    }
                                }
                                "sources" -> {
                                    items(session.allStreams) { s ->
                                        val cur = s.url == session.stream.url
                                        OptionRow("${s.addonName}: ${s.title.replace("\n", " · ").take(75)}", cur) {
                                            sheetMode = null; if (!cur) onSwitchSession(session.copy(stream = s))
                                        }
                                    }
                                }
                                "episodes" -> {
                                    if (session.episodes.size > 50) {
                                        item {
                                            val chunks = (session.episodes.size + 99) / 100
                                            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                items(chunks) { cIdx ->
                                                    FilterChip(selected = epRangeIdx == cIdx, onClick = { epRangeIdx = cIdx }, label = { Text("${cIdx * 100 + 1}–${minOf((cIdx + 1) * 100, session.episodes.size)}") })
                                                }
                                            }
                                        }
                                    }
                                    val epSlice = if (session.episodes.size > 50) session.episodes.drop(epRangeIdx * 100).take(100) else session.episodes
                                    items(epSlice) { ep ->
                                        val lb = "S${ep.season}E${ep.episode}"
                                        OptionRow("$lb · ${ep.title}", ep.id == session.vid) {
                                            sheetMode = null; if (ep.id != session.vid) loadEpisode(ep)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PlayerSeekBar(posMs: Long, durMs: Long, onSeekPreview: (Long) -> Unit, onSeekCommit: (Long) -> Unit, modifier: Modifier = Modifier) {
    var widthPx by remember { mutableIntStateOf(1) }
    val frac = if (durMs > 0L) (posMs.toFloat() / durMs).coerceIn(0f, 1f) else 0f

    BoxWithConstraints(
        modifier.height(28.dp).onSizeChanged { widthPx = it.width.coerceAtLeast(1) }
            .pointerInput(durMs) { detectTapGestures { offset -> if (durMs > 0L) onSeekCommit(((offset.x / widthPx).coerceIn(0f, 1f) * durMs).toLong()) } }
            .pointerInput(durMs) {
                var dragX = 0f
                detectHorizontalDragGestures(
                    onDragStart = { start -> dragX = start.x; if (durMs > 0L) onSeekPreview(((dragX / widthPx).coerceIn(0f, 1f) * durMs).toLong()) },
                    onHorizontalDrag = { _, delta -> dragX = (dragX + delta).coerceIn(0f, widthPx.toFloat()); if (durMs > 0L) onSeekPreview(((dragX / widthPx).coerceIn(0f, 1f) * durMs).toLong()) },
                    onDragEnd = { if (durMs > 0L) onSeekCommit(((dragX / widthPx).coerceIn(0f, 1f) * durMs).toLong()) }
                )
            },
        contentAlignment = Alignment.CenterStart
    ) {
        Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.28f)))
        Box(Modifier.fillMaxWidth(frac).height(4.dp).clip(CircleShape).background(AccentPink))
        val thumbX = (maxWidth * frac - 7.dp).coerceIn(0.dp, (maxWidth - 14.dp).coerceAtLeast(0.dp))
        Box(Modifier.offset(x = thumbX).size(14.dp).clip(CircleShape).background(AccentPink))
    }
}

@Composable
fun PlayerPill(text: String, icon: ImageVector?, onClick: () -> Unit) {
    Row(Modifier.clip(CircleShape).background(Color.White.copy(alpha = 0.14f)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) { Icon(icon, null, tint = Color.White, modifier = Modifier.size(15.dp)); Spacer(Modifier.width(5.dp)) }
        Text(text, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun OptionRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .background(if (selected) AccentPink.copy(alpha = 0.24f) else Color.White.copy(alpha = 0.06f))
            .clickable(onClick = onClick).padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 13.sp, color = Color.White, modifier = Modifier.weight(1f))
        if (selected) Icon(Icons.Rounded.Check, null, tint = AccentPink, modifier = Modifier.size(18.dp))
    }
}
