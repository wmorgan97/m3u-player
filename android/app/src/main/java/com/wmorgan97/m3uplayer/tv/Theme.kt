package com.wmorgan97.m3uplayer.tv

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import androidx.compose.ui.input.key.*

// The TV app's design tokens: the single source for colour, type, spacing, shape, size, elevation and motion.
// Shared components built from them live in Components.kt (focus, buttons, chips, rows, panels, badges, states) and
// Cards.kt (picture cards and rows). Rules and ownership: android/docs/TV_DESIGN_SYSTEM.md.
//
// The UI renders at 1080p density (960x540dp) and is read from a sofa 2.5-3 m from a 55" set: nothing under 12sp,
// secondary text 13sp and up, a 48dp overscan-safe margin on the sides.

/** A named section in a system trace around a piece of composition. */
inline fun <T> traced(name: String, block: () -> T): T { android.os.Trace.beginSection(name); try { return block() } finally { android.os.Trace.endSection() } }

object Tv {
    // ---------- Colour roles ----------
    val bg = Color(0xFF0E1014)              // page background
    val rail = Color(0xFF0A0C10)            // side menu
    val surface = Color(0xFF181B22)         // base surface: tiles, list containers, dialogs
    val surface2 = Color(0xFF232733)        // raised: placeholders, selected-but-not-focused rows
    val surface3 = Color(0xFF2E3341)        // highest: fallback art, skeleton sheen
    val scrimSoft = Color(0x66000000)       // badges over pictures, light dimming
    val scrim = Color(0x9E000000)           // 62%: panels over video / content
    val scrimStrong = Color(0xCC000000)     // 80%: side panels, menu over the page
    val line = Color(0x1FFFFFFF)            // hairline borders, dividers
    val lineStrong = Color(0x33FFFFFF)      // outlines on secondary buttons / chips
    val text = Color.White                  // primary text
    val text2 = Color(0xFFD5DAE3)           // secondary text
    val muted = Color(0xFF9AA3B5)           // tertiary text (meta, counts, hints)
    val text3 get() = muted
    val accent = Color(0xFF5B93FF)          // state: selected, playing, progress, primary action
    val onAccent = Color.White
    val accentDeep = Color(0xFF2A4FB8)      // on-air fills, accent badges
    val live = Color(0xFFFF4550)            // a dot or a thin line only, never a large fill
    val star = Color(0xFFFFD166)            // ratings, favourites, reminders
    val success = Color(0xFF3DD68C)
    val warning = Color(0xFFFFB224)
    val danger = Color(0xFFE5484D)          // errors, destructive actions
    val error get() = danger
    val focusSurface = Color.White          // focused filled elements
    val onFocus = Color(0xFF0E1014)         // content on a focused element
    val focusRing = Color.White             // focus outline on pictures and fields
    val focusGlow = Color.White             // the soft halo under a focused card

    // ---------- Type scale (size / line height / weight) ----------
    val display = 30.sp;    val displayLine = 36.sp      // banner / details title
    val title = 22.sp;      val titleLine = 28.sp        // page title
    val headline = 20.sp;   val headlineLine = 26.sp     // dialog headings, big card titles
    val rowTitle = 18.sp;   val rowTitleLine = 24.sp     // section heading
    val body = 15.sp;       val bodyLine = 22.sp         // plots, menus, list titles
    val bodySmall = 14.sp;  val bodySmallLine = 20.sp    // buttons, secondary lines
    val caption = 13.sp;    val captionLine = 18.sp      // under cards, episode plots
    val label = 13.sp;      val labelLine = 18.sp        // tags, meta lines, counts
    val micro = 12.sp;      val microLine = 16.sp        // badges on pictures, guide times: the floor
    val numeral = 22.sp                                  // channel initials on a tile
    val rank = 40.sp                                     // Top 10 number on a poster
    val wDisplay = FontWeight.SemiBold
    val wTitle = FontWeight.SemiBold
    val wBody = FontWeight.Normal
    val wLabel = FontWeight.Medium
    val wStrong = FontWeight.SemiBold

