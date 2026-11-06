package com.wmorgan97.m3uplayer.tv

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

// ---------- The spotlight (Home, Movies, Series) ----------
//
// The top of a browse page: a big backdrop with the title, its year/rating/genres, the best copy's picture and sound
// and a short plot. On the featured slides it has [Play] [More info]; once the remote is in the rows it quietly shows
// the title the highlight rests on (after a short pause, so moving along a row never flickers). The rows sit below it
// and the row you're on always settles in the same place, just under the spotlight ([SPOT_ROW_TOP] + [SPOT_ROW_HEAD]).
//
// Cost on the Shield: moving the highlight writes one snapshot value that only a debounced collector reads, so card
// focus never recomposes the page; the spotlight itself recomposes once per settled title.

val SPOT_H = 226.dp            // spotlight height: kicker, title, tags, plot, buttons; leaves one full row of standard posters below
val SPOT_ROW_HEAD = 34.dp      // row title block
val SPOT_ROW_TOP = 8.dp        // room for the picked card to grow
private const val PREVIEW_DELAY = 320L // ms the highlight must rest on a card before the spotlight follows it

/** What the spotlight shows. */
@Immutable
data class Spot(val key: String, val kicker: String, val accent: Boolean, val title: String, val tags: List<String>, val rating: Double?, val plot: String, val backdrop: String,
                val quality: List<String> = emptyList(), val ref: String = "")

/** What arrives a moment after a spotlight title: its logo (already in memory when set) and, on the featured slides,
 *  its outside ratings. Keyed by "kind:id" ([Spot.ref]). */
@Immutable
data class SpotExtras(val logo: String?, val ratings: org.json.JSONObject? = null)

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

// (QualityTag / premiumQuality and SpotRow are shared components: Theme.kt.)

/** Where the remote is, as far as the spotlight cares. */
sealed interface SpotTarget {
    data object Hero : SpotTarget
    data class Card(val v: Vod, val p: Progress? = null) : SpotTarget
}

/** The spotlight's state. [preview] is called from card focus: it only writes [target], which nothing in composition reads. */
@Stable
class SpotlightState internal constructor(indexState: MutableIntState) {
    val target = mutableStateOf<SpotTarget>(SpotTarget.Hero)
    var index by indexState
    internal var touchedAt = 0L
    var spot by mutableStateOf<Spot?>(null)
    val extras = mutableStateMapOf<String, SpotExtras>()
    /** Follows what the spotlight shows (not where focus is), so buttons and text change together. */
    val heroMode: Boolean get() = spot?.key?.startsWith("hero:") != false
    fun preview(v: Vod, p: Progress? = null) { target.value = SpotTarget.Card(v, p) }
}

/** The spotlight's state and its three loops: backdrop prefetch, the debounced follow, and the 10 s slide timer.
 *  [ranks] maps "kind:id" to the title's place in its own kind's trending list ("#3 TRENDING SERIES"). */
@Composable
fun rememberSpotlight(slides: List<Vod>, ranks: Map<String, Int>): SpotlightState {
    val idx = rememberSaveable { mutableIntStateOf(0) }
    val s = remember { SpotlightState(idx) }
    val ctx = LocalContext.current
    // Every slide's backdrop is fetched up front, so a slide never appears blank and then pops in.
    LaunchedEffect(slides) {
        val l = coil.Coil.imageLoader(ctx); slides.forEach { l.enqueue(ImageRequest.Builder(ctx).data(it.backdrop).build()) }
        // Then each slide's title logo and outside ratings, one at a time (details are cached on the TV and the server;
        // ratings are only asked for these few featured titles, never for every card).
        slides.forEach { v -> loadExtras(s, v, ctx, withRatings = true) }
    }
    LaunchedEffect(slides, ranks) {
        snapshotFlow { s.target.value to s.index }.collectLatest { (t, i) ->
            when (t) {
                SpotTarget.Hero -> slides.getOrNull(i.coerceIn(0, (slides.size - 1).coerceAtLeast(0)))?.let { v -> s.spot = slideSpot(v, ranks["${v.kind}:${v.id}"] ?: (i + 1)) }
                is SpotTarget.Card -> {
                    delay(PREVIEW_DELAY)
                    val sp = cardSpot(t.v, t.p)
                    // The picture is in memory before the spotlight changes, so text and picture arrive together
                    // (and Details, opened from this card, has its backdrop at once).
                    if (sp.backdrop.isNotEmpty()) withTimeoutOrNull(1500) { coil.Coil.imageLoader(ctx).execute(ImageRequest.Builder(ctx).data(sp.backdrop).build()) }
                    s.spot = sp
                    loadExtras(s, t.v, ctx, withRatings = false)
                }
            }
        }
    }
    // Slides change every 10 s (a 0.8 s cross-fade), only while the spotlight is showing them, and wait longer after
    // you've moved by hand.
    LaunchedEffect(slides) {
        while (true) {
            delay(10_000)
            if (slides.size > 1 && s.target.value == SpotTarget.Hero && System.currentTimeMillis() - s.touchedAt > 12_000) s.index = (s.index + 1) % slides.size
        }
    }
    return s
}

