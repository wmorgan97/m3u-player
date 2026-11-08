package com.wmorgan97.m3uplayer.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SyncStreamTest {
    private fun feedAll(vararg lines: String): List<SseEvent> { val p = SseParser(); return lines.mapNotNull { p.feed(it) } }

    @Test fun parsesChangedAndSkipsCommentsAndRetry() {
        val ev = feedAll("retry: 3000", "", ": hb", "", "event: changed", "data: {\"v\":5,\"p\":\"main\",\"from\":\"abc\"}", "", ": hb", "")
        assertEquals(1, ev.size)
        assertEquals("changed", ev[0].name)
        assertEquals("abc", ev[0].from)
    }
    @Test fun noFromAndMultipleEvents() {
        val ev = feedAll("event: changed", "data: {\"v\":1,\"p\":\"*\"}", "", "event: changed", "data: {\"v\":2,\"p\":\"mz\",\"from\":\"x\"}", "")
        assertEquals(2, ev.size)
        assertNull(ev[0].from)
        assertEquals("x", ev[1].from)
    }
    @Test fun dataWithoutEventIsMessage() {
        assertEquals("message", feedAll("data: hi", "")[0].name)
    }
    @Test fun backoffGrowsAndCaps() {
        assertEquals(3_000L, SyncStream.backoffMs(0))
        assertEquals(6_000L, SyncStream.backoffMs(1))
        assertEquals(12_000L, SyncStream.backoffMs(2))
        assertEquals(60_000L, SyncStream.backoffMs(5))
        assertEquals(60_000L, SyncStream.backoffMs(50))
        assertEquals(3_000L, SyncStream.backoffMs(-1))
    }
}
