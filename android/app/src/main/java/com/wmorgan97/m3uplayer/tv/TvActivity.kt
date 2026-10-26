package com.wmorgan97.m3uplayer.tv

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import androidx.tv.material3.*
import com.wmorgan97.m3uplayer.MainActivity
import com.wmorgan97.m3uplayer.NativePlayerPlugin
import com.wmorgan97.m3uplayer.PlayerActivity
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** Where you are in the TV app. Back goes one step up this stack. */
sealed class Screen {
    data object Home : Screen()
    data object Live : Screen()
    data class Vods(val kind: String) : Screen()          // movies | series
    data object Search : Screen()
    data class Details(val vod: Vod) : Screen()
    data class Grid(val kind: String, val id: String, val title: String) : Screen()
}

val Bg = Color(0xFF0E1014)
val Surface1 = Color(0xFF181B22)
val Surface2 = Color(0xFF232733)
val Accent = Color(0xFF5B93FF)
val Muted = Color(0xFF9AA3B5)

/**
 * The Shield's own app (Compose for TV): a side menu that opens out on focus, and native screens for Home,
 * Live TV, Movies, Series, search and details. Playback is PlayerActivity (ExoPlayer). The web app is still
 * there for setup and settings.
 */
class TvActivity : ComponentActivity() {
    private val stack = mutableStateListOf<Screen>(Screen.Home)
    var refresh by mutableIntStateOf(0)
    private var focusTick by mutableIntStateOf(0)   // bumped to pull the highlight back into the page   // bumped when history / progress change (after watching)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Repo.init(this)
        if (!Repo.hasKey()) {   // first run: the web app has the server key; it hands it over and comes back here
            startActivity(Intent(this, MainActivity::class.java).putExtra("setup", true))
            finish(); return
        }
        Repo.cachedSync()
        lifecycleScope.launch { try { Repo.sync(); refresh++ } catch (_: Exception) {} }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Accent, background = Bg, surface = Surface1, onSurface = Color.White, onBackground = Color.White)) {
                App()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (Repo.hasKey()) lifecycleScope.launch { try { Repo.sync(); refresh++ } catch (_: Exception) {} }
    }

    fun go(s: Screen) { stack += s }
    fun back(): Boolean {
        if (stack.size > 1) { stack.removeAt(stack.lastIndex); Nav.restoreKey = Nav.pending; return true }   // highlight the card it was opened from
        return false
    }
    fun root(s: Screen) { stack.clear(); stack += s }
    fun openWeb(hash: String = "") { startActivity(Intent(this, MainActivity::class.java).putExtra("hash", hash)) }

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
            drawerContent = { state ->
                // Moving into the menu lands on the section you're in.
                val current = remember { FocusRequester() }
                Column(Modifier.fillMaxHeight().background(Color(0xFF0A0C10)).padding(horizontal = 12.dp, vertical = 24.dp).focusProperties { enter = { current } }.focusGroup(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val item: @Composable (String, ImageVector, Boolean, () -> Unit) -> Unit = { label, icon, selected, onClick ->
                        // Choosing a section closes the menu and moves into the page (see LaunchedEffect(screen) below).
                        NavigationDrawerItem(selected = selected, onClick = { drawer.setValue(DrawerValue.Closed); onClick(); if (selected) focusTick++ }, leadingContent = { Icon(icon, null) },
                            modifier = if (selected) Modifier.focusRequester(current) else Modifier) { Text(label, fontSize = 17.sp) }
                    }
                    item("Search", Icons.Default.Search, screen is Screen.Search) { root(Screen.Search) }
                    item("Home", Icons.Default.Home, section is Screen.Home && screen !is Screen.Search) { root(Screen.Home) }
                    item("Live TV", Icons.Default.LiveTv, section is Screen.Live) { root(Screen.Live) }
                    item("Movies", Icons.Default.Movie, (section as? Screen.Vods)?.kind == "movies") { root(Screen.Vods("movies")) }
                    item("Series", Icons.Default.VideoLibrary, (section as? Screen.Vods)?.kind == "series") { root(Screen.Vods("series")) }
                    item("TV Guide", Icons.Default.CalendarViewWeek, false) { openWeb("#/tvguide") }
                    Spacer(Modifier.weight(1f))
                    item("Settings", Icons.Default.Settings, false) { openWeb("#/settings") }
                }
            },
        ) {
            Box(Modifier.fillMaxSize().background(Bg).focusRequester(contentFocus).onFocusChanged { contentHasFocus = it.hasFocus }) {
                // Each page starts with the remote on its content (not the menu): retried while its rows load in.
                val fromBack = focusTick > 0
                LaunchedEffect(screen, focusTick) {
                    repeat(20) {
                        kotlinx.coroutines.delay(150)
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
                        Screen.Live -> LiveScreen(this@TvActivity)
                        is Screen.Vods -> VodScreen(this@TvActivity, screen.kind)
                        Screen.Search -> SearchScreen(this@TvActivity)
                        is Screen.Details -> DetailsScreen(this@TvActivity, screen.vod)
                        is Screen.Grid -> GridScreen(this@TvActivity, screen.kind, screen.id, screen.title)
                    }
                } }
            }
        }
    }

    // ---------- playback ----------

    private var playList: List<JSONObject> = emptyList()   // the items up/down / next episode step through
    private var playIndex = 0

    /** Plays one of a list (channels of a group, episodes of a season) in the TV player. */
    fun play(items: List<JSONObject>, index: Int) {
        playList = items; playIndex = index
        NativePlayerPlugin.listener = NativePlayerPlugin.Listener { onPlayerEvent(it) }
        val it = playerItem(index)
        lifecycleScope.launch { Repo.saveRecent(items[index].optJSONObject("sync") ?: JSONObject()) }
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
            "step" -> { val n = playIndex + e.optInt("dir"); if (n in playList.indices) play(playList, n) }
            "jump" -> { val n = e.optInt("index"); if (n in playList.indices && n != playIndex) play(playList, n) }
            "progress", "closed" -> {
                val cur = playList.getOrNull(playIndex) ?: return
                if (!cur.optBoolean("live")) lifecycleScope.launch {
                    Repo.saveProgress(cur.optString("url"), e.optDouble("t"), e.optDouble("d"), cur.optJSONObject("sync") ?: JSONObject())
                    if (e.optString("type") == "closed") refresh++
                }
                if (e.optString("type") == "closed") NativePlayerPlugin.listener = null
            }
        }
    }
}
