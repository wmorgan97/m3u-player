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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarViewWeek
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Schedule
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
// window of programmes across, starting at NOW (rounded down to 5 minutes): nothing already over is drawn, as in TiviMate. The grid is ONE focus target that moves a highlight itself (no focusable cells), and
// only the programmes inside the window are drawn, so a move redraws two rows instead of hundreds of cells.
// OK: on now = watch; finished with catch-up = play it from the archive; anything else = details (remind me, etc.).

private val ROW_H = 40.dp   // TiviMate-dense: ~13 channels on screen
private val CHAN_W = 200.dp   // (room for most channel names next to the logo)
private const val SPAN_MIN = 120
private const val HALF_HOUR = GUIDE_HALF_HOUR
private fun at(l: List<Programme>, t: Long): Programme? { val i = l.indexOfLast { it.start <= t }; return if (i >= 0 && l[i].end > t) l[i] else null }

@Composable
fun GuideScreen(act: TvActivity, startGroup: String?) {
    val live by produceState<Repo.Live?>(Repo.live, Repo.version, Repo.liveTick) {
        // A failed or interrupted load keeps what was there (it used to put up an empty list: "No live channels" while
        // the playlists were still loading after a cold start).
        value = try { Repo.liveChannels() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { value ?: Repo.Live(emptyList(), emptyList(), emptyList()) }
    }
    val l = live
    if (l == null || Repo.liveSettling(l)) { Box(Modifier.fillMaxSize()) { PageTitle("TV Guide", "Loading channels…") }; return }
    if (l.channels.isEmpty()) { Repo.liveError?.let { ErrorState("Couldn't load your channels", "$it Check the playlist in Settings, or try again later.") } ?: EmptyState(Icons.Default.CalendarViewWeek, "No live channels", "Add a playlist with live TV in Settings"); return }
    LaunchedEffect(Unit) { Repo.loadSavedGuide() }
    val v = Repo.version
    val favChans = remember(v, l) { Repo.favouriteChannels(l) }   // the same set as Live TV's (Favourites.kt)
    val favUrls = remember(favChans) { favChans.mapTo(HashSet()) { it.url } }
    val hiddenCh = remember(v) { Repo.hiddenChannels }
    val byGroup = remember(l) { l.channels.groupBy { it.group } }
    val groups = remember(l, favUrls, v) { listOfNotNull(if (favChans.isNotEmpty()) "★ Favourites" else null) + Repo.arrange(l.groups.filter { it.isNotBlank() }) }
    var group by rememberSaveable { mutableStateOf(startGroup?.takeIf { it in groups } ?: groups.firstOrNull() ?: "") }
    var unlockTick by remember { mutableIntStateOf(0) }
    val locked = remember(group, v, unlockTick) { Repo.isLocked(group) }
    // A group (favourited or not) can hold the same channel twice - merged from more than one playlist, or a stream
    // and its backup both named the same: one row per URL, the first kept, same as CardRow's own duplicate-key rule.
    val chans = remember(group, l, favUrls, hiddenCh) { ((if (group == "★ Favourites") favChans else byGroup[group] ?: emptyList()).distinctBy { it.url }.filter { it.url !in hiddenCh }) }   // every channel of the group (rows are lazy); a 600 cap cut big groups like "24/7 Channels" (2,600+) short

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = System.currentTimeMillis() } }
    LaunchedEffect(chans) { while (true) { delay(30 * 60_000L); if (chans.isNotEmpty()) Repo.requestGuide(chans.take(300)) } }   // keeps the guide current while it's open
    val tick = Repo.guideVersion

    // Where the highlight is: a row, and a moment in time (the programme under it is the one picked).
    var row by remember(group) { mutableIntStateOf(0) }
    var cursor by remember(group) { mutableLongStateOf(System.currentTimeMillis()) }
    var viewStartRaw by remember(group) { mutableLongStateOf(guideStart(System.currentTimeMillis())) }
    LaunchedEffect(now) { if (cursor < now) cursor = now }   // (never rest on a programme that has finished: it is no longer drawn)
    LaunchedEffect(group, locked, row / 100) { if (!locked) Repo.requestGuide(chans.drop(maxOf(0, row - 50)).take(300)) }   // (rows further down get theirs as you reach them)
    val anyArchive = remember(chans) { chans.any { it.hasCatchup } }
    val earliest = guideStart(now)   // TiviMate: the guide starts at what's on now and only looks ahead (catch-up lives in the programme menu)
    val viewStart = maxOf(viewStartRaw, earliest)   // (at the start, the window follows the clock in 5-minute steps)
    fun setViewStart(t: Long) { viewStartRaw = t }
    val latest = now + 24 * 3600_000L
    val span = SPAN_MIN * 60_000L
    val listState = rememberLazyListState(prefetchStrategy = remember { AheadPrefetch(rows = 4, nested = 0) })   // rows built before they scroll in
    val gridFocus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val chipFocus = remember { FocusRequester() }   // the open group's chip
    val chipState = rememberLazyListState()
    var focusedChip by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(focusedChip) { val g = focusedChip ?: return@LaunchedEffect; if (g != group) { delay(350); group = g } }
    /** Up to the group chips from anywhere in the grid (one press, however far down the list you are). */
    fun toChips() = scope.launch {
        val i = groups.indexOf(group).takeIf { it >= 0 }?.plus(2) ?: 0   // (after "Now" and the divider)
        if (chipState.layoutInfo.visibleItemsInfo.none { it.index == i }) chipState.scrollToItem(maxOf(0, i - 1))
        delay(16); if (runCatching { chipFocus.requestFocus() }.isFailure) runCatching { chipFocus.requestFocus() }
    }
    var gridFocused by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf<Pair<Channel, Programme>?>(null) }
    var pinFor by remember { mutableStateOf<String?>(null) }

    fun progs(i: Int) = chans.getOrNull(i)?.let { Repo.guideFor(it) } ?: emptyList()
    /** Moves the highlight onto a programme, shifting the window (by whole half hours) only when it's out of sight. */
    fun pick(p: Programme) {
        if (p.start >= viewStart + span) setViewStart(floorHalfHour(p.start))
        else if (p.end <= viewStart) setViewStart(maxOf(earliest, floorHalfHour(maxOf(p.start, p.end - span))))
        cursor = maxOf(p.start, viewStartRaw, earliest)   // (viewStart is this composition's value: read the new one)
    }
    /** Back to the start: the window begins at now and the highlight is on what's on now. */
    fun jumpToNow() { val t = System.currentTimeMillis(); now = t; setViewStart(guideStart(t)); cursor = t }
    /** A whole window (two hours) later or earlier; the highlight lands on the programme at the window's start. */
    fun page(pages: Int) { val t = guidePage(viewStart, pages, span, earliest, latest); setViewStart(t); cursor = if (t <= earliest) maxOf(now, t) else t }
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
                else if (viewStart + span < latest) { val t = nextHalfHour(viewStart); setViewStart(t); cursor = maxOf(cursor, t) }
            }
            Key.DirectionLeft -> {
                // The first cell of a row is the programme on now (nothing earlier is drawn): Left from it opens the menu.
                val prev = if (cur != null) l0.lastOrNull { it.end <= cur.start } else null
                if (cur != null && cur.start < viewStart) {   // clipped on the left: the window slides back to where it starts
                    if (viewStart <= earliest) return false
                    val t = maxOf(earliest, floorHalfHour(cur.start)); setViewStart(t); cursor = maxOf(cur.start, t)
                }
                // (a programme right next to this one; across a gap in the listings it steps back to the edge instead)
                else if (prev != null && prev.end > now && prev.end >= cur!!.start - HALF_HOUR) pick(prev)
                else if (viewStart <= earliest) { if ((cur?.start ?: cursor) <= now) return false else cursor = viewStart }
                else { val t = prevHalfHour(viewStart, earliest); setViewStart(t); cursor = t }
            }
            Key.DirectionUp -> { if (row == 0) return false; row--; keepRowVisible() }
            Key.DirectionDown -> { if (row >= chans.size - 1) return true; row++; keepRowVisible() }
            Key.PageDown, Key.ChannelDown -> { row = minOf(chans.size - 1, row + 8); keepRowVisible() }
            Key.PageUp, Key.ChannelUp -> { row = maxOf(0, row - 8); keepRowVisible() }
            // Fast sideways: fast-forward / rewind turn a whole page (two hours); the guide button comes back to now.
            Key.MediaFastForward, Key.MediaSkipForward, Key.MediaNext -> page(1)
            Key.MediaRewind, Key.MediaSkipBackward, Key.MediaPrevious -> page(-1)
            Key.Guide, Key.MoveHome -> jumpToNow()
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
        Row(Modifier.fillMaxWidth().height(LiveTvTokens.headerH).zIndex(1f)) {
        if (!locked && Repo.preview) PreviewBox(pc, act.resumeTick, Modifier.zIndex(1f).padding(start = Tv.margin, top = Tv.topMargin - Tv.s2, bottom = Tv.s3).fillMaxHeight().aspectRatio(16 / 9f))
        Column(Modifier.weight(1f).fillMaxHeight().padding(start = if (Repo.preview) 24.dp else Tv.margin, end = Tv.margin, top = Tv.topMargin).clearAndSetSemantics { }) {
            if (locked || selC == null) {
                Text("TV Guide", fontSize = Tv.title, fontWeight = FontWeight.Bold, color = Tv.text)
                Text(java.text.SimpleDateFormat("EEEE d MMMM", java.util.Locale.UK).format(java.util.Date(now)), fontSize = Tv.bodySmall, color = Tv.muted)
            } else {
                Text(selP?.title ?: selC.name, fontSize = Tv.title, fontWeight = FontWeight.Bold, color = Tv.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val onAir = selP != null && selP.start <= now && selP.end > now
                val bits = listOfNotNull(selC.name, selP?.let { "${dayLabel(it.start, now)}${hhmm(it.start)}–${hhmm(it.end)}" }, selP?.let { "${(it.end - it.start) / 60_000} min" },
                    selP?.let { p -> when { onAir -> liveLeftLabel(p, now) ?: "On now"; p.end <= now && Repo.catchupUrl(selC, p.start, p.end) != null -> "Catch-up"; Repo.hasReminder(selC.url, p.start) -> "Reminder set"; else -> liveStartsIn(p, now) } })
                Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (onAir) { Box(Modifier.size(Tv.liveDot).background(Tv.live, CircleShape)); Spacer(Modifier.width(8.dp)) }   // (red only as a dot)
                    Text(bits.joinToString("  ·  "), fontSize = Tv.bodySmall, color = Tv.text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (onAir) ProgressLine(liveFraction(selP!!, now), Modifier.padding(start = 14.dp).width(120.dp), 3.dp)
                }
                Text(selP?.desc?.ifBlank { null } ?: if (selP == null) "No programme information" else "", fontSize = Tv.caption, color = Tv.muted, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = Tv.captionLine, modifier = Modifier.padding(top = 4.dp))
            }
        }
        }
        LazyRow(state = chipState, contentPadding = PaddingValues(horizontal = Tv.margin), horizontalArrangement = Arrangement.spacedBy(Tv.s2), verticalAlignment = Alignment.CenterVertically) {
            // "Now": back to the present from anywhere in the guide (also the guide button, or Back once you've moved away).
            item(key = "\u0000now") {
                TvChip("Now", selected = false, onClick = { jumpToNow(); scope.launch { delay(60); runCatching { gridFocus.requestFocus() } } }, icon = Icons.Default.Schedule)
            }
            item(key = "\u0000sep") { Box(Modifier.padding(horizontal = 4.dp).width(1.dp).height(20.dp).background(Tv.line)) }
            // Group tabs: the shared chip (the open group in accent, the highlight white).
            // Resting on a chip switches to its group (after a short settle, as in Live TV's group list); OK goes down into it.
            items(groups, key = { it }) { g ->
                TvChip((if (g in Repo.lockedGroups && Repo.pin.isNotEmpty()) "🔒 " else "") + prettyGroup(g), g == group,
                    { focusedChip = null; if (g != group) group = g else if (!locked) runCatching { gridFocus.requestFocus() } },
                    modifier = if (g == group) Modifier.focusRequester(chipFocus) else Modifier, onFocus = { if (it) focusedChip = g else if (focusedChip == g) focusedChip = null })
            }
        }
        Spacer(Modifier.height(Tv.s2))
        if (locked) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("This group is locked", fontSize = Tv.body, color = Tv.text, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(12.dp))
                    TvButton("Enter PIN", { pinFor = group }, icon = Icons.Default.Lock, style = TvButtonStyle.Primary)
                }
            }
        } else if (chans.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(bottom = 80.dp)) { EmptyState(Icons.Default.CalendarViewWeek, "No channels in this group", "Pick another group above") }
        } else BoxWithConstraints(Modifier.fillMaxSize().padding(start = Tv.margin, end = Tv.margin)) {
            val gridW = maxWidth - CHAN_W
            val perMin = gridW / SPAN_MIN.toFloat()
            Column(Modifier.fillMaxSize()) {
                // Time ruler: "Now" at the left edge (the window starts there) and a label with a short tick at each half hour after it.
                GuideRuler(viewStart, now, perMin, atNow = viewStart <= earliest)
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
                }
            }
        }
    }
    // Back: away from now, it first comes back to the present; at now it opens the menu (TvActivity), like every page.
    // (It used to send the preview full screen, which made the Guide feel like a page you couldn't leave; OK on the
    // previewed channel does that.)
    // At now, Back first goes up to the group chips (TiviMate-like: switching group is one press away from anywhere in a
    // long group); Back on the chips opens the menu.
    androidx.activity.compose.BackHandler(enabled = gridFocused && viewStart > earliest && info == null && pinFor == null) { jumpToNow() }
    androidx.activity.compose.BackHandler(enabled = gridFocused && viewStart <= earliest && info == null && pinFor == null) { toChips() }
    // The grid takes the highlight when the page (or a group) opens, except while you're moving along the group chips:
    // a group picked there keeps the highlight on the chips, so you can carry on; OK / Down goes into the grid.
    LaunchedEffect(locked, chans) { if (!locked && chans.isNotEmpty()) { delay(120); if (focusedChip == null) runCatching { gridFocus.requestFocus() } } }
    info?.let { (c, p) ->
        ProgrammeDialog(act, c, p, now, chans) { info = null }
    }
    pinFor?.let { g -> PinDialog("Enter PIN to open ${prettyGroup(g)}", verify = Repo::checkPin) { pin -> pinFor = null; if (pin != null) { Repo.unlocked += g; unlockTick++ } } }
}

