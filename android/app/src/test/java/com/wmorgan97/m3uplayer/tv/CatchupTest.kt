package com.wmorgan97.m3uplayer.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class CatchupTest {
    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()
    private val start = ms("2026-10-25T19:30:00Z")          // 1793043000
    private val stop = ms("2026-10-25T20:15:30Z")           // 45.5 minutes later
    private val now = ms("2026-10-26T08:00:00Z")
    private val s = start / 1000; private val e = stop / 1000; private val n = now / 1000

    private fun ch(url: String, mode: String = "", source: String = "", days: Int = 7) =
        Channel("C", url, "", "", "", "", 1, catchupDays = days, catchupMode = mode, catchupSource = source)

    @Test fun xtreamTimeshift() {
        val c = ch("http://host.test:8080/live/user/pass/1234.ts", mode = "xc")
        assertEquals("http://host.test:8080/timeshift/user/pass/46/2026-10-25:19-30/1234.ts", catchupUrl(c, start, stop, now))
    }

    @Test fun xtreamInferredFromLivePathAndUtcEvenInBst() {
        // 2026-07-01 12:05 UTC is 13:05 in London: the link uses UTC.
        val c = ch("http://host.test/live/u/p/9.m3u8")
        val st = ms("2026-07-01T12:05:00Z")
        assertEquals("http://host.test/timeshift/u/p/60/2026-07-01:12-05/9.ts", catchupUrl(c, st, st + 3600_000L, st + 7200_000L))
    }

    @Test fun xtreamWithoutLivePrefix() {
        val c = ch("http://host.test/u/p/77", mode = "xtream")
        assertEquals("http://host.test/timeshift/u/p/1/2026-10-25:19-30/77.ts", catchupUrl(c, start, start + 1000, now))
    }

    @Test fun defaultShiftAppendsUtcAndLutc() {
        assertEquals("http://a.test/s.m3u8?utc=$s&lutc=$n", catchupUrl(ch("http://a.test/s.m3u8", mode = "shift"), start, stop, now))
        assertEquals("http://a.test/s.m3u8?x=1&utc=$s&lutc=$n", catchupUrl(ch("http://a.test/s.m3u8?x=1", mode = "default"), start, stop, now))
    }

    @Test fun allPlaceholders() {
        val src = "http://cu.test/p?utc={utc}&lutc={lutc}&start={start}&end={end}&dur={duration}&off={offset}&ts={timestamp}&now={now}&ue={utcend}&t={Y}{m}{d}{H}{M}{S}"
        val c = ch("http://a.test/live.m3u8", mode = "default", source = src)
        assertEquals("http://cu.test/p?utc=$s&lutc=$n&start=$s&end=$e&dur=${e - s}&off=${n - s}&ts=$s&now=$n&ue=$e&t=20261025193000", catchupUrl(c, start, stop, now))
    }

    @Test fun dollarAndParenForms() {
        val c = ch("http://a.test/live.m3u8", mode = "append", source = "?from=\${start}&to=\${end}&b={(Y)}")
        assertEquals("http://a.test/live.m3u8?from=$s&to=$e&b=2026", catchupUrl(c, start, stop, now))
    }

    @Test fun formattedTimePlaceholders() {
        val c = ch("http://a.test/live.m3u8", mode = "default", source = "http://cu.test/{utc:Y-m-d}/{utc:H-M-S}/{utcend:YmdHMS}.ts?d={duration:60}&o={offset:60}")
        assertEquals("http://cu.test/2026-10-25/19-30-00/20261025201530.ts?d=45&o=${(n - s) / 60}", catchupUrl(c, start, stop, now))
    }

    @Test fun unknownPlaceholdersLeftAlone() {
        val c = ch("http://a.test/x", mode = "default", source = "?a={unknown}&b={utc}")
        assertEquals("http://a.test/x?a={unknown}&b=$s", catchupUrl(c, start, stop, now))
    }

    @Test fun nullForFutureOrTooOldOrNoArchive() {
        val c = ch("http://host.test/live/u/p/1.ts", mode = "xc", days = 2)
        assertNull(catchupUrl(c, now + 60_000, now + 3600_000, now))                    // not started
        assertNull(catchupUrl(c, now - 3 * 86_400_000L, now - 3 * 86_400_000L + 3600_000, now))   // beyond 2 days
        assertEquals("http://host.test/timeshift/u/p/60/2026-10-24:08-00/1.ts", catchupUrl(c, now - 2 * 86_400_000L, now - 2 * 86_400_000L + 3600_000, now))   // exactly at the limit
        assertNull(catchupUrl(ch("http://host.test/live/u/p/1.ts", days = 0), start, stop, now))
    }
}
