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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
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
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

// ---------- Home ----------

@Composable
fun HomeScreen(act: TvActivity) {
    val refresh = act.refresh + Repo.version
    val movies by produceState(Repo.cachedOverview("movies"), Repo.version) { if (value == null) value = runCatching { Repo.overview("movies") }.getOrNull() }
    val series by produceState(Repo.cachedOverview("series"), Repo.version) { if (value == null) value = runCatching { Repo.overview("series") }.getOrNull() }
    val cw = remember(refresh) { Repo.continueWatching() }
    val favs = remember(refresh) { Repo.favorites().filter { it.optString("url").isNotEmpty() } }
    val recent = remember(refresh) { Repo.recentChannels() }
    val guideTick = Repo.guideVersion
    LaunchedEffect(favs, recent, Repo.version) {
        val chans = (favs + recent).map { liveFromSync(it) }
        if (chans.isNotEmpty()) { if (Repo.live == null) runCatching { Repo.liveChannels() }; Repo.requestGuide(chans) }
    }
    var menuFor by remember { mutableStateOf<Progress?>(null) }
    var chanMenu by remember { mutableStateOf<Channel?>(null) }
    val scope = rememberCoroutineScope()

    LazyColumn(Modifier.fillMaxSize(), state = rememberPageState(), contentPadding = PaddingValues(bottom = 56.dp)) {
        // Trending films and series alternate (#1 movie, #1 series, #2 movie, …), each ranked within its own kind.
        item { Hero(act, run { val m = (movies?.trending ?: emptyList()).filter { it.backdrop.isNotEmpty() }.take(4); val sr = (series?.trending ?: emptyList()).filter { it.backdrop.isNotEmpty() }.take(4)
            (0 until maxOf(m.size, sr.size)).flatMap { k -> listOfNotNull(m.getOrNull(k), sr.getOrNull(k)) } }, movies == null && series == null) }
        if (cw.isNotEmpty()) {
            item { RowTitle("Continue watching") }
            item {
                CardRow(cw, key = { it.url }) { p, _ ->
                    val it = p.item
                    WideCard(it.optString("logo"), progressTitle(it), "${clock((p.d - p.t).toLong())} left", (p.t / p.d).toFloat(), restoreKey = "cw:" + p.url,
                        badge = if (it.optString("kind") == "episode") "EPISODE" else null, onLong = { menuFor = p }) { resume(act, p) }
                }
            }
        }
        if (favs.isNotEmpty()) {
            item { RowTitle("Favourite channels", count = favs.size) }
            item { ChannelRowCards(act, favs.map { liveFromSync(it) }, guideTick) { chanMenu = it } }
        }
        if (recent.isNotEmpty()) {
            item { RowTitle("Recently watched channels") }
            item { ChannelRowCards(act, recent.map { liveFromSync(it) }, guideTick) { chanMenu = it } }
        }
        val m = movies; val s = series
        if (m == null) { item { SkeletonTitle() }; item { SkeletonRow() } }
        else {
            if (m.trending.isNotEmpty()) { item { RowTitle("Top 10 movies this week", "from TMDB") }; item { CardRow(m.trending.take(10), { it.id }) { v, i -> PosterCard(v, rank = i + 1) { act.go(Screen.Details(v)) } } } }
            if (m.top.isNotEmpty()) { item { RowTitle("Popular movies") }; item { CardRow(m.top.take(30), { it.id }) { v, _ -> PosterCard(v) { act.go(Screen.Details(v)) } } } }
            if (m.fresh.isNotEmpty()) { item { RowTitle("New movies") }; item { CardRow(m.fresh.take(30), { it.id }) { v, _ -> PosterCard(v) { act.go(Screen.Details(v)) } } } }
        }
        if (s == null) { item { SkeletonTitle() }; item { SkeletonRow() } }
        else {
            if (s.trending.isNotEmpty()) { item { RowTitle("Top 10 series this week", "from TMDB") }; item { CardRow(s.trending.take(10), { it.id }) { v, i -> PosterCard(v, rank = i + 1) { act.go(Screen.Details(v)) } } } }
            if (s.top.isNotEmpty()) { item { RowTitle("Popular series") }; item { CardRow(s.top.take(30), { it.id }) { v, _ -> PosterCard(v) { act.go(Screen.Details(v)) } } } }
            if (s.fresh.isNotEmpty()) { item { RowTitle("New series") }; item { CardRow(s.fresh.take(30), { it.id }) { v, _ -> PosterCard(v) { act.go(Screen.Details(v)) } } } }
        }
    }

    menuFor?.let { p ->
        Options(progressTitle(p.item), listOf(
            "Resume" to { menuFor = null; resume(act, p) },
            "Start over" to { menuFor = null; resume(act, p, fromStart = true) },
            "Remove from Continue watching" to { menuFor = null; scope.launch { Repo.saveProgress(p.url, p.t, p.d, p.item, hide = true); act.refresh++ } },
        )) { menuFor = null }
    }
    chanMenu?.let { c -> ChannelOptions(act, c) { chanMenu = null } }
}

/** A row of channel cards with what's on now (favourites, recent). */
@Composable
fun ChannelRowCards(act: TvActivity, all: List<Channel>, tick: Int, onLong: (Channel) -> Unit) {
    val chans = remember(all) { all.distinctBy { it.url } }   // a playlist can list one stream twice; row keys must be unique
    CardRow(chans, { it.url }) { c, i ->
        val (now, _) = remember(tick, c) { Repo.nowNext(c) }
        val p = now?.let { ((System.currentTimeMillis() - it.start).toFloat() / (it.end - it.start)) }
        ChannelCard(c.name, c.logo, now?.title ?: prettyGroup(c.group), p, onLong = { onLong(c) }) { act.playLive(chans, i) }
    }
}

