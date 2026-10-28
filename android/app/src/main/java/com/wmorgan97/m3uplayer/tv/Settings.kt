package com.wmorgan97.m3uplayer.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import org.json.JSONObject

// Settings on the TV, laid out like TiviMate: sections on the left, the selected section's rows on the right, each row
// showing its current value. Playlist editing, the TMDB key and player options stay in the full settings (shared
// through the same server), which every "full settings" row opens.

private enum class Sec(val title: String, val icon: ImageVector) {
    Playlists("Playlists", Icons.Default.PlaylistPlay), Live("Live TV", Icons.Default.LiveTv), Guide("TV Guide", Icons.Default.CalendarViewWeek),
    Playback("Playback", Icons.Default.PlayCircle), Parental("Parental control", Icons.Default.Lock), Interface("Interface", Icons.Default.Tune),
    Data("Data", Icons.Default.Storage), About("About", Icons.Default.Info)
}

/** Wrong-PIN attempts (D6): five tries, then PIN entry pauses for 30 seconds. Kept for the app's lifetime, not the screen's. */
private object PinGuard {
    const val MAX_TRIES = 5
    const val LOCKOUT_MS = 30_000L
    var tries = 0
    var lockedUntil = 0L
    fun secondsLeft() = ((lockedUntil - System.currentTimeMillis() + 999) / 1000).coerceAtLeast(0)
}

private data class PinAsk(val title: String, val check: Boolean, val seq: Int, val then: (String) -> Unit)

@Composable
fun SettingsScreen(act: TvActivity) {
    val v = Repo.version                              // re-read prefs whenever anything changes
    var tick by remember { mutableIntStateOf(0) }     // local changes that don't bump Repo.version
    var focusedSec by remember { mutableStateOf(Sec.Playlists) }
    var shown by remember { mutableStateOf(Sec.Playlists) }
    var inPane by remember { mutableStateOf(false) }
    var enterPane by remember { mutableIntStateOf(0) }
    val secReqs = remember { Sec.entries.associateWith { FocusRequester() } }
    val paneReq = remember { FocusRequester() }

    var toast by remember { mutableStateOf<String?>(null) }
    var toastSeq by remember { mutableIntStateOf(0) }
    fun say(s: String) { toast = s; toastSeq++ }
    LaunchedEffect(toastSeq) { if (toast != null) { delay(2600); toast = null } }

    var menu by remember { mutableStateOf<Pair<String, List<Pair<String, () -> Unit>>>?>(null) }
    var pinAsk by remember { mutableStateOf<PinAsk?>(null) }
    var pinSeq by remember { mutableIntStateOf(0) }
    fun askPin(title: String, check: Boolean, then: (String) -> Unit) {
        if (check && PinGuard.secondsLeft() > 0) { say("Too many wrong PINs. Try again in ${PinGuard.secondsLeft()} s"); return }
        pinAsk = PinAsk(title, check, ++pinSeq, then)
    }
    fun choosePin(title: String = "Choose a 4-digit PIN"): Unit = askPin(title, false) { first ->
        askPin("Enter the same PIN again", false) { again ->
            if (again == first) { Repo.pin = first; say("PIN saved") } else choosePin("PINs didn't match. Choose a 4-digit PIN")
        }
    }

    // Moving through the sections updates the right pane once the highlight settles (so scrolling past is cheap).
    LaunchedEffect(focusedSec) { if (!inPane) { delay(150); shown = focusedSec } }
    LaunchedEffect(Unit) { delay(80); runCatching { secReqs.getValue(Sec.Playlists).requestFocus() } }
    LaunchedEffect(enterPane) {
        if (enterPane == 0) return@LaunchedEffect
        repeat(6) { delay(16); if (runCatching { paneReq.requestFocus() }.isSuccess) return@LaunchedEffect }
    }
    fun goPane(s: Sec) { shown = s; enterPane++ }
    fun backToSections() { runCatching { secReqs.getValue(shown).requestFocus() } }
    // Back in the pane returns to the section column; Back there falls through to the app (act.back(), then the menu).
    BackHandler(enabled = inPane && menu == null && pinAsk == null) { backToSections() }

    val rows = sectionRows(act, shown, v + tick, ::say, { t, o -> menu = t to o }, ::askPin, { choosePin() }) { tick++ }

    Column(Modifier.fillMaxSize()) {
        PageTitle("Settings", "Shared with your phone and computer through your server")
        Row(Modifier.weight(1f).padding(start = Tv.margin - 12.dp, end = Tv.margin)) {
            Column(Modifier.width(250.dp).fillMaxHeight().onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionRight) { goPane(focusedSec); true } else false
            }, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                for (s in Sec.entries) SectionItem(s.icon, s.title, s == shown, secReqs.getValue(s), onFocus = { focusedSec = s }) { goPane(s) }
            }
            Box(Modifier.padding(horizontal = 20.dp).width(1.dp).fillMaxHeight().background(Tv.line))
            val firstFocusable = rows.indexOfFirst { it.focusable }
            LazyColumn(Modifier.weight(1f).widthIn(max = 760.dp).fillMaxHeight().onFocusChanged { inPane = it.hasFocus }.onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionLeft) { backToSections(); true } else false
            }, contentPadding = PaddingValues(bottom = 56.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                item(key = "head-${shown.name}") {
                    Text(shown.title.uppercase(), Modifier.padding(start = 12.dp, top = 10.dp, bottom = 8.dp), fontSize = Tv.label, color = Tv.muted, fontWeight = FontWeight.Bold)
                }
                itemsIndexed(rows, key = { i, _ -> "${shown.name}-$i" }) { i, r ->
                    SettingRowView(r, if (i == firstFocusable) Modifier.focusRequester(paneReq) else Modifier)
                }
            }
        }
    }
    Box(Modifier.fillMaxSize().padding(end = Tv.margin, bottom = 32.dp), contentAlignment = Alignment.BottomEnd) { SettingsToast(toast) }

    menu?.let { (title, options) -> Options(title, options.map { (l, fn) -> l to { menu = null; fn() } }) { menu = null } }
    pinAsk?.let { ask ->
        key(ask.seq) {
            PinDialog(ask.title) { pin ->
                pinAsk = null
                if (pin == null) return@PinDialog
                if (!ask.check) { ask.then(pin); return@PinDialog }
                if (Repo.checkPin(pin)) { PinGuard.tries = 0; ask.then(pin); return@PinDialog }
                PinGuard.tries++
                if (PinGuard.tries >= PinGuard.MAX_TRIES) {
                    PinGuard.tries = 0; PinGuard.lockedUntil = System.currentTimeMillis() + PinGuard.LOCKOUT_MS
                    say("Too many wrong PINs. Try again in 30 s")
                } else {
                    val left = PinGuard.MAX_TRIES - PinGuard.tries
                    pinAsk = PinAsk("Wrong PIN — try again ($left ${if (left == 1) "try" else "tries"} left)", true, ++pinSeq, ask.then)
                }
            }
        }
    }
}

