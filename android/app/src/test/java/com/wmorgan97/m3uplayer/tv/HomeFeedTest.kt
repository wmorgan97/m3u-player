package com.wmorgan97.m3uplayer.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeFeedTest {
    private fun v(t: String, kind: String = "movie", id: Long = t.hashCode().toLong()) = Vod(kind, id, t, "", "", null, null, "mp4")

    @Test fun interleaveAlternatesAndDropsRepeats() {
        val out = interleave(listOf(listOf(v("A"), v("B"), v("C")), listOf(v("X", "series"), v("A", "series"))))
        assertEquals(listOf("A", "X", "B", "C"), out.map { it.title })
    }

    @Test fun dedupeKeepsRankedRowsWhole() {
        val top = HomeRow("top-m", "Top 10", (1..10).map { v("T$it") }, ranked = true)
        val next = HomeRow("new", "New", (1..10).map { v("T$it") } + (1..8).map { v("N$it") })
        val r = dedupe(listOf(top, next))
        assertEquals(10, r[0].items.size)
        assertEquals((1..8).map { "N$it" }, r[1].items.map { it.title })
    }

    @Test fun dedupeFallsBackToAdjacentRowWhenTooThin() {
        val a = HomeRow("a", "A", (1..10).map { v("T$it") })
        val b = HomeRow("b", "B", (1..10).map { v("U$it") })
        val c = HomeRow("c", "C", (1..10).map { v("T$it") } + listOf(v("U1"), v("Z")))   // only 1 new title vs all rows; vs B alone: 11 left
        val r = dedupe(listOf(a, b, c))
        assertTrue(r[2].items.none { it.title == "U1" })
        assertTrue(r[2].items.any { it.title == "T1" })
    }

    @Test fun myListIsNeverTrimmed() {
        val a = HomeRow("a", "A", (1..10).map { v("T$it") })
        val mine = HomeRow("mine", "My list", listOf(v("T1")))
        assertEquals(1, dedupe(listOf(a, mine))[1].items.size)
    }
}
