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

private val SPOT_H = 226.dp            // spotlight height: kicker, title, tags, plot, buttons; leaves one full row of standard posters below
private val ROW_HEAD = 34.dp           // row title block
private val ROW_TOP = 8.dp             // room for the picked card to grow
private const val PREVIEW_DELAY = 320L // ms the highlight must rest on a card before the spotlight follows it

/** What the spotlight shows. */
@Immutable
data class Spot(val key: String, val kicker: String, val accent: Boolean, val title: String, val tags: List<String>, val rating: Double?, val plot: String, val backdrop: String,
                val quality: List<String> = emptyList())

private val Q_UHD = Regex("""\b(4k|uhd|2160p?)\b|ᵁᴴᴰ""", RegexOption.IGNORE_CASE)
private val Q_1080 = Regex("""\b(1080p?|fhd|full ?hd)\b""", RegexOption.IGNORE_CASE)
private val Q_720 = Regex("""\b720p?\b""", RegexOption.IGNORE_CASE)
private val Q_HD = Regex("""\bhd\b|ᴴᴰ""", RegexOption.IGNORE_CASE)
private val Q_DV = Regex("""dolby ?vision|\bdv\b""", RegexOption.IGNORE_CASE)
private val Q_HDR = Regex("""\bhdr(10\+?)?\b|ᴴᴰᴿ""", RegexOption.IGNORE_CASE)
private val Q_ATMOS = Regex("""atmos""", RegexOption.IGNORE_CASE)
private val Q_DOLBY = Regex("""dolby(?! ?vision)|\bddp\b|\beac3\b|\b[57]\.1\b""", RegexOption.IGNORE_CASE)

/** The best picture and sound the playlist has for a title, read from its best copy's name and group (the catalogue
 *  keeps the best-scoring copy as the title): "4K", "Dolby Vision" / "HDR", "Atmos" / "Dolby"; else "1080p" / "720p" /
 *  "HD". Only what the names actually say; nothing when they say nothing. */
fun qualityTags(name: String, group: String): List<String> {
    val t = "$name $group"
    val res = when { Q_UHD.containsMatchIn(t) -> "4K"; Q_1080.containsMatchIn(t) -> "1080p"; Q_720.containsMatchIn(t) -> "720p"; Q_HD.containsMatchIn(t) -> "HD"; else -> null }
    val hdr = when { Q_DV.containsMatchIn(t) -> "Dolby Vision"; Q_HDR.containsMatchIn(t) -> "HDR"; else -> null }
    val audio = when { Q_ATMOS.containsMatchIn(t) -> "Atmos"; Q_DOLBY.containsMatchIn(t) -> "Dolby"; else -> null }
    return listOfNotNull(res, hdr, audio)
}

/** Premium formats get the accent tag; plain resolutions stay neutral. */
private fun premium(q: String) = q == "4K" || q == "HDR" || q == "Dolby Vision" || q == "Atmos"

