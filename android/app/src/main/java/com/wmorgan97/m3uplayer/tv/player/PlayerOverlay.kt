package com.wmorgan97.m3uplayer.tv.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.wmorgan97.m3uplayer.tv.Repo
import com.wmorgan97.m3uplayer.tv.BadgeTone
import com.wmorgan97.m3uplayer.tv.FocusStyle
import com.wmorgan97.m3uplayer.tv.Tv
import com.wmorgan97.m3uplayer.tv.LiveTvTokens
import com.wmorgan97.m3uplayer.tv.TvBadge
import com.wmorgan97.m3uplayer.tv.TvButton
import com.wmorgan97.m3uplayer.tv.TvButtonStyle
import com.wmorgan97.m3uplayer.tv.TvIconButton
import com.wmorgan97.m3uplayer.tv.TvListRow
import com.wmorgan97.m3uplayer.tv.TvPanel
import com.wmorgan97.m3uplayer.tv.TvProgress
import com.wmorgan97.m3uplayer.tv.onFocusedChange
import com.wmorgan97.m3uplayer.tv.tvClickable
import com.wmorgan97.m3uplayer.tv.tvFocus
import java.util.Locale

// The new player interface (PlayerActivity hosts it in a ComposeView above the video). Calm and sparse: nothing on
// screen until asked for, one panel at a time, the same focus language everywhere (white = focus).
//
//   Controls (hold OK on live; OK on films):
//     top right: clock · LIVE / −4:12 · sleep                         (a thin top shade, nothing else up there)
//     bottom:    [logo] 101  Channel                     1080p 50 fps DD 5.1
//                Programme title                                       20:00 – 21:00
//                Two lines of description …
//                ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
//                Next 21:00  Following programme
//                (⏯)  [≡ Channels] [♪ Audio] [CC Subtitles] [★ Favourite] [··· More]
//   Films:      (⟲10) (⏯) (30⟳)   [⏭ Next episode] [Episodes] [Audio] [Subtitles] [··· More], with a seek bar above.

private fun hm(ms: Long) = java.text.SimpleDateFormat("HH:mm", Locale.UK).format(java.util.Date(ms))
private fun clock(ms: Long): String { val s = ms / 1000; return if (s >= 3600) String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60) else String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60) }

private val enter: EnterTransition = fadeIn(tween(Tv.durMed, easing = Tv.easeEnter)) + slideInVertically(tween(Tv.durMed, easing = Tv.easeEnter)) { it / 12 }
private val exit: ExitTransition = fadeOut(tween(Tv.durShort))

/** Asks for focus once the target is laid out (a frame later), and never throws if it has gone already. */
@Composable
private fun FocusOnShow(r: FocusRequester, key: Any? = Unit) {
    LaunchedEffect(key) { androidx.compose.runtime.withFrameNanos { }; runCatching { r.requestFocus() } }
}

@Composable
fun PlayerOverlay(ui: PlayerUi) {
    Box(Modifier.fillMaxSize()) {
        val chrome = ui.controls
        AnimatedVisibility(ui.next != null, enter = fadeIn(tween(Tv.durMed)), exit = exit) {
            Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color.Transparent, Color(0x66000000), Color(0xCC000000)))))
        }
        AnimatedVisibility(chrome, enter = fadeIn(tween(Tv.durMed, easing = Tv.easeEnter)), exit = exit) { Controls(ui) }
        AnimatedVisibility(ui.banner != null && !chrome, Modifier.align(Alignment.BottomCenter), enter = enter, exit = exit) {
            ui.banner?.let { Banner(ui, it) }
        }
        AnimatedVisibility(ui.next != null, Modifier.align(Alignment.BottomEnd), enter = enter, exit = exit) { ui.next?.let { NextCard(ui, it) } }
        val p = ui.panel
        AnimatedVisibility(p is PlayerUi.Channels, Modifier.align(Alignment.CenterStart),
            enter = fadeIn(tween(Tv.durMed, easing = Tv.easeEnter)) + slideInHorizontally(tween(Tv.durMed, easing = Tv.easeEnter)) { -it / 10 }, exit = exit) {
            (p as? PlayerUi.Channels)?.let { ChannelsPanel(ui, it) }
        }
        AnimatedVisibility(p != null && p !is PlayerUi.Channels, Modifier.align(Alignment.CenterEnd),
            enter = fadeIn(tween(Tv.durMed, easing = Tv.easeEnter)) + slideInHorizontally(tween(Tv.durMed, easing = Tv.easeEnter)) { it / 10 }, exit = exit) {
            when (p) { is PlayerUi.Options -> OptionsPanel(ui, p); is PlayerUi.Episodes -> EpisodesPanel(ui, p); PlayerUi.More -> MorePanel(ui); else -> Unit }
        }
        AnimatedVisibility(ui.info != null, Modifier.align(Alignment.CenterEnd), enter = enter, exit = exit) { ui.info?.let { InfoSheet(ui, it) } }
        AnimatedVisibility(ui.error != null, Modifier.align(Alignment.Center), enter = enter, exit = exit) { ui.error?.let { ErrorCard(ui, it) } }
        AnimatedVisibility(ui.notice != null, Modifier.align(Alignment.TopCenter), enter = enter, exit = exit) { ui.notice?.let { Notice(it) } }
        AnimatedVisibility(ui.hud != null, Modifier.align(Alignment.Center), enter = fadeIn(tween(Tv.durShort)), exit = exit) { ui.hud?.let { Hud(it) } }
    }
}