/** A title's key for [SpotlightState.extras] (the title too: Continue watching's cards don't all carry an id). */
private fun refOf(v: Vod) = "${v.kind}:${v.id}:${v.title}"

/** Fetches a title's logo (into memory before it's shown, so it never pops in half-drawn) and, if asked, its outside
 *  ratings, then records them for the spotlight. Quietly does nothing when there's neither. */
private suspend fun loadExtras(s: SpotlightState, v: Vod, ctx: android.content.Context, withRatings: Boolean) {
    val ref = refOf(v)
    if (s.extras[ref]?.let { !withRatings || it.ratings != null } == true) return
    val info = withTimeoutOrNull(4000) { runCatching { Repo.tmdbInfo(v) }.getOrNull() } ?: return
    val logo = Repo.logoImg(info.optString("logo"))?.takeIf { u ->
        withTimeoutOrNull(3000) { coil.Coil.imageLoader(ctx).execute(ImageRequest.Builder(ctx).data(u).size(coil.size.Size.ORIGINAL).build()) } is coil.request.SuccessResult
    }
    val ratings = if (withRatings) withTimeoutOrNull(4000) { Repo.ratings(info.optString("imdb")) } else null
    if (logo != null || ratings != null) s.extras[ref] = SpotExtras(logo, ratings)
}

/** The title as its logo (TMDB's transparent title art), left-aligned within [maxW] x [maxH]; [text] while there's
 *  none, while it loads or if it fails. The logo fades in over the text's place. */
@Composable
fun TitleLogo(url: String?, title: String, maxW: androidx.compose.ui.unit.Dp, maxH: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier, text: @Composable () -> Unit) {
    if (url.isNullOrEmpty()) { text(); return }
    val ctx = LocalContext.current
    val painter = coil.compose.rememberAsyncImagePainter(remember(url) { ImageRequest.Builder(ctx).data(url).size(coil.size.Size.ORIGINAL).crossfade(false).build() })
    val st = painter.state
    val ok = st is coil.compose.AsyncImagePainter.State.Success
    val alpha by animateFloatAsState(if (ok) 1f else 0f, tween(Tv.durMed, easing = Tv.easeMove), label = "logo")
    Box(modifier.semantics { contentDescription = title }) {
        if (!ok) text()
        if (st !is coil.compose.AsyncImagePainter.State.Error)
            androidx.compose.foundation.Image(painter, null, Modifier.sizeIn(maxWidth = maxW, maxHeight = maxH).graphicsLayer { this.alpha = alpha },
                contentScale = ContentScale.Fit, alignment = Alignment.BottomStart)
    }
}

/** The backdrop layer: right-aligned, fading into the page on the left and at the bottom. Put it first in the page's Box. */
@Composable
fun SpotlightBackdrop(state: SpotlightState, modifier: Modifier) =
    CinematicBackdrop(state.spot?.backdrop ?: "", if (state.heroMode) Tv.durCrossfade else 300, modifier)

