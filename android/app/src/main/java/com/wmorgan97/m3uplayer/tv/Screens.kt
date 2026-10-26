package com.wmorgan97.m3uplayer.tv

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.border
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.*
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

// ---------- Home ----------

@Composable
fun HomeScreen(act: TvActivity) {
    val refresh = act.refresh
    val movies by produceState<Overview?>(null) { value = runCatching { Repo.overview("movies") }.getOrNull() }
    val series by produceState<Overview?>(null) { value = runCatching { Repo.overview("series") }.getOrNull() }
    val cw = remember(refresh) { Repo.continueWatching() }
    val favs = remember(refresh) { Repo.favorites().filter { it.optString("url").isNotEmpty() } }
    val recent = remember(refresh) { Repo.recentChannels() }
    var menuFor by remember { mutableStateOf<Progress?>(null) }
    val scope = rememberCoroutineScope()

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 48.dp)) {
        item { Hero(act, ((movies?.trending ?: emptyList()).take(4) + (series?.trending ?: emptyList()).take(3)).filter { it.backdrop.isNotEmpty() }) }
        if (cw.isNotEmpty()) {
            item { RowTitle("Continue watching") }
            item {
                CardRow(cw) { p, _ ->
                    val it = p.item
                    WideCard(it.optString("logo"), progressTitle(it), "${clock((p.d - p.t).toLong())} left", (p.t / p.d).toFloat(),
                        onLong = { menuFor = p }) { resume(act, p) }
                }
            }
        }
        if (favs.isNotEmpty()) {
            item { RowTitle("Favourite channels") }
            item { CardRow(favs) { c, i -> ChannelCard(c.optString("name"), c.optString("logo"), "") { act.play(favs.map { liveFromSync(it) }, i) } } }
        }
        if (recent.isNotEmpty()) {
            item { RowTitle("Recently watched channels") }
            item { CardRow(recent) { c, i -> ChannelCard(c.optString("name"), c.optString("logo"), prettyGroup(c.optString("group"))) { act.play(recent.map { liveFromSync(it) }, i) } } }
        }
        movies?.let { m ->
            if (m.trending.isNotEmpty()) { item { RowTitle("Top 10 movies this week", "from TMDB") }; item { CardRow(m.trending.take(10)) { v, i -> PosterCard(v, rank = i + 1) { act.go(Screen.Details(v)) } } } }
            if (m.top.isNotEmpty()) { item { RowTitle("Popular movies") }; item { CardRow(m.top.take(30)) { v, _ -> PosterCard(v) { act.go(Screen.Details(v)) } } } }
            if (m.fresh.isNotEmpty()) { item { RowTitle("New movies") }; item { CardRow(m.fresh.take(30)) { v, _ -> PosterCard(v) { act.go(Screen.Details(v)) } } } }
        }
        series?.let { s ->
            if (s.trending.isNotEmpty()) { item { RowTitle("Top 10 series this week", "from TMDB") }; item { CardRow(s.trending.take(10)) { v, i -> PosterCard(v, rank = i + 1) { act.go(Screen.Details(v)) } } } }
            if (s.top.isNotEmpty()) { item { RowTitle("Popular series") }; item { CardRow(s.top.take(30)) { v, _ -> PosterCard(v) { act.go(Screen.Details(v)) } } } }
        }
        if (movies == null && series == null) item { Text("Loading…", Modifier.padding(48.dp), color = Muted) }
    }

    menuFor?.let { p ->
        Options(progressTitle(p.item), listOf(
            "Resume" to { menuFor = null; resume(act, p) },
            "Start over" to { menuFor = null; resume(act, p, fromStart = true) },
            "Remove from Continue watching" to { menuFor = null; scope.launch { Repo.saveProgress(p.url, p.t, p.d, p.item, hide = true); act.refresh++ } },
        )) { menuFor = null }
    }
}

fun progressTitle(it: JSONObject): String {
    val name = it.optString("name"); val m = vodMeta(name)
    return if (it.optString("kind") == "episode" && m.episode != null) "${displayTitle(name)} · S${m.season ?: 1} E${m.episode}" else displayTitle(name)
}

private fun liveFromSync(o: JSONObject): JSONObject {
    val c = Channel(o.optString("name"), o.optString("url"), o.optString("group"), o.optString("logo"), o.optString("tvgId"), "", 0)
    return channelItem(c)
}

