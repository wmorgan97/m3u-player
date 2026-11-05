package com.wmorgan97.m3uplayer.tv

import android.content.Context
import android.util.Log
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.Normalizer

// Real fixtures for the Sports page (as the iPhone: js/fixtures.js + the Sports section of js/app.js). The server's
// fixture list (GET /fixtures, server/fixtures.js) says what is actually being played; the guide is only asked which of
// your channels shows each game (POST /fixtures/channels, the same body as /guide). An older server without /fixtures,
// or a feed that fails with nothing saved, leaves the guide-only Sports page as it was.

data class FxTeam(val name: String, val short: String, val crest: String, val score: String?, val nick: String? = null) {
    /** The name shown on a card: the full name unless it's long ("Brighton & Hove Albion" -> "Brighton"). */
    val label get() = if (name.length <= 16 || short.isBlank()) name else short
}

/** A driver, player or fighter (F1, tennis, UFC): [flag] is a 3-letter country code; [sets] / [tb] a tennis score so far,
 *  [pos] / [gap] a place in a race's running order, [record] a fighter's "17-4-0". */
data class FxAth(val name: String, val short: String, val flag: String = "", val seed: Int? = null, val sets: List<String> = emptyList(), val tb: List<Int?> = emptyList(),
                 val win: Boolean = false, val record: String = "", val pos: Int? = null, val gap: String = "") {
    /** "Verstappen" from "M. Verstappen". */
    val surname get() = short.ifBlank { name }.replace(Regex("""^[A-Z]\.\s+"""), "")
}
/** A tennis match ([round], [court]) or a UFC bout ([weight], [card]: "Main card" / "Prelims", [main]: the main event). */
data class FxBout(val start: Long, val status: String, val a: FxAth, val b: FxAth, val round: String = "", val court: String = "", val detail: String = "",
                  val weight: String = "", val card: String = "", val main: Boolean = false) {
    fun has(q: String) = FixtureRules.namesPerson(q, a.name) || FixtureRules.namesPerson(q, b.name)
}

/** One fixture from the feed. Team sports have [home]/[away]; F1 sessions, tennis days and UFC cards a [title] (and
 *  [session]): F1 its [order] (top 3, Race / Sprint) and [drivers]; a tennis day its order of play ([matches]); a UFC
 *  card its [fights], main event first. */
data class Fixture(
    val id: String, val sport: String, val comp: String, val compRank: Int, val start: Long, val end: Long, val status: String,
    val minute: String?, val home: FxTeam?, val away: FxTeam?, val title: String, val session: String, val venue: String, val round: String,
    val country: String = "", val prelims: Long = 0L, val drivers: List<String> = emptyList(), val order: List<FxAth> = emptyList(),
    val matches: List<FxBout> = emptyList(), val fights: List<FxBout> = emptyList(), val women: Boolean = false,
) {
    /** Live now, or due on: started and not reported finished (feeds lag a minute or two). Tennis: only a match in
     *  progress (its later times are "not before" guesses). */
    fun isOn(now: Long) = status == "live" || (status == "scheduled" && matches.isEmpty() && start <= now && now < end)
    /** Still to come; a tennis day between matches (play resumes later today) too, though its first time has passed. */
    fun upcoming(now: Long) = status == "scheduled" && (start > now || (matches.isNotEmpty() && end > now))
    /** Everyone named in it (drivers, players, fighters), for follows. */
    val people: List<String> get() = drivers.ifEmpty { order.map { it.name } } + matches.flatMap { listOf(it.a.name, it.b.name) } + fights.flatMap { listOf(it.a.name, it.b.name) }
    /** The tennis match to show: the first on court, else the next, else the last played. */
    val focusMatch: FxBout? get() = matches.firstOrNull { it.status == "live" } ?: matches.firstOrNull { it.status == "scheduled" } ?: matches.lastOrNull()
    val mainEvent: FxBout? get() = fights.firstOrNull { it.main } ?: fights.firstOrNull()
    /** F1, tennis or UFC: has more to show than a programme (the running order, order of play, fight card). */
    val detailed get() = sport == "f1" || matches.isNotEmpty() || fights.isNotEmpty()
    /** "Arsenal v Chelsea", "São Paulo Grand Prix · Qualifying", "UFC 334: Gane v Hokit". */
    val label: String get() = if (home != null && away != null) "${home.short.ifBlank { home.name }} v ${away.short.ifBlank { away.name }}"
        else if (session.isNotBlank()) "$title · $session" else title
    val gone get() = status == "final" || status == "postponed"
}

/** A guide programme the server matched to a fixture: [id] / [name] are the guide's channel id and display name. */
data class FxMatch(val id: String, val name: String, val start: Long, val end: Long, val title: String, val desc: String, val live: Boolean)

