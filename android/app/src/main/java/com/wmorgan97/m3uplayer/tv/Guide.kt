package com.wmorgan97.m3uplayer.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarViewWeek
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.*
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// The TV guide, TiviMate-style: what's picked is described at the top, channels run down the left and a two-hour
// window of programmes across. The grid is ONE focus target that moves a highlight itself (no focusable cells), and
// only the programmes inside the window are drawn, so a move redraws two rows instead of hundreds of cells.
// OK: on now = watch; finished with catch-up = play it from the archive; anything else = details (remind me, etc.).

private val ROW_H = 40.dp   // TiviMate-dense: ~13 channels on screen
private val CHAN_W = 180.dp
private const val SPAN_MIN = 120
private const val HALF_HOUR = 30 * 60_000L

private fun floor30(t: Long): Long { val c = java.util.Calendar.getInstance().apply { timeInMillis = t; set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0) }; c.set(java.util.Calendar.MINUTE, c.get(java.util.Calendar.MINUTE) / 30 * 30); return c.timeInMillis }
private fun at(l: List<Programme>, t: Long): Programme? { val i = l.indexOfLast { it.start <= t }; return if (i >= 0 && l[i].end > t) l[i] else null }

@Composable
fun GuideScreen(act: TvActivity, startGroup: String?) {
    val live by produceState<Repo.Live?>(Repo.live, Repo.version) { value = runCatching { Repo.liveChannels() }.getOrNull() ?: Repo.Live(emptyList(), emptyList(), emptyList()) }
    val l = live ?: run { Box(Modifier.fillMaxSize()) { PageTitle("TV Guide", "Loading channels…") }; return }
    if (l.channels.isEmpty() && Repo.syncData.optJSONObject("sources") == null && Repo.lastSyncError == null) { Box(Modifier.fillMaxSize()) { PageTitle("TV Guide", "Loading channels…") }; return }
    if (l.channels.isEmpty()) { Repo.liveError?.let { EmptyState(Icons.Default.CloudOff, "Couldn't load your channels", "$it Check the playlist in Settings, or try again later.") } ?: EmptyState(Icons.Default.CalendarViewWeek, "No live channels", "Add a playlist with live TV in Settings"); return }
    LaunchedEffect(Unit) { Repo.loadSavedGuide() }
    val v = Repo.version
    val favUrls = remember(v) { Repo.favorites().map { it.optString("url") }.toSet() }
    val hiddenCh = remember(v) { Repo.hiddenChannels }
    val byGroup = remember(l) { l.channels.groupBy { it.group } }
    val groups = remember(l, favUrls, v) { listOfNotNull(if (l.channels.any { it.url in favUrls }) "★ Favourites" else null) + Repo.arrange(l.groups.filter { it.isNotBlank() }) }
    var group by rememberSaveable { mutableStateOf(startGroup?.takeIf { it in groups } ?: groups.firstOrNull() ?: "") }
    var unlockTick by remember { mutableIntStateOf(0) }
    val locked = remember(group, v, unlockTick) { Repo.isLocked(group) }
    val chans = remember(group, l, favUrls, hiddenCh) { (if (group == "★ Favourites") l.channels.filter { it.url in favUrls } else byGroup[group] ?: emptyList()).filter { it.url !in hiddenCh } }   // every channel of the group (rows are lazy); a 600 cap cut big groups like "24/7 Channels" (2,600+) short

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = System.currentTimeMillis() } }
    LaunchedEffect(chans) { while (true) { delay(30 * 60_000L); if (chans.isNotEmpty()) Repo.requestGuide(chans.take(300)) } }   // keeps the guide current while it's open
    val tick = Repo.guideVersion

    // Where the highlight is: a row, and a moment in time (the programme under it is the one picked).
    var row by remember(group) { mutableIntStateOf(0) }
    var cursor by remember(group) { mutableLongStateOf(System.currentTimeMillis()) }
    var viewStart by remember(group) { mutableLongStateOf(floor30(System.currentTimeMillis())) }
    LaunchedEffect(group, locked, row / 100) { if (!locked) Repo.requestGuide(chans.drop(maxOf(0, row - 50)).take(300)) }   // (rows further down get theirs as you reach them)
    val anyArchive = remember(chans) { chans.any { it.hasCatchup } }
    val earliest = floor30(now) - (if (anyArchive) 24 * 3600_000L else 0L)   // the past is only worth visiting with catch-up; otherwise left from now opens the menu
    val latest = now + 24 * 3600_000L
    val span = SPAN_MIN * 60_000L
    val listState = rememberLazyListState(prefetchStrategy = remember { AheadPrefetch(rows = 4, nested = 0) })   // rows built before they scroll in
    val gridFocus = remember { FocusRequester() }
    var gridFocused by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf<Pair<Channel, Programme>?>(null) }
    var pinFor by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun progs(i: Int) = chans.getOrNull(i)?.let { Repo.guideFor(it) } ?: emptyList()
    /** Moves the highlight onto a programme, shifting the window (by whole half hours) only when it's out of sight. */
    fun pick(p: Programme) {
        if (p.start >= viewStart + span) viewStart = floor30(p.start)
        else if (p.end <= viewStart) viewStart = maxOf(earliest, floor30(maxOf(p.start, p.end - span)))
        cursor = maxOf(p.start, viewStart)
    }
    fun keepRowVisible() = scope.launch {
        val vis = listState.layoutInfo.visibleItemsInfo
        val first = vis.firstOrNull()?.index ?: 0; val last = (vis.lastOrNull()?.index ?: 0) - 1
        if (row < first) listState.scrollToItem(row) else if (row > last && vis.isNotEmpty()) listState.scrollToItem(maxOf(0, row - (vis.size - 2)))
    }
    fun ok(hold: Boolean) {
        val c = chans.getOrNull(row) ?: return
        val p = at(progs(row), cursor)
        // TiviMate: the first OK puts a channel in the preview, OK again (on the one in the preview) goes full screen.
        fun watch() { if (Repo.preview && Preview.channel?.url != c.url) Preview.select(c, chans) else act.playLive(chans, row) }
        if (p == null) { if (!hold) watch(); return }
        val onAir = p.start <= now && p.end > now
        when {
            hold -> info = c to p
            onAir -> watch()
            p.end <= now && Repo.catchupUrl(c, p.start, p.end) != null -> act.playCatchup(c, p)
            else -> info = c to p
        }
    }
    fun move(k: Key): Boolean {
        val l0 = progs(row); val cur = at(l0, cursor)
        when (k) {
            Key.DirectionRight -> {
                val next = if (cur != null) l0.firstOrNull { it.start >= cur.end } else l0.firstOrNull { it.start > cursor }
                if (next != null && next.start < latest && next.start <= (cur?.end ?: cursor) + HALF_HOUR) pick(next)
                else if (viewStart + span < latest) { viewStart += HALF_HOUR; cursor = maxOf(cursor, viewStart) }
            }
            Key.DirectionLeft -> {
                if (cur != null && cur.start < viewStart) { if (viewStart <= earliest) return false; viewStart -= HALF_HOUR; cursor = maxOf(cur.start, viewStart) }
                else {
                    val prev = if (cur != null) l0.lastOrNull { it.end <= cur.start } else l0.lastOrNull { it.end <= cursor }
                    // (a programme right next to this one; across a gap in the listings it steps half an hour instead)
                    if (prev != null && prev.end > earliest && prev.end >= (cur?.start ?: cursor) - HALF_HOUR) pick(prev)
                    else if (viewStart > earliest) { viewStart -= HALF_HOUR; cursor = viewStart }
                    else return false   // the start of the guide: the menu opens
                }
            }
            Key.DirectionUp -> { if (row == 0) return false; row--; keepRowVisible() }
            Key.DirectionDown -> { if (row >= chans.size - 1) return true; row++; keepRowVisible() }
            Key.PageDown, Key.ChannelDown -> { row = minOf(chans.size - 1, row + 8); keepRowVisible() }
            Key.PageUp, Key.ChannelUp -> { row = maxOf(0, row - 8); keepRowVisible() }
            else -> return false
        }
        return true
    }

    Column(Modifier.fillMaxSize()) {
        // What's picked, described at the top (TiviMate's info area).
        val selC = chans.getOrNull(row)
        val selP = remember(row, cursor, tick, chans) { at(progs(row), cursor) }
        LaunchedEffect(l) { Preview.current() }
        val pc = Preview.channel
        Row(Modifier.fillMaxWidth().height(150.dp).zIndex(1f)) {
        if (!locked && Repo.preview) PreviewBox(pc, act.resumeTick, Modifier.zIndex(1f).padding(start = Tv.margin, top = Tv.topMargin - 8.dp, bottom = 10.dp).fillMaxHeight().aspectRatio(16 / 9f))
        Column(Modifier.weight(1f).fillMaxHeight().padding(start = if (Repo.preview) 24.dp else Tv.margin, end = Tv.margin + 80.dp, top = Tv.topMargin).clearAndSetSemantics { }) {
            if (locked || selC == null) {
                Text("TV Guide", fontSize = Tv.title, fontWeight = FontWeight.Bold, color = Tv.text)
                Text(java.text.SimpleDateFormat("EEEE d MMMM", java.util.Locale.UK).format(java.util.Date(now)), fontSize = Tv.bodySmall, color = Tv.muted)
            } else {
                Text(selP?.title ?: selC.name, fontSize = Tv.title, fontWeight = FontWeight.Bold, color = Tv.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val bits = listOfNotNull(selC.name, selP?.let { "${dayLabel(it.start, now)}${hhmm(it.start)}–${hhmm(it.end)}" }, selP?.let { "${(it.end - it.start) / 60_000} min" },
                    selP?.let { p -> when { p.start <= now && p.end > now -> "On now"; p.end <= now && Repo.catchupUrl(selC, p.start, p.end) != null -> "Catch-up"; Repo.hasReminder(selC.url, p.start) -> "Reminder set"; else -> null } })
                Text(bits.joinToString("  ·  "), fontSize = Tv.bodySmall, color = Tv.text2, maxLines = 1, modifier = Modifier.padding(top = 2.dp))
                Text(selP?.desc?.ifBlank { null } ?: if (selP == null) "No programme information" else "", fontSize = Tv.caption, color = Tv.muted, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = Tv.captionLine, modifier = Modifier.padding(top = 4.dp))
            }
        }
        }
        LazyRow(contentPadding = PaddingValues(horizontal = Tv.margin), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Slim group tabs (30dp): the open group in blue, the highlight white.
            items(groups, key = { it }) { g ->
                Surface(onClick = { group = g }, modifier = Modifier.height(30.dp), shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(15.dp)), scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                    colors = ClickableSurfaceDefaults.colors(containerColor = if (g == group) Tv.accent.copy(alpha = .16f) else Tv.surface, focusedContainerColor = Tv.text,
                        contentColor = if (g == group) Tv.accent else Tv.text2, focusedContentColor = Tv.bg)) {
                    Box(Modifier.fillMaxHeight().padding(horizontal = 14.dp).clearAndSetSemantics { }, contentAlignment = Alignment.Center) {
                        Text((if (g in Repo.lockedGroups && Repo.pin.isNotEmpty()) "🔒 " else "") + prettyGroup(g), fontSize = Tv.caption, fontWeight = if (g == group) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        if (locked) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("This group is locked", fontSize = Tv.body, color = Tv.text, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { pinFor = group }) { Text("Enter PIN", fontSize = Tv.bodySmall) }
                }
            }
        } else if (chans.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(bottom = 80.dp)) { EmptyState(Icons.Default.CalendarViewWeek, "No channels in this group", "Pick another group above") }
        } else BoxWithConstraints(Modifier.fillMaxSize().padding(start = Tv.margin, end = Tv.margin - 16.dp)) {
            val gridW = maxWidth - CHAN_W
            val perMin = gridW / SPAN_MIN.toFloat()
            Column(Modifier.fillMaxSize()) {
                // Time header: four half-hour marks across the window.
                Box(Modifier.fillMaxWidth().height(22.dp).padding(start = CHAN_W).clipToBounds()) {
                    for (k in 0 until SPAN_MIN / 30) {
                        val t = viewStart + k * HALF_HOUR
                        Text((if (k == 0) dayLabel(t, now) else "") + hhmm(t), Modifier.offset(x = perMin * (k * 30f)).padding(start = 6.dp).align(Alignment.CenterStart), fontSize = Tv.label, color = Tv.text2, fontWeight = FontWeight.Medium)
                    }
                }
                Box(Modifier.fillMaxSize()) {
                    LazyColumn(Modifier.fillMaxSize().clearAndSetSemantics { }.focusRequester(gridFocus).onFocusChanged { gridFocused = it.isFocused }
                        .onPreviewKeyEvent { e ->
                            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            move(e.key)
                        }
                        .holdOk { ok(hold = true) }
                        .onKeyEvent { e -> if (e.type == KeyEventType.KeyUp && (e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter)) { ok(hold = false); true } else false }
                        .focusable(), state = listState, userScrollEnabled = false, contentPadding = PaddingValues(bottom = 24.dp)) {
                        itemsIndexed(chans, key = { i, c -> "$i|${c.url}" }) { i, c ->
                            GuideRow(c, i == row && gridFocused, if (i == row) cursor else -1L, viewStart, span, now, tick, perMin, c.url in favUrls, c.url == Preview.channel?.url)
                        }
                    }
                    if (now in viewStart until viewStart + span)
                        Box(Modifier.padding(start = CHAN_W).offset(x = perMin * ((now - viewStart) / 60_000f)).width(2.dp).fillMaxHeight().background(Tv.live.copy(alpha = .9f)))
                }
            }
        }
    }
    // Back goes to the channel in the preview, full screen (the menu is one press of left away).
    androidx.activity.compose.BackHandler(enabled = Repo.preview && Preview.channel != null && info == null && pinFor == null) {
        val pl = Preview.list; val c = Preview.channel!!
        act.playLive(pl.ifEmpty { listOf(c) }, pl.indexOf(c).coerceAtLeast(0))
    }
    LaunchedEffect(locked, chans) { if (!locked && chans.isNotEmpty()) { delay(120); runCatching { gridFocus.requestFocus() } } }
    info?.let { (c, p) ->
        ProgrammeDialog(act, c, p, now, chans) { info = null }
    }
    pinFor?.let { g -> PinDialog("Enter PIN to open ${prettyGroup(g)}", verify = Repo::checkPin) { pin -> pinFor = null; if (pin != null) { Repo.unlocked += g; unlockTick++ } } }
}