private fun resume(act: TvActivity, p: Progress, fromStart: Boolean = false) {
    val it = p.item
    val title = progressTitle(it)
    val item = JSONObject().put("url", p.url).put("title", title).put("subtitle", if (it.optString("kind") == "episode") epTitle(it.optString("name")) else "")
        .put("live", false).put("start", if (fromStart) 0.0 else p.t).put("art", it.optString("logo")).put("listTitle", title).put("sync", it)
    act.play(listOf(item), 0)
}

/** The featured banner: TMDB's trending titles with their backdrops. */
@Composable
fun Hero(act: TvActivity, items: List<Vod>) {
    if (items.isEmpty()) { Spacer(Modifier.height(24.dp)); return }
    // Picked: a white frame; OK opens the title on show; left/right change slide.
    var focused by remember { mutableStateOf(false) }
    val state = rememberCarouselState()
    Carousel(itemCount = items.size, carouselState = state,
        modifier = Modifier.fillMaxWidth().height(360.dp).padding(start = 48.dp, end = 48.dp, top = 28.dp)
            .onFocusChanged { focused = it.hasFocus }
            .border(if (focused) 4.dp else 0.dp, if (focused) Color.White else Color.Transparent, RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp))
            .onKeyEvent { e ->
                if (e.type == KeyEventType.KeyUp && (e.key == Key.DirectionCenter || e.key == Key.Enter)) { items.getOrNull(state.activeItemIndex)?.let { act.go(Screen.Details(it)) }; true } else false
            },
        contentTransformStartToEnd = fadeIn() togetherWith fadeOut(), contentTransformEndToStart = fadeIn() togetherWith fadeOut(), autoScrollDurationMillis = 8000) { i ->
        val v = items[i]
        Box(Modifier.fillMaxSize()) {
            AsyncImage(v.backdrop, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color(0xF00E1014), Color(0x990E1014), Color.Transparent))))
            Column(Modifier.align(Alignment.CenterStart).padding(start = 40.dp).fillMaxWidth(.55f)) {
                Text("#${i + 1} TRENDING ${if (v.kind == "series") "SERIES" else "MOVIE"}", fontSize = 12.sp, color = Accent, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                Spacer(Modifier.height(8.dp))
                Text(v.title, fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(6.dp))
                Text(listOfNotNull(v.year?.toString(), v.rating?.let { "★ %.1f".format(it) }).joinToString("   "), fontSize = 14.sp, color = Muted)
                Spacer(Modifier.height(10.dp))
                Text(v.overview, fontSize = 14.sp, color = Color(0xFFD5DAE3), maxLines = 3, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(16.dp))
                Text(if (v.kind == "series") "OK  ·  Episodes" else "OK  ·  More info", fontSize = 13.sp, color = if (focused) Color.White else Muted)
            }
        }
    }
}

// ---------- Live TV ----------

@Composable
fun LiveScreen(act: TvActivity) {
    val live by produceState<Repo.Live?>(Repo.live) { value = runCatching { Repo.liveChannels() }.getOrNull() ?: Repo.Live(emptyList(), emptyList(), emptyList()) }
    val l = live
    if (l == null || l.channels.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(if (l == null) "Loading channels…" else "No live channels in your playlists.", color = Muted, fontSize = 18.sp) }
        return
    }
    val byGroup = remember(l) { l.channels.groupBy { it.group } }
    val groups = remember(l) { listOf("All channels") + l.groups.filter { it.isNotBlank() } }
    var group by remember { mutableStateOf(groups.getOrElse(1) { groups[0] }) }
    val list = remember(group, l) { if (group == "All channels") l.channels else byGroup[group] ?: emptyList() }
    var guideTick by remember { mutableIntStateOf(0) }
    val listState = rememberLazyListState()
    LaunchedEffect(group) { listState.scrollToItem(0); if (Repo.loadGuide(list.take(300))) guideTick++ }
    LaunchedEffect(Unit) { while (true) { delay(60_000); guideTick++ } }   // keep "now" and the progress bars current
    // More of the guide as you scroll down a long group.
    val firstVisible by remember { derivedStateOf { listState.firstVisibleItemIndex } }
    LaunchedEffect(firstVisible / 100) { if (firstVisible > 200 && Repo.loadGuide(list.drop(firstVisible).take(300))) guideTick++ }

    Row(Modifier.fillMaxSize().padding(top = 24.dp)) {
        // Groups: moving onto one shows its channels (as on TiviMate).
        LazyColumn(Modifier.width(300.dp).fillMaxHeight().padding(start = 24.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
            items(groups) { g ->
                ListItem(selected = g == group, onClick = { group = g }, modifier = Modifier.onFocusChanged { if (it.isFocused && g != group) group = g },
                    headlineContent = { Text(prettyGroup(g), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 15.sp) },
                    trailingContent = { Text("${(if (g == "All channels") l.channels.size else byGroup[g]?.size ?: 0)}", fontSize = 12.sp, color = Muted) })
            }
        }
        LazyColumn(Modifier.weight(1f).fillMaxHeight().padding(start = 16.dp, end = 32.dp), state = listState, contentPadding = PaddingValues(vertical = 8.dp)) {
            itemsIndexed(list, key = { i, c -> "$i|${c.url}" }) { i, c -> ChannelRow(c, guideTick) { act.play(list.map { channelItem(it) }, i) } }
        }
    }
}