internal fun dayLabel(t: Long, now: Long): String {
    val a = java.util.Calendar.getInstance().apply { timeInMillis = t }; val b = java.util.Calendar.getInstance().apply { timeInMillis = now }
    val d = (a.get(java.util.Calendar.YEAR) - b.get(java.util.Calendar.YEAR)) * 400 + a.get(java.util.Calendar.DAY_OF_YEAR) - b.get(java.util.Calendar.DAY_OF_YEAR)
    return when (d) { 0 -> ""; 1 -> "Tomorrow "; -1 -> "Yesterday "; else -> java.text.SimpleDateFormat("EEE ", java.util.Locale.UK).format(java.util.Date(t)) }
}

/** The time ruler over the grid: "Now" at the left edge while the window starts at now, then a label with a hairline tick at each half hour. */
@Composable
private fun GuideRuler(viewStart: Long, now: Long, perMin: Dp, atNow: Boolean) {
    val ticks = remember(viewStart) { guideTicks(viewStart, SPAN_MIN * 60_000L) }
    Box(Modifier.fillMaxWidth().height(28.dp).padding(start = CHAN_W).clipToBounds()) {
        var prev = viewStart
        if (atNow) Text("Now", Modifier.padding(start = Tv.s2, bottom = 6.dp).align(Alignment.BottomStart), fontSize = Tv.label, lineHeight = Tv.labelLine, color = Tv.text, fontWeight = FontWeight.SemiBold)
        for (t in ticks) {
            val x = perMin * ((t - viewStart) / 60_000f)
            val crowded = atNow && t - viewStart < 9 * 60_000L   // (a tick right next to "Now": its label would collide)
            if (x + 52.dp > perMin * SPAN_MIN.toFloat()) break   // (a label would be clipped at the right edge)
            Box(Modifier.offset(x = x).align(Alignment.BottomStart).padding(bottom = 2.dp).width(1.dp).height(8.dp).background(Tv.lineStrong))
            if (!crowded) {
                val day = if (dayLabel(t, now) != dayLabel(prev, now) || (t == ticks.first() && !atNow)) dayLabel(t, now) else ""
                Text(day + hhmm(t), Modifier.offset(x = x).padding(start = Tv.s2, bottom = 6.dp).align(Alignment.BottomStart), fontSize = Tv.label, lineHeight = Tv.labelLine, color = Tv.text2, fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false)
            }
            prev = t
        }
    }
}

