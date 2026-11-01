package com.wmorgan97.m3uplayer.tv.player

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray

/**
 * The new player interface's state: what the overlays show, fed by PlayerActivity (Java), which keeps the engine, the
 * zapping, LiveBuffer and the remote's keys. The overlays only read this and send intents back through [Actions].
 *
 * Cheap by design: every field is a separate snapshot state, so a change recomposes only the overlay that reads it.
 * Nothing here changes per frame; the position ticks once a second, and only while the controls or info are up.
 */
class PlayerUi(private val actions: Actions) {

    /** Every button and row in the overlays ends up here: PlayerActivity runs it (a string switch: no Kotlin lambdas
     *  across the Java boundary). [arg]: a list index or a seek amount where the action needs one. */
    fun interface Actions { fun act(what: String, arg: Long) }
    /** A pick in an option panel (audio, subtitles, sleep, episodes): row index. */
    fun interface Pick { fun on(index: Int) }

    fun act(what: String, arg: Long = 0) = actions.act(what, arg)

    // ---------- what's playing ----------

    data class Media(
        val live: Boolean, val title: String, val subtitle: String, val num: String, val logo: String,
        val nowTitle: String, val nowStart: Long, val nowEnd: Long, val nextTitle: String, val desc: String,
        val hasPrev: Boolean, val hasNext: Boolean, val catchup: Boolean, val series: Boolean, val listTitle: String,
        val canList: Boolean, val last: String, val fromLive: Boolean,
    )
    var media by mutableStateOf(Media(true, "", "", "", "", "", 0, 0, "", "", false, false, false, false, "", false, "", false))
    var badges by mutableStateOf<List<String>>(emptyList())
    var playing by mutableStateOf(true)
    /** null: favourites don't apply (not a channel in your lists). */
    var favourite by mutableStateOf<Boolean?>(null)
    var aspect by mutableStateOf("Best fit")
    var sleep by mutableStateOf("Off")
    var frameMatch by mutableStateOf(false)
    var behindMs by mutableLongStateOf(0)
    var clock by mutableStateOf("")

    // Film position (once a second while the controls are up) and a skip being chosen (-1: none).
    var pos by mutableLongStateOf(0)
    var dur by mutableLongStateOf(0)
    var seekTo by mutableLongStateOf(-1)

    // ---------- surfaces ----------

    data class Banner(val num: String, val name: String, val logo: String, val nowTitle: String, val start: Long, val end: Long, val next: String, val fallback: String)
    var banner by mutableStateOf<Banner?>(null)
    var tuning by mutableStateOf(false)
    var controls by mutableStateOf(false)

    sealed interface Panel
    data class Options(val title: String, val rows: List<String>, val checked: Int, val pick: Pick) : Panel
    data class Channels(val rows: JSONArray, val current: Int, val last: String) : Panel
    /** Films / episodes of the list being played: image-led rows (still, number, title, runtime, progress). */
    data class Episodes(val title: String, val rows: JSONArray, val current: Int) : Panel
    object More : Panel
    var panel by mutableStateOf<Panel?>(null)

    data class Info(val title: String, val when_: String, val desc: String, val next: String, val video: String, val audio: String, val bitrate: String, val badges: List<String>)
    var info by mutableStateOf<Info?>(null)

    data class Err(val message: String, val code: String, val canLast: Boolean)
    var error by mutableStateOf<Err?>(null)

    var notice by mutableStateOf<String?>(null)
    var hud by mutableStateOf<String?>(null)

    data class Next(val still: Boolean, val label: String, val name: String, val sub: String, val art: String, val play: String, val other: String)
    var next by mutableStateOf<Next?>(null)
    /** The Play next fill: where it is (0..1), how long until it is full, and whether it is moving (paused: it waits). */
    data class Countdown(val from: Float, val leftMs: Long, val running: Boolean)
    var countdown by mutableStateOf(Countdown(0f, 0, false))

    // ---------- from Java ----------

    fun showBanner(num: String, name: String, logo: String, nowTitle: String, start: Long, end: Long, next: String, fallback: String) {
        banner = Banner(num, name, logo, nowTitle, start, end, next, fallback)
    }
    fun hideBanner() { banner = null; tuning = false }
    fun showOptions(title: String, rows: Array<String>, checked: Int, pick: Pick) { panel = Options(title, rows.toList(), checked, pick) }
    fun showChannels(rows: JSONArray, current: Int, last: String) { panel = Channels(rows, current, last) }
    fun showEpisodes(title: String, rows: JSONArray, current: Int) { panel = Episodes(title, rows, current) }
    fun showMore() { panel = More }
    fun closePanel() { panel = null }
    fun showError(message: String, code: String?, canLast: Boolean) { error = Err(message, code ?: "", canLast) }
    /** A side panel or the info sheet: the remote's arrows and OK belong to it. */
    fun modal() = panel != null || info != null
    /** Back inside a modal surface. */
    fun back() { if (panel != null) panel = null else info = null }
    fun tick(position: Long, duration: Long) { pos = position; dur = duration }

    companion object {
        private const val PREFS = "player"
        /** Setting: the new player interface (default: off until it has proven itself; see the Phase 2 report). */
        @JvmStatic fun enabled(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("newUi", false)
        @JvmStatic fun setEnabled(c: Context, on: Boolean) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("newUi", on).apply()
    }
}