/** One of your channels showing a fixture, with the guide's programme for it. */
data class FxChannel(val c: Channel, val p: Programme, val live: Boolean, val side: FixtureRules.Side? = null) {
    /** A club's or match's own channel (no guide listing; the programme is the fixture's own kick-off to end). */
    val teamCh get() = side != null
    /** "Sky Sports Main Event"; a club channel "Blackburn Rovers channel". */
    val where get() = if (side != null) FixtureRules.teamChannelLabel(channelLabel(c.name)) + " channel" else FixtureRules.shortChannel(c.name)
}

object FixtureRules {
    private val SUPERSCRIPT = Regex("[ᴬ-ᵪᶜ-ᶿ²³¹⁰-₟ⱽ]+")
    private val MARKS = Regex("\\p{Mn}+")
    private val NON = Regex("[^a-z0-9]+")
    private val SPACES = Regex("\\s+")

    /** "Brighton & Hove Albion" -> "brighton and hove albion"; "Nott'm Forest" -> "nottm forest"; "Man Utd" -> "man united";
     *  "Paris Saint-Germain" -> "paris st germain" (js/fixtures.js foldName). */
    private val APOS = Regex("['’.]")
    /** Folded names, kept (team and channel names repeat on every pass; folding them is most of the matching work). */
    private val folded = java.util.concurrent.ConcurrentHashMap<String, String>()
    fun foldName(s: String?): String {
        val src = s ?: ""
        folded[src]?.let { return it }
        val t = Normalizer.normalize(src, Normalizer.Form.NFD).replace(MARKS, "").replace(SUPERSCRIPT, "").lowercase()
            .replace("&", " and ").replace(APOS, "").replace(NON, " ")
        val out = " $t ".replace(" utd ", " united ").replace(" saint ", " st ").replace(SPACES, " ").trim()
        if (src.length <= 120) { if (folded.size > 50_000) folded.clear(); folded[src] = out }
        return out
    }