@Composable
fun ChannelOptions(act: TvActivity, c: Channel, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val fav = Repo.isFavourite(c.url)
    Options(c.name, listOf(
        "Watch" to { onDone(); act.playLive(listOf(c), 0) },
        (if (fav) "Remove from favourites" else "Add to favourites") to { onDone(); scope.launch { Repo.toggleFavourite(c); act.refresh++ } },
        "TV guide" to { onDone(); act.go(Screen.Guide(c.group)) },
        "Hide this channel" to { onDone(); Repo.toggleHiddenChannel(c.url) },
    ), onDone)
}

fun progressTitle(it: JSONObject): String {
    val name = it.optString("name"); val m = vodMeta(name)
    return if (it.optString("kind") == "episode" && m.episode != null) "${displayTitle(name)} · S${m.season ?: 1} E${m.episode}" else displayTitle(name)
}

fun liveFromSync(o: JSONObject): Channel = Repo.channelByUrl(o.optString("url"))
    ?: Channel(o.optString("name"), o.optString("url"), o.optString("group"), o.optString("logo"), o.optString("tvgId"), "", 0)

private fun resume(act: TvActivity, p: Progress, fromStart: Boolean = false) {
    val it = p.item
    val title = progressTitle(it)
    val item = JSONObject().put("url", p.url).put("title", title).put("subtitle", if (it.optString("kind") == "episode") epTitle(it.optString("name")) else "")
        .put("live", false).put("start", if (fromStart) 0.0 else p.t).put("art", it.optString("logo")).put("listTitle", title).put("sync", it)
    act.play(listOf(item), 0)
}

/** The featured banner: TMDB's trending titles with their backdrops. Picked: a white frame; OK opens it. */
@Composable
fun Hero(act: TvActivity, items: List<Vod>, loading: Boolean = false) {
    val shape = Tv.bigShape
    val mod = Modifier.fillMaxWidth().height(340.dp).padding(start = Tv.margin, end = Tv.margin, top = Tv.topMargin)
    if (items.isEmpty()) { Box(mod.clip(shape).let { if (loading) it.shimmer() else it.background(Tv.surface) }); return }
    var focused by remember { mutableStateOf(false) }
    // Own slide timer: the TV carousel stops auto-sliding while focused when an accessibility service is on (the Shield
    // runs one), so slides advance here every 10 s, cross-fading over 0.8 s, and wait longer after you've moved by hand.
    var index by remember(items) { mutableIntStateOf(0) }
    var touchedAt by remember { mutableLongStateOf(0L) }
    LaunchedEffect(items) {
        while (true) {
            delay(10_000)
            if (System.currentTimeMillis() - touchedAt > 12_000) index = (index + 1) % items.size
        }
    }
    // Every slide's backdrop is fetched up front, so a slide never appears blank and then pops in.
    val ctx = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(items) { val l = coil.Coil.imageLoader(ctx); items.forEach { l.enqueue(coil.request.ImageRequest.Builder(ctx).data(it.backdrop).build()) } }
    // Rank within its own kind ("#3 trending series"), not its place in the mixed films + series banner.
    val ranks = remember(items) { val n = HashMap<String, Int>(); items.map { v -> (n.merge(v.kind, 1, Int::plus) ?: 1) } }
    Box(mod.onFocusChanged { focused = it.hasFocus }
            .border(if (focused) 1.5.dp else 0.dp, if (focused) Tv.focusRing.copy(alpha = .85f) else Color.Transparent, shape).clip(shape)   // thin, clean focus line
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) {
                    if (e.type == KeyEventType.KeyUp && (e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter)) { items.getOrNull(index)?.let { act.go(Screen.Details(it)) }; return@onKeyEvent true }
                    return@onKeyEvent false
                }
                when (e.key) {
                    Key.DirectionRight -> { index = (index + 1) % items.size; touchedAt = System.currentTimeMillis(); true }
                    Key.DirectionLeft -> if (index > 0) { index--; touchedAt = System.currentTimeMillis(); true } else false   // left on the first slide: the menu
                    else -> false
                }
            }.focusable()) {
    androidx.compose.animation.Crossfade(index.coerceIn(0, items.size - 1), animationSpec = androidx.compose.animation.core.tween(800), label = "hero") { i ->
        val v = items[i]
        Box(Modifier.fillMaxSize()) {
            Picture(v.backdrop, Modifier.fillMaxSize(), fade = false)   // (the slide change already cross-fades)
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color(0xF20E1014), Color(0xB30E1014), Color.Transparent), endX = 1400f)))
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0x990E1014)), startY = 400f)))
            Column(Modifier.align(Alignment.CenterStart).padding(start = 40.dp).fillMaxWidth(.56f)) {
                Text("#${ranks.getOrElse(i) { i + 1 }} TRENDING ${if (v.kind == "series") "SERIES" else "MOVIE"}", fontSize = Tv.label, color = Tv.accent, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                Spacer(Modifier.height(8.dp))
                Text(v.title, fontSize = Tv.display, fontWeight = FontWeight.Bold, color = Tv.text, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 38.sp)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    v.year?.let { Tag("$it") }
                    v.rating?.takeIf { it > 0 }?.let { Tag("★ %.1f".format(it), color = Tv.star) }
                    quality(v.name, v.group)?.let { Tag(it) }
                }
                Spacer(Modifier.height(12.dp))
                Text(v.overview, fontSize = Tv.bodySmall, color = Tv.text2, maxLines = 3, overflow = TextOverflow.Ellipsis, lineHeight = 20.sp)
                Spacer(Modifier.height(18.dp))
                // One clear action, lit when the banner has focus (OK opens it).
                Row(Modifier.background(if (focused) Tv.text else Color(0x33FFFFFF), RoundedCornerShape(8.dp)).padding(start = 12.dp, end = 18.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (v.kind == "series") Icons.Default.VideoLibrary else Icons.Default.PlayArrow, null, Modifier.size(20.dp), tint = if (focused) Tv.bg else Tv.text)
                    Spacer(Modifier.width(8.dp))
                    Text(if (v.kind == "series") "Episodes" else "Watch", fontSize = Tv.bodySmall, fontWeight = FontWeight.SemiBold, color = if (focused) Tv.bg else Tv.text)
                }
            }
        }
    }
        CarouselDefaults.IndicatorRow(itemCount = items.size, activeItemIndex = index, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp))
    }
}