@Composable
fun ChannelRow(c: Channel, tick: Int, onClick: () -> Unit) {
    val (now, next) = remember(tick, c) { Repo.nowNext(c) }
    ListItem(selected = false, onClick = onClick,
        leadingContent = {
            Box(Modifier.size(width = 72.dp, height = 44.dp).background(Surface2, RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) {
                Text(initials(c.name), fontSize = 14.sp, color = Muted, fontWeight = FontWeight.Bold)
                AsyncImage(Repo.img(c.logo, 120), null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(4.dp))
            }
        },
        headlineContent = { Text("${c.num}  ${c.name}", maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 16.sp, fontWeight = FontWeight.Medium) },
        supportingContent = {
            Column {
                Text(if (now != null) "${now.title}   ${hhmm(now.start)}–${hhmm(now.end)}" else "", maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp)
                if (now != null) {
                    val p = ((System.currentTimeMillis() - now.start).toFloat() / (now.end - now.start)).coerceIn(0f, 1f)
                    Box(Modifier.padding(top = 4.dp).width(220.dp).height(3.dp).background(Color(0x33FFFFFF))) { Box(Modifier.fillMaxHeight().fillMaxWidth(p).background(Accent)) }
                }
            }
        },
        trailingContent = { if (next != null) Text("Next: ${next.title}", fontSize = 12.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 260.dp)) })
}

// ---------- Movies / Series ----------

@Composable
fun VodScreen(act: TvActivity, kind: String) {
    val ov by produceState<Overview?>(null) { value = runCatching { Repo.overview(kind) }.getOrNull() }
    val o = ov ?: run { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Loading…", color = Muted) }; return }
    val what = if (kind == "series") "series" else "movies"
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 48.dp)) {
        item { Hero(act, o.trending.filter { it.backdrop.isNotEmpty() }.take(6)) }
        if (o.trending.isNotEmpty()) { item { RowTitle("Top 10 $what this week", "from TMDB") }; item { CardRow(o.trending.take(10)) { v, i -> PosterCard(v, rank = i + 1) { act.go(Screen.Details(v)) } } } }
        if (o.top.isNotEmpty()) { item { RowTitle("Popular now") }; item { CardRow(o.top.take(40)) { v, _ -> PosterCard(v) { act.go(Screen.Details(v)) } } } }
        if (o.fresh.isNotEmpty()) { item { RowTitle("New releases") }; item { CardRow(o.fresh.take(40)) { v, _ -> PosterCard(v) { act.go(Screen.Details(v)) } } } }
        for ((id, name) in o.groups) {
            item(key = id) { GroupRow(act, kind, id, name) }
        }
    }
}

/** A TMDB list / genre: its first titles, then a "See all" tile into the full grid. */
@Composable
fun GroupRow(act: TvActivity, kind: String, id: String, name: String) {
    val items by produceState<List<Vod>?>(null, id) { value = runCatching { Repo.group(kind, id) }.getOrElse { emptyList() } }
    val l = items ?: emptyList()
    Column {
        RowTitle(name, if (items == null) "Loading…" else "${l.size}")
        if (l.isNotEmpty()) LazyRow(contentPadding = PaddingValues(horizontal = 48.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            items(l.take(20)) { v -> PosterCard(v) { act.go(Screen.Details(v)) } }
            if (l.size > 20) item {
                Card(onClick = { act.go(Screen.Grid(kind, id, name)) }, border = cardBorder(), scale = CardDefaults.scale(focusedScale = 1.06f), modifier = Modifier.width(132.dp).aspectRatio(2f / 3f)) {
                    Box(Modifier.fillMaxSize().background(Surface2), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("See all", fontSize = 16.sp, fontWeight = FontWeight.SemiBold); Text("${l.size}", color = Muted, fontSize = 13.sp) }
                    }
                }
            }
        } else if (items == null) Spacer(Modifier.height(240.dp))
    }
}