    private val ALIASES: List<List<String>> = listOf(
        listOf("manchester united", "man united", "man u"), listOf("manchester city", "man city"), listOf("tottenham hotspur", "spurs", "tottenham"),
        listOf("wolverhampton wanderers", "wolves", "wolverhampton"), listOf("brighton and hove albion", "brighton", "brighton hove albion"),
        listOf("nottingham forest", "nottm forest", "notts forest", "forest"), listOf("sheffield united", "sheff united"), listOf("sheffield wednesday", "sheff wed", "sheffield wed"),
        listOf("west bromwich albion", "west brom", "wba"), listOf("queens park rangers", "qpr"), listOf("middlesbrough", "boro"), listOf("afc bournemouth", "bournemouth"),
        listOf("preston north end", "preston", "pne"), listOf("milton keynes dons", "mk dons"), listOf("crystal palace", "palace", "c palace"),
        listOf("heart of midlothian", "hearts"), listOf("hibernian", "hibs"), listOf("st mirren"), listOf("st johnstone"),
        listOf("paris st germain", "psg", "paris sg", "paris"), listOf("olympique lyonnais", "lyon"), listOf("olympique de marseille", "marseille"), listOf("olympique marseille", "marseille"),
        listOf("as monaco", "monaco"), listOf("losc lille", "lille"), listOf("stade rennais", "rennes"), listOf("ogc nice", "nice"), listOf("rc lens", "lens"),
        listOf("internazionale", "inter", "inter milan", "inter milano"), listOf("ac milan", "milan"), listOf("juventus", "juve"), listOf("ssc napoli", "napoli"),
        listOf("bayern munich", "bayern munchen", "bayern", "fc bayern"), listOf("borussia dortmund", "dortmund", "bvb"), listOf("borussia monchengladbach", "gladbach", "monchengladbach"),
        listOf("bayer leverkusen", "leverkusen", "bayer 04 leverkusen"), listOf("rb leipzig", "leipzig"), listOf("eintracht frankfurt", "frankfurt"), listOf("1 fc koln", "koln", "cologne", "fc koln"),
        listOf("tsg hoffenheim", "hoffenheim"), listOf("hamburg sv", "hamburger sv", "hsv", "hamburg"), listOf("vfl wolfsburg", "wolfsburg"), listOf("sv werder bremen", "werder bremen", "bremen"), listOf("werder bremen", "bremen"), listOf("fc st pauli", "st pauli"),
        listOf("barcelona", "barca", "fc barcelona"), listOf("atletico madrid", "atletico", "atleti", "atletico de madrid"), listOf("athletic club", "athletic bilbao", "bilbao"),
        listOf("real betis", "betis"), listOf("real sociedad", "sociedad"), listOf("sevilla", "seville"), listOf("celta vigo", "celta"), listOf("deportivo alaves", "alaves"),
        listOf("sporting cp", "sporting lisbon", "sporting"), listOf("fc porto", "porto"), listOf("sl benfica", "benfica"), listOf("psv eindhoven", "psv"), listOf("feyenoord rotterdam", "feyenoord"),
        listOf("club brugge", "club bruges", "brugge"), listOf("red star belgrade", "crvena zvezda", "red star"), listOf("fc copenhagen", "copenhagen", "fc kobenhavn", "kobenhavn"),
        listOf("red bull salzburg", "salzburg", "rb salzburg"), listOf("shakhtar donetsk", "shakhtar"), listOf("dinamo zagreb", "gnk dinamo"), listOf("slavia prague", "slavia praha", "slavia"),
        listOf("sparta prague", "sparta praha", "sparta"), listOf("bodo glimt", "bodo/glimt", "bodoe glimt"), listOf("olympiacos", "olympiakos"), listOf("union st gilloise", "union sg", "usg"),
        listOf("young boys", "bsc young boys"), listOf("ferencvaros", "ferencvarosi"), listOf("qarabag", "qarabag fk"), listOf("fenerbahce"), listOf("besiktas"), listOf("galatasaray"),
    )
    private val ALIAS_OF: Map<String, String> = LinkedHashMap<String, String>().apply {
        for (l in ALIASES) { val canon = foldName(l[0]); for (n in l) putIfAbsent(foldName(n), canon) }
    }
    /** canon -> the other names in its alias list(s). */
    private val ALIAS_REST: Map<String, List<String>> = HashMap<String, MutableList<String>>().apply {
        for (l in ALIASES) getOrPut(foldName(l[0])) { ArrayList() } += l.drop(1)
    }
    private val PREFIX = Regex("""^(?:afc|fc|cf|ac|as|ssc|ss|us|sc|sv|vfb|vfl|tsg|rc|rcd|ud|cd|sd|ca|1 fc|fk|nk|sk|bsc|kv|krc|kaa|rsc|sl|gnk)\s+""")
    private val SUFFIX = Regex("""\s+(?:fc|afc|cf|sc|ac|bc|fk|city|town|united|rovers|albion|athletic|county|wanderers|hotspur|rugby|rfc|north end|argyle|alexandra|orient|stanley)$""")
    /** Cores shared by two clubs: "Manchester" alone could be City or United. */
    private val AMBIGUOUS = setOf("manchester", "sheffield", "bristol", "dundee", "nottingham", "notts", "birmingham", "oxford", "cambridge", "leicester tigers", "real", "inter", "sporting", "athletic", "paris", "new york", "los angeles", "la")
    private val SHORT_OK = Regex("""^[a-z]{2}\d|^\d""")

    /** Every way a team might be written: its names, nickname, aliases and the core without "FC" / "City" etc. */
    private val variants = java.util.concurrent.ConcurrentHashMap<String, List<String>>()
    fun teamVariants(t: FxTeam?): List<String> {
        if (t == null) return emptyList()
        val key = "${t.name}\u0001${t.short}\u0001${t.nick}"
        variants[key]?.let { return it }
        return variantsOf(t).also { if (variants.size > 20_000) variants.clear(); variants[key] = it }
    }
    private fun variantsOf(t: FxTeam?): List<String> {
        val out = LinkedHashSet<String>()
        fun add(s: String) { val k = foldName(s); if (k.length >= 3 || SHORT_OK.containsMatchIn(k)) out += k }
        for (n in listOfNotNull(t?.name, t?.short, t?.nick)) {
            if (n.isBlank()) continue
            val k = foldName(n)
            add(k)
            var core = k.replace(PREFIX, "")
            repeat(2) { core = core.replace(SUFFIX, "") }
            if (core.isNotEmpty() && core !in AMBIGUOUS) add(core)
            val canon = ALIAS_OF[k] ?: ALIAS_OF[core]
            if (canon != null) { add(canon); ALIAS_REST[canon]?.forEach(::add) }
        }
        out.removeAll(AMBIGUOUS)
        return out.sortedByDescending { it.length }
    }

