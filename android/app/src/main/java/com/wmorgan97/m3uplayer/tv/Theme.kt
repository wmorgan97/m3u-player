package com.wmorgan97.m3uplayer.tv

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*

// The TV app's design system. Everything on screen is built from these values, so the whole app shares one
// rhythm: a 48dp safe margin (Android TV's overscan guide), an 8dp spacing grid, one type scale sized for a
// sofa (nothing under 12sp), one card shape, one focus treatment.

object Tv {
    // Layout
    val margin = 48.dp          // left/right content margin (overscan-safe)
    val topMargin = 28.dp
    val gap = 14.dp             // between cards in a row
    val rowGap = 26.dp          // between rows
    val radius = 10.dp
    val shape = RoundedCornerShape(radius)
    val bigShape = RoundedCornerShape(18.dp)

    // Card sizes (dp). Posters: 6 across the content area plus a peek, like Google TV.
    val poster = 128.dp
    val posterWide = 140.dp     // grids
    val wide = 236.dp           // 16:9 cards (continue watching, episodes)
    val channel = 176.dp

    // Type scale
    val display = 34.sp         // banner / details title
    val title = 24.sp           // page title
    val rowTitle = 19.sp
    val body = 16.sp
    val bodySmall = 14.sp
    val caption = 13.sp         // under cards
    val label = 12.sp           // smallest allowed on TV

    // Colours
    val bg = Color(0xFF0E1014)
    val rail = Color(0xFF0A0C10)
    val surface = Color(0xFF181B22)
    val surface2 = Color(0xFF232733)
    val surface3 = Color(0xFF2E3341)
    val line = Color(0x1FFFFFFF)
    val text = Color.White
    val text2 = Color(0xFFD5DAE3)
    val muted = Color(0xFF9AA3B5)
    val accent = Color(0xFF5B93FF)
    val accentDeep = Color(0xFF2A4FB8)
    val star = Color(0xFFFFD166)
    val live = Color(0xFFFF4550)
    val danger = Color(0xFFE5484D)
    val focusRing = Color.White
}

// Older names used across the screens
val Bg get() = Tv.bg
val Surface1 get() = Tv.surface
val Surface2 get() = Tv.surface2
val Accent get() = Tv.accent
val Muted get() = Tv.muted

/** One soft moving sheen, shared by every placeholder on screen (one animation, not one per block). */
@Composable
fun shimmerBrush(): Brush {
    val t by rememberInfiniteTransition(label = "shimmer").animateFloat(0f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "x")
    return Brush.linearGradient(listOf(Tv.surface2, Tv.surface3, Tv.surface2), start = Offset(t * 1400f - 400f, 0f), end = Offset(t * 1400f, 300f))
}
@Composable
fun Modifier.shimmer(): Modifier = this.background(shimmerBrush())

/** A row of grey poster-shaped blocks while the real row loads: the page has shape from the first frame. */
@Composable
fun SkeletonRow(count: Int = 7, width: Dp = Tv.poster, aspect: Float = 2f / 3f) {
    val brush = shimmerBrush()
    Row(Modifier.padding(horizontal = Tv.margin), horizontalArrangement = Arrangement.spacedBy(Tv.gap)) {
        repeat(count) {
            Column(Modifier.width(width)) {
                Box(Modifier.width(width).aspectRatio(aspect).clip(Tv.shape).background(brush))
                Spacer(Modifier.height(8.dp))
                Box(Modifier.width(width * .7f).height(12.dp).clip(RoundedCornerShape(4.dp)).background(brush))
            }
        }
    }
}

@Composable
fun SkeletonTitle() {
    Box(Modifier.padding(start = Tv.margin, top = Tv.rowGap, bottom = 12.dp).width(180.dp).height(18.dp).clip(RoundedCornerShape(5.dp)).shimmer())
}

/** Rows are prepared before they scroll into view (and the first cards of each row with them), so moving down a page
 *  never waits for a row to be built: the one cause of dropped frames on a Shield. */
@Composable
fun rememberPageState() = androidx.compose.foundation.lazy.rememberLazyListState(prefetchStrategy = androidx.compose.foundation.lazy.LazyListPrefetchStrategy(nestedPrefetchItemCount = 8))

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

/** Section heading above a row: a title, an optional quiet caption, and a count on the right. */
@Composable
fun RowTitle(text: String, extra: String? = null, count: Int? = null) {
    Row(Modifier.fillMaxWidth().padding(start = Tv.margin, end = Tv.margin, top = Tv.rowGap, bottom = 12.dp), verticalAlignment = Alignment.Bottom) {
        Text(text, fontSize = Tv.rowTitle, fontWeight = FontWeight.SemiBold, color = Tv.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (extra != null) { Spacer(Modifier.width(10.dp)); Text(extra, fontSize = Tv.label, color = Tv.muted, modifier = Modifier.padding(bottom = 2.dp)) }
        Spacer(Modifier.weight(1f))
        if (count != null) Text("$count", fontSize = Tv.label, color = Tv.muted, modifier = Modifier.padding(bottom = 2.dp))
    }
}

/** A page's heading (Live TV, a genre grid, Settings). */
@Composable
fun PageTitle(text: String, sub: String? = null) {
    Column(Modifier.padding(start = Tv.margin, end = Tv.margin, top = Tv.topMargin, bottom = 18.dp)) {
        Text(text, fontSize = Tv.title, fontWeight = FontWeight.Bold, color = Tv.text)
        if (sub != null) Text(sub, fontSize = Tv.bodySmall, color = Tv.muted, modifier = Modifier.padding(top = 2.dp))
    }
}

/** Small rounded tag: quality, year, genre. */
@Composable
fun Tag(text: String, color: Color = Tv.text2, bg: Color = Color(0x26FFFFFF), size: TextUnit = Tv.label) {
    Text(text, Modifier.background(bg, RoundedCornerShape(5.dp)).padding(horizontal = 7.dp, vertical = 2.dp), fontSize = size, color = color, fontWeight = FontWeight.SemiBold)
}

/** Nothing here: an icon, a line, and a hint of what to do. */
@Composable
fun EmptyState(icon: ImageVector, title: String, hint: String? = null) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, tint = Tv.surface3, modifier = Modifier.size(64.dp))
            Spacer(Modifier.height(16.dp))
            Text(title, fontSize = Tv.body, color = Tv.text2, fontWeight = FontWeight.Medium)
            if (hint != null) { Spacer(Modifier.height(6.dp)); Text(hint, fontSize = Tv.bodySmall, color = Tv.muted) }
        }
    }
}

/** The clock in the top-right corner, as on every TV guide app. */
@Composable
fun Clock(modifier: Modifier = Modifier) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(10_000); now = System.currentTimeMillis() } }
    Text(hhmm(now), modifier, fontSize = Tv.body, fontWeight = FontWeight.Medium, color = Tv.text2)
}

/** Progress bar under a picture. */
@Composable
fun ProgressLine(progress: Float, modifier: Modifier = Modifier, height: Dp = 4.dp) {
    Box(modifier.height(height).background(Color(0x59FFFFFF))) { Box(Modifier.fillMaxHeight().fillMaxWidth(progress.coerceIn(0f, 1f)).background(Tv.accent)) }
}