// ---------- pieces shared by the banner and the controls ----------

@Composable
private fun Logo(url: String, w: Int, h: Int, gap: Int) {
    val src = remember(url) { Repo.logo(url) }
    var failed by remember(url) { mutableStateOf(false) }   // no logo found for the channel: no empty box either
    if (src == null || failed) return
    val ctx = LocalContext.current
    Box(Modifier.size(w.dp, h.dp).clip(RoundedCornerShape(Tv.radius)).background(Tv.surface2).padding(horizontal = 8.dp, vertical = 5.dp)) {
        AsyncImage(remember(src) { ImageRequest.Builder(ctx).data(src).crossfade(false).build() }, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit, onError = { failed = true })
    }
    Spacer(Modifier.width(gap.dp))
}

@Composable
private fun Badges(list: List<String>) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) { list.forEach { TvBadge(it) } }
}

@Composable
private fun LiveState(ui: PlayerUi) {
    val b = ui.behindMs
    if (b >= 1500) TvBadge("−" + clock(b), tone = BadgeTone.Accent) else TvBadge("LIVE", tone = BadgeTone.Live)
}

/** Programme progress, computed when shown (the banner and controls are up for seconds, not minutes). */
private fun progress(s: Long, e: Long): Float = if (s > 0 && e > s) ((System.currentTimeMillis() - s) / (e - s).toFloat()).coerceIn(0f, 1f) else -1f

// ---------- channel banner ----------

@Composable
private fun Banner(ui: PlayerUi, b: PlayerUi.Banner) {
    // Apple-TV-like: no panel, one soft gradient up from the bottom edge (under a third of the screen), the content on it.
    //   [logo]  101  Channel                                  [4K] [HDR] [Dolby]   21:04
    //           Programme title
    //           20:00 – 21:00  ·  34 min left  ━━━━━━━━━        Next 21:00  Following programme
    Box(Modifier.fillMaxWidth().background(Brush.verticalGradient(0f to Color(LiveTvTokens.bannerGradientTop), .45f to Color(LiveTvTokens.bannerGradientMid), 1f to Color(LiveTvTokens.bannerGradientEnd)))
        .padding(top = 36.dp, start = Tv.margin, end = Tv.margin, bottom = Tv.playerSafeBottom)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Logo(b.logo, 96, 54, gap = 20)
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (b.num.isNotEmpty()) { Text(b.num, color = Tv.muted, fontSize = Tv.rowTitle, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(end = 10.dp)) }
                    Text(b.name, color = Tv.text2, fontSize = Tv.rowTitle, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Spacer(Modifier.weight(1f))
                    if (ui.tuning) { Spinner(); Spacer(Modifier.width(12.dp)) } else QuietBadges(ui.badges)
                    Text(ui.clock, color = Tv.text2, fontSize = Tv.rowTitle, modifier = Modifier.padding(start = 16.dp))
                }
                val now = b.nowTitle.ifEmpty { b.fallback }
                Text(now, color = Tv.text, fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                val timed = b.nowTitle.isNotEmpty() && b.start > 0 && b.end > b.start
                if (timed || b.next.isNotEmpty()) Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (timed) {
                        val left = ((b.end - System.currentTimeMillis()) / 60_000L).toInt()
                        Text(hm(b.start) + " – " + hm(b.end) + (if (left >= 1) "  ·  " + (if (left >= 60) "${left / 60}h ${"%02d".format(left % 60)}m" else "$left min") + " left" else ""),
                            color = Tv.text2, fontSize = Tv.bodySmall, maxLines = 1)
                        TvProgress(progress(b.start, b.end), Modifier.padding(start = 14.dp).width(140.dp).clip(Tv.pill).height(3.dp))
                    }
                    if (b.next.isNotEmpty()) Text((if (b.end > 0) "Next  ${hm(b.end)}  " else "Next  ") + b.next, color = Tv.muted, fontSize = Tv.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(start = if (timed) 24.dp else 0.dp))
                }
            }
        }
    }
}

