package com.wmorgan97.m3uplayer.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.lifecycle.lifecycleScope
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
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

// Settings on the TV, laid out like TiviMate: sections on the left, the selected section's rows on the right, each row
// showing its current value. Everything is native (playlists, server, TMDB key, player languages): nothing opens the
// phone-style web settings, so the look never changes under you.

private enum class Sec(val title: String, val icon: ImageVector) {
    Playlists("Playlists", Icons.Default.PlaylistPlay), Profiles("Profiles", Icons.Default.People), Live("Live TV", Icons.Default.LiveTv), Guide("TV Guide", Icons.Default.CalendarViewWeek),
    Playback("Playback", Icons.Default.PlayCircle), Parental("Parental control", Icons.Default.Lock), Interface("Interface", Icons.Default.Tune),
    Data("Data", Icons.Default.Storage), About("Server & about", Icons.Default.Info)
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

/** A form waiting on screen; [save] returns null when it worked, else the message to show above the buttons. */
internal class FormAsk(val title: String, val intro: String?, val fields: List<FormField>, val submit: String = "Save", val save: suspend (List<String>) -> String?)

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
    var menuSelected by remember { mutableStateOf<String?>(null) }   // a chooser's current choice (marked in the list)
    var pinAsk by remember { mutableStateOf<PinAsk?>(null) }
    var pinSeq by remember { mutableIntStateOf(0) }
    var form by remember { mutableStateOf<FormAsk?>(null) }
    var formBusy by remember { mutableStateOf(false) }
    var formError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    fun ask(f: FormAsk) { formError = null; formBusy = false; form = f }
    val keys by produceState<JSONObject?>(null, v + tick) { value = Repo.keysStatus() }
    val tmdbOn = keys?.optBoolean("configured")
    val omdbOn = keys?.optBoolean("omdb")
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
        repeat(30) { delay(16); if (runCatching { paneReq.requestFocus() }.isSuccess) return@LaunchedEffect }   // (up to ~0.5 s: a slow frame building the new section no longer leaves Right doing nothing)
    }
    fun goPane(s: Sec) { shown = s; enterPane++ }
    fun backToSections() { runCatching { secReqs.getValue(shown).requestFocus() } }
    // Back in the pane returns to the section column; Back there falls through to the app (act.back(), then the menu).
    BackHandler(enabled = inPane && menu == null && pinAsk == null) { backToSections() }

    val rows = sectionRows(act, shown, v + tick, ::say, { t, o -> menuSelected = null; menu = t to o }, { t, o, sel -> menuSelected = sel; menu = t to o }, ::askPin, { choosePin() }, ::ask, tmdbOn, omdbOn) { tick++ }

    Column(Modifier.fillMaxSize()) {
        PageTitle("Settings", "Shared with your phone and computer through your server")
        Row(Modifier.weight(1f).padding(start = Tv.margin - Tv.s3, end = Tv.margin)) {
            Column(Modifier.width(SettingsDims.sectionColumn).fillMaxHeight().onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionRight) { goPane(focusedSec); true } else false
            }, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                for (s in Sec.entries) SectionItem(s.icon, s.title, s == shown, secReqs.getValue(s), onFocus = { focusedSec = s }) { goPane(s) }
            }
            Box(Modifier.padding(horizontal = Tv.s5).width(1.dp).fillMaxHeight().background(Tv.line))
            val firstFocusable = rows.indexOfFirst { it.focusable }
            val paneState = rememberLazyListState()
            LaunchedEffect(shown) { paneState.scrollToItem(0) }   // a new section always opens at its top
            // Dense list: edge scrolling with a peek of the next row; held Up/Down keeps TvActivity's list pace (Tv.heldListMs).
            WithScroll(TvScroll.edge) {
                LazyColumn(Modifier.weight(1f).widthIn(max = SettingsDims.paneMax).fillMaxHeight().onFocusChanged { inPane = it.hasFocus }.onPreviewKeyEvent { e ->
                    if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionLeft) { backToSections(); true } else false
                }, state = paneState, contentPadding = PaddingValues(bottom = Tv.s12), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    item(key = "head-${shown.name}") { SettingsPaneTitle(shown.title) }
                    itemsIndexed(rows, key = { i, _ -> "${shown.name}-$i" }) { i, r ->
                        // The first row brings the pane's title back in; a row under a group heading brings the heading.
                        val reach = when { i == firstFocusable -> SettingsDims.toTop; i > 0 && rows[i - 1] is SettingRow.Header -> headerReach(first = i - 1 == 0); else -> 0.dp }
                        SettingRowView(r, (if (i == firstFocusable) Modifier.focusRequester(paneReq) else Modifier).revealAbove(reach), first = i == 0)
                    }
                }
            }
        }
    }
    Box(Modifier.fillMaxSize().padding(end = Tv.margin, bottom = 32.dp), contentAlignment = Alignment.BottomEnd) { SettingsToast(toast) }

    form?.let { f ->
        key(f) {
            FormDialog(f.title, f.intro, f.fields, f.submit, formBusy, formError, onSubmit = { vals ->
                formBusy = true; formError = null
                scope.launch {
                    val err = runCatching { f.save(vals) }.getOrElse { it.message ?: "That didn't work" }
                    formBusy = false
                    if (err == null) { form = null; tick++ } else formError = err
                }
            }) { form = null }
        }
    }
    menu?.let { (title, options) -> Options(title, options.map { (l, fn) -> l to { menu = null; fn() } }, selected = menuSelected) { menu = null } }
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

