package com.wmorgan97.m3uplayer.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HiddenSyncTest {
    private val S = HiddenSync.SEP
    @Test fun keys() {
        assertEquals("News", HiddenSync.groupOf("g$S*${S}News"))
        assertEquals("A${S}B", HiddenSync.groupOf("g${S}x:h|u|both${S}A${S}B"))
        assertEquals("http://a/1", HiddenSync.channelOf(HiddenSync.channelKey("http://a/1")))
        assertEquals(null, HiddenSync.groupOf("c${S}x"))
    }
    @Test fun firstSyncIsUnion() {
        val l = HiddenSync.local(setOf("Sport"), emptySet(), emptyMap())
        assertEquals(1L, l[HiddenSync.groupKey("Sport")]!!.at)
        val r = HiddenSync.adopt(setOf("Sport"), emptySet(), emptyMap(), mapOf("g${S}x:h|u|both${S}Adult" to HEntry(true, 50)))
        assertEquals(setOf("Sport", "Adult"), r.groups); assertTrue(r.changed)
    }
    @Test fun showOnOtherDevicePropagatesAndOlderHideDoesNot() {
        val k = HiddenSync.groupKey("News")
        val r = HiddenSync.adopt(setOf("News"), emptySet(), mapOf(k to 10L), mapOf(k to HEntry(false, 20)))
        assertFalse("News" in r.groups)
        val r2 = HiddenSync.adopt(r.groups, emptySet(), r.at, mapOf(k to HEntry(true, 15)))
        assertFalse(r2.changed); assertFalse("News" in r2.groups)
        assertEquals(HEntry(false, 20), HiddenSync.local(r.groups, emptySet(), r.at)[k])
    }
    @Test fun channels() {
        val k = HiddenSync.channelKey("http://a/1")
        val r = HiddenSync.adopt(emptySet(), emptySet(), emptyMap(), mapOf(k to HEntry(true, 5)))
        assertEquals(setOf("http://a/1"), r.chans)
        assertEquals(emptySet<String>(), HiddenSync.adopt(r.groups, r.chans, r.at, mapOf(k to HEntry(false, 6))).chans)
    }
}
