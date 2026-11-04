package com.wmorgan97.m3uplayer.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HandoffTest {
    @Test fun actsOnceOnFreshUntakenRequests() {
        assertTrue(Handoff.isDue("a", "", sent = 1_000, now = 30_000, lastId = null))
        assertFalse("already handled", Handoff.isDue("a", "", 1_000, 30_000, lastId = "a"))
        assertFalse("another TV took it", Handoff.isDue("a", "Bedroom", 1_000, 30_000, null))
        assertFalse("over a minute old", Handoff.isDue("a", "", 1_000, 1_000 + Handoff.FRESH_MS, null))
        assertTrue("a little clock skew is fine", Handoff.isDue("a", "", 4_000, 1_000, null))
        assertFalse("no id", Handoff.isDue("", "", 1_000, 2_000, null))
    }

    @Test fun startsWhereThePhoneWasByTheResumeRule() {
        assertEquals(1234.0, Handoff.startFor(1234.0, 6000.0), 0.0)
        assertEquals("barely started: from the beginning", 0.0, Handoff.startFor(3.0, 6000.0), 0.0)
        assertEquals("finished: from the beginning", 0.0, Handoff.startFor(5950.0, 6000.0), 0.0)
        assertEquals("length unknown: the phone's point", 700.0, Handoff.startFor(700.0, 0.0), 0.0)
    }
}