@Composable
fun GridScreen(act: TvActivity, kind: String, id: String, title: String) {
    val items by produceState<List<Vod>?>(null) { value = runCatching { Repo.group(kind, id) }.getOrElse { emptyList() } }
    Column(Modifier.fillMaxSize().padding(top = 28.dp)) {
        Text(title, fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 48.dp, bottom = 16.dp))
        LazyVerticalGrid(GridCells.Fixed(7), contentPadding = PaddingValues(start = 48.dp, end = 48.dp, bottom = 48.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            itemsIndexed(items ?: emptyList()) { _, v -> PosterCard(v, width = 140.dp) { act.go(Screen.Details(v)) } }
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
    LaunchedEffect(Unit) { delay(80); runCatching { playFocus.requestFocus() } }
    Box(Modifier.fillMaxSize()) {
        if (backdrop.isNotEmpty()) AsyncImage(backdrop, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color(0xFA0E1014), Color(0xD90E1014), Color(0x660E1014)))))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xF00E1014)), startY = 300f)))
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 48.dp, end = 48.dp, top = 48.dp, bottom = 48.dp)) {
            item {
                Row {
                    AsyncImage(Repo.img(v.poster, 300), null, contentScale = ContentScale.Crop, modifier = Modifier.width(170.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(10.dp)).background(Surface2))
                    Spacer(Modifier.width(28.dp))
                    Column(Modifier.fillMaxWidth(.7f)) {
                        Text(v.title, fontSize = 36.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 2)
                        info?.optString("tagline")?.takeIf { it.isNotBlank() }?.let { Text(it, fontSize = 15.sp, color = Muted, modifier = Modifier.padding(top = 4.dp)) }
                        Spacer(Modifier.height(10.dp))
                        val genres = info?.optJSONArray("genres")?.let { g -> (0 until g.length()).joinToString(", ") { g.getString(it) } }
                        val vote = info?.optDouble("vote")?.takeIf { !it.isNaN() && it > 0 } ?: v.rating
                        Text(listOfNotNull(v.year?.toString(), info?.optInt("runtime")?.takeIf { it > 0 }?.let { runtime(it) }, genres, vote?.let { "★ %.1f".format(it) },
                            if (v.copies > 1) "${v.copies} versions" else null).joinToString("   ·   "), fontSize = 15.sp, color = Color(0xFFD5DAE3))
                        Spacer(Modifier.height(14.dp))
                        Text(overview, fontSize = 16.sp, color = Color(0xFFD5DAE3), maxLines = 5, overflow = TextOverflow.Ellipsis, lineHeight = 23.sp)
                        Spacer(Modifier.height(20.dp))
                        if (v.kind == "movie") {
                            val url = Repo.movieUrl(v) ?: ""
                            val p = remember(refresh) { Repo.progressFor(url)?.takeIf { !it.done && it.t > 30 } }
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Button(onClick = { act.play(listOf(movieItem(v, url, p?.t ?: 0.0)), 0) }, modifier = Modifier.focusRequester(playFocus)) {
                                    Text(if (p != null) "Resume  ·  ${clock((p.d - p.t).toLong())} left" else "▶  Play")
                                }
                                if (p != null) OutlinedButton(onClick = { act.play(listOf(movieItem(v, url, 0.0)), 0) }) { Text("Start over") }
                            }
                        } else {
                            val all = eps
                            val next = remember(all, refresh) { all?.let { nextEpisode(it) } }
                            Button(onClick = { next?.let { (e, start) -> playEpisode(act, v, all!!, e, start) } }, modifier = Modifier.focusRequester(playFocus), enabled = next != null) {
                                Text(when { all == null -> "Loading episodes…"; next == null -> "No episodes"; next.second > 0 -> "Continue S${next.first.season} E${next.first.num}"; else -> "▶  Play S${next.first.season} E${next.first.num}" })
                            }
                        }
                    }
                }
            }
            val all = eps
            if (v.kind == "series" && !all.isNullOrEmpty()) {
                item { Seasons(act, v, all) }
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

@Composable
private fun Seasons(act: TvActivity, show: Vod, all: Map<Int, List<Episode>>) {
    var season by remember { mutableIntStateOf(all.keys.first()) }
    Column(Modifier.padding(top = 28.dp)) {
        if (all.size > 1) LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(all.keys.toList()) { s ->
                FilterChip(selected = s == season, onClick = { season = s }, modifier = Modifier.onFocusChanged { if (it.isFocused) season = s }) { Text("Season $s") }
            }
        }
        Spacer(Modifier.height(16.dp))
        val list = all[season] ?: emptyList()
        LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            items(list) { e ->
                val p = remember(act.refresh) { Repo.episodeUrl(e)?.let { Repo.progressFor(it) } }
                WideCard(e.still.ifEmpty { show.poster }, "${e.num}. ${e.title}", listOfNotNull(e.duration.takeIf { it.isNotBlank() }, if (p?.done == true) "Watched" else null).joinToString(" · "),
                    p?.takeIf { !it.done && it.d > 0 }?.let { (it.t / it.d).toFloat() }, width = 260.dp) {
                    playEpisode(act, show, all, e, p?.takeIf { !it.done && it.t > 30 }?.t ?: 0.0)
                }
            }
        }
    }
}

