package com.wmorgan97.m3uplayer.tv

import org.junit.Assert.assertEquals
import org.junit.Test

/** Where focused items settle: the keyline, row anchor and edge strategies (Motion.kt). Sizes in px. */
class ScrollSpecTest {
    @Test fun keylinePinsTheFocusedCardAtTheMargin() {
        val k = TvScroll.keyline(48f)
        assertEquals(0f, k.calculateScrollDistance(48f, 128f, 960f), 0f)       // the first card already sits on it
        assertEquals(142f, k.calculateScrollDistance(190f, 128f, 960f), 0f)    // the next card: the row slides one card
        assertEquals(-142f, k.calculateScrollDistance(-94f, 128f, 960f), 0f)   // and back
    }

    @Test fun anchorPutsEveryRowAtTheSameHeight() {
        val a = TvScroll.anchor(90f)
        assertEquals(310f, a.calculateScrollDistance(400f, 192f, 540f), 0f)
        assertEquals(-62f, a.calculateScrollDistance(28f, 300f, 540f), 0f)     // above the anchor: scrolls back (clamped at the top by the list)
    }

    @Test fun edgeScrollsOnlyWhenNeededAndKeepsAPeek() {
        val e = TvScroll.edge
        assertEquals(0f, e.calculateScrollDistance(200f, 40f, 500f), 0f)       // comfortably visible: no move
        val peek = minOf(40f * .5f, 500f * .08f)
        assertEquals(480f + 40f - (500f - peek), e.calculateScrollDistance(480f, 40f, 500f), 0.01f)   // at the bottom edge: just enough, plus a peek
        assertEquals(-10f - peek, e.calculateScrollDistance(-10f, 40f, 500f), 0.01f)
        assertEquals(30f, e.calculateScrollDistance(30f, 600f, 500f), 0f)      // bigger than the screen: its top goes to the top
    }

    @Test fun focusScalesStayModest() {
        listOf(Tv.scaleCard, Tv.scaleWide, Tv.scaleLarge, Tv.scaleButton, Tv.scaleDisc).forEach { assert(it in 1f..1.08f) }
    }
}