private fun premium(q: String) = q == "4K" || q.startsWith("HDR") || q == "HLG" || q.startsWith("Dolby")

/** Quality badges: small quiet pills (a faint fill, no outline); the premium ones (4K, HDR, Dolby) a little brighter. */
@Composable
private fun QuietBadges(list: List<String>) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        list.forEach { q ->
            Text(q, color = if (premium(q)) Tv.text else Tv.text2, fontSize = Tv.micro, lineHeight = Tv.microLine, fontWeight = FontWeight.Medium, maxLines = 1,
                modifier = Modifier.background(Color(if (premium(q)) 0x33FFFFFF else 0x1FFFFFFF), Tv.pill).padding(horizontal = 9.dp, vertical = 2.dp))
        }
    }
}

/** The small "tuning" ring: one rotating arc (an infinite transition only while it is on screen). */
@Composable
private fun Spinner() {
    val t = androidx.compose.animation.core.rememberInfiniteTransition(label = "spin")
    val a by t.animateFloat(0f, 360f, androidx.compose.animation.core.infiniteRepeatable(tween(900, easing = LinearEasing)), label = "a")
    Box(Modifier.size(20.dp).drawBehind {
        drawArc(Color(0xCCFFFFFF), a, 270f, false, style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round))
    })
}

// ---------- controls ----------

@Composable
private fun Controls(ui: PlayerUi) {
    val m = ui.media
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().height(120.dp).background(Brush.verticalGradient(listOf(Color(0x99000000), Color.Transparent))))
        Box(Modifier.fillMaxWidth().height(420.dp).align(Alignment.BottomCenter).background(Brush.verticalGradient(listOf(Color.Transparent, Color(0x99000000), Color(0xD9000000), Color(0xF2000000)))))
        // Top right: the clock and where playback is.
        Row(Modifier.align(Alignment.TopEnd).padding(top = Tv.topMargin, end = Tv.margin), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (ui.sleep != "Off") TvBadge("Sleep · ${ui.sleep}")
            if (m.live) LiveState(ui)
            Text(ui.clock, color = Tv.text2, fontSize = Tv.title)
        }
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = Tv.margin, end = Tv.margin, bottom = Tv.playerSafeBottom)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Logo(m.logo, 80, 45, gap = 14)
                if (m.live && m.num.isNotEmpty()) { Text(m.num, color = Tv.muted, fontSize = Tv.title, fontWeight = FontWeight.SemiBold); Spacer(Modifier.width(12.dp)) }
                Text(m.title, color = Tv.text, fontSize = Tv.display, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.weight(1f))
                Badges(ui.badges)
            }
            val timed = m.live && m.nowTitle.isNotEmpty() && m.nowStart > 0 && m.nowEnd > m.nowStart
            val line = if (m.live) m.nowTitle.ifEmpty { m.subtitle } else m.subtitle
            if (line.isNotEmpty() || timed) {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(line, color = Tv.text2, fontSize = Tv.rowTitle, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    if (timed) Text("${hm(m.nowStart)} – ${hm(m.nowEnd)}", color = Tv.muted, fontSize = Tv.bodySmall, modifier = Modifier.padding(start = 16.dp))
                }
            }
            if (m.desc.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(m.desc, color = Tv.muted, fontSize = Tv.bodySmall, lineHeight = Tv.bodySmallLine, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth(0.72f))
            }
            Spacer(Modifier.height(12.dp))
            val first = remember { FocusRequester() }
            if (m.live) {
                if (timed) TvProgress(progress(m.nowStart, m.nowEnd), Modifier.fillMaxWidth())
                if (m.nextTitle.isNotEmpty()) { Spacer(Modifier.height(8.dp)); Text("Next  ${if (m.nowEnd > 0) hm(m.nowEnd) else ""}   ${m.nextTitle}", color = Tv.muted, fontSize = Tv.caption, maxLines = 1) }
            } else SeekBar(ui)
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!m.live) TvIconButton(Icons.Default.Replay10, "Back 10 seconds", { ui.act("seek", -10_000) })
                TvIconButton(if (ui.playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (ui.playing) "Pause" else "Play", { ui.act("play") }, Modifier.focusRequester(first), size = 56.dp)
                if (!m.live) TvIconButton(Icons.Default.Forward30, "Forward 30 seconds", { ui.act("seek", 30_000) })
                Spacer(Modifier.width(12.dp))
                if (m.live) {
                    if (m.canList) TvButton("Channels", { ui.act("channels") }, icon = Icons.AutoMirrored.Filled.List)
                } else {
                    if (m.series && m.hasNext) TvButton("Next episode", { ui.act("nextEpisode") }, icon = Icons.Default.SkipNext)
                    if (m.canList) TvButton(if (m.series) "Episodes" else m.listTitle.ifEmpty { "Up next" }, { ui.act("list") }, icon = Icons.Default.VideoLibrary)
                }
                TvButton("Audio", { ui.act("audio") }, icon = Icons.Default.Audiotrack)
                TvButton("Subtitles", { ui.act("subs") }, icon = Icons.Default.ClosedCaption)
                ui.favourite?.let { f -> TvButton(if (f) "Favourite" else "Add to favourites", { ui.act("fav") }, icon = if (f) Icons.Default.Star else Icons.Default.StarBorder) }
                TvButton("More", { ui.act("more") }, icon = Icons.Default.MoreHoriz)
            }
            FocusOnShow(first)
        }
    }
}

