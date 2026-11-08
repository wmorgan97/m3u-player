package com.wmorgan97.m3uplayer.tv

import android.content.SharedPreferences
import kotlinx.coroutines.launch

// How often the playlists and the TV guide update by themselves (Settings > Playlists, Settings > TV Guide), like TiviMate's
// "update playlist every" / "update EPG every". The decision itself is the pure [RefreshPolicy] (unit-tested); [RefreshPrefs]
// only stores the choices.

/** An update interval: [hours] > 0 is "every N hours", [APP_START] is "only when the app opens", [MANUAL] is "only when asked". */
object RefreshPolicy {
    const val APP_START = -1
    const val MANUAL = 0
    const val DEFAULT_PLAYLIST_H = 12
    const val DEFAULT_GUIDE_H = 6
    val PLAYLIST_CHOICES = listOf(APP_START, 1, 2, 3, 4, 6, 8, 12, 24, 48, 72, MANUAL)
    val GUIDE_CHOICES = listOf(1, 2, 3, 4, 6, 8, 12, 24, 48, 72, MANUAL)
    /** "Update on app start" never refetches data younger than this (opening the app twice in a row downloads once). */
    const val START_GAP_MS = 60 * 60_000L
    private const val HOUR = 3_600_000L

    fun label(i: Int) = when (i) { APP_START -> "App start only"; MANUAL -> "Manual only"; 1 -> "1 hour"; else -> "$i hours" }
    fun short(i: Int) = when (i) { APP_START -> "App start"; MANUAL -> "Manual"; else -> "$i h" }

    /**
     * Is an automatic update due? [lastAt] is when the saved copy was made (0: none yet), [atStart] is true for the first check
     * after the app opened. Nothing saved yet is always due (there is nothing to keep); a copy dated in the future is treated
     * as due too (a clock that was set back must not freeze updates). Manual only never updates by itself, not even at start.
     */
    fun due(interval: Int, onStart: Boolean, lastAt: Long, now: Long, atStart: Boolean): Boolean {
        if (lastAt <= 0L || lastAt > now + HOUR) return true
        if (interval == MANUAL) return false
        val age = now - lastAt
        if (atStart && (onStart || interval == APP_START) && age > START_GAP_MS) return true
        if (interval == APP_START) return false
        return age > interval * HOUR
    }
}

object RefreshPrefs {
    @Volatile private var p: SharedPreferences? = null
    fun init(prefs: SharedPreferences) { p = prefs }

    /** True until the first background check after the app opened has looked at it (see [takeStart]). */
    @Volatile private var startPending = true
    /** The first call after the app opened returns true, every later one false. */
    @Synchronized fun takeStart(): Boolean { val s = startPending; startPending = false; return s }

    var onStart: Boolean
        get() = p?.getBoolean("refresh.onStart", true) ?: true
        set(v) { p?.edit()?.putBoolean("refresh.onStart", v)?.apply() }

    fun playlist(key: String): Int = p?.getInt("refresh.pl.$key", RefreshPolicy.DEFAULT_PLAYLIST_H) ?: RefreshPolicy.DEFAULT_PLAYLIST_H
    fun setPlaylist(key: String, v: Int) { p?.edit()?.putInt("refresh.pl.$key", v)?.apply() }

    var guide: Int
        get() = p?.getInt("refresh.guide", RefreshPolicy.DEFAULT_GUIDE_H) ?: RefreshPolicy.DEFAULT_GUIDE_H
        set(v) { p?.edit()?.putInt("refresh.guide", v)?.apply() }

    fun playlistAt(key: String): Long = p?.getLong("refresh.at.$key", 0L) ?: 0L
    fun setPlaylistAt(key: String, v: Long) { p?.edit()?.putLong("refresh.at.$key", v)?.apply() }
    var guideAt: Long
        get() = p?.getLong("refresh.guideAt", 0L) ?: 0L
        set(v) { p?.edit()?.putLong("refresh.guideAt", v)?.apply() }