// ---------- Live TV ----------

@Composable
fun LiveScreen(act: TvActivity, startGroup: String? = null) {
    val live by produceState<Repo.Live?>(Repo.live, Repo.version) { value = runCatching { Repo.liveChannels() }.getOrNull() ?: Repo.Live(emptyList(), emptyList(), emptyList()) }
    val l = live
    if (l == null) { LiveSkeleton(); return }
    // Before the first sync arrives there are no playlists yet: that's loading, not "no channels".
    if (l.channels.isEmpty() && Repo.syncData.optJSONObject("sources") == null && Repo.lastSyncError == null) { LiveSkeleton(); return }
    if (l.channels.isEmpty()) { Repo.liveError?.let { EmptyState(Icons.Default.CloudOff, "Couldn't load your channels", "$it Check the playlist in Settings, or try again later.") } ?: EmptyState(Icons.Default.LiveTv, "No live channels", "Add a playlist with live TV in Settings"); return }
    val refresh = act.refresh + Repo.version
    val favUrls = remember(refresh) { Repo.favorites().map { it.optString("url") }.toSet() }
    val hiddenCh = remember(Repo.version) { Repo.hiddenChannels }
    val byGroup = remember(l) { l.channels.groupBy { it.group } }
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
    var groupMenu by remember { mutableStateOf<String?>(null) }
    var focusedChan by remember(group) { mutableStateOf<Channel?>(null) }
    var pinFor by remember { mutableStateOf<Pair<String, (String) -> Unit>?>(null) }   // PIN prompt: title, then what to do with the right PIN
    val groupFocus = remember { FocusRequester() }   // entering the group list lands on the group that's open

    Column(Modifier.fillMaxSize()) {
        LaunchedEffect(l) { Preview.current() }   // (falls back to the last channel watched; not during drawing)
        val pc = Preview.channel
        if (Repo.preview && !locked) Row(Modifier.fillMaxWidth().height(186.dp).padding(bottom = 12.dp).zIndex(1f)) {   // (above the lists, for the grow-to-full-screen)
            // TiviMate-style top: the preview (last channel, or the one picked with OK) and what's on the highlighted channel.
            PreviewBox(pc, act.resumeTick, Modifier.zIndex(1f).padding(start = Tv.margin, top = Tv.topMargin - 8.dp).fillMaxHeight().aspectRatio(16 / 9f))
            val fc = focusedChan ?: pc
            Column(Modifier.weight(1f).padding(start = 24.dp, end = Tv.margin + 80.dp, top = Tv.topMargin).clearAndSetSemantics { }) {
                if (fc != null) {
                    val (n, nx) = remember(fc, guideTick) { Repo.nowNext(fc) }
                    Text(n?.title ?: fc.name, fontSize = Tv.title, fontWeight = FontWeight.Bold, color = Tv.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(listOfNotNull(fc.name, n?.let { "${hhmm(it.start)}–${hhmm(it.end)}" }, nx?.let { "Next  ${hhmm(it.start)}  ${it.title}" }).joinToString("  ·  "), fontSize = Tv.bodySmall, color = Tv.text2, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                    if (!n?.desc.isNullOrBlank()) Text(n!!.desc, fontSize = Tv.caption, color = Tv.muted, maxLines = 3, overflow = TextOverflow.Ellipsis, lineHeight = 18.sp, modifier = Modifier.padding(top = 4.dp))
                } else Text("Live TV", fontSize = Tv.title, fontWeight = FontWeight.Bold, color = Tv.text)
            }
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
                    Surface(onClick = { focusedGroup = g; group = g }, onLongClick = { if (!special) groupMenu = g },
                        modifier = Modifier.fillMaxWidth().height(38.dp).padding(vertical = 1.dp).let { if (g == group) it.focusRequester(groupFocus) else it }.holdOk { if (!special) groupMenu = g }.onFocusChanged { if (it.isFocused) focusedGroup = g },
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)), scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                        colors = ClickableSurfaceDefaults.colors(containerColor = if (g == group) Tv.surface2 else Color.Transparent, focusedContainerColor = Tv.text, contentColor = Tv.text, focusedContentColor = Tv.bg)) {
                        Row(Modifier.fillMaxSize().padding(horizontal = 12.dp).clearAndSetSemantics { }, verticalAlignment = Alignment.CenterVertically) {
                            Text((if (Repo.pin.isNotEmpty() && g in Repo.lockedGroups) "🔒 " else if (g in Repo.pinnedGroups) "↑ " else "") + prettyGroup(g), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = Tv.bodySmall, fontWeight = if (g == group) FontWeight.SemiBold else FontWeight.Normal, modifier = Modifier.weight(1f))
                            Text("${when (g) { "All channels" -> l.channels.size; "★ Favourites" -> favUrls.size; else -> byGroup[g]?.size ?: 0 }}", fontSize = Tv.label, color = LocalContentColor.current.copy(alpha = .6f))
                        }
                    }
                }
            }
            if (locked) Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Lock, null, Modifier.size(36.dp), tint = Tv.muted)
                    Spacer(Modifier.height(10.dp))
                    Text("This group is locked", fontSize = Tv.body, color = Tv.text, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(14.dp))
                    Button(onClick = { pinFor = "Enter PIN to open ${prettyGroup(group)}" to { _ -> Repo.unlocked += group; unlockTick++ } }) { Text("Enter PIN", fontSize = Tv.bodySmall) }
                }
            }
            else LazyColumn(Modifier.weight(1f).fillMaxHeight().padding(start = 12.dp, end = Tv.margin - 16.dp), state = listState, contentPadding = PaddingValues(bottom = 24.dp)) {
                // A new group's rows are built a few per frame (all nine at once took ~150-200 ms on the Shield).
                itemsIndexed(list.subList(0, minOf(list.size, shownRows)), key = { i, c -> "$i|${c.url}" }) { i, c -> ChannelRow(c, guideTick, c.url in favUrls, onLong = { chanMenu = c }, onFocus = { focusedChan = c }, inPreview = c.url == Preview.channel?.url) {
                    if (Repo.preview && Preview.channel?.url != c.url) Preview.select(c, list) else act.playLive(list, i)   // first OK: preview; again: full screen
                } }
            }
        }
    }
    chanMenu?.let { c -> ChannelOptions(act, c) { chanMenu = null } }
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
fun ChannelRow(c: Channel, tick: Int, fav: Boolean, onLong: () -> Unit, onFocus: () -> Unit = {}, inPreview: Boolean = false, onClick: () -> Unit) {
    // Slim, TiviMate-dense row (40dp): number, logo, name with what's on under it and a hairline progress bar.
    val (now, _) = remember(tick, c) { Repo.nowNext(c) }
    Surface(onClick = onClick, onLongClick = onLong, modifier = Modifier.fillMaxWidth().height(40.dp).padding(vertical = 1.dp).holdOk(onLong).onFocusChanged { if (it.isFocused) onFocus() },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)), scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        // The channel that's playing: a faint blue tint and its name in blue (subtle, TiviMate-like); the highlight stays white.
        colors = ClickableSurfaceDefaults.colors(containerColor = if (inPreview) Tv.accent.copy(alpha = .12f) else Color.Transparent, focusedContainerColor = Tv.text, contentColor = Tv.text, focusedContentColor = Tv.bg)) {
        Row(Modifier.fillMaxSize().padding(horizontal = 10.dp).clearAndSetSemantics { }, verticalAlignment = Alignment.CenterVertically) {
            Text(if (inPreview) "▶" else if (Repo.showNumbers) "${c.num}" else "", fontSize = Tv.label, color = if (inPreview) Tv.accent else LocalContentColor.current.copy(alpha = .6f), modifier = Modifier.width(if (Repo.showNumbers) 40.dp else 20.dp), maxLines = 1)
            LogoTile(c, Modifier.size(width = 40.dp, height = 24.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(c.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold, color = if (inPreview) Tv.accent else Color.Unspecified, modifier = Modifier.weight(1f, fill = false))
                    if (fav) { Spacer(Modifier.width(6.dp)); Text("★", fontSize = Tv.label, color = Tv.star) }
                    // There's a 4K copy and "Prefer 4K" is on: OK plays that copy (the normal one stays as its backup).
                    if (Repo.fourKFirst && remember(c.url) { Repo.uhdFor(c) != null }) {
                        Spacer(Modifier.width(8.dp))
                        Text("4K", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = LocalContentColor.current.copy(alpha = .75f),
                            modifier = Modifier.border(1.dp, LocalContentColor.current.copy(alpha = .45f), RoundedCornerShape(3.dp)).padding(horizontal = 4.dp))
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(now?.title ?: "No programme information", maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 11.5.sp, lineHeight = 13.sp, color = LocalContentColor.current.copy(alpha = .7f), modifier = Modifier.weight(1f))
                    // progress bars line up in one column at the row's end
                    Box(Modifier.padding(start = 12.dp).width(64.dp)) { if (now != null) ProgressLine(((System.currentTimeMillis() - now.start).toFloat() / (now.end - now.start)), Modifier.fillMaxWidth(), 2.dp) }
                }
            }
        }
    }
}

