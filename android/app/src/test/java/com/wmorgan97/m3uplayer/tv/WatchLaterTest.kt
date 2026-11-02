package com.wmorgan97.m3uplayer.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchLaterTest {
    private val heat = Vod("movie", 101, "EN - Heat (1995)", "http://img/heat.jpg", "Action", 7.9, 1995, "mkv")
    private val show = Vod("series", 7, "Slow Horses", "http://img/sh.jpg", "Drama", null, 2022, "mp4")

    @Test fun keysMatchTheWebApp() {
        assertEquals("movie:101", WatchLater.key(heat))
        assertEquals("series:7", WatchLater.key(show))
        assertEquals("101", WatchLater.movieIdOf("http://prov:8080/movie/u/p/101.mkv"))
        assertEquals(null, WatchLater.movieIdOf("http://prov:8080/series/u/p/101.mkv"))
        val e = WatchLater.entry(heat, 5)
        assertEquals(heat.copy(), e.toVod().copy(overview = heat.overview))
        assertEquals("", WatchLater.entry(show, 5).ext)
    }

    @Test fun newestFirstRemovalsHidden() {
        val all = listOf(WatchLater.entry(heat, 100), WatchLater.entry(show, 200), WatchLater.removal("movie:5", 300))
        assertEquals(listOf("series:7", "movie:101"), WatchLater.visible(all, emptyMap()).map { it.key })
    }

    @Test fun finishedFilmsDropOffStartedOnesStay() {
        val all = listOf(WatchLater.entry(heat, 100), WatchLater.entry(show, 200))
        val started = WatchLater.finished(listOf(Triple("http://p/movie/u/p/101.mkv", false, 150L)))
        assertEquals(2, WatchLater.visible(all, started).size)
        val finished = WatchLater.finished(listOf(Triple("http://other/movie/u/p/101.mp4", true, 150L)))
        assertEquals(listOf("series:7"), WatchLater.visible(all, finished).map { it.key })
        // Added again after finishing it: back on the list.
        assertEquals(listOf("movie:101", "series:7"), WatchLater.visible(listOf(WatchLater.entry(heat, 400), WatchLater.entry(show, 200)), finished).map { it.key })
    }

    @Test fun newestEditWinsAndOldRemovalsAreForgotten() {
        val mine = mapOf("movie:101" to WatchLater.entry(heat, 10), "series:7" to WatchLater.removal("series:7", 30))
        val remote = mapOf("movie:101" to WatchLater.removal("movie:101", 20), "series:7" to WatchLater.entry(show, 25))
        val out = WatchLater.adopt(mine, remote)
        assertTrue(out.getValue("movie:101").deleted)
        assertTrue(out.getValue("series:7").deleted)
        val now = 200L * 86_400_000L
        assertEquals(emptyMap<String, WatchLaterEntry>(), WatchLater.prune(mapOf("movie:1" to WatchLater.removal("movie:1", now - 91L * 86_400_000L)), now))
    }
}
