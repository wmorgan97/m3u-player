package com.wmorgan97.m3uplayer.tv

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.layout.layout
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import org.json.JSONObject

// Live TV: the group list, the channel list with the preview on top, and the channel cards / options shared with Search.

/** A row of channel cards with what's on now (favourites, recent). */
@Composable
fun ChannelRowCards(act: TvActivity, all: List<Channel>, tick: Int, keyPrefix: String? = null, onLong: (Channel) -> Unit) {
    val chans = remember(all) { all.distinctBy { it.url } }   // a playlist can list one stream twice; row keys must be unique
    CardRow(chans, { it.url }) { c, i ->
        val (now, _) = remember(tick, c) { Repo.nowNext(c) }
        val p = now?.let { ((System.currentTimeMillis() - it.start).toFloat() / (it.end - it.start)) }
        ChannelCard(c.name, c.logo, now?.title ?: prettyGroup(c.group), p, restoreKey = keyPrefix?.let { it + c.url }, onLong = { onLong(c) }) { act.playLive(chans, i) }
    }
}

@Composable
fun ChannelOptions(act: TvActivity, c: Channel, onRemove: (String) -> Unit = {}, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val fav = Repo.isFavourite(c.url)
    Options(c.name, listOf(
        "Watch" to { onDone(); act.playLive(listOf(c), 0) },
        (if (fav) "Remove from favourites" else "Add to favourites") to { onDone(); if (fav) onRemove("fav"); scope.launch { Repo.toggleFavourite(c); act.refresh++ } },
        "TV guide" to { onDone(); act.go(Screen.Guide(c.group)) },
        "Hide this channel" to { onDone(); onRemove("hide"); Repo.toggleHiddenChannel(c.url) },
    ), onDone)
}

fun liveFromSync(o: JSONObject): Channel = Repo.channelByUrl(o.optString("url"))
    ?: Channel(o.optString("name"), o.optString("url"), o.optString("group"), o.optString("logo"), o.optString("tvgId"), "", 0)

// ---------- Live TV ----------

