package com.wmorgan97.m3uplayer.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpotlightTest {
    @Test fun resumeRule() {
        assertNull(resumePoint(0.0, false))          // never started: from the beginning
        assertNull(resumePoint(4.0, false))          // a peek (5 s or less): from the beginning
        assertEquals(6.0, resumePoint(6.0, false)!!, 0.0)       // anything more carries on
        assertEquals(42.0, resumePoint(42.0, false)!!, 0.0)     // (was 30 s before the rule)
        assertNull(resumePoint(5000.0, true))        // finished: starts over
    }

    @Test fun qualityFromBestCopy() {
        assertEquals(listOf("4K", "HDR", "Atmos"), qualityTags("EN - Dune Part Two 4K HDR Atmos", ""))
        assertEquals(listOf("4K", "Dolby Vision"), qualityTags("Dune (2021) UHD Dolby Vision", ""))
        assertEquals(listOf("1080p"), qualityTags("Heat 1080p", "Movies"))
        assertEquals(emptyList<String>(), qualityTags("Heat", "Movies"))
        assertEquals(true, premiumQuality("4K")); assertEquals(false, premiumQuality("1080p"))
    }
}
