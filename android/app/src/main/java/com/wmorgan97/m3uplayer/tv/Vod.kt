package com.wmorgan97.m3uplayer.tv

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.layout.layout
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import org.json.JSONObject

// Movies and Series: the banner, the rows, a genre's full grid; and resuming from watch history.

fun progressTitle(it: JSONObject): String {
    val name = it.optString("name"); val m = vodMeta(name)
    return if (it.optString("kind") == "episode" && m.episode != null) "${displayTitle(name)} · S${m.season ?: 1} E${m.episode}" else displayTitle(name)
}

fun resume(act: TvActivity, p: Progress, fromStart: Boolean = false) {
    val it = p.item
    val title = progressTitle(it)
    val item = JSONObject().put("url", p.url).put("title", title).put("subtitle", if (it.optString("kind") == "episode") epTitle(it.optString("name")) else "")
        .put("live", false).put("start", if (fromStart) 0.0 else p.t).put("art", it.optString("logo")).put("listTitle", title).put("sync", it)
    act.play(listOf(item), 0)
}

/** The featured banner: TMDB's trending titles with their backdrops. Picked: a white frame; OK opens it. */
@Composable
fun Hero(act: TvActivity, items: List<Vod>, loading: Boolean = false) {
    val shape = Tv.shapeL
    val mod = Modifier.fillMaxWidth().height(340.dp).padding(start = Tv.margin, end = Tv.margin, top = Tv.topMargin)
    if (items.isEmpty()) { Box(mod.clip(shape).let { if (loading) it.shimmer() else it.background(Tv.surface) }); return }
    var focused by remember { mutableStateOf(false) }
    // Own slide timer: the TV carousel stops auto-sliding while focused when an accessibility service is on (the Shield
    // runs one), so slides advance here every 10 s, cross-fading over 0.8 s, and wait longer after you've moved by hand.
    var index by remember(items) { mutableIntStateOf(0) }
    var touchedAt by remember { mutableLongStateOf(0L) }
    LaunchedEffect(items) {
        while (true) {
            delay(10_000)
            if (System.currentTimeMillis() - touchedAt > 12_000) index = (index + 1) % items.size
        }
    }
    // Every slide's backdrop is fetched up front, so a slide never appears blank and then pops in.
    val ctx = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(items) { val l = coil.Coil.imageLoader(ctx); items.forEach { l.enqueue(coil.request.ImageRequest.Builder(ctx).data(it.backdrop).build()) } }
    // Rank within its own kind ("#3 trending series"), not its place in the mixed films + series banner.
    val ranks = remember(items) { val n = HashMap<String, Int>(); items.map { v -> (n.merge(v.kind, 1, Int::plus) ?: 1) } }
    Box(mod.onFocusChanged { focused = it.hasFocus }
            .border(if (focused) 2.dp else 0.dp, if (focused) Tv.focusRing else Color.Transparent, shape).clip(shape)   // the banner is too big to grow: a clean white line
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) {
                    if (e.type == KeyEventType.KeyUp && (e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter)) { items.getOrNull(index)?.let { act.go(Screen.Details(it)) }; return@onKeyEvent true }
                    return@onKeyEvent false
                }
                when (e.key) {
                    Key.DirectionRight -> { index = (index + 1) % items.size; touchedAt = System.currentTimeMillis(); true }
                    Key.DirectionLeft -> if (index > 0) { index--; touchedAt = System.currentTimeMillis(); true } else false   // left on the first slide: the menu
                    else -> false
                }
            }.focusable()) {
    androidx.compose.animation.Crossfade(index.coerceIn(0, items.size - 1), animationSpec = androidx.compose.animation.core.tween(Tv.durCrossfade), label = "hero") { i ->
        val v = items[i]
        Box(Modifier.fillMaxSize()) {
            Picture(v.backdrop, Modifier.fillMaxSize(), fade = false)   // (the slide change already cross-fades)
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color(0xF20E1014), Color(0xB30E1014), Color.Transparent), endX = 1400f)))
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0x990E1014)), startY = 400f)))
            Column(Modifier.align(Alignment.CenterStart).padding(start = 40.dp).fillMaxWidth(.56f)) {
                Text("#${ranks.getOrElse(i) { i + 1 }} TRENDING ${if (v.kind == "series") "SERIES" else "MOVIE"}", fontSize = Tv.label, color = Tv.accent, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                Spacer(Modifier.height(8.dp))
                Text(v.title, fontSize = Tv.display, fontWeight = Tv.wDisplay, color = Tv.text, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = Tv.displayLine)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    v.year?.let { Tag("$it") }
                    v.rating?.takeIf { it > 0 }?.let { Tag("★ %.1f".format(it), color = Tv.star) }
                    quality(v.name, v.group)?.let { Tag(it) }
                }
                Spacer(Modifier.height(12.dp))
                Text(v.overview, fontSize = Tv.bodySmall, color = Tv.text2, maxLines = 3, overflow = TextOverflow.Ellipsis, lineHeight = Tv.bodySmallLine)
                Spacer(Modifier.height(18.dp))
                // One clear action, lit when the banner has focus (OK opens it).
                // (Drawn like a TvButton: the banner itself is the focus target.)
                Row(Modifier.height(Tv.buttonHeight).background(if (focused) Tv.focusSurface else Tv.accent, Tv.pill).padding(start = 16.dp, end = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (v.kind == "series") Icons.Default.VideoLibrary else Icons.Default.PlayArrow, null, Modifier.size(Tv.icon), tint = if (focused) Tv.onFocus else Tv.onAccent)
                    Spacer(Modifier.width(8.dp))
                    Text(if (v.kind == "series") "Episodes" else "Watch", style = TvType.button, color = if (focused) Tv.onFocus else Tv.onAccent)
                }
            }
        }
    }
        CarouselDefaults.IndicatorRow(itemCount = items.size, activeItemIndex = index, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp))
    }
}