    private val WOMEN_Q = Regex(""" (?:women|womens|ladies|wfc|lfc|w)\b""")
    /** A side's name marks it as a women's or a youth / reserve side ("Chelsea Women", "Arsenal W", "Chelsea U21",
     *  "Chelsea Academy"): following "Chelsea" means the men's first team only (js/fixtures.js). */
    private val WOMEN_NAME = Regex("""\b(?:women|womens|ladies|wfc|wsl|w)\b""")
    private val YOUTH_NAME = Regex("""\b(?:u ?(?:1[5-9]|2[0-3])s?|under ?(?:1[5-9]|2[0-3])s?|academy|reserves?|youth|b team)\b""")
    private val SIDE_TAGS = Regex("""\b(?:women|womens|ladies|wfc|w|u ?(?:1[5-9]|2[0-3])s?|academy|reserves?|youth)\b""")
    fun isWomensName(s: String?) = WOMEN_NAME.containsMatchIn(foldName(s))
    fun isYouthName(s: String?) = YOUTH_NAME.containsMatchIn(foldName(s))

    /** 2026 teams, for following a team ("McLaren") rather than a driver (js/fixtures.js F1_TEAMS). */
    val F1_TEAMS = setOf("red bull", "ferrari", "mercedes", "mclaren", "aston martin", "alpine", "williams", "racing bulls", "haas", "audi", "sauber", "cadillac")
    /** A follow names this person? "Lewis Hamilton", or the surname alone (4+ letters): "Hamilton", "Martin Etcheverry". */
    fun namesPerson(q: String, name: String): Boolean {
        val k = foldName(q); val n = foldName(name)
        if (k.isEmpty() || n.isEmpty()) return false
        if (k == n) return true
        val w = n.split(' ')
        return k.length >= 4 && w.size > 1 && (k == w.drop(1).joinToString(" ") || k == w.last())
    }

    /** A follow ("Man Utd", "Spurs", "Chelsea Women", "F1", "Hamilton", "Alcaraz") matches this fixture? "Chelsea" is the
     *  men's first team: never a women's game (the feed's WSL) or a side named "… Women" / "… U21" / "… Academy";
     *  "Chelsea Women" the women's. F1 / tennis / UFC: the follow names the sport, a driver or F1 team, a player or a
     *  fighter. Same rules as js/fixtures.js followMatches. */
    fun followMatches(q: String, f: Fixture): Boolean {
        var k = foldName(q)
        val women = WOMEN_Q.containsMatchIn(" $k")
        if (women) k = " $k".replace(WOMEN_Q, "").trim()
        if (k.isEmpty() || women != f.women) return false
        val youth = YOUTH_NAME.containsMatchIn(k)
        if (f.home == null || f.away == null) {
            classifySport(q)?.let { c -> return c == f.comp || (c == "F1" && f.sport == "f1") || (c == "Tennis" && f.sport == "tennis") || (c == "UFC" && f.sport == "ufc") }
            if (women) return false
            if (f.sport == "f1" && (k in F1_TEAMS || k.replace(F1_TEAM_TAIL, "") in F1_TEAMS)) return true
            return f.people.any { namesPerson(k, it) }
        }
        val canon = ALIAS_OF[k] ?: k
        for (t in listOf(f.home, f.away)) {
            if (t.name.isBlank()) continue
            if (isYouthName(t.name) != youth || (!women && isWomensName(t.name))) continue
            val vs = teamVariants(t)
            if (k in vs || canon in vs) return true
            if (k.length >= 4 && " ${foldName(t.name)} ".contains(" $k ") && k !in AMBIGUOUS) return true
        }
        return false
    }

    private val F1_TEAM_TAIL = Regex(" (?:f1|racing|team)$")
    /** Which side of a fixture a follow is about (for its crest), or null. */
    fun followedSide(q: String, f: Fixture): FxTeam? = listOfNotNull(f.home, f.away).firstOrNull { t ->
        followMatches(q, f.copy(home = t, away = FxTeam("", "", "", null)))
    }

    /** A followed team's fixtures still to come or on now, soonest first. */
    fun teamFixtures(q: String, list: List<Fixture>, now: Long): List<Fixture> =
        list.filter { !it.gone && it.end > now && followMatches(q, it) }.sortedBy { it.start }

    /** Where a variant appears in a folded title, as whole words. */
    private fun spans(t: String, variants: List<String>): List<IntRange> {
        val out = ArrayList<IntRange>(); val s = " $t "
        for (v in variants) { var i = s.indexOf(" $v "); while (i >= 0) { out += i until i + v.length; i = s.indexOf(" $v ", i + 1) } }
        return out
    }
    /** True when a title names both teams (either order, not the same words twice). */
    fun titleHasTeams(title: String, hv: List<String>, av: List<String>): Boolean = foldedHasTeams(foldName(title), hv, av)
    private fun foldedHasTeams(t: String, hv: List<String>, av: List<String>): Boolean {
        val a = spans(t, hv); if (a.isEmpty()) return false
        return spans(t, av).any { b -> a.any { x -> b.last < x.first || x.last < b.first } }
    }