// ---------- Movies / Series ----------

@Composable
fun VodScreen(act: TvActivity, kind: String) {
    val ov by produceState(Repo.cachedOverview(kind), Repo.version) { if (value == null) value = runCatching { Repo.overview(kind) }.getOrNull() }
    val what = if (kind == "series") "series" else "movies"
    val o = ov
    LazyColumn(Modifier.fillMaxSize(), state = rememberPageState(), contentPadding = PaddingValues(bottom = 56.dp)) {
        item { Hero(act, o?.trending?.filter { it.backdrop.isNotEmpty() }?.take(6) ?: emptyList(), o == null) }
        if (o == null) { repeat(3) { item { SkeletonTitle() }; item { SkeletonRow() } }; return@LazyColumn }
        if (o.trending.isNotEmpty()) { item { RowTitle("Top 10 $what this week", "from TMDB") }; item { CardRow(o.trending.take(10), { it.id }) { v, i -> PosterCard(v, rank = i + 1) { act.go(Screen.Details(v)) } } } }
        if (o.top.isNotEmpty()) { item { RowTitle("Popular now") }; item { CardRow(o.top.take(40), { it.id }) { v, _ -> PosterCard(v) { act.go(Screen.Details(v)) } } } }
        if (o.fresh.isNotEmpty()) { item { RowTitle("New releases") }; item { CardRow(o.fresh.take(40), { it.id }) { v, _ -> PosterCard(v) { act.go(Screen.Details(v)) } } } }
        for ((id, name) in o.groups) item(key = id) { GroupRow(act, kind, id, name) }
    }
}

