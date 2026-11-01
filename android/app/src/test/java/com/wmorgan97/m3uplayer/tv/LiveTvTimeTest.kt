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
}