private sealed interface Target {
    data object Hero : Target
    data class Card(val v: Vod, val p: Progress? = null) : Target
}

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

    // The remote's place, written on every card focus but read only by the debounced collector below: moving the
    // highlight never recomposes the page.
    val target = remember { mutableStateOf<Target>(Target.Hero) }
    var index by rememberSaveable { mutableIntStateOf(0) }
    var touchedAt by remember { mutableLongStateOf(0L) }
    var rowsFocused by remember { mutableStateOf(false) }
    var spot by remember { mutableStateOf<Spot?>(null) }
    val ctx = LocalContext.current
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

    // Every slide's backdrop is fetched up front, so a slide never appears blank and then pops in.
    LaunchedEffect(slides) { val l = coil.Coil.imageLoader(ctx); slides.forEach { l.enqueue(ImageRequest.Builder(ctx).data(it.backdrop).build()) } }
    LaunchedEffect(slides) {
        snapshotFlow { target.value to index }.collectLatest { (t, i) ->
            when (t) {
                Target.Hero -> slides.getOrNull(i.coerceIn(0, (slides.size - 1).coerceAtLeast(0)))?.let { v -> spot = slideSpot(v, ranks["${v.kind}:${v.id}"] ?: (i + 1)) }
                is Target.Card -> {
                    delay(PREVIEW_DELAY)
                    val s = cardSpot(t.v, t.p)
                    // The picture is in memory before the spotlight changes, so text and picture arrive together.
                    if (s.backdrop.isNotEmpty()) withTimeoutOrNull(1500) { coil.Coil.imageLoader(ctx).execute(ImageRequest.Builder(ctx).data(s.backdrop).build()) }
                    spot = s
                }
            }
        }
    }
    // Slides change as before (every 10 s, a 0.8 s cross-fade), only while the spotlight is showing them, and wait
    // longer after you've moved by hand.
    LaunchedEffect(slides) {
        while (true) {
            delay(10_000)
            if (slides.size > 1 && target.value == Target.Hero && System.currentTimeMillis() - touchedAt > 12_000) index = (index + 1) % slides.size
        }
    }
    // Back from deep in the rows: to the top first (Back again opens the menu).
    BackHandler(enabled = rowsFocused && slides.isNotEmpty()) { scope.launch { listState.animateScrollToItem(0) }; runCatching { playFocus.requestFocus() } }

    val nothing = loaded == 3 && movies == null && series == null && cw.isEmpty()
    if (nothing) { EmptyState(Icons.Default.Movie, "No films or series yet", "Add a playlist with films and series in Settings"); return }
    val heroMode = spot?.key?.startsWith("hero:") != false   // follows what the spotlight shows, so buttons and text change together
    Box(Modifier.fillMaxSize().onPreviewKeyEvent { if (it.type == KeyEventType.KeyDown) pressed = true; false }) {
        Backdrop(spot?.backdrop ?: "", if (heroMode) 800 else 500, Modifier.align(Alignment.TopEnd).fillMaxWidth(.74f).aspectRatio(16f / 9f))
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth().height(SPOT_H)) {
                val s = spot
                if (s == null) { if (movies == null && series == null) SpotSkeleton() }
                else SpotText(s, heroMode)
                if (slides.isNotEmpty()) {
                    val v = slides[index.coerceIn(0, slides.size - 1)]
                    val alpha by animateFloatAsState(if (heroMode) 1f else 0f, tween(Tv.durShort + 50, easing = Tv.easeMove), label = "buttons")
                    HeroButtons(act, v, refresh, playFocus, Modifier.align(Alignment.BottomStart).padding(start = Tv.margin, bottom = 10.dp).graphicsLayer { this.alpha = alpha }
                        .onFocusChanged { heroFocused = it.hasFocus; if (it.hasFocus) target.value = Target.Hero },
                        onStep = { d -> index = (index + d + slides.size) % slides.size; touchedAt = System.currentTimeMillis(); d < 0 && index == 0 },
                        canStepBack = index > 0)
                    if (slides.size > 1) Dots(slides.size, index, Modifier.align(Alignment.BottomEnd).padding(end = Tv.margin, bottom = 22.dp).graphicsLayer { this.alpha = alpha })
                }
            }
            // Rows settle with the picked card's row title just under the spotlight (the same place every time).
            WithScroll(rememberRowAnchor(ROW_TOP + ROW_HEAD)) {
                LazyColumn(Modifier.fillMaxWidth().weight(1f).onFocusChanged { rowsFocused = it.hasFocus }, state = listState,
                    contentPadding = PaddingValues(top = ROW_TOP, bottom = 240.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    var first = true
                    fun upToHero(isFirst: Boolean): Modifier = if (!isFirst || slides.isEmpty()) Modifier else Modifier.onPreviewKeyEvent { e ->
                        // Up from the first row: always the Play button (not whichever button is nearest the card).
                        if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionUp) { runCatching { playFocus.requestFocus() }; true } else false
                    }
                    if (cw.isNotEmpty()) {
                        val f = first; first = false
                        item(key = "cw") {
                            HomeRowFrame("Continue watching", upToHero(f)) {
                                CardRow(cw, key = { it.url }) { p, _ ->
                                    val it = p.item
                                    WideCard(it.optString("logo"), progressTitle(it), "${clock((p.d - p.t).toLong())} left", (p.t / p.d).toFloat(), restoreKey = "cw:" + p.url,
                                        badge = if (it.optString("kind") == "episode") "EPISODE" else null, onLong = { menuFor.value = p },
                                        onFocus = { target.value = Target.Card(progressVod(it), p) }) { resume(act, p) }
                                }
                            }
                        }
                    }
                    if (rows.isEmpty() && (movies == null || series == null)) item(key = "skel") { Column { SkeletonTitle(); SkeletonRow(width = Tv.poster) } }
                    val firstRow = if (first) rows.firstOrNull()?.id else null
                    items(rows, key = { it.id }) { r ->
                        HomeRowFrame(r.title, upToHero(r.id == firstRow), extra = if (r.ranked) "from TMDB" else null) {
                            CardRow(r.items, key = { "${it.kind}:${it.id}" }) { v, i ->
                                PosterCard(v, Tv.poster, rank = if (r.ranked) i + 1 else null, restoreKey = "${r.id}|${v.kind}:${v.id}",
                                    onFocus = { target.value = Target.Card(v) }) { act.go(Screen.Details(v)) }
                            }
                        }
                    }
                }
            }
        }
        // The rows dissolve into the page at the bottom edge instead of being cut off.
        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(28.dp).background(Brush.verticalGradient(listOf(Color.Transparent, Tv.bg))))
    }

    menuFor.value?.let { p ->
        Options(progressTitle(p.item), listOf(
            "Resume" to { menuFor.value = null; resume(act, p) },
            "Start over" to { menuFor.value = null; resume(act, p, fromStart = true) },
            "Remove from Continue watching" to {
                menuFor.value = null
                val i = cw.indexOf(p); Nav.restoreKey = (cw.getOrNull(i + 1) ?: cw.getOrNull(i - 1))?.let { "cw:" + it.url }
                scope.launch { Repo.saveProgress(p.url, p.t, p.d, p.item, hide = true); act.refresh++ }
            },
        )) { menuFor.value = null }
    }
}

