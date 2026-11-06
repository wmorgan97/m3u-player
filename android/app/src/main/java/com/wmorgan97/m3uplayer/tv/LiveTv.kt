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
        ChannelCard(c.name, Repo.chLogo(c), now?.title ?: prettyGroup(c.group), p, restoreKey = keyPrefix?.let { it + c.url }, onLong = { onLong(c) }) { act.playLive(chans, i) }
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
    val live by produceState<Repo.Live?>(Repo.live, Repo.version, Repo.liveTick) {
        // A failed or interrupted load keeps what was there (it used to put up an empty list: "No live channels" while
        // the playlists were still loading after a cold start).
        value = try { Repo.liveChannels() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { value ?: Repo.Live(emptyList(), emptyList(), emptyList()) }
    }
    val l = live
    // Before the first sync arrives, or while the playlists load: that's loading, not "no channels".
    if (l == null || Repo.liveSettling(l)) { LiveSkeleton(); return }
    if (l.channels.isEmpty()) { Repo.liveError?.let { ErrorState("Couldn't load your channels", "$it Check the playlist in Settings, or try again later.") } ?: EmptyState(Icons.Default.LiveTv, "No live channels", "Add a playlist with live TV in Settings"); return }
    val refresh = act.refresh + Repo.version
    val favChans = remember(refresh, l) { Repo.favouriteChannels(l) }   // the same set as the Guide's (Favourites.kt)
    val favUrls = remember(favChans) { favChans.mapTo(HashSet()) { it.url } }
    val hiddenCh = remember(Repo.version) { Repo.hiddenChannels }
    val byGroup = remember(l) { l.channels.groupBy { it.group } }
    val favCount = favChans.size   // favourites still in a playlist (not stale links)
    val groups = remember(l, favUrls, Repo.version) { listOfNotNull(if (favChans.isNotEmpty()) "★ Favourites" else null, "All channels") + Repo.arrange(l.groups.filter { it.isNotBlank() }) }
    var group by rememberSaveable { mutableStateOf(startGroup ?: groups.firstOrNull { it != "All channels" && it != "★ Favourites" } ?: groups[0]) }
    // The highlight moves through the groups at once; the channel list only follows once it rests there (TiviMate does
    // the same), so holding down through the groups doesn't build a whole list for every group passed.
    var focusedGroup by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(focusedGroup) { val g = focusedGroup ?: return@LaunchedEffect; if (g != group) { delay(280); group = g } }
    var unlockTick by remember { mutableIntStateOf(0) }
    val locked = remember(group, Repo.version, unlockTick) { Repo.isLocked(group) }
    // Built off the main thread ("All channels" can be ~19k channels): the old list stays up until the new one is ready.
    val list by produceState(emptyList<Channel>(), group, l, favChans, hiddenCh, Repo.version) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            val hidden = Repo.hiddenGroups; val lockedGs = if (Repo.pin.isEmpty()) emptySet() else Repo.lockedGroups - Repo.unlocked
            when (group) { "All channels" -> l.channels.filter { it.group !in hidden && it.group !in lockedGs }; "★ Favourites" -> favChans; else -> byGroup[group] ?: emptyList() }
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
    // A new group's list eases in (a short fade) instead of popping.
    val listFade = remember { androidx.compose.animation.core.Animatable(1f) }
    var fadeKey by remember { mutableIntStateOf(0) }
    LaunchedEffect(fadeKey) { if (fadeKey > 1) { listFade.snapTo(.25f); listFade.animateTo(1f, androidx.compose.animation.core.tween(Tv.durMed, easing = Tv.easeEnter)) } }
    var shownGroup by remember { mutableStateOf<String?>(null) }   // back to the top only when the list is a new group's
    LaunchedEffect(list) { if (list.isEmpty()) return@LaunchedEffect; if (shownGroup != group) { shownGroup = group; listState.scrollToItem(0); fadeKey++ }; Repo.requestGuide(list.take(300)) }
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
        if (Repo.preview && !locked) Row(Modifier.fillMaxWidth().height(LiveTvTokens.headerH).zIndex(1f)) {   // (above the lists, for the grow-to-full-screen)
            // TiviMate-style top, laid out exactly as the Guide's: the preview (last channel, or the one picked with OK)
            // and what's on the highlighted channel.
            PreviewBox(pc, act.resumeTick, Modifier.zIndex(1f).padding(start = Tv.margin, top = Tv.topMargin - Tv.s2, bottom = Tv.s3).fillMaxHeight().aspectRatio(16 / 9f))
            val fc = focusedChan ?: pc
            LiveNowNext(fc, guideTick, Modifier.weight(1f).fillMaxHeight().padding(start = 24.dp, end = Tv.margin + Tv.clockReserve, top = Tv.topMargin))
        } else Row(Modifier.fillMaxWidth().padding(start = Tv.margin, end = Tv.margin + Tv.clockReserve, top = Tv.topMargin, bottom = 14.dp), verticalAlignment = Alignment.Bottom) {
            Text("Live TV", fontSize = Tv.title, fontWeight = FontWeight.Bold, color = Tv.text)
            Spacer(Modifier.width(14.dp))
            Text("${prettyGroup(group)}  ·  ${list.size} channels", fontSize = Tv.bodySmall, color = Tv.muted, modifier = Modifier.padding(bottom = 3.dp))
            Spacer(Modifier.weight(1f))
            Text("Hold OK for options  ·  Type a number to zap", fontSize = Tv.label, color = Tv.muted, modifier = Modifier.padding(bottom = 3.dp))
        }
        Row(Modifier.fillMaxSize()) {
            LazyColumn(Modifier.width(LiveTvTokens.groupW + Tv.margin - 12.dp).fillMaxHeight().padding(start = Tv.margin - 12.dp).focusProperties { enter = { groupFocus } }.focusGroup(), contentPadding = PaddingValues(bottom = 24.dp)) {
                items(groups, key = { it }) { g ->
                    val special = g == "All channels" || g == "★ Favourites"
                    // Group row: name and count together (the count right after the name, not at the far edge); the open group keeps a quiet tint, the highlight is white.
                    TvListRow(onClick = { focusedGroup = g; group = g }, onLongClick = { if (!special) groupMenu = g },
                        modifier = Modifier.fillMaxWidth().height(LiveTvTokens.rowH).padding(vertical = 1.dp).let { if (g == group) it.focusRequester(groupFocus) else it }.onFocusChanged { if (it.isFocused) focusedGroup = g },
                        selected = g == group, label = prettyGroup(g)) {
                        Text((if (Repo.pin.isNotEmpty() && g in Repo.lockedGroups) "🔒 " else if (g in Repo.pinnedGroups) "↑ " else "") + prettyGroup(g), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = Tv.bodySmall, fontWeight = if (g == group) FontWeight.SemiBold else FontWeight.Normal, modifier = Modifier.weight(1f, fill = false))
                        Spacer(Modifier.width(8.dp))
                        Text("${when (g) { "All channels" -> l.channels.size; "★ Favourites" -> favCount; else -> byGroup[g]?.size ?: 0 }}", fontSize = Tv.label, maxLines = 1, color = LocalContentColor.current.copy(alpha = .6f))
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
            else LazyColumn(Modifier.weight(1f).fillMaxHeight().padding(start = Tv.s2, end = Tv.margin - 16.dp).graphicsLayer { alpha = listFade.value }, state = listState, contentPadding = PaddingValues(bottom = 24.dp)) {
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
    // Back opens the menu (TvActivity), as on every page; OK on the previewed channel goes full screen.
    pinFor?.let { (title, then) ->
        val choosing = Repo.pin.isEmpty()
        PinDialog(title, verify = if (choosing) null else Repo::checkPin) { pin -> pinFor = null; if (pin != null) then(pin) }
    }
}

@Composable
private fun LiveSkeleton() {
    // The same blocks as the loaded screen (header height, preview window, 48 dp rows), so nothing moves when it fills in.
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(LiveTvTokens.headerH)) {
            if (Repo.preview) Box(Modifier.padding(start = Tv.margin, top = Tv.topMargin - Tv.s2, bottom = Tv.s3).fillMaxHeight().aspectRatio(16 / 9f).clip(Tv.shape).shimmer())
            Column(Modifier.weight(1f).padding(start = if (Repo.preview) 24.dp else Tv.margin, top = Tv.topMargin + 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.fillMaxWidth(.55f).height(22.dp).clip(Tv.shapeS).shimmer())
                Box(Modifier.fillMaxWidth(.4f).height(14.dp).clip(Tv.shapeS).shimmer())
                Box(Modifier.fillMaxWidth(.7f).height(12.dp).clip(Tv.shapeS).shimmer())
            }
        }
        Row(Modifier.fillMaxSize()) {
            Column(Modifier.width(LiveTvTokens.groupW + Tv.margin - 12.dp).padding(start = Tv.margin - 12.dp)) { repeat(8) { Box(Modifier.fillMaxWidth().height(LiveTvTokens.rowH).padding(vertical = 1.dp).clip(Tv.rowShape).shimmer()) } }
            Column(Modifier.weight(1f).padding(start = Tv.s2, end = Tv.margin - 16.dp)) { repeat(8) { Box(Modifier.fillMaxWidth().height(LiveTvTokens.rowH).padding(vertical = 1.dp).clip(Tv.rowShape).shimmer()) } }
        }
    }
}

@Composable
fun ChannelRow(c: Channel, tick: Int, fav: Boolean, onLong: () -> Unit, onFocus: () -> Unit = {}, inPreview: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    // One 48 dp row: number (or a small play glyph for the channel that's playing), logo, name | what's on now with its
    // hairline progress under it | what's next. The columns sit close together, so the row reads left to right with no
    // large gaps. The playing channel is shown softly: accent name and a play glyph, no fill. The highlight stays white.
    val (now, next) = remember(tick, c) { Repo.nowNext(c) }
    TvListRow(onClick = onClick, onLongClick = onLong, modifier = modifier.fillMaxWidth().height(LiveTvTokens.rowH).padding(vertical = 1.dp)
        .onFocusChanged { if (it.isFocused) onFocus() }, contentPadding = PaddingValues(horizontal = 10.dp), label = c.name) { focused ->
        val dim = LocalContentColor.current.copy(alpha = .6f)
        Box(Modifier.width(if (Repo.showNumbers) 36.dp else 20.dp)) {
            if (inPreview) Icon(Icons.Default.PlayArrow, null, Modifier.size(Tv.iconS), tint = if (focused) Tv.onFocus.copy(alpha = .7f) else Tv.accent)
            else if (Repo.showNumbers) Text("${c.num}", fontSize = Tv.label, color = dim, maxLines = 1)
        }
        LogoTile(c, Modifier.size(width = 40.dp, height = 24.dp))
        Spacer(Modifier.width(12.dp))
        // Name
        Row(Modifier.weight(.8f), verticalAlignment = Alignment.CenterVertically) {
            Text(c.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = Tv.bodySmall, lineHeight = Tv.bodySmallLine, fontWeight = FontWeight.SemiBold, color = if (inPreview && !focused) Tv.accent else Color.Unspecified, modifier = Modifier.weight(1f, fill = false))
            if (fav) { Spacer(Modifier.width(6.dp)); Text("★", fontSize = Tv.label, color = Tv.star) }
            // There's a 4K copy and "Prefer 4K" is on: OK plays that copy (the normal one stays as its backup).
            if (Repo.fourKFirst && remember(c.url) { Repo.uhdFor(c) != null }) {
                Spacer(Modifier.width(8.dp))
                Text("4K", fontSize = Tv.micro, fontWeight = FontWeight.Bold, color = LocalContentColor.current.copy(alpha = .75f),
                    modifier = Modifier.border(1.dp, LocalContentColor.current.copy(alpha = .45f), RoundedCornerShape(3.dp)).padding(horizontal = 4.dp))
            }
        }
        Spacer(Modifier.width(LiveTvTokens.colGap))
        // Now, with how far through it is
        Column(Modifier.weight(1.25f), verticalArrangement = Arrangement.Center) {
            Text(now?.title ?: "No programme information", maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = Tv.label, lineHeight = Tv.labelLine, color = LocalContentColor.current.copy(alpha = if (now != null) .85f else .5f))
            if (now != null) ProgressLine(liveFraction(now, System.currentTimeMillis()), Modifier.padding(top = 4.dp).fillMaxWidth().clip(Tv.pill), 2.dp)
        }
        Spacer(Modifier.width(LiveTvTokens.colGap))
        // Next
        Row(Modifier.width(LiveTvTokens.nextW)) {
            if (next != null) {
                Text(hhmm(next.start), maxLines = 1, fontSize = Tv.micro, lineHeight = Tv.microLine, color = LocalContentColor.current.copy(alpha = .55f), modifier = Modifier.width(38.dp))
                Text(next.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = Tv.micro, lineHeight = Tv.microLine, color = LocalContentColor.current.copy(alpha = .55f), modifier = Modifier.weight(1f))
            }
        }
    }
}

/** The top of Live TV, beside the preview, laid out like the Guide's header: the programme's title, one meta line
 *  (channel · times · length · time left, with a short progress line), and up to two lines of description.
 *  Calm: red only as a dot. */
@Composable
private fun LiveNowNext(fc: Channel?, guideTick: Int, modifier: Modifier) {
    Column(modifier.clearAndSetSemantics { }) {
        if (fc == null) { Text("Live TV", fontSize = Tv.title, fontWeight = FontWeight.Bold, color = Tv.text); return@Column }
        val t = System.currentTimeMillis()
        val (n, nx) = remember(fc, guideTick) { Repo.nowNext(fc) }
        Text(n?.title ?: fc.name, fontSize = Tv.title, fontWeight = FontWeight.Bold, color = Tv.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val bits = listOfNotNull(fc.name, n?.let { "${hhmm(it.start)}–${hhmm(it.end)}" }, n?.let { liveLeftLabel(it, t) })
        Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            if (n != null) { Box(Modifier.size(Tv.liveDot).background(Tv.live, androidx.compose.foundation.shape.CircleShape)); Spacer(Modifier.width(8.dp)) }
            Text(bits.joinToString("  ·  "), fontSize = Tv.bodySmall, color = Tv.text2, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            if (n != null) ProgressLine(liveFraction(n, t), Modifier.padding(start = 14.dp).width(120.dp).clip(Tv.pill), 3.dp)
        }
        // The description; with none, what's next stands in, so the block never has a hole.
        val desc = n?.desc?.takeIf { it.isNotBlank() } ?: nx?.let { "Next  ${hhmm(it.start)}  ${it.title}" } ?: if (n == null) "No programme information" else ""
        Text(desc, fontSize = Tv.caption, color = Tv.muted, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = Tv.captionLine, modifier = Modifier.padding(top = 4.dp))
    }
}
