package com.wmorgan97.m3uplayer.tv.player

import android.app.Activity
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Display
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Frame-rate matching: plays 25/50 fps TV at 50 Hz, 24p films at 24 Hz, and so on, instead of everything at the
 * Shield's 59.94/60 Hz (which judders: 25 fps on 60 Hz shows frames for 2,3,2,3… refreshes).
 *
 * How: the player window asks for a display mode ([android.view.WindowManager.LayoutParams.preferredDisplayModeId]) at
 * the SAME resolution as now, whose refresh rate is a whole multiple of the content's (preferring 48-61 Hz: 25 fps ->
 * 50 Hz, 23.976 -> 23.976, 29.97 -> 59.94). Android 11's Surface.setFrameRate only does seamless switches, and an HDMI
 * refresh change never is, so the window mode is the only route on a Shield.
 *
 * Guarded, because a switch blanks the TV for a second or two:
 *  - only when asked ([request]) — PlayerActivity asks once the rate is known and the channel has played steadily for
 *    2 s, never during a run of zaps;
 *  - one request per distinct target; a mode already active is left alone;
 *  - verified: the active mode is read back from the Display after the switch. If Android hasn't moved to it within
 *    5 s, the request is withdrawn and matching stops for this player session (no retry loops, no black-screen cycles);
 *  - [restore] withdraws the request (the system's own mode returns) when the player closes or goes to the background.
 */
class FrameRateMatcher(private val act: Activity) : DisplayManager.DisplayListener {
    private val dm = act.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    private val main = Handler(Looper.getMainLooper())
    private var wanted = 0            // the mode id we asked for (0: none)
    private var askedAt = 0L
    private var gaveUp = false
    var enabled = true
        set(v) { field = v; if (!v) restore() }

    private val display: Display? get() = act.window?.decorView?.display ?: act.windowManager.defaultDisplay

    init { dm.registerDisplayListener(this, main) }

    /** The best mode for [fps] content at the current resolution, or null (nothing better than what's on). */
    fun pick(fps: Float): Display.Mode? {
        val d = display ?: return null
        val cur = d.mode
        return choose(fps, cur.physicalWidth, cur.physicalHeight, d.supportedModes.map { Triple(it.modeId, it.physicalWidth to it.physicalHeight, it.refreshRate) })
            ?.let { id -> d.supportedModes.firstOrNull { it.modeId == id } }
    }

    /** Asks for the mode matching [fps]. Returns what it did, for the log. */
    fun request(fps: Float): String {
        if (!enabled) return "off"
        if (gaveUp) return "gave up earlier"
        val d = display ?: return "no display"
        val m = pick(fps) ?: return "no matching mode"
        val cur = d.mode
        if (m.modeId == cur.modeId) {
            if (wanted != 0 && wanted != m.modeId) setPreferred(0)   // (a stale request for another mode)
            return "already ${fmt(cur)}"
        }
        if (m.modeId == wanted) return "pending ${fmt(m)}"
        wanted = m.modeId
        askedAt = SystemClock.uptimeMillis()
        setPreferred(m.modeId)
        Log.i("M3U", String.format(java.util.Locale.ROOT, "frame rate match: content %.3f fps -> asking for mode %d (%s), active %d (%s)", fps, m.modeId, fmt(m), cur.modeId, fmt(cur)))
        val asked = wanted
        main.postDelayed({ verify(asked) }, 5_000)
        return "asked ${fmt(m)}"
    }

    private fun verify(asked: Int) {
        if (asked != wanted) return
        val cur = display?.mode ?: return
        if (cur.modeId == asked) return
        Log.w("M3U", "frame rate match: still on mode ${cur.modeId} (${fmt(cur)}) 5 s after asking for $asked; withdrawn, off for this session")
        gaveUp = true
        restore()
    }

    override fun onDisplayChanged(displayId: Int) {
        val d = display ?: return
        if (displayId != d.displayId || wanted == 0) return
        val cur = d.mode
        if (cur.modeId == wanted) Log.i("M3U", "frame rate match: active mode now ${cur.modeId} (${fmt(cur)}) after ${SystemClock.uptimeMillis() - askedAt}ms")
    }
    override fun onDisplayAdded(displayId: Int) {}
    override fun onDisplayRemoved(displayId: Int) {}

    /** Withdraws any request: the display goes back to the system's own mode. */
    fun restore() {
        if (wanted == 0) return
        wanted = 0
        setPreferred(0)
        Log.i("M3U", "frame rate match: request withdrawn (system mode restored)")
    }

    fun release() { restore(); dm.unregisterDisplayListener(this); main.removeCallbacksAndMessages(null) }

    /** The active mode, e.g. "3840x2160@50.00" (for the More panel / logs). */
    fun active(): String = display?.mode?.let { fmt(it) } ?: ""

    private fun setPreferred(id: Int) {
        val w = act.window ?: return
        val lp = w.attributes
        if (lp.preferredDisplayModeId == id) return
        lp.preferredDisplayModeId = id
        w.attributes = lp
    }

    companion object {
        private fun fmt(m: Display.Mode) = String.format(java.util.Locale.ROOT, "%dx%d@%.2f", m.physicalWidth, m.physicalHeight, m.refreshRate)

        /**
         * Pure choice (unit-tested): among [modes] (id, size, refresh) at [w]x[h], the one whose refresh is a whole
         * multiple of [fps] (within 0.05%: 59.94 is not 60), preferring 48-61 Hz, then the lowest multiple. Null if none fits or [fps]
         * isn't a plausible video rate.
         */
        @JvmStatic fun choose(fps: Float, w: Int, h: Int, modes: List<Triple<Int, Pair<Int, Int>, Float>>): Int? {
            if (fps.isNaN() || fps < 10f || fps > 121f) return null
            val fits = modes.filter { (_, size, r) -> size.first == w && size.second == h && r > 0 && run {
                val k = (r / fps).roundToInt(); k >= 1 && abs(r - k * fps) <= r * 0.0005f } }
            if (fits.isEmpty()) return null
            return fits.minWithOrNull(compareBy<Triple<Int, Pair<Int, Int>, Float>>({ if (it.third in 47.5f..61f) 0 else 1 }, { (it.third / fps).roundToInt() }, { abs(it.third - (it.third / fps).roundToInt() * fps) }))?.first
        }

        @JvmStatic fun enabled(c: Context) = c.getSharedPreferences("player", Context.MODE_PRIVATE).getBoolean("frameMatch", DEFAULT_ON)
        @JvmStatic fun setEnabled(c: Context, on: Boolean) = c.getSharedPreferences("player", Context.MODE_PRIVATE).edit().putBoolean("frameMatch", on).apply()
        /** Default: decided by what the Shield actually does (see the Phase 2 report). */
        const val DEFAULT_ON = false
    }
}
