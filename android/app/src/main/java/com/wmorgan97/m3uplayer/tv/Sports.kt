package com.wmorgan97.m3uplayer.tv

import android.content.Context
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.SportsSoccer
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.focusable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

// Sports: what's live now and what's coming up in the next three days, across the whole TV guide, on your own channels.
// The server searches every guide channel for each competition (POST /guide {find}); the matches are mapped onto the
// playlist's channels the way the web app's "On TV" search does (js/follow.js matchUpcoming): one copy per title and
// start, preferring the main, English, HD channel. Results are kept on the TV for 30 minutes, and the page fills in
// competition by competition while the searches run.

/** A competition and the guide searches that find it. [must], when set, is what a title has to contain to count
 *  ("Formula 1" searches for "formula", which also finds Formula E). [fixtures]: team sports, where only a match
 *  ("A v B", "Live …") is worth listing, not the magazine shows around it. */
private data class Competition(val label: String, val queries: List<String>, val fixtures: Boolean, val must: Regex? = null, val football: Boolean = false)

private val COMPETITIONS = listOf(
    Competition("Premier League", listOf("Premier League"), true, football = true),
    Competition("Champions League", listOf("Champions League"), true, football = true),
    Competition("Europa League", listOf("Europa League"), true, football = true),
    Competition("Conference League", listOf("Conference League"), true, football = true),
    Competition("FA Cup", listOf("FA Cup"), true, football = true),
    Competition("EFL", listOf("EFL", "Carabao Cup", "Sky Bet Championship"), true, football = true),
    Competition("Scottish Premiership", listOf("Scottish Premiership", "SPFL"), true, football = true),
    Competition("LaLiga", listOf("LaLiga", "La Liga"), true, football = true),
    Competition("Serie A", listOf("Serie A"), true, football = true),
    Competition("Bundesliga", listOf("Bundesliga"), true, football = true),
    Competition("MLS", listOf("MLS", "Major League Soccer"), true, Regex("""\bmls\b|major league soccer""", RegexOption.IGNORE_CASE), football = true),
    Competition("Formula 1", listOf("F1", "Formula 1"), false, Regex("""\b(f1|formula 1|formula one)\b""", RegexOption.IGNORE_CASE)),
    Competition("MotoGP", listOf("MotoGP"), false),
    Competition("Six Nations", listOf("Six Nations"), true),
    Competition("Rugby", listOf("Rugby"), true),
    Competition("Cricket", listOf("Cricket"), false),
    Competition("Tennis", listOf("Tennis", "ATP", "WTA"), false),
    Competition("Golf", listOf("Golf", "PGA"), false),
    Competition("Snooker", listOf("Snooker"), false),
    Competition("Darts", listOf("Darts"), false),
    Competition("Boxing", listOf("Boxing"), false),
    Competition("UFC", listOf("UFC"), false),
    Competition("WWE", listOf("WWE"), false),
    Competition("NFL", listOf("NFL"), true),
    Competition("NBA", listOf("NBA"), true),
    Competition("NHL", listOf("NHL"), true),
    Competition("MLB", listOf("MLB"), true),
)

/** UK channels (the default for Sports, as on the iPhone: js/sports.js isUkChannel): a UK group or name tag ("UK| Sky
 *  Sports", "UK: Sky Sports 1", "(UK)", "🇬🇧"), or a UK broadcaster's name, as long as nothing marks it as another
 *  country ("CA| TSN", "🇺🇸 TV Guide (USA)", "US: ESPN") or language ("DE| Sport"). */
private val UK_TAG = Regex("""(?:^|[\s|:·\-–\[(/])(?:uk|gb|united kingdom)(?=$|[\s|:·\-–\])/])|🇬🇧""", RegexOption.IGNORE_CASE)
private val UK_BRAND = Regex("""sky sports|\btnt sports?\b|\bbt sport|\bbbc\b|\bitv ?\d?\b|\bstv\b|channel [45]\b|premier sports|eurosport|viaplay|\blfc tv\b|\bmutv\b|racing tv|\bs4c\b|\bfreesports\b|\bboxnation\b""", RegexOption.IGNORE_CASE)
private val NOT_UK = Regex("""(?:^|[\s|:·\-–\[(/])(?:us|usa|ca|can|canada|au|aus|australia|nz|new zealand|za|ie|ireland|in|india|my|sg|ph|mena|arab|latino|africa)(?=$|[\s|:·\-–\])/])|🇺🇸|🇨🇦|🇦🇺|🇳🇿|🇮🇪""", RegexOption.IGNORE_CASE)
private val NOT_UK_NAME = Regex("""^\s*(?:us|usa|ca|au|nz|ie|za|in|sg|my|hk|id|ph)\s*[:|\-]""", RegexOption.IGNORE_CASE)
// Providers file other countries' channels under "UK| Football" too (Astro, SG Hub Sports, Now Sports HK, beIN, Vidio):
// their own names give them away, whatever the group says.
private val FOREIGN_BRAND = Regex("""\b(?:astro|starhub|hub sports?|now sports|bein|supersport|sportsnet|tsn|espn|fox sports?|kayo|optus|stan sport|spark sport|sky sport(?!s)|vidio|mola|true ?sports?|ziggo|canal\+|movistar|star sports?|sony (?:ten|six|sports)|ten sports?|willow|sportv|tudn|tyc)\b|\bv\+|vision\+""", RegexOption.IGNORE_CASE)
private val OTHER_LANG_TAG = Regex("""^\s*(?:fr|de|es|it|nl|pt|pl|tr|ar|ru|gr|ro|bg|hu|cz|sk|se|no|dk|fi|be|ch|at|br|mx|la|in|pk|il|ir|al|rs|hr|si|ba|mk|cn|jp|kr|ph|my|th|vn|af|ex|ku|mt|lat|swe|nor|den|ger|ita|esp|por|pol|tur|ara|rus|bra|mex|hrv)\s*[:|\-]""", RegexOption.IGNORE_CASE)
private val ukGroups = java.util.concurrent.ConcurrentHashMap<String, Int>()   // group -> 0 not UK, 1 UK, 2 depends on the name
fun isUkChannel(c: Channel): Boolean {
    val g = ukGroups.getOrPut(c.group) { if (OTHER_LANG_TAG.containsMatchIn(c.group) || NOT_UK.containsMatchIn(c.group) || FOREIGN_BRAND.containsMatchIn(c.group)) 0 else if (UK_TAG.containsMatchIn(c.group)) 1 else 2 }
    if (g == 0 || OTHER_LANG_TAG.containsMatchIn(c.name) || NOT_UK_NAME.containsMatchIn(c.name) || FOREIGN_BRAND.containsMatchIn(c.name)) return false
    return g == 1 || UK_TAG.containsMatchIn(c.name) || UK_BRAND.containsMatchIn(c.name)
}

/** Actually live, not just on air (as the iPhone: js/sports.js isNoise + isLiveEvent): the guide tags live events
 *  ("Arsenal v Chelsea ᴸᶦᵛᵉ", "Live: …", "… (Live)"), or the description says it's live coverage. Replays, "Best of",
 *  "Gold", old finals and studio shows ("The Premier League Show ᴸᶦᵛᵉ") don't count. */
private val TAGS = Regex("[ᴬ-ᵪᶜ-ᶿ²³¹⁰-₟ⱽ]+")
private val NOT_EVENT = Regex("""\b(show|stories|years|netbusters|reload|weekly|episode|special|extra|talk|soccer saturday|goals on sunday|highlights?|hls|magazine|review|preview|reloaded|rewind|classics?|best (?:goals|of)|goals|gold(?! cup)|en 60|compacto|resumo|top 10|power rankings|fantasy|report|re-?airs?|stock exchange|countdown|draw|press conference|documentary|anthology|replay|encore|retro|in 90|recap|wrap|wins the|story of|look ?back|legends|greatest|the debate|news|daily|tonight show|podcast|uncut|central|centre|center)\b""", RegexOption.IGNORE_CASE)
private val PLACEHOLDER = Regex("""^[\s#*=_~-]{2,}|no event|^next\s*\||^\W*$|off ?air|to be announced|^tba$|^tbc$|stream (?:offline|starts)""", RegexOption.IGNORE_CASE)
private val LIVE_MARK = Regex("""ᴸᶦᵛᵉ|^\s*live\b|[(\[\-–|]\s*live\s*[)\]]?\s*$|\blive\s*[:|]""", RegexOption.IGNORE_CASE)
private val LIVE_DESC = Regex("""^(?:live|live coverage|coverage of)\b|\blive coverage\b|\bcoverage of\b.*\blive\b""", RegexOption.IGNORE_CASE)
private val NOT_LIVE_DESC = Regex("""\b(relive|highlights|replay|repeat|classic)\b""", RegexOption.IGNORE_CASE)
fun isLiveEvent(title: String, desc: String): Boolean {
    val t = title.replace(TAGS, "").trim()
    if (t.isEmpty() || PLACEHOLDER.containsMatchIn(t) || NOT_EVENT.containsMatchIn(t)) return false
    if (LIVE_MARK.containsMatchIn(title)) return true
    val d = desc.take(120)
    return LIVE_DESC.containsMatchIn(d) && !NOT_LIVE_DESC.containsMatchIn(d)
}

/** One programme on one of your channels; [comp] is the competition it was found under (null: a sports channel's own). */
data class SportItem(val c: Channel, val p: Programme, val comp: String?) {
    val key get() = "${c.url}|${p.start}"
    fun onAir(t: Long) = p.start <= t && p.end > t
}

private data class Match(val id: String, val name: String, val start: Long, val end: Long, val title: String, val desc: String)

/** The searches and their saved results. Lives as long as the app, so leaving and coming back is instant. */
object SportsHub {
    private const val TAG = "M3U"
    private const val FRESH = 30 * 60_000L
    /** query -> matches from the guide (raw, before mapping onto channels). Compose state: the page redraws as each lands. */
    private val raw: SnapshotStateMap<String, List<Match>> = mutableStateMapOf()
    private val fetchedAt = HashMap<String, Long>()
    private val running = mutableStateOf(false)
    /** adb test hook (`--es testSports dialog|remind|scroll:<row>|fresh`): opens the first upcoming card's dialog, or also sets (and later
     *  clears) a reminder on it, so the dialog and the bell can be checked without pressing OK near a live channel. */
    @Volatile var test: String? = null
    val busy get() = running.value
    /** Whether [q]'s search has come back (saved or fresh): until then a team's card says it's looking. */
    fun searched(q: String) = q in raw
    val doneCount get() = raw.size
    val total get() = COMPETITIONS.sumOf { it.queries.size }
    /** Competitions (labels, and classifySport names) the fixtures feed covers. */
    val FEED_COMPS = setOf("Premier League", "Champions League", "Europa League", "Conference League", "FA Cup", "EFL", "Carabao Cup", "Scottish Premiership",
        "LaLiga", "Serie A", "Bundesliga", "MLS", "Formula 1", "F1", "Six Nations", "Rugby", "UFC", "NFL", "NBA", "Tennis", "ATP", "WTA")
    private val scope = kotlinx.coroutines.CoroutineScope(Dispatchers.IO + kotlinx.coroutines.SupervisorJob())
    /** One read of the channel list for the page and its searches (each used to read all ~19k channels at the same time). */
    private val liveLock = kotlinx.coroutines.sync.Mutex()
    suspend fun live(): Repo.Live? = Repo.live ?: liveLock.withLock { Repo.live ?: runCatching { Repo.liveChannels() }.getOrNull() }
    /** The last pages worked out, by scope (UK / all countries): shown at once when the page is opened again. */
    @Volatile var last: Triple<Boolean, Page?, FxPage?>? = null

    // UK-only and all-countries results are saved apart (a UK search only looks through your UK channels' guide).
    private fun file(c: Context, q: String) = File(c.filesDir, "sports-" + q.lowercase().replace(Regex("[^a-z0-9]+"), "-") + (if (Repo.sportsAll) "-all" else "") + ".json")
    @Volatile private var again = false

    /** UK channels only, or every country's: the page re-filters at once; going wider searches the guide again. */
    fun showAll(c: Context, v: Boolean) {
        if (Repo.sportsAll == v) return
        Repo.sportsAll = v
        FixturesHub.scopeChanged(c)
        if (v) { fetchedAt.clear(); again = true; refresh(c) }
    }

    /** Saved results at once, then fresh searches (3 at a time) for any older than 30 minutes. */
    fun refresh(c: Context) {
        if (running.value) { again = true; return }   // (runs again when this pass ends: e.g. a team just followed)
        running.value = true; again = false
        scope.launch {
            try {
                val now = System.currentTimeMillis()
                // With real fixtures, the guide is only searched for the sports the feed doesn't cover (darts, snooker, cricket…).
                val comps = if (FixturesHub.usable) COMPETITIONS.filter { it.label !in FEED_COMPS } else COMPETITIONS
                val queries = (comps.flatMap { it.queries } + Repo.follows.map { it.q }).distinct()
                for (q in queries) if (q !in raw) runCatching {   // the saved copies first: the page has shape at once
                    val f = file(c, q); if (!f.exists()) return@runCatching
                    raw[q] = parse(JSONObject(f.readText()).optJSONArray("matches")); fetchedAt[q] = f.lastModified()
                }
                val live = live()
                if (live != null) kotlinx.coroutines.withContext(Dispatchers.Default) { indexFor(live); sportChannels(live) }   // ready before the first results land
                val urls = live?.epgUrls.orEmpty()
                if (urls.isEmpty()) { queries.forEach { if (it !in raw) raw[it] = emptyList() }; return@launch }
                // One request for every competition, searching only the guide channels you have (up to 300 matches a
                // search, so a busy competition is no longer cut short by other countries' channels). An older server
                // answers without `found`: then one search per competition, 3 at a time.
                val force = test == "fresh"; if (force) test = null
                val stale = queries.filter { force || (fetchedAt[it] ?: 0) <= now - FRESH }
                if (stale.isEmpty()) return@launch
                while (com.wmorgan97.m3uplayer.PlayerActivity.current != null) delay(5_000)   // never while watching
                if (searchBatch(c, live!!, stale)) return@launch
                val sem = Semaphore(3)
                kotlinx.coroutines.coroutineScope {
                    for (q in queries) {
                        if ((fetchedAt[q] ?: 0) > now - FRESH) continue
                        launch { sem.withPermit {
                            while (com.wmorgan97.m3uplayer.PlayerActivity.current != null) delay(5_000)   // never while watching
                            try {
                                val body = JSONObject().put("urls", JSONArray(urls)).put("find", q).toString()
                                val text = post(Repo.api("guide"), body)
                                val m = parse(JSONObject(text).optJSONArray("matches"))
                                raw[q] = m; fetchedAt[q] = System.currentTimeMillis()
                                runCatching { file(c, q).writeText(JSONObject().put("matches", JSONObject(text).optJSONArray("matches") ?: JSONArray()).toString()) }
                                Log.i(TAG, "sports: '$q' -> ${m.size} matches")
                            } catch (e: Exception) {
                                Log.w(TAG, "sports search failed for '$q': ${e.javaClass.simpleName}")
                                if (q !in raw) raw[q] = emptyList()   // shown as nothing found; tried again next time
                            }
                        } }
                    }
                }
            } finally { running.value = false; if (again) { again = false; refresh(c) } }
        }
    }

