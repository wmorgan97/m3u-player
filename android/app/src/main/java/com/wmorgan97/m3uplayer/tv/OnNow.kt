package com.wmorgan97.m3uplayer.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import kotlin.math.ln
import kotlin.math.pow

// Home's live rows: what's on now on the channels you watch, and what's starting soon that suits you (films and your
// series; sport has its own page and stays off Home). Everything comes from guide data the TV already keeps; Home only asks for
// the guide of the few channels it shows. One card size for all three rows (236 x 118), so they read as one family
// with the Sports page's cards: the channel on top, the programme in large type, when underneath.

/** The minute, ticking: cards that show progress or "in 5 min" read it, so only they redraw each minute. */
object HomeClock { var now by mutableLongStateOf(System.currentTimeMillis()) }

val NOW_W = 236.dp
/** Card height, sized to what the row holds: the header, the title on one line or two, the detail line. A row is one
 *  height (the tallest card's), so a row of one-line titles has no empty band in the middle; two lines = the Sports
 *  page's 116. */
fun nowHeight(twoLines: Boolean) = if (twoLines) 116.dp else 97.dp
private val TITLE_STYLE = androidx.compose.ui.text.TextStyle(fontSize = 15.sp, lineHeight = 19.sp, fontWeight = FontWeight.SemiBold)
/** Whether any of these titles takes two lines on a card (measured with the card's own type and width). */
@Composable
fun anyTwoLines(titles: List<String>): Boolean {
    val m = androidx.compose.ui.text.rememberTextMeasurer(cacheSize = 0)
    val w = with(androidx.compose.ui.platform.LocalDensity.current) { (NOW_W - 28.dp).roundToPx() }
    return remember(titles, w) { titles.any { m.measure(it, TITLE_STYLE, maxLines = 2, constraints = androidx.compose.ui.unit.Constraints(maxWidth = w)).lineCount > 1 } }
}

// ---------- your channels ----------

private fun isLiveEntry(o: org.json.JSONObject): Boolean {
    val u = o.optString("url"); val k = o.optString("kind")
    return u.isNotEmpty() && (k.isEmpty() || k == "live") && !u.contains("/movie/") && !u.contains("/series/")
}

/** Your favourite and recent channels, the ones you watch most and most lately first. A favourite counts for a little
 *  over a channel watched once yesterday; a channel watched every evening outranks both. */
fun yourChannels(now: Long = System.currentTimeMillis()): List<Channel> {
    val favs = Repo.favorites().filter(::isLiveEntry)
    val rec = Repo.recentChannels().filter(::isLiveEntry)
    val plays = Repo.channelPlays()
    val hidden = Repo.hiddenChannels
    val score = HashMap<String, Double>(); val entry = LinkedHashMap<String, org.json.JSONObject>()
    fun recency(at: Long) = if (at <= 0) 0.0 else 4.0 * 0.5.pow(((now - at).coerceAtLeast(0) / 3_600_000.0) / 24.0)   // halves every day
    favs.forEachIndexed { i, o -> val u = o.optString("url"); entry.putIfAbsent(u, o); score[u] = 2.5 - i * 0.001 }
    for (o in rec) { val u = o.optString("url"); entry.putIfAbsent(u, o); score[u] = (score[u] ?: 0.0) + recency(o.optLong("at")) }
    for ((u, p) in plays) if (u in entry) score[u] = (score[u] ?: 0.0) + 1.5 * ln(1.0 + p.first) / ln(2.0) + 0.5 * recency(p.second)
    // Sports channels stay on the Sports page, not Home (the user's choice).
    val sporty = Regex("""\bsports?\b|eurosport|\bbein\b|\bdazn\b|\bespn|premier sports|\bviaplay\b|\bbt sport|sky sports|tnt sports|laliga|\bnfl\b|\bnba\b|\bnhl\b|\bmlb\b|\bufc\b|\bwwe\b|\bracing\b|\bgolf\b|\bf1\b|\bppv\b|\b(lfc|mutv|chelsea|club|barca|city) ?tv\b""", RegexOption.IGNORE_CASE)
    return entry.keys.filter { it !in hidden && !sporty.containsMatchIn(entry.getValue(it).optString("name") + " " + entry.getValue(it).optString("group")) }.sortedByDescending { score[it] ?: 0.0 }.map { liveFromSync(entry.getValue(it)) }.distinctBy { it.url }
}

