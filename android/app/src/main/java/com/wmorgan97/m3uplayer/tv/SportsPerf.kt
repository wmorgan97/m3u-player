package com.wmorgan97.m3uplayer.tv

import android.os.SystemClock
import android.os.Trace
import android.util.Log

/** Load timing for the Sports page: `adb logcat -s M3U-SP` prints each step as "+<ms since the page opened> <step>",
 *  and each heavy step is an android.os.Trace section ("SP:<name>") for atrace / Perfetto. Cheap enough to leave on. */
object SportsPerf {
    private const val TAG = "M3U-SP"
    @Volatile private var openedAt = 0L
    @Volatile private var shown = false
    private val once = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    fun opened() { openedAt = SystemClock.uptimeMillis(); shown = false; once.clear(); Log.i(TAG, "+0 open (process up ${SystemClock.uptimeMillis() - android.os.Process.getStartUptimeMillis()} ms)") }
    private fun since() = if (openedAt == 0L) -1 else SystemClock.uptimeMillis() - openedAt
    fun mark(step: String) = Log.i(TAG, "+${since()} $step")
    /** Logged the first time only, per page visit. */
    fun first(step: String) { if (once.add(step)) mark(step) }
    /** The first real cards (not skeletons) are on screen. */
    fun content(what: String) { if (!shown) { shown = true; mark("CONTENT $what") } }

    inline fun <T> trace(name: String, block: () -> T): T {
        val t0 = SystemClock.uptimeMillis()
        Trace.beginSection("SP:$name")
        try { return block() } finally { Trace.endSection(); val ms = SystemClock.uptimeMillis() - t0; if (ms >= 20) mark("$name ${ms}ms") }
    }
}
