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

// A film or series: its details, play / resume, seasons and episodes, more like this.

// ---------- Details ----------

@Composable
fun DetailsScreen(act: TvActivity, v: Vod) {
    var infoDone by remember { mutableStateOf(false) }
    // (Starts from what's already in memory, e.g. from the spotlight, so the title logo is there on the first frame.)
    val info by produceState(Repo.cachedTmdbInfo(v)) { value = Repo.tmdbInfo(v); infoDone = true }
    // IMDb / Rotten Tomatoes (OMDb), only with the server's key; asked for here, on opening the page.
    val ratings by produceState<JSONObject?>(null, info?.optString("imdb")) { value = info?.optString("imdb")?.let { Repo.ratings(it) } }
    // "More like this": titles from your catalogue in this one's first matching TMDB genre (else what's popular).
    val similar by produceState<List<Vod>?>(null, infoDone) {
        if (!infoDone) return@produceState
        val kind = if (v.kind == "series") "series" else "movies"
        val ov = runCatching { Repo.overview(kind) }.getOrNull() ?: run { value = emptyList(); return@produceState }
        val genres = info?.optJSONArray("genres")?.let { g -> (0 until g.length()).map { g.getString(it) } } ?: emptyList()
        val gid = genres.firstNotNullOfOrNull { g -> ov.groups.firstOrNull { (_, n) -> n.equals(g, true) || n.contains(g, true) || g.contains(n, true) }?.first }
        val pool = gid?.let { runCatching { Repo.group(kind, it) }.getOrNull() }?.takeIf { it.size > 3 } ?: (ov.trending + ov.top)
        value = pool.filter { it.id != v.id && !it.title.equals(v.title, true) }.distinctBy { it.title.lowercase() }.take(20)
    }
    val eps by produceState<Map<Int, List<Episode>>?>(null) { if (v.kind == "series") value = runCatching { Repo.episodes(v) }.getOrElse { emptyMap() } }
    val refresh = act.refresh
    val backdrop = v.backdrop.ifEmpty { info?.optString("backdrop") ?: "" }
    val overview = (info?.optString("overview")?.ifBlank { null } ?: v.overview).trim()
    val playFocus = remember { FocusRequester() }
    val seasonFocus = remember { FocusRequester() }    // always on the chip of the season shown
    val firstEpFocus = remember { FocusRequester() }   // the first episode of the season shown
    LaunchedEffect(eps) { delay(120); runCatching { playFocus.requestFocus() } }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // Arriving: the header rises 8 dp and fades in over durMed (once; coming back from the player it's simply there).
    var entered by rememberSaveable(v.id) { mutableStateOf(false) }
    val enter = remember { androidx.compose.animation.core.Animatable(if (entered) 1f else 0f) }
    LaunchedEffect(Unit) { if (!entered) { entered = true; enter.animateTo(1f, androidx.compose.animation.core.tween(Tv.durMed, easing = Tv.easeEnter)) } }
    var season by rememberSaveable(v.id) { mutableStateOf<Int?>(null) }   // null: the season you're watching
    // The season on screen, with TMDB's plots and sharp stills merged in once they arrive (same Episode ids, so play works).
    // TMDB's details are kept with the season they belong to: a season switch never shows the last season's plots.
    val shownSeason = eps?.takeIf { it.isNotEmpty() }?.let { all -> season ?: (nextEpisode(all)?.first?.season?.takeIf { it in all } ?: all.keys.first()) }
    val tmEps by produceState<Pair<Int, Map<Int, JSONObject>>?>(null, v.id, shownSeason) { val s = shownSeason ?: return@produceState; value = s to runCatching { Repo.tmdbEpisodes(v, s) }.getOrElse { emptyMap() } }
    val merged = remember(eps, tmEps, shownSeason) { eps?.let { all -> val t = tmEps?.takeIf { it.first == shownSeason }?.second
        if (shownSeason == null || t.isNullOrEmpty()) all else all + (shownSeason to (all[shownSeason] ?: emptyList()).map { Repo.withTmdb(it, t[it.num]) }) } }
    // Every episode watched: the series is finished, so it leaves Watch later (on every device).
    LaunchedEffect(eps) {
        val all = eps?.values?.flatten().orEmpty()
        if (all.isNotEmpty() && Repo.isWatchLater(v) && all.all { e -> Repo.episodeUrl(e)?.let { Repo.progressFor(it)?.done } == true }) Repo.toggleWatchLater(v, on = false)
    }
    val multiSeason = (merged?.size ?: 0) > 1
    val hasEpisodes = merged?.get(shownSeason)?.isNotEmpty() == true
    Box(Modifier.fillMaxSize()) {
        // The spotlight's backdrop (right-aligned, fading into the page): the art you opened it from carries over,
        // already in memory, and TMDB's arrives with a short cross-fade when the row didn't know it.
        CinematicBackdrop(backdrop, 500, Modifier.align(Alignment.TopEnd).fillMaxWidth(.74f).aspectRatio(16f / 9f))
        CompositionLocalProvider(androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides CenteredBringIntoView) {
        // Overscan-safe on all sides: 48dp left/right, 36dp top, 40dp bottom (5% of 960x540dp is 48 x 27).
        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(start = Tv.margin, end = Tv.margin, top = 36.dp, bottom = 40.dp)) {
            item(key = "head") {
                // Back on the buttons: the page returns to the top (the episodes slide the header away, Apple TV style).
                Row(Modifier.graphicsLayer { alpha = enter.value; translationY = (1f - enter.value) * 8.dp.toPx() }.onFocusChanged { if (it.hasFocus && listState.firstVisibleItemIndex > 0) scope.launch { listState.animateScrollToItem(0) } }) {
                    Picture(Repo.img(v.poster, 300), Modifier.width(150.dp).aspectRatio(2f / 3f).clip(Tv.shape)) {
                        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Tv.surface3, Tv.surface))), contentAlignment = Alignment.Center) {
                            Text(v.title, Modifier.padding(10.dp), color = Tv.text2, fontSize = Tv.caption, fontWeight = FontWeight.SemiBold, maxLines = 4, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Spacer(Modifier.width(28.dp))
                    Column(Modifier.weight(1f).widthIn(max = 560.dp).padding(end = 64.dp)) {
                        Text(if (v.kind == "series") "SERIES" else "MOVIE", fontSize = Tv.label, color = Tv.muted, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp, maxLines = 1,
                            modifier = Modifier.padding(bottom = 4.dp))
                        TitleLogo(Repo.logoImg(info?.optString("logo")), v.title, 330.dp, 76.dp, Modifier.padding(top = 4.dp, bottom = 6.dp)) {
                            Text(v.title, fontSize = Tv.display, fontWeight = Tv.wDisplay, color = Tv.text, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = Tv.displayLine)
                        }
                        info?.optString("tagline")?.takeIf { it.isNotBlank() }?.let { Text(it, fontSize = Tv.bodySmall, color = Tv.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp)) }
                        Spacer(Modifier.height(10.dp))
                        val genres = info?.optJSONArray("genres")?.let { g -> (0 until g.length()).map { g.getString(it) } } ?: emptyList()
                        val vote = info?.optDouble("vote")?.takeIf { !it.isNaN() && it > 0 } ?: v.rating
                        // Wraps onto a second line rather than running off the edge.
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            v.year?.let { Tag("$it") }
                            info?.optInt("runtime")?.takeIf { it > 0 }?.let { Tag(runtime(it)) }
                            vote?.let { Tag("★ %.1f".format(it), color = Tv.star) }
                            RatingsTag(ratings)
                            qualityTags(v.name, v.group).forEach { QualityTag(it) }
                            if (v.copies > 1) Tag("${v.copies} versions")
                            merged?.takeIf { it.size > 1 }?.let { Tag("${it.size} seasons") }
                            genres.take(3).forEach { Tag(it, color = Tv.muted, bg = Color(0x14FFFFFF)) }
                        }
                        Spacer(Modifier.height(12.dp))
                        when {
                            overview.isNotEmpty() -> Text(overview, fontSize = Tv.body, color = Tv.text2, maxLines = if (v.kind == "series") 3 else 4, overflow = TextOverflow.Ellipsis, lineHeight = Tv.bodyLine)
                            info == null -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {   // the plot is on its way
                                val b = shimmerBrush()
                                repeat(2) { k -> Box(Modifier.fillMaxWidth(if (k == 0) .9f else .6f).height(14.dp).clip(RoundedCornerShape(4.dp)).background(b)) }
                            }
                            // no plot anywhere: nothing, rather than an empty gap or a placeholder sentence
                        }
                        Spacer(Modifier.height(18.dp))
                        if (v.kind == "movie") {
                            val url = Repo.movieUrl(v) ?: ""
                            // The resume rule: started (past 5 s) and not finished carries on; finished plays from the start.
                            val p = remember(refresh) { Repo.progressFor(url)?.takeIf { resumePoint(it) != null } }
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                TvButton(if (p != null) "Resume  ·  ${clock((p.d - p.t).toLong().coerceAtLeast(0))} left" else "Play", { act.play(listOf(movieItem(v, url, p?.t ?: 0.0)), 0) },
                                    Modifier.focusRequester(playFocus), icon = Icons.Default.PlayArrow, style = TvButtonStyle.Primary)
                                if (p != null) TvButton("Start over", { act.play(listOf(movieItem(v, url, 0.0)), 0) }, icon = Icons.Default.Replay)
                                WatchLaterButton(v)
                            }
                            if (p != null && p.d > 0) TvProgress((p.t / p.d).toFloat().coerceIn(0f, 1f), Modifier.padding(top = 12.dp).width(240.dp))
                        } else {
                            val all = eps
                            val next = remember(all, refresh) { all?.let { nextEpisode(it) } }
                            // Down always lands on the season shown (never whichever chip happens to be nearest).
                            val down = Modifier.focusProperties { down = if (multiSeason) seasonFocus else if (hasEpisodes) firstEpFocus else FocusRequester.Default }
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            TvButton(when { all == null -> "Loading episodes…"; next == null -> "No episodes"; next.second > 0 -> "Continue S${next.first.season} E${next.first.num}"; else -> "Play S${next.first.season} E${next.first.num}" },
                                { next?.let { (e, start) -> playEpisode(act, v, all!!, e, start) } },
                                Modifier.focusRequester(playFocus).then(down),
                                icon = Icons.Default.PlayArrow, style = TvButtonStyle.Primary, enabled = next != null)
                            WatchLaterButton(v, down)
                            }
                        }
                        AlsoOn(info?.optJSONArray("providers"))
                    }
                }
            }
            val all = merged
            if (v.kind == "series") {
                if (all == null) item(key = "loading") {
                    Column(Modifier.padding(top = 26.dp)) {
                        Box(Modifier.width(120.dp).height(18.dp).clip(RoundedCornerShape(5.dp)).shimmer())
                        repeat(3) { EpisodeSkeleton() }
                    }
                }
                else if (all.isNotEmpty()) {
                    val shown = shownSeason ?: all.keys.first()
                    val list = all[shown] ?: emptyList()
                    item(key = "seasons") { SeasonHeader(all, shown, list.size, seasonFocus, playFocus, firstEpFocus) { season = it } }
                    itemsIndexed(list, key = { _, e -> "ep:" + e.id }) { i, e ->
                        EpisodeRow(act, v, all, e, backdrop.ifEmpty { v.poster },
                            if (i == 0) Modifier.focusRequester(firstEpFocus).focusProperties { up = if (multiSeason) seasonFocus else playFocus } else Modifier)
                    }
                }
            }
            similar?.takeIf { it.isNotEmpty() }?.let { more -> item(key = "more") { MoreLikeThis(act, more) } }
        }
        }
    }
}