    /** One request for several searches (up to 40), through only the guide channels you have. False: an older server
     *  (no `found` in the reply) or a failure. */
    private suspend fun searchBatch(c: Context, live: Repo.Live, qs: List<String>): Boolean = try {
        val ix = kotlinx.coroutines.withContext(Dispatchers.Default) { indexFor(live) }
        val body = JSONObject().put("urls", JSONArray(live.epgUrls)).put("finds", JSONArray(qs.take(40)))
            .put("ids", JSONArray(ix.byId.keys)).put("names", JSONArray(ix.byName.keys)).toString()
        val found = JSONObject(post(Repo.api("guide"), body)).optJSONObject("found")
        if (found != null) {
            for (q in qs.take(40)) {
                val a = found.optJSONArray(q) ?: JSONArray()
                raw[q] = parse(a); fetchedAt[q] = System.currentTimeMillis()
                runCatching { file(c, q).writeText(JSONObject().put("matches", a).toString()) }
            }
            SportsPerf.mark("guide searches back (${qs.size})")
            Log.i(TAG, "sports: ${qs.size} searches in one request, ${qs.sumOf { raw[it]?.size ?: 0 }} matches")
            true
        } else false
    } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; Log.w(TAG, "sports batch failed: ${e.javaClass.simpleName}"); false }

    // ---------- teams you follow ----------

    @Volatile private var followsAt = 0L
    /** Like the web app's checkFollows: every 3 hours (or at once, [force]: just followed), when nothing is playing,
     *  search the guide for each followed team and set a reminder for each of its matches on your channels. */
    fun checkFollows(c: Context, force: Boolean = false) {
        val f = Repo.follows
        val now = System.currentTimeMillis()
        if (f.isEmpty() || Repo.testFollows != null || !force && now - followsAt < 3 * 3600_000L) return
        followsAt = now
        scope.launch {
            try {
                while (com.wmorgan97.m3uplayer.PlayerActivity.current != null) delay(60_000)   // never while watching
                val live = Repo.liveChannels()
                if (live.epgUrls.isEmpty()) return@launch
                val qs = f.map { it.q }.filter { force || (fetchedAt[it] ?: 0) <= System.currentTimeMillis() - FRESH }
                if (qs.isNotEmpty()) searchBatch(c, live, qs)
                val t = System.currentTimeMillis()
                val items = kotlinx.coroutines.withContext(Dispatchers.Default) { val ix = indexFor(live); f.flatMap { teamMatches(ix, it.q, t) } }.filter { it.p.start > t }
                val n = Repo.addReminders(items.map { it.c to it.p })
                Log.i(TAG, "follows: ${f.size} teams, ${items.size} matches, $n new reminders")
            } catch (e: Exception) { if (e is kotlinx.coroutines.CancellationException) throw e; Log.w(TAG, "follows check failed: ${e.javaClass.simpleName}") }
        }
    }

    /** A followed team's matches in the next 3 days on your channels (live first, then soonest): fixtures with the
     *  team in the title ("Arsenal v Spurs"), not the magazine shows and old matches around them. */
    private fun teamMatches(ix: Index, q: String, now: Long): List<SportItem> {
        val want = fastNorm(q); if (want.length < 2) return emptyList()
        val women = wantsWomens(q)
        val year = java.util.Calendar.getInstance().apply { timeInMillis = now }.get(java.util.Calendar.YEAR)
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.UK).format(java.util.Date(now))
        val best = HashMap<String, Pair<SportItem, Int>>()
        for (m in raw[q].orEmpty()) {
            if (m.end <= now || m.start >= now + 72 * 3600_000L) continue
            if (!fastNorm(m.title).contains(want) || !FIXTURE.containsMatchIn(" ${m.title} ") || !real(m.title, m.desc, today, year) || !isLiveEvent(m.title, m.desc)) continue
            // A real match only ("X v Y", "X vs Y", "X @ Y": never "Made in Chelsea"), and the men's side unless the follow
            // itself says women's ("Chelsea" is the men's team; "Chelsea Women" theirs). Same rule as js/sports.js.
            if (!MATCH_SPLIT.containsMatchIn(" ${m.title} ") || isWomensFixture(m.title, m.desc) != women) continue
            if (Repo.englishOnly && foreign(m.title, m.desc)) continue
            if (fastNorm(m.title) == fastNorm(m.name) || m.title.contains(" | ")) continue
            if (MAGAZINE.containsMatchIn(m.title) && !(LIVE_WORD.containsMatchIn(m.title) && FIXTURE_SPLIT.containsMatchIn(m.title))) continue
            val cands = ix.byId[m.id.lowercase()] ?: ix.byName[fastNorm(m.name)] ?: continue
            // The competition, when the title names one (so the card can drop it: "Premier League: Arsenal v Spurs").
            val comp = COMPETITIONS.firstOrNull { cp -> cp.fixtures && (listOf(cp.label) + cp.queries).any { m.title.contains(it, true) } }?.label
            val k = "${m.title.trim().lowercase()}|${m.start}"
            for (ch in cands) {
                if (fastNorm(ch.name) == fastNorm(m.title)) continue
                val sc = prefer(ch); val prev = best[k]
                if (prev == null || sc > prev.second) best[k] = SportItem(ch, Programme(m.start, m.end, m.title, m.desc), comp) to sc
            }
        }
        return dedupe(best.values.map { it.first }.sortedBy { it.p.start })
    }

    private fun parse(a: JSONArray?): List<Match> = if (a == null) emptyList() else (0 until a.length()).mapNotNull { i ->
        val o = a.optJSONObject(i) ?: return@mapNotNull null
        Match(o.optString("id"), o.optString("name"), o.optLong("start"), o.optLong("end"), o.optString("title"), o.optString("desc"))
    }

    private fun post(url: String, body: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000; c.readTimeout = 60_000
        c.requestMethod = "POST"; c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json"); c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android TV) M3UPlayer")
        c.outputStream.use { it.write(body.toByteArray()) }
        if (c.responseCode !in 200..299) { c.errorStream?.close(); throw java.io.IOException("HTTP ${c.responseCode}") }
        return c.inputStream.bufferedReader().use { it.readText() }
    }

    // ---------- onto your channels ----------

    /** [teams]: club / match channels from the playlist's team groups ("UK| CHAMPIONSHIP PPV"), the fixtures' fallback. */
    internal class Index(val sig: String, val byId: Map<String, List<Channel>>, val byName: Map<String, List<Channel>>, val teams: List<Channel> = emptyList())
    @Volatile private var index: Index? = null
    internal fun indexFor(l: Repo.Live): Index = synchronized(this) {
        // Keyed on the list's contents, not the object: a refresh that loads the same playlist again keeps the index.
        val sig = "${l.channels.size}|${l.channels.firstOrNull()?.url}|${l.channels.lastOrNull()?.url}|${Repo.englishOnly}|${Repo.sportsAll}|${Repo.hiddenGroups.size}|${Repo.hiddenChannels.size}"
        index?.takeIf { it.sig == sig }?.let { return it }
        val t0 = android.os.SystemClock.uptimeMillis()
        val hid = Repo.hiddenGroups; val hidCh = Repo.hiddenChannels
        val byId = HashMap<String, MutableList<Channel>>(); val byName = HashMap<String, MutableList<Channel>>(); val teams = ArrayList<Channel>()
        val english = Repo.englishOnly; val ukOnly = !Repo.sportsAll
        for (c in l.channels) {
            if (c.group in hid || c.url in hidCh || c.name.trimStart().startsWith("#")) continue
            if (ukOnly && !isUkChannel(c)) continue
            if (english && (!englishGroup(c.group) || OTHER_LANG.containsMatchIn(c.name) || OTHER_LANG.containsMatchIn(c.group))) continue
            c.tvgId.trim().lowercase().takeIf { it.isNotEmpty() }?.let { byId.getOrPut(it) { ArrayList() } += c }
            if (FixtureRules.isTeamChannelGroup(c.group)) teams += c
            val n = fastNorm(c.name)
            if (n.isNotEmpty()) byName.getOrPut(n) { ArrayList() } += c
            if (c.tvgName.isNotEmpty() && c.tvgName != c.name) fastNorm(c.tvgName).takeIf { it.isNotEmpty() && it != n }?.let { byName.getOrPut(it) { ArrayList() } += c }
        }
        return Index(sig, byId, byName, teams).also { index = it; Log.i(TAG, "sports index: ${l.channels.size} channels in ${android.os.SystemClock.uptimeMillis() - t0} ms"); SportsPerf.mark("index ${l.channels.size} ch ${android.os.SystemClock.uptimeMillis() - t0}ms") }
    }

    /** Which copy of a channel to use: not a backup or SD copy, an English group, HD (the web app's order). */
    private val BACKUP = Regex("""backup|\bsd\b""", RegexOption.IGNORE_CASE)
    private val HD = Regex("""\b(hd|fhd|uhd|4k)\b""", RegexOption.IGNORE_CASE)
    private val LIVE_WORD = Regex("""\blive\b|ᴸᶦᵛᵉ""", RegexOption.IGNORE_CASE)
    /** isEnglishGroup, worked out once per group (a few hundred groups, ~19k channels). */
    private val englishGroups = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    private fun englishGroup(g: String) = englishGroups.getOrPut(g) { isEnglishGroup(g) }
    /** UK broadcasters first, then other English-speaking ones (Astro, Fox AU, beIN Asia…) only when they're all there is. */
    private val UK = Regex("""\b(sky sports|tnt sports|bt sport|bbc|itv|stv|channel ?[45]|premier sports|eurosport|viaplay|amazon prime|prime video|dazn|racing tv|s4c)\b|^\s*(uk|gb)\s*[:|\-]|\b(uk|gb)\b""", RegexOption.IGNORE_CASE)
    private val FOREIGN_EN = Regex("""\b(sky sport (\d+|select|now|news|arena)|astro|fox sports?|bein|supersport|sportsnet|tsn|one soccer|kayo|optus|stan|starhub|hub sports|sportsmax|willow|ten sports|ptv|espn|fubo|golazo|nbc|cbs|abc|usa network|tubi|pluto|altitude|nfl channel)\b|^\s*(us|usa|ca|au|nz|sg|my|za|in)\s*[:|\-]""", RegexOption.IGNORE_CASE)
    internal fun prefer(c: Channel) = (if (BACKUP.containsMatchIn(c.name)) 0 else 2) + (if (englishGroup(c.group)) 2 else 0) + (if (HD.containsMatchIn(c.name)) 1 else 0) +
        (if (UK.containsMatchIn(c.name) || UK.containsMatchIn(c.group)) 6 else if (FOREIGN_EN.containsMatchIn(c.name) || FOREIGN_EN.containsMatchIn(c.group)) 0 else 3)
    /** A channel marked as another language by a country code before its name or group ("IT: SKY SPORT", "DE | Sport"). */
    private val OTHER_LANG = Regex("""^\s*(fr|de|es|it|nl|pt|pl|tr|ar|ru|gr|ro|bg|hu|cz|sk|se|no|dk|fi|be|ch|at|br|mx|la|il|ir|al|rs|hr|si|ba|mk|cn|jp|kr|ph|th|vn|af|ex|ku|mt|lat|swe|nor|den|ger|ita|esp|por|pol|tur|ara|rus|bra|mex|hrv)\s*[:|\-]""", RegexOption.IGNORE_CASE)
    // A programme in another language: its title (and the start of its description) reads as Italian, Spanish,
    // German, Polish… rather than English ("GP Malesia: Gara MotoGP", "Fútbol Bundesliga - …").
    private val FOREIGN_WORDS = Regex("""\b(gara|partita|giornata|calcio|diretta|sintesi|storie|fútbol|futbol|tenis|jornada|fecha|desde|en vivo|directo|partido|resumen|spiel|gegen|spieltag|übertragung|live-übertragung|mecz|walka|turniej|finałowy|sessie|wedstrijd|sporty|kibice|prenos|uživo|utakmica|svakog|della|dei|delle|los|las|para|und|der|die|das|mit|na|je|od|w|z)\b""", RegexOption.IGNORE_CASE)
    private val ENGLISH_WORDS = Regex("""\b(the|and|of|in|to|from|with|at|on|for|live|coverage|action|match|round|day|final)\b""", RegexOption.IGNORE_CASE)
    private fun foreign(title: String, desc: String): Boolean {
        val t = "$title ${desc.take(160)}"
        val f = FOREIGN_WORDS.findAll(t).count(); val e = ENGLISH_WORDS.findAll(t).count()
        return FOREIGN_WORDS.containsMatchIn(title) && f > e || f >= 3 && e == 0
    }

    private val FIXTURE = Regex("""\s(v|vs|@)\.?\s|\blive\b|ᴸᶦᵛᵉ""", RegexOption.IGNORE_CASE)
    private val MAGAZINE = Regex("""\b(highlights?|review|preview|news|classics?|years|greatest|reloaded|rewind|replay|magazine|best of|stories|goals|extra|weekly|round[- ]?up|podcast|countdown|uncovered|report|90 in 60|upcoming|central|fix|walk|submissions|top \d+|h/ls?|hls|catch[- ]?up|daily|tonight|talk|show|goal rush|compacto|resumen|en 60|mejores)\b""", RegexOption.IGNORE_CASE)

    private val EVENT = Regex("""\b(practice|qualifying|sprint|race|grand prix|gp|final|finals|semi[- ]?finals?|quarter[- ]?finals?|qfs?|sfs?|round \d|day \d|d\d|session|test|odi|t20|innings|fight night|main card|prelims)\b""", RegexOption.IGNORE_CASE)
    private val WOMEN = Regex("""\b(women'?s?|ladies|femenino|féminine|frauen|uwcl|wsl)\b""", RegexOption.IGNORE_CASE)
    private val NON_WORD = Regex("""[^\p{L}\p{N}]+""")
    private val FIXTURE_SPLIT = Regex("""\s(?:v|vs|@)\.?\s""")
    private val MATCH_SPLIT = Regex("""\s(?:v|vs|@)\.?\s""", RegexOption.IGNORE_CASE)
    private val YEAR = Regex("""\b(19[5-9]\d|20\d\d)\b(/\d{2,4})?""")
    private val SEASON = Regex("""\b(\d{2})/(\d{2})\b""")
    private val OTHER_SPORT = Regex("""\b(volleyball|basketball|handball|hockey|cricket|rugby|futsal|darts|snooker|pool|netball|kabaddi|e-?sports|efootball|fifa|beach soccer)\b""", RegexOption.IGNORE_CASE)
    // Event channels (pay-per-view style) list a placeholder block all day, titled with when the event really is:
    // "Chelsea v Man Utd Premier League (2026-10-31 22:30:50)". It isn't on at the block's time, so it isn't listed.
    private val STAMP = Regex("""\(\d{4}-\d{2}-\d{2}[ T]\d{2}:\d{2}""")
    private val DATE = Regex("""\b(20\d\d-[01]\d-[0-3]\d)\b""")
    /** Not a placeholder block, and not an old match shown again ("… (Soccer) - 2026-10-20" in its description). */
    private fun real(title: String, desc: String, today: String, year: Int): Boolean {
        if (STAMP.containsMatchIn(title)) return false
        // An old match: a past year in the title ("1994 FA Cup Final", "Darts Gold: Taylor v Lewis 2010"), or a past
        // season ("SPFL 24/25: Rangers v Celtic"). "2026/27" in October 2026, or in March 2027, is this season.
        for (m in YEAR.findAll(title)) { val y = m.groupValues[1].toInt(); val to = m.groupValues[2].drop(1).toIntOrNull()?.let { if (it < 100) 2000 + it else it }; if ((to ?: y) < year) return false }
        for (m in SEASON.findAll(title)) { val a = m.groupValues[1].toInt(); val b = m.groupValues[2].toInt(); if (b == a + 1 && 2000 + b < year) return false }
        val d = DATE.find(title)?.value ?: DATE.find(desc.take(300))?.value ?: return true
        return d >= today
    }
    data class Page(val live: List<SportItem>, val soon: List<SportItem>, val rows: List<Pair<String, List<SportItem>>>, val channels: List<Pair<Channel, Programme?>>,
                    val teams: List<Pair<String, SportItem?>> = emptyList())

    /** Everything the page shows, worked out off the main thread from the searches so far and the loaded guide. */
    fun page(l: Repo.Live, now: Long): Page {
        val ix = indexFor(l)
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = now }
        val year = cal.get(java.util.Calendar.YEAR)
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.UK).format(java.util.Date(now))
        val seen = HashSet<String>()           // title|start already listed under a competition higher up
        val rows = ArrayList<Pair<String, List<SportItem>>>()
        val live = ArrayList<SportItem>()
        val women = ArrayList<SportItem>()     // women's football gets its own row, after the men's competitions
        fun addRow(label: String, items: List<SportItem>) {
            live += items.filter { it.onAir(now) }
            val later = items.filter { !it.onAir(now) }
            if (later.isNotEmpty()) rows += label to later.take(40)
        }
        for (comp in COMPETITIONS) {
            if (!comp.football && women.isNotEmpty()) { addRow("Women's football", dedupe(women.sortedBy { it.p.start })); women.clear() }
            val best = HashMap<String, Pair<SportItem, Int>>()
            for (q in comp.queries) for (m in raw[q].orEmpty()) {
                if (m.end <= now || m.start >= now + 72 * 3600_000L) continue
                if (!real(m.title, m.desc, today, year) || !isLiveEvent(m.title, m.desc)) continue
                if (Repo.englishOnly && foreign(m.title, m.desc)) continue
                // A programme named after its channel is a filler block, not an event ("DAZN BOXING TV 1", "Rugby 10: …").
                if (fastNorm(m.title) == fastNorm(m.name) || m.title.contains(" | ")) continue
                if (comp.football && OTHER_SPORT.containsMatchIn(m.title)) continue   // "Volleyball Champions League", "Premier League Darts"
                if (comp.must != null && !comp.must.containsMatchIn(m.title)) continue
                if (MAGAZINE.containsMatchIn(m.title) && !(LIVE_WORD.containsMatchIn(m.title) && FIXTURE_SPLIT.containsMatchIn(m.title))) continue
                if (comp.fixtures && !FIXTURE.containsMatchIn(" ${m.title} ") && !LIVE_WORD.containsMatchIn(m.desc.take(80))) continue
                // Other sports: live coverage, or a session of an event (practice, qualifying, a final, day 2 …), not the
                // clip shows and archive fights that fill those channels.
                if (!comp.fixtures && !LIVE_WORD.containsMatchIn(m.title) && !LIVE_WORD.containsMatchIn(m.desc.take(120)) && !EVENT.containsMatchIn(m.title)) continue
                val cands = ix.byId[m.id.lowercase()] ?: ix.byName[fastNorm(m.name)] ?: continue
                val k = "${m.title.trim().lowercase()}|${m.start}"
                for (c in cands) {
                    if (fastNorm(c.name) == fastNorm(m.title)) continue
                    val sc = prefer(c); val prev = best[k]
                    if (prev == null || sc > prev.second) best[k] = SportItem(c, Programme(m.start, m.end, m.title, m.desc), comp.label) to sc
                }
            }
            val items = dedupe(best.values.map { it.first }.sortedBy { it.p.start }).filter { seen.add("${it.p.title.trim().lowercase()}|${it.p.start}") }
            if (comp.football) { val (w, men) = items.partition { WOMEN.containsMatchIn(it.p.title) }; women += w.map { it.copy(comp = "Women's football") }; addRow(comp.label, men) }
            else addRow(comp.label, items)
        }
        // Sports channels: what each is showing now (from the guide loaded on the TV), one copy per channel.
        val chans = sportChannels(l).map { it to Repo.nowNext(it, now).first }
        // Live now also takes matches on the sports channels the searches didn't catch ("Live: Arsenal v Spurs").
        val liveKeys = live.mapTo(HashSet()) { matchKey(it) }
        for ((c, p) in chans) {
            if (p == null || !FIXTURE.containsMatchIn(" ${p.title} ") || MAGAZINE.containsMatchIn(p.title) || !real(p.title, p.desc, today, year) || !isLiveEvent(p.title, p.desc)) continue
            if (Repo.englishOnly && foreign(p.title, p.desc)) continue
            if (fastNorm(p.title) == fastNorm(c.name) || p.title.contains(" | ")) continue
            val it = SportItem(c, p, null)
            if (liveKeys.add(matchKey(it))) live += it
        }
        val soon = rows.flatMap { it.second }.filter { it.p.start < now + 4 * 3600_000L }.sortedBy { it.p.start }.distinctBy { it.key }.take(20)
            .sortedByDescending { importance(it) }   // the biggest events first; soonest first among equals (stable sort)
        // Live now keeps the competitions' order (Premier League first), then the sports channels' own matches.
        // (Row keys must be unique: one guide programme can reach a channel under two guide ids.)
        // Sports channels: only ones showing a real programme (event channels list their own name all day), your
        // favourites and recent channels first.
        val mine = (Repo.favorites() + Repo.recentChannels()).map { it.optString("url") }.withIndex().associate { (i, u) -> u to i }
        // (Only what's live: a channel showing a replay or a studio show is left out.)
        val showing = chans.filter { (c, p) -> p != null && p.start <= now && p.end > now && isLiveEvent(p.title, p.desc) && real(p.title, p.desc, today, year) && fastNorm(p.title) != fastNorm(c.name) && !fastNorm(c.name).contains(fastNorm(p.title)) && !p.title.contains(" | ") }
            .distinctBy { it.first.url }.sortedBy { mine[it.first.url] ?: Int.MAX_VALUE }
        // My teams: each followed team's next match (or the one on now); a team with none in the guide still has its card.
        val teams = Repo.follows.map { f -> f.q to teamMatches(ix, f.q, now).firstOrNull() }
        if (test == "groups") { test = null   // adb test hook: which groups the page's channels come from (names only)
            (live.map { it.c } + rows.flatMap { r -> r.second.map { it.c } } + showing.map { it.first }).map { "${it.group} :: ${it.name}" }.distinct().forEach { Log.i(TAG, "sports channel: $it") } }
        // Live now: the biggest events first (Premier League above MLS and minor sports), else as found, then ending soonest.
        val liveSorted = live.withIndex().sortedWith(compareByDescending<IndexedValue<SportItem>> { importance(it.value) }.thenBy { it.index }.thenBy { it.value.p.end }).map { it.value }
        return Page(liveSorted.distinctBy { it.key }.take(30), soon, rows, showing.take(40), teams)
    }

    // ---------- real fixtures ----------

    /** One shown item: a fixture (with the channel showing it, when known), or a guide programme for a sport the feed
     *  doesn't cover. */
    data class FxItem(val f: Fixture?, val s: SportItem?, val ch: FxChannel?, val rank: Int, val start: Long) {
        val key get() = f?.id ?: ("g:" + s!!.key)
        val c get() = ch?.c ?: s?.c
        val p get() = ch?.p ?: s?.p
        val comp get() = f?.comp ?: s?.comp ?: s?.let { classifySport(it.p.title) }
        val title get() = f?.label ?: s?.let { tidyTitle(it.p.title, it.comp) } ?: ""
        /** "On Sky Sports Main Event", or null when not on your channels. */
        val where get() = ch?.where ?: s?.let { FixtureRules.shortChannel(it.c.name) }
    }
    data class FxDay(val label: String, val date: String, val items: List<FxItem>)
    /** A followed team: its next real fixture, else its next match from the guide (women's teams, other sports), else none. */
    data class FxTeamEntry(val q: String, val fx: FxItem?, val guide: SportItem?)
    data class FxPage(val live: List<FxItem>, val days: List<FxDay>, val teams: List<FxTeamEntry>, val chansKnown: Boolean, val nextLabel: String?)

    /** Asks the server which of your channels (those in the Sports index: UK ones unless you chose otherwise) show each game. */
    suspend fun refreshFixtureChannels(c: Context, l: Repo.Live, force: Boolean = false) {
        val ix = withContext(Dispatchers.Default) { indexFor(l) }
        FixturesHub.refreshChannels(c, l, ix.byId.keys, ix.byName.keys, ix.sig, force)
    }

    /** The channels a worked-out page relies on, most needed first (saved for the next cold start: SportsSnapshot). */
    internal fun snapshotChannels(l: Repo.Live, g: Page?, f: FxPage?): List<Channel> {
        val ix = indexFor(l)
        val out = ArrayList<Channel>()
        f?.let { (it.live + it.days.flatMap { d -> d.items } + it.teams.mapNotNull { t -> t.fx }).mapNotNullTo(out) { x -> x.c }; it.teams.mapNotNullTo(out) { t -> t.guide?.c } }
        g?.let { p -> p.live.mapTo(out) { it.c }; p.rows.forEach { r -> r.second.mapTo(out) { it.c } }; p.teams.mapNotNullTo(out) { it.second?.c } }
        for (ms in FixturesHub.channels.value.orEmpty().values) for (m in ms) out += ix.byId[m.id.lowercase()] ?: ix.byName[fastNorm(m.name)] ?: emptyList()
        out += sportChannels(l)
        out += ix.teams
        return out
    }

    private val FX_BIG = 70   // Premier League … LaLiga: shown even when not on your channels; smaller ones only when they are
    private fun feedSport(comp: String?) = comp != null && comp in FEED_COMPS

    /** Everything the fixtures page shows, worked out off the main thread. [g]: the guide page (other sports, sports channels). */
    fun fixturePage(l: Repo.Live, now: Long, g: Page?, known: Boolean = true): FxPage {
        val ix = indexFor(l)
        val all = FixturesHub.list.value.orEmpty()
        val raw = FixturesHub.channels.value
        val memo = HashMap<String, FxChannel?>()
        fun best(f: Fixture): FxChannel? = memo.getOrPut(f.id) {
            val out = ArrayList<FxChannel>()
            for (m in raw?.get(f.id).orEmpty()) for (c in ix.byId[m.id.lowercase()] ?: ix.byName[fastNorm(m.name)] ?: emptyList()) {
                if (out.none { it.c.url == c.url }) out += FxChannel(c, Programme(m.start, m.end, m.title, m.desc), m.live)
            }
            FixtureRules.sortChannels(out, { it.live }, { isUkChannel(it.c) }, { prefer(it.c) }).firstOrNull()
                // No main channel lists it: the club's own channel (home first), or a channel for the match itself.
                ?: if (raw != null && f.home != null && f.sport == "football") FixtureRules.teamChannelFor(f, ix.teams) { it.name }?.let { (c, side) ->
                    FxChannel(c, Programme(f.start, f.end, f.label, "${f.comp} — on the ${if (side == FixtureRules.Side.Match) "match" else "club"} channel"), false, side)
                } else null
        }
        fun item(f: Fixture) = FxItem(f, null, best(f), f.compRank, f.start)
        fun guide(s: SportItem) = FxItem(null, s, null, importance(s.p.title, s.comp), s.p.start)
        val until = now + 72 * 3600_000L
        val shown = { it: FxItem -> it.f == null || it.ch != null || it.rank >= FX_BIG }
        val fx = all.filter { it.status != "postponed" && it.start < until }
        val gLive = g?.live.orEmpty().filter { s -> (s.comp ?: classifySport(s.p.title)).let { it != null && !feedSport(it) } }.map(::guide)
        val gUp = g?.rows.orEmpty().filter { !feedSport(it.first) }.flatMap { it.second }.filter { it.p.start in (now + 1)..until }.map(::guide)
        val live = (fx.filter { it.isOn(now) }.map(::item) + gLive).filter(shown)
            .sortedWith(compareByDescending<FxItem> { it.rank }.thenByDescending { it.c != null }.thenBy { it.start }).take(16)
        val up = (fx.filter { it.upcoming(now) }.map(::item) + gUp).filter(shown)
            .sortedWith(compareBy<FxItem> { it.start }.thenByDescending { it.rank })
        val dateFmt = java.text.SimpleDateFormat("EEE d MMM", java.util.Locale.UK)
        val days = up.groupBy { dayWord(it.start, now) }.map { (label, items) -> FxDay(label, dateFmt.format(java.util.Date(items.first().start)), items.take(40)) }
        val teams = Repo.follows.map { fl ->
            val f = FixtureRules.teamFixtures(fl.q, all, now).firstOrNull()
            if (f != null) FxTeamEntry(fl.q, item(f), null) else FxTeamEntry(fl.q, null, teamMatches(ix, fl.q, now).firstOrNull())
        }
        val next = up.firstOrNull()?.let { "${it.title}, ${dayWord(it.start, now)} ${hhmm(it.start)}" }
        return FxPage(live, days, teams, raw != null && known, next)
    }

    /** One match once: the same fixture on several channels (each guide words it differently: "Fútbol Bundesliga -
     *  Elversberg vs. Mainz", "Bundesliga Soccer - Elversberg vs. Mainz"), and a match shown again later, keep the
     *  first showing on the best channel. */
    private fun dedupe(sorted: List<SportItem>): List<SportItem> {
        val out = ArrayList<SportItem>(); val keys = ArrayList<String>()
        for (it in sorted) {
            val k = matchKey(it)
            val i = keys.indices.firstOrNull { j -> keys[j] == k && (k.contains('|') || kotlin.math.abs(out[j].p.start - it.p.start) < 40 * 60_000L) }
            if (i == null) { out += it; keys += k }
            else if (kotlin.math.abs(out[i].p.start - it.p.start) < 40 * 60_000L && prefer(it.c) > prefer(out[i].c)) out[i] = it
        }
        return out.distinctBy { it.key }
    }
    /** "elversberg|mainz" for a fixture (the last word of each side), else the tidied title. */
    private fun matchKey(s: SportItem): String {
        val t = tidyTitle(s.p.title, s.comp).lowercase()
        val parts = FIXTURE_SPLIT.split(t, 2)
        if (parts.size == 2) {
            val a = parts[0].split(NON_WORD).lastOrNull { it.length > 2 && it != "fc" }
            val b = parts[1].split(NON_WORD).firstOrNull { it.length > 2 && it != "fc" }
            if (a != null && b != null) return "$a|$b"
        }
        return t
    }

    /** The sports groups' channels (English groups when that's on), one per channel name, best copy first. */
    @Volatile private var sportCache: Pair<String, List<Channel>>? = null
    fun sportChannels(l: Repo.Live): List<Channel> {
        val sig = "${l.channels.size}|${l.channels.firstOrNull()?.url}|${l.channels.lastOrNull()?.url}|${Repo.englishOnly}|${Repo.sportsAll}"
        sportCache?.takeIf { it.first == sig }?.let { return it.second }
        val hid = Repo.hiddenGroups; val hidCh = Repo.hiddenChannels
        return l.channels.asSequence().filter { it.group.contains("sport", ignoreCase = true) && it.group !in hid && it.url !in hidCh && !it.name.trimStart().startsWith("#") }
            .filter { !Repo.englishOnly || (englishGroup(it.group) && !OTHER_LANG.containsMatchIn(it.name) && !OTHER_LANG.containsMatchIn(it.group)) }
            .filter { Repo.sportsAll || isUkChannel(it) }
            .groupBy { liveBase(it.name) }.values.map { g -> g.maxBy { prefer(it) } }
            .take(300).also { sportCache = sig to it }
    }
}

