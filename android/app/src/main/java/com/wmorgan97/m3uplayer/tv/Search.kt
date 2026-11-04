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

// ---------- Search ----------

/** One search for everything: live channels (name, number, group), what's on TV now and later from the loaded guide,
 *  films and series. Channels and programmes come from in-memory indexes (SearchIndex.kt), so they appear as you type;
 *  films and series follow from the server. */
@Composable
fun SearchScreen(act: TvActivity) {
    var q by rememberSaveable { mutableStateOf("") }
    // The field's text and caret (Right at the end of the text steps over to the mic).
    var tf by remember { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(q, androidx.compose.ui.text.TextRange(q.length))) }
    val field = remember { FocusRequester() }
    val firstResult = remember { FocusRequester() }
    val mic = remember { FocusRequester() }
    var fieldFocused by remember { mutableStateOf(false) }
    var micFocused by remember { mutableStateOf(false) }
    val listState = rememberPageState()
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
    var spoken by remember { mutableStateOf<String?>(null) }
    fun submit() { if (query.isNotEmpty()) { debounced = query; submits++ } else kb?.hide() }
    LaunchedEffect(submits) {
        if (submits == 0) return@LaunchedEffect
        kb?.hide()
        val until = System.currentTimeMillis() + 2_500
        val atMost = System.currentTimeMillis() + 10_000   // just started (a voice search from Home): the channel list is still loading
        while (System.currentTimeMillis() < atMost && (chanIndex == null || chans.isEmpty() && progs.isEmpty() && (vodBusy || vods == null) && System.currentTimeMillis() < until)) delay(80)
        delay(60)
        if (chans.isEmpty() && progs.isEmpty() && vods?.let { it.first.isEmpty() && it.second.isEmpty() } != false) delay(400)   // films/series still arriving
        // The first result of the first row (not whichever card happens to sit under the field).
        if (runCatching { firstResult.requestFocus() }.isFailure) focusMgr.moveFocus(androidx.compose.ui.focus.FocusDirection.Down)
    }
    // Voice: the mic button, the remote's search key, or "search for …" from the Assistant. What was heard fills the
    // field and runs at once, like pressing Search on the keyboard.
    LaunchedEffect(act.voiceTick) {
        val heard = act.voiceText ?: return@LaunchedEffect
        act.voiceText = null
        q = heard; tf = androidx.compose.ui.text.input.TextFieldValue(heard, androidx.compose.ui.text.TextRange(heard.length))
        submit(); spoken = heard
        delay(400); kb?.hide()   // (Search opening puts the highlight in the field, which brings the keyboard up)
    }
    // After a voice search the highlight goes to the first result as soon as there is one, however long the channel
    // list takes to load (straight after the app starts it can take ~15 s); typing something else cancels it.
    LaunchedEffect(spoken, chans, progs, vods, chanIndex) {
        if (spoken == null || chanIndex == null) return@LaunchedEffect   // (channels first: they head the results)
        if (query != spoken) { spoken = null; return@LaunchedEffect }
        if (chans.isEmpty() && progs.isEmpty() && vods?.let { it.first.isEmpty() && it.second.isEmpty() } != false) return@LaunchedEffect
        delay(120)
        if (fieldFocused || micFocused) { runCatching { firstResult.requestFocus() }; kb?.hide() }
        spoken = null
    }
    // Back in the field (or on the mic) leaves it for the results below; with nothing below it opens the menu. Back on
    // the results opens the menu (TvActivity), as on every page. (Back from the results used to go up to the field, and
    // the field kept every Back after that: Search could only be left with Left.)
    val hasResults = if (query.isEmpty()) recent.isNotEmpty() else chans.isNotEmpty() || progs.isNotEmpty() || vods?.let { it.first.isNotEmpty() || it.second.isNotEmpty() } == true
    androidx.activity.compose.BackHandler(enabled = (fieldFocused || micFocused) && chanMenu == null) {
        kb?.hide()
        if (!hasResults || runCatching { firstResult.requestFocus() }.isFailure && !focusMgr.moveFocus(androidx.compose.ui.focus.FocusDirection.Down)) act.openMenu()
    }
    HeldPace()
    LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(top = Tv.topMargin, bottom = 56.dp)) {
        item {
            Row(Modifier.padding(horizontal = Tv.margin).fillMaxWidth(.66f), verticalAlignment = Alignment.CenterVertically) {
                // A text field keeps its text readable when focused: it lifts and gets the white focus outline (no fill).
                Row(Modifier.weight(1f).background(if (fieldFocused) Tv.surface2 else Tv.surface, Tv.pill)
                    .border(2.dp, if (fieldFocused) Tv.focusRing else Tv.line, Tv.pill).padding(horizontal = 22.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Search, null, tint = Tv.muted, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(12.dp))
                    Box(Modifier.weight(1f)) {
                        if (tf.text.isEmpty()) Text(if (act.listening) "Listening…" else "Search channels, TV programmes, movies and series", color = if (act.listening) Tv.text2 else Tv.muted, fontSize = Tv.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        BasicTextField(tf, { tf = it; q = it.text }, singleLine = true, textStyle = TextStyle(color = Tv.text, fontSize = Tv.body), cursorBrush = SolidColor(Tv.accent),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { submit() }, onDone = { submit() }, onGo = { submit() }),
                            modifier = Modifier.fillMaxWidth().focusRequester(field).onFocusChanged { fieldFocused = it.isFocused }
                                .onPreviewKeyEvent { e -> if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionRight && tf.selection.collapsed && tf.selection.end >= tf.text.length) { kb?.hide(); runCatching { mic.requestFocus() }; true } else false }
                                .searchFieldKeys { submit() })
                    }
                }
                Spacer(Modifier.width(12.dp))
                MicButton(act.listening, Modifier.focusRequester(mic), onFocus = { micFocused = it }) { act.startVoice() }
            }
        }
        if (query.isEmpty()) {
            if (recent.isNotEmpty()) { item(key = "recent-t") { RowTitle("Recent channels") }; item(key = "recent") { Box(Modifier.focusRequester(firstResult)) { ChannelRowCards(act, recent, guideTick) { chanMenu = it } } } }
            else item { Box(Modifier.fillMaxWidth().padding(top = 120.dp)) { EmptyState(Icons.Default.Search, "Find anything", "Channels, what's on TV, movies and series, all at once") } }
            return@LazyColumn
        }
        val v = vods
        // The first row there is takes the "first result" requester: focus entering it lands on its first card.
        var firstUsed = false
        fun first(): Modifier = if (firstUsed) Modifier else { firstUsed = true; Modifier.focusRequester(firstResult) }
        if (chans.isNotEmpty()) { val m = first(); item(key = "ch-t") { RowTitle("Live channels", count = chans.size) }; item(key = "ch") { Box(m) { ChannelRowCards(act, chans, guideTick) { chanMenu = it } } } }
        if (progs.isNotEmpty()) {
            val m = first()
            item(key = "pg-t") { RowTitle("On TV now and later", extra = "from the TV guide", count = progs.size) }
            item(key = "pg") { Box(m) { CardRow(progs, { "${it.channel.url}|${it.p.start}" }) { h, _ -> SearchProgrammeCard(act, h) { chanMenu = h.channel } } } }
        }
        if (v != null && v.first.isNotEmpty()) { val m = first(); item(key = "mv-t") { RowTitle("Movies", count = v.first.size) }; item(key = "mv") { Box(m) { CardRow(v.first, { it.id }) { m, _ -> PosterCard(m) { act.go(Screen.Details(m)) } } } } }
        if (v != null && v.second.isNotEmpty()) { val m = first(); item(key = "sr-t") { RowTitle("Series", count = v.second.size) }; item(key = "sr") { Box(m) { CardRow(v.second, { it.id }) { s, _ -> PosterCard(s) { act.go(Screen.Details(s)) } } } } }
        if (vodBusy || !settled || chanIndex == null) { item(key = "sk-t") { SkeletonTitle() }; item(key = "sk") { SkeletonRow() } }
        else if (chans.isEmpty() && progs.isEmpty() && (v == null || (v.first.isEmpty() && v.second.isEmpty())))
            item { Box(Modifier.fillMaxWidth().padding(top = 120.dp)) { EmptyState(Icons.Default.SearchOff, "Nothing matches \"$query\"", "Try a shorter word, a channel number or a programme name") } }
    }
    chanMenu?.let { c -> ChannelOptions(act, c) { chanMenu = null } }
}