/** Films: the position line. Focus it (up from the buttons) and left/right skip 10 s (30 s held); the skip shows at once
 *  and is applied when the key settles (PlayerActivity.seekBy). Reads the position only here: one small recomposition
 *  a second while the controls are up. */
@Composable
private fun SeekBar(ui: PlayerUi) {
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    val d = ui.dur
    val p = if (ui.seekTo >= 0) ui.seekTo else ui.pos
    val f = if (d > 0) (p / d.toFloat()).coerceIn(0f, 1f) else 0f
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(clock(p), color = Tv.text, fontSize = Tv.bodySmall, modifier = Modifier.widthIn(min = 64.dp))
        Box(
            Modifier.weight(1f).height(24.dp)
                .onKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                    val big = e.nativeKeyEvent.repeatCount > 3
                    when (e.key) {
                        Key.DirectionLeft -> { ui.act("seek", if (big) -30_000 else -10_000); true }
                        Key.DirectionRight -> { ui.act("seek", if (big) 30_000 else 10_000); true }
                        else -> false
                    }
                }
                .focusable(interactionSource = src)
                .drawBehind {
                    val h = (if (focused) 6 else 4).dp.toPx(); val cy = size.height / 2
                    drawRoundRect(Color(0x40FFFFFF), topLeft = androidx.compose.ui.geometry.Offset(0f, cy - h / 2), size = Size(size.width, h), cornerRadius = CornerRadius(h / 2))
                    drawRoundRect(Tv.accent, topLeft = androidx.compose.ui.geometry.Offset(0f, cy - h / 2), size = Size(size.width * f, h), cornerRadius = CornerRadius(h / 2))
                    if (focused) drawCircle(Color.White, 9.dp.toPx(), androidx.compose.ui.geometry.Offset(size.width * f, cy))
                },
        )
        Text(if (d > 0) "−" + clock((d - p).coerceAtLeast(0)) else "", color = Tv.muted, fontSize = Tv.bodySmall, modifier = Modifier.widthIn(min = 72.dp).padding(start = 12.dp))
    }
}

// ---------- side panels ----------

private val RowMod = Modifier.fillMaxWidth()
private val RowPad = PaddingValues(start = 16.dp, end = 14.dp, top = 9.dp, bottom = 9.dp)
private val panelHeight = Modifier.padding(top = Tv.topMargin, bottom = Tv.playerSafeBottom).fillMaxHeight()

