package com.wmorgan97.m3uplayer.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Follows (men's side unless asked), event importance (js/sports.js equivalents) and Home's quality tags. */
class SportsRulesTest {
    @Test fun womensFixturesAreRecognised() {
        assertTrue(isWomensFixture("Chelsea Women v Arsenal Women"))
        assertTrue(isWomensFixture("Chelsea (W) v Arsenal (W)"))
        assertTrue(isWomensFixture("Chelsea W v Arsenal W"))
        assertTrue(isWomensFixture("Barclays WSL: Chelsea v Arsenal"))
        assertTrue(isWomensFixture("Chelsea v Arsenal", "Women's Super League action from Kingsmeadow"))
        assertFalse(isWomensFixture("Premier League: Chelsea v Arsenal"))
        assertFalse(isWomensFixture("Chelsea v Wolves"))
    }

    @Test fun followNameDecidesTheSide() {
        assertFalse(wantsWomens("Chelsea"))
        assertTrue(wantsWomens("Chelsea Women"))
        assertTrue(wantsWomens("Arsenal W"))
        assertTrue(wantsWomens("WSL"))
    }

    @Test fun biggerEventsRankHigher() {
        assertEquals(104, importance("Premier League: Liverpool v Arsenal"))
        assertEquals(29, importance("MLS: Inter Miami v LA Galaxy"))
        assertEquals(92, importance("Mexico City Grand Prix"))
        assertEquals(92, importance("Race", "Formula 1"))
        assertEquals(4, importance("Kabaddi: Team A v Team B"))
        assertTrue(importance("Premier League: Liverpool v Arsenal") > importance("MLS Cup: A v B"))
    }

    @Test fun qualityTagsSayOnlyWhatTheNameSays() {
        assertEquals(listOf("4K", "Dolby Vision", "Atmos"), qualityTags("Dune Part Two (2024) 4K DV Atmos", "Movies UHD"))
        assertEquals(listOf("1080p", "HDR"), qualityTags("Heat 1080p HDR", ""))
        assertEquals(emptyList<String>(), qualityTags("Heat (1995)", "Movies"))
    }
}