/** The rows of one section. Only existing Repo settings; anything else lives in the full settings. */
private fun sectionRows(
    act: TvActivity, sec: Sec, @Suppress("UNUSED_PARAMETER") stamp: Int, say: (String) -> Unit,
    menu: (String, List<Pair<String, () -> Unit>>) -> Unit, askPin: (String, Boolean, (String) -> Unit) -> Unit,
    choosePin: () -> Unit, changed: () -> Unit,
): List<SettingRow> {
    fun full() = act.openWeb("#/settings")
    fun refreshLists() { Repo.clearCache(); Repo.version++; say("Channel lists will download again") }
    return when (sec) {
        Sec.Playlists -> {
            val s = Repo.syncData.optJSONObject("sources") ?: JSONObject()
            val sources = s.keys().asSequence().mapNotNull { s.optJSONObject(it) }.filter { !it.optBoolean("deleted") }.toList()
            sources.map { src ->
                val x = src.optJSONObject("xtream")
                val what = when (src.optString("content")) { "live" -> "Live TV"; "vod" -> "Movies & series"; else -> "Live TV, movies & series" }
                val name = src.optString("name").ifBlank { if (x != null) "Xtream playlist" else "Playlist" }
                SettingRow.Action(if (x != null) Icons.Default.Cloud else Icons.Default.Link, name, what, if (x != null) "Xtream" else "M3U") {
                    menu(name, listOf("Refresh now" to ::refreshLists, "Edit in full settings" to ::full))
                }
            } + listOf(
                SettingRow.Action(Icons.Default.PlaylistAdd, "Add playlist", "Opens the full settings", onClick = ::full),
                SettingRow.Action(Icons.Default.Refresh, "Refresh channel lists", "Download every playlist again", "${sources.size}", onClick = ::refreshLists),
            )
        }
        Sec.Live -> listOf(
            SettingRow.Toggle(Icons.Default.HighQuality, "Prefer 4K channels", "Start the 4K copy of a channel when there is one", Repo.fourKFirst) { Repo.fourKFirst = it; changed() },
            SettingRow.Toggle(Icons.Default.PictureInPicture, "Channel preview", "Play the highlighted channel in a small window", Repo.preview) { Repo.preview = it },
            SettingRow.Toggle(Icons.Default.Numbers, "Show channel numbers", "Numbers beside channels in lists, the guide and the banner", Repo.showNumbers) { Repo.showNumbers = it },
            SettingRow.Toggle(Icons.Default.Language, "English groups only", "Hide other-language groups", Repo.englishOnly) { Repo.englishOnly = it; Repo.version++ },
            SettingRow.Note("Channel preview uses your one provider connection while you browse."),
        )
        Sec.Guide -> {
            val upcoming = Repo.reminders.count { it.start > System.currentTimeMillis() }
            listOf(
                SettingRow.Action(Icons.Default.CalendarViewWeek, "Open the TV Guide", "Hold OK on a programme to set a reminder", if (upcoming == 0) "No reminders" else "$upcoming reminder${if (upcoming == 1) "" else "s"}") { act.root(Screen.Guide()) },
                SettingRow.Note("The guide refreshes automatically every 3 hours."),
            )
        }
        Sec.Playback -> listOf(
            SettingRow.Action(Icons.Default.OpenInNew, "Player options", "Opens the full settings") { full() },
            SettingRow.Note("While watching live TV: Up/Down change channel, OK opens the channel list, hold OK for the controls, Left/Right show the banner."),
        )
        Sec.Parental -> {
            val hasPin = Repo.pin.isNotEmpty()
            val locked = Repo.lockedGroups.size
            buildList {
                add(SettingRow.Action(Icons.Default.Lock, if (hasPin) "Change PIN" else "Set a PIN", if (hasPin) "Enter your current PIN first" else "Then lock groups from Live TV", if (hasPin) "Set" else "Off") {
                    if (hasPin) askPin("Enter your current PIN", true) { choosePin() } else choosePin()
                })
                if (hasPin) {
                    add(SettingRow.Action(Icons.Default.LockOpen, "Remove PIN", "Unlocks every group", danger = true) {
                        askPin("Enter your PIN", true) { Repo.pin = ""; Repo.unlocked.clear(); say("PIN removed") }
                    })
                    add(SettingRow.Action(Icons.Default.LockClock, "Lock opened groups again", "Groups opened with the PIN this session", "${Repo.unlocked.size} open") {
                        Repo.unlocked.clear(); Repo.version++; say("Locked groups need the PIN again")
                    })
                }
                add(SettingRow.Action(Icons.Default.FolderOff, "Locked groups", "Hold OK on a group in Live TV to lock it", "$locked") { act.root(Screen.Live()) })
            }
        }
        Sec.Interface -> {
            val hidden = Repo.hiddenGroups.size + Repo.hiddenChannels.size
            listOf(
                SettingRow.Action(Icons.Default.Visibility, "Show hidden groups and channels", "Hold OK on a group or channel to hide it", if (hidden == 0) "None" else "$hidden hidden") {
                    if (hidden == 0) say("Nothing is hidden") else { Repo.showAllHidden(); say("$hidden item${if (hidden == 1) "" else "s"} shown again") }
                },
            )
        }
        Sec.Data -> listOf(
            SettingRow.Action(Icons.Default.DeleteSweep, "Clear saved data", "Channel lists, catalogues and the guide download again") {
                menu("Clear saved data? Channel lists, catalogues and the guide download again next time.", listOf("Clear saved data" to { Repo.clearCache(); say("Saved data cleared") }))
            },
            SettingRow.Note(if (Repo.online) "Connected to your server." else "Offline: showing saved data until the server is reachable."),
        )
        Sec.About -> {
            val version = runCatching { act.packageManager.getPackageInfo(act.packageName, 0).versionName }.getOrNull() ?: ""
            listOf(
                SettingRow.Action(Icons.Default.OpenInNew, "Full settings", "Playlists, TMDB, player options and more") { full() },
                SettingRow.Note("M3U Player for Android TV $version"),
                SettingRow.Note("Server: ${Repo.server.removePrefix("https://")}"),
            )
        }
    }
}