/** Continue watching's hold-OK menu (shared by the row's cards; one per screen). */
private val menuFor = mutableStateOf<Progress?>(null)

private fun slideSpot(v: Vod, rank: Int) = Spot("hero:${v.kind}:${v.id}", "#$rank TRENDING ${if (v.kind == "series") "SERIES" else "MOVIE"}", true, v.title,
    listOfNotNull(v.year?.toString()) + v.genres.take(2), v.rating, v.overview, v.backdrop, qualityTags(v.name, v.group))

/** A card's spotlight: what the row already knows, with TMDB's backdrop, plot and genres filled in where it doesn't
 *  (saved on the TV for 30 days, so mostly instant). */
private suspend fun cardSpot(v: Vod, p: Progress?): Spot {
    val info = if (v.backdrop.isEmpty() || v.overview.isBlank() || v.genres.isEmpty()) withTimeoutOrNull(1500) { runCatching { Repo.tmdbInfo(v) }.getOrNull() } else null
    val genres = v.genres.ifEmpty { info?.optJSONArray("genres")?.let { g -> (0 until g.length()).map { g.optString(it) } } ?: emptyList() }
    val year = v.year ?: info?.optString("year")?.take(4)?.toIntOrNull()
    val rating = v.rating ?: info?.optDouble("vote")?.takeIf { !it.isNaN() && it > 0 }
    val plot = v.overview.ifBlank { info?.optString("overview") ?: "" }
    val backdrop = v.backdrop.ifEmpty { info?.optString("backdrop") ?: "" }
    if (p != null) {
        val m = vodMeta(p.item.optString("name"))
        val ep = if (p.item.optString("kind") == "episode" && m.episode != null) "S${m.season ?: 1} E${m.episode}" else null
        return Spot("cw:${p.url}", "CONTINUE WATCHING", false, v.title, listOfNotNull(ep, "${clock((p.d - p.t).toLong())} left") + genres.take(2), rating, plot, backdrop,
            qualityTags(p.item.optString("name"), p.item.optString("group")))
    }
    return Spot("${v.kind}:${v.id}", if (v.kind == "series") "SERIES" else "MOVIE", false, v.title,
        listOfNotNull(year?.toString()) + genres.take(2), rating, plot, backdrop, qualityTags(v.name, v.group))
}

/** The backdrop: right-aligned, fading into the page on the left and at the bottom, cross-fading between titles. */
@Composable
private fun Backdrop(url: String, fadeMs: Int, modifier: Modifier) {
    val ctx = LocalContext.current
    Box(modifier) {
        Crossfade(url, animationSpec = tween(fadeMs, easing = Tv.easeMove), label = "backdrop") { u ->
            if (u.isNotEmpty()) AsyncImage(remember(u) { ImageRequest.Builder(ctx).data(u).crossfade(false).build() }, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Tv.bg, .38f to Tv.bg.copy(alpha = .72f), .7f to Color.Transparent)))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(.35f to Color.Transparent, .78f to Tv.bg.copy(alpha = .85f), 1f to Tv.bg)))
    }
}