/** The mic beside the search field: the shared round [TvIconButton] (accent while listening, white when highlighted)
 *  with one slow, soft ring while listening (calm, not a flashing light). */
@Composable
private fun MicButton(listening: Boolean, modifier: Modifier, onFocus: (Boolean) -> Unit, onClick: () -> Unit) {
    val ring = if (listening) androidx.compose.animation.core.rememberInfiniteTransition(label = "mic").animateFloat(0f, 1f,
        androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(1400), androidx.compose.animation.core.RepeatMode.Restart), label = "ring").value else 0f
    Box(Modifier.size(58.dp), contentAlignment = Alignment.Center) {
        if (listening) Box(Modifier.size(52.dp).graphicsLayer { scaleX = 1f + ring * .25f; scaleY = 1f + ring * .25f; alpha = (1f - ring) * .5f }.background(Tv.accent, RoundedCornerShape(50)))
        TvIconButton(Icons.Default.Mic, if (listening) "Listening" else "Search by voice", onClick, modifier.onFocusChanged { onFocus(it.isFocused) }, size = 52.dp, selected = listening)
    }
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
    val focused = remember { mutableStateOf(false) }
    Column(Modifier.width(Tv.channel)) {
        FocusCard(Modifier.width(Tv.channel).aspectRatio(16f / 9f), scale = Tv.scaleWide, label = "${p.title}, $sub", onLongClick = onLong, focused = focused, onClick = { if (onAir) act.playLive(listOf(c), 0) }) {
            Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Tv.surface3, Tv.surface))), contentAlignment = Alignment.Center) {
                if (c.logo.isBlank()) Text(initials(c.name), fontSize = Tv.numeral, fontWeight = FontWeight.Bold, color = Tv.muted)
                else Picture(Repo.logo(c.logo), Modifier.fillMaxSize().padding(16.dp).background(Color.Transparent), ContentScale.Fit) {
                    Text(initials(c.name), fontSize = Tv.numeral, fontWeight = FontWeight.Bold, color = Tv.muted)
                }
                TvBadge(if (onAir) "LIVE" else "$day${hhmm(p.start)}".trim(), Modifier.align(Alignment.TopStart).padding(6.dp), if (onAir) BadgeTone.Live else BadgeTone.Neutral)
                if (onAir) ProgressLine((now - p.start).toFloat() / (p.end - p.start), Modifier.align(Alignment.BottomStart).fillMaxWidth(), 3.dp)
            }
        }
        Caption(p.title, sub, Tv.channel, focused = focused)
    }
}