/** A TMDB list / genre: its first titles, then a "See all" tile into the full grid. */
@Composable
fun GroupRow(act: TvActivity, kind: String, id: String, name: String) = traced("GroupRow") {
    // Built once, with its titles, when they're already fetched (the usual case: every group is fetched at start).
    val items by produceState(Repo.cachedGroup(kind, id), id, Repo.version) { if (value == null) value = runCatching { Repo.group(kind, id) }.getOrElse { emptyList() } }
    val l = items
    Column {
        RowTitle(name, count = l?.size)
        if (l == null) SkeletonRow()
        else if (l.isNotEmpty()) LazyRow(contentPadding = PaddingValues(horizontal = Tv.margin), horizontalArrangement = Arrangement.spacedBy(Tv.gap)) {
            items(l.take(20), key = { it.id }) { v -> PosterCard(v) { act.go(Screen.Details(v)) } }
            if (l.size > 20) item {
                Column(Modifier.width(Tv.poster)) {
                    FocusCard(Modifier.width(Tv.poster).aspectRatio(2f / 3f), onClick = { act.go(Screen.Grid(kind, id, name)) }) {
                        Box(Modifier.fillMaxSize().background(Tv.surface2), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Default.ArrowForward, null, tint = Tv.text, modifier = Modifier.size(28.dp))
                                Spacer(Modifier.height(8.dp))
                                Text("See all", fontSize = Tv.bodySmall, fontWeight = FontWeight.SemiBold, color = Tv.text)
                                Text("${l.size} titles", color = Tv.muted, fontSize = Tv.label)
                            }
                        }
                    }
                    Caption(" ", null, Tv.poster)
                }
            }
        }
    }
}

@Composable
fun GridScreen(act: TvActivity, kind: String, id: String, title: String) {
    val items by produceState<List<Vod>?>(null) { value = runCatching { Repo.group(kind, id) }.getOrElse { emptyList() } }
    Column(Modifier.fillMaxSize()) {
        PageTitle(title, items?.let { "${it.size} ${if (kind == "series") "series" else "movies"}" } ?: "Loading…")
        LazyVerticalGrid(GridCells.Adaptive(Tv.posterWide), contentPadding = PaddingValues(start = Tv.margin, end = Tv.margin, bottom = 56.dp),
            horizontalArrangement = Arrangement.spacedBy(Tv.gap), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            items(items ?: emptyList(), key = { it.id }) { v -> PosterCard(v, width = Tv.posterWide) { act.go(Screen.Details(v)) } }
        }
    }
}

// ---------- Details ----------

@Composable
fun DetailsScreen(act: TvActivity, v: Vod) {
    val info by produceState<JSONObject?>(null) { value = Repo.tmdbInfo(v) }
    val eps by produceState<Map<Int, List<Episode>>?>(null) { if (v.kind == "series") value = runCatching { Repo.episodes(v) }.getOrElse { emptyMap() } }
    val refresh = act.refresh
    val backdrop = v.backdrop.ifEmpty { info?.optString("backdrop") ?: "" }
    val overview = info?.optString("overview")?.ifEmpty { null } ?: v.overview
    val playFocus = remember { FocusRequester() }
    LaunchedEffect(eps) { delay(120); runCatching { playFocus.requestFocus() } }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var season by rememberSaveable(v.id) { mutableStateOf<Int?>(null) }   // null: the season you're watching
    Box(Modifier.fillMaxSize()) {
        if (backdrop.isNotEmpty()) Picture(backdrop, Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color(0xFC0E1014), Color(0xE60E1014), Color(0x800E1014)))))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xF50E1014)), startY = 250f)))
        CompositionLocalProvider(androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides CenteredBringIntoView) {
        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(start = Tv.margin, end = Tv.margin, top = 44.dp, bottom = 56.dp)) {
            item {
                // Back on the buttons: the page returns to the top (the episodes slide the header away, Apple TV style).
                Row(Modifier.onFocusChanged { if (it.hasFocus && listState.firstVisibleItemIndex > 0) scope.launch { listState.animateScrollToItem(0) } }) {
                    Picture(Repo.img(v.poster, 300), Modifier.width(168.dp).aspectRatio(2f / 3f).clip(Tv.shape)) {
                        Box(Modifier.fillMaxSize().background(Tv.surface2), contentAlignment = Alignment.Center) { Text(v.title, Modifier.padding(10.dp), color = Tv.text2, fontSize = Tv.caption, fontWeight = FontWeight.SemiBold) }
                    }
                    Spacer(Modifier.width(30.dp))
                    Column(Modifier.fillMaxWidth(.72f)) {
                        Text(v.title, fontSize = Tv.display, fontWeight = FontWeight.Bold, color = Tv.text, maxLines = 2, lineHeight = 38.sp)
                        info?.optString("tagline")?.takeIf { it.isNotBlank() }?.let { Text(it, fontSize = Tv.bodySmall, color = Tv.muted, modifier = Modifier.padding(top = 4.dp)) }
                        Spacer(Modifier.height(12.dp))
                        val genres = info?.optJSONArray("genres")?.let { g -> (0 until g.length()).map { g.getString(it) } } ?: emptyList()
                        val vote = info?.optDouble("vote")?.takeIf { !it.isNaN() && it > 0 } ?: v.rating
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            v.year?.let { Tag("$it") }
                            info?.optInt("runtime")?.takeIf { it > 0 }?.let { Tag(runtime(it)) }
                            vote?.let { Tag("★ %.1f".format(it), color = Tv.star) }
                            quality(v.name, v.group)?.let { Tag(it) }
                            if (v.copies > 1) Tag("${v.copies} versions")
                            genres.take(if (v.kind == "series") 2 else 3).forEach { Tag(it, color = Tv.muted, bg = Color(0x14FFFFFF)) }
                        }
                        Spacer(Modifier.height(14.dp))
                        Text(overview, fontSize = Tv.body, color = Tv.text2, maxLines = if (v.kind == "series") 3 else 5, overflow = TextOverflow.Ellipsis, lineHeight = 23.sp)
                        Spacer(Modifier.height(22.dp))
                        if (v.kind == "movie") {
                            val url = Repo.movieUrl(v) ?: ""
                            val p = remember(refresh) { Repo.progressFor(url)?.takeIf { !it.done && it.t > 30 } }
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Button(onClick = { act.play(listOf(movieItem(v, url, p?.t ?: 0.0)), 0) }, modifier = Modifier.focusRequester(playFocus)) {
                                    Icon(Icons.Default.PlayArrow, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp))
                                    Text(if (p != null) "Resume  ·  ${clock((p.d - p.t).toLong())} left" else "Play", fontSize = Tv.bodySmall, fontWeight = FontWeight.SemiBold)
                                }
                                if (p != null) OutlinedButton(onClick = { act.play(listOf(movieItem(v, url, 0.0)), 0) }) { Text("Start over", fontSize = Tv.bodySmall) }
                            }
                        } else {
                            val all = eps
                            val next = remember(all, refresh) { all?.let { nextEpisode(it) } }
                            Button(onClick = { next?.let { (e, start) -> playEpisode(act, v, all!!, e, start) } }, modifier = Modifier.focusRequester(playFocus), enabled = next != null) {
                                Icon(Icons.Default.PlayArrow, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp))
                                Text(when { all == null -> "Loading episodes…"; next == null -> "No episodes"; next.second > 0 -> "Continue S${next.first.season} E${next.first.num}"; else -> "Play S${next.first.season} E${next.first.num}" }, fontSize = Tv.bodySmall, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }
            val all = eps
            if (v.kind == "series") {
                if (all == null) { item { Spacer(Modifier.height(28.dp)); SkeletonRow(3, EP_W, 16f / 9f) } }
                else if (all.isNotEmpty()) {
                    val startSeason = nextEpisode(all)?.first?.season?.takeIf { it in all } ?: all.keys.first()
                    val list = all[season ?: startSeason] ?: emptyList()
                    item(key = "seasons") { SeasonHeader(all, season ?: startSeason, list.size) { season = it } }
                    items(list, key = { "ep:" + it.id }) { e -> EpisodeRow(act, v, all, e, backdrop.ifEmpty { v.poster }) }
                }
            }
        }
        }
    }
}