    // ---------- team channels: the fallback when no main channel lists a game (js/fixtures.js teamChannelFor) ----------
    // Providers carry a channel per club ("UK| CHAMPIONSHIP PPV": "Blackburn Rovers", "UK| SPFL PPV": "Celtic TV",
    // "UK| SERIE A TEAM PPV": "UK: SERIE A - AC MILAN 4K") or per match ("National League 4 | Barrow vs Eastleigh // …").
    // Premier League club channels sit in groups like "UK| PREMIER LEAGUE TEAMS", "UK| PREMIER LEAGUE CLUBS" or "EPL TEAMS".
    private val TEAM_GROUP = Regex("""\bppv\b|\bteams?\b|\bclubs?\b|\bepl\b|\bpremier league\b""", RegexOption.IGNORE_CASE)
    private val CHANNEL_NOISE = Regex(""" (?:uk|gb|serie a|la ?liga|premier league|epl|efl|spfl|championship|league one|league two|4k|uhd|fhd|hd|sd|raw|ppv|tv|channel|official)\b""")
    fun isTeamChannelGroup(g: String?) = TEAM_GROUP.containsMatchIn(g ?: "")
    /** "UK: SERIE A - AC MILAN 4K" -> "ac milan"; "Celtic TV" -> "celtic". */
    fun channelTeam(name: String): String {
        var s = " ${foldName(name)} "
        while (true) { val n = s.replace(CHANNEL_NOISE, " "); if (n == s) break; s = n }   // (adjacent tokens share a space)
        return s.replace(SPACES, " ").trim()
    }
    enum class Side { Home, Away, Match }
    /** The team channels worked out once per channel list: the team each is named after (and the club's bare name for a
     *  women's / youth channel, checked by side), so each fixture is a few map lookups instead of a pass over them all. */
    class TeamChannels<T>(chans: List<T>, name: (T) -> String) {
        class E<T>(val c: T, val folded: String, val women: Boolean, val youth: Boolean)
        val list: List<E<T>> = chans.map { val n = name(it); E(it, foldName(n), isWomensName(n), isYouthName(n)) }
        val byKey: Map<String, List<E<T>>> = HashMap<String, MutableList<E<T>>>().also { m ->
            for (e in list) {
                val t = channelTeam(e.folded); if (t.isEmpty()) continue
                val bare = t.replace(SIDE_TAGS, " ").replace(SPACES, " ").trim()
                for (k in linkedSetOf(t, t.replace(PREFIX, "").replace(SUFFIX, ""), bare, bare.replace(PREFIX, "").replace(SUFFIX, ""))) if (k.isNotEmpty()) m.getOrPut(k) { ArrayList() }.let { if (e !in it) it += e }
            }
        }
        val size get() = list.size
    }
    /** The club's own channel for this fixture (home side first), or a channel named after the match itself. Never a
     *  women's or youth side's channel for a men's game (nor the other way round). */
    fun <T> teamChannelFor(f: Fixture, ix: TeamChannels<T>): Pair<T, Side>? {
        if (f.home == null || f.away == null || ix.size == 0) return null
        val women = f.women || isWomensName(f.home.name); val youth = isYouthName(f.home.name) || isYouthName(f.away.name)
        fun ok(e: TeamChannels.E<T>) = e.women == women && e.youth == youth
        val hv = teamVariants(f.home); val av = teamVariants(f.away)
        fun named(side: List<String>): T? { for (v in side) ix.byKey[v]?.firstOrNull(::ok)?.let { return it.c }; return null }
        named(hv)?.let { return it to Side.Home }
        named(av)?.let { return it to Side.Away }
        return ix.list.firstOrNull { ok(it) && foldedHasTeams(it.folded, hv, av) }?.let { it.c to Side.Match }
    }
    fun <T> teamChannelFor(f: Fixture, chans: List<T>, name: (T) -> String): Pair<T, Side>? = teamChannelFor(f, TeamChannels(chans, name))

