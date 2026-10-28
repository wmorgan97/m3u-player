package com.wmorgan97.m3uplayer.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RecentsAndRedactTest {
    private data class R(val url: String, val at: Long)
    private fun tidy(l: List<R>, exists: ((String) -> Boolean)?, cap: Int = 20) = tidyRecents(l, { it.url }, { it.at }, exists, cap)

    @Test fun dedupesKeepingNewest() {
        val out = tidy(listOf(R("a", 1), R("b", 5), R("a", 9), R("c", 3)), null)
        assertEquals(listOf(R("a", 9), R("b", 5), R("c", 3)), out)
    }

    @Test fun dropsGoneChannelsOnlyWhenListLoaded() {
        val l = listOf(R("a", 2), R("gone", 3))
        assertEquals(listOf("gone", "a"), tidy(l, null).map { it.url })
        assertEquals(listOf("a"), tidy(l, { it == "a" }).map { it.url })
    }

    @Test fun capsAndSkipsEmptyUrls() {
        val l = (1..30).map { R("u$it", it.toLong()) } + R("", 99)
        val out = tidy(l, null)
        assertEquals(20, out.size); assertEquals("u30", out.first().url)
    }

    @Test fun redactsXtreamAndQueries() {
        assertEquals("http://h.tv:8080/live/***/***/123.ts", redactUrl("http://h.tv:8080/live/bob/s3cret/123.ts"))
        assertEquals("http://h.tv/movie/***/***/9.mkv", redactUrl("http://h.tv/movie/bob/s3cret/9.mkv"))
        assertEquals("http://h.tv/***/***/77", redactUrl("http://h.tv/bob/s3cret/77"))
        assertEquals("https://h.tv/get.php", redactUrl("https://h.tv/get.php?username=bob&password=s3cret&type=m3u"))
        assertEquals("https://h.tv/list.m3u", redactUrl("https://bob:s3cret@h.tv/list.m3u#frag"))
        assertEquals("http://h.tv", redactUrl("http://h.tv"))
        for (u in listOf("http://h.tv/timeshift/bob/s3cret/60/2026-10-25:19-30/1.ts", "http://h.tv/series/bob/s3cret/5.mp4"))
            assertFalse(redactUrl(u).contains("s3cret") || redactUrl(u).contains("bob"))
    }
}
