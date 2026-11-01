package com.wmorgan97.m3uplayer.tv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The mode choice, against the Shield's real mode list (adb shell dumpsys display, 2026-11-01). */
class FrameRateMatcherTest {
    private val shield = listOf(
        1 to (3840 to 2160) to 23.976025f, 2 to (1920 to 1080) to 29.97003f, 3 to (1920 to 1080) to 23.976025f, 4 to (1920 to 1080) to 59.94006f,
        5 to (3840 to 2160) to 29.97003f, 6 to (1280 to 720) to 59.94006f, 7 to (3840 to 2160) to 24.000002f, 8 to (3840 to 2160) to 25.0f,
        9 to (1920 to 1080) to 30.000002f, 10 to (1920 to 1080) to 25.0f, 11 to (720 to 480) to 60.000004f, 12 to (1280 to 720) to 50.0f,
        13 to (1920 to 1080) to 24.000002f, 14 to (1920 to 1080) to 50.0f, 15 to (3840 to 2160) to 30.000002f, 16 to (3840 to 2160) to 50.0f,
        17 to (3840 to 2160) to 60.000004f, 18 to (1280 to 720) to 60.000004f, 19 to (1920 to 1080) to 60.000004f, 20 to (3840 to 2160) to 59.94006f,
    ).map { (a, r) -> Triple(a.first, a.second, r) }

    private fun at4k(fps: Float) = FrameRateMatcher.choose(fps, 3840, 2160, shield)

    @Test fun pal25GoesTo50() = assertEquals(16, at4k(25f))
    @Test fun pal50GoesTo50() = assertEquals(16, at4k(50f))
    @Test fun film23976() = assertEquals(1, at4k(23.976f))
    @Test fun film24() = assertEquals(7, at4k(24f))
    @Test fun ntsc2997GoesTo5994() = assertEquals(20, at4k(29.97f))
    @Test fun ntsc30GoesTo60() = assertEquals(17, at4k(30f))
    @Test fun exact60NotFractional() = assertEquals(17, at4k(60f))
    @Test fun exact5994() = assertEquals(20, at4k(59.94f))
    @Test fun sameResolutionOnly() = assertEquals(14, FrameRateMatcher.choose(25f, 1920, 1080, shield))
    @Test fun nonsenseRates() { assertNull(at4k(0f)); assertNull(at4k(Float.NaN)); assertNull(at4k(240f)) }
}