// ---------- starting soon ----------

/** A programme starting in the next two hours, and why it's here ("Film · 2023", "Your series", "Reminder set"). */
data class SoonItem(val c: Channel, val p: Programme, val why: String) { val key get() = "${c.url}|${p.start}" }

private val FILM_GROUP = Regex("""\b(movies?|films?|cinema|kino)\b""", RegexOption.IGNORE_CASE)
private val FILM_NAME = Regex("""\b(cinema|film ?4|films?|movies?|tcm|great!? movies|sony movies|talking pictures)\b""", RegexOption.IGNORE_CASE)
private val NOT_A_FILM = Regex("""\b(preview|review|news|trailers?|showcase|coming soon|behind the|making of|the best of|top \d+|teleshopping|close)\b""", RegexOption.IGNORE_CASE)
private val UK_CH = Regex("""\b(uk|gb|sky|bbc|itv|channel ?[45]|film ?4)\b""", RegexOption.IGNORE_CASE)
private val YEAR_TAIL = Regex("""\s*\((19|20)\d\d\)\s*$""")

/** A handful of film channels (Sky Cinema, Film4…): English and UK ones first, one copy of each, not hidden or locked. */
fun filmChannels(l: Repo.Live, mine: List<Channel>): List<Channel> {
    val hid = Repo.hiddenGroups; val hidCh = Repo.hiddenChannels; val en = Repo.englishOnly
    val mineUrls = mine.mapTo(HashSet()) { it.url }
    return l.channels.asSequence()
        .filter { c -> c.url !in hidCh && c.group !in hid && !Repo.isChannelLocked(c) && !c.name.trimStart().startsWith("#") && (FILM_GROUP.containsMatchIn(c.group) || FILM_NAME.containsMatchIn(c.name)) }
        .filter { c -> !en || isEnglishGroup(c.group) }
        .take(400).toList()
        .groupBy { liveBase(it.name) }.values.map { g -> g.maxBy { qualityScore(it.name, it.group) } }
        .sortedBy { c -> (if (c.url in mineUrls) 0 else 2) + (if (UK_CH.containsMatchIn(c.name) || UK_CH.containsMatchIn(c.group)) 0 else 1) }
        .take(12)
}

private val SPORT_CH = Regex("""\bsports?\b|eurosport|\bbein\b|\bdazn\b|\bespn|premier sports|\bviaplay\b|\bnfl\b|\bnba\b|\bufc\b|\bwwe\b|\bppv\b|racing|\b(lfc|mutv|chelsea|club|barca|real madrid|city) ?tv\b""", RegexOption.IGNORE_CASE)
private val SPORT_TITLE = Regex("""\S\s+(v|vs\.?|@)\s+\S|premier league|champions league|formula 1|\bf1\b|grand prix|motogp|snooker|darts|boxing|\bufc\b|golf|tennis|cricket|rugby""", RegexOption.IGNORE_CASE)
/** Sport (a sports or club channel, a match or event title), or a channel's own name as its programme (a filler
 *  block, not something to plan for). Home leaves these out; sport has its own page. */
private fun isSport(c: Channel, p: Programme) = fold(p.title) == fold(c.name) || SPORT_CH.containsMatchIn(c.group) || SPORT_CH.containsMatchIn(c.name) || SPORT_TITLE.containsMatchIn(p.title)