private fun dayLabel(t: Long, now: Long): String {
    val a = java.util.Calendar.getInstance().apply { timeInMillis = t }; val b = java.util.Calendar.getInstance().apply { timeInMillis = now }
    val d = (a.get(java.util.Calendar.YEAR) - b.get(java.util.Calendar.YEAR)) * 400 + a.get(java.util.Calendar.DAY_OF_YEAR) - b.get(java.util.Calendar.DAY_OF_YEAR)
    return when (d) { 0 -> ""; 1 -> "Tomorrow "; -1 -> "Yesterday "; else -> java.text.SimpleDateFormat("EEE ", java.util.Locale.UK).format(java.util.Date(t)) }
}

/** One channel's row: the channel on the left, then only the programmes inside the window, drawn as plain boxes. */
@Composable
private fun GuideRow(c: Channel, selected: Boolean, cursor: Long, viewStart: Long, span: Long, now: Long, tick: Int, perMin: Dp, fav: Boolean, inPreview: Boolean) {
    val all = remember(tick, c) { Repo.guideFor(c) }
    val end = viewStart + span
    val shown = remember(all, viewStart) { all.filter { it.end > viewStart && it.start < end } }
    val sel = if (cursor >= 0) at(all, cursor) else null
    Row(Modifier.fillMaxWidth().height(ROW_H).padding(bottom = 2.dp).clearAndSetSemantics { }, verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.width(CHAN_W).fillMaxHeight().padding(end = 6.dp).background(if (cursor >= 0) Tv.surface3 else if (inPreview) Tv.accent.copy(alpha = .12f) else Tv.surface, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (inPreview) "▶" else if (Repo.showNumbers) "${c.num}" else "", fontSize = Tv.micro, color = if (inPreview) Tv.accent else Tv.muted, modifier = Modifier.width(if (Repo.showNumbers) 30.dp else 16.dp), maxLines = 1)
            LogoTile(c, Modifier.size(width = 36.dp, height = 22.dp))
            Spacer(Modifier.width(8.dp))
            Text(c.name + if (fav) " ★" else "", fontSize = Tv.caption, lineHeight = 16.sp, color = if (inPreview) Tv.accent else Tv.text, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        }
        Box(Modifier.fillMaxSize().clipToBounds()) {
            if (shown.isEmpty()) Cell("No programme information", null, 0.dp, perMin * SPAN_MIN.toFloat(), on = selected && cursor >= 0, past = false, onAir = false, progress = null, catchup = false)
            for (p in shown) {
                val s = maxOf(p.start, viewStart); val e = minOf(p.end, end)
                val onAir = p.start <= now && p.end > now
                Cell(p.title, "${hhmm(p.start)}–${hhmm(p.end)}", perMin * ((s - viewStart) / 60_000f), perMin * ((e - s) / 60_000f),
                    on = selected && sel == p, past = p.end <= now, onAir = onAir, progress = if (onAir) (now - p.start).toFloat() / (p.end - p.start) else null,
                    catchup = p.end <= now && c.hasCatchup && p.start > now - c.catchupDays * 86_400_000L)
            }
        }
    }
}