@Composable
private fun OptionsPanel(ui: PlayerUi, o: PlayerUi.Options) {
    val start = o.checked.coerceAtLeast(0)
    val state = rememberLazyListState(maxOf(0, start - 3))
    val fr = remember { FocusRequester() }
    TvPanel(panelHeight.padding(end = Tv.margin).width(380.dp), title = o.title) {
        LazyColumn(state = state, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            itemsIndexed(o.rows) { i, r ->
                TvListRow({ ui.panel = null; o.pick.on(i) }, RowMod.then(if (i == start) Modifier.focusRequester(fr) else Modifier), contentPadding = RowPad, selected = i == o.checked) { focused ->
                    Text(r, color = if (focused) Tv.onFocus else Tv.text, fontSize = Tv.body, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    if (i == o.checked) Icon(Icons.Default.Check, null, tint = if (focused) Tv.onFocus else Tv.accent, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
    FocusOnShow(fr, o)
}

/** Live TV: the channels down the left (the one playing marked), "Last channel" on top; OK switches. */
@Composable
private fun ChannelsPanel(ui: PlayerUi, c: PlayerUi.Channels) {
    val off = if (c.last.isNotEmpty()) 1 else 0
    val start = (c.current + off).coerceAtLeast(0)
    val state = rememberLazyListState(maxOf(0, start - 3))
    val fr = remember { FocusRequester() }
    val rows = remember(c.rows) { (0 until c.rows.length()).map { c.rows.optJSONObject(it) } }
    TvPanel(panelHeight.padding(start = Tv.margin).width(420.dp), title = "Channels") {
        LazyColumn(state = state, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (off == 1) item {
                TvListRow({ ui.panel = null; ui.act("last") }, RowMod.then(if (start == 0) Modifier.focusRequester(fr) else Modifier), contentPadding = RowPad) { focused ->
                    Icon(Icons.Default.History, null, tint = if (focused) Tv.onFocus else Tv.text2, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Last channel", color = if (focused) Tv.onFocus else Tv.text, fontSize = Tv.body, fontWeight = FontWeight.Medium)
                        Text(c.last, color = if (focused) Color(0xFF4A505C) else Tv.muted, fontSize = Tv.caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            itemsIndexed(rows) { i, r ->
                val playing = i == c.current
                TvListRow({ ui.panel = null; if (!playing) ui.act("jump", i.toLong()) }, RowMod.then(if (i + off == start) Modifier.focusRequester(fr) else Modifier), selected = playing, contentPadding = RowPad) { focused ->
                    val n = r?.optString("n").orEmpty()
                    if (n.isNotEmpty()) Text(n, color = if (focused) Tv.onFocus else Tv.muted, fontSize = Tv.bodySmall, fontWeight = FontWeight.Medium, modifier = Modifier.widthIn(min = 40.dp))
                    Column(Modifier.weight(1f)) {
                        Text(r?.optString("t").orEmpty(), color = if (focused) Tv.onFocus else if (playing) Color(0xFFBFD3FF) else Tv.text, fontSize = Tv.body, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val sub = r?.optString("nt").orEmpty().ifEmpty { r?.optString("s").orEmpty() }
                        if (sub.isNotEmpty()) Text(sub, color = if (focused) Color(0xFF4A505C) else Tv.muted, fontSize = Tv.caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val pr = progress(r?.optLong("ps") ?: 0, r?.optLong("pe") ?: 0)
                        if (pr >= 0) { Spacer(Modifier.height(5.dp)); TvProgress(pr, Modifier.fillMaxWidth().height(3.dp)) }
                    }
                    if (playing) { Spacer(Modifier.width(8.dp)); Icon(Icons.Default.GraphicEq, "Playing", tint = if (focused) Tv.onFocus else Tv.accent, modifier = Modifier.size(18.dp)) }
                }
            }
        }
    }
    FocusOnShow(fr, c)
}

/** Episodes (or the films of the list): each row leads with its 16:9 still, then "S1 E2", the title, the runtime and how
 *  far you got (a thin line; a tick once watched). The one playing is marked; OK plays (resuming where it was left). */
@Composable
private fun EpisodesPanel(ui: PlayerUi, e: PlayerUi.Episodes) {
    val start = e.current.coerceAtLeast(0)
    val state = rememberLazyListState(maxOf(0, start - 2))
    val fr = remember { FocusRequester() }
    val ctx = LocalContext.current
    val rows = remember(e.rows) { (0 until e.rows.length()).map { e.rows.optJSONObject(it) } }
    TvPanel(panelHeight.padding(end = Tv.margin).width(540.dp), title = e.title) {
        LazyColumn(state = state, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            itemsIndexed(rows) { i, r ->
                val playing = i == e.current
                TvListRow({ ui.panel = null; if (!playing) ui.act("jump", i.toLong()) }, RowMod.then(if (i == start) Modifier.focusRequester(fr) else Modifier), selected = playing, contentPadding = RowPad) { focused ->
                    val art = r?.optString("a").orEmpty()
                    val img = remember(art) { if (art.startsWith("http")) Repo.img(art, 320) else null }
                    Box(Modifier.size(128.dp, 72.dp).clip(RoundedCornerShape(Tv.radiusS)).background(Tv.surface2)) {
                        if (img != null) AsyncImage(remember(img) { ImageRequest.Builder(ctx).data(img).crossfade(150).build() }, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        if (r?.optBoolean("w") == true) Box(Modifier.align(Alignment.TopEnd).padding(4.dp).size(20.dp).clip(CircleShape).background(Color(0xCC000000)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Check, "Watched", tint = Color.White, modifier = Modifier.size(14.dp))
                        }
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        val label = r?.optString("e").orEmpty()
                        val name = r?.optString("n").orEmpty().ifEmpty { r?.optString("t").orEmpty() }
                        val sub = listOfNotNull(label.ifEmpty { null }, r?.optString("s")?.ifEmpty { null }, if (playing) "Playing" else null).joinToString("  ·  ")
                        if (sub.isNotEmpty()) Text(sub, color = if (focused) Color(0xFF4A505C) else if (playing) Color(0xFFBFD3FF) else Tv.muted, fontSize = Tv.caption, maxLines = 1)
                        Text(name, color = if (focused) Tv.onFocus else Tv.text, fontSize = Tv.body, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        val pr = r?.optDouble("pr", -1.0) ?: -1.0
                        if (pr > 0 && r?.optBoolean("w") != true) { Spacer(Modifier.height(6.dp)); TvProgress(pr.toFloat(), Modifier.fillMaxWidth(0.8f).height(3.dp)) }
                    }
                }
            }
        }
    }
    FocusOnShow(fr, e)
}

/** The things you reach for less: contextual (live / film / series), each row says its current value. */
@Composable
private fun MorePanel(ui: PlayerUi) {
    val m = ui.media
    data class R(val id: String, val icon: ImageVector, val label: String, val value: String = "")
    val rows = buildList {
        if (m.live && m.last.isNotEmpty()) add(R("last", Icons.Default.History, "Last channel", m.last))
        if (m.live && m.catchup) add(R("startOver", Icons.Default.Replay, "Start over", "From the beginning"))
        if (m.live && ui.behindMs >= 1500) add(R("goLive", Icons.Default.FiberManualRecord, "Back to live", "−" + clock(ui.behindMs)))
        if (m.fromLive) add(R("goLive", Icons.Default.FiberManualRecord, "Back to live TV"))
        if (!m.live && m.canList) add(R("list", Icons.Default.VideoLibrary, if (m.series) "Episodes" else m.listTitle.ifEmpty { "Up next" }))
        if (!m.live && ui.pos > 5_000) add(R("restart", Icons.Default.Replay, "Start from the beginning"))
        add(R("info", Icons.Default.Info, "Programme & stream info"))
        add(R("aspect", Icons.Default.AspectRatio, "Picture size", ui.aspect))
        add(R("sleep", Icons.Default.Bedtime, "Sleep timer", ui.sleep))
        add(R("frameMatch", Icons.Default.Tv, "Match frame rate", if (ui.frameMatch) "On" else "Off"))
        add(R("classic", Icons.Default.ViewQuilt, "Player interface", "New · switch to classic"))
    }
    val fr = remember { FocusRequester() }
    TvPanel(panelHeight.padding(end = Tv.margin).width(400.dp), title = "More") {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            itemsIndexed(rows) { i, r ->
                TvListRow({ ui.act(r.id) }, RowMod.then(if (i == 0) Modifier.focusRequester(fr) else Modifier), contentPadding = RowPad) { focused ->
                    Icon(r.icon, null, tint = if (focused) Tv.onFocus else Tv.text2, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(14.dp))
                    Text(r.label, color = if (focused) Tv.onFocus else Tv.text, fontSize = Tv.body, fontWeight = FontWeight.Medium, maxLines = 1, modifier = Modifier.weight(1f))
                    if (r.value.isNotEmpty()) Text(r.value, color = if (focused) Color(0xFF4A505C) else Tv.muted, fontSize = Tv.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 12.dp).widthIn(max = 170.dp))
                }
            }
        }
    }
    FocusOnShow(fr)
}

// ---------- info sheet ----------

@Composable
private fun InfoSheet(ui: PlayerUi, i: PlayerUi.Info) {
    val m = ui.media
    val fr = remember { FocusRequester() }
    TvPanel(panelHeight.padding(end = Tv.margin).width(520.dp)) {
        Text(i.title, color = Tv.text, fontSize = Tv.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (i.when_.isNotEmpty()) { Spacer(Modifier.height(4.dp)); Text(i.when_, color = Tv.muted, fontSize = Tv.bodySmall) }
        if (i.desc.isNotEmpty()) { Spacer(Modifier.height(12.dp)); Text(i.desc, color = Tv.text2, fontSize = Tv.body, lineHeight = Tv.bodyLine, maxLines = 7, overflow = TextOverflow.Ellipsis) }
        if (i.next.isNotEmpty()) { Spacer(Modifier.height(12.dp)); Text(i.next, color = Tv.muted, fontSize = Tv.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        Spacer(Modifier.height(20.dp))
        Text("Stream", color = Tv.text, fontSize = Tv.rowTitle, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        if (i.badges.isNotEmpty()) { Badges(i.badges); Spacer(Modifier.height(8.dp)) }
        listOf("Video" to i.video, "Audio" to i.audio, "Bitrate" to i.bitrate).filter { it.second.isNotEmpty() }.forEach { (k, v) ->
            Row(Modifier.padding(vertical = 2.dp)) {
                Text(k, color = Tv.muted, fontSize = Tv.bodySmall, modifier = Modifier.width(72.dp))
                Text(v, color = Tv.text2, fontSize = Tv.bodySmall, maxLines = 2)
            }
        }
        if (i.video.isEmpty() && i.audio.isEmpty()) Text("Stream details appear once it's playing.", color = Tv.muted, fontSize = Tv.bodySmall)
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (m.live && m.catchup) TvButton("Start over", { ui.info = null; ui.act("startOver") }, Modifier.focusRequester(fr), icon = Icons.Default.Replay, style = TvButtonStyle.Primary)
            TvButton("Audio", { ui.info = null; ui.act("audio") }, if (!(m.live && m.catchup)) Modifier.focusRequester(fr) else Modifier, icon = Icons.Default.Audiotrack)
            TvButton("Subtitles", { ui.info = null; ui.act("subs") }, icon = Icons.Default.ClosedCaption)
            ui.favourite?.let { f -> TvIconButton(if (f) Icons.Default.Star else Icons.Default.StarBorder, "Favourite", { ui.act("fav") }, size = 44.dp, selected = f) }
        }
    }
    FocusOnShow(fr)
}

// ---------- error, notice, HUD ----------

@Composable
private fun ErrorCard(ui: PlayerUi, e: PlayerUi.Err) {
    val fr = remember { FocusRequester() }
    TvPanel(Modifier.width(520.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.ErrorOutline, null, tint = Tv.danger, modifier = Modifier.size(26.dp))
            Spacer(Modifier.width(12.dp))
            Text("Can't play this", color = Tv.text, fontSize = Tv.title, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(8.dp))
        Text(e.message, color = Tv.text2, fontSize = Tv.body, lineHeight = Tv.bodyLine, maxLines = 3)
        if (e.code.isNotEmpty()) { Spacer(Modifier.height(4.dp)); Text(e.code, color = Tv.muted, fontSize = Tv.micro) }
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TvButton("Try again", { ui.act("retry") }, Modifier.focusRequester(fr), icon = Icons.Default.Refresh, style = TvButtonStyle.Primary)
            if (e.canLast) TvButton("Last channel", { ui.act("last") }, icon = Icons.Default.History)
            TvButton("Close", { ui.act("close") }, style = TvButtonStyle.Tertiary)
        }
        if (ui.media.live) { Spacer(Modifier.height(12.dp)); Text("▲ ▼  change channel", color = Tv.muted, fontSize = Tv.micro) }
    }
    FocusOnShow(fr, e)
}

@Composable
private fun Notice(text: String) {
    Row(Modifier.padding(top = Tv.topMargin).clip(Tv.pill).background(Tv.scrimStrong).border(1.dp, Tv.line, Tv.pill).padding(start = 18.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.NotificationsActive, null, tint = Tv.star, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, color = Tv.text, fontSize = Tv.body, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 620.dp))
        Spacer(Modifier.width(14.dp))
        Box(Modifier.clip(Tv.pill).background(Tv.focusSurface).padding(horizontal = 14.dp, vertical = 6.dp)) { Text("OK  Watch", color = Tv.onFocus, fontSize = Tv.label, fontWeight = FontWeight.Medium) }
    }
}

@Composable
private fun Hud(text: String) {
    Box(Modifier.clip(RoundedCornerShape(Tv.radiusL)).background(Tv.scrim).padding(horizontal = 24.dp, vertical = 12.dp)) {
        Text(text, color = Tv.text, fontSize = Tv.display, fontWeight = FontWeight.SemiBold)
    }
}

// ---------- next episode / still watching ----------

@Composable
private fun NextCard(ui: PlayerUi, n: PlayerUi.Next) {
    val fr = remember { FocusRequester() }
    val ctx = LocalContext.current
    TvPanel(Modifier.padding(end = Tv.margin, bottom = Tv.playerSafeBottom).width(440.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val img = remember(n.art) { Repo.img(n.art, 480)?.takeIf { n.art.startsWith("http") } }
            if (img != null) {
                AsyncImage(remember(img) { ImageRequest.Builder(ctx).data(img).crossfade(true).build() }, null,
                    Modifier.size(160.dp, 90.dp).clip(RoundedCornerShape(Tv.radius)).background(Tv.surface2), contentScale = ContentScale.Crop)
                Spacer(Modifier.width(16.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(n.label, color = Tv.muted, fontSize = Tv.caption, maxLines = 1)
                Spacer(Modifier.height(2.dp))
                Text(n.name, color = Tv.text, fontSize = Tv.rowTitle, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (n.sub.isNotEmpty()) Text(n.sub, color = Tv.text2, fontSize = Tv.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CountdownButton(ui, n.play, Modifier.focusRequester(fr))
            TvButton(n.other, { ui.act("nextOther") })
        }
    }
    FocusOnShow(fr, n)
}

/** Play next, filling from the left as the countdown runs: animated in the draw phase only (no recomposition), and it
 *  holds still while you've paused. */
@Composable
private fun CountdownButton(ui: PlayerUi, text: String, modifier: Modifier) {
    var focused by remember { mutableStateOf(false) }
    val fill = remember { Animatable(0f) }
    val c = ui.countdown
    LaunchedEffect(c) {
        fill.snapTo(c.from)
        if (c.running && c.leftMs > 0) fill.animateTo(1f, tween(c.leftMs.toInt(), easing = LinearEasing))
    }
    Row(
        modifier.height(Tv.buttonHeight)
            .onFocusedChange { focused = it }
            .tvFocus(FocusStyle.Pill)
            .tvClickable({ ui.act("nextPlay") }, label = text)
            .drawBehind {   // (inside the focus fill: the countdown shows on the white pill too)
                val r = CornerRadius(size.height / 2)
                if (!focused) drawRoundRect(Tv.accent, cornerRadius = r)
                drawRoundRect(if (focused) Color(0xFFC4D6FF) else Color(0x40FFFFFF), size = Size(size.width * fill.value, size.height), cornerRadius = r)
            }
            .padding(start = 16.dp, end = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.PlayArrow, null, tint = if (focused) Tv.onFocus else Tv.text, modifier = Modifier.size(Tv.icon))
        Spacer(Modifier.width(8.dp))
        Text(text, color = if (focused) Tv.onFocus else Tv.text, fontSize = Tv.bodySmall, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}