    // ---------- Spacing (4dp grid) ----------
    val s1 = 4.dp; val s2 = 8.dp; val s3 = 12.dp; val s4 = 16.dp; val s5 = 20.dp; val s6 = 24.dp; val s8 = 32.dp; val s10 = 40.dp; val s12 = 48.dp
    val margin = 48.dp          // left/right content margin (overscan-safe: 5% of 960dp)
    val topMargin = 28.dp       // page top (5% of 540dp is 27)
    val bottomMargin = 28.dp
    val gap = 14.dp             // between cards in a row
    val rowGap = 26.dp          // above each row title
    val panelPadding = 24.dp    // inside dialogs and panels
    val playerSafeBottom = 32.dp

    // ---------- Shape ----------
    val radiusS = 6.dp          // badges, tags, small tiles
    val radius = 10.dp          // cards, rows
    val radiusL = 18.dp         // panels, dialogs, hero
    val shapeS = RoundedCornerShape(radiusS)
    val shape = RoundedCornerShape(radius)
    val rowShape = RoundedCornerShape(8.dp)   // dense list rows (a little tighter than cards at 38-48dp tall)
    val shapeL = RoundedCornerShape(radiusL)
    val bigShape get() = shapeL
    val pill = RoundedCornerShape(50)

    // ---------- Card dimensions ----------
    val poster = 128.dp         // 2:3 posters in rows: 6 across the content area plus a peek
    val posterWide = 140.dp     // 2:3 posters in grids
    val gridPoster get() = posterWide
    val wide = 236.dp           // 16:9 cards (continue watching)
    val channel = 176.dp        // 16:9 channel / programme cards
    val landscape = 300.dp      // 16:9 large landscape cards (featured)
    val episodeStill = 176.dp   // episode row picture
    val listRow = 40.dp         // dense list row (channels)
    val settingRow = 48.dp      // settings row
    val chipHeight = 32.dp
    val buttonHeight = 40.dp
    val switchWidth = 36.dp     // the on/off switch at the end of a settings row ([TvSwitch])
    val switchHeight = 20.dp
    val liveDot = 6.dp          // the red "on now" dot drawn inline beside text (Live TV, the guide, live badges)

    // ---------- Elevation / glow ----------
    val focusFrame = 3.dp       // white frame on a focused picture
    val glowElevation = 18.dp   // spread of the halo under a focused card

    // ---------- Focus scale ----------
    val scaleCard = 1.07f       // posters
    val scaleWide = 1.05f       // 16:9 cards
    val scaleLarge = 1.02f      // wide rows (episodes), banners
    val scaleButton = 1.04f
    val scaleDisc = 1.05f

    // ---------- Icons ----------
    val iconS = 16.dp; val icon = 20.dp; val iconM = 24.dp; val iconL = 32.dp; val iconXL = 56.dp

    // ---------- Motion ----------
    val durShort = 150          // exits, quick fades
    val durMed = 250            // overlays entering, content swaps
    val durLong = 400           // large surfaces
    val durCrossfade = 800      // hero / backdrop cross-fades
    val easeEnter: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)   // emphasized decelerate: things arriving
    val easeMove: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)         // standard: things moving on screen
    val easeExit: Easing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)    // emphasized accelerate: things leaving
    /** Focus size changes: no bounce, medium-low stiffness (calm, ~230 ms to settle), retargets with its velocity. */
    val focusSpring: SpringSpec<Float> = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)
    /** Focus frame / glow: the same curve, quicker, so the highlight reads at once while the size eases. */
    val focusFadeSpring: SpringSpec<Float> = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 1400f)
    /** Sideways scrolling of a row: no bounce, ~180 ms; a new target mid-flight keeps the current speed (no pile-up). */
    val scrollSpring: SpringSpec<Float> = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 650f)
    /** Up/down between rows: a touch softer than sideways (bigger distance), ~220 ms. */
    val rowScrollSpring: SpringSpec<Float> = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 450f)
    /** Held D-pad pace: at most one move per this many ms (sideways / between rows on pages of rows / in dense lists). */
    const val heldCardMs = 100L
    const val heldRowMs = 150L
    const val heldListMs = 90L
}