/** The episode to start: the one you were part-way through, else the one after the last you finished, else the first. */
private fun nextEpisode(all: Map<Int, List<Episode>>): Pair<Episode, Double>? {
    val flat = all.values.flatten()
    if (flat.isEmpty()) return null
    val prog = flat.mapNotNull { e -> Repo.episodeUrl(e)?.let { Repo.progressFor(it) }?.let { e to it } }
    val latest = prog.maxByOrNull { it.second.at } ?: return flat.first() to 0.0
    if (!latest.second.done && latest.second.t > 30) return latest.first to latest.second.t
    val i = flat.indexOf(latest.first)
    return (flat.getOrNull(i + 1) ?: latest.first) to 0.0
}

private fun playEpisode(act: TvActivity, show: Vod, all: Map<Int, List<Episode>>, e: Episode, start: Double) {
    val season = all[e.season] ?: listOf(e)
    val items = season.map { ep -> episodeItem(show, ep, Repo.episodeUrl(ep) ?: "", if (ep == e) start else 0.0) }
    act.play(items, season.indexOf(e).coerceAtLeast(0))
}

private val EP_W = 208.dp

/** "00:45:12" / "45:12" / "45" (provider formats) -> minutes, or null. */
private fun epMinutes(d: String): Int? {
    val p = d.trim().split(':').mapNotNull { it.trim().toIntOrNull() }
    return when (p.size) { 3 -> p[0] * 60 + p[1] + (if (p[2] >= 30) 1 else 0); 2 -> p[0] + (if (p[1] >= 30) 1 else 0); 1 -> p[0]; else -> null }?.takeIf { it > 0 }
}

/** The provider's episode pictures are TMDB stills at 185 px wide (blurry on a TV): the same picture at 780 px. */
private fun sharpStill(url: String): String = url.replace(Regex("""(image\.tmdb\.org/t/p/)w\d+/"""), "$1w780/")

/** "Season 1 · 8 episodes" and, with more than one season, a chip per season (moving onto a chip switches to it). */
@Composable
private fun SeasonHeader(all: Map<Int, List<Episode>>, season: Int, count: Int, pick: (Int) -> Unit) {
    Column(Modifier.padding(top = 30.dp, bottom = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Season $season", fontSize = Tv.rowTitle, fontWeight = FontWeight.SemiBold, color = Tv.text)
            Spacer(Modifier.width(12.dp))
            Text("$count episode${if (count == 1) "" else "s"}", fontSize = Tv.label, color = Tv.muted)
        }
        if (all.size > 1) LazyRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(all.keys.toList()) { s ->
                FilterChip(selected = s == season, onClick = { pick(s) }, modifier = Modifier.onFocusChanged { if (it.isFocused) pick(s) }) { Text("Season $s", fontSize = Tv.bodySmall) }
            }
        }
    }
}

/** One episode: a small sharp 16:9 picture (the show's backdrop when the provider has none), then
 *  "Episode N · 55m · Watched / 12m left", the title and two lines of plot. */