@Composable
private fun Cell(title: String, sub: String?, x: Dp, w: Dp, on: Boolean, past: Boolean, onAir: Boolean, progress: Float?, catchup: Boolean) {
    val shape = RoundedCornerShape(4.dp)
    val bg = when { on -> Tv.text; onAir -> Tv.accentDeep.copy(alpha = .5f); past -> Tv.surface.copy(alpha = .55f); else -> Tv.surface2 }
    val fg = when { on -> Tv.bg; past -> Tv.muted; else -> Tv.text }
    Box(Modifier.offset(x = x).width(w).fillMaxHeight().padding(end = 2.dp).background(bg, shape)) {   // (white fill = the highlight; no extra border pass)
        Column(Modifier.fillMaxHeight().padding(start = 8.dp, end = 4.dp), verticalArrangement = Arrangement.Center) {
            Text((if (catchup) "↺ " else "") + title, fontSize = Tv.caption, lineHeight = 16.sp, fontWeight = if (on || onAir) FontWeight.SemiBold else FontWeight.Normal, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sub != null && w > 70.dp) Text(sub, fontSize = Tv.micro, lineHeight = 14.sp, color = if (on) Tv.bg.copy(alpha = .7f) else Tv.muted, maxLines = 1)
        }
        if (progress != null && !on) ProgressLine(progress, Modifier.align(Alignment.BottomStart).fillMaxWidth(), 2.dp)
    }
}

