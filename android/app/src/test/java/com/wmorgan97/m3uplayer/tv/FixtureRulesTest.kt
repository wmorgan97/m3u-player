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
        // Ambiguous, another club, or a women's follow (the men's game is never the women's side's).
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

    // F1 / tennis / UFC (the shapes server/fixtures.js gives: tests/espn-samples has the recorded replies they come from).
    private fun ath(n: String, short: String, sets: List<String> = emptyList()) = FxAth(n, short, sets = sets)

    @Test fun followsADriverATeamAPlayerOrAFighter() {
        val race = fx(null, null, comp = "F1", sport = "f1", title = "São Paulo Grand Prix", session = "Race").copy(drivers = listOf("Lewis Hamilton", "Max Verstappen", "Kimi Antonelli"))
        for (q in listOf("Lewis Hamilton", "Hamilton", "verstappen", "McLaren", "Ferrari", "F1")) assertTrue(q, FixtureRules.followMatches(q, race))
        for (q in listOf("Lewis", "Arsenal", "Alcaraz", "UFC", "Tennis")) assertFalse(q, FixtureRules.followMatches(q, race))
        val m = FxBout(5_000_000L, "scheduled", ath("Ben Shelton", "B. Shelton"), ath("Tomas Martin Etcheverry", "T. Etcheverry"), round = "Round 3", court = "Court Central")
        val day = fx(null, null, comp = "ATP", sport = "tennis", title = "Paris Masters", session = "Round 3").copy(matches = listOf(m))
        for (q in listOf("Shelton", "Ben Shelton", "Etcheverry", "Martin Etcheverry", "Tennis")) assertTrue(q, FixtureRules.followMatches(q, day))
        for (q in listOf("Ben", "Sinner", "F1")) assertFalse(q, FixtureRules.followMatches(q, day))
        assertTrue(m.has("shelton")); assertEquals("Etcheverry", m.b.surname)
        val card = fx(null, null, comp = "UFC", sport = "ufc", title = "UFC Fight Night: Cortes Acosta v Walker").copy(
            fights = listOf(FxBout(0, "scheduled", ath("Waldo Cortes Acosta", "W. Cortes Acosta"), ath("Johnny Walker", "J. Walker"), weight = "Heavyweight", card = "Main card", main = true)))
        for (q in listOf("UFC", "Johnny Walker", "Cortes Acosta")) assertTrue(q, FixtureRules.followMatches(q, card))
        assertEquals("Johnny Walker", card.mainEvent?.b?.name)
    }

    @Test fun aTennisDayIsLiveOnlyWithAMatchOnCourt() {
        val start = 1_000_000L
        val waiting = fx(null, null, comp = "ATP", sport = "tennis", start = start).copy(matches = listOf(FxBout(start, "scheduled", ath("A", "A"), ath("B", "B"))))
        assertFalse(waiting.isOn(start + 60_000))            // a "not before" time passed: not live
        assertTrue(waiting.upcoming(start + 60_000))         // still to come today
        val race = fx(null, null, comp = "F1", sport = "f1", start = start)
        assertTrue(race.isOn(start + 60_000))                // others: due on (the feed lags a minute)
        assertFalse(race.upcoming(start + 60_000))
        val on = waiting.copy(status = "live", matches = listOf(FxBout(start, "live", ath("A", "A", listOf("6", "3")), ath("B", "B", listOf("4", "2")))))
        assertTrue(on.isOn(start + 60_000)); assertEquals("live", on.focusMatch?.status)
        assertTrue(on.detailed); assertFalse(fx(MUN, TOT).detailed)
    }

    // ---------- sports polish: men's first team only, Premier League club channels, one card per game, one tile per team ----------
    private fun side(n: String, nick: String? = null) = FxTeam(n, n, "", null, nick)
    private fun game(h: FxTeam, a: FxTeam, id: String = "x:1", sport: String = "football", comp: String = "Premier League", start: Long = 1_000_000L, women: Boolean = false) =
        Fixture(id, sport, comp, 100, start, start + 7_200_000L, "scheduled", null, h, a, "", "", "", "", women = women)

    @Test fun chelseaMeansTheMensFirstTeam() {
        val men = game(side("Chelsea"), side("Arsenal"))
        assertTrue(FixtureRules.followMatches("Chelsea", men))
        for (f in listOf(game(side("Chelsea"), side("Arsenal"), comp = "WSL", women = true), game(side("Chelsea Women"), side("Arsenal Women")),
                game(side("Chelsea U21"), side("Exeter City"), comp = "EFL Trophy"), game(side("Chelsea Academy"), side("Fulham Academy"))))
            assertFalse(f.home!!.name + " " + f.comp, FixtureRules.followMatches("Chelsea", f))
        assertTrue(FixtureRules.followMatches("Chelsea Women", game(side("Chelsea"), side("Arsenal"), women = true)))
        assertFalse(FixtureRules.followMatches("Chelsea Women", men))
        assertTrue(FixtureRules.followMatches("Chelsea U21", game(side("Chelsea U21"), side("Exeter City"))))
        assertTrue(FixtureRules.followMatches("Exeter City", game(side("Chelsea U21"), side("Exeter City"))))
        // F1 / tennis / UFC follows never pick up a club; a club follow never picks up a race, a tennis day or a card.
        val race = Fixture("f1:1", "f1", "F1", 92, 1L, 2L, "scheduled", null, null, null, "Las Vegas Grand Prix", "Race", "", "", drivers = listOf("Lewis Hamilton"))
        val day = Fixture("t:1", "tennis", "WTA", 52, 1L, 2L, "scheduled", null, null, null, "WTA Finals", "", "", "",
            matches = listOf(FxBout(1L, "scheduled", FxAth("Chelsea Smith", "C. Smith"), FxAth("Coco Gauff", "C. Gauff"))))
        for (f in listOf(race, day)) assertFalse(f.id, FixtureRules.followMatches("Chelsea", f))
        for (q in listOf("F1", "Tennis", "UFC", "Hamilton", "Gauff")) assertFalse(q, FixtureRules.followMatches(q, men))
        assertTrue(FixtureRules.followMatches("Hamilton", race))
    }

    @Test fun premierLeagueClubChannelsAndNeverTheWomensOrU21() {
        for (g in listOf("UK| PREMIER LEAGUE TEAMS", "EPL TEAMS", "UK| PREMIER LEAGUE CLUBS", "UK| CHAMPIONSHIP PPV")) assertTrue(g, FixtureRules.isTeamChannelGroup(g))
        for (g in listOf("UK| SPORTS", "UK| ENTERTAINMENT")) assertFalse(g, FixtureRules.isTeamChannelGroup(g))
        val chans = listOf("UK: Chelsea Women HD", "UK: Chelsea U21 HD", "Chelsea Women v Arsenal Women", "UK: Everton HD", "Fulham FC", "UK: Chelsea TV HD")
        val ix = FixtureRules.TeamChannels(chans) { it }
        assertEquals("UK: Chelsea TV HD", FixtureRules.teamChannelFor(game(side("Chelsea"), side("Arsenal")), ix)?.first)
        assertNull(FixtureRules.teamChannelFor(game(side("Chelsea"), side("Arsenal")), chans.filter { !it.contains("Chelsea TV") }) { it })
        assertEquals("UK: Chelsea Women HD", FixtureRules.teamChannelFor(game(side("Chelsea"), side("Arsenal"), women = true), ix)?.first)
        assertEquals("UK: Everton HD", FixtureRules.teamChannelFor(game(side("Everton"), side("Tottenham Hotspur")), ix)?.first)
        assertEquals(FixtureRules.Side.Away, FixtureRules.teamChannelFor(game(side("Wolverhampton Wanderers"), side("Fulham")), ix)?.second)
    }

    @Test fun aGuideListingOfAFixtureIsThatFixture() {
        val t = 1_800_000_000_000L
        val nhl = game(side("Seattle Kraken", "Kraken"), side("Calgary Flames", "Flames"), id = "nhl:1", sport = "nhl", comp = "NHL", start = t)
        val pl = game(MUN, TOT, id = "eng.1:1", start = t)
        assertEquals("nhl:1", FixtureRules.fixtureForProgramme("Live NHL: Seattle v Calgary", t + 300_000L, listOf(pl, nhl))?.id)
        assertEquals("eng.1:1", FixtureRules.fixtureForProgramme("Man Utd v Spurs", t - 1_800_000L, listOf(pl, nhl))?.id)
        assertNull(FixtureRules.fixtureForProgramme("Seattle v Calgary", t + 5 * 3_600_000L, listOf(nhl)))
        assertNull(FixtureRules.fixtureForProgramme("NBA: Seattle v Calgary", t, listOf(nhl)))
        assertNull(FixtureRules.fixtureForProgramme("Women's Super League: Man Utd v Spurs", t, listOf(pl)))
    }

    @Test fun oneTilePerFollowedTeam() {
        val nba = game(side("Los Angeles Lakers", "Lakers"), side("Boston Celtics", "Celtics"), id = "nba:1", sport = "nba", comp = "NBA")
        val pl = game(MUN, side("Chelsea"))
        val qs = listOf("Lakers", "Los Angeles Lakers", "Man Utd", "Manchester United", "Chelsea", "Chelsea Women", "Celtics")
        assertEquals(listOf("Lakers", "Man Utd", "Chelsea", "Chelsea Women", "Celtics"), FixtureRules.dedupeFollows(qs, listOf(nba, pl)) { it })
        assertEquals(FixtureRules.followTeamKey("Spurs", emptyList()), FixtureRules.followTeamKey("Tottenham Hotspur", emptyList()))
    }

}