/** "Also on": where the title streams in the UK (TMDB's watch providers): each service's small logo and name, then
 *  the JustWatch credit their terms ask for. Information only (not focusable); nothing when it isn't streaming. */
@Composable
private fun AlsoOn(arr: org.json.JSONArray?) {
    val list = remember(arr) { (0 until (arr?.length() ?: 0)).mapNotNull { arr!!.optJSONObject(it) }.filter { it.optString("name").isNotBlank() } }
    if (list.isEmpty()) return
    Column(Modifier.padding(top = 18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Also on", fontSize = Tv.label, color = Tv.muted, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(end = 2.dp))
            list.forEach { p ->
                val logo = p.optString("logo").takeIf { it.startsWith("http") }
                Row(Modifier.background(Color(0x14FFFFFF), RoundedCornerShape(50)).padding(start = if (logo != null) 3.dp else 10.dp, end = 10.dp, top = 3.dp, bottom = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (logo != null) {
                        Picture(Repo.img(logo, 120), Modifier.size(20.dp).clip(RoundedCornerShape(5.dp)))
                        Spacer(Modifier.width(7.dp))
                    }
                    Text(p.optString("name"), fontSize = Tv.label, color = Tv.text2, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
            }
        }
        Text("Streaming data: JustWatch", fontSize = 12.sp, color = Tv.muted.copy(alpha = .75f), modifier = Modifier.padding(top = 6.dp))
    }
}

/** Adds the title to Watch later or takes it off: a plus, then a tick once it's on the list (calm, never red). */
@Composable
private fun WatchLaterButton(v: Vod, modifier: Modifier = Modifier) {
    val on = remember(v.kind, v.id, Repo.version) { Repo.isWatchLater(v) }
    TvButton("Watch later", { Repo.toggleWatchLater(v, !on) }, modifier, icon = if (on) Icons.Default.Check else Icons.Default.Add)
}

/** A row of posters under the page. The page's own 48dp margins are the row's padding (it's widened by them), so the
 *  first poster lines up with the content above and a highlighted poster is never cut at either edge. */
@Composable
private fun MoreLikeThis(act: TvActivity, items: List<Vod>) {
    Column(Modifier.padding(top = 8.dp).layout { m, c ->
        val extra = (Tv.margin * 2).roundToPx()
        val p = m.measure(c.copy(minWidth = c.maxWidth + extra, maxWidth = c.maxWidth + extra))
        layout(c.maxWidth, p.height) { p.place(-extra / 2, 0) }
    }) {
        RowTitle("More like this")
        CardRow(items, { it.id }) { m, _ -> PosterCard(m) { act.go(Screen.Details(m)) } }
    }
}

/** The episode to start: the one you were part-way through, else the one after the last you finished, else the first. */
fun nextEpisode(all: Map<Int, List<Episode>>): Pair<Episode, Double>? {
    val flat = all.values.flatten()
    if (flat.isEmpty()) return null
    val prog = flat.mapNotNull { e -> Repo.episodeUrl(e)?.let { Repo.progressFor(it) }?.let { e to it } }
    val latest = prog.maxByOrNull { it.second.at } ?: return flat.first() to 0.0
    resumePoint(latest.second)?.let { return latest.first to it }
    val i = flat.indexOf(latest.first)
    return (flat.getOrNull(i + 1) ?: latest.first) to 0.0
}

fun playEpisode(act: TvActivity, show: Vod, all: Map<Int, List<Episode>>, e: Episode, start: Double) {
    val season = all[e.season] ?: listOf(e)
    val items = season.map { ep -> episodeItem(show, ep, Repo.episodeUrl(ep) ?: "", if (ep.id == e.id) start else 0.0) }
    act.play(items, season.indexOfFirst { it.id == e.id }.coerceAtLeast(0))
}

private val EP_W = 176.dp
private val EP_H = EP_W * 9 / 16 + 12.dp

/** "00:45:12" / "45:12" / "45" (provider formats) -> minutes, or null. */
private fun epMinutes(d: String): Int? {
    val p = d.trim().split(':').mapNotNull { it.trim().toIntOrNull() }
    return when (p.size) { 3 -> p[0] * 60 + p[1] + (if (p[2] >= 30) 1 else 0); 2 -> p[0] + (if (p[1] >= 30) 1 else 0); 1 -> p[0]; else -> null }?.takeIf { it > 0 }
}

/** The provider's episode pictures are TMDB stills at 185 px wide (blurry on a TV): the same picture at 780 px. */
private fun sharpStill(url: String): String = url.replace(Regex("""(image\.tmdb\.org/t/p/)w\d+/"""), "$1w780/")

/** "Season 1 · 8 episodes" and, with more than one season, a chip per season. Entering the chips (Down from Play, Up from
 *  the first episode) always lands on the season shown, via [selectedFocus]; a season is switched to once focus rests on
 *  its chip for a moment, so passing over chips doesn't flick the list. */
@Composable
private fun SeasonHeader(all: Map<Int, List<Episode>>, season: Int, count: Int, selectedFocus: FocusRequester, playFocus: FocusRequester, firstEpFocus: FocusRequester, pick: (Int) -> Unit) {
    val episodes = "$count episode${if (count == 1) "" else "s"}"
    Column(Modifier.padding(top = if (all.size > 1) 20.dp else 24.dp, bottom = 2.dp)) {
        // One season: a plain heading. Several: the chips are the heading (no "Season 2" said twice), count at the end.
        if (all.size == 1) Row(verticalAlignment = Alignment.Bottom) {
            Text("Season $season", fontSize = Tv.rowTitle, fontWeight = FontWeight.SemiBold, color = Tv.text)
            Spacer(Modifier.width(10.dp))
            Text(episodes, fontSize = Tv.label, color = Tv.muted, modifier = Modifier.padding(bottom = 2.dp))
        }
        if (all.size > 1) {
            val keys = all.keys.toList()
            var focused by remember { mutableStateOf<Int?>(null) }
            LaunchedEffect(focused) { val f = focused ?: return@LaunchedEffect; delay(280); if (f != season) pick(f) }
            // The chips scroll sideways inside 8dp of padding (pulled back by the same 8dp), so a highlighted chip at
            // either end is never cut off.
            Row(Modifier.offset(x = (-8).dp).horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                keys.forEach { s ->
                    SeasonChip("Season $s", s == season,
                        Modifier.let { if (s == season) it.focusRequester(selectedFocus) else it }.focusProperties { up = playFocus; down = firstEpFocus },
                        onFocus = { f -> if (f) focused = s else if (focused == s) focused = null }) { pick(s) }
                }
                Text(episodes, fontSize = Tv.label, color = Tv.muted, modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

/** A season chip: the shared [TvChip] (the season shown in accent, the highlight solid white). */
@Composable
private fun SeasonChip(text: String, selected: Boolean, modifier: Modifier, onFocus: (Boolean) -> Unit, onClick: () -> Unit) =
    TvChip(text, selected, onClick, modifier, onFocus = onFocus)

/** An episode row's shape while the list loads. */
@Composable
private fun EpisodeSkeleton() {
    val b = shimmerBrush()
    Row(Modifier.padding(top = 10.dp).fillMaxWidth(.86f).height(EP_H).clip(Tv.shape).background(Color(0x0FFFFFFF)).padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(EP_W).aspectRatio(16f / 9f).clip(RoundedCornerShape(6.dp)).background(b))
        Column(Modifier.padding(start = 16.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.width(110.dp).height(11.dp).clip(RoundedCornerShape(3.dp)).background(b))
            Box(Modifier.fillMaxWidth(.5f).height(14.dp).clip(RoundedCornerShape(3.dp)).background(b))
            Box(Modifier.fillMaxWidth(.85f).height(11.dp).clip(RoundedCornerShape(3.dp)).background(b))
        }
    }
}

/** One episode: a small sharp 16:9 picture (the show's backdrop when the provider has none), then
 *  "Episode N · 55m · Watched / 12m left", the title and two lines of plot. */
@Composable
private fun EpisodeRow(act: TvActivity, show: Vod, all: Map<Int, List<Episode>>, e: Episode, art: String, modifier: Modifier = Modifier) {
    val p = remember(act.refresh) { Repo.episodeUrl(e)?.let { Repo.progressFor(it) } }
    val watched = p?.done == true
    val partial = p?.takeIf { resumePoint(it) != null && it.d > 0 }
    val meta = listOfNotNull("Episode ${e.num}", epMinutes(e.duration)?.let { runtime(it) },
        if (watched) "Watched" else partial?.let { "${runtime(((it.d - it.t) / 60).toInt().coerceAtLeast(1))} left" }).joinToString("  ·  ")
    FocusCard(modifier.padding(top = 8.dp).fillMaxWidth(.86f).height(EP_H), scale = Tv.scaleLarge, restoreKey = "ep:" + e.id, label = "Episode ${e.num}, ${e.title}",
        onClick = { playEpisode(act, show, all, e, partial?.t ?: 0.0) }) {
        Row(Modifier.fillMaxSize().background(Color(0x14FFFFFF)).padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(EP_W).aspectRatio(16f / 9f).clip(RoundedCornerShape(6.dp))) {
                Picture(e.still.ifEmpty { art }.let { if (it.startsWith("http")) sharpStill(it) else it }, Modifier.fillMaxSize()) {
                    // no picture at all (or it failed): the episode number on a quiet gradient, never an empty box
                    Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Tv.surface3, Tv.surface))), contentAlignment = Alignment.Center) {
                        Text("E${e.num}", fontSize = Tv.numeral, fontWeight = FontWeight.Bold, color = Tv.muted)
                    }
                }
                if (e.still.isEmpty()) Box(Modifier.fillMaxSize().background(Color(0x73000000)), contentAlignment = Alignment.Center) {
                    Text("E${e.num}", fontSize = Tv.numeral, fontWeight = FontWeight.Bold, color = Color(0xE6FFFFFF))
                }
                Box(Modifier.align(Alignment.BottomStart).padding(6.dp).size(24.dp).background(Color(0x99000000), RoundedCornerShape(50)), contentAlignment = Alignment.Center) {
                    Icon(if (watched) Icons.Default.Check else Icons.Default.PlayArrow, null, Modifier.size(15.dp), tint = Color.White)
                }
                if (partial != null) ProgressLine((partial.t / partial.d).toFloat(), Modifier.align(Alignment.BottomStart).fillMaxWidth(), 3.dp)
            }
            Column(Modifier.padding(start = 16.dp, end = 14.dp).weight(1f)) {
                Text(meta, fontSize = Tv.label, lineHeight = 16.sp, color = if (watched) Tv.accent else Tv.muted, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(e.title.ifBlank { "Episode ${e.num}" }, fontSize = Tv.body, lineHeight = 20.sp, color = Tv.text, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                if (e.plot.isNotBlank()) Text(e.plot, fontSize = Tv.caption, color = Tv.text2, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = Tv.captionLine, modifier = Modifier.padding(top = 3.dp))
            }
        }
    }
}