// normName (Text.kt) with its patterns compiled once: the hub normalises ~40k channel names, and compiling seven
// patterns per call took seconds on the Shield. Same steps, same result.
private val N1 = Regex("[ᴬ-ᵪ²³¹⁰-₟]+")
private val N2 = Regex("""^\s*(?:uk|gb|us|usa|ca|ie|au|nz|eu)\s*[:|\-]\s*""")
private val N3 = Regex("""\(.*?\)|\[.*?\]""")
private val N4 = Regex("""\b(hd|fhd|uhd|4k|sd|hevc|h265|backup|raw|vip|low|50fps)\b""")
private val N5 = Regex("""\b(one|two|three|four|five|six|seven|eight|nine|ten)\b""")
private val N6 = Regex("""[^\p{L}\p{N}+]+""")
private val NUMS = mapOf("one" to "1", "two" to "2", "three" to "3", "four" to "4", "five" to "5", "six" to "6", "seven" to "7", "eight" to "8", "nine" to "9", "ten" to "10")
private fun fastNorm(s: String?): String = (s ?: "").lowercase().replace(N1, "").replace(N2, "").replace(N3, "").replace(N4, "").replace(N5) { NUMS[it.value] ?: it.value }.replace(N6, "")

private val LIVE_PREFIX = Regex("""^\s*((live|en directo|ao vivo)\b|\(d\))\s*[:\-–|]?\s*""", RegexOption.IGNORE_CASE)
/** A programme title without "Live:" and without the competition's name in front ("Premier League: Arsenal v Spurs"
 *  under Premier League reads "Arsenal v Spurs"); the full title is in the programme's details. */