// ---------- Movies / Series ----------

@Composable
fun VodScreen(act: TvActivity, kind: String) {
    val ov by produceState(Repo.cachedOverview(kind), Repo.version) { if (value == null) value = runCatching { Repo.overview(kind) }.getOrNull() }
    val what = if (kind == "series") "series" else "movies"
    val o = ov
    HeldPace()
    WithScroll(rememberRowAnchor(PAGE_ROW_TOP)) {
    LazyColumn(Modifier.fillMaxSize(), state = rememberPageState(), contentPadding = PaddingValues(bottom = 56.dp)) {
        item { Hero(act, o?.trending?.filter { it.backdrop.isNotEmpty() }?.take(6) ?: emptyList(), o == null) }
        if (o == null) { repeat(3) { item { SkeletonTitle() }; item { SkeletonRow() } }; return@LazyColumn }
        if (o.trending.isNotEmpty()) { item { RowTitle("Top 10 $what this week", "from TMDB") }; item { CardRow(o.trending.take(10), { it.id }) { v, i -> PosterCard(v, rank = i + 1) { act.go(Screen.Details(v)) } } } }
        if (o.top.isNotEmpty()) { item { RowTitle("Popular now") }; item { CardRow(o.top.take(40), { it.id }) { v, _ -> PosterCard(v) { act.go(Screen.Details(v)) } } } }
        if (o.fresh.isNotEmpty()) { item { RowTitle("New releases") }; item { CardRow(o.fresh.take(40), { it.id }) { v, _ -> PosterCard(v) { act.go(Screen.Details(v)) } } } }
        for ((id, name) in o.groups) item(key = id) { GroupRow(act, kind, id, name) }
    }
    }
}

/** Pages of rows (Movies, Series): where the focused row's cards settle, below its title (with the page's top margin above). */
val PAGE_ROW_TOP = Tv.topMargin + 62.dp

/** A TMDB list / genre: its first titles, then a "See all" tile into the full grid. */
@Composable
fun GroupRow(act: TvActivity, kind: String, id: String, name: String) = traced("GroupRow") {
    // Built once, with its titles, when they're already fetched (the usual case: every group is fetched at start).
    val items by produceState(Repo.cachedGroup(kind, id), id, Repo.version) { if (value == null) value = runCatching { Repo.group(kind, id) }.getOrElse { emptyList() } }
    val l = items
    Column {
        RowTitle(name, count = l?.size)
        if (l == null) SkeletonRow()
        else if (l.isNotEmpty()) WithScroll(rememberRowKeyline()) { LazyRow(contentPadding = PaddingValues(horizontal = Tv.margin), horizontalArrangement = Arrangement.spacedBy(Tv.gap)) {
            items(l.take(20), key = { it.id }) { v -> PosterCard(v) { act.go(Screen.Details(v)) } }
            if (l.size > 20) item {
                Column(Modifier.width(Tv.poster)) {
                    FocusCard(Modifier.width(Tv.poster).aspectRatio(2f / 3f), onClick = { act.go(Screen.Grid(kind, id, name)) }) {
                        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Tv.surface3, Tv.surface))), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Default.ArrowForward, null, tint = Tv.text, modifier = Modifier.size(28.dp))
                                Spacer(Modifier.height(8.dp))
                                Text("See all", fontSize = Tv.bodySmall, fontWeight = FontWeight.SemiBold, color = Tv.text)
                                Text("${l.size} titles", color = Tv.muted, fontSize = Tv.label)
                            }
                        }
                    }
                    Caption(" ", null, Tv.poster)
                }
            }
        } }
    }
}

@Composable
fun GridScreen(act: TvActivity, kind: String, id: String, title: String) {
    val items by produceState<List<Vod>?>(null) { value = runCatching { Repo.group(kind, id) }.getOrElse { emptyList() } }
    Column(Modifier.fillMaxSize()) {
        PageTitle(title, items?.let { "${it.size} ${if (kind == "series") "series" else "movies"}" } ?: "Loading…")
        LazyVerticalGrid(GridCells.Adaptive(Tv.posterWide), contentPadding = PaddingValues(start = Tv.margin, end = Tv.margin, bottom = 56.dp),
            horizontalArrangement = Arrangement.spacedBy(Tv.gap), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            items(items ?: emptyList(), key = { it.id }) { v -> PosterCard(v, width = Tv.posterWide) { act.go(Screen.Details(v)) } }
        }
    }
}