/** A programme's details with what you can do: watch, watch from the start / play from the archive, remind me. */
@Composable
fun ProgrammeDialog(act: TvActivity, c: Channel, p: Programme, now: Long, chans: List<Channel>, onDismiss: () -> Unit) {
    val onAir = p.start <= now && p.end > now
    val archive = Repo.catchupUrl(c, p.start, p.end)
    val first = remember { FocusRequester() }
    var reminded by remember { mutableStateOf(Repo.hasReminder(c.url, p.start)) }
    val actions = buildList<Pair<String, () -> Unit>> {
        if (onAir) add("Watch now" to { onDismiss(); act.playLive(chans, chans.indexOf(c).coerceAtLeast(0)) })
        if (archive != null) add((if (onAir) "Watch from the start" else "Play (catch-up)") to { onDismiss(); act.playCatchup(c, p) })
        if (p.start > now) add((if (reminded) "Cancel reminder" else "Remind me") to { Repo.toggleReminder(c, p); reminded = !reminded })
        if (!onAir) add("Watch the channel now" to { onDismiss(); act.playLive(chans, chans.indexOf(c).coerceAtLeast(0)) })
        add("Close" to onDismiss)
    }
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.freshOkOnly().width(600.dp).background(Tv.surface, Tv.bigShape).border(1.dp, Tv.line, Tv.bigShape).padding(24.dp)) {
            if (!c.name.equals(p.title, true)) Text(c.name, fontSize = Tv.label, color = Tv.muted, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(p.title, fontSize = Tv.title, fontWeight = FontWeight.Bold, color = Tv.text, maxLines = 2, modifier = Modifier.padding(top = 2.dp))
            Text("${dayLabel(p.start, now)}${hhmm(p.start)}–${hhmm(p.end)}  ·  ${(p.end - p.start) / 60_000} min" + (if (onAir) "  ·  On now" else if (p.end <= now) "  ·  Finished" else "") + (if (reminded) "  ·  Reminder set" else ""),
                fontSize = Tv.bodySmall, color = Tv.text2, modifier = Modifier.padding(top = 6.dp))
            if (p.desc.isNotBlank()) Text(p.desc, fontSize = Tv.bodySmall, color = Tv.text2, lineHeight = Tv.bodySmallLine, maxLines = 6, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 12.dp))
            Spacer(Modifier.height(18.dp))
            // Wraps onto a second line rather than pushing a button out of the dialog.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                actions.forEachIndexed { i, (label, fn) ->
                    if (i == 0) Button(onClick = fn, modifier = Modifier.focusRequester(first)) { Text(label, fontSize = Tv.bodySmall, fontWeight = FontWeight.SemiBold, maxLines = 1) }
                    else OutlinedButton(onClick = fn) { Text(label, fontSize = Tv.bodySmall, fontWeight = FontWeight.SemiBold, maxLines = 1) }
                }
            }
        }
        LaunchedEffect(Unit) { delay(50); runCatching { first.requestFocus() } }
    }
}