fun tidyTitle(t: String, comp: String?): String {
    // Event channels name the programme after themselves: "AU (STAN 18) | • Chelsea v Man Utd Premier League".
    var s = t.replace(SUPERSCRIPT, " ").replace(" : ", ": ").replace(EVENT_PREFIX, "").replace(LIVE_PREFIX, "").trim()
    // The competition's names: its label and what it is searched by ("EFL" is also "Carabao Cup").
    // Only for team sports, where what's left is the match itself. "MotoGP Australia: Practice" keeps its "MotoGP":
    // "Australia: Practice" alone doesn't say what it is.
    val cp = COMPETITIONS.firstOrNull { it.label == comp }
    val names = if (cp == null || !cp.fixtures) emptyList() else (listOf(cp.label) + cp.queries).filter { it.length > 2 } + ALIASES[cp.label].orEmpty()
    // The competition in front, before a colon or a dash: "Premier League: …", "Premier League - Aston Villa v …".
    // (Repeated: some guides stack them, "Premier League: PL: Sunderland v Leeds".)
    for (round in 0..2) {
        val dash = Regex("""\s[-–|]\s""").find(s)?.range?.first ?: -1
        val i = listOf(s.indexOf(':'), dash).filter { it > 0 }.minOrNull() ?: -1
        val name = if (i in 1..60) names.firstOrNull { s.substring(0, i).contains(it, ignoreCase = true) } else null
        if (name == null || s.length - i <= 4) break
        // "Premier League: Arsenal v Spurs" -> "Arsenal v Spurs"; "Carabao Cup Live: …" -> the match too.
        val rest = s.substring(0, i).replace(name, "", ignoreCase = true).replace(LIVE_ANY, "").trim(' ', '-', '–', '|', ':', '·')
        val after = s.substring(i).trimStart(':', '-', '–', '|', ' ')
        // ("Live EFL Cup: …" under EFL leaves "Cup": a word on its own like that says nothing.)
        val keep = rest.length >= 3 && !rest.matches(Regex("""(?i)(the )?(cup|trophy|league|shield)"""))
        s = if (keep) "$rest: $after" else after
        if (keep) break
    }
    // "Chelsea v Man Utd Premier League Matchweek 9": the competition (and what follows it) goes after the match.
    for (n in names) {
        val j = s.indexOf(n, ignoreCase = true)
        if (j >= 8 && FIXTURE_WORD.containsMatchIn(s.substring(0, j))) { s = s.substring(0, j).trimEnd(' ', '-', '–', '|', ':', '·', ','); break }
    }
    s = s.replace(LIVE_PREFIX, "").replace(TAIL, "").replace(Regex("\\s{2,}"), " ").trim()
    // A session on its own ("Practice", "Qualifying", "Race 2") means nothing: the whole title then, competition first.
    if (s.isEmpty() || GENERIC.matches(s)) {
        val full = t.replace(SUPERSCRIPT, " ").replace(" : ", ": ").replace(EVENT_PREFIX, "").replace(LIVE_PREFIX, "").replace(Regex("\\s{2,}"), " ").trim()
        s = if (comp != null && !full.contains(comp, ignoreCase = true)) "$comp · $full" else full
    }
    return s
}
private val GENERIC = Regex("""(?i)(free\s+)?(practice|qualifying|race|sprint( race)?|final|finals|semi[- ]?finals?|quarter[- ]?finals?|warm[- ]?up|session|main card|prelims|fp\d|q\d)(\s*\d+)?""")
/** Short names guides use in front of a match ("PL: Sunderland v Leeds"). */
private val ALIASES = mapOf("Premier League" to listOf("EPL", "PL"), "Champions League" to listOf("UCL"), "Europa League" to listOf("UEL"))
private val LIVE_ANY = Regex("""\blive\b""", RegexOption.IGNORE_CASE)
private val SUPERSCRIPT = Regex("""[ʰ-˿ᴬ-ᵫᶦ⁰-₟]+""")
/** What trails a match's name in guide titles: "- MD3", "UEFA", the season, "(Live)". */
private val TAIL = Regex("""(\s*[-–|:]\s*(MD|Matchday|Round|Rd)\s*\d+|\s+(UEFA|Lega|Sky Bet)|\s+\d{4}/\d{2,4}|\s*\(live\))+\s*$""", RegexOption.IGNORE_CASE)
private val FIXTURE_WORD = Regex("""\s(v|vs|@)\.?\s""", RegexOption.IGNORE_CASE)
private val EVENT_PREFIX = Regex("""^[^|]{1,30}\|\s*[•·*\-]?\s*""")
private val BULLETS = Regex("""^\s*[•·*]\s*""")
/** A channel's name without the event it's showing ("AU (STAN 18) | • Chelsea v …" -> "AU (STAN 18)"). */
fun channelLabel(n: String): String = n.substringBefore(" | ").replace(BULLETS, "").trim().ifEmpty { n }

private fun dayWord(t: Long, now: Long): String {
    val a = java.util.Calendar.getInstance().apply { timeInMillis = t }; val b = java.util.Calendar.getInstance().apply { timeInMillis = now }
    val d = (a.get(java.util.Calendar.YEAR) - b.get(java.util.Calendar.YEAR)) * 400 + a.get(java.util.Calendar.DAY_OF_YEAR) - b.get(java.util.Calendar.DAY_OF_YEAR)
    return when (d) { 0 -> "Today"; 1 -> "Tomorrow"; else -> java.text.SimpleDateFormat("EEEE", java.util.Locale.UK).format(java.util.Date(t)) }
}

// Cards sized to what they hold (a header line, two lines of title, a detail line), not to a picture's 16:9: more rows
// fit on the screen and there's no empty band in the middle.
/** Where the focused row's cards settle (the first row's own place under the page title, so the page doesn't jump). */
private val SPORTS_ROW_TOP = 124.dp
private val LIVE_W = 300.dp
private val LIVE_H = 132.dp
private val NEXT_W = 236.dp
private val NEXT_H = 116.dp

// ---------- the page ----------

