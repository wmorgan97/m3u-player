package com.wmorgan97.m3uplayer.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class M3UParserTest {
    private fun parse(s: String, offset: Int = 0) = parseM3U(s.trimIndent(), offset)

    @Test fun standardPlaylist() {
        val (chs, epg) = parse("""
            #EXTM3U url-tvg="http://a.test/epg.xml.gz"
            #EXTINF:-1 tvg-id="bbc1.uk" tvg-name="BBC One" tvg-logo="http://a.test/bbc1.png" group-title="UK | General",BBC One HD
            http://a.test/live/u/p/1.ts
            #EXTINF:-1 tvg-id="itv.uk" group-title="UK | General",ITV 1
            http://a.test/live/u/p/2.ts
        """)
        assertEquals(listOf("http://a.test/epg.xml.gz"), epg)
        assertEquals(2, chs.size)
        val c = chs[0]
        assertEquals("BBC One HD", c.name); assertEquals("http://a.test/live/u/p/1.ts", c.url); assertEquals("UK | General", c.group)
        assertEquals("http://a.test/bbc1.png", c.logo); assertEquals("bbc1.uk", c.tvgId); assertEquals("BBC One", c.tvgName)
        assertEquals(1, c.num); assertEquals(2, chs[1].num); assertEquals(0, c.catchupDays); assertTrue(c.headers.isEmpty())
    }

    @Test fun multipleEpgUrlsInHeader() {
        val (_, epg) = parse("""#EXTM3U x-tvg-url="http://a.test/1.xml,http://a.test/2.xml" url-tvg='http://a.test/3.xml'""")
        assertEquals(listOf("http://a.test/1.xml", "http://a.test/2.xml", "http://a.test/3.xml"), epg)
    }

    @Test fun emptyEpgAttributeDoesNotCrash() {
        val (chs, epg) = parse("""
            #EXTM3U url-tvg=""
            #EXTINF:-1,One
            http://a.test/1
        """)
        assertTrue(epg.isEmpty()); assertEquals(1, chs.size)
    }

    @Test fun byteOrderMarkHeader() {
        val (chs, epg) = parseM3U("﻿#EXTM3U url-tvg=\"http://a.test/e.xml\"\n#EXTINF:-1,One\nhttp://a.test/1\n", 0)
        assertEquals(listOf("http://a.test/e.xml"), epg)
        assertEquals(listOf("One"), chs.map { it.name })
    }

    @Test fun extgrpUsedWhenNoGroupTitle() {
        val (chs, _) = parse("""
            #EXTINF:-1,News 24
            #EXTGRP:News
            http://a.test/1
            #EXTINF:-1 group-title="Sport",Sport 1
            #EXTGRP:Ignored
            http://a.test/2
            #EXTINF:-1,Loose
            http://a.test/3
        """)
        assertEquals(listOf("News", "Sport", ""), chs.map { it.group })
    }

    @Test fun vlcOptHeadersPerChannel() {
        val (chs, _) = parse("""
            #EXTINF:-1,One
            #EXTVLCOPT:http-user-agent=Agent/1.0 (X11)
            #EXTVLCOPT:http-referrer=http://ref.test/
            #EXTVLCOPT:network-caching=1000
            http://a.test/1
            #EXTINF:-1,Two
            http://a.test/2
        """)
        assertEquals(mapOf("User-Agent" to "Agent/1.0 (X11)", "Referer" to "http://ref.test/"), chs[0].headers)
        assertTrue(chs[1].headers.isEmpty())
    }

    @Test fun quotedCommasAndTitleCommas() {
        val (chs, _) = parse("""
            #EXTINF:-1 tvg-name="Sky, Sports" group-title='A, B',Sky Sports, Main Event
            http://a.test/1
        """)
        assertEquals("Sky Sports, Main Event", chs[0].name); assertEquals("Sky, Sports", chs[0].tvgName); assertEquals("A, B", chs[0].group)
    }

    @Test fun unicodeNames() {
        val (chs, _) = parse("""
            #EXTINF:-1 group-title="Ελληνικά",ΕΡΤ1 ᴴᴰ
            http://a.test/1
            #EXTINF:-1 group-title="Arabic",قناة الجزيرة
            http://a.test/2
        """)
        assertEquals(listOf("ΕΡΤ1 ᴴᴰ", "قناة الجزيرة"), chs.map { it.name }); assertEquals("Ελληνικά", chs[0].group)
    }

    @Test fun blankLinesAndCrlf() {
        val (chs, _) = parseM3U("#EXTM3U\r\n\r\n#EXTINF:-1,One\r\n\r\n   \r\nhttp://a.test/1\r\n\r\n", 0)
        assertEquals(1, chs.size); assertEquals("http://a.test/1", chs[0].url)
    }

    @Test fun malformedLines() {
        val (chs, _) = parse("""
            #EXTINF:-1 tvg-id="x" group-title="G"
            http://a.test/no-title
            #EXTINF garbage
            #RANDOM comment
            #EXTINF:-1,Orphan with no url
            #EXTINF:-1 tvg-name="Fallback",
            http://a.test/2
            #EXTINF:-1 tvg-name="unterminated,Name
            http://a.test/3
        """)
        // No title -> tvg-name, else the URL itself; a dangling EXTINF is replaced by the next one.
        assertEquals("http://a.test/no-title", chs[0].name)
        assertEquals("Fallback", chs[1].name)
        assertEquals(3, chs.size)
    }

    @Test fun separatorsPromosAndVodDropped() {
        val (chs, _) = parse("""
            #EXTINF:-1 group-title="UK",----- UK SPORTS -----
            http://a.test/1
            #EXTINF:-1 group-title="UK",★★★
            http://a.test/2
            #EXTINF:-1 group-title="UK",⬇️ Release update ⬇️
            http://a.test/3
            #EXTINF:-1 group-title="UK",EPGenius
            http://a.test/4
            #EXTINF:-1 group-title="UK",Some Film
            http://a.test/movie/u/p/5.mkv
            #EXTINF:-1 group-title="UK",Some Show
            http://a.test/series/u/p/6.mp4
            #EXTINF:-1 group-title="UK",Clip
            http://a.test/clip.MP4?token=1
            #EXTINF:-1 group-title="UK",Sky Sports 1 -- HD
            http://a.test/7
            #EXTINF:-1 group-title="UK",M*A*S*H TV
            http://a.test/8
        """)
        assertEquals(listOf("Sky Sports 1 -- HD", "M*A*S*H TV"), chs.map { it.name })
        assertEquals(listOf(1, 2), chs.map { it.num })
    }

    @Test fun tvgChnoAndOffsetNumbering() {
        val (chs, _) = parse("""
            #EXTINF:-1 tvg-chno="101",One
            http://a.test/1
            #EXTINF:-1 tvg-chno="abc",Two
            http://a.test/2
            #EXTINF:-1,Three
            http://a.test/3
        """, offset = 50)
        assertEquals(listOf(101, 52, 53), chs.map { it.num })
    }

    @Test fun catchupAttributes() {
        val (chs, _) = parse("""
            #EXTINF:-1 catchup="default" catchup-days="3" catchup-source="http://a.test/c?s={utc}",A
            http://a.test/1
            #EXTINF:-1 catchup-type="XC",B
            http://a.test/live/u/p/2.ts
            #EXTINF:-1 catchup="shift" catchup-days="0",C
            http://a.test/3
            #EXTINF:-1,D
            http://a.test/4
        """)
        assertEquals(listOf(3, 7, 0, 0), chs.map { it.catchupDays })
        assertEquals(listOf("default", "xc", "shift", ""), chs.map { it.catchupMode })
        assertEquals("http://a.test/c?s={utc}", chs[0].catchupSource)
    }

    @Test fun twentyThousandChannelsQuickly() {
        val sb = StringBuilder("#EXTM3U url-tvg=\"http://a.test/e.xml\"\n")
        for (i in 1..20_000) sb.append("#EXTINF:-1 tvg-id=\"c$i.uk\" tvg-name=\"Channel $i\" tvg-logo=\"http://a.test/l/$i.png\" group-title=\"Group ${i % 50}\" catchup=\"xc\" catchup-days=\"7\",Channel $i HD\n")
            .append("#EXTVLCOPT:http-user-agent=Agent\nhttp://a.test/live/u/p/$i.ts\n")
        val text = sb.toString()
        parseM3U(text, 0)   // warm up the JIT
        val t0 = System.nanoTime()
        val (chs, _) = parseM3U(text, 0)
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertEquals(20_000, chs.size); assertEquals("Channel 20000 HD", chs.last().name)
        assertTrue("took ${ms}ms", ms < 2000)
    }
}
