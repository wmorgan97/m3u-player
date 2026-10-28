package com.wmorgan97.m3uplayer.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuideDataTest {
    private fun p(s: Long, e: Long, t: String = "P$s") = Programme(s, e, t, "")
    private fun ch(name: String, id: String = "", tvgName: String = "") = Channel(name, "http://x/$name", "", "", id, tvgName, 1)

    @Test fun sortsUnsortedInput() {
        val out = tidyProgrammes(listOf(p(30, 40), p(10, 20), p(20, 30)))
        assertEquals(listOf(10L, 20L, 30L), out.map { it.start })
    }

    @Test fun dropsZeroAndNegativeDurations() {
        val out = tidyProgrammes(listOf(p(10, 10), p(20, 15), p(30, 40)))
        assertEquals(listOf(p(30, 40)), out)
    }

    @Test fun dropsDuplicates() {
        val out = tidyProgrammes(listOf(p(10, 20, "News"), p(10, 20, "News"), p(20, 30, "Film")))
        assertEquals(listOf(p(10, 20, "News"), p(20, 30, "Film")), out)
    }

    @Test fun sameStartKeepsTheLongest() {
        val out = tidyProgrammes(listOf(p(10, 15, "Short"), p(10, 30, "Long"), p(30, 40)))
        assertEquals(listOf("Long", "P30"), out.map { it.title })
    }

    @Test fun trimsOverlapsToNextStart() {
        val out = tidyProgrammes(listOf(p(10, 25, "A"), p(20, 40, "B"), p(35, 50, "C")))
        assertEquals(listOf(p(10, 20, "A"), p(20, 35, "B"), p(35, 50, "C")), out)
        for (i in 1 until out.size) assertTrue(out[i - 1].end <= out[i].start)
    }

    @Test fun dropsProgrammesThatAlreadyEnded() {
        val out = tidyProgrammes(listOf(p(0, 10), p(10, 20), p(20, 30)), dropEndingBefore = 15)
        assertEquals(listOf(10L, 20L), out.map { it.start })
        assertEquals(listOf(20L), dropFinished(out, 20).map { it.start })
        assertEquals(emptyList<Programme>(), dropFinished(out, 30))
    }

    @Test fun nowNextOnTidiedList() {
        val l = tidyProgrammes(listOf(p(10, 25, "A"), p(20, 40, "B"), p(50, 60, "C")))
        assertEquals("B", nowNextIn(l, 22).first?.title)          // A was trimmed to end at 20
        assertEquals("C", nowNextIn(l, 22).second?.title)
        assertNull(nowNextIn(l, 45).first)                        // gap
        assertEquals("C", nowNextIn(l, 45).second?.title)
        assertNull(nowNextIn(l, 60).first); assertNull(nowNextIn(l, 60).second)
    }

    @Test fun matchesByTvgIdFirst() {
        val progs = mapOf("bbc1.uk" to listOf(p(0, 1)), "other" to listOf(p(0, 1)))
        val names = mapOf("bbc1" to "other")
        assertEquals("bbc1.uk", guideKeyFor(ch("BBC One", id = "BBC1.uk"), progs, names))
    }

    @Test fun fallsBackToNameWhenIdMissingOrUnknown() {
        val progs = mapOf("bbc1.uk" to listOf(p(0, 1)))
        val names = mapOf("bbc1" to "bbc1.uk")
        assertEquals("bbc1.uk", guideKeyFor(ch("UK: BBC One HD"), progs, names))
        assertEquals("bbc1.uk", guideKeyFor(ch("BBC One", id = "nope.id"), progs, names))
        assertEquals("bbc1.uk", guideKeyFor(ch("Channel 101", tvgName = "BBC One"), progs, names))
    }

    @Test fun emptyIdOrNameNeverMatches() {
        // A guide that (wrongly) has an empty key must not attach to channels without a tvg-id or a usable name.
        val progs = mapOf("" to listOf(p(0, 1)), "x" to listOf(p(0, 1)))
        val names = mapOf("" to "x")
        assertNull(guideKeyFor(ch("HD"), progs, names))            // normalises to ""
        assertNull(guideKeyFor(ch("Unrelated"), progs, names))
        assertNull(guideKeyFor(ch("  ", id = "  "), progs, names))
    }

    @Test fun nameMatchToMissingProgrammesIsNoMatch() {
        assertNull(guideKeyFor(ch("BBC One"), emptyMap<String, Any>(), mapOf("bbc1" to "bbc1.uk")))
    }
}
