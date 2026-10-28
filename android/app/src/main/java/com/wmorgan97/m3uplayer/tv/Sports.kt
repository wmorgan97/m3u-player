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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.focusable
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
    private val scope = kotlinx.coroutines.CoroutineScope(Dispatchers.IO + kotlinx.coroutines.SupervisorJob())

    // UK-only and all-countries results are saved apart (a UK search only looks through your UK channels' guide).
    private fun file(c: Context, q: String) = File(c.filesDir, "sports-" + q.lowercase().replace(Regex("[^a-z0-9]+"), "-") + (if (Repo.sportsAll) "-all" else "") + ".json")
    @Volatile private var again = false

    /** UK channels only, or every country's: the page re-filters at once; going wider searches the guide again. */
    fun showAll(c: Context, v: Boolean) {
        if (Repo.sportsAll == v) return
        Repo.sportsAll = v
        if (v) { fetchedAt.clear(); again = true; refresh(c) }
    }

    /** Saved results at once, then fresh searches (3 at a time) for any older than 30 minutes. */
    fun refresh(c: Context) {
        if (running.value) { again = true; return }   // (runs again when this pass ends: e.g. a team just followed)
        running.value = true; again = false
        scope.launch {
            try {
                val now = System.currentTimeMillis()
                val queries = (COMPETITIONS.flatMap { it.queries } + Repo.follows.map { it.q }).distinct()
                for (q in queries) if (q !in raw) runCatching {   // the saved copies first: the page has shape at once
                    val f = file(c, q); if (!f.exists()) return@runCatching
                    raw[q] = parse(JSONObject(f.readText()).optJSONArray("matches")); fetchedAt[q] = f.lastModified()
                }
                val live = runCatching { Repo.liveChannels() }.getOrNull()
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
        val year = java.util.Calendar.getInstance().apply { timeInMillis = now }.get(java.util.Calendar.YEAR)
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.UK).format(java.util.Date(now))
        val best = HashMap<String, Pair<SportItem, Int>>()
        for (m in raw[q].orEmpty()) {
            if (m.end <= now || m.start >= now + 72 * 3600_000L) continue
            if (!fastNorm(m.title).contains(want) || !FIXTURE.containsMatchIn(" ${m.title} ") || !real(m.title, m.desc, today, year) || !isLiveEvent(m.title, m.desc)) continue
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

    private class Index(val sig: String, val byId: Map<String, List<Channel>>, val byName: Map<String, List<Channel>>)
    @Volatile private var index: Index? = null
    private fun indexFor(l: Repo.Live): Index = synchronized(this) {
        // Keyed on the list's contents, not the object: a refresh that loads the same playlist again keeps the index.
        val sig = "${l.channels.size}|${l.channels.firstOrNull()?.url}|${l.channels.lastOrNull()?.url}|${Repo.englishOnly}|${Repo.sportsAll}|${Repo.hiddenGroups.size}|${Repo.hiddenChannels.size}"
        index?.takeIf { it.sig == sig }?.let { return it }
        val t0 = android.os.SystemClock.uptimeMillis()
        val hid = Repo.hiddenGroups; val hidCh = Repo.hiddenChannels
        val byId = HashMap<String, MutableList<Channel>>(); val byName = HashMap<String, MutableList<Channel>>()
        val english = Repo.englishOnly; val ukOnly = !Repo.sportsAll
        for (c in l.channels) {
            if (c.group in hid || c.url in hidCh || c.name.trimStart().startsWith("#")) continue
            if (ukOnly && !isUkChannel(c)) continue
            if (english && (!englishGroup(c.group) || OTHER_LANG.containsMatchIn(c.name) || OTHER_LANG.containsMatchIn(c.group))) continue
            c.tvgId.trim().lowercase().takeIf { it.isNotEmpty() }?.let { byId.getOrPut(it) { ArrayList() } += c }
            val n = fastNorm(c.name)
            if (n.isNotEmpty()) byName.getOrPut(n) { ArrayList() } += c
            if (c.tvgName.isNotEmpty() && c.tvgName != c.name) fastNorm(c.tvgName).takeIf { it.isNotEmpty() && it != n }?.let { byName.getOrPut(it) { ArrayList() } += c }
        }
        return Index(sig, byId, byName).also { index = it; Log.i(TAG, "sports index: ${l.channels.size} channels in ${android.os.SystemClock.uptimeMillis() - t0} ms") }
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
    private fun prefer(c: Channel) = (if (BACKUP.containsMatchIn(c.name)) 0 else 2) + (if (englishGroup(c.group)) 2 else 0) + (if (HD.containsMatchIn(c.name)) 1 else 0) +
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
        return Page(live.distinctBy { it.key }.take(30), soon, rows, showing.take(40), teams)
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
private val LIVE_W = 300.dp
private val LIVE_H = 132.dp
private val NEXT_W = 236.dp
private val NEXT_H = 116.dp

// ---------- the page ----------

@Composable
fun SportsScreen(act: TvActivity) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    // Opening the page, and following a team (on any device): searches whatever is older than 30 minutes, or new.
    val followKey = Repo.follows.joinToString("|") { it.q.lowercase() }
    LaunchedEffect(followKey) { SportsHub.refresh(ctx.applicationContext) }
    val live by produceState(Repo.live, Repo.version) { value = runCatching { Repo.liveChannels() }.getOrNull() ?: value }
    var minute by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); minute = System.currentTimeMillis() } }
    // The sports channels' guide (what each is showing now): asked for once the channel list is there.
    LaunchedEffect(live) { live?.let { l -> Repo.requestGuide(withContext(Dispatchers.Default) { SportsHub.sportChannels(l) }) } }
    val ver = Repo.version
    // Worked out again as results land, never cancelled half-way (results arrive faster than the page is worked out):
    // each pass finishes, then the newest state is taken.
    var page by remember { mutableStateOf<SportsHub.Page?>(null) }
    LaunchedEffect(Unit) {
        snapshotFlow { listOf(live, SportsHub.doneCount, Repo.guideVersion, minute, Repo.version) }.conflate().collect { k ->
            val l = k[0] as Repo.Live? ?: return@collect
            page = withContext(Dispatchers.Default) { runCatching { SportsHub.page(l, System.currentTimeMillis()) }.onFailure { android.util.Log.w("M3U", "sports page failed", it) }.getOrNull() } ?: page
        }
    }
    val reminded = remember(ver) { Repo.reminders.mapTo(HashSet()) { "${it.url}|${it.start}" } }
    var info by remember { mutableStateOf<SportItem?>(null) }
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
    val firstKey = p?.let { it.teams.firstOrNull()?.first ?: it.live.firstOrNull()?.key ?: it.soon.firstOrNull()?.key ?: it.rows.firstOrNull()?.second?.firstOrNull()?.key }
    LaunchedEffect(firstKey) {
        if (firstKey == null) return@LaunchedEffect
        repeat(15) { if (landed || System.currentTimeMillis() - openedAt > 30_000) return@LaunchedEffect; delay(100); runCatching { first.requestFocus() } }
    }

    fun group(c: Channel): List<Channel> = Repo.live?.channels?.filter { it.group == c.group && c.group.isNotEmpty() }?.takeIf { it.contains(c) } ?: listOf(c)
    fun watch(c: Channel) { val g = group(c); act.playLive(g, g.indexOf(c).coerceAtLeast(0)) }

    val listState = rememberPageState()
    LazyColumn(Modifier.fillMaxSize().onFocusChanged { pageFocused = it.hasFocus }, state = listState, contentPadding = PaddingValues(bottom = 56.dp)) {
        item(key = "title") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    PageTitle("Sports", if (loading && (p == null || p.rows.isEmpty())) "Looking through the TV guide…" else "Live and upcoming on your ${if (Repo.sportsAll) "" else "UK "}channels · next 3 days")
                }
                // Which countries' channels: UK by default (the user is in the UK); one press away from Up on the first row.
                Row(Modifier.padding(end = Tv.margin, top = Tv.topMargin - 8.dp).onFocusChanged { chipsFocused = it.hasFocus }, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SportsChip("UK channels", !Repo.sportsAll) { SportsHub.showAll(ctx.applicationContext, false) }
                    SportsChip("All countries", Repo.sportsAll) { SportsHub.showAll(ctx.applicationContext, true) }
                }
            }
        }
        if (p == null) { repeat(3) { k -> item(key = "sk$k") { Column { if (k > 0) SkeletonTitle() else Spacer(Modifier.height(8.dp)); SkeletonRow(count = 4, width = if (k == 0) LIVE_W else NEXT_W, aspect = if (k == 0) LIVE_W / LIVE_H else NEXT_W / NEXT_H) } } }; return@LazyColumn }
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
                    ChannelCard(c.name, c.logo, now?.title ?: prettyGroup(c.group), prog, restoreKey = "sp:ch:" + c.url) { act.playLive(list, i) }
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
    LaunchedEffect(p) {
        val t = SportsHub.test?.takeIf { it != "groups" } ?: return@LaunchedEffect
        if (t.startsWith("scroll:")) { if (SportsHub.busy) return@LaunchedEffect; SportsHub.test = null; delay(500); listState.scrollToItem(t.substringAfter(':').toIntOrNull() ?: 0); return@LaunchedEffect }
        val s = p?.rows?.firstOrNull()?.second?.firstOrNull() ?: return@LaunchedEffect
        SportsHub.test = null
        if (t == "remind") {   // (cleared again even if the page is left meanwhile)
            Repo.toggleReminder(s.c, s.p)
            kotlinx.coroutines.GlobalScope.launch(Dispatchers.Main) { delay(60_000); if (Repo.hasReminder(s.c.url, s.p.start)) Repo.toggleReminder(s.c, s.p) }
        } else info = s
    }
    info?.let { s -> ProgrammeDialog(act, s.c, s.p, System.currentTimeMillis(), group(s.c), teams = teamsIn(s.p.title, s.comp)) { info = null } }
    teamInfo?.let { t -> TeamDialog(t) { teamInfo = null } }
}