// ---------- Search ----------

@Composable
fun SearchScreen(act: TvActivity) {
    var q by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<Triple<List<Channel>, List<Vod>, List<Vod>>?>(null) }
    var busy by remember { mutableStateOf(false) }
    val field = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(100); runCatching { field.requestFocus() } }
    LaunchedEffect(q) {
        val t = q.trim()
        if (t.length < 2) { results = null; return@LaunchedEffect }
        delay(350); busy = true
        val chans = (Repo.live ?: runCatching { Repo.liveChannels() }.getOrNull())?.channels?.filter { it.name.contains(t, true) }?.take(40) ?: emptyList()
        val (m, s) = Repo.search(t)
        results = Triple(chans, m, s); busy = false
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 36.dp, bottom = 48.dp)) {
        item {
            Box(Modifier.padding(horizontal = 48.dp).fillMaxWidth(.7f).background(Surface1, RoundedCornerShape(14.dp)).padding(horizontal = 20.dp, vertical = 16.dp)) {
                if (q.isEmpty()) Text("Search channels, movies and series", color = Muted, fontSize = 20.sp)
                BasicTextField(q, { q = it }, singleLine = true, textStyle = TextStyle(color = Color.White, fontSize = 20.sp), cursorBrush = SolidColor(Accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { }),
                    modifier = Modifier.fillMaxWidth().focusRequester(field))
            }
        }
        val r = results
        if (busy && r == null) item { Text("Searching…", Modifier.padding(48.dp), color = Muted) }
        if (r != null) {
            if (r.first.isEmpty() && r.second.isEmpty() && r.third.isEmpty()) item { Text("Nothing matches \"${q.trim()}\".", Modifier.padding(48.dp), color = Muted) }
            if (r.first.isNotEmpty()) { item { RowTitle("Channels", "${r.first.size}") }; item { CardRow(r.first) { c, i -> ChannelCard(c.name, c.logo, prettyGroup(c.group)) { act.play(r.first.map { channelItem(it) }, i) } } } }
            if (r.second.isNotEmpty()) { item { RowTitle("Movies", "${r.second.size}") }; item { CardRow(r.second) { v, _ -> PosterCard(v) { act.go(Screen.Details(v)) } } } }
            if (r.third.isNotEmpty()) { item { RowTitle("Series", "${r.third.size}") }; item { CardRow(r.third) { v, _ -> PosterCard(v) { act.go(Screen.Details(v)) } } } }
        }
    }
}

// ---------- options (hold OK) ----------

@Composable
fun Options(title: String, options: List<Pair<String, () -> Unit>>, onDismiss: () -> Unit) {
    val first = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.width(460.dp).background(Surface1, RoundedCornerShape(18.dp)).padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 8.dp), maxLines = 2)
            options.forEachIndexed { i, (label, fn) ->
                ListItem(selected = false, onClick = fn, headlineContent = { Text(label, color = if (label.startsWith("Remove")) Color(0xFFE5484D) else Color.Unspecified) },   // unspecified: dark on the white highlight
                    modifier = if (i == 0) Modifier.focusRequester(first) else Modifier)
            }
            ListItem(selected = false, onClick = onDismiss, headlineContent = { Text("Cancel") })
        }
        LaunchedEffect(Unit) { delay(50); runCatching { first.requestFocus() } }
    }
}
