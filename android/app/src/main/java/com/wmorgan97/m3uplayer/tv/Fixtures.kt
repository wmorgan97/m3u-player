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

/** One fixture from the feed. Team sports have [home]/[away]; F1 sessions and UFC cards a [title] (and [session]). */
data class Fixture(
    val id: String, val sport: String, val comp: String, val compRank: Int, val start: Long, val end: Long, val status: String,
    val minute: String?, val home: FxTeam?, val away: FxTeam?, val title: String, val session: String, val venue: String, val round: String,
) {
    /** Live now, or due on: started and not reported finished (feeds lag a minute or two). */
    fun isOn(now: Long) = status == "live" || (status == "scheduled" && start <= now && now < end)
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
    fun foldName(s: String?): String {
        val t = Normalizer.normalize(s ?: "", Normalizer.Form.NFD).replace(MARKS, "").replace(SUPERSCRIPT, "").lowercase()
            .replace("&", " and ").replace(Regex("['’.]"), "").replace(NON, " ")
        return " $t ".replace(" utd ", " united ").replace(" saint ", " st ").replace(SPACES, " ").trim()
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
    private val PREFIX = Regex("""^(?:afc|fc|cf|ac|as|ssc|ss|us|sc|sv|vfb|vfl|tsg|rc|rcd|ud|cd|sd|ca|1 fc|fk|nk|sk|bsc|kv|krc|kaa|rsc|sl|gnk)\s+""")
    private val SUFFIX = Regex("""\s+(?:fc|afc|cf|sc|ac|bc|fk|city|town|united|rovers|albion|athletic|county|wanderers|hotspur|rugby|rfc|north end|argyle|alexandra|orient|stanley)$""")
    /** Cores shared by two clubs: "Manchester" alone could be City or United. */
    private val AMBIGUOUS = setOf("manchester", "sheffield", "bristol", "dundee", "nottingham", "notts", "birmingham", "oxford", "cambridge", "leicester tigers", "real", "inter", "sporting", "athletic", "paris", "new york", "los angeles", "la")
    private val SHORT_OK = Regex("""^[a-z]{2}\d|^\d""")

    /** Every way a team might be written: its names, nickname, aliases and the core without "FC" / "City" etc. */
    fun teamVariants(t: FxTeam?): List<String> {
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
            if (canon != null) { add(canon); for (l in ALIASES) if (foldName(l[0]) == canon) l.drop(1).forEach(::add) }
        }
        out.removeAll(AMBIGUOUS)
        return out.sortedByDescending { it.length }
    }

    private val WOMEN = Regex("""\b(?:women|womens|ladies|wsl|w)\b""")

    /** A follow ("Man Utd", "Spurs", "Chelsea Women", "F1") matches this fixture? Women's follows never match (the feed
     *  has no women's games, so those stay with the guide search). F1 / UFC: the follow names the competition. */
    fun followMatches(q: String, f: Fixture): Boolean {
        val k = foldName(q)
        if (k.isEmpty() || WOMEN.containsMatchIn(k)) return false
        if (f.home == null || f.away == null) return classifySport(q)?.let { it == f.comp } ?: false
        val canon = ALIAS_OF[k] ?: k
        for (t in listOf(f.home, f.away)) {
            val vs = teamVariants(t)
            if (k in vs || canon in vs) return true
            if (k.length >= 4 && " ${foldName(t.name)} ".contains(" $k ") && k !in AMBIGUOUS) return true
        }
        return false
    }

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
    fun titleHasTeams(title: String, hv: List<String>, av: List<String>): Boolean {
        val t = foldName(title)
        val a = spans(t, hv); if (a.isEmpty()) return false
        return spans(t, av).any { b -> a.any { x -> b.last < x.first || x.last < b.first } }
    }

    // ---------- team channels: the fallback when no main channel lists a game (js/fixtures.js teamChannelFor) ----------
    // Providers carry a channel per club ("UK| CHAMPIONSHIP PPV": "Blackburn Rovers", "UK| SPFL PPV": "Celtic TV",
    // "UK| SERIE A TEAM PPV": "UK: SERIE A - AC MILAN 4K") or per match ("National League 4 | Barrow vs Eastleigh // …").
    private val TEAM_GROUP = Regex("""\bppv\b|\bteam\b|\bclubs?\b""", RegexOption.IGNORE_CASE)
    private val CHANNEL_NOISE = Regex(""" (?:uk|gb|serie a|la ?liga|premier league|efl|spfl|championship|league one|league two|4k|uhd|fhd|hd|sd|raw|ppv|tv|channel|official)\b""")
    fun isTeamChannelGroup(g: String?) = TEAM_GROUP.containsMatchIn(g ?: "")
    /** "UK: SERIE A - AC MILAN 4K" -> "ac milan"; "Celtic TV" -> "celtic". */
    fun channelTeam(name: String): String {
        var s = " ${foldName(name)} "
        while (true) { val n = s.replace(CHANNEL_NOISE, " "); if (n == s) break; s = n }   // (adjacent tokens share a space)
        return s.replace(SPACES, " ").trim()
    }
    enum class Side { Home, Away, Match }
    /** The club's own channel for this fixture (home side first), or a channel named after the match itself. */
    fun <T> teamChannelFor(f: Fixture, chans: List<T>, name: (T) -> String): Pair<T, Side>? {
        if (f.home == null || f.away == null || chans.isEmpty()) return null
        val hv = teamVariants(f.home); val av = teamVariants(f.away)
        fun named(side: List<String>) = chans.firstOrNull { c ->
            val t = channelTeam(name(c))
            t.isNotEmpty() && side.any { v -> v == t || v == t.replace(PREFIX, "").replace(SUFFIX, "") }
        }
        named(hv)?.let { return it to Side.Home }
        named(av)?.let { return it to Side.Away }
        return chans.firstOrNull { titleHasTeams(name(it), hv, av) }?.let { it to Side.Match }
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
                o.optString("title"), o.optString("session"), o.optString("venue"), o.optString("round"))
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
                Log.i(TAG, "fixtures: ${l.size} (${l.count { it.isOn(now) }} on now)")
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
                    Log.i(TAG, "fixture channels: ${channels.value?.size} games on your channels")
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