    // ---------- one card per game ----------
    /** US teams: the guide often says the city alone ("NHL: Seattle v Calgary" for Seattle Kraken v Calgary Flames). */
    private fun sideWords(t: FxTeam): List<String> {
        val v = teamVariants(t); val n = foldName(t.name); val k = foldName(t.nick)
        val city = if (k.isNotEmpty() && n.endsWith(" $k")) n.dropLast(k.length + 1) else ""
        return if (city.isNotEmpty() && city !in AMBIGUOUS && city !in v) v + city else v
    }
    private val US_SPORT = mapOf("NFL" to "nfl", "NBA" to "nba", "NHL" to "nhl", "MLB" to "mlb", "Tennis" to "tennis", "F1" to "f1", "UFC" to "ufc", "Golf" to "golf",
        "Rugby" to "rugby", "Six Nations" to "rugby", "Cricket" to "cricket", "Darts" to "darts", "Snooker" to "snooker", "Boxing" to "boxing", "WWE" to "wwe", "MotoGP" to "motogp")
    /** The fixture a guide programme is the same game as (both teams named, starting within 90 minutes, not another
     *  sport, not the women's game), so the page shows the fixture's card once (js/fixtures.js fixtureForProgramme). */
    fun fixtureForProgramme(title: String, start: Long, fixtures: List<Fixture>): Fixture? {
        val sport = classifySport(title)?.let { US_SPORT[it] ?: "football" }
        val t = foldName(title)
        val women = isWomensFixture(title)
        for (f in fixtures) {
            if (f.home == null || f.away == null || kotlin.math.abs(f.start - start) > 90 * 60_000L || (sport != null && sport != f.sport) || women != f.women) continue
            if (foldedHasTeams(t, sideWords(f.home), sideWords(f.away))) return f
        }
        return null
    }

    /** One follow per team: "Lakers" and "Los Angeles Lakers" (or "Man Utd" and "Manchester United") are the same side,
     *  so they show as one tile (js/fixtures.js followTeamKey / dedupeFollows). */
    fun followTeamKey(q: String, fixtures: List<Fixture>): String {
        for (f in fixtures) {
            if (f.home == null || f.away == null || !followMatches(q, f)) continue
            followedSide(q, f)?.let { return "${f.sport}:${foldName(it.name)}" + if (f.women) ":w" else "" }
        }
        val k = foldName(q); return ALIAS_OF[k] ?: k
    }
    fun <T> dedupeFollows(follows: List<T>, fixtures: List<Fixture>, q: (T) -> String): List<T> {
        val seen = HashSet<String>(); return follows.filter { seen.add(followTeamKey(q(it), fixtures)) }
    }
    /** A channel's name as a card says it: "UK: Sky Sports Main Event HD" -> "Sky Sports Main Event HD". */
    fun shortChannel(name: String): String = channelLabel(name).replace(Regex("""^\s*(?:uk|gb)\s*[:|\-]\s*""", RegexOption.IGNORE_CASE), "").ifBlank { name }
    /** "National League 4 | Barrow vs Eastleigh // 15:00" -> "Barrow vs Eastleigh"; "UK: Blackburn Rovers" stays. */
    fun teamChannelLabel(name: String): String = name.replace(Regex("""\s*//.*$"""), "").replace(Regex("""^.*\|\s*"""), "").trim().ifEmpty { name }

    /** The best copy first: tagged live by the guide, then a UK channel, then the main / English / HD copy. */
    fun <T> sortChannels(list: List<T>, live: (T) -> Boolean, uk: (T) -> Boolean, prefer: (T) -> Int): List<T> =
        list.sortedWith(compareByDescending<T> { live(it) }.thenByDescending { uk(it) }.thenByDescending { prefer(it) })

    /** Club crests from the feed's CDN, asked for at the size shown (a 4 KB picture instead of a 36 KB one). */
    fun crestUrl(u: String?, px: Int = 96): String? = when {
        u.isNullOrBlank() -> null
        u.startsWith("https://a.espncdn.com/i/") -> "https://a.espncdn.com/combiner/i?img=${u.substring(22)}&w=$px&h=$px"
        else -> u
    }

    // ---------- reading the server's replies ----------

    private fun strs(a: JSONArray?): List<String> = if (a == null) emptyList() else (0 until a.length()).map { a.optString(it) }
    private fun ath(o: JSONObject?): FxAth = if (o == null) FxAth("", "") else FxAth(o.optString("name"), o.optString("short"), o.optString("flag"),
        o.optInt("seed").takeIf { it > 0 }, strs(o.optJSONArray("sets")), o.optJSONArray("tb")?.let { a -> (0 until a.length()).map { if (a.isNull(it)) null else a.optInt(it) } }.orEmpty(),
        o.optBoolean("win"), o.optString("record"), o.optInt("pos").takeIf { it > 0 }, o.optString("gap"))
    private fun bouts(a: JSONArray?): List<FxBout> = if (a == null) emptyList() else (0 until a.length()).mapNotNull { i ->
        val m = a.optJSONObject(i) ?: return@mapNotNull null
        FxBout(m.optLong("start"), m.optString("status", "scheduled"), ath(m.optJSONObject("a")), ath(m.optJSONObject("b")), m.optString("round"), m.optString("court"),
            m.optString("detail"), m.optString("weight"), m.optString("card"), m.optBoolean("main"))
    }

