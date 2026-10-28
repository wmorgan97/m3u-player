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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
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
    data object Sports : Screen()
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
    private var backAt = 0L
    private var lastRepeatAt = 0L
    private var pinGate by mutableStateOf<(() -> Unit)?>(null)   // a locked channel waiting for its PIN
    // ---------- voice search ----------
    // The mic beside the search field, the remote's search key, or the Assistant's "search for … on M3U Player":
    // Android's speech recogniser (Google's on the Shield) listens; what it heard goes into Search and runs.
    var listening by mutableStateOf(false); private set
    var voiceText: String? = null
    var voiceTick by mutableIntStateOf(0); private set
    private val voice = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { r ->
        listening = false
        val heard = r.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.trim()
        if (r.resultCode == RESULT_OK && !heard.isNullOrEmpty()) searchFor(heard)
    }
    /** Opens Search (if it isn't open) with [text] in the field, searched at once. */
    fun searchFor(text: String) { if (stack.lastOrNull() !is Screen.Search) root(Screen.Search); voiceText = text; voiceTick++ }
    fun startVoice() {
        if (stack.lastOrNull() !is Screen.Search) root(Screen.Search)
        val i = Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, "en-GB")
            .putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Search channels, programmes, movies and series")
            .putExtra(android.speech.RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        try { voice.launch(i); listening = true }
        catch (e: android.content.ActivityNotFoundException) { listening = false; android.widget.Toast.makeText(this, "Voice search isn't available on this device", android.widget.Toast.LENGTH_SHORT).show() }
    }
    private fun handleSearchIntent(i: Intent?) {
        if (i?.action != Intent.ACTION_SEARCH) return
        val q = i.getStringExtra(android.app.SearchManager.QUERY)?.trim(); i.action = null
        if (!q.isNullOrEmpty()) searchFor(q) else startVoice()
    }

    var resumeTick by mutableIntStateOf(0)                // bumped on coming back (from the player): the preview starts again   // a reminder that just came up (shown over the app)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Repo.init(this)
        if (!Repo.hasKey()) {   // first run: a native setup (server, then a playlist), then the app starts afresh
            setContent { MaterialTheme(colorScheme = darkColorScheme(primary = Tv.accent, background = Tv.bg, surface = Tv.surface, onSurface = Tv.text, onBackground = Tv.text, surfaceVariant = Tv.surface2)) {
                SetupScreen(onDone = { recreate() }, onQuit = { finish() }) } }
            return
        }
        // Pictures (logos, posters) rarely change: keep them on the device whatever cache headers the original hosts send
        // (many logo hosts say "don't cache", so logos were downloaded again every time a list came up).
        coil.Coil.setImageLoader(coil.ImageLoader.Builder(applicationContext)
            .respectCacheHeaders(false)
            .memoryCache { coil.memory.MemoryCache.Builder(applicationContext).maxSizePercent(0.20).build() }
            .diskCache { coil.disk.DiskCache.Builder().directory(cacheDir.resolve("pictures")).maxSizeBytes(300L * 1024 * 1024).build() }
            .build())
        Repo.cachedSync()
        testHook(intent)   // (adb test hook: on create and on new intents only, never again on resume)
        handleSearchIntent(intent)
        lifecycleScope.launch { try { Repo.sync(); refresh++ } catch (_: Exception) {} }
        lifecycleScope.launch { runCatching { Repo.liveChannels() }; delay(4000); Repo.warmAllLogos(applicationContext) }   // channels ready before Live TV is opened
        lifecycleScope.launch { delay(1500); runCatching { Repo.overview("movies") }; runCatching { Repo.overview("series") } }   // rows ready before Movies / Series are opened
        lifecycleScope.launch { delay(2500); com.wmorgan97.m3uplayer.PlayerHolder.warm(this@TvActivity) }   // a player ready before play is pressed
        Repo.loadSavedGuide()
        lifecycleScope.launch { while (true) { checkReminders(); delay(15_000) } }
        // Every 10 min, pick up what the other devices changed (reminders set on the phone, favourites) while the app stays open.
        lifecycleScope.launch { while (true) { delay(10 * 60_000L); if (Repo.hasKey()) runCatching { Repo.sync() } } }
        // Followed teams: every 3 hours, a reminder for each of their matches (checkFollows keeps its own pace).
        lifecycleScope.launch { delay(90_000); while (true) { SportsHub.checkFollows(applicationContext); delay(30 * 60_000L) } }
        // Guide auto-refresh for the channels you use most (favourites, recents, the preview's group): every 30 min it asks
        // again, and each channel actually re-downloads at most every 3 h. The server re-reads the guide source every 6 h.
        lifecycleScope.launch {
            while (true) {
                delay(30 * 60_000L)
                if (PlayerActivity.current != null) continue   // watching: leave the connection to the picture
                val mine = (Repo.favorites() + Repo.recentChannels()).map { liveFromSync(it) } + Preview.list.take(200)
                if (mine.isNotEmpty()) Repo.requestGuide(mine.distinctBy { it.url })
            }
        }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Tv.accent, background = Tv.bg, surface = Tv.surface, onSurface = Tv.text, onBackground = Tv.text, surfaceVariant = Tv.surface2)) {
                CompositionLocalProvider(androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides QuickBringIntoView) { App() }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (Repo.hasKey()) lifecycleScope.launch { try { Repo.sync(); refresh++ } catch (_: Exception) {} }
        // Back from full screen on a live channel: the preview takes back the player, playing whichever channel you ended
        // on (you may have changed channel in full screen), and the window shrinks into place.
        val scr = stack.last()
        val r = if (Repo.preview && (scr is Screen.Live || scr is Screen.Guide)) com.wmorgan97.m3uplayer.PlayerHolder.takeReturnedAny() else null
        Preview.resetZoom()
        com.wmorgan97.m3uplayer.PlayerHolder.dropReturned()
        if (r != null) {
            val p = r[0] as androidx.media3.exoplayer.ExoPlayer; val u = r[1] as String
            val hit = liveLists.firstNotNullOfOrNull { (_, l) -> l.firstOrNull { it.url == u }?.let { it to l } }
            if (hit != null) { Preview.select(hit.first, hit.second); Preview.adopt(p, u) } else com.wmorgan97.m3uplayer.PlayerHolder.giveBackPublic(p)
        }
        Preview.suspended = false
        resumeTick++
    }
    override fun onStop() { super.onStop(); Preview.suspended = true; Preview.stop() }   // Home / the player: the preview's stream closes
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); testHook(intent); handleSearchIntent(intent) }

    /** Test hook (adb only): `--es testStream <url> --ei testIndex n` plays a pretend channel list on that stream, so the
     *  player's channel panel, last-channel entry and zapping can be checked without touching the provider. */
    private fun testHook(i: Intent?) {
        i?.getStringExtra("testPreview")?.let { Preview.testUrl = it.ifEmpty { null }; i.removeExtra("testPreview") }
        i?.getStringExtra("testSports")?.let { SportsHub.test = it; i.removeExtra("testSports") }
        i?.getStringExtra("testFollows")?.let { f -> i.removeExtra("testFollows"); Repo.testFollows = f.split('|').filter { it.isNotBlank() }.map { Repo.Follow(it.trim(), 1) }.ifEmpty { null }; Repo.version++ }
        // `--es testFollowSync <name>`: follows <name> (synced), then unfollows it 5 s later: checks the server round trip.
        i?.getStringExtra("testFollowSync")?.let { n -> i.removeExtra("testFollowSync"); Repo.toggleFollow(n); lifecycleScope.launch { delay(5_000); if (Repo.isFollowing(n)) Repo.toggleFollow(n) } }
        i?.getStringExtra("testScreen")?.let { sc -> i.removeExtra("testScreen"); when (sc) { "live" -> root(Screen.Live()); "guide" -> root(Screen.Guide()); "home" -> root(Screen.Home); "sports" -> root(Screen.Sports) } }
        i?.getStringExtra("testSeries")?.let { q -> i.removeExtra("testSeries")   // test hook: open the first series found for q
            lifecycleScope.launch { runCatching { Repo.search(q) }.getOrNull()?.second?.firstOrNull()?.let { root(Screen.Vods("series")); go(Screen.Details(it)) } } }
        // `--es testEpisodes <url> [--ei testStart secs]`: a pretend 3-episode season on that video, to check next-episode autoplay.
        i?.getStringExtra("testEpisodes")?.let { u -> i.removeExtra("testEpisodes"); val st = i.getIntExtra("testStart", 0).toDouble()
            val eps = (1..3).map { n -> JSONObject().put("url", "$u#$n").put("title", "Test show · S1 E$n").put("subtitle", "Episode $n").put("live", false).put("start", if (n == 1) st else 0.0)
                .put("listTitle", "$n. Episode $n").put("listTitleOverride", "Episodes")
                .put("show", "Test show").put("epLabel", "S1 E$n").put("epTitle", "Episode $n").put("art", i.getStringExtra("testArt") ?: "https://peach.blender.org/wp-content/uploads/bbb-splash.png") }
            lifecycleScope.launch { delay(800); play(eps, 0) } }
        val url = i?.getStringExtra("testStream") ?: return
        i.removeExtra("testStream")
        val names = listOf("Sky Sports Main Event UHD", "Sky Sports F1", "BBC One HD", "BBC Two HD", "ITV1 HD", "Channel 4 HD", "Channel 5 HD", "Sky Atlantic")
        // Pretend guide for each, `--es testLogos <base>` = logos at <base><n>.png, `--es testCatchup <url>` = an archive (Start over plays <url>).
        val logos = i.getStringExtra("testLogos"); val cu = i.getStringExtra("testCatchup")
        val chans = names.mapIndexed { k, n -> Channel(n, "$url#$k", "Test", logos?.let { "$it$k.png" } ?: "", "m3utest$k", "", k + 1,
            catchupDays = if (cu != null) 1 else 0, catchupMode = if (cu != null) "default" else "", catchupSource = cu?.let { "$it?utc={utc}" } ?: "") }
        chans.forEachIndexed { k, c -> Repo.testGuide(c.tvgId, k) }
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
        // A held arrow repeats ~20 times a second. When a frame runs late those repeats queue up and the list keeps moving
        // after the key is let go; repeats that are already stale are dropped instead (the highlight stops when you stop).
        // Held arrows also move at a steady pace (~11 steps a second): quick, but slow enough to stop where you mean to.
        if (e.action == KeyEvent.ACTION_DOWN && e.keyCode in KeyEvent.KEYCODE_DPAD_UP..KeyEvent.KEYCODE_DPAD_RIGHT) {
            val t = android.os.SystemClock.uptimeMillis()
            if (e.repeatCount > 0 && (t - e.eventTime > 40 || t - lastRepeatAt < 90)) return true
            lastRepeatAt = t
        }
        // The remote's search / mic key: Search, listening.
        if (e.keyCode == KeyEvent.KEYCODE_SEARCH || e.keyCode == KeyEvent.KEYCODE_VOICE_ASSIST) {
            if (e.action == KeyEvent.ACTION_UP && !listening) startVoice()
            return true
        }
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
            if (drawer.currentValue == DrawerValue.Open) {   // a second Back within 2 s leaves the app (no accidental exits)
                if (System.currentTimeMillis() - backAt < 2000) finish() else { backAt = System.currentTimeMillis(); android.widget.Toast.makeText(this@TvActivity, "Press Back again to exit", android.widget.Toast.LENGTH_SHORT).show() }
                return@BackHandler
            }
            if (back()) { drawer.setValue(DrawerValue.Closed); focusTick++ } else drawer.setValue(DrawerValue.Open)
        }
        val section = stack.firstOrNull() ?: Screen.Home
        var railPx by remember { mutableIntStateOf(0) }   // the closed menu's width: the page starts after it (the open menu lies over the page)
        val contentFocus = remember { FocusRequester() }
        var contentHasFocus by remember { mutableStateOf(false) }
        val saveable = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
        ModalNavigationDrawer(scrimBrush = androidx.compose.ui.graphics.SolidColor(androidx.compose.ui.graphics.Color(0x99000000)),   // over the page (TiviMate), so opening it never re-lays the page out
            drawerState = drawer,
            drawerContent = {
                val current = remember { FocusRequester() }   // moving into the menu lands on the section you're in
                // Hidden during the preview's grow/shrink. Read in the layer (drawing only): reading it here in composition
                // re-composed the whole screen on the first frame of the zoom (~55 ms).
                Column(Modifier.fillMaxHeight().graphicsLayer { alpha = if (Preview.zooming || Preview.unzooming) 0f else 1f }.background(Tv.rail).onSizeChanged { if (drawer.currentValue == DrawerValue.Closed && it.width > 0) railPx = it.width }
                    .padding(horizontal = 12.dp, vertical = 24.dp).focusProperties { enter = { current } }.focusGroup(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val item: @Composable (String, ImageVector, Boolean, () -> Unit) -> Unit = { label, icon, selected, onClick ->
                        NavigationDrawerItem(selected = selected, onClick = { drawer.setValue(DrawerValue.Closed); onClick(); if (selected) focusTick++ }, leadingContent = { Icon(icon, null) },
                            modifier = if (selected) Modifier.focusRequester(current) else Modifier) { Text(label, fontSize = Tv.body, fontWeight = FontWeight.Medium) }
                    }
                    item("Search", Icons.Default.Search, screen is Screen.Search) { root(Screen.Search) }
                    item("Home", Icons.Default.Home, section is Screen.Home && screen !is Screen.Search) { root(Screen.Home) }
                    item("Live TV", Icons.Default.LiveTv, section is Screen.Live) { root(Screen.Live()) }
                    item("TV Guide", Icons.Default.CalendarViewWeek, section is Screen.Guide) { root(Screen.Guide()) }
                    item("Sports", Icons.Default.SportsSoccer, section is Screen.Sports) { root(Screen.Sports) }
                    item("Movies", Icons.Default.Movie, (section as? Screen.Vods)?.kind == "movies") { root(Screen.Vods("movies")) }
                    item("Series", Icons.Default.VideoLibrary, (section as? Screen.Vods)?.kind == "series") { root(Screen.Vods("series")) }
                    Spacer(Modifier.weight(1f))
                    item("Settings", Icons.Default.Settings, section is Screen.Settings) { root(Screen.Settings) }
                }
            },
        ) {
            Box(Modifier.fillMaxSize().background(Tv.bg).padding(start = with(androidx.compose.ui.platform.LocalDensity.current) { railPx.toDp() }).focusRequester(contentFocus).onFocusChanged { contentHasFocus = it.hasFocus }) {
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
                        Screen.Sports -> SportsScreen(this@TvActivity)
                        Screen.Settings -> SettingsScreen(this@TvActivity)
                    }
                } }
                // The clock sits on a small dark pill so it stays readable over posters scrolled up beneath it.
                if (screen !is Screen.Details) Row(Modifier.align(Alignment.TopEnd).graphicsLayer { alpha = if (Preview.zooming || Preview.unzooming) 0f else 1f }.padding(top = 4.dp, end = Tv.margin).background(Tv.bg.copy(alpha = .85f), RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (!Repo.online) { Text("Offline · showing saved data", fontSize = Tv.label, color = Tv.muted); Spacer(Modifier.width(14.dp)) }
                    Clock()
                }
                pinGate?.let { go -> PinDialog("Enter PIN to watch this channel", verify = Repo::checkPin) { pin -> pinGate = null; if (pin != null) go() } }
                due?.let { r ->
                    Options("Starting now on ${r.channel}: ${r.title}", listOf("Watch now" to { due = null; watchReminder(r) })) { due = null }
                }
                if (zap.isNotEmpty()) Box(Modifier.align(Alignment.TopEnd).padding(top = 70.dp, end = Tv.margin).background(Tv.surface, RoundedCornerShape(10.dp)).padding(horizontal = 18.dp, vertical = 8.dp)) {
                    Column(horizontalAlignment = Alignment.End) {
                        Text(zap, fontSize = Tv.display, fontWeight = FontWeight.Bold, color = Tv.text)
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
        // Parental lock: every way into a channel (favourites, home rows, search, number zap, guide) comes through here.
        chans.getOrNull(index)?.let { c -> if (Repo.isChannelLocked(c)) { pinGate = { Repo.unlocked += c.group; playLive(chans, index) }; return } }
        var list = chans; var i = index
        // Only the channels around this one go to the player (its channel panel shows ±150; zapping wraps within them):
        // building player entries for all ~19k of "All channels" on the main thread cost seconds.
        if (list.size > 400) { val from = maxOf(0, minOf(i - 200, list.size - 400)); list = list.subList(from, from + 400); i -= from }
        val c = list[i]
        // Prefer 4K (an O(1) lookup), except for the channel already playing in the preview: that stream carries on as is.
        val inPreview = Preview.channel?.url == c.url && Preview.shown
        // The picked channel keeps its name, logo and number; only the stream is the 4K copy's, with the normal one as its
        // backup (the player falls back to it if the 4K stream won't play).
        val uhd = if (Repo.fourKFirst && !inPreview) Repo.uhdFor(c) else null
        Repo.prefetchLogos(this, list.subList(maxOf(0, i - 20), minOf(list.size, i + 21)))   // zapping nearby shows logos at once
        val items = list.map { channelItem(it) }
        if (uhd != null) items[i].put("url", uhd.url).put("headers", JSONObject(uhd.headers)).put("fallback", c.url).put("fallbackHeaders", JSONObject(c.headers)).put("uhd", true)
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

    /** Whether the archive has [p] (the programme on now): catchupUrl's own test, without building ~300 links per zap. */
    private fun canStartOver(c: Channel, p: Programme?, now: Long) = p != null && c.hasCatchup && p.start <= now && p.start >= now - c.catchupDays * 86_400_000L

    /** "Start over" in the player: the programme on now on channel [n] of the playing list, from its start (+[atMs]) out
     *  of the channel's archive. Back / "Live" in the player returns to the channel (event goLive). */
    private fun startOver(n: Int, atMs: Long) {
        val ch = liveLists.firstOrNull { it.first === playList }?.second?.getOrNull(n) ?: return
        val p = Repo.nowNext(ch).first ?: return
        val url = Repo.catchupUrl(ch, p.start, p.end) ?: return
        val item = JSONObject().put("url", url).put("title", p.title).put("subtitle", "${ch.name}  ·  ${hhmm(p.start)}–${hhmm(p.end)}  ·  From the start  ·  Back for live TV")
            .put("info", p.desc).put("live", false).put("start", atMs / 1000.0).put("art", ch.logo).put("headers", JSONObject(ch.headers))
            .put("fromLive", true).put("progStart", p.start).put("progEnd", p.end).put("listTitle", p.title)
        if (n != playIndex) { lastLive = playList to playIndex; playIndex = n }
        PlayerActivity.current?.load(com.getcapacitor.JSObject(item.toString()))
    }

    /** Plays one of a list (channels of a group, episodes of a season) in the TV player. */
    fun play(items: List<JSONObject>, index: Int) {
        // Going full screen on the channel already in the preview: the window grows to fill the screen first, then the
        // player screen takes over its running stream (no reload, no cut).
        val first = items.getOrNull(index)
        if (PlayerActivity.current == null && first?.optBoolean("live") == true && Preview.channel?.url == first.optString("url") && Preview.shown && !Preview.zooming) {
            Preview.zoomThen { playNow(items, index) }; return
        }
        playNow(items, index)
    }

    private fun playNow(items: List<JSONObject>, index: Int) {
        playList = items; playIndex = index
        // One stream at a time. Going full screen on the channel already in the preview hands its player (and connection)
        // over, so the picture carries on; anything else closes the preview first.
        val first = items.getOrNull(index)
        val handed = if (first?.optBoolean("live") == true && Preview.channel?.url == first.optString("url")) Preview.handOff() else null
        if (handed != null) com.wmorgan97.m3uplayer.PlayerHolder.handOff(handed, first!!.optString("url")) else Preview.release()
        liveLists.firstOrNull { it.first === items }?.second?.let { l -> l.getOrNull(index)?.let { Preview.select(it, l) } }
        NativePlayerPlugin.listener = NativePlayerPlugin.Listener { onPlayerEvent(it) }
        val it = playerItem(index)
        val sync = items[index].optJSONObject("sync") ?: JSONObject()
        if (sync.optString("url").isNotEmpty() && sync.optString("group") != "Test") lifecycleScope.launch { Repo.saveRecent(sync) }   // (catch-up has no sync entry)   // (test-hook channels stay out of your history)
        PlayerActivity.current?.let { p -> p.load(com.getcapacitor.JSObject(it.toString())); return }
        startActivity(Intent(this, PlayerActivity::class.java).putExtra("item", it.toString()))
        if (handed != null) overridePendingTransition(com.wmorgan97.m3uplayer.R.anim.m3u_fade_in, com.wmorgan97.m3uplayer.R.anim.m3u_hold)   // the grown preview stays put while the player fades in over it
    }

    /** The player's item: the entry itself plus the list around it (for the channel panel / episodes). */
    private fun playerItem(i: Int): JSONObject {
        val o = JSONObject(playList[i].toString())
        val from = maxOf(0, minOf(i - 150, playList.size - 300))
        val part = playList.subList(from, minOf(playList.size, from + 300))
        val wrap = o.optBoolean("live") && playList.size > 1   // live: up/down go round the list, as on a TV
        o.put("hasPrev", wrap || i > 0).put("hasNext", wrap || i < playList.size - 1)
        // Rows for the side panel; episodes also carry their still and "S1 E2" / title for the next-episode card.
        // Live: each row also carries the channel's logo, number and a fresh now/next (the player's mini guide and the
        // instant banner on a quick run of zaps draw from these), and whether "Start over" can play the programme on now.
        val chans = if (o.optBoolean("live")) liveLists.firstOrNull { it.first === playList }?.second else null
        val now = System.currentTimeMillis()
        o.put("list", JSONArray(part.mapIndexed { k, r -> JSONObject().put("t", r.optString("listTitle", r.optString("title"))).put("s", r.optString("listSub"))
            .apply { if (!r.optBoolean("live")) put("a", r.optString("art")).put("e", r.optString("epLabel")).put("n", r.optString("epTitle")) }
            .apply { chans?.getOrNull(from + k)?.let { c -> val (pn, px) = Repo.nowNext(c, now)
                put("a", r.optString("art")).put("n", r.optString("num")).put("nt", pn?.title ?: "").put("ps", pn?.start ?: 0).put("pe", pn?.end ?: 0).put("nx", px?.title ?: "")
                    .put("cu", canStartOver(c, pn, now)) } } }))
        chans?.getOrNull(i)?.let { c -> val (pn, px) = Repo.nowNext(c, now)
            o.put("nowTitle", pn?.title ?: "").put("nowStart", pn?.start ?: 0).put("nowEnd", pn?.end ?: 0).put("nextTitle", px?.title ?: "").put("info", pn?.desc ?: o.optString("info"))
                .put("catchup", canStartOver(c, pn, now)) }
        o.put("total", playList.size)
        // Films/episodes: the next item's stream, so the player can buffer it during the end credits (see PlayerActivity.preloadNext).
        playList.getOrNull(i + 1)?.takeIf { !o.optBoolean("live") && !it.optBoolean("live") }?.let { n ->
            o.put("nextItem", JSONObject().put("url", n.optString("url")).put("start", n.optDouble("start", 0.0)).put("headers", n.optJSONObject("headers") ?: JSONObject()))
        }
        o.put("listStart", from).put("current", i)
        o.put("listTitle", o.optString("listTitleOverride", "Up next"))   // the panel's heading: Channels / Episodes
        o.remove("sync"); o.remove("listTitleOverride"); o.remove("listSub")
        return o
    }

    private fun onPlayerEvent(e: JSONObject) {
        when (e.optString("type")) {
            "step", "jump" -> {
                var n = if (e.optString("type") == "step") playIndex + e.optInt("dir") else e.optInt("index")
                if (playList.getOrNull(playIndex)?.optBoolean("live") == true && playList.isNotEmpty()) n = Math.floorMod(n, playList.size)
                if (n !in playList.indices || n == playIndex) return
                if (playList[n].optBoolean("live")) { lastLive = playList to playIndex; playList[n].put("last", playList[playIndex].optString("title")); Repo.lastChannelUrl = playList[n].optString("url") }
                play(playList, n)
            }
            "last" -> lastLive?.let { (l, k) -> val cur = playList to playIndex; lastLive = cur; play(l, k) }
            "startOver" -> startOver(e.optInt("index", playIndex), e.optLong("at", 0))
            "goLive" -> if (playList.getOrNull(playIndex)?.optBoolean("live") == true) play(playList, playIndex)
            "guide" -> if (playList.getOrNull(playIndex)?.optBoolean("live") == true) PlayerActivity.current?.updateGuide(com.getcapacitor.JSObject(playerItem(playIndex).toString()))
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
