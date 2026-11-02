package com.wmorgan97.m3uplayer.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class LiveBaseTest {
    @Test fun plusChannelsStayDistinct() {
        assertNotEquals(liveBase("Sky Sports +"), liveBase("Sky Sports"))
        assertNotEquals(liveBase("ITV1 +1"), liveBase("ITV1"))
    }
    @Test fun qualityCopiesStillMatch() {
        assertEquals(liveBase("Sky Sports + HD"), liveBase("Sky Sports + UHD"))
    }
}