/** One channel's row: the channel on the left, then only what's still to finish inside the window, drawn as plain boxes. */
@Composable
private fun GuideRow(c: Channel, selected: Boolean, cursor: Long, viewStart: Long, span: Long, now: Long, tick: Int, perMin: Dp, fav: Boolean, inPreview: Boolean) {
    val all = remember(tick, c) { Repo.guideFor(c) }
    val end = viewStart + span
    val slots = remember(all, viewStart, now / 60_000L) { guideSlots(all, viewStart, end, now) }
    val sel = if (cursor >= 0) at(all, cursor) else null
    val placed = remember(slots, perMin) { guideLayout(slots, viewStart, perMin.value, perMin.value * SPAN_MIN) }   // (min width for the first cell, nothing too narrow to read)
    Row(Modifier.fillMaxWidth().height(ROW_H).padding(bottom = Tv.s1).clearAndSetSemantics { }, verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.width(CHAN_W).fillMaxHeight().padding(end = Tv.s1).background(if (cursor >= 0) Tv.surface3 else if (inPreview) Tv.accent.copy(alpha = .12f) else Tv.surface, Tv.shapeS).padding(horizontal = Tv.s2), verticalAlignment = Alignment.CenterVertically) {
            Text(if (inPreview) "▶" else if (Repo.showNumbers) "${c.num}" else "", fontSize = Tv.micro, color = if (inPreview) Tv.accent else Tv.muted, modifier = Modifier.width(if (Repo.showNumbers) 28.dp else 16.dp), maxLines = 1)
            LogoTile(c, Modifier.size(width = 40.dp, height = 24.dp))
            Spacer(Modifier.width(Tv.s2))
            Text(c.name, fontSize = Tv.caption, lineHeight = Tv.captionLine, color = if (inPreview) Tv.accent else Tv.text, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            if (fav) Text("★", fontSize = Tv.micro, lineHeight = Tv.microLine, color = Tv.star, modifier = Modifier.padding(start = Tv.s1), maxLines = 1)   // (its own slot: a long name never cuts it off)
        }
        Box(Modifier.fillMaxSize().clipToBounds()) {
            for (pl in placed) {
                val g = pl.slot; val p = g.p; val x = pl.x.dp; val w = pl.w.dp
                if (p == null) { Cell("No information", null, x, w, on = selected && sel == null, onAir = false, progress = null, quiet = true); continue }
                val onAir = p.start <= now
                Cell(p.title, if (onAir) liveLeftLabel(p, now) else "${hhmm(p.start)}–${hhmm(p.end)}", x, w,
                    on = selected && sel == p, onAir = onAir, progress = if (onAir) liveFraction(p, now) else null, quiet = false)
            }
        }
    }
}

