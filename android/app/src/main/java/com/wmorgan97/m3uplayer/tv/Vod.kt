package com.wmorgan97.m3uplayer.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

// Movies and Series: the spotlight (Spotlight.kt, shared with Home), Continue watching, the Top 10 and the genre
// shelves; a shelf's full grid; and resuming from watch history.

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

// ---------- Movies / Series ----------

/** A Movies / Series history entry belongs on that page: films on Movies, episodes (and series) on Series. */
private fun belongsTo(p: Progress, kind: String): Boolean {
    val k = p.item.optString("kind")
    return if (kind == "series") k == "episode" || k == "series" || (k.isEmpty() && p.url.contains("/series/"))
    else k == "movie" || (k.isEmpty() && p.url.contains("/movie/"))
}

@Composable
fun VodScreen(act: TvActivity, kind: String) {
    var loaded by remember { mutableStateOf(Repo.cachedOverview(kind) != null) }
    val ov by produceState(Repo.cachedOverview(kind), Repo.version) { if (value == null) value = runCatching { Repo.overview(kind) }.getOrNull(); loaded = true }
    val what = if (kind == "series") "series" else "movies"
    val o = ov
    val refresh = act.refresh + Repo.version
    val cw = remember(refresh, kind) { Repo.continueWatching().filter { isVodProgress(it) && belongsTo(it, kind) } }
    // The slides: this week's six most-watched with a backdrop; each ranked within the trending list.
    val slides = remember(o) { o?.trending.orEmpty().filter { it.backdrop.isNotEmpty() }.take(6) }
    val ranks = remember(o) { o?.trending.orEmpty().withIndex().associate { (i, v) -> "${v.kind}:${v.id}" to (i + 1) } }
    val spot = rememberSpotlight(slides, ranks)
    val playFocus = remember { FocusRequester() }
    val listState = rememberPageState()
    val scope = rememberCoroutineScope()
    var rowsFocused by remember { mutableStateOf(false) }
    HeldPace()
    // The page opens on Play (as Home): first visit only, only if the remote hasn't moved, never when coming back.
    var autoFocusDone by rememberSaveable { mutableStateOf(false) }
    var pressed by remember { mutableStateOf(false) }
    LaunchedEffect(slides.isNotEmpty()) {
        if (slides.isEmpty() || autoFocusDone) return@LaunchedEffect
        autoFocusDone = true
        if (Nav.restoreKey != null) return@LaunchedEffect
        repeat(12) { kotlinx.coroutines.delay(50); if (pressed) return@LaunchedEffect; runCatching { playFocus.requestFocus() } }
    }
    // Back from deep in the rows: to the top first (Back again opens the menu), as on Home.
    BackHandler(enabled = rowsFocused && slides.isNotEmpty()) { scope.launch { listState.animateScrollToItem(0) }; runCatching { playFocus.requestFocus() } }

    if (loaded && o == null && cw.isEmpty()) {
        EmptyState(if (kind == "series") Icons.Default.VideoLibrary else Icons.Default.Movie, "No $what yet", "Add a playlist with $what in Settings")
        return
    }
    Box(Modifier.fillMaxSize().onPreviewKeyEvent { if (it.type == KeyEventType.KeyDown) pressed = true; false }) {
        SpotlightBackdrop(spot, Modifier.align(Alignment.TopEnd).fillMaxWidth(.74f).aspectRatio(16f / 9f))
        Column(Modifier.fillMaxSize()) {
            SpotlightHeader(act, spot, slides, refresh, playFocus, loading = o == null)
            WithScroll(rememberRowAnchor(SPOT_ROW_TOP + SPOT_ROW_HEAD)) {
                LazyColumn(Modifier.fillMaxWidth().weight(1f).onFocusChanged { rowsFocused = it.hasFocus }, state = listState,
                    contentPadding = PaddingValues(top = SPOT_ROW_TOP, bottom = 240.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    var first = slides.isNotEmpty()   // the first row's Up goes to Play
                    fun up(): Modifier { val f = first; first = false; return Modifier.upToPlay(f, playFocus) }
                    if (cw.isNotEmpty()) { val m = up(); item(key = "cw") { ContinueRow(act, cw, spot, m) } }
                    if (o == null) { item(key = "skel") { Column { SkeletonTitle(); SkeletonRow(width = Tv.poster) } }; return@LazyColumn }
                    if (o.trending.isNotEmpty()) { val m = up(); item(key = "top10") { PosterShelf(act, "top10", "Top 10 $what this week", o.trending.take(10), spot, m, extra = "from TMDB", ranked = true) } }
                    if (o.top.isNotEmpty()) { val m = up(); item(key = "popular") { PosterShelf(act, "popular", "Popular now", o.top.take(40), spot, m) } }
                    if (o.fresh.isNotEmpty()) { val m = up(); item(key = "fresh") { PosterShelf(act, "fresh", "New releases", o.fresh.take(40), spot, m) } }
                    for ((id, name) in o.groups) { val m = up(); item(key = "g:$id") { GroupRow(act, kind, id, name, spot, m) } }
                }
            }
        }
        // The rows dissolve into the page at the bottom edge instead of being cut off.
        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(28.dp).background(Brush.verticalGradient(listOf(Color.Transparent, Tv.bg))))
    }
}

