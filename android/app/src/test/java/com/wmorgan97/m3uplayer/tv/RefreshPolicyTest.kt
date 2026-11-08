package com.wmorgan97.m3uplayer.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RefreshPolicyTest {
    private val now = 1_800_000_000_000L
    private val h = 3_600_000L
    private fun due(i: Int, ageH: Double, start: Boolean = false, onStart: Boolean = true) =
        RefreshPolicy.due(i, onStart, now - (ageH * h).toLong(), now, start)

    @Test fun everyNHoursIsDueOnlyOncePastTheInterval() {
        assertFalse(due(12, 11.9)); assertTrue(due(12, 12.1))
        assertFalse(due(6, 5.0)); assertTrue(due(6, 6.5))
        assertFalse(due(48, 47.0)); assertTrue(due(48, 49.0))
    }

    @Test fun nothingSavedIsAlwaysDue() {
        for (i in listOf(6, 12, RefreshPolicy.APP_START, RefreshPolicy.MANUAL)) assertTrue(RefreshPolicy.due(i, false, 0L, now, false))
    }

    @Test fun manualOnlyNeverUpdatesByItself() {
        assertFalse(due(RefreshPolicy.MANUAL, 24 * 30.0)); assertFalse(due(RefreshPolicy.MANUAL, 24 * 30.0, start = true))
    }

    @Test fun appStartOnlyUpdatesOnlyAtStartAndNotTwiceInAnHour() {
        assertFalse(due(RefreshPolicy.APP_START, 100.0))                  // while the app runs: no
        assertTrue(due(RefreshPolicy.APP_START, 3.0, start = true))       // at start: yes
        assertFalse(due(RefreshPolicy.APP_START, 0.3, start = true))      // reopened within the hour: no
        assertTrue(due(RefreshPolicy.APP_START, 3.0, start = true, onStart = false))   // (the interval itself says start)
    }

    @Test fun updateOnAppStartSwitchAddsAStartCheckToAnInterval() {
        assertTrue(due(24, 3.0, start = true, onStart = true))
        assertFalse(due(24, 3.0, start = true, onStart = false))
        assertFalse(due(24, 0.5, start = true, onStart = true))
        assertFalse(due(24, 3.0, start = false, onStart = true))          // only the first check after opening
    }

    @Test fun aClockSetBackDoesNotFreezeUpdates() { assertTrue(RefreshPolicy.due(12, true, now + 30 * h, now, false)) }

    @Test fun labels() {
        assertEquals("App start only", RefreshPolicy.label(RefreshPolicy.APP_START)); assertEquals("Manual only", RefreshPolicy.label(RefreshPolicy.MANUAL))
        assertEquals("12 hours", RefreshPolicy.label(12)); assertEquals("12 h", RefreshPolicy.short(12))
        assertEquals(12, RefreshPolicy.DEFAULT_PLAYLIST_H); assertEquals(6, RefreshPolicy.DEFAULT_GUIDE_H)
    }
}
