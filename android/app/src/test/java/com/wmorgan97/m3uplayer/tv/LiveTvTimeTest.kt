package com.wmorgan97.m3uplayer.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiveTvTimeTest {
    private val m = 60_000L
    private fun p(s: Long, e: Long) = Programme(s * m, e * m, "x", "")

    @Test fun fractionIsClamped() {
        assertEquals(0.5f, liveFraction(p(0, 60), 30 * m), 1e-4f)
        assertEquals(0f, liveFraction(p(10, 60), 0), 0f)
        assertEquals(1f, liveFraction(p(0, 60), 90 * m), 0f)
        assertEquals(0f, liveFraction(p(10, 10), 10 * m), 0f)
    }

    @Test fun timeLeft() {
        assertEquals("45 min left", liveLeftLabel(p(0, 60), 15 * m))
        assertEquals("1h 05m left", liveLeftLabel(p(0, 120), 55 * m))
        assertEquals("Ending", liveLeftLabel(p(0, 60), 60 * m - 10_000))
        assertNull(liveLeftLabel(p(0, 60), 60 * m))
        assertNull(liveLeftLabel(p(10, 60), 5 * m))
    }

    @Test fun startsIn() {
        assertEquals("Starts in 20 min", liveStartsIn(p(30, 60), 10 * m))
        assertEquals("Starts in 1h 10m", liveStartsIn(p(80, 90), 10 * m))
        assertNull(liveStartsIn(p(0, 60), 10 * m))
        assertNull(liveStartsIn(p(13 * 60, 14 * 60), 0))
    }

    @Test fun pagingStaysInsideTheGuide() {
        val span = 120 * m; val earliest = 0L; val latest = 24 * 60 * m
        assertEquals(span, guidePage(0, 1, span, earliest, latest))
        assertEquals(0L, guidePage(60 * m, -1, span, earliest, latest))
        assertEquals(latest - span, guidePage(latest - 90 * m, 1, span, earliest, latest))
    }

    // Window maths. Times are minutes from a half-hour line (offset 0 = UTC) so they don't depend on the machine's zone.
    private val z = 0L
    private fun pg(s: Long, e: Long, t: String = "x") = Programme(s * m, e * m, t, "")

    @Test fun startRoundsDownToFiveMinutes() {
        assertEquals(20 * m, guideStart(23 * m + 59_000))
        assertEquals(20 * m, guideStart(20 * m))
        assertEquals(25 * m, guideStart(25 * m + 1))
    }

    @Test fun halfHourSteps() {
        assertEquals(30 * m, floorHalfHour(49 * m, z)); assertEquals(60 * m, nextHalfHour(49 * m, z)); assertEquals(60 * m, nextHalfHour(30 * m, z))
        assertEquals(30 * m, prevHalfHour(49 * m, 20 * m, z)); assertEquals(30 * m, prevHalfHour(60 * m, 20 * m, z))
        assertEquals(20 * m, prevHalfHour(25 * m, 20 * m, z))   // never before the start
        assertEquals(30 * m, floorHalfHour(30 * m + 4 * 3_600_000L - 3_600_000L * 4, z))
        assertEquals(30 * m, floorHalfHour(60 * m + 5 * 3_600_000L - 3_600_000L * 5 - 30 * m, z))
        // a zone offset of +5:45 still lands on the local half hour
        val off = 345 * m; assertEquals(0L, (floorHalfHour(12 * m, off) + off) % (30 * m))
    }

    @Test fun pagingSnapsToHalfHoursOffTheStart() {
        val span = 120 * m; val earliest = 20 * m; val latest = 24 * 60 * m
        assertEquals(120 * m, guidePage(20 * m, 1, span, earliest, latest))   // 20+120=140 -> 120 (a half hour line)
        assertEquals(earliest, guidePage(120 * m, -1, span, earliest, latest))
        assertEquals(earliest, guidePage(60 * m, -1, span, earliest, latest))
    }

    @Test fun finishedProgrammesAreNotDrawnAndTheFirstIsClipped() {
        val l = listOf(pg(-60, 0, "done"), pg(0, 25, "Just over"), pg(-5 + 5, 60, "now"), pg(60, 90, "next"), pg(90, 200, "later"), pg(300, 330, "outside"))
        val s = guideSlots(l.filter { it.title != "Just over" }, 20 * m, 140 * m, 23 * m)
        assertEquals(listOf("now", "next", "later"), s.map { it.p!!.title })
        assertEquals(20 * m, s[0].start)    // clipped on the left to the window start
        assertEquals(60 * m, s[0].end)
        assertEquals(140 * m, s[2].end)     // and on the right
    }

    @Test fun aProgrammeThatEndedJustBeforeNowIsGone() {
        val s = guideSlots(listOf(pg(0, 21, "ended"), pg(21, 60, "now")), 20 * m, 140 * m, 23 * m)
        assertEquals(listOf("now"), s.map { it.p!!.title }); assertEquals(20 * m, s[0].start)   // (it ended at 21, before now = 23: not drawn, though inside the window)
    }

    @Test fun noListingForNowGivesAQuietGap() {
        val gap = guideSlots(listOf(pg(0, 18, "old"), pg(45, 90, "later")), 20 * m, 140 * m, 23 * m)
        assertNull(gap[0].p); assertEquals(20 * m, gap[0].start); assertEquals(45 * m, gap[0].end); assertEquals("later", gap[1].p!!.title)
        val none = guideSlots(emptyList(), 20 * m, 140 * m, 23 * m)
        assertEquals(1, none.size); assertNull(none[0].p); assertEquals(140 * m, none[0].end)
        // a window paged into the future has no gap cell (nothing is "now" there)
        assertEquals(0, guideSlots(emptyList(), 200 * m, 320 * m, 23 * m).size)
    }

    @Test fun rulerTicksAreTheNextHalfHours() {
        assertEquals(listOf(30L, 60, 90, 120).map { it * m }, guideTicks(20 * m, 120 * m, z))   // 20..140: 30,60,90,120
        assertEquals(listOf(60L, 90, 120, 150).map { it * m }, guideTicks(60 * m, 120 * m, z))  // on a half hour: it is the first label
    }

    @Test fun firstCellHasAMinimumWidthAndPushesTheRest() {
        // 6 dp per minute, window 20..140; "now" ends at 25 (30 dp natural), next 25..60
        val sl = guideSlots(listOf(pg(0, 25, "now"), pg(25, 60, "next"), pg(60, 400, "long")), 20 * m, 140 * m, 23 * m)
        val l = guideLayout(sl, 20 * m, 6f, 720f)
        assertEquals(140f, l[0].w, 0.01f); assertEquals(0f, l[0].x, 0.01f)
        assertEquals(140f, l[1].x, 0.01f)   // pushed right by 140 - 30
        assertEquals(720f, l[2].x + l[2].w, 0.01f)   // cut cleanly at the grid edge
    }

    @Test fun cellsTooNarrowToReadAreDroppedAndTheHoleIsAbsorbed() {
        // now ends at 45 (150 dp natural, no push); a 5 min listing (30 dp) follows, then the real next one
        val sl = guideSlots(listOf(pg(0, 45, "now"), pg(45, 50, "tiny"), pg(50, 90, "next"), pg(130, 135, "edge")), 20 * m, 140 * m, 23 * m)
        val l = guideLayout(sl, 20 * m, 6f, 720f)
        assertEquals(listOf("now", "next"), l.map { it.slot.p!!.title })   // "tiny" (30 dp) and the 5 min cell cut at the edge are gone
        assertEquals(l[1].x, l[0].x + l[0].w, 0.01f)   // no hole behind the first cell
    }
}