/** Text styles for the type scale (for new code; older code passes fontSize/lineHeight from [Tv] directly). */
object TvType {
    val display = TextStyle(fontSize = Tv.display, lineHeight = Tv.displayLine, fontWeight = Tv.wDisplay)
    val title = TextStyle(fontSize = Tv.title, lineHeight = Tv.titleLine, fontWeight = Tv.wTitle)
    val headline = TextStyle(fontSize = Tv.headline, lineHeight = Tv.headlineLine, fontWeight = Tv.wTitle)
    val rowTitle = TextStyle(fontSize = Tv.rowTitle, lineHeight = Tv.rowTitleLine, fontWeight = Tv.wTitle)
    val body = TextStyle(fontSize = Tv.body, lineHeight = Tv.bodyLine, fontWeight = Tv.wBody)
    val bodySmall = TextStyle(fontSize = Tv.bodySmall, lineHeight = Tv.bodySmallLine, fontWeight = Tv.wBody)
    val label = TextStyle(fontSize = Tv.label, lineHeight = Tv.labelLine, fontWeight = Tv.wLabel)
    val caption = TextStyle(fontSize = Tv.caption, lineHeight = Tv.captionLine, fontWeight = Tv.wBody)
    val micro = TextStyle(fontSize = Tv.micro, lineHeight = Tv.microLine, fontWeight = Tv.wLabel)
    val button = TextStyle(fontSize = Tv.bodySmall, lineHeight = Tv.bodySmallLine, fontWeight = Tv.wStrong)
}

/** Hold OK for options on a list row: the remote's first key repeat (~0.5s) is the hold, and the release after it is
 *  swallowed so the row's normal click never follows. (Cards get this from onLongClick; list rows need it spelled out.) */
@Composable
fun Modifier.holdOk(onHold: () -> Unit): Modifier {
    var held by remember { mutableStateOf(false) }
    return this.onPreviewKeyEvent { e ->
        val ok = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
        if (!ok) return@onPreviewKeyEvent false
        when (e.type) {
            KeyEventType.KeyDown -> if (e.nativeKeyEvent.repeatCount >= 1) { if (!held) { held = true; onHold() }; true } else { held = false; false }
            KeyEventType.KeyUp -> if (held) { held = false; true } else false
            else -> false
        }
    }
}

/** For pop-ups: OK only counts once it has been pressed afresh inside it. A menu opened by holding OK otherwise received
 *  that same press (its repeats and its release) and ran its first entry, usually "Watch". */
@Composable
fun Modifier.freshOkOnly(): Modifier {
    var armed by remember { mutableStateOf(false) }
    return this.onPreviewKeyEvent { e ->
        val ok = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
        if (!ok || armed) return@onPreviewKeyEvent false
        if (e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount == 0) { armed = true; false } else true
    }
}

/** Section heading above a row: a title, an optional quiet caption, and a count on the right. */
@Composable
fun RowTitle(text: String, extra: String? = null, count: Int? = null) = traced("RowTitle") {
    Row(Modifier.fillMaxWidth().padding(start = Tv.margin, end = Tv.margin, top = Tv.rowGap, bottom = 12.dp), verticalAlignment = Alignment.Bottom) {
        Text(text, fontSize = Tv.rowTitle, fontWeight = Tv.wTitle, color = Tv.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        if (extra != null) { Spacer(Modifier.width(10.dp)); Text(extra, fontSize = Tv.label, color = Tv.muted, maxLines = 1, modifier = Modifier.padding(bottom = 2.dp)) }
        Spacer(Modifier.weight(1f))
        if (count != null) Text("$count", fontSize = Tv.label, color = Tv.muted, modifier = Modifier.padding(bottom = 2.dp))
    }
}

/** A page's heading (Live TV, a genre grid, Settings). */
@Composable
fun PageTitle(text: String, sub: String? = null) {
    Column(Modifier.padding(start = Tv.margin, end = Tv.margin, top = Tv.topMargin, bottom = 18.dp)) {
        Text(text, fontSize = Tv.title, lineHeight = Tv.titleLine, fontWeight = Tv.wTitle, color = Tv.text)
        if (sub != null) Text(sub, fontSize = Tv.bodySmall, color = Tv.muted, modifier = Modifier.padding(top = 2.dp))
    }
}

/** Small rounded tag: quality, year, genre. (A [TvBadge] with free colours; kept for the many existing call sites.) */
@Composable
fun Tag(text: String, color: Color = Tv.text2, bg: Color = Color(0x26FFFFFF), size: TextUnit = Tv.label) {
    // A fixed line box (centred, not trimmed), so a tag with a ★ is exactly as tall as one without.
    Text(text, Modifier.background(bg, Tv.shapeS).padding(horizontal = 7.dp, vertical = 2.dp), fontSize = size, color = color, fontWeight = Tv.wStrong, maxLines = 1, softWrap = false,
        style = androidx.compose.ui.text.TextStyle(lineHeight = size * 1.4f, lineHeightStyle = androidx.compose.ui.text.style.LineHeightStyle(androidx.compose.ui.text.style.LineHeightStyle.Alignment.Center, androidx.compose.ui.text.style.LineHeightStyle.Trim.None),
            platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false)))
}

