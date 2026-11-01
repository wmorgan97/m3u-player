package com.wmorgan97.m3uplayer.tv

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.Icon
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

// ---------- Home ----------
//
// Films and series only: Home is for discovery, Live TV and the Guide are for live (and sport has its own page).
// The layout is the lean-back one people know from Netflix and Apple TV:
//  - the top of the screen is a spotlight: a big backdrop with the title, its year/rating/genres and a short plot.
//    On the featured slides it has [Play] [More info]; once the remote is in the rows it quietly shows the title the
//    highlight rests on (after a short pause, so moving along a row never flickers);
//  - the rows sit below it, and the row you're on always settles in the same place, just under the spotlight, so your
//    eye never has to hunt for the highlight.

@Composable
fun HomeScreen(act: TvActivity) {
    val refresh = act.refresh + Repo.version
    var loaded by remember { mutableIntStateOf(0) }
    val movies by produceState(Repo.cachedOverview("movies"), Repo.version) { if (value == null) value = runCatching { Repo.overview("movies") }.getOrNull(); loaded = loaded or 1 }
    val series by produceState(Repo.cachedOverview("series"), Repo.version) { if (value == null) value = runCatching { Repo.overview("series") }.getOrNull(); loaded = loaded or 2 }
    // Continue watching: films and episodes only.
    val cw = remember(refresh) { Repo.continueWatching().filter(::isVodProgress) }
    // Built off the main thread and cached (HomeFeed): the quick rows at once, the rest when their lists are in.
    val rows by produceState(HomeFeed.cached() ?: emptyList(), movies, series, refresh) {
        val m = movies; val s = series
        if (m == null && s == null) return@produceState
        if (value.isEmpty()) value = withContext(Dispatchers.Default) { HomeFeed.quick(m, s) }
        value = withContext(Dispatchers.Default) { HomeFeed.build(m, s) }
    }
    // The featured slides: this week's top three films and top three series, alternating.
    val slides = remember(movies, series) {
        val m = movies?.trending.orEmpty().filter { it.backdrop.isNotEmpty() }.take(3); val s = series?.trending.orEmpty().filter { it.backdrop.isNotEmpty() }.take(3)
        (0 until maxOf(m.size, s.size)).flatMap { k -> listOfNotNull(m.getOrNull(k), s.getOrNull(k)) }
    }
    val ranks = remember(movies, series) { (movies?.trending.orEmpty() + series?.trending.orEmpty()).let { all -> all.associate { v -> "${v.kind}:${v.id}" to (all.filter { it.kind == v.kind }.indexOf(v) + 1) } } }

    // The spotlight (Spotlight.kt): the slides, and the title the highlight rests on once the remote is in the rows.
    val spot = rememberSpotlight(slides, ranks)
    var rowsFocused by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val playFocus = remember { FocusRequester() }
    val listState = rememberPageState()
    HeldPace()
    // Home opens on Play: the slides usually arrive a moment after the first frame, by which time the page has put the
    // highlight on the first row. Once they're in, the highlight moves to Play, but only on the first visit, only if
    // the remote hasn't been touched, and never when coming back to Home (Back restores the card you left).
    var autoFocusDone by rememberSaveable { mutableStateOf(false) }
    var pressed by remember { mutableStateOf(false) }
    var heroFocused by remember { mutableStateOf(false) }
    LaunchedEffect(slides.isNotEmpty()) {
        if (slides.isEmpty() || autoFocusDone) return@LaunchedEffect
        autoFocusDone = true
        if (Nav.restoreKey != null) return@LaunchedEffect
        repeat(12) { delay(50); if (pressed || heroFocused) return@LaunchedEffect; runCatching { playFocus.requestFocus() } }
    }

    // Back from deep in the rows: to the top first (Back again opens the menu).
    BackHandler(enabled = rowsFocused && slides.isNotEmpty()) { scope.launch { listState.animateScrollToItem(0) }; runCatching { playFocus.requestFocus() } }

    val nothing = loaded == 3 && movies == null && series == null && cw.isEmpty()
    if (nothing) { EmptyState(Icons.Default.Movie, "No films or series yet", "Add a playlist with films and series in Settings"); return }
    Box(Modifier.fillMaxSize().onPreviewKeyEvent { if (it.type == KeyEventType.KeyDown) pressed = true; false }) {
        SpotlightBackdrop(spot, Modifier.align(Alignment.TopEnd).fillMaxWidth(.74f).aspectRatio(16f / 9f))
        Column(Modifier.fillMaxSize()) {
            SpotlightHeader(act, spot, slides, refresh, playFocus, loading = movies == null && series == null, onHeroFocus = { heroFocused = it })
            // Rows settle with the picked card's row title just under the spotlight (the same place every time).
            WithScroll(rememberRowAnchor(SPOT_ROW_TOP + SPOT_ROW_HEAD)) {
                LazyColumn(Modifier.fillMaxWidth().weight(1f).onFocusChanged { rowsFocused = it.hasFocus }, state = listState,
                    contentPadding = PaddingValues(top = SPOT_ROW_TOP, bottom = 240.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    var first = true
                    if (cw.isNotEmpty()) {
                        val f = first; first = false
                        item(key = "cw") { ContinueRow(act, cw, spot, Modifier.upToPlay(f && slides.isNotEmpty(), playFocus)) }
                    }
                    if (rows.isEmpty() && (movies == null || series == null)) item(key = "skel") { Column { SkeletonTitle(); SkeletonRow(width = Tv.poster) } }
                    val firstRow = if (first) rows.firstOrNull()?.id else null
                    items(rows, key = { it.id }) { r ->
                        SpotRow(r.title, Modifier.upToPlay(r.id == firstRow && slides.isNotEmpty(), playFocus), extra = if (r.ranked) "from TMDB" else null) {
                            CardRow(r.items, key = { "${it.kind}:${it.id}" }) { v, i ->
                                PosterCard(v, Tv.poster, rank = if (r.ranked) i + 1 else null, restoreKey = "${r.id}|${v.kind}:${v.id}",
                                    onFocus = { spot.preview(v) }) { act.go(Screen.Details(v)) }
                            }
                        }
                    }
                }
            }
        }
        // The rows dissolve into the page at the bottom edge instead of being cut off.
        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(28.dp).background(Brush.verticalGradient(listOf(Color.Transparent, Tv.bg))))
    }
}

