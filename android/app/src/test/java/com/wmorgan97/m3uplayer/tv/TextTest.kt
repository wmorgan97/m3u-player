package com.wmorgan97.m3uplayer.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TextTest {
    @Test fun displayTitle() {
        assertEquals("Inception", displayTitle("4K-EN - Inception (2010) (US)"))
        assertEquals("The Dark Knight", displayTitle("EN - The Dark Knight (2008)"))
        assertEquals("S.W.A.T. Exiles", displayTitle("EN - S.W.A.T. Exiles (2026) (US) - S01E01 - Clean Entry"))
        assertEquals("Dune Part Two", displayTitle("UK| Dune Part Two [MULTI-SUB] 4K"))
        assertEquals("Angry Men", displayTitle("12. Angry Men"))
        assertEquals("1917", displayTitle("1917"))                 // a year-only title is kept
        assertEquals("", displayTitle(null))
    }

    @Test fun vodMetaYearAndEpisode() {
        val m = vodMeta("EN - The Office (US) (2005) S02E03 - The Fire")
        assertEquals(2005, m.year); assertEquals(2, m.season); assertEquals(3, m.episode)
    }

    @Test fun prettyGroup() {
        assertEquals("UK · Sky Sports", prettyGroup("UK| Sky Sports ᴳᴬᴺᴶᴬ"))
        assertEquals("UK · Entertainment", prettyGroup("UK / Entertainment"))
        assertEquals("Sports · Football", prettyGroup("Sports ► Football"))
        assertEquals("News", prettyGroup("News |"))
        assertEquals("", prettyGroup(null))
    }

    @Test fun normNameMatchesGuideAndPlaylistSpellings() {
        assertEquals("bbc1", normName("UK: BBC One HD"))
        assertEquals("bbc1", normName("BBC ONE FHD"))
        assertEquals("bbc1", normName("bbc1"))
        assertEquals("skysportsmainevent", normName("UK| Sky Sports Main Event ᴴᴰ (Backup)"))
        assertEquals("channel4+1", normName("Channel 4 +1 [50fps]"))
        assertEquals("ερτ1", normName("ΕΡΤ1 HD"))
        assertEquals("", normName(null))
    }

    @Test fun quality() {
        assertEquals("4K", quality("Sky Sports UHD", ""))
        assertEquals("4K", quality("Film 2160p", ""))
        assertEquals("4K", quality("Nature", "4K Channels"))
        assertEquals("4K", quality("BBC One ᵁᴴᴰ", ""))
        assertEquals("HD", quality("BBC One HD", ""))
        assertEquals("HD", quality("BBC One ᴴᴰ", ""))
        assertEquals("HD", quality("Movie 1080p", ""))
        assertNull(quality("BBC One", "UK"))
        assertNull(quality("HDR Movies", ""))          // HDR is not a resolution
    }
}