/** The spotlight's text and, on the slides, [Play] [More info] and the slide dots. [onHeroFocus] reports focus on the buttons. */
@Composable
fun SpotlightHeader(act: TvActivity, state: SpotlightState, slides: List<Vod>, refresh: Int, playFocus: FocusRequester, loading: Boolean,
                    onHeroFocus: (Boolean) -> Unit = {}) {
    Box(Modifier.fillMaxWidth().height(SPOT_H)) {
        val s = state.spot
        val heroMode = state.heroMode
        if (s == null) { if (loading) SpotSkeleton() }
        else SpotText(s, heroMode, state.extras[s.ref])
        if (slides.isNotEmpty()) {
            val index = state.index.coerceIn(0, slides.size - 1)
            val v = slides[index]
            val alpha by animateFloatAsState(if (heroMode) 1f else 0f, tween(Tv.durShort + 50, easing = Tv.easeMove), label = "buttons")
            HeroButtons(act, v, refresh, playFocus, Modifier.align(Alignment.BottomStart).padding(start = Tv.margin, bottom = 10.dp).graphicsLayer { this.alpha = alpha }
                .onFocusChanged { onHeroFocus(it.hasFocus); if (it.hasFocus) state.target.value = SpotTarget.Hero },
                onStep = { d -> state.index = (index + d + slides.size) % slides.size; state.touchedAt = System.currentTimeMillis() },
                canStepBack = index > 0)
            if (slides.size > 1) Dots(slides.size, index, Modifier.align(Alignment.BottomEnd).padding(end = Tv.margin, bottom = 22.dp).graphicsLayer { this.alpha = alpha })
        }
    }
}

private fun slideSpot(v: Vod, rank: Int) = Spot("hero:${v.kind}:${v.id}", "#$rank TRENDING ${if (v.kind == "series") "SERIES" else "MOVIE"}", true, v.title,
    listOfNotNull(v.year?.toString()) + v.genres.take(2), v.rating, v.overview, v.backdrop, qualityTags(v.name, v.group), refOf(v))

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
            qualityTags(p.item.optString("name"), p.item.optString("group")), refOf(v))
    }
    return Spot("${v.kind}:${v.id}", if (v.kind == "series") "SERIES" else "MOVIE", false, v.title,
        listOfNotNull(year?.toString()) + genres.take(2), rating, plot, backdrop, qualityTags(v.name, v.group), refOf(v))
}

/** A right-aligned backdrop fading into the page on the left and at the bottom, cross-fading between pictures.
 *  (Gradients only: no blur, which the Shield can't afford full-screen.) */
@Composable
fun CinematicBackdrop(url: String, fadeMs: Int, modifier: Modifier) {
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
private fun SpotText(s: Spot, heroMode: Boolean, extras: SpotExtras?) {
    // One copy at a time, faded in. A Crossfade composes and lays out two copies, which landed on the first frames of
    // the next move along a row (Shield trace: 20–25 ms recomposes).
    key(s.key) {
        val sp = s
        val fade = remember { Animatable(0f) }
        LaunchedEffect(Unit) { fade.animateTo(1f, tween(Tv.durMed, easing = Tv.easeMove)) }
        var titleLines by remember { mutableIntStateOf(1) }
        Column(Modifier.graphicsLayer { alpha = fade.value }.padding(start = Tv.margin, top = 22.dp).fillMaxWidth(.64f)) {
            Text(sp.kicker, fontSize = Tv.label, color = if (sp.accent) Tv.accent else Tv.muted, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp, maxLines = 1)
            Spacer(Modifier.height(6.dp))
            // The title logo when there is one (it counts as a two-line title for the plot below).
            val logo = extras?.logo
            if (logo != null) TitleLogo(logo, sp.title, 330.dp, 60.dp) { Text(sp.title, fontSize = Tv.display, fontWeight = Tv.wDisplay, color = Tv.text, maxLines = 1, overflow = TextOverflow.Ellipsis, lineHeight = Tv.displayLine) }
            else Text(sp.title, fontSize = Tv.display, fontWeight = Tv.wDisplay, color = Tv.text, maxLines = if (heroMode) 1 else 2, overflow = TextOverflow.Ellipsis, lineHeight = Tv.displayLine,
                onTextLayout = { titleLines = it.lineCount })
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                sp.tags.firstOrNull()?.let { Tag(it) }
                sp.rating?.takeIf { it > 0 }?.let { Tag("★ %.1f".format(it), color = Tv.star) }
                RatingsTag(extras?.ratings)
                sp.quality.forEach { q -> QualityTag(q) }
                sp.tags.drop(1).take(if (extras?.ratings != null) 1 else 2).forEach { Tag(it, color = Tv.muted, bg = Color(0x14FFFFFF)) }   // one genre fewer beside the outside ratings, so the row never runs off
            }
            if (sp.plot.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                // Two lines above the buttons; three once the buttons make way; one fewer under a two-line title.
                Text(sp.plot.trim(), fontSize = Tv.body, color = Tv.text2, lineHeight = Tv.bodyLine, overflow = TextOverflow.Ellipsis,
                    maxLines = ((if (heroMode) 2 else 3) - ((if (logo != null) 2 else titleLines) - 1)).coerceAtLeast(1))
            }
        }
    }
}