@Composable
private fun EpisodeRow(act: TvActivity, show: Vod, all: Map<Int, List<Episode>>, e: Episode, art: String) {
    val p = remember(act.refresh) { Repo.episodeUrl(e)?.let { Repo.progressFor(it) } }
    val watched = p?.done == true
    val partial = p?.takeIf { !it.done && it.t > 30 && it.d > 0 }
    val meta = listOfNotNull("Episode ${e.num}", epMinutes(e.duration)?.let { runtime(it) },
        if (watched) "Watched" else partial?.let { "${runtime(((it.d - it.t) / 60).toInt().coerceAtLeast(1))} left" }).joinToString("  ·  ")
    FocusCard(Modifier.padding(top = 10.dp).fillMaxWidth(.82f).height(EP_W * 9 / 16 + 16.dp), scale = 1.02f, restoreKey = "ep:" + e.id, label = "Episode ${e.num}, ${e.title}",
        onClick = { playEpisode(act, show, all, e, partial?.t ?: 0.0) }) {
        Row(Modifier.fillMaxSize().background(Color(0x1AFFFFFF)).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(EP_W).aspectRatio(16f / 9f).clip(RoundedCornerShape(7.dp))) {
                Picture(e.still.ifEmpty { art }.let { if (it.startsWith("http")) sharpStill(it) else it }, Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Tv.surface3, Tv.surface))))
                }
                if (e.still.isEmpty()) Box(Modifier.fillMaxSize().background(Color(0x73000000)), contentAlignment = Alignment.Center) {
                    Text("E${e.num}", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color(0xE6FFFFFF))
                }
                Box(Modifier.align(Alignment.BottomStart).padding(7.dp).size(28.dp).background(Color(0x99000000), RoundedCornerShape(50)), contentAlignment = Alignment.Center) {
                    Icon(if (watched) Icons.Default.Check else Icons.Default.PlayArrow, null, Modifier.size(17.dp), tint = Color.White)
                }
                if (partial != null) ProgressLine((partial.t / partial.d).toFloat(), Modifier.align(Alignment.BottomStart).fillMaxWidth())
            }
            Column(Modifier.padding(start = 18.dp, end = 12.dp).weight(1f)) {
                Text(meta, fontSize = Tv.label, color = if (watched) Tv.accent else Tv.muted, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(e.title, fontSize = Tv.body, color = Tv.text, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
                if (e.plot.isNotBlank()) Text(e.plot, fontSize = Tv.caption, color = Tv.text2, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 18.sp, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

// ---------- Search ----------

/** One search for everything: live channels (name, number, group), what's on TV now and later from the loaded guide,
 *  films and series. Channels and programmes come from in-memory indexes (SearchIndex.kt), so they appear as you type;
 *  films and series follow from the server. */
@Composable
fun SearchScreen(act: TvActivity) {
    var q by rememberSaveable { mutableStateOf("") }
    val field = remember { FocusRequester() }
    var fieldFocused by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(100); runCatching { field.requestFocus() } }
    val live by produceState(Repo.live, Repo.version) { value = runCatching { Repo.liveChannels() }.getOrNull() ?: value }
    val chanIndex by produceState<ChannelIndex?>(null, live) { value = live?.let { ChannelIndex.forLive(it) } }
    val guideTick = Repo.guideVersion
    val query = q.trim().replace(Regex("\\s+"), " ")
    var debounced by remember { mutableStateOf(query) }
    LaunchedEffect(query) { if (query.isEmpty()) debounced = "" else { delay(250); debounced = query } }

    // Channels and programmes: local, so they follow every pause in typing.
    var chans by remember { mutableStateOf<List<Channel>>(emptyList()) }
    var progs by remember { mutableStateOf<List<ProgrammeHit>>(emptyList()) }
    LaunchedEffect(debounced, chanIndex) {
        val ix = chanIndex
        chans = if (debounced.isEmpty() || ix == null) emptyList() else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { ix.search(debounced, 40) }
        if (chans.isNotEmpty()) Repo.requestGuide(chans)   // what's on for the channels found (3-hourly at most per channel)
    }
    LaunchedEffect(debounced, live, guideTick) {
        val l = live
        progs = if (debounced.length < 2 || l == null) emptyList() else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { ProgrammeIndex.forGuide(l, guideTick).search(debounced, 40) }
    }
    // Films and series: the server's search, two letters or more.
    var vods by remember { mutableStateOf<Pair<List<Vod>, List<Vod>>?>(null) }
    var vodBusy by remember { mutableStateOf(false) }
    LaunchedEffect(debounced) {
        if (debounced.length < 2) { vods = null; vodBusy = false; return@LaunchedEffect }
        vodBusy = true
        try { vods = runCatching { Repo.search(debounced) }.getOrNull()?.let { (m, s) -> m.take(40) to s.take(40) } ?: (emptyList<Vod>() to emptyList()) } finally { vodBusy = false }
    }
    val recent = remember(act.refresh, Repo.version, live) { Repo.recentChannels().map { liveFromSync(it) }.filter { it.url.isNotEmpty() } }
    LaunchedEffect(recent) { if (recent.isNotEmpty()) Repo.requestGuide(recent) }

    var chanMenu by remember { mutableStateOf<Channel?>(null) }
    val settled = debounced == query
    // The keyboard's Search key: search now, close the keyboard and move to the first results once they're there.
    val focusMgr = androidx.compose.ui.platform.LocalFocusManager.current
    val kb = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    var submits by remember { mutableIntStateOf(0) }
    fun submit() { if (query.isNotEmpty()) { debounced = query; submits++ } else kb?.hide() }
    LaunchedEffect(submits) {
        if (submits == 0) return@LaunchedEffect
        kb?.hide()
        val until = System.currentTimeMillis() + 2_500
        while (chans.isEmpty() && progs.isEmpty() && (vodBusy || vods == null) && System.currentTimeMillis() < until) delay(80)
        delay(60)
        if (chans.isEmpty() && progs.isEmpty() && vods?.let { it.first.isEmpty() && it.second.isEmpty() } != false) delay(400)   // films/series still arriving
        focusMgr.moveFocus(androidx.compose.ui.focus.FocusDirection.Down)
    }
    LazyColumn(Modifier.fillMaxSize(), state = rememberPageState(), contentPadding = PaddingValues(top = Tv.topMargin, bottom = 56.dp)) {
        item {
            Row(Modifier.padding(horizontal = Tv.margin).fillMaxWidth(.66f).background(if (fieldFocused) Tv.surface2 else Tv.surface, RoundedCornerShape(14.dp))
                .border(2.dp, if (fieldFocused) Tv.focusRing else Color.Transparent, RoundedCornerShape(14.dp)).padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Search, null, tint = Tv.muted, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(12.dp))
                Box(Modifier.weight(1f)) {
                    if (q.isEmpty()) Text("Search channels, TV programmes, movies and series", color = Tv.muted, fontSize = Tv.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    BasicTextField(q, { q = it }, singleLine = true, textStyle = TextStyle(color = Tv.text, fontSize = Tv.body), cursorBrush = SolidColor(Tv.accent),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { submit() }, onDone = { submit() }, onGo = { submit() }),
                        modifier = Modifier.fillMaxWidth().focusRequester(field).onFocusChanged { fieldFocused = it.isFocused }.searchFieldKeys { submit() })
                }
            }
        }
        if (query.isEmpty()) {
            if (recent.isNotEmpty()) { item(key = "recent-t") { RowTitle("Recent channels") }; item(key = "recent") { ChannelRowCards(act, recent, guideTick) { chanMenu = it } } }
            else item { Box(Modifier.fillMaxWidth().padding(top = 120.dp)) { EmptyState(Icons.Default.Search, "Find anything", "Channels, what's on TV, movies and series, all at once") } }
            return@LazyColumn
        }
        val v = vods
        if (chans.isNotEmpty()) { item(key = "ch-t") { RowTitle("Live channels", count = chans.size) }; item(key = "ch") { ChannelRowCards(act, chans, guideTick) { chanMenu = it } } }
        if (progs.isNotEmpty()) {
            item(key = "pg-t") { RowTitle("On TV now and later", extra = "from the TV guide", count = progs.size) }
            item(key = "pg") { CardRow(progs, { "${it.channel.url}|${it.p.start}" }) { h, _ -> SearchProgrammeCard(act, h) { chanMenu = h.channel } } }
        }
        if (v != null && v.first.isNotEmpty()) { item(key = "mv-t") { RowTitle("Movies", count = v.first.size) }; item(key = "mv") { CardRow(v.first, { it.id }) { m, _ -> PosterCard(m) { act.go(Screen.Details(m)) } } } }
        if (v != null && v.second.isNotEmpty()) { item(key = "sr-t") { RowTitle("Series", count = v.second.size) }; item(key = "sr") { CardRow(v.second, { it.id }) { s, _ -> PosterCard(s) { act.go(Screen.Details(s)) } } } }
        if (vodBusy || !settled) { item(key = "sk-t") { SkeletonTitle() }; item(key = "sk") { SkeletonRow() } }
        else if (chans.isEmpty() && progs.isEmpty() && (v == null || (v.first.isEmpty() && v.second.isEmpty())))
            item { Box(Modifier.fillMaxWidth().padding(top = 120.dp)) { EmptyState(Icons.Default.SearchOff, "Nothing matches \"$query\"", "Try a shorter word, a channel number or a programme name") } }
    }
    chanMenu?.let { c -> ChannelOptions(act, c) { chanMenu = null } }
}

/** A programme from the guide: the channel's logo, the title, and when it's on. OK on one that's on now plays the
 *  channel; one later shows its time (hold OK for the channel's options). */
@Composable
private fun SearchProgrammeCard(act: TvActivity, h: ProgrammeHit, onLong: () -> Unit) {
    val c = h.channel; val p = h.p
    val now = System.currentTimeMillis()
    val onAir = h.onAir(now)
    val day = if (!onAir && java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.UK).let { it.format(java.util.Date(p.start)) != it.format(java.util.Date(now)) }) "Tomorrow " else ""
    val sub = (if (onAir) "Now · until ${hhmm(p.end)}" else "$day${hhmm(p.start)}–${hhmm(p.end)}") + " · " + c.name
    Column(Modifier.width(Tv.channel)) {
        FocusCard(Modifier.width(Tv.channel).aspectRatio(16f / 9f), label = "${p.title}, $sub", onLongClick = onLong, onClick = { if (onAir) act.playLive(listOf(c), 0) }) {
            Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Tv.surface3, Tv.surface))), contentAlignment = Alignment.Center) {
                if (c.logo.isBlank()) Text(initials(c.name), fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Tv.muted)
                else Picture(Repo.logo(c.logo), Modifier.fillMaxSize().padding(16.dp).background(Color.Transparent), ContentScale.Fit) {
                    Text(initials(c.name), fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Tv.muted)
                }
                Box(Modifier.align(Alignment.TopStart).padding(6.dp)) { Tag(if (onAir) "LIVE" else "$day${hhmm(p.start)}".trim(), bg = if (onAir) Tv.accentDeep else Color(0xB3000000), color = Tv.text, size = 11.sp) }
                if (onAir) ProgressLine((now - p.start).toFloat() / (p.end - p.start), Modifier.align(Alignment.BottomStart).fillMaxWidth(), 3.dp)
            }
        }
        Caption(p.title, sub, Tv.channel)
    }
}

// ---------- options (hold OK) ----------

@Composable
fun Options(title: String, options: List<Pair<String, () -> Unit>>, onDismiss: () -> Unit) {
    val first = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.freshOkOnly().width(440.dp).background(Tv.surface, Tv.bigShape).border(1.dp, Tv.line, Tv.bigShape).padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, fontSize = Tv.body, fontWeight = FontWeight.SemiBold, color = Tv.text, modifier = Modifier.padding(start = 4.dp, bottom = 10.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
            options.forEachIndexed { i, (label, fn) ->
                ListItem(selected = false, onClick = fn, headlineContent = { Text(label, fontSize = Tv.bodySmall, color = if (label.startsWith("Remove")) Tv.danger else Color.Unspecified) },
                    modifier = if (i == 0) Modifier.focusRequester(first) else Modifier)
            }
            ListItem(selected = false, onClick = onDismiss, headlineContent = { Text("Cancel", fontSize = Tv.bodySmall) })
        }
        LaunchedEffect(Unit) { delay(50); runCatching { first.requestFocus() } }
    }
}