/** The rows of one section. */
private fun sectionRows(
    act: TvActivity, sec: Sec, @Suppress("UNUSED_PARAMETER") stamp: Int, say: (String) -> Unit,
    menu: (String, List<Pair<String, () -> Unit>>) -> Unit,
    chooser: (String, List<Pair<String, () -> Unit>>, String?) -> Unit,   // a menu of choices with the current one marked
    askPin: (String, Boolean, (String) -> Unit) -> Unit,
    choosePin: () -> Unit, ask: (FormAsk) -> Unit, tmdbOn: Boolean?, omdbOn: Boolean?, changed: () -> Unit,
): List<SettingRow> {
    fun refreshLists() { Repo.clearCache(); say("Channel lists will download again") }
    val player = act.getSharedPreferences("player", android.content.Context.MODE_PRIVATE)
    return when (sec) {
        Sec.Playlists -> {
            val s = Repo.syncData.optJSONObject("sources") ?: JSONObject()
            val sources = s.keys().asSequence().mapNotNull { k -> s.optJSONObject(k)?.let { k to it } }.filter { !it.second.optBoolean("deleted") }.toList()
            listOf(SettingRow.Header("Your playlists")) + sources.map { (key, src) ->
                val x = src.optJSONObject("xtream")
                val what = when (src.optString("content")) { "live" -> "Live TV"; "vod" -> "Movies & series"; else -> "Live TV, movies & series" }
                val name = src.optString("name").ifBlank { if (x != null) "Xtream playlist" else "Playlist" }
                SettingRow.Action(if (x != null) Icons.Default.Cloud else Icons.Default.Link, name, what, if (x != null) "Xtream" else "M3U", opens = true) {
                    menu(name, listOf(
                        "Refresh now" to ::refreshLists,
                        "Edit" to { ask(playlistForm(key, src)) },
                        "Remove playlist" to {
                            ask(FormAsk("Remove \"$name\"?", "Its channels, films and series disappear from this TV and your other devices.", emptyList(), "Remove") { Repo.removeSource(key); say("Playlist removed"); null })
                        },
                    ))
                }
            } + listOf(
                SettingRow.Action(Icons.Default.PlaylistAdd, "Add playlist", "An Xtream login or an M3U link", opens = true) {
                    menu("Add playlist", listOf("Xtream login (server, username, password)" to { ask(playlistForm(null, JSONObject().put("kind", "xtream"))) },
                        "M3U playlist link" to { ask(playlistForm(null, JSONObject().put("kind", "url"))) }))
                },
                SettingRow.Header("Maintenance"),
                SettingRow.Action(Icons.Default.Refresh, "Refresh channel lists", "Download every playlist again", count(sources.size, "playlist"), onClick = ::refreshLists),
            )
        }
        Sec.Profiles -> {
            val list = Repo.profiles(); val cur = Repo.profileId
            buildList {
                add(SettingRow.Header("Who's watching"))
                for (p in list) add(SettingRow.Action(if (p.id == cur) Icons.Default.AccountCircle else Icons.Default.Person, p.name,
                    if (p.id == cur) "Watching on this TV" else "Their own Continue watching, favourites and lists", if (p.id == cur) "This TV" else "", opens = true) {
                    menu(p.name, listOfNotNull(
                        (if (p.avatar.isEmpty()) "Choose a picture" else "Change picture") to { act.pictureFor = p },
                        if (p.id != cur) "Switch to ${p.name}" to { act.pickProfile(p, Repo.rememberProfile) } else null,
                        "Rename" to { ask(FormAsk("Rename ${p.name}", null, listOf(FormField("Name", if (p.named) p.name else "", "e.g. Will")), "Save") { (n) ->
                            if (n.isBlank()) "Enter a name" else { Repo.saveProfile(p.id, n, p.color); say("Saved"); null } }) },
                        if (p.id != Profiles.MAIN) "Remove profile" to { ask(FormAsk("Remove ${p.name}?", "Their Continue watching, favourites and lists go too, on every device. Playlists stay.", emptyList(), "Remove") {
                            Repo.saveProfile(p.id, null); act.refresh++; say("Removed ${p.name}"); null }) } else null,
                    ))
                })
                if (list.size > 1) add(SettingRow.Toggle(Icons.Default.PushPin, "Remember on this TV", "Open straight into ${Repo.currentProfile().name} instead of asking who's watching", Repo.rememberProfile) { Repo.rememberProfile = it; changed() })
                if (list.size < Profiles.MAX) add(SettingRow.Action(Icons.Default.PersonAdd, "Add profile", "Up to ${Profiles.MAX}. No passwords: anyone can pick one", opens = true) {
                    val mainUnnamed = !list.first().named
                    ask(FormAsk("Add profile", "Their own Continue watching, favourites and lists. Playlists and settings stay shared.",
                        (if (mainUnnamed) listOf(FormField("Your name (the first profile)", "", "e.g. Will")) else emptyList()) + FormField("New profile's name", "", "e.g. Mazie"), "Add") { vals ->
                        val name = vals.last().trim()
                        if (name.isEmpty()) return@FormAsk "Enter a name"
                        if (mainUnnamed && vals.first().isNotBlank()) Repo.saveProfile(Profiles.MAIN, vals.first(), 0)
                        Repo.saveProfile(Profiles.newId(), name, Profiles.nextColor(Repo.profiles()))
                        say("Added $name"); null
                    })
                })
                add(SettingRow.Note("Each person has their own Continue watching, favourites, Watch later, history, teams and reminders. Playlists, the PIN and settings are shared."))
            }
        }
        Sec.Live -> listOf(
            SettingRow.Header("Channels"),
            SettingRow.Toggle(Icons.Default.HighQuality, "Prefer 4K channels", "Start the 4K copy of a channel when there is one", Repo.fourKFirst) { Repo.fourKFirst = it; changed() },
            SettingRow.Toggle(Icons.Default.PictureInPicture, "Channel preview", "Play the highlighted channel in a small window", Repo.preview) { Repo.preview = it; changed() },
            SettingRow.Note("Channel preview uses your one provider connection while you browse."),
        )
        Sec.Guide -> {
            val upcoming = Repo.reminders.count { it.start > System.currentTimeMillis() }
            listOf(
                SettingRow.Action(Icons.Default.CalendarViewWeek, "Open the TV Guide", "Hold OK on a programme to set a reminder", if (upcoming == 0) "No reminders" else count(upcoming, "reminder")) { act.root(Screen.Guide()) },
                SettingRow.Note("The guide refreshes automatically every 3 hours."),
            )
        }
        Sec.Playback -> {
            val subs = player.getBoolean("textOn", false)
            val subLang = player.getString("textLang", null)
            val audioLang = player.getString("audioLang", null)
            fun langName(l: String?) = l?.let { java.util.Locale.forLanguageTag(it).getDisplayLanguage(java.util.Locale.UK).ifBlank { it } } ?: "Automatic"
            fun langMenu(title: String, prefKey: String) = chooser(title, listOf("Automatic" to { player.edit().remove(prefKey).apply(); changed() }) +
                listOf("en", "fr", "de", "es", "it", "pl", "pt", "ar", "tr", "nl").map { l -> langName(l) to { player.edit().putString(prefKey, l).apply(); changed() } },
                langName(player.getString(prefKey, null)))
            listOf(
                SettingRow.Header("Subtitles & audio"),
                SettingRow.Toggle(Icons.Default.ClosedCaption, "Subtitles", "Turn subtitles on for every film and episode that has them", subs) { player.edit().putBoolean("textOn", it).apply(); changed() },
                SettingRow.Action(Icons.Default.Subtitles, "Subtitle language", "Used when subtitles are on", langName(subLang), opens = true) { langMenu("Subtitle language", "textLang") },
                SettingRow.Action(Icons.Default.RecordVoiceOver, "Audio language", "Picked when a stream has more than one", langName(audioLang), opens = true) { langMenu("Audio language", "audioLang") },
                SettingRow.Header("Player"),
                SettingRow.Toggle(Icons.Default.ViewQuilt, "New player interface", "The redesigned controls, panels and info while watching", com.wmorgan97.m3uplayer.tv.player.PlayerUi.enabled(act)) { com.wmorgan97.m3uplayer.tv.player.PlayerUi.setEnabled(act, it); changed() },
                SettingRow.Toggle(Icons.Default.Tv, "Match frame rate", "Switch to the programme's rate: 50 Hz for TV, 24 Hz for films", com.wmorgan97.m3uplayer.tv.player.FrameRateMatcher.enabled(act)) { com.wmorgan97.m3uplayer.tv.player.FrameRateMatcher.setEnabled(act, it); changed() },
                SettingRow.Note("With frame-rate matching the screen blanks briefly when the rate changes. Audio and subtitle choices made while watching are remembered here. Watching live TV: Up/Down change channel, OK opens the channel list, Left/Right show the banner."),
            )
        }
        Sec.Parental -> {
            val hasPin = Repo.pin.isNotEmpty()
            val locked = Repo.lockedGroups.size
            buildList {
                add(SettingRow.Header("PIN"))
                add(SettingRow.Action(Icons.Default.Lock, if (hasPin) "Change PIN" else "Set a PIN", if (hasPin) "Enter your current PIN first" else "Then lock groups from Live TV", if (hasPin) "On" else "Off", opens = true) {
                    if (hasPin) askPin("Enter your current PIN", true) { choosePin() } else choosePin()
                })
                if (hasPin) {
                    add(SettingRow.Action(Icons.Default.LockOpen, "Remove PIN", "Unlocks every group", danger = true, opens = true) {
                        askPin("Enter your PIN", true) { Repo.pin = ""; Repo.unlocked.clear(); say("PIN removed") }
                    })
                }
                add(SettingRow.Header("Groups"))
                add(SettingRow.Action(Icons.Default.FolderOff, "Locked groups", "Hold OK on a group in Live TV to lock it", if (locked == 0) "None" else count(locked, "group")) { act.root(Screen.Live()) })
                if (hasPin) {
                    add(SettingRow.Action(Icons.Default.LockClock, "Lock opened groups again", "Groups opened with the PIN this session", "${Repo.unlocked.size} open") {
                        Repo.unlocked.clear(); Repo.version++; say("Locked groups need the PIN again")
                    })
                }
            }
        }
        Sec.Interface -> {
            val hidden = Repo.hiddenGroups.size + Repo.hiddenChannels.size
            listOf(
                SettingRow.Header("Lists"),
                SettingRow.Toggle(Icons.Default.Numbers, "Show channel numbers", "Numbers beside channels in lists, the guide and the banner", Repo.showNumbers) { Repo.showNumbers = it; changed() },
                SettingRow.Action(Icons.Default.Visibility, "Show hidden groups and channels", if (hidden == 0) "Hold OK on a group or channel to hide it" else hiddenNames(), if (hidden == 0) "None" else "$hidden hidden") {
                    if (hidden == 0) say("Nothing is hidden") else { Repo.showAllHidden(); say("$hidden item${if (hidden == 1) "" else "s"} shown again") }
                },
                SettingRow.Header("Content"),
                SettingRow.Toggle(Icons.Default.Language, "English groups only", "Hide other-language groups, films and series", Repo.englishOnly) { Repo.englishOnly = it; Repo.version++ },
                SettingRow.Header("Sports"),
                SettingRow.Toggle(Icons.Default.VisibilityOff, "Hide scores", "Live and finished games say \"Live\" or \"Full time\" instead of the score", Repo.hideScores) { Repo.hideScores = it; changed() },
            )
        }
        Sec.Data -> listOf(
            SettingRow.Action(Icons.Default.Sync, "Sync now", "Favourites, history and playlists from your other devices", if (Repo.online) "Online" else "Offline", opens = true) {
                ask(FormAsk("Sync now?", "Fetches favourites, what you've watched and playlists from your server.", emptyList(), "Sync") { Repo.sync(); act.refresh++; say("Up to date"); null })
            },
            SettingRow.Action(Icons.Default.DeleteSweep, "Clear saved data", "Channel lists, catalogues and the guide download again", opens = true) {
                menu("Clear saved data? Channel lists, catalogues and the guide download again next time.", listOf("Clear saved data" to { Repo.clearCache(); say("Saved data cleared") }))
            },
            SettingRow.Note(if (Repo.online) "Connected to your server." else "Offline: showing saved data until the server is reachable."),
        )
        Sec.About -> {
            val host = Repo.server.removePrefix("https://").removePrefix("http://")
            val syncError = Repo.lastSyncError
            listOf(
                SettingRow.Header("Connection"),
                SettingRow.Action(Icons.Default.Dns, "Server", host, if (Repo.online) "Connected" else "Offline", opens = true) {
                    ask(FormAsk("Your server", "The address and key shown at the end of the server install.",
                        listOf(FormField("Server address", Repo.server, "https://your-server.ts.net:8443", url = true), FormField("Server key", Repo.key, "Server key", secret = true))) { (addr, k) ->
                        val a = addr.trimEnd('/').let { if (it.startsWith("http")) it else "https://$it" }
                        if (k.isEmpty()) return@FormAsk "Enter the server key"
                        val oldS = Repo.server; val oldK = Repo.key
                        Repo.saveConfig(k, a)
                        try { Repo.sync(); Repo.clearCache(); act.refresh++; say("Connected to your server"); null }
                        catch (e: Exception) { Repo.saveConfig(oldK, oldS); "Couldn't connect with that address and key" }
                    })
                },
                SettingRow.Action(Icons.Default.Movie, "Film & TV info (TMDB)", "Trending, backdrops, plots and episode pictures", when (tmdbOn) { true -> "Connected"; false -> "Not set up"; null -> "" }, opens = true) {
                    ask(FormAsk("Film & TV info (TMDB)", "Get a free key at themoviedb.org (Settings, API): the API Read Access Token or the API Key. It's kept on your server only." +
                        if (tmdbOn == true) " A key is already saved: enter a new one to replace it." else "",
                        listOf(FormField("TMDB key", "", if (tmdbOn == true) "Saved on your server" else "Paste your TMDB key", secret = true))) { (k) ->
                        if (k.isEmpty()) return@FormAsk if (tmdbOn == true) null else "Enter a TMDB key"   // empty: keep the saved key
                        Repo.setTmdbKey(k) ?: run { say(if (k.isEmpty()) "TMDB key removed" else "TMDB connected"); null }
                    })
                },
                SettingRow.Action(Icons.Default.StarRate, "Ratings (OMDb)", "Adds IMDb and Rotten Tomatoes ratings", when (omdbOn) { true -> "Connected"; false -> "Not set up"; null -> "" }, opens = true) {
                    ask(FormAsk("Ratings (OMDb)", "Get a free key at omdbapi.com (API Key, Free). It's kept on your server only; ratings are looked up when a page opens and kept for weeks. Ratings by OMDb, non-commercial use." +
                        if (omdbOn == true) " A key is already saved: enter a new one to replace it." else "",
                        listOf(FormField("OMDb key", "", if (omdbOn == true) "Saved on your server" else "Paste your OMDb key", secret = true))) { (k) ->
                        if (k.isEmpty()) return@FormAsk if (omdbOn == true) null else "Enter an OMDb key"   // empty: keep the saved key
                        Repo.setOmdbKey(k) ?: run { say("Ratings connected"); changed(); null }
                    })
                },
                SettingRow.Action(Icons.Default.NetworkCheck, "Check connection", "Contact your server now and report back") {
                    act.lifecycleScope.launch {
                        val ok = runCatching { Repo.sync() }.isSuccess
                        say(if (ok) "Your server answered" else "Couldn't reach your server"); changed()
                    }
                },
            ) + SettingsAbout.diagnostics(act, syncError)
        }
    }
}

