@file:OptIn(androidx.media3.common.util.UnstableApi::class, ExperimentalMaterial3Api::class)

package com.streamer.app

import android.app.Activity
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
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
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
    val episodes: List<VideoEp>
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val store = Store(applicationContext)
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
                    StreamerApp(store)
                }
            }
        }
    }
}

@Composable
fun StreamerApp(store: Store) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var tab by remember { mutableStateOf("home") }
    var addons by remember { mutableStateOf(store.getAddonBases().map { Addon(it, it) }) }
    var favs by remember { mutableStateOf(store.getFavs()) }
    var history by remember { mutableStateOf(store.getHistory()) }
    var selectedItem by remember { mutableStateOf<MetaItem?>(null) }
    var activePlayer by remember { mutableStateOf<PlaySession?>(null) }

    val refreshAllAddons: () -> Unit = {
        scope.launch {
            addons = addons.map { a ->
                runCatching { Engine.fetchManifest(a.base) }.getOrElse { a }
            }
        }
    }

    LaunchedEffect(Unit) { refreshAllAddons() }

    if (activePlayer != null) {
        val session = activePlayer!!
        PlayerScreen(
            session = session,
            addons = addons,
            store = store,
            onSwitchSession = { activePlayer = it },
            onSaveProgress = { posMs, durMs ->
                store.setPos(session.vid, posMs)
                val rest = history.filter { it.id != session.meta.id }
                val updated = if (durMs > 0 && posMs.toFloat() / durMs > 0.92f) {
                    store.setDone(session.vid)
                    val idx = session.episodes.indexOfFirst { it.id == session.vid }
                    val nx = if (session.meta.type == "series" && idx in 0 until session.episodes.lastIndex) session.episodes[idx + 1] else null
                    if (nx != null) {
                        listOf(HistoryItem(session.meta.id, session.meta.type, session.meta.name, session.meta.poster, nx.id, "S${nx.season}E${nx.episode}", 0L, 0L)) + rest
                    } else rest
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
            onBack = { selectedItem = null }
        )
        return
    }

    Scaffold(
        containerColor = BgDark,
        contentColor = TextWhite,
        topBar = {
            Row(
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(30.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(Brush.linearGradient(listOf(AccentPink, AccentPurple))),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(10.dp))
                Text("Streamer", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = TextWhite)
            }
        },
        bottomBar = {
            NavigationBar(containerColor = Color(0xFF0E0E16)) {
                NavigationBarItem(selected = tab == "home", onClick = { tab = "home" }, icon = { Icon(Icons.Rounded.Home, null) }, label = { Text("Home") })
                NavigationBarItem(selected = tab == "search", onClick = { tab = "search" }, icon = { Icon(Icons.Rounded.Search, null) }, label = { Text("Search") })
                NavigationBarItem(selected = tab == "addons", onClick = { tab = "addons" }, icon = { Icon(Icons.Rounded.Extension, null) }, label = { Text("Addons") })
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                "home" -> HomeScreen(addons, history, favs, onOpen = { selectedItem = it }, onGoAddons = { tab = "addons" })
                "search" -> SearchScreen(addons, store, onOpen = { selectedItem = it })
                "addons" -> AddonsScreen(
                    addons = addons,
                    onAdd = { raw ->
                        if (raw.startsWith("cloudstreamrepo:", true) || raw.endsWith("/repo.json", true)) {
                            return@AddonsScreen "CloudStream (.cs3) repo detected! JS Scraper Engine is coming in Step 2 — for now, paste a Stremio manifest.json link."
                        }
                        val base = Engine.toBase(raw)
                        if (addons.any { it.base == base }) return@AddonsScreen "Addon already installed."
                        val loaded = Engine.fetchManifest(base)
                        addons = addons + loaded
                        store.saveAddonBases(addons.map { it.base })
                        null
                    },
                    onRemove = { idx ->
                        addons = addons.toMutableList().also { it.removeAt(idx) }
                        store.saveAddonBases(addons.map { it.base })
                    },
                    onMove = { from, to ->
                        val m = addons.toMutableList()
                        val item = m.removeAt(from)
                        m.add(to, item)
                        addons = m
                        store.saveAddonBases(addons.map { it.base })
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
fun HomeScreen(
    addons: List<Addon>,
    history: List<HistoryItem>,
    favs: List<MetaItem>,
    onOpen: (MetaItem) -> Unit,
    onGoAddons: () -> Unit
) {
    val catalogs = remember(addons) {
        addons.flatMap { a ->
            a.catalogs.filter { !it.extraRequired }.map { a to it }
        }.take(10)
    }

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
                Box(
                    Modifier
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                        .fillMaxWidth()
                        .height(220.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(CardDark)
                )
            } else {
                Box(
                    Modifier
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                        .fillMaxWidth()
                        .height(230.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(CardDark)
                        .clickable { onOpen(f) }
                ) {
                    AsyncImage(
                        model = f.background ?: f.poster,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                    Box(
                        Modifier.fillMaxSize().background(
                            Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC0A0A0F), BgDark))
                        )
                    )
                    Column(Modifier.align(Alignment.BottomStart).padding(16.dp)) {
                        Text("FEATURED ${f.type.uppercase()}", color = AccentPink, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Text(f.name, color = TextWhite, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        f.description?.let {
                            Text(it, fontSize = 12.sp, color = Color(0xFFD0CDE0), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { onOpen(f) }, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)) {
                            Icon(Icons.Rounded.PlayArrow, null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Watch / Details")
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
                        PosterCard(
                            name = h.name,
                            poster = h.poster,
                            sub = h.lab.ifBlank { null },
                            progress = if (h.durMs > 0) h.posMs.toFloat() / h.durMs else 0f,
                            onClick = { onOpen(MetaItem(h.id, h.type, h.name, h.poster)) }
                        )
                    }
                }
            }
        }

        if (favs.isNotEmpty()) {
            item { SectionTitle("My List", "${favs.size} saved") }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(favs, key = { "${it.type}_${it.id}" }) { m ->
                        PosterCard(m.name, m.poster, m.imdbRating?.let { "★ $it" }, onClick = { onOpen(m) })
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
                            PosterCard(m.name, m.poster, m.imdbRating?.let { "★ $it" }, onClick = { onOpen(m) })
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
            Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(12.dp))
                .background(CardDark)
                .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
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
        if (!sub.isNullOrBlank()) {
            Text(sub, fontSize = 11.sp, color = TextMut, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
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
                    runCatching { Engine.fetchCatalog(a.base, c.type, c.id, trimmed) }
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
                items(recents) { rq ->
                    AssistChip(onClick = { query = rq; runSearch(rq) }, label = { Text(rq) })
                }
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
                PosterCard(m.name, m.poster, m.source ?: m.releaseInfo, onClick = { onOpen(m) })
            }
        }
    }
}

@Composable
fun DetailScreen(
    initial: MetaItem,
    addons: List<Addon>,
    store: Store,
    isFav: Boolean,
    contItem: HistoryItem?,
    onToggleFav: (MetaItem) -> Unit,
    onClearHist: (String) -> Unit,
    onPlay: (PlaySession) -> Unit,
    onBack: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var meta by remember { mutableStateOf(initial) }
    var selectedSeason by remember { mutableIntStateOf(1) }
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
                selectedSeason = seasons.firstOrNull { it > 0 } ?: seasons.firstOrNull() ?: 0
                break
            }
        }
    }

    val findStreams: (String, String) -> Unit = { vid, epLab ->
        activeVid = vid
        activeEpLabel = epLab
        loadingStreams = true
        streamList = null
        streamFilter = "ALL"
        scope.launch {
            streamList = Engine.fetchStreams(addons, meta.type, vid)
            loadingStreams = false
        }
    }

    LazyColumn(Modifier.fillMaxSize().background(BgDark), contentPadding = PaddingValues(bottom = 32.dp)) {
        item {
            Box(Modifier.fillMaxWidth()) {
                AsyncImage(
                    model = meta.background ?: meta.poster,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize().blur(18.dp)
                )
                Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Color(0x660A0A0F), BgDark))))
                Column(Modifier.statusBarsPadding().padding(14.dp)) {
                    IconButton(onClick = onBack, modifier = Modifier.background(Color.Black.copy(0.55f), CircleShape)) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Color.White)
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        AsyncImage(
                            model = meta.poster,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.width(114.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(12.dp)).background(CardDark)
                        )
                        Column(Modifier.weight(1f)) {
                            Text(meta.name, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = TextWhite)
                            val info = listOfNotNull(meta.imdbRating?.let { "★ $it" }, meta.releaseInfo, meta.runtime).joinToString(" · ")
                            if (info.isNotBlank()) Text(info, color = Color(0xFFFBBF24), fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                            if (meta.genres.isNotEmpty()) {
                                Text(meta.genres.take(3).joinToString(", "), color = Color(0xFFD0CDE0), fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                            }
                            Spacer(Modifier.height(10.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = {
                                    if (contItem != null) findStreams(contItem.vid, contItem.lab)
                                    else if (meta.videos.isNotEmpty()) {
                                        val first = meta.videos.firstOrNull { it.season > 0 } ?: meta.videos.first()
                                        findStreams(first.id, "S${first.season}E${first.episode}")
                                    } else findStreams(meta.id, "")
                                }) {
                                    Icon(Icons.Rounded.PlayArrow, null, modifier = Modifier.size(18.dp))
                                    Text(if (contItem != null) "Continue ${contItem.lab}" else "Find Streams")
                                }
                                IconButton(
                                    onClick = { onToggleFav(meta) },
                                    modifier = Modifier.background(Color.White.copy(0.14f), CircleShape)
                                ) {
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
                    meta.description?.let {
                        Spacer(Modifier.height(10.dp))
                        Text(it, fontSize = 13.sp, color = Color(0xFFCBC7DC))
                    }
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
                            FilterChip(
                                selected = streamFilter == tag,
                                onClick = { streamFilter = tag },
                                label = { Text(tag) }
                            )
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
                item {
                    Text(
                        "No streams match $streamFilter.",
                        color = TextMut,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                    )
                }
            } else {
                items(shown) { s ->
                    val badges = remember(s) { extractStreamBadges(s) }
                    Card(
                        colors = CardDefaults.cardColors(containerColor = CardDark),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 5.dp)
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text(s.addonName.uppercase(), color = AccentPink, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                    badges.forEach { (bTxt, bCol) ->
                                        Text(
                                            bTxt,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = bCol,
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(5.dp))
                                                .background(bCol.copy(alpha = 0.18f))
                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.height(5.dp))
                            Text(s.title, fontSize = 13.sp, color = TextWhite)
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (s.url != null) {
                                    Button(
                                        onClick = {
                                            val title = if (activeEpLabel.isNotBlank()) "${meta.name} $activeEpLabel" else meta.name
                                            onPlay(PlaySession(s, title, meta, activeVid, activeEpLabel, playable, meta.videos))
                                        },
                                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                                    ) { Text("▶ Play") }
                                    OutlinedButton(onClick = {
                                        val i = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(s.url), "video/*")
                                        runCatching { ctx.startActivity(i) }
                                    }) { Text("External", color = TextWhite) }
                                    OutlinedButton(onClick = {
                                        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        cm.setPrimaryClip(ClipData.newPlainText("url", s.url))
                                        Toast.makeText(ctx, "Link copied", Toast.LENGTH_SHORT).show()
                                    }) { Text("Copy", color = TextWhite) }
                                } else if (s.externalUrl != null) {
                                    OutlinedButton(onClick = {
                                        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(s.externalUrl))) }
                                    }) { Text("Open Link", color = TextWhite) }
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
                    items(seasons) { sn ->
                        FilterChip(
                            selected = selectedSeason == sn,
                            onClick = { selectedSeason = sn },
                            label = { Text(if (sn == 0) "Specials" else "Season $sn") }
                        )
                    }
                }
            }
            val eps = meta.videos.filter { it.season == selectedSeason }
            items(eps, key = { it.id }) { ep ->
                val done = store.isDone(ep.id)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 5.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(CardDark)
                        .clickable { findStreams(ep.id, "S${ep.season}E${ep.episode}") }
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.width(104.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)).background(BgDark), contentAlignment = Alignment.Center) {
                        Text("E${ep.episode}", fontSize = 12.sp, color = TextMut)
                        AsyncImage(model = ep.thumbnail, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (done) {
                                Icon(Icons.Rounded.Check, null, tint = Color(0xFF34D399), modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                            }
                            Text("${ep.episode}. ${ep.title}", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = TextWhite, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        ep.overview?.let {
                            Text(it, fontSize = 12.sp, color = TextMut, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AddonsScreen(
    addons: List<Addon>,
    onAdd: suspend (String) -> String?,
    onRemove: (Int) -> Unit,
    onMove: (Int, Int) -> Unit,
    onRefresh: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 14.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Addons & Extensions", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = TextWhite)
                IconButton(onClick = onRefresh) { Icon(Icons.Rounded.Refresh, "Refresh", tint = TextWhite) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = { Text("Paste Stremio manifest.json link") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = {
                    val v = input.trim()
                    if (v.isNotEmpty()) {
                        status = "Adding…"
                        scope.launch {
                            val err = runCatching { onAdd(v) }.getOrElse { it.message ?: "Failed" }
                            if (err == null) { input = ""; status = "Added!" } else status = err
                        }
                    }
                }) { Icon(Icons.Rounded.Add, null); Text("Add") }
            }
            if (status.isNotBlank()) Text(status, fontSize = 13.sp, color = AccentPink, modifier = Modifier.padding(top = 6.dp))
            Spacer(Modifier.height(10.dp))
        }
        items(addons.size) { i ->
            val a = addons[i]
            Card(colors = CardDefaults.cardColors(containerColor = CardDark), modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(a.name, fontWeight = FontWeight.Bold, color = TextWhite, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(if (a.loaded) "${a.catalogs.size} catalogs" else a.base, fontSize = 12.sp, color = TextMut, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (i > 0) IconButton(onClick = { onMove(i, i - 1) }) { Text("↑", color = TextWhite) }
                    if (i < addons.lastIndex) IconButton(onClick = { onMove(i, i + 1) }) { Text("↓", color = TextWhite) }
                    IconButton(onClick = { onRemove(i) }) { Icon(Icons.Rounded.Delete, "Remove", tint = TextWhite) }
                }
            }
        }
    }
}

@Composable
fun PlayerScreen(
    session: PlaySession,
    addons: List<Addon>,
    store: Store,
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

    var extSubs by remember { mutableStateOf<List<SubItem>>(emptyList()) }
    var activeCues by remember { mutableStateOf<List<SubCue>>(emptyList()) }
    var activeExtLabel by remember { mutableStateOf("None") }
    var subOffsetMs by remember { mutableLongStateOf(0L) }
    var subSizeSp by remember { mutableIntStateOf(18) }
    var playerSize by remember { mutableStateOf(IntSize(1080, 720)) }

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
                if (saved > 15_000L) seekTo(saved)
                playWhenReady = true
            }
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
            activity?.window?.attributes = activity?.window?.attributes?.apply {
                screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            }
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
            delay(900L)
            hudText = null
        }
    }

    BackHandler {
        if (sheetMode != null) sheetMode = null else onClose()
    }

    val loadEpisode: (VideoEp) -> Unit = { ep ->
        scope.launch {
            hudText = "Loading S${ep.season}E${ep.episode}…"
            val ss = Engine.fetchStreams(addons, session.meta.type, ep.id).filter { it.url != null }
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
                factory = { c ->
                    PlayerView(c).apply {
                        useController = false
                        keepScreenOn = true
                        player = exoPlayer
                        this.resizeMode = resizeMode
                    }
                },
                update = { v ->
                    v.player = exoPlayer
                    v.resizeMode = resizeMode
                },
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
                Modifier
                    .fillMaxSize()
                    .pointerInput(locked) {
                        detectTapGestures(
                            onTap = {
                                if (sheetMode != null) sheetMode = null else showControls = !showControls
                            },
                            onDoubleTap = { offset ->
                                if (!locked) {
                                    val delta = if (offset.x < playerSize.width / 2f) -10_000L else 10_000L
                                    val target = (exoPlayer.currentPosition + delta).coerceIn(0L, durMs.coerceAtLeast(1L))
                                    exoPlayer.seekTo(target)
                                    hudText = if (delta < 0) "⏪ -10s" else "+10s ⏩"
                                }
                            }
                        )
                    }
                    .pointerInput(locked) {
                        if (locked) return@pointerInput
                        detectDragGestures(
                            onDragStart = { start ->
                                dragMode = null
                                dragStartX = start.x
                                dragAccumX = 0f
                                dragAccumY = 0f
                                startPos = exoPlayer.currentPosition
                                val curB = activity?.window?.attributes?.screenBrightness ?: -1f
                                startBright = if (curB < 0f) 0.5f else curB
                                startVol = audioMgr.getStreamVolume(AudioManager.STREAM_MUSIC)
                            },
                            onDrag = { change, amount ->
                                change.consume()
                                dragAccumX += amount.x
                                dragAccumY += amount.y
                                if (dragMode == null && (abs(dragAccumX) > 20f || abs(dragAccumY) > 20f)) {
                                    dragMode = if (abs(dragAccumX) > abs(dragAccumY)) "seek"
                                    else if (dragStartX < playerSize.width / 2f) "bright" else "vol"
                                }
                                when (dragMode) {
                                    "seek" -> {
                                        val deltaMs = (dragAccumX * 120f).toLong()
                                        val target = (startPos + deltaMs).coerceIn(0L, durMs.coerceAtLeast(1L))
                                        posMs = target
                                        isSeeking = true
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
                            onDragEnd = {
                                if (dragMode == "seek") {
                                    exoPlayer.seekTo(posMs)
                                    isSeeking = false
                                }
                                dragMode = null
                            },
                            onDragCancel = { isSeeking = false; dragMode = null }
                        )
                    }
            )

            if (activeCues.isNotEmpty()) {
                val t = posMs - subOffsetMs
                val text = activeCues.filter { t in it.startMs..it.endMs }.joinToString("\n") { it.text }
                if (text.isNotBlank()) {
                    Text(
                        text,
                        color = Color.White,
                        fontSize = subSizeSp.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(horizontal = 24.dp, vertical = if (showControls) 98.dp else 24.dp)
                            .background(Color.Black.copy(alpha = 0.74f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                    )
                }
            }

            hudText?.let {
                Surface(
                    color = Color.Black.copy(alpha = 0.8f),
                    contentColor = Color.White,
                    shape = CircleShape,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 64.dp)
                ) {
                    Text(it, color = Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                }
            }

            if (showControls) {
                if (locked) {
                    IconButton(
                        onClick = { locked = false },
                        modifier = Modifier.align(Alignment.TopStart).padding(16.dp).background(Color.Black.copy(0.65f), CircleShape)
                    ) { Icon(Icons.Rounded.LockOpen, "Unlock", tint = Color.White) }
                } else {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .background(Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent, Color(0xD8000000))))
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Close", tint = Color.White) }
                            Text(session.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                IconButton(onClick = {
                                    activity?.enterPictureInPictureMode(
                                        PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()
                                    )
                                }) { Icon(Icons.Rounded.PictureInPictureAlt, "PiP", tint = Color.White) }
                            }
                            IconButton(onClick = { locked = true }) { Icon(Icons.Rounded.Lock, "Lock", tint = Color.White) }
                        }

                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = { exoPlayer.seekTo((exoPlayer.currentPosition - 10_000L).coerceAtLeast(0L)) },
                                modifier = Modifier.size(52.dp).background(Color.Black.copy(0.55f), CircleShape)
                            ) { Icon(Icons.Rounded.Replay10, "-10s", tint = Color.White, modifier = Modifier.size(28.dp)) }

                            Spacer(Modifier.width(28.dp))

                            IconButton(
                                onClick = { if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play() },
                                modifier = Modifier.size(66.dp).background(AccentPink, CircleShape)
                            ) {
                                Icon(
                                    if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                    "Play/Pause",
                                    tint = Color(0xFF19040C),
                                    modifier = Modifier.size(36.dp)
                                )
                            }

                            Spacer(Modifier.width(28.dp))

                            IconButton(
                                onClick = { exoPlayer.seekTo(exoPlayer.currentPosition + 10_000L) },
                                modifier = Modifier.size(52.dp).background(Color.Black.copy(0.55f), CircleShape)
                            ) { Icon(Icons.Rounded.Forward10, "+10s", tint = Color.White, modifier = Modifier.size(28.dp)) }
                        }

                        Column {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(fmtMs(posMs), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                PlayerSeekBar(
                                    posMs = posMs,
                                    durMs = durMs,
                                    onSeekPreview = { isSeeking = true; posMs = it },
                                    onSeekCommit = { exoPlayer.seekTo(it); posMs = it; isSeeking = false },
                                    modifier = Modifier.weight(1f).padding(horizontal = 12.dp)
                                )
                                Text(fmtMs(durMs), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                            }
                            Spacer(Modifier.height(4.dp))
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                item { PlayerPill("+85s", null) { exoPlayer.seekTo(exoPlayer.currentPosition + 85_000L); hudText = "+85s" } }
                                item { PlayerPill("Audio", Icons.Rounded.Audiotrack) { sheetMode = "audio" } }
                                item { PlayerPill("Quality", Icons.Rounded.HighQuality) { sheetMode = "quality" } }
                                item { PlayerPill("Subs", Icons.Rounded.Subtitles) { sheetMode = "subs" } }
                                if (session.allStreams.size > 1) {
                                    item { PlayerPill("Sources (${session.allStreams.size})", null) { sheetMode = "sources" } }
                                }
                                if (session.episodes.isNotEmpty()) {
                                    val idx = session.episodes.indexOfFirst { it.id == session.vid }
                                    val nx = if (idx in 0 until session.episodes.lastIndex) session.episodes[idx + 1] else null
                                    if (nx != null) {
                                        item { PlayerPill("Next", Icons.Rounded.SkipNext) { loadEpisode(nx) } }
                                    }
                                    item { PlayerPill("Episodes", Icons.AutoMirrored.Rounded.List) { sheetMode = "episodes" } }
                                }
                                item {
                                    PlayerPill("${speed}x", Icons.Rounded.Speed) {
                                        val sp = listOf(1f, 1.25f, 1.5f, 2f, 0.75f)
                                        speed = sp[(sp.indexOf(speed) + 1) % sp.size]
                                        exoPlayer.setPlaybackSpeed(speed)
                                        hudText = "${speed}x"
                                    }
                                }
                                item {
                                    PlayerPill("Fit", Icons.Rounded.AspectRatio) {
                                        val modes = listOf(
                                            AspectRatioFrameLayout.RESIZE_MODE_FIT to "Fit",
                                            AspectRatioFrameLayout.RESIZE_MODE_ZOOM to "Zoom",
                                            AspectRatioFrameLayout.RESIZE_MODE_FILL to "Stretch"
                                        )
                                        val nxt = modes[(modes.indexOfFirst { it.first == resizeMode } + 1) % modes.size]
                                        resizeMode = nxt.first
                                        hudText = nxt.second
                                    }
                                }
                            }
                        }
                    }
                }
            }

            errMsg?.let { em ->
                Column(
                    Modifier.fillMaxSize().background(Color.Black.copy(0.88f)).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(Icons.Rounded.Warning, null, tint = AccentPink, modifier = Modifier.size(40.dp))
                    Spacer(Modifier.height(10.dp))
                    Text("Stream Failed: $em", color = Color.White, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (session.allStreams.size > 1) Button(onClick = { errMsg = null; sheetMode = "sources" }) { Text("Switch Source") }
                        OutlinedButton(onClick = {
                            val i = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(session.stream.url), "video/*")
                            runCatching { ctx.startActivity(i) }
                        }) { Text("Open in VLC", color = Color.White) }
                        OutlinedButton(onClick = onClose) { Text("Back", color = Color.White) }
                    }
                }
            }

            sheetMode?.let { mode ->
                Surface(
                    color = Color(0xF5141420),
                    contentColor = Color.White,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(14.dp)
                        .widthIn(max = 380.dp)
                        .fillMaxHeight(0.88f)
                ) {
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
                                    if (audioGroups.isEmpty()) {
                                        item { Text("No switchable audio tracks found.", color = TextMut) }
                                    } else {
                                        audioGroups.forEach { g ->
                                            items(g.length) { i ->
                                                val f = g.getTrackFormat(i)
                                                val lbl = listOfNotNull(f.label, f.language?.uppercase(), f.codecs, "${f.channelCount}ch").joinToString(" · ")
                                                OptionRow(lbl.ifBlank { "Audio Track ${i + 1}" }, g.isTrackSelected(i)) {
                                                    exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon()
                                                        .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                                                        .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i))
                                                        .build()
                                                    sheetMode = null
                                                }
                                            }
                                        }
                                    }
                                }
                                "quality" -> {
                                    item {
                                        OptionRow("Auto (Best Available)", exoPlayer.trackSelectionParameters.overrides.isEmpty()) {
                                            exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon()
                                                .clearOverridesOfType(C.TRACK_TYPE_VIDEO).build()
                                            sheetMode = null
                                        }
                                    }
                                    tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }.forEach { g ->
                                        items(g.length) { i ->
                                            val f = g.getTrackFormat(i)
                                            val lbl = "${if (f.height > 0) "${f.height}p" else "Video $i"} ${if (f.bitrate > 0) "· ${f.bitrate / 1000} kbps" else ""}"
                                            OptionRow(lbl, g.isTrackSelected(i)) {
                                                exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon()
                                                    .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i)).build()
                                                sheetMode = null
                                            }
                                        }
                                    }
                                }
                                "subs" -> {
                                    item {
                                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                            OutlinedButton(onClick = { subOffsetMs -= 500L }) { Text("-0.5s", color = Color.White) }
                                            OutlinedButton(onClick = { subOffsetMs += 500L }) { Text("+0.5s", color = Color.White) }
                                            Text("${subOffsetMs / 1000f}s", color = Color.White, fontSize = 12.sp)
                                            OutlinedButton(onClick = { subSizeSp = if (subSizeSp == 18) 23 else if (subSizeSp == 23) 14 else 18 }) {
                                                Text("${subSizeSp}sp", color = Color.White)
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
                                    item {
                                        OptionRow("Load local .srt / .vtt from phone", activeExtLabel == "Local File") {
                                            localSubPicker.launch("*/*")
                                        }
                                    }
                                    tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }.forEach { g ->
                                        items(g.length) { i ->
                                            val f = g.getTrackFormat(i)
                                            val lbl = "Embedded: ${listOfNotNull(f.label, f.language?.uppercase()).joinToString(" ")}"
                                            OptionRow(lbl, g.isTrackSelected(i) && activeExtLabel == "None") {
                                                activeCues = emptyList(); activeExtLabel = "None"
                                                exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon()
                                                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                                                    .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i)).build()
                                                sheetMode = null
                                            }
                                        }
                                    }
                                    items(extSubs) { sub ->
                                        val lbl = "${sub.lang.uppercase()} · ${sub.id.take(18)}"
                                        OptionRow(lbl, activeExtLabel == lbl) {
                                            scope.launch {
                                                runCatching { Engine.fetchText(sub.url) }.onSuccess {
                                                    activeCues = Engine.parseSubtitles(it)
                                                    activeExtLabel = lbl
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
                                            sheetMode = null
                                            if (!cur) onSwitchSession(session.copy(stream = s))
                                        }
                                    }
                                }
                                "episodes" -> {
                                    items(session.episodes) { ep ->
                                        val lb = "S${ep.season}E${ep.episode}"
                                        OptionRow("$lb · ${ep.title}", ep.id == session.vid) {
                                            sheetMode = null
                                            if (ep.id != session.vid) loadEpisode(ep)
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
fun PlayerSeekBar(
    posMs: Long,
    durMs: Long,
    onSeekPreview: (Long) -> Unit,
    onSeekCommit: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    var widthPx by remember { mutableIntStateOf(1) }
    val frac = if (durMs > 0L) (posMs.toFloat() / durMs).coerceIn(0f, 1f) else 0f

    BoxWithConstraints(
        modifier
            .height(28.dp)
            .onSizeChanged { widthPx = it.width.coerceAtLeast(1) }
            .pointerInput(durMs) {
                detectTapGestures { offset ->
                    if (durMs > 0L) onSeekCommit(((offset.x / widthPx).coerceIn(0f, 1f) * durMs).toLong())
                }
            }
            .pointerInput(durMs) {
                var dragX = 0f
                detectHorizontalDragGestures(
                    onDragStart = { start ->
                        dragX = start.x
                        if (durMs > 0L) onSeekPreview(((dragX / widthPx).coerceIn(0f, 1f) * durMs).toLong())
                    },
                    onHorizontalDrag = { _, delta ->
                        dragX = (dragX + delta).coerceIn(0f, widthPx.toFloat())
                        if (durMs > 0L) onSeekPreview(((dragX / widthPx).coerceIn(0f, 1f) * durMs).toLong())
                    },
                    onDragEnd = {
                        if (durMs > 0L) onSeekCommit(((dragX / widthPx).coerceIn(0f, 1f) * durMs).toLong())
                    }
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
    Row(
        Modifier
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.14f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(icon, null, tint = Color.White, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(5.dp))
        }
        Text(text, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun OptionRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) AccentPink.copy(alpha = 0.24f) else Color.White.copy(alpha = 0.06f))
            .clickable(onClick = onClick)
            .padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 13.sp, color = Color.White, modifier = Modifier.weight(1f))
        if (selected) Icon(Icons.Rounded.Check, null, tint = AccentPink, modifier = Modifier.size(18.dp))
    }
}
