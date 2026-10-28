package com.wmorgan97.m3uplayer.tv

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import androidx.tv.material3.*
import com.wmorgan97.m3uplayer.MainActivity
import com.wmorgan97.m3uplayer.NativePlayerPlugin
import com.wmorgan97.m3uplayer.PlayerActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** Where you are in the TV app. Back goes one step up this stack. */
sealed class Screen {
    data object Home : Screen()
    data class Live(val group: String? = null) : Screen()
    data class Vods(val kind: String) : Screen()          // movies | series
    data object Search : Screen()
    data class Details(val vod: Vod) : Screen()
    data class Grid(val kind: String, val id: String, val title: String) : Screen()
    data class Guide(val group: String? = null) : Screen()
    data object Settings : Screen()
}

/**
 * The Shield's own app (Compose for TV): a side menu that opens out on focus, and native screens for Home,
 * Live TV, the guide, Movies, Series, search, details and settings. Playback is PlayerActivity (ExoPlayer).
 */
class TvActivity : ComponentActivity() {
    private val stack = mutableStateListOf<Screen>(Screen.Home)
    var refresh by mutableIntStateOf(0)                 // bumped when history / progress change (after watching)
    private var focusTick by mutableIntStateOf(0)       // bumped to pull the highlight back into the page
    private var zap by mutableStateOf("")               // digits typed on the remote
    private var due by mutableStateOf<Reminder?>(null)
    var resumeTick by mutableIntStateOf(0)                // bumped on coming back (from the player): the preview starts again   // a reminder that just came up (shown over the app)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Repo.init(this)
        if (!Repo.hasKey()) {   // first run: the web app has the server key; it hands it over and comes back here
            startActivity(Intent(this, MainActivity::class.java).putExtra("setup", true))
            finish(); return
        }
        Repo.cachedSync()
        lifecycleScope.launch { try { Repo.sync(); refresh++ } catch (_: Exception) {} }
        lifecycleScope.launch { runCatching { Repo.liveChannels() } }   // channels ready before Live TV is opened
        lifecycleScope.launch { delay(1500); runCatching { Repo.overview("movies") }; runCatching { Repo.overview("series") } }   // rows ready before Movies / Series are opened
        lifecycleScope.launch { delay(2500); com.wmorgan97.m3uplayer.PlayerHolder.warm(this@TvActivity) }   // a player ready before play is pressed
        Repo.loadSavedGuide()
        lifecycleScope.launch { while (true) { checkReminders(); delay(15_000) } }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Tv.accent, background = Tv.bg, surface = Tv.surface, onSurface = Tv.text, onBackground = Tv.text, surfaceVariant = Tv.surface2)) {
                CompositionLocalProvider(androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides QuickBringIntoView) { App() }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (Repo.hasKey()) lifecycleScope.launch { try { Repo.sync(); refresh++ } catch (_: Exception) {} }
        resumeTick++
        testHook(intent)
    }
    override fun onStop() { super.onStop(); Preview.stop() }   // Home / the player: the preview's stream closes
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); testHook(intent) }

    /** Test hook (adb only): `--es testStream <url> --ei testIndex n` plays a pretend channel list on that stream, so the
     *  player's channel panel, last-channel entry and zapping can be checked without touching the provider. */
    private fun testHook(i: Intent?) {
        i?.getStringExtra("testPreview")?.let { Preview.testUrl = it.ifEmpty { null }; i.removeExtra("testPreview") }
        val url = i?.getStringExtra("testStream") ?: return
        i.removeExtra("testStream")
        val names = listOf("Sky Sports Main Event", "Sky Sports F1", "BBC One HD", "BBC Two HD", "ITV1 HD", "Channel 4 HD", "Channel 5 HD", "Sky Atlantic")
        val chans = names.mapIndexed { k, n -> Channel(n, "$url#$k", "Test", "", "", "", k + 1) }
        lifecycleScope.launch { delay(800); playLive(chans, i.getIntExtra("testIndex", 2)) }
    }

    fun go(s: Screen) { stack += s }
    fun back(): Boolean {
        if (stack.size > 1) { stack.removeAt(stack.lastIndex); Nav.restoreKey = Nav.pending; return true }   // highlight the card it was opened from
        return false
    }
    fun root(s: Screen) { stack.clear(); stack += s }
    fun openWeb(hash: String = "") { startActivity(Intent(this, MainActivity::class.java).putExtra("hash", hash)) }

    // Number keys zap to a channel, as on any TV: the digits show for a moment, then it tunes.
    private var zapJob: Job? = null
    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (e.action == KeyEvent.ACTION_DOWN && e.keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 && stack.last() !is Screen.Search && stack.last() !is Screen.Settings) {
            zap = (zap + (e.keyCode - KeyEvent.KEYCODE_0)).takeLast(4)
            zapJob?.cancel()
            zapJob = lifecycleScope.launch {
                delay(1300)
                val n = zap.toIntOrNull(); zap = ""
                val c = n?.let { Repo.channelByNumber(it) }
                if (c != null) playLive(Repo.live?.channels ?: listOf(c), Repo.live?.channels?.indexOf(c)?.coerceAtLeast(0) ?: 0)
            }
            return true
        }
        return super.dispatchKeyEvent(e)
    }

    @Composable
    private fun App() {
        val screen = stack.last()
        val drawer = rememberDrawerState(DrawerValue.Closed)
        BackHandler(enabled = true) {
            if (drawer.currentValue == DrawerValue.Open) { finish(); return@BackHandler }
            if (back()) { drawer.setValue(DrawerValue.Closed); focusTick++ } else drawer.setValue(DrawerValue.Open)
        }
        val section = stack.firstOrNull() ?: Screen.Home
        val contentFocus = remember { FocusRequester() }
        var contentHasFocus by remember { mutableStateOf(false) }
        val saveable = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
        NavigationDrawer(
            drawerState = drawer,
            drawerContent = {
                val current = remember { FocusRequester() }   // moving into the menu lands on the section you're in
                Column(Modifier.fillMaxHeight().background(Tv.rail).padding(horizontal = 12.dp, vertical = 24.dp).focusProperties { enter = { current } }.focusGroup(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val item: @Composable (String, ImageVector, Boolean, () -> Unit) -> Unit = { label, icon, selected, onClick ->
                        NavigationDrawerItem(selected = selected, onClick = { drawer.setValue(DrawerValue.Closed); onClick(); if (selected) focusTick++ }, leadingContent = { Icon(icon, null) },
                            modifier = if (selected) Modifier.focusRequester(current) else Modifier) { Text(label, fontSize = Tv.body, fontWeight = FontWeight.Medium) }
                    }
                    item("Search", Icons.Default.Search, screen is Screen.Search) { root(Screen.Search) }
                    item("Home", Icons.Default.Home, section is Screen.Home && screen !is Screen.Search) { root(Screen.Home) }
                    item("Live TV", Icons.Default.LiveTv, section is Screen.Live) { root(Screen.Live()) }
                    item("TV Guide", Icons.Default.CalendarViewWeek, section is Screen.Guide) { root(Screen.Guide()) }
                    item("Movies", Icons.Default.Movie, (section as? Screen.Vods)?.kind == "movies") { root(Screen.Vods("movies")) }
                    item("Series", Icons.Default.VideoLibrary, (section as? Screen.Vods)?.kind == "series") { root(Screen.Vods("series")) }
                    Spacer(Modifier.weight(1f))
                    item("Settings", Icons.Default.Settings, section is Screen.Settings) { root(Screen.Settings) }
                }
            },
        ) {
            Box(Modifier.fillMaxSize().background(Tv.bg).focusRequester(contentFocus).onFocusChanged { contentHasFocus = it.hasFocus }) {
                // Each page starts with the remote on its content (not the menu): retried while its rows load in.
                val fromBack = focusTick > 0
                LaunchedEffect(screen, focusTick) {
                    repeat(20) {
                        delay(150)
                        if (drawer.currentValue == DrawerValue.Open && !fromBack) return@LaunchedEffect
                        if (contentHasFocus) return@LaunchedEffect
                        if (Nav.restoreKey != null && it < 6) return@repeat   // the card it came from takes the highlight itself
                        runCatching { contentFocus.requestFocus() }
                    }
                }
                // Each page keeps its scroll position and focus while you're on a page above it (Back returns to the same poster).
                key(screen) { saveable.SaveableStateProvider(screen.toString()) {
                    when (screen) {
                        Screen.Home -> HomeScreen(this@TvActivity)
                        is Screen.Live -> LiveScreen(this@TvActivity, screen.group)
                        is Screen.Vods -> VodScreen(this@TvActivity, screen.kind)
                        Screen.Search -> SearchScreen(this@TvActivity)
                        is Screen.Details -> DetailsScreen(this@TvActivity, screen.vod)
                        is Screen.Grid -> GridScreen(this@TvActivity, screen.kind, screen.id, screen.title)
                        is Screen.Guide -> GuideScreen(this@TvActivity, screen.group)
                        Screen.Settings -> SettingsScreen(this@TvActivity)
                    }
                } }
                if (screen !is Screen.Details) Row(Modifier.align(Alignment.TopEnd).padding(top = 6.dp, end = Tv.margin), verticalAlignment = Alignment.CenterVertically) {
                    if (!Repo.online) { Text("Offline · showing saved data", fontSize = Tv.label, color = Tv.muted); Spacer(Modifier.width(14.dp)) }
                    Clock()
                }
                due?.let { r ->
                    Options("Starting now on ${r.channel}: ${r.title}", listOf("Watch now" to { due = null; watchReminder(r) })) { due = null }
                }
                if (zap.isNotEmpty()) Box(Modifier.align(Alignment.TopEnd).padding(top = 70.dp, end = Tv.margin).background(Tv.surface, RoundedCornerShape(10.dp)).padding(horizontal = 18.dp, vertical = 8.dp)) {
                    Column(horizontalAlignment = Alignment.End) {
                        Text(zap, fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Tv.text)
                        Text(Repo.channelByNumber(zap.toIntOrNull() ?: -1)?.name ?: "No channel $zap", fontSize = Tv.label, color = Tv.muted)
                    }
                }
            }
        }
    }

    // ---------- playback ----------

    private var playList: List<JSONObject> = emptyList()   // the items up/down / next episode step through
    private var playIndex = 0
    private var lastLive: Pair<List<JSONObject>, Int>? = null   // for "last channel"
    private val liveLists = ArrayList<Pair<List<JSONObject>, List<Channel>>>()   // player lists -> their channels (the preview follows what you watch)

    /** Live TV: plays one of a list of channels, preferring the 4K copy when that setting is on. */
    fun playLive(chans: List<Channel>, index: Int) {
        var list = chans; var i = index
        if (Repo.fourKFirst) {
            val c = chans[index]
            if (quality(c.name, c.group) != "4K") {
                val base = normName(c.name)
                Repo.live?.channels?.firstOrNull { quality(it.name, it.group) == "4K" && normName(it.name) == base }?.let { uhd -> list = chans.toMutableList().also { it[index] = uhd }; i = index }
            }
        }
        val items = list.map { channelItem(it) }
        liveLists.add(0, items to list); while (liveLists.size > 3) liveLists.removeAt(3)
        val prev = lastLive?.let { (l, k) -> l.getOrNull(k) }
        if (prev != null && prev.optString("url") != items[i].optString("url")) items[i].put("last", prev.optString("title"))
        Repo.lastChannelUrl = list[i].url
        play(items, i)
        lastLive = items to i
    }

    /** A past programme from the channel's archive (or the current one from its start), played like a recording. */
    fun playCatchup(c: Channel, p: Programme) {
        val url = Repo.catchupUrl(c, p.start, p.end) ?: return
        val item = JSONObject().put("url", url).put("title", p.title).put("subtitle", "${c.name}  ·  ${hhmm(p.start)}–${hhmm(p.end)}  ·  Catch-up").put("info", p.desc)
            .put("live", false).put("start", 0.0).put("art", c.logo).put("headers", JSONObject(c.headers)).put("listTitle", p.title)
        play(listOf(item), 0)
    }

    // ---------- reminders ----------

    private val fired = HashSet<String>()
    private fun checkReminders() {
        val now = System.currentTimeMillis()
        val l = Repo.reminders
        val r = l.firstOrNull { it.start in (now - 90_000)..(now + 30_000) && "${it.url}|${it.start}" !in fired } ?: return
        fired += "${r.url}|${r.start}"
        Repo.reminders = l.filter { it.start > now - 90_000 && it != r }
        val p = PlayerActivity.current
        if (p != null && !p.isFinishing) p.notice("Starting now on ${r.channel}: ${r.title}", "OK to watch") { watchReminder(r) }
        else due = r
    }
    private fun watchReminder(r: Reminder) {
        val c = Repo.channelByUrl(r.url) ?: Channel(r.channel, r.url, "", "", "", "", 0)
        val group = Repo.live?.channels?.filter { it.group == c.group && c.group.isNotEmpty() }?.takeIf { it.contains(c) } ?: listOf(c)
        playLive(group, group.indexOf(c).coerceAtLeast(0))
    }

    /** Plays one of a list (channels of a group, episodes of a season) in the TV player. */
    fun play(items: List<JSONObject>, index: Int) {
        playList = items; playIndex = index
        Preview.release()   // one stream at a time: the preview's connection is closed before the full-screen one opens
        liveLists.firstOrNull { it.first === items }?.second?.let { l -> l.getOrNull(index)?.let { Preview.select(it, l) } }
        NativePlayerPlugin.listener = NativePlayerPlugin.Listener { onPlayerEvent(it) }
        val it = playerItem(index)
        val sync = items[index].optJSONObject("sync") ?: JSONObject()
        if (sync.optString("url").isNotEmpty() && sync.optString("group") != "Test") lifecycleScope.launch { Repo.saveRecent(sync) }   // (catch-up has no sync entry)   // (test-hook channels stay out of your history)
        PlayerActivity.current?.let { p -> p.load(com.getcapacitor.JSObject(it.toString())); return }
        startActivity(Intent(this, PlayerActivity::class.java).putExtra("item", it.toString()))
    }

    /** The player's item: the entry itself plus the list around it (for the channel panel / episodes). */
    private fun playerItem(i: Int): JSONObject {
        val o = JSONObject(playList[i].toString())
        val from = maxOf(0, minOf(i - 150, playList.size - 300))
        val part = playList.subList(from, minOf(playList.size, from + 300))
        o.put("hasPrev", i > 0).put("hasNext", i < playList.size - 1)
        o.put("list", JSONArray(part.map { JSONObject().put("t", it.optString("listTitle", it.optString("title"))).put("s", it.optString("listSub")) }))
        o.put("listStart", from).put("current", i)
        o.put("listTitle", o.optString("listTitleOverride", "Up next"))   // the panel's heading: Channels / Episodes
        o.remove("sync"); o.remove("listTitleOverride"); o.remove("listSub")
        return o
    }

    private fun onPlayerEvent(e: JSONObject) {
        when (e.optString("type")) {
            "step", "jump" -> {
                val n = if (e.optString("type") == "step") playIndex + e.optInt("dir") else e.optInt("index")
                if (n !in playList.indices || n == playIndex) return
                if (playList[n].optBoolean("live")) { lastLive = playList to playIndex; playList[n].put("last", playList[playIndex].optString("title")); Repo.lastChannelUrl = playList[n].optString("url") }
                play(playList, n)
            }
            "last" -> lastLive?.let { (l, k) -> val cur = playList to playIndex; lastLive = cur; play(l, k) }
            "zap" -> { val c = Repo.channelByNumber(e.optInt("num")); if (c != null) playLive(Repo.live?.channels ?: listOf(c), Repo.live?.channels?.indexOf(c)?.coerceAtLeast(0) ?: 0) }
            "progress", "closed" -> {
                val cur = playList.getOrNull(playIndex) ?: return
                if (!cur.optBoolean("live") && cur.has("sync")) lifecycleScope.launch {
                    Repo.saveProgress(cur.optString("url"), e.optDouble("t"), e.optDouble("d"), cur.optJSONObject("sync") ?: JSONObject())
                    if (e.optString("type") == "closed") refresh++
                }
                if (e.optString("type") == "closed") NativePlayerPlugin.listener = null
            }
        }
    }
}