internal fun count(n: Int, one: String) = "$n $one${if (n == 1) "" else "s"}"

/** Add or edit a playlist: an Xtream login (server, username, password) or an M3U link (and its TV guide link). */
internal fun playlistForm(key: String?, src: JSONObject): FormAsk {
    val xtream = src.optString("kind") == "xtream"
    val x = src.optJSONObject("xtream") ?: JSONObject()
    val content = src.optString("content").ifBlank { "both" }
    val title = if (key == null) (if (xtream) "Add an Xtream login" else "Add an M3U playlist") else "Edit ${src.optString("name").ifBlank { "playlist" }}"
    return if (xtream) FormAsk(title, "From your provider's welcome email.", listOf(
        FormField("Name", src.optString("name"), "My TV"),
        FormField("Server address", x.optString("server"), "http://provider.example:8080", url = true),
        FormField("Username", x.optString("user")),
        FormField("Password", x.optString("pass"), secret = true))) { (name, server0, user, pass) ->
        val server = server0.trimEnd('/').let { if (it.isEmpty() || it.startsWith("http")) it else "http://$it" }
        when {
            server.isEmpty() || user.isEmpty() || pass.isEmpty() -> "Fill in the server, username and password"
            else -> { Repo.saveSource(Repo.sourceKey("xtream", server, user, "", content), JSONObject().put("name", name.ifBlank { "Xtream playlist" }).put("kind", "xtream")
                .put("xtream", JSONObject().put("server", server).put("user", user).put("pass", pass)).put("content", content), key); null }
        }
    } else FormAsk(title, "A link ending in .m3u or .m3u8 (and, if your provider gives one, its TV guide link).", listOf(
        FormField("Name", src.optString("name"), "My playlist"),
        FormField("Playlist link", src.optString("sourceUrl"), "http://…/playlist.m3u", url = true),
        FormField("TV guide link (optional)", src.optString("epgUrl"), "http://…/epg.xml", url = true))) { (name, link, epg) ->
        if (!link.startsWith("http")) "Enter a link starting with http:// or https://"
        else { Repo.saveSource(Repo.sourceKey("url", "", "", link, content), JSONObject().put("name", name.ifBlank { "Playlist" }).put("kind", "url").put("sourceUrl", link)
            .put("content", content).apply { if (epg.isNotEmpty()) put("epgUrl", epg) }, key); null }
    }
}


/** What is hidden on this TV, by name ("Group, Channel, …"), so a group hidden by accident can be found again. */
private fun hiddenNames(): String {
    val chans = Repo.live?.channels.orEmpty().associateBy { it.url }
    val names = Repo.hiddenGroups.sorted() + Repo.hiddenChannels.mapNotNull { chans[it]?.name ?: "1 channel no longer in your playlists" }
    return "Hidden: " + names.take(4).joinToString(", ") + if (names.size > 4) " and ${names.size - 4} more" else ""
}