    private fun team(o: JSONObject?): FxTeam? = o?.let {
        FxTeam(it.optString("name"), it.optString("short"), it.optString("crest"), if (it.has("score") && !it.isNull("score")) it.optString("score") else null,
            it.optString("nick").ifBlank { null })
    }

    fun parseFixtures(text: String): List<Fixture> {
        val a = JSONObject(text).optJSONArray("fixtures") ?: throw org.json.JSONException("no fixtures")
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val start = o.optLong("start"); if (start <= 0) return@mapNotNull null
            Fixture(o.optString("id"), o.optString("sport"), o.optString("comp"), o.optInt("compRank"), start, o.optLong("end", start + 2 * 3600_000L),
                o.optString("status", "scheduled"), o.optString("minute").ifBlank { null }, team(o.optJSONObject("home")), team(o.optJSONObject("away")),
                o.optString("title"), o.optString("session"), o.optString("venue"), o.optString("round"), o.optString("country"), o.optLong("prelims"),
                strs(o.optJSONArray("drivers")), o.optJSONArray("order")?.let { a -> (0 until a.length()).map { ath(a.optJSONObject(it)) } }.orEmpty(),
                bouts(o.optJSONArray("matches")), bouts(o.optJSONArray("fights")), o.optBoolean("women"))
        }
    }

    /** True when the reply says at least one competition was fetched fine (an empty list is then real, not a failure). */
    fun anySourceOk(text: String): Boolean {
        val s = JSONObject(text).optJSONObject("sources") ?: return false
        return s.keys().asSequence().any { s.optJSONObject(it)?.optBoolean("ok") == true }
    }

    fun parseChannels(text: String): Map<String, List<FxMatch>> {
        val o = JSONObject(text).optJSONObject("channels") ?: return emptyMap()
        val out = HashMap<String, List<FxMatch>>()
        for (k in o.keys()) {
            val a = o.optJSONArray(k) ?: continue
            out[k] = (0 until a.length()).mapNotNull { i ->
                val m = a.optJSONObject(i) ?: return@mapNotNull null
                FxMatch(m.optString("id"), m.optString("name"), m.optLong("start"), m.optLong("end"), m.optString("title"), m.optString("desc"), m.optBoolean("live"))
            }
        }
        return out
    }
}

/** The fixture list and which guide programmes show each game. Lives as long as the app, saved on the TV so the page
 *  opens with its last copy at once (and offline). */
object FixturesHub {
    private const val TAG = "M3U"
    const val FRESH = 15 * 60_000L
    const val LIVE_FRESH = 2 * 60_000L
    private const val CH_FRESH = 10 * 60_000L

    /** null: not known yet; true: the feed works (or a saved copy is here); false: an older server or no feed. */
    val ok = mutableStateOf<Boolean?>(null)
    val list = mutableStateOf<List<Fixture>?>(null)
    /** fixture id -> matched guide programmes (raw; mapped onto your channels when the page is worked out). */
    val channels = mutableStateOf<Map<String, List<FxMatch>>?>(null)
    /** Bumped when [list] or [channels] change. */
    val version = mutableIntStateOf(0)
    @Volatile var at = 0L; private set
    @Volatile private var chAt = 0L
    @Volatile private var chFor = ""
    @Volatile private var cacheRead = false
    private val lock = kotlinx.coroutines.sync.Mutex()
    private val chLock = kotlinx.coroutines.sync.Mutex()
    /** adb test hook (`--es testSports nofixtures`): this run behaves as an older server (the guide-only page). */
    @Volatile var disabled = false

    val usable get() = !disabled && ok.value == true && list.value != null

    private fun file(c: Context) = File(c.filesDir, "fixtures.json")
    private fun chFile(c: Context) = File(c.filesDir, "fixtures-ch" + (if (Repo.sportsAll) "-all" else "") + ".json")

    /** The saved copies, once (fast: no network). */
    private val cacheLock = kotlinx.coroutines.sync.Mutex()
    suspend fun readCache(c: Context) = cacheLock.withLock { withContext(Dispatchers.IO) {
        if (cacheRead) return@withContext
        cacheRead = true
        runCatching {
            val f = file(c)
            if (f.exists() && list.value == null) {
                list.value = FixtureRules.parseFixtures(f.readText()); ok.value = true; at = 0L   // saved: still asked again now
            }
        }.onFailure { Log.w(TAG, "fixtures cache unreadable: ${it.javaClass.simpleName}") }
        runCatching { val f = chFile(c); if (f.exists() && channels.value == null) channels.value = FixtureRules.parseChannels(f.readText()) }
        version.intValue++
        SportsPerf.mark("fixtures cache read (${list.value?.size} fixtures, channels ${channels.value?.size})")
    } }

