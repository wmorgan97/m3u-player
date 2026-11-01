package com.wmorgan97.m3uplayer.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real fixtures on the TV: reading the server's replies, follows (js/fixtures.js followMatches), the channel order and
 *  the club-channel fallback (js/fixtures.js teamChannelFor). */
class FixtureRulesTest {
    private fun t(name: String, short: String = name, score: String? = null) = FxTeam(name, short, "https://a.espncdn.com/i/teamlogos/soccer/500/1.png", score)
    private fun fx(home: FxTeam?, away: FxTeam?, comp: String = "Premier League", sport: String = "football", title: String = "", session: String = "", status: String = "scheduled", start: Long = 1_000_000L) =
        Fixture("x:1", sport, comp, 100, start, start + 7_200_000L, status, null, home, away, title, session, "", "")

    private val MUN = t("Manchester United", "Man Utd"); private val TOT = t("Tottenham Hotspur", "Spurs")

    @Test fun foldsNamesLikeTheWebApp() {
        assertEquals("brighton and hove albion", FixtureRules.foldName("Brighton & Hove Albion"))
        assertEquals("nottm forest", FixtureRules.foldName("Nott'm Forest"))
        assertEquals("man united", FixtureRules.foldName("Man Utd"))
        assertEquals("paris st germain", FixtureRules.foldName("Paris Saint-Germain"))
        assertEquals("atletico madrid", FixtureRules.foldName("Atlético Madrid"))
    }

    @Test fun followsMatchTheMensTeamByAnyName() {
        val f = fx(MUN, TOT)
        for (q in listOf("Man Utd", "Manchester United", "man united", "Spurs", "Tottenham")) assertTrue(q, FixtureRules.followMatches(q, f))
        // Ambiguous, another club, or a women's follow (no women's feed: the guide search covers those).
        for (q in listOf("Manchester", "Chelsea", "Man Utd Women", "Spurs W", "Chelsea Ladies")) assertFalse(q, FixtureRules.followMatches(q, f))
        assertEquals("Manchester United", FixtureRules.followedSide("Man Utd", f)?.name)
        assertEquals("Tottenham Hotspur", FixtureRules.followedSide("Spurs", f)?.name)
    }

    @Test fun competitionFollowsMatchF1AndUfc() {
        val race = fx(null, null, comp = "F1", sport = "f1", title = "Mexico City Grand Prix", session = "Race")
        assertTrue(FixtureRules.followMatches("Formula 1", race))
        assertTrue(FixtureRules.followMatches("F1", race))
        assertFalse(FixtureRules.followMatches("Arsenal", race))
        assertEquals("Mexico City Grand Prix · Race", race.label)
    }

    @Test fun teamFixturesSkipFinishedAndPostponed() {
        val now = 10_000_000L
        val list = listOf(
            fx(MUN, TOT, status = "final", start = now - 9_000_000L),
            fx(TOT, MUN, status = "postponed", start = now + 1000).copy(id = "p"),
            fx(t("Chelsea"), MUN, start = now + 86_400_000L).copy(id = "next"),
            fx(MUN, t("Arsenal"), status = "live", start = now - 600_000L).copy(id = "on"),
        )
        assertEquals(listOf("on", "next"), FixtureRules.teamFixtures("Man Utd", list, now).map { it.id })
    }

    // (Reading the server's JSON isn't unit-tested: org.json is a stub in local JVM tests without an extra test dependency.)

    @Test fun crestsAreAskedForSmall() {
        assertEquals("https://a.espncdn.com/combiner/i?img=i/teamlogos/soccer/500/364.png&w=96&h=96", FixtureRules.crestUrl("https://a.espncdn.com/i/teamlogos/soccer/500/364.png"))
        assertNull(FixtureRules.crestUrl(""))
    }

    @Test fun channelOrderIsLiveThenUkThenPreferred() {
        data class C(val n: String, val live: Boolean, val uk: Boolean, val pref: Int)
        val l = listOf(C("us", false, false, 9), C("uk-sd", false, true, 1), C("uk-hd", false, true, 5), C("live-foreign", true, false, 0))
        assertEquals(listOf("live-foreign", "uk-hd", "uk-sd", "us"), FixtureRules.sortChannels(l, { it.live }, { it.uk }, { it.pref }).map { it.n })
    }

    @Test fun clubChannelsAreFoundFromTheirNames() {
        assertTrue(FixtureRules.isTeamChannelGroup("UK| CHAMPIONSHIP PPV"))
        assertTrue(FixtureRules.isTeamChannelGroup("UK| SERIE A TEAM PPV"))
        assertTrue(FixtureRules.isTeamChannelGroup("UK| Football Clubs"))
        assertFalse(FixtureRules.isTeamChannelGroup("UK| Sports"))
        assertEquals("ac milan", FixtureRules.channelTeam("UK: SERIE A - AC MILAN 4K"))
        assertEquals("celtic", FixtureRules.channelTeam("Celtic TV"))
        assertEquals("blackburn rovers", FixtureRules.channelTeam("Blackburn Rovers"))
    }

    @Test fun teamChannelFallbackPrefersHomeThenAwayThenMatch() {
        val chans = listOf("Preston North End", "Blackburn Rovers HD", "UK: SERIE A - AC MILAN 4K", "Hearts FC", "Celtic TV", "National League 4 | Barrow vs Eastleigh // 15:00", "Barcelona")
        fun find(f: Fixture) = FixtureRules.teamChannelFor(f, chans) { it }
        assertEquals("Blackburn Rovers HD" to FixtureRules.Side.Home, find(fx(t("Blackburn Rovers"), t("Preston North End"), comp = "Championship")))
        assertEquals("Preston North End" to FixtureRules.Side.Away, find(fx(t("Hull City"), t("Preston North End"), comp = "Championship")))
        assertEquals("UK: SERIE A - AC MILAN 4K" to FixtureRules.Side.Home, find(fx(t("AC Milan"), t("Lazio"), comp = "Serie A")))
        assertEquals("Hearts FC" to FixtureRules.Side.Home, find(fx(t("Heart of Midlothian", "Hearts"), t("Rangers"), comp = "Scottish Premiership")))
        assertEquals("Celtic TV" to FixtureRules.Side.Away, find(fx(t("Aberdeen"), t("Celtic"), comp = "Scottish Premiership")))
        assertEquals("National League 4 | Barrow vs Eastleigh // 15:00" to FixtureRules.Side.Match, find(fx(t("Barrow"), t("Eastleigh"), comp = "National League")))
        assertEquals("Barcelona" to FixtureRules.Side.Home, find(fx(t("Barcelona"), t("Real Madrid"), comp = "LaLiga")))
        assertNull(find(fx(t("Arsenal"), t("Chelsea"))))
        assertNull(find(fx(null, null, comp = "F1", sport = "f1", title = "Grand Prix")))
        assertEquals("Barrow vs Eastleigh", FixtureRules.teamChannelLabel("National League 4 | Barrow vs Eastleigh // 15:00"))
        assertEquals("Blackburn Rovers", FixtureRules.teamChannelLabel("Blackburn Rovers"))
    }
}