@Composable
fun SportsScreen(act: TvActivity) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    remember { SportsPerf.opened() }
    // Opening the page, and following a team (on any device): searches whatever is older than 30 minutes, or new.
    val followKey = Repo.follows.joinToString("|") { it.q.lowercase() }
    LaunchedEffect(followKey) { FixturesHub.readCache(ctx.applicationContext); SportsHub.refresh(ctx.applicationContext) }
    // Real fixtures: the saved copy at once, then fresh every 15 minutes (2 while a game is on), and which of your
    // channels shows each game. Never while something is playing full screen.
    LaunchedEffect(Unit) {
        val app = ctx.applicationContext
        if (SportsHub.test == "nofixtures") { SportsHub.test = null; FixturesHub.disabled = true }
        FixturesHub.readCache(app)
        while (true) {
            if (com.wmorgan97.m3uplayer.PlayerActivity.current == null) {
                FixturesHub.refresh(app)
                Repo.live?.let { l -> runCatching { SportsHub.refreshFixtureChannels(app, l) } }
            }
            delay(if (FixturesHub.usable && (FixturesHub.channels.value == null || Repo.live == null)) 3_000 else 30_000)
        }
    }
    val live by produceState(Repo.live, Repo.version) { value = SportsHub.live() ?: value; value?.let { SportsPerf.first("live ready (${it.channels.size} channels)") } }
    // Until the full channel list is in (after a cold start it is read, or downloaded again, which can take a while on
    // the Shield): the channels the page used last time, saved on the TV, so the cards show at once; else no channels
    // at all (the fixtures alone, "finding the channels").
    var quick by remember { mutableStateOf<Repo.Live?>(null) }
    LaunchedEffect(Unit) {
        if (Repo.live != null) return@LaunchedEffect
        val q = withContext(Dispatchers.IO) { FixturesHub.readCache(ctx.applicationContext); SportsPerf.trace("snapshot") { SportsSnapshot.load(ctx.applicationContext) } }
        SportsPerf.mark("snapshot ${q?.channels?.size ?: "none"}")
        quick = q ?: Repo.Live(emptyList(), emptyList(), emptyList())
    }
    // Which of your channels shows each game: asked the moment the full list is in (not on the next 3-second pass).
    LaunchedEffect(live) { val l = live ?: return@LaunchedEffect; if (com.wmorgan97.m3uplayer.PlayerActivity.current == null) runCatching { SportsHub.refreshFixtureChannels(ctx.applicationContext, l) } }
    var minute by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); minute = System.currentTimeMillis() } }
    // The sports channels' guide (what each is showing now): asked for once the channel list is there.
    LaunchedEffect(live) { live?.let { l -> Repo.requestGuide(withContext(Dispatchers.Default) { SportsHub.sportChannels(l) }) } }
    val ver = Repo.version
    // Worked out again as results land, never cancelled half-way (results arrive faster than the page is worked out):
    // each pass finishes, then the newest state is taken.
    // Opened again: the last pages at once (worked out afresh just after).
    val lastPages = remember { SportsHub.last?.takeIf { it.first == Repo.sportsAll } }
    var page by remember { mutableStateOf(lastPages?.second) }
    var fpage by remember { mutableStateOf(lastPages?.third) }
    LaunchedEffect(Unit) {
        snapshotFlow { listOf(live ?: quick, SportsHub.doneCount, Repo.guideVersion, minute, Repo.version, FixturesHub.version.intValue, FixturesHub.ok.value, Repo.sportsAll) }.conflate().collect { k ->
            val l = k[0] as Repo.Live? ?: return@collect
            val full = l === live
            withContext(Dispatchers.Default) {
                val now = System.currentTimeMillis()
                val g = runCatching { SportsPerf.trace("page") { SportsHub.page(l, now) } }.onFailure { android.util.Log.w("M3U", "sports page failed", it) }.getOrNull() ?: page
                val f = if (FixturesHub.usable) runCatching { SportsPerf.trace("fixturePage") { SportsHub.fixturePage(l, now, g, known = full || l.channels.isNotEmpty()) } }.onFailure { android.util.Log.w("M3U", "fixtures page failed", it) }.getOrNull() else null
                withContext(Dispatchers.Main) { page = g; fpage = f }
                SportsPerf.first(if (full) "worked out (full list)" else "worked out (saved channels: ${l.channels.size})")
                if (full) { SportsHub.last = Triple(Repo.sportsAll, g, f)
                    withContext(Dispatchers.IO) { runCatching { SportsSnapshot.save(ctx.applicationContext, l, SportsHub.snapshotChannels(l, g, f)) } } }
            }
        }
    }
    val reminded = remember(ver) { Repo.reminders.mapTo(HashSet()) { "${it.url}|${it.start}" } }
    var info by remember { mutableStateOf<SportItem?>(null) }
    var infoTeams by remember { mutableStateOf<List<String>?>(null) }   // a fixture's two sides (else read from the title)
    var fxInfo by remember { mutableStateOf<Fixture?>(null) }
    var fxDetail by remember { mutableStateOf<SportsHub.FxItem?>(null) }
    var teamInfo by remember { mutableStateOf<String?>(null) }
    val first = remember { FocusRequester() }
    var pageFocused by remember { mutableStateOf(false) }
    var chipsFocused by remember { mutableStateOf(false) }
    var landed by remember { mutableStateOf(false) }   // the first card has had the highlight: from then on it's yours to move
    LaunchedEffect(pageFocused, chipsFocused) { if (pageFocused && !chipsFocused) landed = true }
    val openedAt = remember { System.currentTimeMillis() }
    val p = page
    val loading = SportsHub.busy || p == null
    // The page opens on its first card, even when the first card only arrives after a second or two.
    val fp = fpage
    val firstKey = if (fp != null) fp.teams.firstOrNull()?.q ?: fp.live.firstOrNull()?.key ?: fp.days.firstOrNull()?.items?.firstOrNull()?.key
        else p?.let { it.teams.firstOrNull()?.first ?: it.live.firstOrNull()?.key ?: it.soon.firstOrNull()?.key ?: it.rows.firstOrNull()?.second?.firstOrNull()?.key }
    LaunchedEffect(firstKey) {
        if (firstKey == null) return@LaunchedEffect
        repeat(15) { if (landed || System.currentTimeMillis() - openedAt > 30_000) return@LaunchedEffect; delay(100); runCatching { first.requestFocus() } }
    }

    fun group(c: Channel): List<Channel> = Repo.live?.channels?.filter { it.group == c.group && c.group.isNotEmpty() }?.takeIf { it.contains(c) } ?: listOf(c)
    fun watch(c: Channel) { val g = group(c); act.playLive(g, g.indexOf(c).coerceAtLeast(0)) }
    fun sides(f: Fixture) = listOfNotNull(f.home, f.away).map { it.label }
    /** OK on a fixture: on now -> its channel; coming up -> the programme (Remind me, Follow); not on your channels -> its details. */
    fun open(it: SportsHub.FxItem, longPress: Boolean = false) {
        val now = System.currentTimeMillis()
        val c = it.c; val pr = it.p
        when {
            c == null || pr == null -> if (it.f?.detailed == true) fxDetail = it else fxInfo = it.f
            !longPress && (it.f?.isOn(now) == true || (it.f == null && pr.start <= now) || (it.ch?.teamCh == true && now >= pr.start)) -> watch(c)
            // F1, tennis, UFC coming up: the session / order of play / fight card first (Remind me from there); hold OK for the programme.
            !longPress && it.f?.detailed == true -> fxDetail = it
            else -> { info = SportItem(c, pr, it.comp); infoTeams = it.f?.let(::sides) }
        }
    }

    val listState = rememberPageState()
    HeldPace()
    WithScroll(rememberRowAnchor(SPORTS_ROW_TOP)) {
    LazyColumn(Modifier.fillMaxSize().onFocusChanged { pageFocused = it.hasFocus }, state = listState, contentPadding = PaddingValues(bottom = 56.dp)) {
        item(key = "title") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    PageTitle("Sports", if (fp != null) "Real fixtures · big games and your ${if (Repo.sportsAll) "" else "UK "}channels · next 3 days"
                        else if (loading && (p == null || p.rows.isEmpty())) "Looking through the TV guide…" else "Live and upcoming on your ${if (Repo.sportsAll) "" else "UK "}channels · next 3 days")
                }
                // Which countries' channels: UK by default (the user is in the UK); one press away from Up on the first row.
                Row(Modifier.padding(end = Tv.margin, top = Tv.topMargin - 8.dp).onFocusChanged { chipsFocused = it.hasFocus }, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SportsChip("UK channels", !Repo.sportsAll) { SportsHub.showAll(ctx.applicationContext, false) }
                    SportsChip("All countries", Repo.sportsAll) { SportsHub.showAll(ctx.applicationContext, true) }
                }
            }
        }
        if (fp != null) {
            SportsPerf.content("fixtures (${fp.live.size} live, ${fp.days.sumOf { it.items.size }} upcoming, channels known ${fp.chansKnown})")
            var firstUsed = false
            fun takeFirst(): Modifier = if (!firstUsed) { firstUsed = true; Modifier.focusRequester(first) } else Modifier
            val scope = if (Repo.sportsAll) ":all" else ""
            if (fp.teams.isNotEmpty()) {
                val m = takeFirst()
                item(key = "fx-teams-t") { SportsRowTitle("My teams", first = true) }
                item(key = "fx-teams") {
                    CardRow(fp.teams, { it.q.lowercase() }) { e, i ->
                        val mod = if (i == 0) m else Modifier
                        when {
                            e.fx != null -> FxTeamCard(e.q, e.fx, minute, fp.chansKnown, e.fx.c != null && "${e.fx.c!!.url}|${e.fx.p!!.start}" in reminded, mod, onLong = { teamInfo = e.q }) { open(e.fx) }
                            else -> TeamCard(e.q, e.guide, minute, e.guide != null && e.guide.key in reminded, mod, onLong = { teamInfo = e.q }) {
                                val s = e.guide
                                when { s == null -> teamInfo = e.q; s.onAir(System.currentTimeMillis()) -> watch(s.c); else -> { info = s; infoTeams = null } }
                            }
                        }
                    }
                }
            }
            if (fp.live.isNotEmpty()) {
                val m = takeFirst()
                item(key = "fx-live-t") { SportsRowTitle("Live now", count = fp.live.size, first = fp.teams.isEmpty()) }
                item(key = "fx-live$scope") { CardRow(fp.live, { it.key }) { it, i -> FxLiveCard(it, minute, fp.chansKnown, if (i == 0) m else Modifier, onLong = { open(it, longPress = true) }) { open(it) } } }
            } else {
                item(key = "fx-nolive") {
                    Text("No live games right now" + (fp.nextLabel?.let { "  ·  Next: $it" } ?: ""), fontSize = Tv.bodySmall, color = Tv.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = Tv.margin, end = Tv.margin, top = if (fp.teams.isEmpty()) 0.dp else 8.dp, bottom = 4.dp))
                }
            }
            for (d in fp.days) {
                val m = takeFirst()
                item(key = "fx-d-${d.label}") { SportsRowTitle(d.label + "  ·  " + d.date, count = d.items.size, first = fp.teams.isEmpty() && fp.live.isEmpty() && d === fp.days.first()) }
                item(key = "fx-r-${d.label}$scope") {
                    CardRow(d.items, { it.key }) { it, i -> FxNextCard(it, minute, fp.chansKnown, it.c != null && "${it.c!!.url}|${it.p!!.start}" in reminded, if (i == 0) m else Modifier, row = d.label) { open(it) } }
                }
            }
            val chans = p?.channels.orEmpty().filter { it.second != null }
            if (chans.isNotEmpty()) {
                item(key = "ch-t") { SportsRowTitle("Sports channels", count = chans.size) }
                item(key = "ch$scope") {
                    val list = chans.map { it.first }
                    CardRow(chans, { it.first.url }) { (c, now), i ->
                        val prog = now?.let { ((minute - it.start).toFloat() / (it.end - it.start)) }
                        ChannelCard(c.name, Repo.chLogo(c), now?.title ?: prettyGroup(c.group), prog, restoreKey = "sp:ch:" + c.url) { act.playLive(list, i) }
                    }
                }
            }
            if (fp.live.isEmpty() && fp.days.isEmpty()) item(key = "fx-empty") {
                Box(Modifier.fillMaxWidth().padding(top = 60.dp)) {
                    EmptyState(Icons.Default.SportsSoccer, "No games in the next three days", "Nothing from the big competitions or on your ${if (Repo.sportsAll) "" else "UK "}channels")
                }
            }
            if (!fp.chansKnown && (fp.live.isNotEmpty() || fp.days.isNotEmpty())) item(key = "fx-finding") {
                Text("Finding the channels…", fontSize = Tv.bodySmall, color = Tv.muted, modifier = Modifier.padding(start = Tv.margin, top = 16.dp))
            }
            return@LazyColumn
        }
        if (p == null) { repeat(3) { k -> item(key = "sk$k") { Column { if (k > 0) SkeletonTitle() else Spacer(Modifier.height(8.dp)); SkeletonRow(count = 4, width = if (k == 0) LIVE_W else NEXT_W, aspect = if (k == 0) LIVE_W / LIVE_H else NEXT_W / NEXT_H) } } }; return@LazyColumn }
        SportsPerf.content("guide page (${p.live.size} live, ${p.rows.size} rows)")
        var firstUsed = false
        fun takeFirst(): Modifier = if (!firstUsed) { firstUsed = true; Modifier.focusRequester(first) } else Modifier
        // Switching UK / all countries starts each row afresh from its first card (rather than keeping the old first card in place).
        val scope = if (Repo.sportsAll) ":all" else ""
        if (p.teams.isNotEmpty()) {
            val m = takeFirst()
            item(key = "teams-t") { SportsRowTitle("My teams", first = true) }
            item(key = "teams") {
                CardRow(p.teams, { it.first.lowercase() }) { (team, s), i ->
                    TeamCard(team, s, minute, s != null && s.key in reminded, if (i == 0) m else Modifier, onLong = { teamInfo = team }) {
                        when { s == null -> teamInfo = team; s.onAir(System.currentTimeMillis()) -> watch(s.c); else -> info = s }
                    }
                }
            }
        }
        if (p.live.isEmpty() && !loading) {
            // Nothing live: say so, and what's next, so a short page reads as meant (not as broken).
            val next = p.rows.flatMap { it.second }.minByOrNull { it.p.start }
            item(key = "nolive") {
                Text("No live sport right now" + (next?.let { "  ·  Next: ${tidyTitle(it.p.title, it.comp)}, ${dayWord(it.p.start, minute)} ${hhmm(it.p.start)}" } ?: ""),
                    fontSize = Tv.bodySmall, color = Tv.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = Tv.margin, end = Tv.margin, top = if (p.teams.isEmpty()) 0.dp else 8.dp, bottom = 4.dp))
            }
        }
        if (p.live.isNotEmpty()) {
            val m = takeFirst()
            item(key = "live-t") { SportsRowTitle("Live now", count = p.live.size, first = p.teams.isEmpty()) }
            item(key = "live$scope") {
                CardRow(p.live, { it.key }) { it, i -> LiveSportCard(it, minute, if (i == 0) m else Modifier, reminded = false, onLong = { info = it }) { watch(it.c) } }
            }
        }
        if (p.soon.isNotEmpty()) {
            val m = takeFirst()
            item(key = "soon-t") { SportsRowTitle("Starting soon", first = p.teams.isEmpty() && p.live.isEmpty()) }
            item(key = "soon$scope") { CardRow(p.soon, { it.key }) { it, i -> NextSportCard(it, minute, it.key in reminded, showComp = true, modifier = if (i == 0) m else Modifier, row = "soon") { info = it } } }
        }
        for ((comp, items) in p.rows) {
            val m = takeFirst()
            item(key = "t-$comp") { SportsRowTitle(comp, count = items.size, first = p.teams.isEmpty() && p.live.isEmpty() && p.soon.isEmpty() && comp == p.rows.first().first) }
            item(key = "r-$comp$scope") { CardRow(items, { it.key }) { it, i -> NextSportCard(it, minute, it.key in reminded, showComp = false, modifier = if (i == 0) m else Modifier, row = comp) { info = it } } }
        }
        if (loading) item(key = "more") { Column { SkeletonTitle(); SkeletonRow(count = 4, width = NEXT_W, aspect = NEXT_W / NEXT_H) } }
        val chans = p.channels.filter { it.second != null }
        if (chans.isNotEmpty()) {
            item(key = "ch-t") { SportsRowTitle("Sports channels", count = chans.size) }
            item(key = "ch$scope") {
                val list = chans.map { it.first }
                CardRow(chans, { it.first.url }) { (c, now), i ->
                    val prog = now?.let { ((minute - it.start).toFloat() / (it.end - it.start)) }
                    ChannelCard(c.name, Repo.chLogo(c), now?.title ?: prettyGroup(c.group), prog, restoreKey = "sp:ch:" + c.url) { act.playLive(list, i) }
                }
            }
        }
        if (!loading && p.live.isEmpty() && p.rows.isEmpty() && chans.isEmpty()) item(key = "empty") {
            Box(Modifier.fillMaxWidth().padding(top = 90.dp)) {
                EmptyState(Icons.Default.SportsSoccer, if (Repo.sportsAll) "No sport found in the TV guide" else "No sport on your UK channels",
                    if (Repo.live?.epgUrls.isNullOrEmpty()) "Your playlists have no TV guide linked" else if (Repo.sportsAll) "Nothing from the big competitions in the next 3 days" else "Choose All countries above to see other countries' channels")
            }
        }
    }
    }
    LaunchedEffect(fp) {   // adb test hooks on the fixtures page (`--es testSports dialog|fxinfo`): never presses OK on a channel
        val f = fp ?: return@LaunchedEffect
        when (SportsHub.test) {
            "dialog" -> { val it = f.days.flatMap { d -> d.items }.firstOrNull { it.c != null } ?: return@LaunchedEffect; SportsHub.test = null; open(it, longPress = true) }
            "fxinfo" -> { val it = f.days.flatMap { d -> d.items }.firstOrNull { it.c == null && it.f != null } ?: return@LaunchedEffect; SportsHub.test = null; fxInfo = it.f }
            // `--es testSports detail:tennis|f1|ufc`: the first such card's detail (F1 / tennis / UFC)
            "detail:f1", "detail:tennis", "detail:ufc" -> { val sp = SportsHub.test!!.substringAfter(':'); val it = (f.live + f.days.flatMap { d -> d.items }).firstOrNull { it.f?.sport == sp } ?: return@LaunchedEffect; SportsHub.test = null; fxDetail = it }
            "teamch" -> { val it = (f.live + f.days.flatMap { d -> d.items }).firstOrNull { it.ch?.teamCh == true } ?: return@LaunchedEffect; SportsHub.test = null; open(it, longPress = true) }
        }
    }
    LaunchedEffect(p) {
        if (fpage != null && SportsHub.test != null && !SportsHub.test!!.startsWith("scroll:")) return@LaunchedEffect
        val t = SportsHub.test?.takeIf { it != "groups" } ?: return@LaunchedEffect
        if (t.startsWith("scroll:")) { if (SportsHub.busy) return@LaunchedEffect; SportsHub.test = null; delay(500); listState.scrollToItem(t.substringAfter(':').toIntOrNull() ?: 0); return@LaunchedEffect }
        val s = p?.rows?.firstOrNull()?.second?.firstOrNull() ?: return@LaunchedEffect
        SportsHub.test = null
        if (t == "remind") {   // (cleared again even if the page is left meanwhile)
            Repo.toggleReminder(s.c, s.p)
            kotlinx.coroutines.GlobalScope.launch(Dispatchers.Main) { delay(60_000); if (Repo.hasReminder(s.c.url, s.p.start)) Repo.toggleReminder(s.c, s.p) }
        } else info = s
    }
    info?.let { s -> ProgrammeDialog(act, s.c, s.p, System.currentTimeMillis(), group(s.c), teams = infoTeams ?: teamsIn(s.p.title, s.comp)) { info = null; infoTeams = null } }
    fxInfo?.let { f -> FixtureDialog(f, fp?.chansKnown == true) { fxInfo = null } }
    fxDetail?.let { d -> FixtureDetailDialog(d, fp?.chansKnown == true, reminded, onWatch = { fxDetail = null; d.c?.let(::watch) },
        onProgramme = { fxDetail = null; val c = d.c; val pr = d.p; if (c != null && pr != null) { info = SportItem(c, pr, d.comp); infoTeams = d.f?.let(::sides) } }) { fxDetail = null } }
    teamInfo?.let { t -> TeamDialog(t) { teamInfo = null } }
}