/** Pages of rows: where the focused row's cards settle (kept for callers outside the spotlight pages). */
val PAGE_ROW_TOP = Tv.topMargin + 62.dp

/** A shelf of posters under the spotlight; ranked shelves carry the Top 10 numerals. */
@Composable
private fun PosterShelf(act: TvActivity, id: String, title: String, items: List<Vod>, spot: SpotlightState, modifier: Modifier, extra: String? = null, ranked: Boolean = false) {
    SpotRow(title, modifier, extra = extra) {
        CardRow(items, key = { "${it.kind}:${it.id}" }) { v, i ->
            PosterCard(v, Tv.poster, rank = if (ranked) i + 1 else null, restoreKey = "$id|${v.kind}:${v.id}", onFocus = { spot.preview(v) }) { act.go(Screen.Details(v)) }
        }
    }
}

private const val SHELF_MAX = 20

/** A TMDB list / genre: its first titles, then a "See all" tile into the full grid. */
@Composable
fun GroupRow(act: TvActivity, kind: String, id: String, name: String, spot: SpotlightState? = null, modifier: Modifier = Modifier) = traced("GroupRow") {
    // Built once, with its titles, when they're already fetched (the usual case: every group is fetched at start).
    val items by produceState(Repo.cachedGroup(kind, id), id, Repo.version) { if (value == null) value = runCatching { Repo.group(kind, id) }.getOrElse { emptyList() } }
    val l = items
    if (l != null && l.isEmpty()) return@traced   // an empty shelf is left out, not shown as a bare title
    SpotRow(name, modifier, count = l?.size) {
        if (l == null) SkeletonRow()
        else WithScroll(rememberRowKeyline()) { LazyRow(state = rememberRowState(), contentPadding = PaddingValues(horizontal = Tv.margin), horizontalArrangement = Arrangement.spacedBy(Tv.gap)) {
            val shown = l.take(SHELF_MAX)
            itemsIndexed(shown, key = { _, v -> v.id }) { i, v -> RowEnd(i == shown.lastIndex && l.size <= SHELF_MAX) { PosterCard(v, Tv.poster, restoreKey = "g$id|${v.kind}:${v.id}", onFocus = spot?.let { s -> { s.preview(v) } }) { act.go(Screen.Details(v)) } } }
            if (l.size > SHELF_MAX) item(key = "all") { RowEnd(true) { SeeAllTile(l.size, kind) { act.go(Screen.Grid(kind, id, name)) } } }
        } }
    }
}