    fun playlistDue(key: String, lastAt: Long, now: Long, atStart: Boolean) = RefreshPolicy.due(playlist(key), onStart, lastAt, now, atStart)
    fun guideDue(lastAt: Long, now: Long, atStart: Boolean) = RefreshPolicy.due(guide, onStart, lastAt, now, atStart)
    /** How long a saved catalogue (films, series) is trusted: follows the playlists' shortest interval; [Long.MAX_VALUE] when all are manual. */
    fun catalogueMs(keys: List<String>): Long {
        val hs = keys.map { playlist(it) }
        if (hs.isEmpty()) return 12 * 3_600_000L
        val timed = hs.filter { it > 0 }
        return when { timed.isNotEmpty() -> timed.min() * 3_600_000L; hs.all { it == RefreshPolicy.MANUAL } -> Long.MAX_VALUE; else -> 24 * 3_600_000L }
    }
}


/** The glue between the choices above and the personal app's own loading code (Repo.kt calls these few functions). */
object RefreshHook {
    @Volatile var forcePlaylistsAt = 0L
    @Volatile var forceGuideAt = 0L
    private val startSeen = java.util.Collections.synchronizedSet(HashSet<String>())
    @Volatile private var guideStartDone = false
    private fun atStart(key: String) = startSeen.add(key)

    /** Is this playlist's saved download ([file]) due for a fresh download? Records the file's date as "last updated". */
    fun playlistStale(key: String, file: java.io.File): Boolean {
        if (!file.exists()) return true
        val mod = file.lastModified()
        if (mod < forcePlaylistsAt) return true
        if (RefreshPrefs.playlistAt(key) <= 0L) RefreshPrefs.setPlaylistAt(key, mod)
        return RefreshPrefs.playlistDue(key, mod, System.currentTimeMillis(), atStart(key))
    }
    fun playlistFetched(key: String) = RefreshPrefs.setPlaylistAt(key, System.currentTimeMillis())

    /** Should the guide for a channel be asked from the server again? [askedAt]: when this run last asked (0: not yet). */
    fun guideWanted(hasData: Boolean, askedAt: Long, now: Long): Boolean {
        if (askedAt < forceGuideAt) return true
        if (!hasData) return askedAt == 0L || now - askedAt > 3 * 3_600_000L   // nothing to show: always worth one ask
        val last = maxOf(askedAt, RefreshPrefs.guideAt)
        return RefreshPolicy.due(RefreshPrefs.guide, RefreshPrefs.onStart, last, now, !guideStartDone && askedAt == 0L)
    }
    fun guideFetched() { guideStartDone = true; RefreshPrefs.guideAt = System.currentTimeMillis() }
    fun updatePlaylistsNow() { forcePlaylistsAt = System.currentTimeMillis() }
    fun updateGuideNow() { forceGuideAt = System.currentTimeMillis() }
}

/** Background check, every 15 minutes while the app is open: refreshes what Settings says is due. Never while a channel,
 *  film or episode plays full screen (the player is checked again the next round). */
object RefreshScheduler {
    private var job: kotlinx.coroutines.Job? = null
    fun start(scope: kotlinx.coroutines.CoroutineScope) {
        if (job?.isActive == true) return
        job = scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            kotlinx.coroutines.delay(90_000)
            while (true) {
                if (com.wmorgan97.m3uplayer.PlayerActivity.current == null) runCatching { tick() }
                kotlinx.coroutines.delay(15 * 60_000L)
            }
        }
    }
    private suspend fun tick() {
        val now = System.currentTimeMillis()
        val srcs = Repo.syncData.optJSONObject("sources") ?: return
        val live = srcs.keys().asSequence().filter { k -> srcs.optJSONObject(k)?.let { !it.optBoolean("deleted") && it.optString("content") != "vod" } == true }.toList()
        // The saved download's own date decides (playlistStale); a round only re-reads the lists when one of them is due.
        if (live.any { RefreshPolicy.due(RefreshPrefs.playlist(it), RefreshPrefs.onStart, RefreshPrefs.playlistAt(it), now, false) }) {
            Repo.liveChannels(refresh = true)
        }
        val l = Repo.live ?: return
        if (RefreshPolicy.due(RefreshPrefs.guide, RefreshPrefs.onStart, RefreshPrefs.guideAt, now, false)) {
            RefreshHook.updateGuideNow()
            Repo.requestGuide(l.channels.take(200))
        }
    }
}