/** UK channels / All countries: the shared [TvChip]. */
@Composable
private fun SportsChip(text: String, selected: Boolean, onClick: () -> Unit) = TvChip(text, selected, onClick)

/** "Arsenal v Spurs" -> [Arsenal, Spurs]: the two sides of a fixture, for "Follow …" in the programme's details. */
fun teamsIn(title: String, comp: String?): List<String> {
    val parts = Regex("""\s(?:v|vs|@)\.?\s""", RegexOption.IGNORE_CASE).split(tidyTitle(title, comp), 2)
    if (parts.size != 2) return emptyList()
    val home = parts[0].substringAfterLast(": ").substringAfterLast(" - ").substringAfterLast(" | ")
    val away = parts[1].split(Regex("""\s[-–|:]\s|[:(\[,]""")).first()
    return listOf(home, away).map { it.replace(Regex("""^(live|en directo)\b\s*""", RegexOption.IGNORE_CASE), "").trim(' ', '-', '–', '·', '.', '•') }
        .filter { it.length in 3..32 && it.any(Char::isLetter) && !Regex("""^\d""").containsMatchIn(it) }.distinctBy { it.lowercase() }
}

/** A followed team: its next match (or the one on now), when, and where; a quiet line when there's none. */
@Composable
private fun TeamCard(team: String, s: SportItem?, now: Long, reminded: Boolean, modifier: Modifier, onLong: () -> Unit, onClick: () -> Unit) {
    val live = s?.onAir(now) == true
    val title = s?.let { tidyTitle(it.p.title, it.comp) }
    val label = when { s == null -> "$team, no match in the next 3 days"; live -> "$team: $title, live on ${s.c.name}"; else -> "$team: $title, ${dayWord(s.p.start, now)} ${hhmm(s.p.start)} on ${s.c.name}" }
    FocusCard(modifier.width(NEXT_W).height(NEXT_H), scale = Tv.scaleWide, restoreKey = "sp:team:" + team.lowercase(), label = label, onLongClick = onLong, onClick = onClick) {
        Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Tv.surface3, Tv.surface)))) {
            Column(Modifier.fillMaxSize().padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(team, Modifier.weight(1f), fontSize = Tv.label, color = Tv.accent, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (live) LivePill() else if (reminded) Icon(Icons.Default.NotificationsActive, "Reminder set", tint = Tv.star, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.weight(1f))
                if (s == null) {
                    Text(if (SportsHub.searched(team)) "No match in the next 3 days" else "Searching the TV guide…", fontSize = Tv.bodySmall, color = Tv.muted, maxLines = 2)
                } else {
                    Text(title!!, fontSize = Tv.body, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, color = Tv.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SmallLogo(s.c, 40.dp, 24.dp)
                        Spacer(Modifier.width(8.dp))
                        Text((if (live) "until ${hhmm(s.p.end)}" else "${dayWord(s.p.start, now)} ${hhmm(s.p.start)}") + "  ·  " + channelLabel(s.c.name),
                            fontSize = Tv.micro, color = Tv.text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            if (live) ProgressLine((now - s!!.p.start).toFloat() / (s.p.end - s.p.start), Modifier.align(Alignment.BottomStart).fillMaxWidth(), 4.dp)
        }
    }
}

/** A followed team's own small dialog: stop following it. */
@Composable
private fun TeamDialog(team: String, onDismiss: () -> Unit) {
    val first = remember { FocusRequester() }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        TvDialogCard(460.dp) {
            Text(team, fontSize = Tv.title, fontWeight = Tv.wTitle, color = Tv.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("Their next live match on your channels shows under My teams. Open a match to set a reminder for it.", fontSize = Tv.bodySmall, lineHeight = Tv.bodySmallLine, color = Tv.text2, modifier = Modifier.padding(top = 6.dp))
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TvButton("Stop following", { Repo.toggleFollow(team); onDismiss() }, Modifier.focusRequester(first))
                TvButton("Close", onDismiss)
            }
        }
        LaunchedEffect(Unit) { delay(50); runCatching { first.requestFocus() } }
    }
}

/** The row heading; the first one sits closer to the page title. */
@Composable
private fun SportsRowTitle(text: String, count: Int? = null, first: Boolean = false) {
    if (first) Row(Modifier.fillMaxWidth().padding(start = Tv.margin, end = Tv.margin, bottom = 12.dp), verticalAlignment = Alignment.Bottom) {
        Text(text, fontSize = Tv.rowTitle, fontWeight = FontWeight.SemiBold, color = Tv.text)
        Spacer(Modifier.weight(1f))
        if (count != null) Text("$count", fontSize = Tv.label, color = Tv.muted, modifier = Modifier.padding(bottom = 2.dp))
    } else RowTitle(text, count = count)
}

/** The channel's logo, small, on its own dark tile (initials when it has none). */
@Composable
private fun SmallLogo(c: Channel, w: Dp, h: Dp) {
    val src = remember(c) { Repo.chLogo(c) }
    Box(Modifier.size(w, h).clip(RoundedCornerShape(5.dp)).background(Color(0x1FFFFFFF)), contentAlignment = Alignment.Center) {
        if (src.isBlank()) Text(initials(c.name), fontSize = Tv.micro, color = Tv.muted, fontWeight = FontWeight.Bold)
        else Picture(Repo.logo(src), Modifier.fillMaxSize().padding(3.dp).background(Color.Transparent), androidx.compose.ui.layout.ContentScale.Fit, fade = false) {
            Text(initials(c.name), fontSize = Tv.micro, color = Tv.muted, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.Center))
        }
    }
}

/** Live now: the channel, the match in large type, the competition and when it ends, and how far through it is. */
@Composable
private fun LiveSportCard(s: SportItem, now: Long, modifier: Modifier, reminded: Boolean, onLong: () -> Unit, onClick: () -> Unit) {
    val title = tidyTitle(s.p.title, s.comp)
    val left = ((s.p.end - now) / 60_000).coerceAtLeast(1)
    val sub = listOfNotNull(s.comp, if (left < 60) "$left min left" else "until ${hhmm(s.p.end)}").joinToString("  ·  ")
    FocusCard(modifier.width(LIVE_W).height(LIVE_H), scale = Tv.scaleWide, restoreKey = "sp:live:" + s.key, label = "$title, live on ${s.c.name}, $sub", onLongClick = onLong, onClick = onClick) {
        Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Tv.surface3, Tv.surface)))) {
            Column(Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SmallLogo(s.c, 48.dp, 28.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(channelLabel(s.c.name), Modifier.weight(1f), fontSize = Tv.label, color = Tv.text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.width(8.dp))
                    LivePill()
                }
                Spacer(Modifier.weight(1f))
                Text(title, fontSize = 18.sp, lineHeight = 23.sp, fontWeight = FontWeight.SemiBold, color = Tv.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(sub, fontSize = Tv.label, color = Tv.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
            }
            ProgressLine((now - s.p.start).toFloat() / (s.p.end - s.p.start), Modifier.align(Alignment.BottomStart).fillMaxWidth(), 4.dp)
        }
    }
}

@Composable
private fun LivePill() = TvBadge("LIVE", tone = BadgeTone.Live)

