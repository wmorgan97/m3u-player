package com.wmorgan97.m3uplayer.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.util.TimeZone

class GuideTimeTest {
    private val london = TimeZone.getTimeZone("Europe/London")
    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()
    private fun floor(iso: String, tz: TimeZone = london) = Instant.ofEpochMilli(floorToHalfHour(ms(iso), tz)).toString()

    @Test fun floorsToHalfHour() {
        assertEquals("2026-07-01T12:00:00Z", floor("2026-07-01T12:29:59.999Z"))
        assertEquals("2026-07-01T12:30:00Z", floor("2026-07-01T12:30:00Z"))
        assertEquals("2026-07-01T12:30:00Z", floor("2026-07-01T12:59:01Z"))
    }

    @Test fun clocksGoBackInLondon() {
        // 25 Oct 2026: 01:00 BST (00:00 UTC) .. 01:59 BST, then 01:00 GMT (01:00 UTC) .. 01:59 GMT again.
        assertEquals("2026-10-25T00:30:00Z", floor("2026-10-25T00:45:00Z"))   // first 01:45 (BST)
        assertEquals("2026-10-25T01:30:00Z", floor("2026-10-25T01:45:00Z"))   // second 01:45 (GMT): must not jump back an hour
        assertEquals("2026-10-25T01:00:00Z", floor("2026-10-25T01:10:00Z"))
    }

    @Test fun clocksGoForwardInLondon() {
        // 29 Mar 2026: 01:00 GMT becomes 02:00 BST at 01:00 UTC.
        assertEquals("2026-03-29T00:30:00Z", floor("2026-03-29T00:59:00Z"))
        assertEquals("2026-03-29T01:00:00Z", floor("2026-03-29T01:10:00Z"))   // 02:10 BST -> 02:00 BST
    }

    @Test fun halfHourOffsetZones() {
        // India is UTC+5:30, Nepal +5:45: the half hour is on the local clock.
        assertEquals("2026-07-01T12:00:00Z", floor("2026-07-01T12:10:00Z", TimeZone.getTimeZone("Asia/Kolkata")))   // 17:40 -> 17:30
        assertEquals("2026-07-01T11:45:00Z", floor("2026-07-01T12:10:00Z", TimeZone.getTimeZone("Asia/Kathmandu"))) // 17:55 -> 17:30
    }

    private fun p(a: Long, b: Long, t: String) = Programme(a, b, t, "")

    @Test fun programmeAtBasic() {
        val l = listOf(p(0, 100, "A"), p(100, 200, "B"))
        assertEquals("A", programmeAt(l, 0)?.title)
        assertEquals("B", programmeAt(l, 100)?.title)   // end is exclusive
        assertNull(programmeAt(l, 200)); assertNull(programmeAt(l, -1)); assertNull(programmeAt(emptyList(), 5))
    }

    @Test fun programmeAtGaps() {
        val l = listOf(p(0, 100, "A"), p(150, 200, "B"))
        assertNull(programmeAt(l, 120))
        assertEquals("B", programmeAt(l, 150)?.title)
    }

    @Test fun programmeAtOverlaps() {
        // A long programme with a short one inside it, and two that overlap at the edge.
        val l = listOf(p(0, 300, "Long"), p(100, 150, "Inner"), p(290, 400, "Next"))
        assertEquals("Inner", programmeAt(l, 120)?.title)
        assertEquals("Long", programmeAt(l, 200)?.title)    // after Inner ended, Long is still on
        assertEquals("Next", programmeAt(l, 295)?.title)    // latest start wins
    }

    @Test fun programmeAtUnsorted() {
        val l = listOf(p(200, 300, "C"), p(0, 100, "A"), p(100, 200, "B"))
        assertEquals("B", programmeAt(l, 150)?.title)
    }
}