private fun fold(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

/** What starts in the next two hours on your channels and the film channels that suits you, soonest first. */
fun startingSoon(mine: List<Channel>, films: List<Channel>, now: Long): List<SoonItem> {
    val until = now + 2 * 3600_000L
    val out = ArrayList<SoonItem>()
    fun window(p: Programme) = p.start > now + 60_000 && p.start <= until
    val series = Repo.favorites().filter { it.optString("kind") == "series" }.map { fold(displayTitle(it.optString("name"))) }.filter { it.length >= 4 }.distinct()
    val filmUrls = films.mapTo(HashSet()) { it.url }
    for (c in (mine + films).distinctBy { it.url }) {
        for (p in Repo.guideFor(c)) {
            if (p.start > until) break
            if (!window(p)) continue
            val t = fold(p.title)
            if (c.url !in filmUrls && isSport(c, p)) continue   // ("Godzilla vs Kong" on a film channel is a film)
            val why = if (series.any { t == it || t.startsWith("$it ") }) "Your series"
                else if (c.url in filmUrls && p.end - p.start >= 70 * 60_000L && !NOT_A_FILM.containsMatchIn(p.title) && fold(p.title) != fold(c.name))
                    YEAR_TAIL.find(p.title)?.value?.trim()?.trim('(', ')')?.let { "Film · $it" } ?: "Film"
                else null
            if (why != null) out += SoonItem(c, p, why)
        }
    }
    // Reminders you've set (here or on the phone), in the window, after the rest: a film you're reminded of
    // still says it's a film (the bell shows the reminder).
    for (r in Repo.reminders) {
        if (r.start <= now + 60_000 || r.start > until) continue
        val c = Repo.channelByUrl(r.url) ?: continue
        val p = Repo.guideFor(c).firstOrNull { it.start == r.start } ?: Programme(r.start, r.start + 3600_000L, r.title, "")
        if (isSport(c, p)) continue   // match reminders (a followed team's) belong to the Sports page
        out += SoonItem(c, p, "Reminder set")
    }
    // One of each: the first reason found for a programme, and a film on two channels (+1, HD) shows once.
    return out.sortedBy { it.p.start }.distinctBy { it.key }.distinctBy { fold(it.p.title.replace(YEAR_TAIL, "")) }.take(12)
}

private val FIXTURE_PART = Regex("""\S\s+(v|vs\.?|@)\s+\S""", RegexOption.IGNORE_CASE)
/** The match on its own when a title has one ("LALIGA EA SPORTS 2026/2027 MD10 : Celta vs Real Betis" -> "Celta v
 *  Real Betis"), else the title tidied of "Live:" and provider prefixes. The full title is in the programme's details. */
fun shortTitle(t: String, comp: String? = null): String {
    val s = tidyTitle(t, comp)
    val vs = s.split(Regex("""\s+[-–|]\s+|\s*:\s+""")).map { it.trim() }.lastOrNull { FIXTURE_PART.containsMatchIn(it) }
    return vs?.replace(Regex("""\s+vs\.?\s+""", RegexOption.IGNORE_CASE), " v ") ?: s
}

// ---------- cards ----------

@Composable
private fun TinyLogo(c: Channel) {
    Box(Modifier.size(40.dp, 24.dp).clip(RoundedCornerShape(5.dp)).background(Color(0x1FFFFFFF)), contentAlignment = Alignment.Center) {
        if (c.logo.isBlank()) Text(initials(c.name), fontSize = Tv.micro, color = Tv.muted, fontWeight = FontWeight.Bold)
        else Picture(Repo.logo(c.logo), Modifier.fillMaxSize().padding(3.dp).background(Color.Transparent), ContentScale.Fit, fade = false) {
            Text(initials(c.name), fontSize = Tv.micro, color = Tv.muted, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.Center))
        }
    }
}

fun leftText(end: Long, now: Long): String {
    val m = ((end - now) / 60_000).coerceAtLeast(1)
    return if (m < 60) "$m min left" else if (m < 180) "${m / 60} h ${m % 60} min left".replace(" 0 min", "") else "until ${hhmm(end)}"
}