/** Coming up: when (day and time), the match, and the channel. A bell when a reminder is set. */
@Composable
private fun NextSportCard(s: SportItem, now: Long, reminded: Boolean, showComp: Boolean, modifier: Modifier, row: String, onClick: () -> Unit) {
    val title = tidyTitle(s.p.title, s.comp)
    val day = dayWord(s.p.start, now)
    val mins = (s.p.start - now) / 60_000
    val soon = mins in 0..59
    FocusCard(modifier.width(NEXT_W).height(NEXT_H), scale = Tv.scaleWide, restoreKey = "sp:$row:" + s.key, label = "$title, $day ${hhmm(s.p.start)} on ${s.c.name}", onLongClick = onClick, onClick = onClick) {
        Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Tv.surface2, Tv.surface)))) {
            Column(Modifier.fillMaxSize().padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 12.dp)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    if (!soon) Text("$day ", fontSize = Tv.label, color = Tv.text2, fontWeight = FontWeight.Medium, maxLines = 1)
                    Text(hhmm(s.p.start), fontSize = Tv.body, color = Tv.text, fontWeight = FontWeight.Bold, maxLines = 1)
                    if (soon) Text("  in ${mins.coerceAtLeast(1)} min", fontSize = Tv.label, color = Tv.star, fontWeight = FontWeight.Medium, maxLines = 1)
                    Spacer(Modifier.weight(1f))
                    if (reminded) Icon(Icons.Default.NotificationsActive, "Reminder set", tint = Tv.star, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.weight(1f))
                Text(title, fontSize = Tv.body, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, color = Tv.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SmallLogo(s.c, 40.dp, 24.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(if (showComp && s.comp != null) "${s.comp}  ·  ${channelLabel(s.c.name)}" else channelLabel(s.c.name), fontSize = Tv.micro, color = Tv.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

// ---------- women's fixtures (equivalent to js/sports.js isWomens / wantsWomens) ----------

private val WOMENS_TITLE = Regex("""\b(women'?s?|ladies|wsl|uwcl|femenino|feminine|f[ée]minine|frauen|girls)\b|\(w\)|\bw\s+v\b|\bv\s+[^|]*\bw\b(?=\s|$)|barclays wsl|women'?s super league""", RegexOption.IGNORE_CASE)
private val WOMENS_DESC = Regex("""^\s*(?:women'?s|wsl|barclays women)""", RegexOption.IGNORE_CASE)
private val COMBINING = Regex("""\p{Mn}+""")

/** A women's fixture: "Chelsea Women v Arsenal Women", "Chelsea (W) v …", "Chelsea W v Arsenal W", "Barclays WSL: …",
 *  or a description that starts "Women's …" / "WSL …". */
fun isWomensFixture(title: String, desc: String = ""): Boolean =
    WOMENS_TITLE.containsMatchIn(java.text.Normalizer.normalize(title, java.text.Normalizer.Form.NFD).replace(COMBINING, "")) || WOMENS_DESC.containsMatchIn(desc)

/** The follow asks for the women's side ("Chelsea Women", "Arsenal W", "WSL"). */
fun wantsWomens(q: String): Boolean = Regex("""\b(women|womens|ladies|wsl|w)\b""", RegexOption.IGNORE_CASE).containsMatchIn(q)

// ---------- how big an event is (equivalent to js/sports.js IMPORTANCE / classify / importance) ----------

/** Title -> competition, in the iPhone's order (first match wins). */
private val CLASSIFY: List<Pair<String, Regex>> = listOf(
    "Darts" to """\bdarts?\b|\bpdc\b""", "Snooker" to """\bsnooker\b""",
    "Cricket" to """\bcricket\b|\bt20\b|\bipl\b|indian premier league|the hundred|big bash|\bodi\b|test match|\bashes\b""",
    "Six Nations" to """six nations""", "Rugby" to """\brugby\b|gallagher premiership|\burc\b|super league|top 14|champions cup rugby""",
    "Golf" to """\bgolf\b|\bpga\b|\blpga\b|dp world tour|ryder cup|\bthe masters\b|\bliv golf\b""",
    "Tennis" to """\btennis\b|\batp\b|\bwta\b|wimbledon|roland[- ]garros|australian open|us open tennis""",
    "MotoGP" to """\bmoto ?gp\b|\bmoto[23]\b""", "F1" to """\bf1\b|formula (?:1|one)\b""", "UFC" to """\bufc\b""",
    "Boxing" to """(?<!kick[- ]?)\bboxing\b""", "WWE" to """\bwwe\b|smackdown|wrestlemania""", "NFL" to """\bnfl\b|redzone|super bowl""",
    "NBA" to """\bnba\b""", "NHL" to """\bnhl\b|stanley cup""", "MLB" to """\bmlb\b|world series""",
    "Champions League" to """(?<!\b(?:afc|caf|concacaf|women'?s)\s)champions league""", "Europa League" to """europa (?:conference )?league""",
    "FA Cup" to """\bfa cup\b|emirates fa cup""", "Carabao Cup" to """carabao cup|\befl cup\b|league cup""",
    "Scottish Premiership" to """scottish premiership|\bspfl\b""", "EFL" to """\befl\b|sky bet|^championship\b|\bleague (?:one|two)\b""",
    "Premier League" to """premier league""", "LaLiga" to """\bla ?liga\b""", "Serie A" to """\bserie a\b""", "Bundesliga" to """bundesliga""",
    "MLS" to """\bmls\b|major league soccer""", "F1" to """grand prix\b""",
).map { (c, r) -> c to Regex(r, RegexOption.IGNORE_CASE) }

private val IMPORTANCE = mapOf("Premier League" to 100, "Champions League" to 95, "F1" to 92, "FA Cup" to 88, "Europa League" to 85, "Six Nations" to 84,
    "Boxing" to 80, "UFC" to 78, "Carabao Cup" to 76, "Scottish Premiership" to 74, "EFL" to 72, "LaLiga" to 70, "Serie A" to 66, "Bundesliga" to 65,
    "NFL" to 62, "Cricket" to 60, "Rugby" to 58, "Darts" to 56, "Snooker" to 55, "Golf" to 52, "Tennis" to 52, "MotoGP" to 50, "NBA" to 40, "NHL" to 35, "MLB" to 34,
    "WWE" to 30, "MLS" to 25)
private val REAL_MATCH = Regex("""\S\s+(?:v|vs\.?|@)\s+\S""", RegexOption.IGNORE_CASE)

/** Which competition a title is from (the iPhone's classify), or null. */
fun classifySport(title: String): String? = CLASSIFY.firstOrNull { it.second.containsMatchIn(title) }?.first

/** How much a UK viewer is likely to care: the competition's weight (0 when unclassified), +4 for a real "X v Y" match. */
fun importance(title: String, comp: String? = null): Int =
    (IMPORTANCE[classifySport(title) ?: if (comp == "Formula 1") "F1" else comp] ?: 0) + (if (REAL_MATCH.containsMatchIn(title)) 4 else 0)

private fun importance(it: SportItem) = importance(it.p.title, it.comp)

// ---------- real fixtures: cards ----------

private val FX_LIVE_W = 320.dp
private val FX_LIVE_H = 156.dp
private val FX_NEXT_W = 300.dp
private val FX_NEXT_H = 140.dp

/** A club crest from the feed (small, cached by Coil like every picture); nothing when there is none. */
@Composable
private fun Crest(t: FxTeam?, size: Dp) {
    val url = FixtureRules.crestUrl(t?.crest)
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        if (url != null) coil.compose.AsyncImage(
            model = remember(url) { coil.request.ImageRequest.Builder(ctx).data(url).size(96).crossfade(false).build() },
            contentDescription = null, contentScale = androidx.compose.ui.layout.ContentScale.Fit, modifier = Modifier.fillMaxSize())
    }
}

/** "F1" / "UFC" on a small tile, where a team sport has its crests. */
@Composable
private fun CompTile(f: Fixture, size: Dp) {
    Box(Modifier.size(size).clip(Tv.shapeS).background(Tv.surface3), contentAlignment = Alignment.Center) {
        Text(when (f.sport) { "f1" -> "F1"; "ufc" -> "UFC"; "tennis" -> f.comp.take(3); else -> f.comp.take(2) }, fontSize = if (f.sport == "tennis") 10.sp else Tv.micro, fontWeight = FontWeight.Bold, color = Tv.text2, maxLines = 1)
    }
}

/** One side of a fixture: crest, name and (once it has started) the score. */
@Composable
private fun TeamLine(t: FxTeam, score: String?, crest: Dp, big: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Crest(t, crest)
        Spacer(Modifier.width(10.dp))
        Text(t.label, Modifier.weight(1f), fontSize = if (big) Tv.body else Tv.bodySmall, lineHeight = if (big) Tv.bodyLine else Tv.bodySmallLine,
            fontWeight = FontWeight.SemiBold, color = Tv.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (score != null) Text(score, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Tv.text, modifier = Modifier.padding(start = 8.dp))
    }
}

/** What's being played: the two sides with crests, or (F1, UFC) the event and its session. */
@Composable
private fun FixtureLines(it: SportsHub.FxItem, live: Boolean, crest: Dp, big: Boolean) {
    val f = it.f
    when {
        f?.home != null && f.away != null -> Column(verticalArrangement = Arrangement.spacedBy(if (big) 6.dp else 4.dp)) {
            val scored = live || f.status == "final"
            TeamLine(f.home, if (scored) f.home.score else null, crest, big)
            TeamLine(f.away, if (scored) f.away.score else null, crest, big)
        }
        f != null -> Column(verticalArrangement = Arrangement.spacedBy(if (live && (f.order.isNotEmpty() || f.matches.isNotEmpty())) 2.dp else 4.dp)) {
            val dense = live && ((f.sport == "f1" && f.order.isNotEmpty()) || (f.sport == "tennis" && f.matches.any { m -> m.status == "live" }))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CompTile(f, crest); Spacer(Modifier.width(10.dp))
                Text(f.title, fontSize = if (big && !dense) Tv.body else Tv.bodySmall, fontWeight = FontWeight.SemiBold, color = Tv.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val pad = Modifier.padding(start = crest + 10.dp)
            val on = f.matches.filter { m -> m.status == "live" }
            when {
                // F1 on: the top three, the gap to the leader.
                live && f.sport == "f1" && f.order.isNotEmpty() -> f.order.forEach { d -> AthRow(d, null, pad, pos = d.pos, gap = d.gap) }
                // Tennis on: the first match on court with its sets.
                live && on.isNotEmpty() -> { AthRow(on[0].a, on[0].b, pad); AthRow(on[0].b, on[0].a, pad)
                    if (on.size > 1) Text("+${on.size - 1} more on court", fontSize = Tv.micro, color = Tv.muted, maxLines = 1, modifier = pad) }
                else -> Text(subLine(f, live), fontSize = Tv.label, color = Tv.text2, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = pad)
            }
        }
        else -> Text(it.title, fontSize = if (big) Tv.body else Tv.bodySmall, lineHeight = if (big) Tv.bodyLine else Tv.bodySmallLine, fontWeight = FontWeight.SemiBold, color = Tv.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** The line under an F1 / tennis / UFC title: session and circuit; round and how many matches; the main event. */
private fun subLine(f: Fixture, live: Boolean): String = when (f.sport) {
    "f1" -> listOf(f.session, f.venue, f.country).filter { it.isNotBlank() }.joinToString(" · ")
    "tennis" -> { val n = f.matches.count { it.status == "scheduled" }; val m = f.focusMatch
        listOfNotNull(f.session.ifBlank { null }, if (n > 1) "$n matches" else m?.let { "${it.a.surname} v ${it.b.surname}" }).joinToString(" · ") }
    "ufc" -> if (live && !f.minute.isNullOrBlank()) "Now: ${f.minute}" else f.mainEvent?.let { "Main event · ${it.weight}".removeSuffix(" · ") } ?: "Main card ${hhmm(f.start)}"
    else -> f.session.ifBlank { f.venue }
}

/** A country flag from the feed's CDN (a 3-letter code), round and small; nothing when there is none. */
@Composable
private fun Flag(code: String, size: Dp) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Box(Modifier.size(size).clip(RoundedCornerShape(50)).background(Tv.surface3)) {
        if (code.isNotBlank()) {
            val url = "https://a.espncdn.com/combiner/i?img=/i/teamlogos/countries/500/${code.lowercase()}.png&w=40&h=40"
            coil.compose.AsyncImage(model = remember(url) { coil.request.ImageRequest.Builder(ctx).data(url).size(40).crossfade(false).build() },
                contentDescription = null, contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}

/** A driver / player / fighter on one line: (place), flag, name, seed, and a tennis score by set or a race gap. */
@Composable
private fun AthRow(a: FxAth, other: FxAth?, modifier: Modifier = Modifier, pos: Int? = null, gap: String = "", full: Boolean = false, size: androidx.compose.ui.unit.TextUnit = Tv.bodySmall) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (pos != null) Text("$pos", Modifier.width(16.dp), fontSize = Tv.label, fontWeight = FontWeight.Bold, color = Tv.muted, maxLines = 1)
        Flag(a.flag, 16.dp); Spacer(Modifier.width(8.dp))
        Text((if (full) a.name else a.surname) + (a.seed?.let { "  ($it)" } ?: ""), Modifier.weight(1f), fontSize = size,
            fontWeight = if (a.win || pos == 1) FontWeight.Bold else FontWeight.SemiBold, color = Tv.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (gap.isNotBlank()) Text(gap, fontSize = Tv.label, color = Tv.muted, maxLines = 1)
        a.sets.forEachIndexed { i, v ->
            val won = (v.toIntOrNull() ?: 0) > (other?.sets?.getOrNull(i)?.toIntOrNull() ?: 99)
            Text(v, Modifier.width(18.dp), fontSize = size, fontWeight = if (won) FontWeight.Bold else FontWeight.Normal, color = if (won) Tv.text else Tv.muted, maxLines = 1,
                textAlign = androidx.compose.ui.text.style.TextAlign.End)
        }
    }
}

@Composable
private fun LiveDot() = Box(Modifier.size(8.dp).clip(RoundedCornerShape(50)).background(Tv.live))

/** Where it's on: "On Sky Sports Main Event" / "On Blackburn Rovers channel", "Not on your channels" (once the channels are known). */
private fun whereText(it: SportsHub.FxItem, known: Boolean): String? = it.where?.let { w -> "On $w" } ?: if (known || it.f == null) "Not on your channels" else null

/** Live now: the minute and competition, both sides with crests and the score, and the channel. */
@Composable
private fun FxLiveCard(it: SportsHub.FxItem, now: Long, known: Boolean, modifier: Modifier, onLong: () -> Unit, onClick: () -> Unit) {
    val f = it.f
    val clock = when { f == null -> "until ${hhmm(it.p?.end ?: now)}"; f.status == "live" -> f.minute ?: "Live"; else -> "Starting" }
    val where = whereText(it, known)
    val off = it.c == null && known
    val score = f?.home?.score?.let { h -> f.away?.score?.let { a -> " $h–$a" } }.orEmpty()
    FocusCard(modifier.width(FX_LIVE_W).height(FX_LIVE_H), scale = Tv.scaleWide, restoreKey = "sp:fxl:" + it.key,
        label = "${it.title}$score, $clock, ${it.comp.orEmpty()}, ${where.orEmpty()}", onLongClick = onLong, onClick = onClick) {
        Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Tv.surface3, Tv.surface)))) {
            Column(Modifier.fillMaxSize().alpha(if (off) .55f else 1f).padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LiveDot(); Spacer(Modifier.width(8.dp))
                    Text(clock, fontSize = Tv.label, fontWeight = FontWeight.Bold, color = Tv.text, maxLines = 1)
                    Text("  ·  " + (it.comp ?: ""), Modifier.weight(1f), fontSize = Tv.label, color = Tv.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(10.dp))
                FixtureLines(it, live = true, crest = 26.dp, big = true)
                Spacer(Modifier.weight(1f))
                if (where != null) Text(where, fontSize = Tv.label, color = if (it.c != null) Tv.text2 else Tv.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val p = it.p
            val prog = if (f != null) (now - f.start).toFloat() / (f.end - f.start) else if (p != null) (now - p.start).toFloat() / (p.end - p.start) else null
            if (prog != null) ProgressLine(prog.coerceIn(0f, 1f), Modifier.align(Alignment.BottomStart).fillMaxWidth(), 3.dp)
        }
    }
}

/** Coming up: kick-off, both sides with crests, the competition and the channel (dimmed when it isn't on yours). */
@Composable
private fun FxNextCard(it: SportsHub.FxItem, now: Long, known: Boolean, reminded: Boolean, modifier: Modifier, row: String, onClick: () -> Unit) {
    val mins = (it.start - now) / 60_000
    val soon = mins in 0..59
    val where = whereText(it, known)
    val off = it.c == null && known
    val meta = listOfNotNull(listOfNotNull(it.comp, it.f?.round?.takeIf { r -> r.isNotBlank() }).joinToString(" · ").ifBlank { null }, where).joinToString("  ·  ")
    FocusCard(modifier.width(FX_NEXT_W).height(FX_NEXT_H), scale = Tv.scaleWide, restoreKey = "sp:fx:$row:" + it.key,
        label = "${it.title}, ${dayWord(it.start, now)} ${hhmm(it.start)}, $meta", onLongClick = onClick, onClick = onClick) {
        Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Tv.surface2, Tv.surface)))) {
            Column(Modifier.fillMaxSize().alpha(if (off) .55f else 1f).padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 12.dp)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(hhmm(it.start), fontSize = Tv.body, color = Tv.text, fontWeight = FontWeight.Bold, maxLines = 1)
                    if (soon) Text("  in ${mins.coerceAtLeast(1)} min", fontSize = Tv.label, color = Tv.star, fontWeight = FontWeight.Medium, maxLines = 1)
                    Spacer(Modifier.weight(1f))
                    if (reminded) Icon(Icons.Default.NotificationsActive, "Reminder set", tint = Tv.star, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.height(8.dp))
                FixtureLines(it, live = false, crest = 22.dp, big = false)
                Spacer(Modifier.weight(1f))
                Text(meta, fontSize = Tv.micro, color = if (it.c != null) Tv.text2 else Tv.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** A followed team's next real fixture: its crest and name, the match, and when / where (live: the minute and score). */
@Composable
private fun FxTeamCard(team: String, it: SportsHub.FxItem, now: Long, known: Boolean, reminded: Boolean, modifier: Modifier, onLong: () -> Unit, onClick: () -> Unit) {
    val f = it.f!!
    val on = f.isOn(now)
    val mine = FixtureRules.followedSide(team, f)
    // A player or fighter: their own match on the day's order of play / the card, not the whole event.
    val bout = (f.matches + f.fights).firstOrNull { it.has(team) }
    val title = bout?.let { "${it.a.surname} v ${it.b.surname} · ${f.title}" } ?: it.title
    val at = bout?.start?.takeIf { s -> s > 0 } ?: f.start
    val boutOn = if (bout != null && f.sport == "tennis") bout.status == "live" else on
    val whenText = if (boutOn) (if (f.status == "live" && f.home != null) "${f.minute ?: "Live"} · ${f.home.score ?: 0}–${f.away?.score ?: 0}" else "Live now") else "${dayWord(at, now)} ${hhmm(at)}"
    val where = whereText(it, known)
    FocusCard(modifier.width(FX_NEXT_W).height(NEXT_H), scale = Tv.scaleWide, restoreKey = "sp:team:" + team.lowercase(),
        label = "$team: ${it.title}, $whenText${where?.let { w -> ", $w" } ?: ""}", onLongClick = onLong, onClick = onClick) {
        Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Tv.surface3, Tv.surface)))) {
            Column(Modifier.fillMaxSize().padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (mine != null) { Crest(mine, 22.dp); Spacer(Modifier.width(8.dp)) } else if (f.home == null) { CompTile(f, 22.dp); Spacer(Modifier.width(8.dp)) }
                    Text(team, Modifier.weight(1f), fontSize = Tv.body, color = Tv.text, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (reminded) Icon(Icons.Default.NotificationsActive, "Reminder set", tint = Tv.star, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.weight(1f))
                Text(title, fontSize = Tv.bodySmall, color = Tv.text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (boutOn) { LiveDot(); Spacer(Modifier.width(6.dp)) }
                    Text(whenText + (where?.let { w -> "  ·  " + w.removePrefix("On ") } ?: ""), fontSize = Tv.micro, color = Tv.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (on) ProgressLine(((now - f.start).toFloat() / (f.end - f.start)).coerceIn(0f, 1f), Modifier.align(Alignment.BottomStart).fillMaxWidth(), 3.dp)
        }
    }
}

/** A fixture none of your channels shows: what, when, where it's played, and Follow for either side. */
@Composable
private fun FixtureDialog(f: Fixture, known: Boolean, onDismiss: () -> Unit) {
    val first = remember { FocusRequester() }
    val now = System.currentTimeMillis()
    val sides = if (f.home != null && f.away != null) listOf(f.home.label, f.away.label) else listOf(f.comp)
    val following = remember { mutableStateMapOf<String, Boolean>().apply { sides.forEach { put(it, Repo.isFollowing(it)) } } }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        TvDialogCard(600.dp) {
            Text(listOf(f.comp, f.round).filter { it.isNotBlank() }.joinToString(" · "), fontSize = Tv.label, color = Tv.muted, fontWeight = Tv.wStrong, maxLines = 1)
            Text(f.label + (if (f.status != "scheduled" && f.home?.score != null) "  ${f.home.score}–${f.away?.score}" else ""), fontSize = Tv.title, lineHeight = Tv.titleLine,
                fontWeight = Tv.wTitle, color = Tv.text, maxLines = 2, modifier = Modifier.padding(top = 2.dp))
            val whenText = if (f.isOn(now)) "On now" + (f.minute?.let { " · $it" } ?: "") else if (f.status == "final") "Finished" else "${dayWord(f.start, now)} ${hhmm(f.start)}"
            Text(listOf(whenText, f.venue).filter { it.isNotBlank() }.joinToString("  ·  "), fontSize = Tv.bodySmall, color = Tv.text2, modifier = Modifier.padding(top = 6.dp))
            Text(if (known) "Not on any of your ${if (Repo.sportsAll) "" else "UK "}channels." else "Still finding which channel shows it…",
                fontSize = Tv.bodySmall, color = Tv.muted, modifier = Modifier.padding(top = 12.dp))
            Spacer(Modifier.height(20.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                sides.forEachIndexed { i, t ->
                    TvButton(if (following[t] == true) "Following $t ✓" else "Follow $t", { following[t] = Repo.toggleFollow(t) },
                        if (i == 0) Modifier.focusRequester(first) else Modifier, style = if (i == 0) TvButtonStyle.Primary else TvButtonStyle.Secondary)
                }
                TvButton("Close", onDismiss)
            }
        }
        LaunchedEffect(Unit) { delay(50); runCatching { first.requestFocus() } }
    }
}


/** An F1 session, a tennis day or a UFC card in full (as the iPhone's sheet): the running order and the weekend's
 *  sessions; the order of play with scores; the fight card, main event first. Watch / Remind me when one of your
 *  channels shows it, Follow for the sport and the people in the match that matters now. */
@Composable
private fun FixtureDetailDialog(it: SportsHub.FxItem, known: Boolean, reminded: Set<String>, onWatch: () -> Unit, onProgramme: () -> Unit, onDismiss: () -> Unit) {
    val f = it.f ?: return
    val first = remember { FocusRequester() }
    val now = System.currentTimeMillis()
    val on = f.isOn(now)
    val people = when (f.sport) {
        "tennis" -> f.focusMatch?.let { m -> listOf(m.a.name, m.b.name) }.orEmpty()
        "ufc" -> f.mainEvent?.let { m -> listOf(m.a.name, m.b.name) }.orEmpty()
        else -> f.order.take(1).map { d -> d.name }
    }
    val follows = listOf(when (f.sport) { "f1" -> "F1"; "ufc" -> "UFC"; else -> "Tennis" }) + people
    val following = remember { mutableStateMapOf<String, Boolean>().apply { follows.forEach { put(it, Repo.isFollowing(it)) } } }
    val reminder = it.c != null && it.p != null && "${it.c!!.url}|${it.p!!.start}" in reminded
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
        TvDialogCard(760.dp) {
            Text(listOf(f.comp, if (f.sport == "f1") f.session else f.session).filter { s -> s.isNotBlank() }.distinct().joinToString(" · "), fontSize = Tv.label, color = Tv.muted, fontWeight = Tv.wStrong, maxLines = 1)
            Text(f.title, fontSize = Tv.title, lineHeight = Tv.titleLine, fontWeight = Tv.wTitle, color = Tv.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            val whenText = if (on) "On now" + (f.minute?.let { m -> " · $m" } ?: "") else if (f.status == "final") "Finished" else "${dayWord(f.start, now)} ${hhmm(f.start)}"
            val where = it.where?.let { w -> "On $w" } ?: if (known) "Not on your ${if (Repo.sportsAll) "" else "UK "}channels" else "Finding the channel…"
            Text(listOf(whenText, listOf(f.venue, f.country).filter { s -> s.isNotBlank() }.joinToString(", "), where).filter { s -> s.isNotBlank() }.joinToString("  ·  "),
                fontSize = Tv.bodySmall, color = Tv.text2, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
            Spacer(Modifier.height(14.dp))
            // The list scrolls under the D-pad (each match / bout takes the focus in turn); the buttons stay below it.
            Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                when (f.sport) {
                    "f1" -> {
                        if (f.order.isNotEmpty()) {
                            DetailHead(if (f.status == "final") "Result" else "Running order")
                            f.order.forEach { d -> AthRow(d, null, pos = d.pos, gap = d.gap, full = true) }
                            Spacer(Modifier.height(6.dp))
                        }
                        DetailHead("The weekend")
                        FixturesHub.list.value.orEmpty().filter { x -> x.sport == "f1" && x.title == f.title }.sortedBy { x -> x.start }.forEach { x ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(if (x.isOn(now)) "Live" else if (x.status == "final") "Done" else "${dayWord(x.start, now)} ${hhmm(x.start)}", Modifier.width(140.dp), fontSize = Tv.label,
                                    color = if (x.isOn(now)) Tv.live else Tv.muted, maxLines = 1)
                                Text(x.session, fontSize = Tv.bodySmall, fontWeight = if (x.id == f.id) FontWeight.Bold else FontWeight.Normal, color = if (x.id == f.id) Tv.text else Tv.text2, maxLines = 1)
                            }
                        }
                    }
                    "tennis" -> {
                        // On court first, then still to come, then played; as many as fit.
                        val list = f.matches.sortedBy { m -> when (m.status) { "live" -> 0; "scheduled" -> 1; else -> 2 } }
                        list.forEach { m -> BoutRow(m, now) }
                    }
                    "ufc" -> {
                        for (card in listOf("Main card", "Prelims", "Early prelims")) {
                            val l = f.fights.filter { b -> b.card == card }
                            if (l.isEmpty()) continue
                            DetailHead(card + when (card) { "Main card" -> "  ·  ${hhmm(f.start)}"; "Prelims" -> if (f.prelims > 0) "  ·  ${hhmm(f.prelims)}" else ""; else -> "" })
                            l.forEach { b -> BoutRow(b, now) }
                        }
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                var firstSet = false
                fun focusFirst(): Modifier = if (!firstSet) { firstSet = true; Modifier.focusRequester(first) } else Modifier
                if (it.c != null && it.p != null) {
                    if (on || it.p!!.start <= now) TvButton("Watch", onWatch, focusFirst(), style = TvButtonStyle.Primary)
                    else TvButton(if (reminder) "Reminder set ✓" else "Remind me", onProgramme, focusFirst(), style = TvButtonStyle.Primary)
                }
                follows.forEach { q ->
                    val label = if (q.contains(' ')) q.substringAfterLast(' ') else q
                    TvButton(if (following[q] == true) "Following $label ✓" else "Follow $label", { following[q] = Repo.toggleFollow(q) }, focusFirst())
                }
                TvButton("Close", onDismiss)
            }
        }
        LaunchedEffect(Unit) { delay(50); runCatching { first.requestFocus() } }
    }
}

@Composable
private fun DetailHead(t: String) = Text(t.uppercase(), fontSize = Tv.micro, fontWeight = FontWeight.Bold, color = Tv.muted, letterSpacing = 1.sp, modifier = Modifier.padding(top = 2.dp))

/** A tennis match or UFC bout in the detail: when / where on the left, the two names (and sets) on the right; the main event marked. */
@Composable
private fun BoutRow(m: FxBout, now: Long) {
    var focused by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().clip(Tv.shapeS).background(if (focused) Tv.surface3 else if (m.main) Tv.accent.copy(alpha = .10f) else Color.Transparent)
        .onFocusChanged { focused = it.isFocused }.focusable().padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.width(150.dp)) {
            val live = m.status == "live"
            Text(when { m.main -> "Main event"; live -> m.detail.ifBlank { "Live" }; m.status == "final" -> m.detail.ifBlank { "Done" }; m.start > 0 -> hhmm(m.start); else -> m.weight },
                fontSize = Tv.label, fontWeight = FontWeight.SemiBold, color = if (live) Tv.live else if (m.main) Tv.accent else Tv.text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val sub = listOf(m.round, m.court).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { if (m.main) m.weight else "" }
            if (sub.isNotBlank()) Text(sub, fontSize = Tv.micro, color = Tv.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            AthRow(m.a, m.b, full = true, size = Tv.label)
            AthRow(m.b, m.a, full = true, size = Tv.label)
        }
    }
}