@Composable
fun LiveScreen(act: TvActivity, startGroup: String? = null) {
    val live by produceState<Repo.Live?>(Repo.live, Repo.version) { value = runCatching { Repo.liveChannels() }.getOrNull() ?: Repo.Live(emptyList(), emptyList(), emptyList()) }
    val l = live
    if (l == null) { LiveSkeleton(); return }
    // Before the first sync arrives there are no playlists yet: that's loading, not "no channels".
    if (l.channels.isEmpty() && Repo.syncData.optJSONObject("sources") == null && Repo.lastSyncError == null) { LiveSkeleton(); return }
    if (l.channels.isEmpty()) { Repo.liveError?.let { ErrorState("Couldn't load your channels", "$it Check the playlist in Settings, or try again later.") } ?: EmptyState(Icons.Default.LiveTv, "No live channels", "Add a playlist with live TV in Settings"); return }
    val refresh = act.refresh + Repo.version
    val favUrls = remember(refresh) { Repo.favorites().map { it.optString("url") }.toSet() }
    val hiddenCh = remember(Repo.version) { Repo.hiddenChannels }
    val byGroup = remember(l) { l.channels.groupBy { it.group } }
    val favCount = remember(l, favUrls) { l.channels.count { it.url in favUrls } }   // favourites still in a playlist (not stale links)
    val groups = remember(l, favUrls, Repo.version) { listOfNotNull(if (favUrls.isNotEmpty()) "★ Favourites" else null, "All channels") + Repo.arrange(l.groups.filter { it.isNotBlank() }) }
    var group by rememberSaveable { mutableStateOf(startGroup ?: groups.firstOrNull { it != "All channels" && it != "★ Favourites" } ?: groups[0]) }
    // The highlight moves through the groups at once; the channel list only follows once it rests there (TiviMate does
    // the same), so holding down through the groups doesn't build a whole list for every group passed.
    var focusedGroup by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(focusedGroup) { val g = focusedGroup ?: return@LaunchedEffect; if (g != group) { delay(280); group = g } }
    var unlockTick by remember { mutableIntStateOf(0) }
    val locked = remember(group, Repo.version, unlockTick) { Repo.isLocked(group) }
    // Built off the main thread ("All channels" can be ~19k channels): the old list stays up until the new one is ready.
    val list by produceState(emptyList<Channel>(), group, l, favUrls, hiddenCh, Repo.version) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            val hidden = Repo.hiddenGroups; val lockedGs = if (Repo.pin.isEmpty()) emptySet() else Repo.lockedGroups - Repo.unlocked
            when (group) { "All channels" -> l.channels.filter { it.group !in hidden && it.group !in lockedGs }; "★ Favourites" -> l.channels.filter { it.url in favUrls }; else -> byGroup[group] ?: emptyList() }
                .let { if (hiddenCh.isEmpty()) it else it.filter { c -> c.url !in hiddenCh } }
        }
    }
    var minute by remember { mutableIntStateOf(0) }
    val guideTick = Repo.guideVersion + minute * 1000
    val listState = rememberLazyListState()
    // Keyed on the list itself, so the very first frame of a new group already builds only 2 rows (a LaunchedEffect
    // alone runs a frame late, and that frame built all nine: an 83 ms hitch).
    var shownRows by remember(list) { mutableIntStateOf(1) }
    LaunchedEffect(list) { while (shownRows < list.size && shownRows < 30) { androidx.compose.runtime.withFrameNanos { }; shownRows += 1 }; shownRows = Int.MAX_VALUE }
    // The group's logos are fetched in the background as soon as its list is known, so rows appear with them in place.
    val ctxImg = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(list) { Repo.prefetchLogos(ctxImg, list.take(150)) }
    // Guide data stays current while the screen is open: asked again every 30 min (each channel re-downloads at most every 3 h).
    LaunchedEffect(list) { while (true) { delay(30 * 60_000L); if (list.isNotEmpty()) Repo.requestGuide(list.take(300)) } }
    var shownGroup by remember { mutableStateOf<String?>(null) }   // back to the top only when the list is a new group's
    LaunchedEffect(list) { if (list.isEmpty()) return@LaunchedEffect; if (shownGroup != group) { shownGroup = group; listState.scrollToItem(0) }; Repo.requestGuide(list.take(300)) }
    LaunchedEffect(Unit) { while (true) { delay(60_000); minute++ } }
    val firstVisible by remember { derivedStateOf { listState.firstVisibleItemIndex } }
    LaunchedEffect(firstVisible / 100) { if (firstVisible > 200) Repo.requestGuide(list.drop(firstVisible).take(300)) }
    var chanMenu by remember { mutableStateOf<Channel?>(null) }
    // A channel removed from the list (hidden, or unfavourited in Favourites): the row now in its place takes the highlight.
    var refocusAt by remember { mutableIntStateOf(-1) }
    val refocusReq = remember { FocusRequester() }
    LaunchedEffect(list) { if (refocusAt < 0) return@LaunchedEffect; refocusAt = refocusAt.coerceAtMost(list.size - 1)
        repeat(15) { delay(60); if (refocusAt < 0) return@LaunchedEffect; runCatching { refocusReq.requestFocus() } }; refocusAt = -1 }
    var groupMenu by remember { mutableStateOf<String?>(null) }
    var focusedChan by remember(group) { mutableStateOf<Channel?>(null) }
    var focusedIdx by remember(group) { mutableIntStateOf(-1) }   // (its place in the list, for "12 of 60")
    var pinFor by remember { mutableStateOf<Pair<String, (String) -> Unit>?>(null) }   // PIN prompt: title, then what to do with the right PIN
    val groupFocus = remember { FocusRequester() }   // entering the group list lands on the group that's open

    Column(Modifier.fillMaxSize()) {
        LaunchedEffect(l) { Preview.current() }   // (falls back to the last channel watched; not during drawing)
        val pc = Preview.channel
        if (Repo.preview && !locked) Row(Modifier.fillMaxWidth().height(186.dp).padding(bottom = 12.dp).zIndex(1f)) {   // (above the lists, for the grow-to-full-screen)
            // TiviMate-style top: the preview (last channel, or the one picked with OK) and what's on the highlighted channel.
            PreviewBox(pc, act.resumeTick, Modifier.zIndex(1f).padding(start = Tv.margin, top = Tv.topMargin - 8.dp).fillMaxHeight().aspectRatio(16 / 9f))
            val fc = focusedChan ?: pc
            // Where the highlight is in the open group ("UK · General  ·  12 of 60"): a sense of place in long lists.
            val place = prettyGroup(group) + "  ·  " + (if (fc != null && fc == focusedChan && focusedIdx in list.indices) "${focusedIdx + 1} of ${list.size}" else "${list.size} channels")
            LiveNowNext(fc, guideTick, place, Modifier.weight(1f).padding(start = 24.dp, end = Tv.margin + 80.dp, top = Tv.topMargin - 6.dp))
        } else Row(Modifier.fillMaxWidth().padding(start = Tv.margin, end = Tv.margin, top = Tv.topMargin, bottom = 14.dp), verticalAlignment = Alignment.Bottom) {
            Text("Live TV", fontSize = Tv.title, fontWeight = FontWeight.Bold, color = Tv.text)
            Spacer(Modifier.width(14.dp))
            Text("${prettyGroup(group)}  ·  ${list.size} channels", fontSize = Tv.bodySmall, color = Tv.muted, modifier = Modifier.padding(bottom = 3.dp))
            Spacer(Modifier.weight(1f))
            Text("Hold OK for options  ·  Type a number to zap", fontSize = Tv.label, color = Tv.muted, modifier = Modifier.padding(bottom = 3.dp))
        }
        Row(Modifier.fillMaxSize()) {
            LazyColumn(Modifier.width(290.dp).fillMaxHeight().padding(start = Tv.margin - 12.dp).focusProperties { enter = { groupFocus } }.focusGroup(), contentPadding = PaddingValues(bottom = 24.dp)) {
                items(groups, key = { it }) { g ->
                    val special = g == "All channels" || g == "★ Favourites"
                    // Slim group row (38dp): the open group keeps a quiet tint, the highlight is white.
                    TvListRow(onClick = { focusedGroup = g; group = g }, onLongClick = { if (!special) groupMenu = g },
                        modifier = Modifier.fillMaxWidth().height(38.dp).padding(vertical = 1.dp).let { if (g == group) it.focusRequester(groupFocus) else it }.onFocusChanged { if (it.isFocused) focusedGroup = g },
                        selected = g == group, label = prettyGroup(g)) {
                        Text((if (Repo.pin.isNotEmpty() && g in Repo.lockedGroups) "🔒 " else if (g in Repo.pinnedGroups) "↑ " else "") + prettyGroup(g), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = Tv.bodySmall, fontWeight = if (g == group) FontWeight.SemiBold else FontWeight.Normal, modifier = Modifier.weight(1f))
                        Text("${when (g) { "All channels" -> l.channels.size; "★ Favourites" -> favCount; else -> byGroup[g]?.size ?: 0 }}", fontSize = Tv.label, color = LocalContentColor.current.copy(alpha = .6f))
                    }
                }
            }
            if (locked) Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Lock, null, Modifier.size(36.dp), tint = Tv.muted)
                    Spacer(Modifier.height(10.dp))
                    Text("This group is locked", fontSize = Tv.body, color = Tv.text, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(14.dp))
                    TvButton("Enter PIN", { pinFor = "Enter PIN to open ${prettyGroup(group)}" to { _ -> Repo.unlocked += group; unlockTick++ } }, icon = Icons.Default.Lock, style = TvButtonStyle.Primary)
                }
            }
            else LazyColumn(Modifier.weight(1f).fillMaxHeight().padding(start = 12.dp, end = Tv.margin - 16.dp), state = listState, contentPadding = PaddingValues(bottom = 24.dp)) {
                // A new group's rows are built a few per frame (all nine at once took ~150-200 ms on the Shield).
                itemsIndexed(list.subList(0, minOf(list.size, shownRows)), key = { i, c -> "$i|${c.url}" }) { i, c -> ChannelRow(c, guideTick, c.url in favUrls, onLong = { chanMenu = c }, onFocus = { focusedChan = c; focusedIdx = i; if (i == refocusAt) refocusAt = -1 }, inPreview = c.url == Preview.channel?.url,
                    modifier = if (i == refocusAt) Modifier.focusRequester(refocusReq) else Modifier) {
                    if (Repo.preview && Preview.channel?.url != c.url) Preview.select(c, list) else act.playLive(list, i)   // first OK: preview; again: full screen
                } }
            }
        }
    }
    chanMenu?.let { c -> ChannelOptions(act, c, onRemove = { what -> if (what == "hide" || group == "★ Favourites") refocusAt = list.indexOf(c) }) { chanMenu = null } }
    groupMenu?.let { g ->
        val isLocked = g in Repo.lockedGroups && Repo.pin.isNotEmpty()
        Options(prettyGroup(g), listOf(
            (if (g in Repo.pinnedGroups) "Move back to its place" else "Move to the top") to { groupMenu = null; Repo.togglePinned(g) },
            "Hide this group" to { groupMenu = null; if (group == g) group = groups.firstOrNull { it != g && it != "★ Favourites" } ?: "All channels"; Repo.toggleHidden(g) },
            (if (isLocked) "Unlock this group" else "Lock with PIN") to {
                groupMenu = null
                if (Repo.pin.isEmpty()) pinFor = "Choose a 4-digit PIN for locked groups" to { pin -> Repo.pin = pin; if (g !in Repo.lockedGroups) Repo.toggleLock(g) }
                else pinFor = "Enter PIN" to { _ -> Repo.toggleLock(g) }
            },
            "TV guide for this group" to { groupMenu = null; act.go(Screen.Guide(g)) },
        )) { groupMenu = null }
    }
    androidx.activity.compose.BackHandler(enabled = Repo.preview && Preview.channel != null && chanMenu == null && groupMenu == null && pinFor == null) {
        val pl = Preview.list; val c = Preview.channel!!
        act.playLive(pl.ifEmpty { listOf(c) }, pl.indexOf(c).coerceAtLeast(0))
    }
    pinFor?.let { (title, then) ->
        val choosing = Repo.pin.isEmpty()
        PinDialog(title, verify = if (choosing) null else Repo::checkPin) { pin -> pinFor = null; if (pin != null) then(pin) }
    }
}