/** Four-digit PIN pad (the Shield remote has no number keys, so it's on screen; number keys work too). null = cancelled. */
@Composable
fun PinDialog(title: String, verify: ((String) -> Boolean)? = null, onResult: (String?) -> Unit) {
    var pin by remember { mutableStateOf("") }
    var wrong by remember { mutableIntStateOf(0) }   // with [verify]: a wrong PIN says so and clears; 5 wrong tries closes
    val first = remember { FocusRequester() }
    fun press(d: String) {
        if (pin.length < 4) pin += d
        if (pin.length < 4) return
        val v = verify
        if (v == null || v(pin)) { onResult(pin); return }
        wrong++; pin = ""
        if (wrong >= 5) onResult(null)
    }
    Dialog(onDismissRequest = { onResult(null) }) {
        Column(Modifier.freshOkOnly().width(360.dp).background(Tv.surface, Tv.bigShape).border(1.dp, Tv.line, Tv.bigShape).padding(24.dp)
            .onPreviewKeyEvent { e ->
                val n = e.nativeKeyEvent.keyCode - android.view.KeyEvent.KEYCODE_0
                if (e.type == KeyEventType.KeyDown && n in 0..9) { press("$n"); true } else false
            }, horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (wrong > 0) "Wrong PIN, try again" else title, fontSize = Tv.body, fontWeight = FontWeight.SemiBold, color = if (wrong > 0) Tv.danger else Tv.text, maxLines = 2)
            if (wrong > 0) Text("${5 - wrong} tries left", fontSize = Tv.label, color = Tv.muted, modifier = Modifier.padding(top = 2.dp))
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) { repeat(4) { k -> Box(Modifier.size(16.dp).background(if (k < pin.length) Tv.text else Tv.surface3, RoundedCornerShape(8.dp))) } }
            Spacer(Modifier.height(18.dp))
            val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "⌫", "0", "✕")
            for (r in 0 until 4) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                for (k in keys.subList(r * 3, r * 3 + 3)) {
                    Surface(onClick = { when (k) { "⌫" -> pin = pin.dropLast(1); "✕" -> onResult(null); else -> press(k) } },
                        modifier = Modifier.size(width = 72.dp, height = 48.dp).let { if (k == "1") it.focusRequester(first) else it },
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                        colors = ClickableSurfaceDefaults.colors(containerColor = Tv.surface2, focusedContainerColor = Tv.text, contentColor = Tv.text, focusedContentColor = Tv.bg),
                        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(k, fontSize = Tv.body, fontWeight = FontWeight.SemiBold) }
                    }
                }
            }
        }
        LaunchedEffect(Unit) { delay(50); runCatching { first.requestFocus() } }
    }
}
