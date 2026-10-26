package com.wmorgan97.m3uplayer.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarViewWeek
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Calendar

// The TV guide: channels down the left, time across the top, programme cells sized by their length. The remote
// moves cell to cell; the grid scrolls to keep the picked cell in view and a red line marks now. OK on what's on
// plays the channel; OK on anything else shows its details.

private val PX_PER_MIN = 6.dp          // 30 minutes = 180dp
private val ROW_H = 58.dp
private val CHAN_W = 200.dp
private const val HOURS_AHEAD = 24

@Composable
fun GuideScreen(act: TvActivity, startGroup: String?) {
    val live by produceState<Repo.Live?>(Repo.live, Repo.version) { value = runCatching { Repo.liveChannels() }.getOrNull() ?: Repo.Live(emptyList(), emptyList(), emptyList()) }
    val l = live ?: run { Box(Modifier.fillMaxSize()) { PageTitle("TV Guide", "Loading channels…") }; return }
    if (l.channels.isEmpty()) { EmptyState(Icons.Default.CalendarViewWeek, "No live channels", "Add a playlist with live TV in Settings"); return }
    val favUrls = remember(Repo.version) { Repo.favorites().map { it.optString("url") }.toSet() }
    val byGroup = remember(l) { l.channels.groupBy { it.group } }
    val groups = remember(l, favUrls) { listOfNotNull(if (favUrls.isNotEmpty()) "★ Favourites" else null) + l.groups.filter { it.isNotBlank() } }
    var group by rememberSaveable { mutableStateOf(startGroup?.takeIf { it in groups } ?: groups.firstOrNull() ?: "") }
    val chans = remember(group, l, favUrls) { (if (group == "★ Favourites") l.channels.filter { it.url in favUrls } else byGroup[group] ?: emptyList()).take(400) }

    // The day starts at the hour before now and runs 24 hours.
    val dayStart = remember { Calendar.getInstance().apply { add(Calendar.HOUR_OF_DAY, -1); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = System.currentTimeMillis() } }
    val tick = Repo.guideVersion
    LaunchedEffect(group) { Repo.requestGuide(chans) }
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    LaunchedEffect(group) { scroll.scrollTo(with(density) { (((now - dayStart) / 60_000f - 30) * PX_PER_MIN.value).dp.roundToPx() }.coerceAtLeast(0)) }
    var info by remember { mutableStateOf<Pair<Channel, Programme>?>(null) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = Tv.margin, end = Tv.margin, top = Tv.topMargin, bottom = 12.dp), verticalAlignment = Alignment.Bottom) {
            Text("TV Guide", fontSize = Tv.title, fontWeight = FontWeight.Bold, color = Tv.text)
            Spacer(Modifier.width(14.dp))
            Text(java.text.SimpleDateFormat("EEEE d MMMM", java.util.Locale.UK).format(java.util.Date(now)), fontSize = Tv.bodySmall, color = Tv.muted, modifier = Modifier.padding(bottom = 3.dp))
            Spacer(Modifier.weight(1f))
            Text("OK on a programme for details", fontSize = Tv.label, color = Tv.muted, modifier = Modifier.padding(bottom = 3.dp))
        }
        LazyRow(contentPadding = PaddingValues(horizontal = Tv.margin), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(groups, key = { it }) { g -> FilterChip(selected = g == group, onClick = { group = g }) { Text(prettyGroup(g), fontSize = Tv.caption, maxLines = 1) } }
        }
        Spacer(Modifier.height(12.dp))
        // Time header
        Row(Modifier.fillMaxWidth().padding(start = Tv.margin)) {
            Spacer(Modifier.width(CHAN_W))
            Row(Modifier.horizontalScroll(scroll, enabled = false)) {
                repeat(HOURS_AHEAD * 2) { i ->
                    Box(Modifier.width(PX_PER_MIN * 30).height(26.dp)) {
                        Text(hhmm(dayStart + i * 30 * 60_000L), Modifier.align(Alignment.CenterStart).padding(start = 6.dp), fontSize = Tv.label, color = if (i % 2 == 0) Tv.text2 else Tv.muted, fontWeight = FontWeight.Medium)
                        Box(Modifier.align(Alignment.BottomStart).width(1.dp).height(8.dp).background(Tv.line))
                    }
                }
            }
        }
        Box(Modifier.fillMaxSize()) {
            LazyColumn(Modifier.fillMaxSize().padding(start = Tv.margin), contentPadding = PaddingValues(bottom = 40.dp)) {
                itemsIndexed(chans, key = { _, c -> c.url }) { _, c -> GuideRow(act, c, dayStart, now, tick, scroll, c.url in favUrls) { info = c to it } }
            }
            // Now line, drawn over the grid in the scrolled position
            val nowX = with(density) { (((now - dayStart) / 60_000f) * PX_PER_MIN.value).dp.roundToPx() } - scroll.value
            if (nowX >= 0) Box(Modifier.padding(start = Tv.margin + CHAN_W).offset(x = with(density) { nowX.toDp() }).width(2.dp).fillMaxHeight().background(Tv.live.copy(alpha = .9f)))
        }
    }
    info?.let { (c, p) ->
        ProgrammeDialog(c, p, now, onWatch = { info = null; act.playLive(chans, chans.indexOf(c).coerceAtLeast(0)) }) { info = null }
    }
}