/** On now: the channel (logo and name) on top, the programme in large type, how long is left, and a progress line.
 *  A channel with no guide shows its group instead, without a line. [kicker] goes before the time left (a competition). */
@Composable
fun NowCard(c: Channel, prog: Programme?, title: String, kicker: String? = null, live: Boolean = false, twoLines: Boolean = true, restoreKey: String, onLong: (() -> Unit)? = null, onClick: () -> Unit) {
    val now = HomeClock.now
    val p = prog?.takeIf { it.end > now }
    val sub = if (p == null) prettyGroup(c.group).ifEmpty { "Live channel" } else listOfNotNull(kicker, leftText(p.end, now)).joinToString("  ·  ")
    FocusCard(Modifier.width(NOW_W).height(nowHeight(twoLines)), scale = 1.05f, restoreKey = restoreKey, label = "$title on ${c.name}, $sub", onLongClick = onLong, onClick = onClick) {
        Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Tv.surface3, Tv.surface)))) {
            Column(Modifier.fillMaxSize().padding(start = 14.dp, end = 14.dp, top = 11.dp, bottom = 13.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TinyLogo(c)
                    Spacer(Modifier.width(8.dp))
                    Text(channelLabel(c.name), fontSize = Tv.label, color = Tv.text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(8.dp))
                Text(title, fontSize = Tv.body, lineHeight = 19.sp, fontWeight = FontWeight.SemiBold, color = if (p == null) Tv.text2 else Tv.text, maxLines = if (twoLines) 2 else 1, overflow = TextOverflow.Ellipsis)
                Row(Modifier.padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (live && p != null) { Box(Modifier.size(6.dp).clip(RoundedCornerShape(3.dp)).background(Tv.live)); Spacer(Modifier.width(6.dp)) }
                    Text(sub, fontSize = Tv.micro, color = Tv.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (p != null) ProgressLine((now - p.start).toFloat() / (p.end - p.start), Modifier.align(Alignment.BottomStart).fillMaxWidth(), 3.dp)
        }
    }
}

/** Starting soon: the time (and "in 25 min" when it's within the hour), the programme, then the channel and why it's
 *  here. A bell when a reminder is set. */
@Composable
fun SoonCard(s: SoonItem, reminded: Boolean, twoLines: Boolean = true, restoreKey: String, onClick: () -> Unit) {
    val now = HomeClock.now
    val mins = ((s.p.start - now) / 60_000).coerceAtLeast(0)
    val title = s.p.title.replace(YEAR_TAIL, "")
    FocusCard(Modifier.width(NOW_W).height(nowHeight(twoLines)), restoreKey = restoreKey, label = "$title, ${hhmm(s.p.start)} on ${s.c.name}, ${s.why}", onLongClick = onClick, onClick = onClick) {
        Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Tv.surface2, Tv.surface)))) {
            Column(Modifier.fillMaxSize().padding(start = 14.dp, end = 14.dp, top = 9.dp, bottom = 10.dp)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(hhmm(s.p.start), fontSize = Tv.body, color = Tv.text, fontWeight = FontWeight.Bold, maxLines = 1)
                    Text(if (mins < 1) "  starting" else if (mins < 60) "  in $mins min" else "", fontSize = Tv.label, color = Tv.star, fontWeight = FontWeight.Medium, maxLines = 1)
                    Spacer(Modifier.weight(1f))
                    if (reminded) Icon(Icons.Default.NotificationsActive, "Reminder set", tint = Tv.star, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.height(6.dp))
                Text(title, fontSize = Tv.body, lineHeight = 19.sp, fontWeight = FontWeight.SemiBold, color = Tv.text, maxLines = if (twoLines) 2 else 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TinyLogo(s.c)
                    Spacer(Modifier.width(8.dp))
                    Text("${s.why}  ·  ${channelLabel(s.c.name)}", fontSize = Tv.micro, color = Tv.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