@Composable
private fun Cell(title: String, sub: String?, x: Dp, w: Dp, on: Boolean, onAir: Boolean, progress: Float?, quiet: Boolean) {
    val bg = when { on -> Tv.focusSurface; quiet -> Tv.surface.copy(alpha = .6f); onAir -> Tv.accentDeep.copy(alpha = .5f); else -> Tv.surface2 }
    val fg = when { on -> Tv.onFocus; quiet -> Tv.muted; else -> Tv.text }
    // 4 dp between cells; text only where it fits (a cell under ~72 dp is just a block, never a "…" fragment).
    Box(Modifier.offset(x = x).width(w).fillMaxHeight().padding(end = Tv.s1).background(bg, Tv.shapeS)) {   // (white fill = the highlight; no extra border pass)
        if (w >= GUIDE_TEXT_MIN_DP.dp) Column(Modifier.fillMaxHeight().padding(horizontal = Tv.s2), verticalArrangement = Arrangement.Center) {
            Text(title, fontSize = Tv.caption, lineHeight = Tv.captionLine, fontWeight = if (on || onAir) FontWeight.SemiBold else FontWeight.Normal, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sub != null && w >= GUIDE_SUB_MIN_DP.dp) Text(sub, fontSize = Tv.micro, lineHeight = Tv.microLine, color = if (on) Tv.onFocus.copy(alpha = .7f) else Tv.muted, maxLines = 1)
        }
        if (progress != null && !on) ProgressLine(progress, Modifier.align(Alignment.BottomStart).fillMaxWidth(), 2.dp)
    }
}
