package com.wmorgan97.m3uplayer.tv

import org.junit.Assert.assertEquals
import org.junit.Test

class PlainTitleTest {
    @Test fun superscriptTagsBecomeWords() {
        assertEquals("NBA Basketball - Pistons at Knicks Live", plainTitle("NBA Basketball - Pistons at Knicks ᴸᶦᵛᵉ"))
        assertEquals("Entertainment Tonight New", plainTitle("Entertainment Tonight ᴺᵉʷ"))
        assertEquals("Plain title", plainTitle("Plain title"))
    }
    @Test fun tidyCleansTitlesOnce() {
        val p = tidyProgrammes(listOf(Programme(0, 10, "Match ᴸᶦᵛᵉ", "")))
        assertEquals("Match Live", p.single().title)
    }
}