    fun anyOn(now: Long) = list.value.orEmpty().any { it.isOn(now) || (it.status == "scheduled" && it.start in now..now + LIVE_FRESH) }

    /** Fresh fixtures when the saved ones are older than 15 minutes (2 while a game is on), or [force]. */
    suspend fun refresh(c: Context, force: Boolean = false): Boolean = lock.withLockIfFree {
        val now = System.currentTimeMillis()
        val fresh = if (anyOn(now)) LIVE_FRESH else FRESH
        if (!force && at > 0 && now - at < fresh) return@withLockIfFree false
        at = now   // also after a failure: tried again later, not on every pass
        withContext(Dispatchers.IO) {
            try {
                val text = get(Repo.api("fixtures") + "&days=7")
                val l = FixtureRules.parseFixtures(text)
                // Keep the last good list if the feed has nothing at all right now.
                if (l.isNotEmpty() || list.value == null) { list.value = l; runCatching { file(c).writeText(text) } }
                ok.value = !list.value.isNullOrEmpty() || FixtureRules.anySourceOk(text)
                Log.i(TAG, "fixtures: ${l.size} (${l.count { it.isOn(now) }} on now)"); SportsPerf.mark("fixtures fetched (${l.size})")
            } catch (e: Exception) {
                val code = (e as? HttpCode)?.code
                Log.w(TAG, "fixtures failed: ${code ?: e.javaClass.simpleName}")
                if (code == 404 || code == 405) { if (list.value == null) ok.value = false }   // an older server
                else if (list.value == null) ok.value = false
            }
            version.intValue++
        }
        true
    } ?: false

    /** Which of your channels shows each game: the server matches the fixtures against the guide of the channels in
     *  [ix] (UK ones unless you chose otherwise). Asked again every 10 minutes, or when the channel set changes. */
    internal suspend fun refreshChannels(c: Context, live: Repo.Live, ids: Collection<String>, names: Collection<String>, sig: String, force: Boolean = false) {
        chLock.withLockIfFree {
            val now = System.currentTimeMillis()
            if (!usable || live.epgUrls.isEmpty()) return@withLockIfFree
            if (!force && sig == chFor && now - chAt < CH_FRESH) return@withLockIfFree
            withContext(Dispatchers.IO) {
                try {
                    val body = JSONObject().put("urls", JSONArray(live.epgUrls.take(4))).put("ids", JSONArray(ids)).put("names", JSONArray(names)).put("days", 3).toString()
                    val text = post(Repo.api("fixtures/channels"), body)
                    channels.value = FixtureRules.parseChannels(text); chAt = System.currentTimeMillis(); chFor = sig
                    runCatching { chFile(c).writeText(text) }
                    Log.i(TAG, "fixture channels: ${channels.value?.size} games on your channels"); SportsPerf.mark("fixture channels back (${channels.value?.size})")
                } catch (e: Exception) {
                    Log.w(TAG, "fixture channels failed: ${(e as? HttpCode)?.code ?: e.javaClass.simpleName}")
                    chAt = now - CH_FRESH + 2 * 60_000L; chFor = sig   // try again in 2 minutes, not in a loop
                    if (channels.value == null) channels.value = emptyMap()
                }
                version.intValue++
            }
        }
    }
    /** Switching UK / all countries: the saved copy for that choice, and a fresh match. */
    fun scopeChanged(c: Context) { chAt = 0; channels.value = runCatching { chFile(c).takeIf { it.exists() }?.readText()?.let(FixtureRules::parseChannels) }.getOrNull(); version.intValue++ }

    private class HttpCode(val code: Int) : java.io.IOException("HTTP $code")
    private fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000; readTimeout = 40_000
        setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android TV) M3UPlayer")
    }
    private fun read(c: HttpURLConnection): String {
        if (c.responseCode !in 200..299) { c.errorStream?.close(); throw HttpCode(c.responseCode) }
        return c.inputStream.bufferedReader().use { it.readText() }
    }
    private fun get(url: String) = read(open(url))
    private fun post(url: String, body: String) = read(open(url).apply {
        requestMethod = "POST"; doOutput = true; setRequestProperty("Content-Type", "application/json")
        outputStream.use { it.write(body.toByteArray()) }
    })

    /** Runs [block] unless another caller is already in it (then null: that one's result will land in the state). */
    private suspend inline fun <T> kotlinx.coroutines.sync.Mutex.withLockIfFree(block: () -> T): T? {
        if (!tryLock()) return null
        try { return block() } finally { unlock() }
    }
}