/** The end of a shelf: a poster-sized tile into the shelf's full grid. */
@Composable
private fun SeeAllTile(count: Int, kind: String, onClick: () -> Unit) {
    Column(Modifier.width(Tv.poster)) {
        FocusCard(Modifier.width(Tv.poster).aspectRatio(2f / 3f), label = "See all $count", onClick = onClick) {
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Tv.surface3, Tv.surface))), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(44.dp).clip(RoundedCornerShape(50)).background(Tv.surface3), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.ArrowForward, null, tint = Tv.text, modifier = Modifier.size(Tv.iconM))
                    }
                    Spacer(Modifier.height(Tv.s3))
                    Text("See all", style = TvType.button, color = Tv.text)
                    Text("$count ${if (kind == "series") "series" else "films"}", fontSize = Tv.label, lineHeight = Tv.labelLine, color = Tv.muted)
                }
            }
        }
        Caption(" ", null, Tv.poster)
    }
}

// ---------- A shelf's full grid ----------

private enum class GridSort(val label: String) { Featured("Featured"), Rating("Top rated"), Newest("Newest"), Title("A–Z") }

private fun sorted(l: List<Vod>, s: GridSort): List<Vod> = when (s) {
    GridSort.Featured -> l
    // Ratings with only a handful of votes sort below trusted ones (votes is 0 when TMDB didn't say: kept as is).
    GridSort.Rating -> l.sortedWith(compareByDescending<Vod> { (it.rating ?: 0.0) * (if (it.votes in 1..49) .8 else 1.0) }.thenBy { it.title.lowercase() })
    GridSort.Newest -> l.sortedWith(compareByDescending<Vod> { it.year ?: 0 }.thenBy { it.title.lowercase() })
    GridSort.Title -> l.sortedBy { it.title.lowercase().removePrefix("the ") }
}

@Composable
fun GridScreen(act: TvActivity, kind: String, id: String, title: String) {
    val items by produceState(Repo.cachedGroup(kind, id)) { if (value == null) value = runCatching { Repo.group(kind, id) }.getOrElse { emptyList() } }
    var sort by rememberSaveable { mutableStateOf(GridSort.Featured) }
    // Sorting a few thousand titles stays off the main thread.
    val shown by produceState<List<Vod>?>(null, items, sort) { val l = items ?: return@produceState; value = withContext(Dispatchers.Default) { sorted(l, sort) } }
    val what = if (kind == "series") "series" else "films"
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    HeldPace()
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(end = Tv.margin), verticalAlignment = Alignment.Bottom) {
            Box(Modifier.weight(1f)) { PageTitle(title, items?.let { "${it.size} $what" } ?: "Loading…") }
            // Sort: the shown order in accent; a new order starts from the top.
            Row(Modifier.padding(bottom = 18.dp), horizontalArrangement = Arrangement.spacedBy(Tv.s2)) {
                GridSort.entries.forEach { s -> TvChip(s.label, s == sort, { if (s != sort) { sort = s; scope.launch { gridState.scrollToItem(0) } } }) }
            }
        }
        val l = shown
        when {
            l == null -> GridSkeleton()
            l.isEmpty() -> EmptyState(if (kind == "series") Icons.Default.VideoLibrary else Icons.Default.Movie, "Nothing here yet", "This shelf has no $what in your playlist right now")
            // Fixed-size cells: every poster the same size in every grid, whatever the screen width.
            else -> LazyVerticalGrid(GridCells.FixedSize(Tv.posterWide), state = gridState, contentPadding = PaddingValues(start = Tv.margin, end = Tv.margin, top = Tv.s2, bottom = 56.dp),
                horizontalArrangement = Arrangement.spacedBy(Tv.gap), verticalArrangement = Arrangement.spacedBy(Tv.s5)) {
                items(l, key = { it.id }) { v -> PosterCard(v, width = Tv.posterWide, restoreKey = "grid|${v.kind}:${v.id}") { act.go(Screen.Details(v)) } }
            }
        }
    }
}

/** Three rows of grey posters while a grid loads. */
@Composable
private fun GridSkeleton() {
    val b = shimmerBrush()
    Column(Modifier.padding(start = Tv.margin, top = Tv.s2), verticalArrangement = Arrangement.spacedBy(Tv.s5)) {
        repeat(2) {
            Row(horizontalArrangement = Arrangement.spacedBy(Tv.gap)) {
                repeat(6) { Box(Modifier.width(Tv.posterWide).aspectRatio(2f / 3f).clip(Tv.shape).background(b)) }
            }
        }
    }
}