/** The clock in the top-right corner, as on every TV guide app. */
@Composable
fun Clock(modifier: Modifier = Modifier) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(10_000); now = System.currentTimeMillis() } }
    Text(hhmm(now), modifier, fontSize = Tv.body, fontWeight = FontWeight.Medium, color = Tv.text2)
}

/** Progress bar under a picture (see [TvProgress]). */
@Composable
fun ProgressLine(progress: Float, modifier: Modifier = Modifier, height: Dp = 4.dp) = TvProgress(progress, modifier.height(height))

/** Premium formats get the accent tag; plain resolutions stay neutral. */
fun premiumQuality(q: String) = q == "4K" || q == "HDR" || q == "Dolby Vision" || q == "Atmos"

/** A quality tag drawn the one way everywhere (spotlight, details). */
@Composable
fun QualityTag(q: String) = if (premiumQuality(q)) Tag(q, color = Tv.text, bg = Tv.accentDeep) else Tag(q)

/** Outside ratings beside the TMDB star, the one way everywhere (spotlight, details): a single quiet tag,
 *  "IMDb 8.8 · RT 87%" (Metacritic only when Rotten Tomatoes has no score). Nothing when OMDb isn't set up. */
fun outsideRatings(r: org.json.JSONObject?): String? {
    if (r == null) return null
    val imdb = r.optDouble("imdb").takeIf { !it.isNaN() && it > 0 }?.let { "IMDb %.1f".format(it) }
    val rt = if (r.has("rt")) "RT ${r.optInt("rt")}%" else null
    val mc = if (rt == null) r.optInt("mc").takeIf { it > 0 }?.let { "MC $it" } else null
    return listOfNotNull(imdb, rt, mc).joinToString("  ·  ").ifEmpty { null }
}
@Composable
fun RatingsTag(r: org.json.JSONObject?) { outsideRatings(r)?.let { Tag(it) } }

/** A row under the spotlight: its title (and a quiet note, e.g. "from TMDB" on the Top 10s, or a count), then its cards. */
@Composable
fun SpotRow(title: String, modifier: Modifier = Modifier, extra: String? = null, count: Int? = null, content: @Composable () -> Unit) {
    Column(modifier) {
        Row(Modifier.fillMaxWidth().height(SPOT_ROW_HEAD).padding(start = Tv.margin, end = Tv.margin), verticalAlignment = Alignment.Top) {
            Text(title, fontSize = Tv.rowTitle, lineHeight = Tv.rowTitleLine, fontWeight = Tv.wTitle, color = Tv.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            if (extra != null) Text(extra, fontSize = Tv.label, color = Tv.muted, maxLines = 1, modifier = Modifier.padding(start = 10.dp, top = 4.dp))
            if (count != null) { Spacer(Modifier.weight(1f)); Text("$count", fontSize = Tv.label, color = Tv.muted, maxLines = 1, modifier = Modifier.padding(top = 4.dp)) }
        }
        content()
    }
}