/** A quiet pill (the Details screen's season pill): grey, the chosen one lighter, the highlight solid white. */
@Composable
private fun SportsChip(text: String, selected: Boolean, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(50)
    Box(Modifier.onFocusChanged { f = it.isFocused }
        .onKeyEvent { e -> if (e.type == KeyEventType.KeyUp && (e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter)) { onClick(); true } else false }
        .clearAndSetSemantics { contentDescription = text + if (selected) ", selected" else "" }
        .focusable()
        .background(when { f -> Tv.text; selected -> Color(0x40FFFFFF); else -> Color(0x1AFFFFFF) }, shape)
        .padding(horizontal = 16.dp, vertical = 6.dp)) {
        Text(text, fontSize = Tv.bodySmall, lineHeight = 18.sp, fontWeight = if (f || selected) FontWeight.SemiBold else FontWeight.Medium,
            color = when { f -> Tv.bg; selected -> Tv.text; else -> Tv.text2 })
    }
}

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
    FocusCard(modifier.width(NEXT_W).height(NEXT_H), restoreKey = "sp:team:" + team.lowercase(), label = label, onLongClick = onLong, onClick = onClick) {
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
        Column(Modifier.freshOkOnly().width(460.dp).background(Tv.surface, Tv.bigShape).border(1.dp, Tv.line, Tv.bigShape).padding(24.dp)) {
            Text(team, fontSize = Tv.title, fontWeight = FontWeight.Bold, color = Tv.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("Every match on your channels gets a reminder, here and on your other devices.", fontSize = Tv.bodySmall, lineHeight = Tv.bodySmallLine, color = Tv.text2, modifier = Modifier.padding(top = 6.dp))
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                androidx.tv.material3.OutlinedButton(onClick = { Repo.toggleFollow(team); onDismiss() }, modifier = Modifier.focusRequester(first)) { Text("Stop following", fontSize = Tv.bodySmall, fontWeight = FontWeight.SemiBold) }
                androidx.tv.material3.OutlinedButton(onClick = onDismiss) { Text("Close", fontSize = Tv.bodySmall, fontWeight = FontWeight.SemiBold) }
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
    Box(Modifier.size(w, h).clip(RoundedCornerShape(5.dp)).background(Color(0x1FFFFFFF)), contentAlignment = Alignment.Center) {
        if (c.logo.isBlank()) Text(initials(c.name), fontSize = Tv.micro, color = Tv.muted, fontWeight = FontWeight.Bold)
        else Picture(Repo.logo(c.logo), Modifier.fillMaxSize().padding(3.dp).background(Color.Transparent), androidx.compose.ui.layout.ContentScale.Fit, fade = false) {
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
    FocusCard(modifier.width(LIVE_W).height(LIVE_H), scale = 1.05f, restoreKey = "sp:live:" + s.key, label = "$title, live on ${s.c.name}, $sub", onLongClick = onLong, onClick = onClick) {
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
private fun LivePill() {
    Row(Modifier.background(Color(0x40FF4550), RoundedCornerShape(5.dp)).padding(horizontal = 7.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).clip(RoundedCornerShape(3.dp)).background(Tv.live))
        Spacer(Modifier.width(5.dp))
        Text("LIVE", fontSize = Tv.micro, fontWeight = FontWeight.Bold, color = Tv.text)
    }
}

/** Coming up: when (day and time), the match, and the channel. A bell when a reminder is set. */
@Composable
private fun NextSportCard(s: SportItem, now: Long, reminded: Boolean, showComp: Boolean, modifier: Modifier, row: String, onClick: () -> Unit) {
    val title = tidyTitle(s.p.title, s.comp)
    val day = dayWord(s.p.start, now)
    val mins = (s.p.start - now) / 60_000
    val soon = mins in 0..59
    FocusCard(modifier.width(NEXT_W).height(NEXT_H), restoreKey = "sp:$row:" + s.key, label = "$title, $day ${hhmm(s.p.start)} on ${s.c.name}", onLongClick = onClick, onClick = onClick) {
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