@Composable
private fun SpotText(s: Spot, heroMode: Boolean) {
    Crossfade(s, animationSpec = tween(Tv.durMed, easing = Tv.easeMove), label = "spot") { sp ->
        var titleLines by remember(sp.key) { mutableIntStateOf(1) }
        Column(Modifier.padding(start = Tv.margin, top = 22.dp).fillMaxWidth(.56f)) {
            Text(sp.kicker, fontSize = Tv.label, color = if (sp.accent) Tv.accent else Tv.muted, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp, maxLines = 1)
            Spacer(Modifier.height(6.dp))
            Text(sp.title, fontSize = Tv.display, fontWeight = Tv.wDisplay, color = Tv.text, maxLines = if (heroMode) 1 else 2, overflow = TextOverflow.Ellipsis, lineHeight = Tv.displayLine,
                onTextLayout = { titleLines = it.lineCount })
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                sp.tags.firstOrNull()?.let { Tag(it) }
                sp.rating?.takeIf { it > 0 }?.let { Tag("★ %.1f".format(it), color = Tv.star) }
                sp.quality.forEach { q -> if (premium(q)) Tag(q, color = Tv.text, bg = Tv.accentDeep) else Tag(q) }
                sp.tags.drop(1).forEach { Tag(it, color = Tv.muted, bg = Color(0x14FFFFFF)) }
            }
            if (sp.plot.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                // Two lines above the buttons; three once the buttons make way; one fewer under a two-line title.
                Text(sp.plot.trim(), fontSize = Tv.bodySmall, color = Tv.text2, lineHeight = Tv.bodySmallLine, overflow = TextOverflow.Ellipsis,
                    maxLines = ((if (heroMode) 2 else 3) - (titleLines - 1)).coerceAtLeast(1))
            }
        }
    }
}

@Composable
private fun HeroButtons(act: TvActivity, v: Vod, refresh: Int, playFocus: FocusRequester, modifier: Modifier, onStep: (Int) -> Boolean, canStepBack: Boolean) {
    val scope = rememberCoroutineScope()
    val resumeAt = remember(v, refresh) {
        if (v.kind == "movie") Repo.movieUrl(v)?.let { Repo.progressFor(it) }?.takeIf { !it.done && it.t > 30 }?.t
        else Repo.progress().firstOrNull { it.item.optString("series") == v.name && !it.done && it.t > 30 }?.t
    }
    // Left on the first button / right on the last step through the slides (left on the first slide: the menu).
    fun Modifier.steps(left: Boolean, right: Boolean) = onPreviewKeyEvent { e ->
        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        when {
            left && e.key == Key.DirectionLeft && canStepBack -> { onStep(-1); true }
            right && e.key == Key.DirectionRight -> { onStep(1); true }
            else -> false
        }
    }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        TvButton(if (resumeAt != null) "Resume" else "Play", {
            if (v.kind == "movie") Repo.movieUrl(v)?.let { url -> act.play(listOf(movieItem(v, url, resumeAt ?: 0.0)), 0) }
            else scope.launch {
                val all = runCatching { Repo.episodes(v) }.getOrNull()
                val next = all?.let { nextEpisode(it) }
                if (all != null && next != null) playEpisode(act, v, all, next.first, next.second) else act.go(Screen.Details(v))
            }
        }, Modifier.focusRequester(playFocus).steps(left = true, right = false), icon = Icons.Default.PlayArrow, style = TvButtonStyle.Primary)
        TvButton("More info", { Nav.pending = null; act.go(Screen.Details(v)) }, Modifier.steps(left = false, right = true), icon = Icons.Default.Info)
    }
}

@Composable
private fun Dots(count: Int, active: Int, modifier: Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(count) { i -> Box(Modifier.height(6.dp).width(if (i == active) 18.dp else 6.dp).clip(RoundedCornerShape(3.dp)).background(if (i == active) Tv.text else Color(0x66FFFFFF))) }
    }
}

/** A row: its title (and a quiet source, e.g. "from TMDB" on the Top 10s), then its cards (keyline scrolling: CardRow). */
@Composable
private fun HomeRowFrame(title: String, modifier: Modifier, extra: String? = null, content: @Composable () -> Unit) {
    Column(modifier) {
        Row(Modifier.height(ROW_HEAD).padding(start = Tv.margin, end = Tv.margin), verticalAlignment = Alignment.Top) {
            Text(title, fontSize = Tv.rowTitle, lineHeight = Tv.rowTitleLine, fontWeight = Tv.wTitle, color = Tv.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            if (extra != null) Text(extra, fontSize = Tv.label, color = Tv.muted, maxLines = 1, modifier = Modifier.padding(start = 10.dp, top = 4.dp))
        }
        content()
    }
}

@Composable
private fun SpotSkeleton() {
    val b = shimmerBrush()
    Column(Modifier.padding(start = Tv.margin, top = 26.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.width(140.dp).height(12.dp).clip(RoundedCornerShape(4.dp)).background(b))
        Box(Modifier.width(320.dp).height(30.dp).clip(RoundedCornerShape(6.dp)).background(b))
        Box(Modifier.width(220.dp).height(18.dp).clip(RoundedCornerShape(5.dp)).background(b))
        Box(Modifier.width(420.dp).height(36.dp).clip(RoundedCornerShape(5.dp)).background(b))
    }
}