@Composable
private fun HeroButtons(act: TvActivity, v: Vod, refresh: Int, playFocus: FocusRequester, modifier: Modifier, onStep: (Int) -> Unit, canStepBack: Boolean) {
    val scope = rememberCoroutineScope()
    // The resume rule (see [resumePoint]): anything started and not finished carries on.
    val resumeAt = remember(v, refresh) {
        if (v.kind == "movie") Repo.movieUrl(v)?.let { Repo.progressFor(it) }.let(::resumePoint)
        else resumePoint(Repo.progress().firstOrNull { it.item.optString("series") == v.name && !it.done })
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

// ---------- rows under the spotlight ----------

/** Up from the first row of a spotlight page: always Play (not whichever button is nearest the card). */
fun Modifier.upToPlay(enabled: Boolean, playFocus: FocusRequester): Modifier = if (!enabled) this else onPreviewKeyEvent { e ->
    if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionUp) { runCatching { playFocus.requestFocus() }; true } else false
}

/** Continue watching, the same on Home, Movies and Series: 16:9 cards with a progress line and the time left; OK
 *  resumes, holding OK offers Resume / Start over / Remove. */
@Composable
fun ContinueRow(act: TvActivity, items: List<Progress>, spot: SpotlightState?, modifier: Modifier = Modifier) {
    var menu by remember { mutableStateOf<Progress?>(null) }
    val scope = rememberCoroutineScope()
    SpotRow("Continue watching", modifier) {
        CardRow(items, key = { it.url }) { p, _ ->
            val it = p.item
            WideCard(it.optString("logo"), progressTitle(it), "${clock((p.d - p.t).toLong())} left", (p.t / p.d).toFloat(), restoreKey = "cw:" + p.url,
                badge = if (it.optString("kind") == "episode") "EPISODE" else null, onLong = { menu = p },
                onFocus = { spot?.preview(progressVod(it), p) }) { resume(act, p) }
        }
    }
    menu?.let { p ->
        Options(progressTitle(p.item), listOf(
            "Resume" to { menu = null; resume(act, p) },
            "Start over" to { menu = null; resume(act, p, fromStart = true) },
            "Remove from Continue watching" to {
                menu = null
                val i = items.indexOf(p); Nav.restoreKey = (items.getOrNull(i + 1) ?: items.getOrNull(i - 1))?.let { "cw:" + it.url }
                scope.launch { Repo.saveProgress(p.url, p.t, p.d, p.item, hide = true); act.refresh++ }
            },
        )) { menu = null }
    }
}

// ---------- the resume rule ----------

/** Where a film or episode carries on from: its saved point when it was started (more than 5 s in) and isn't finished;
 *  null when it was never started or is finished (it plays from the beginning). "Start over" is the explicit restart.
 *  (Local twin of the player branch's `tv.player.Resume.startFor`; reconcile when the branches meet.) */
fun resumePoint(p: Progress?): Double? = p?.let { resumePoint(it.t, it.done) }
fun resumePoint(t: Double, done: Boolean): Double? = t.takeIf { !done && it > RESUME_MIN_S }

const val RESUME_MIN_S = 5.0