@Composable
private fun LiveSkeleton() {
    Column(Modifier.fillMaxSize().padding(top = Tv.topMargin)) {
        Box(Modifier.padding(start = Tv.margin).width(120.dp).height(26.dp).clip(RoundedCornerShape(6.dp)).shimmer())
        Spacer(Modifier.height(24.dp))
        Row {
            Column(Modifier.width(290.dp).padding(start = Tv.margin - 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { repeat(9) { Box(Modifier.fillMaxWidth(.85f).height(36.dp).clip(RoundedCornerShape(8.dp)).shimmer()) } }
            Column(Modifier.weight(1f).padding(start = 12.dp, end = Tv.margin), verticalArrangement = Arrangement.spacedBy(12.dp)) { repeat(7) { Box(Modifier.fillMaxWidth().height(54.dp).clip(RoundedCornerShape(8.dp)).shimmer()) } }
        }
    }
}

@Composable
fun ChannelRow(c: Channel, tick: Int, fav: Boolean, onLong: () -> Unit, onFocus: () -> Unit = {}, inPreview: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    // Slim, TiviMate-dense row (40dp): number, logo, name with what's on under it; at the end, what's next above a
    // hairline progress bar (the bars line up in one column, so the list reads as a quiet now/next table).
    val (now, next) = remember(tick, c) { Repo.nowNext(c) }
    // The channel that's playing: a faint blue tint and its name in blue (subtle, TiviMate-like); the highlight stays white.
    TvListRow(onClick = onClick, onLongClick = onLong, modifier = modifier.fillMaxWidth().height(Tv.listRow).padding(vertical = 1.dp).let { if (inPreview) it.background(Tv.accent.copy(alpha = .12f), Tv.rowShape) else it }
        .onFocusChanged { if (it.isFocused) onFocus() }, contentPadding = PaddingValues(horizontal = 10.dp), label = c.name) { focused ->
        run {
            val dim = LocalContentColor.current.copy(alpha = .6f)
            Box(Modifier.width(if (Repo.showNumbers) 40.dp else 20.dp)) {
                if (inPreview) Icon(Icons.Default.PlayArrow, null, Modifier.size(Tv.iconS), tint = if (focused) dim else Tv.accent)
                else if (Repo.showNumbers) Text("${c.num}", fontSize = Tv.label, color = dim, maxLines = 1)
            }
            LogoTile(c, Modifier.size(width = 40.dp, height = 24.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(c.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = Tv.bodySmall, lineHeight = 17.sp, fontWeight = FontWeight.SemiBold, color = if (inPreview && !focused) Tv.accent else Color.Unspecified, modifier = Modifier.weight(1f, fill = false))
                    if (fav) { Spacer(Modifier.width(6.dp)); Text("★", fontSize = Tv.label, color = Tv.star) }
                    // There's a 4K copy and "Prefer 4K" is on: OK plays that copy (the normal one stays as its backup).
                    if (Repo.fourKFirst && remember(c.url) { Repo.uhdFor(c) != null }) {
                        Spacer(Modifier.width(8.dp))
                        Text("4K", fontSize = Tv.micro, fontWeight = FontWeight.Bold, color = LocalContentColor.current.copy(alpha = .75f),
                            modifier = Modifier.border(1.dp, LocalContentColor.current.copy(alpha = .45f), RoundedCornerShape(3.dp)).padding(horizontal = 4.dp))
                    }
                }
                Text(now?.title ?: "No programme information", maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = Tv.micro, lineHeight = 14.sp, color = LocalContentColor.current.copy(alpha = .7f))
            }
            // Next, and how far through now (one column at the row's end).
            Column(Modifier.padding(start = 16.dp).width(LIVE_NEXT_W), verticalArrangement = Arrangement.Center) {
                Text(next?.let { "${hhmm(it.start)}  ${it.title}" } ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = Tv.micro, lineHeight = 14.sp, color = LocalContentColor.current.copy(alpha = .55f))
                Spacer(Modifier.height(5.dp))
                if (now != null) ProgressLine(liveFraction(now, System.currentTimeMillis()), Modifier.fillMaxWidth(), 2.dp)
                else Spacer(Modifier.height(2.dp))
            }
        }
    }
}

private val LIVE_NEXT_W = 168.dp

/** The top of Live TV, beside the preview: where you are, what's on the highlighted channel (with a progress line and
 *  time left), what's next, and a short description. Calm: one title, quiet lines under it, red only as a dot. */
@Composable
private fun LiveNowNext(fc: Channel?, guideTick: Int, place: String, modifier: Modifier) {
    Column(modifier.clearAndSetSemantics { }) {
        Text(place, fontSize = Tv.label, lineHeight = Tv.labelLine, color = Tv.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (fc == null) { Text("Live TV", fontSize = Tv.title, lineHeight = Tv.titleLine, fontWeight = Tv.wTitle, color = Tv.text, modifier = Modifier.padding(top = 2.dp)); return@Column }
        val t = System.currentTimeMillis()
        val (n, nx) = remember(fc, guideTick) { Repo.nowNext(fc) }
        Text(n?.title ?: fc.name, fontSize = Tv.title, lineHeight = Tv.titleLine, fontWeight = Tv.wTitle, color = Tv.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (n != null) { Box(Modifier.size(6.dp).background(Tv.live, androidx.compose.foundation.shape.CircleShape)); Spacer(Modifier.width(8.dp)) }
            Text(listOfNotNull(fc.name, n?.let { "${hhmm(it.start)}–${hhmm(it.end)}" }, n?.let { liveLeftLabel(it, t) }).joinToString("  ·  "),
                fontSize = Tv.bodySmall, lineHeight = Tv.bodySmallLine, color = Tv.text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (n != null) ProgressLine(liveFraction(n, t), Modifier.padding(top = 8.dp).width(240.dp).clip(Tv.shapeS), 3.dp)
        if (nx != null) Text("Next  ${hhmm(nx.start)}  ${nx.title}", fontSize = Tv.caption, lineHeight = Tv.captionLine, color = Tv.text2.copy(alpha = .8f), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
        if (!n?.desc.isNullOrBlank()) Text(n!!.desc, fontSize = Tv.caption, color = Tv.muted, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = Tv.captionLine, modifier = Modifier.padding(top = 2.dp))
    }
}
