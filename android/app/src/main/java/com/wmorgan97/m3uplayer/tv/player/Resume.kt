package com.wmorgan97.m3uplayer.tv.player

import com.wmorgan97.m3uplayer.tv.Repo

/**
 * The one resume rule for films and episodes (shared: Details / Continue watching should use it too):
 * anything started and not finished carries on from where it was (from 5 s in); finished (95% / the last 90 s) starts
 * from the beginning; never started: no opinion (null, the caller's own start applies). "Start from the beginning" in
 * the player is the explicit way to restart.
 */
object Resume {
    /** Progress for test-hook items only (TvActivity testEpisodes): kept in memory, never synced. */
    val test = HashMap<String, Pair<Double, Double>>()

    private const val MIN_S = 5.0
    @JvmStatic fun finished(t: Double, d: Double) = d > 0 && (t >= d * 0.95 || d - t < 90)

    private fun saved(url: String): Triple<Double, Double, Boolean>? =
        test[url]?.let { (t, d) -> Triple(t, d, finished(t, d)) } ?: Repo.progressFor(url)?.let { Triple(it.t, it.d, it.done) }

    /** Where [url] should start (seconds): its saved point, 0 if finished, null if never started. */
    @JvmStatic fun startFor(url: String): Double? {
        val (t, _, done) = saved(url) ?: return null
        return if (done) 0.0 else if (t > MIN_S) t else null
    }

    /** For a row: how far through (0..1) and whether it's watched. Null: never started. */
    @JvmStatic fun state(url: String): Pair<Float, Boolean>? {
        val (t, d, done) = saved(url) ?: return null
        if (done) return 1f to true
        if (d <= 0 || t <= MIN_S) return null
        return (t / d).toFloat().coerceIn(0f, 1f) to false
    }
}