@Composable
private fun GuideRow(act: TvActivity, c: Channel, dayStart: Long, now: Long, tick: Int, scroll: androidx.compose.foundation.ScrollState, fav: Boolean, onInfo: (Programme) -> Unit) {
    val progs = remember(tick, c) { Repo.guideFor(c).filter { it.end > dayStart && it.start < dayStart + HOURS_AHEAD * 3600_000L } }
    val scope = rememberCoroutineScope()
    Row(Modifier.fillMaxWidth().height(ROW_H).padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.width(CHAN_W).fillMaxHeight().padding(end = 8.dp).background(Tv.surface, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            LogoTile(c, Modifier.size(width = 54.dp, height = 32.dp))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(c.name, fontSize = Tv.caption, color = Tv.text, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                Text("${c.num}${if (fav) "  ★" else ""}", fontSize = 11.sp, color = Tv.muted)
            }
        }
        Row(Modifier.horizontalScroll(scroll, enabled = false).fillMaxHeight()) {
            if (progs.isEmpty()) {
                Cell("No programme information", Modifier.width(PX_PER_MIN * 240), muted = true, onClick = { act.playLive(listOf(c), 0) })
            } else {
                var cursor = dayStart
                for (p in progs) {
                    val start = maxOf(p.start, dayStart)
                    if (start > cursor) { Spacer(Modifier.width(PX_PER_MIN * ((start - cursor) / 60_000f))); }
                    val w = PX_PER_MIN * ((minOf(p.end, dayStart + HOURS_AHEAD * 3600_000L) - start) / 60_000f)
                    val onAir = p.start <= now && p.end > now
                    val past = p.end <= now
                    Cell("${p.title}", Modifier.width(w), sub = "${hhmm(p.start)}–${hhmm(p.end)}", onAir = onAir, past = past,
                        progress = if (onAir) ((now - p.start).toFloat() / (p.end - p.start)) else null, scroll = scroll,
                        startMin = ((start - dayStart) / 60_000f), widthMin = ((minOf(p.end, dayStart + HOURS_AHEAD * 3600_000L) - start) / 60_000f),
                        onClick = { if (onAir) act.playLive(listOf(c), 0) else onInfo(p) })
                    cursor = maxOf(cursor, p.end)
                }
            }
        }
    }
}

/** One programme in the grid. Picked: a white frame; on air: a tinted cell with a progress line. */
@Composable
private fun Cell(title: String, modifier: Modifier, sub: String? = null, onAir: Boolean = false, past: Boolean = false, muted: Boolean = false, progress: Float? = null,
                 scroll: androidx.compose.foundation.ScrollState? = null, startMin: Float = 0f, widthMin: Float = 0f, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    // A long programme that began off the left edge keeps its name in view: the text slides along with the scroll.
    val density = LocalDensity.current
    val slide = if (scroll == null || widthMin <= 0f) 0.dp else with(density) {
        val startPx = (startMin * PX_PER_MIN.value).dp.roundToPx(); val widthPx = (widthMin * PX_PER_MIN.value).dp.roundToPx()
        (scroll.value - startPx).coerceIn(0, maxOf(0, widthPx - 140.dp.roundToPx())).toDp()
    }
    val bring = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    Box(modifier.fillMaxHeight().padding(end = 3.dp)) {
        Surface(onClick = onClick, shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(6.dp)),
            colors = ClickableSurfaceDefaults.colors(containerColor = when { onAir -> Tv.accentDeep.copy(alpha = .45f); past -> Tv.surface.copy(alpha = .6f); else -> Tv.surface2 },
                focusedContainerColor = if (onAir) Tv.accentDeep.copy(alpha = .75f) else Tv.surface3, contentColor = Tv.text, focusedContentColor = Tv.text),
            border = ClickableSurfaceDefaults.border(focusedBorder = Border(androidx.compose.foundation.BorderStroke(3.dp, Tv.focusRing), shape = RoundedCornerShape(6.dp))),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
            modifier = Modifier.fillMaxSize().bringIntoViewRequester(bring).onFocusChanged { focused = it.isFocused; if (it.isFocused) scope.launch { bring.bringIntoView() } }) {
            Box(Modifier.fillMaxSize()) {
                Column(Modifier.padding(start = 10.dp + slide, end = 8.dp, top = 7.dp)) {
                    Text(title, fontSize = Tv.caption, fontWeight = if (onAir || focused) FontWeight.SemiBold else FontWeight.Normal, color = if (muted || (past && !focused)) Tv.muted else Tv.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (sub != null) Text(sub, fontSize = 11.sp, color = Tv.muted, maxLines = 1)
                }
                if (progress != null) ProgressLine(progress, Modifier.align(Alignment.BottomStart).fillMaxWidth(), 3.dp)
            }
        }
    }
}

@Composable
private fun ProgrammeDialog(c: Channel, p: Programme, now: Long, onWatch: () -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.width(560.dp).background(Tv.surface, Tv.bigShape).border(1.dp, Tv.line, Tv.bigShape).padding(24.dp)) {
            Text(c.name, fontSize = Tv.label, color = Tv.muted, fontWeight = FontWeight.SemiBold)
            Text(p.title, fontSize = Tv.title, fontWeight = FontWeight.Bold, color = Tv.text, maxLines = 2, modifier = Modifier.padding(top = 2.dp))
            Text("${hhmm(p.start)}–${hhmm(p.end)}  ·  ${(p.end - p.start) / 60_000} min" + (if (p.start <= now && p.end > now) "  ·  On now" else if (p.end <= now) "  ·  Finished" else ""), fontSize = Tv.bodySmall, color = Tv.text2, modifier = Modifier.padding(top = 6.dp))
            if (p.desc.isNotBlank()) Text(p.desc, fontSize = Tv.bodySmall, color = Tv.text2, lineHeight = 20.sp, maxLines = 6, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 12.dp))
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onWatch) { Text(if (p.start <= now && p.end > now) "Watch now" else "Watch channel", fontSize = Tv.bodySmall) }
                OutlinedButton(onClick = onDismiss) { Text("Close", fontSize = Tv.bodySmall) }
            }
        }
    }
}
