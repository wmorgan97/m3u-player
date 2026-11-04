package com.wmorgan97.m3uplayer.tv

import org.junit.Assert.assertEquals
import org.junit.Test

class FavouritesTest {
    private fun ch(id: Int, name: String, tvg: String = "ch$id", host: String = "http://p.example:80") = Channel(name, "$host/live/u/p/$id.ts", "G", "", tvg, "", id)
    private val chans = listOf(ch(1, "BBC One"), ch(2, "BBC Two"), ch(3, "ITV1"), ch(4, "Channel 4", tvg = ""), ch(5, "Sky News"))

    @Test fun exactLink() = assertEquals(listOf(chans[0]), matchFavourites(listOf(FavRef(chans[0].url, "BBC One", "ch1")), chans))

    @Test fun otherLinkForms() {
        val favs = listOf(FavRef("http://p.example:80/live/u/p/2.m3u8", "x", ""), FavRef("http://p.example:80/u/p/3", "y", ""))
        assertEquals(listOf(chans[1], chans[2]), matchFavourites(favs, chans))
    }

    @Test fun movedHostFallsBackToTvgIdAndName() {
        assertEquals(listOf(chans[4]), matchFavourites(listOf(FavRef("http://new.example/u2/p2/999.ts", "Sky News", "CH5")), chans))
        assertEquals(listOf(chans[3]), matchFavourites(listOf(FavRef("http://gone.example/a.ts", "channel 4", "")), chans))
    }

    @Test fun noMatchNoDuplicatesKeepsOrder() {
        val favs = listOf(FavRef("http://gone.example/z.ts", "Nothing", "zz"), FavRef(chans[2].url, "ITV1", "ch3"), FavRef("http://p.example:80/live/u/p/3.m3u8", "ITV1", "ch3"), FavRef(chans[0].url, "BBC One", "ch1"))
        assertEquals(listOf(chans[2], chans[0]), matchFavourites(favs, chans))
    }

    @Test fun streamKeyForms() {
        assertEquals(streamKey("http://a/live/u/p/7.ts"), streamKey("http://a/u/p/7.m3u8"))
        assertEquals(streamKey("http://a/x/stream.m3u8"), streamKey("http://a/x/stream"))
    }
}
