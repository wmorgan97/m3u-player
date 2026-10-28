package com.wmorgan97.m3uplayer.tv

import java.util.Calendar
import java.util.TimeZone

// Pure logic (no Android): the M3U parser, catch-up links and guide time helpers. Unit-tested on the JVM.

// ---------- M3U ----------

private val ATTR_RE = Regex("""([\w-]+)=("([^"]*)"|'([^']*)'|(\S+))""")
private val SEP_RE = Regex("""^\s*[#*=_~·•▬—–-]{2,}|[#*=_~·•▬—–-]{2,}\s*$""")
private val PROMO_RE = Regex("""⬇️|release update|learn that|^epgenius$""", RegexOption.IGNORE_CASE)   // provider notice rows, not channels
private val VOD_EXT_RE = Regex("""\.(mp4|mkv|avi|mov|m4v)$""")
private val EPG_SPLIT_RE = Regex("[\\s,]+")
private val EPG_ATTRS = setOf("url-tvg", "x-tvg-url")

private fun attrValue(m: MatchResult): String = m.groupValues.drop(3).firstOrNull { it.isNotEmpty() }.orEmpty()

/** The live channels of an M3U playlist (films and series rows left out) and the guide links in its header.
 *  Channels without a tvg-chno are numbered from [offset] + 1. */
fun parseM3U(text: String, offset: Int = 0): Pair<List<Channel>, List<String>> {
    val out = ArrayList<Channel>(); val epg = ArrayList<String>()
    var attrs: Map<String, String> = emptyMap(); var title = ""; var grp = ""; val opts = HashMap<String, String>()
    for (raw in text.lineSequence()) {
        val line = raw.trim().trimStart('﻿').trim()   // a byte-order mark would make the header read as a channel
        if (line.isEmpty()) continue
        if (line.startsWith("#EXTM3U", true)) {
            ATTR_RE.findAll(line).forEach { m -> if (m.groupValues[1].lowercase() in EPG_ATTRS) epg += attrValue(m).split(EPG_SPLIT_RE).filter { it.isNotBlank() } }
            continue
        }
        if (line.startsWith("#EXTINF:", true)) {
            val body = line.substring(8)
            var q: Char? = null; var cut = -1
            for (i in body.indices) { val ch = body[i]; if (q != null) { if (ch == q) q = null } else if ((ch == '"' || ch == '\'') && i > 0 && body[i - 1] == '=') q = ch else if (ch == ',') { cut = i; break } }
            val head = if (cut >= 0) body.substring(0, cut) else body
            title = if (cut >= 0) body.substring(cut + 1).trim() else ""
            attrs = ATTR_RE.findAll(head).associate { it.groupValues[1].lowercase() to attrValue(it) }
            continue
        }
        if (line.startsWith("#EXTGRP:", true)) { grp = line.substring(8).trim(); continue }
        if (line.startsWith("#EXTVLCOPT:", true)) {
            val o = line.substring(11).trim(); val i = o.indexOf('=')
            if (i > 0) when (o.substring(0, i).lowercase()) { "http-user-agent" -> opts["User-Agent"] = o.substring(i + 1); "http-referrer", "http-referer" -> opts["Referer"] = o.substring(i + 1) }
            continue
        }
        if (line.startsWith("#")) continue
        val u = line.lowercase().substringBefore('?')
        val isLive = !u.contains("/series/") && !u.contains("/movie/") && !VOD_EXT_RE.containsMatchIn(u)
        val name = title.ifEmpty { attrs["tvg-name"] ?: line }
        val g = attrs["group-title"]?.ifEmpty { null } ?: grp
        if (isLive && !SEP_RE.containsMatchIn(name) && name.any { it.isLetterOrDigit() } && !PROMO_RE.containsMatchIn(name) && !PROMO_RE.containsMatchIn(g) && !u.contains("epgenius.org"))
            out += Channel(name, line, g, attrs["tvg-logo"] ?: "", attrs["tvg-id"] ?: "", attrs["tvg-name"] ?: "",
                attrs["tvg-chno"]?.toIntOrNull() ?: (offset + out.size + 1), HashMap(opts),
                catchupDays = attrs["catchup-days"]?.toIntOrNull() ?: if (!attrs["catchup"].isNullOrEmpty() || !attrs["catchup-source"].isNullOrEmpty() || !attrs["catchup-type"].isNullOrEmpty()) 7 else 0,
                catchupMode = (attrs["catchup"] ?: attrs["catchup-type"] ?: "").lowercase(), catchupSource = attrs["catchup-source"] ?: "")
        attrs = emptyMap(); title = ""; grp = ""; opts.clear()
    }
    return out to epg
}

// ---------- catch-up ----------

private val XC_RE = Regex("""^(https?://[^/]+)/(?:live/)?([^/]+)/([^/]+)/(\d+)(?:\.\w+)?$""", RegexOption.IGNORE_CASE)
private val PLACEHOLDER_RE = Regex("""\$?\{\(?(\w+)\)?(?::([^\}]*))?\}""")
private val UTC: TimeZone = TimeZone.getTimeZone("UTC")

private fun p2(v: Int) = v.toString().padStart(2, '0')

/** Y m d H M S in [fmt] replaced by the parts of [ms] (UTC), e.g. "Y-m-d:H-M" -> "2026-10-25:19-30". */
private fun formatTime(ms: Long, fmt: String): String {
    val c = Calendar.getInstance(UTC).apply { timeInMillis = ms }
    val sb = StringBuilder()
    for (ch in fmt) sb.append(when (ch) {
        'Y' -> c.get(Calendar.YEAR).toString(); 'm' -> p2(c.get(Calendar.MONTH) + 1); 'd' -> p2(c.get(Calendar.DAY_OF_MONTH))
        'H' -> p2(c.get(Calendar.HOUR_OF_DAY)); 'M' -> p2(c.get(Calendar.MINUTE)); 'S' -> p2(c.get(Calendar.SECOND))
        else -> ch.toString()
    })
    return sb.toString()
}

/** The link for a past programme on a channel with an archive (ported from the web app's m3uCatchupUrl). Null for a
 *  programme that hasn't started or is older than the channel keeps. */
fun catchupUrl(c: Channel, start: Long, stop: Long, now: Long = System.currentTimeMillis()): String? {
    if (!c.hasCatchup || start > now || start < now - c.catchupDays * 86_400_000L) return null
    val xc = XC_RE.find(c.url)
    if (xc != null && (c.catchupMode == "xc" || c.catchupMode == "xtream" || (c.catchupSource.isEmpty() && c.url.contains("/live/")))) {
        val (host, user, pass, id) = xc.destructured
        return "$host/timeshift/$user/$pass/${Math.ceil((stop - start) / 60000.0).toInt().coerceAtLeast(1)}/${formatTime(start, "Y-m-d:H-M")}/$id.ts"
    }
    var source = c.catchupSource
    if (source.isEmpty() || c.catchupMode == "shift" || c.catchupMode == "timeshift") source = (if (c.url.contains('?')) "&" else "?") + "utc={utc}&lutc={lutc}"
    val s = start / 1000; val e = stop / 1000; val n = now / 1000
    val times = mapOf("utc" to start, "start" to start, "timestamp" to start, "lutc" to now, "now" to now, "utcend" to stop, "end" to stop)
    val values = mapOf("utc" to "$s", "start" to "$s", "timestamp" to "$s", "lutc" to "$n", "now" to "$n", "utcend" to "$e", "end" to "$e", "duration" to "${e - s}", "offset" to "${n - s}",
        "Y" to formatTime(start, "Y"), "m" to formatTime(start, "m"), "d" to formatTime(start, "d"),
        "H" to formatTime(start, "H"), "M" to formatTime(start, "M"), "S" to formatTime(start, "S"))
    val filled = PLACEHOLDER_RE.replace(source) { m ->
        val k = m.groupValues[1]; val fmt = m.groupValues[2]
        when {
            fmt.isNotEmpty() && k in times -> formatTime(times.getValue(k), fmt)                       // {utc:Y-m-d H:M:S}
            fmt.isNotEmpty() && (k == "duration" || k == "offset") && fmt.toLongOrNull()?.let { it > 0 } == true ->
                ((if (k == "duration") e - s else n - s) / fmt.toLong()).toString()                      // {duration:60} = minutes
            else -> values[k] ?: m.value
        }
    }
    return if (filled.startsWith("http", true)) filled else c.url + filled
}

// ---------- guide time ----------

private const val HALF_HOUR = 30 * 60_000L

/** The start of the half hour (wall clock in [tz]) that [t] falls in. Exact across daylight-saving changes. */
fun floorToHalfHour(t: Long, tz: TimeZone = TimeZone.getDefault()): Long {
    val off = tz.getOffset(t)
    return Math.floorDiv(t + off, HALF_HOUR) * HALF_HOUR - off
}

/** The programme on at [t] (the latest-starting one when they overlap), or null in a gap. */
fun programmeAt(l: List<Programme>, t: Long): Programme? {
    var best: Programme? = null
    for (p in l) if (p.start <= t && p.end > t && (best == null || p.start >= best.start)) best = p
    return best
}

/** A channel's guide data as stored once on arrival: zero/negative-length and finished-before-[dropEndingBefore]
 *  programmes dropped, sorted by start, one entry per start time (the longest, which also covers same start+title
 *  duplicates), and each overlapping programme's end trimmed to the next one's start. */
fun tidyProgrammes(l: List<Programme>, dropEndingBefore: Long = Long.MIN_VALUE): List<Programme> {
    val sorted = l.filter { it.end > it.start && it.end > dropEndingBefore }.map { if (needsPlain(it.title)) it.copy(title = plainTitle(it.title)) else it }
        .sortedWith(compareBy<Programme> { it.start }.thenByDescending { it.end })
    val out = ArrayList<Programme>(sorted.size)
    for (p in sorted) {
        val prev = out.lastOrNull()
        if (prev != null) {
            if (p.start == prev.start) continue
            if (prev.end > p.start) out[out.size - 1] = prev.copy(end = p.start)
        }
        out += p
    }
    return out
}

/** Programmes ending at or before [cutoff] left out of an already-tidied (sorted, non-overlapping) list. */
fun dropFinished(l: List<Programme>, cutoff: Long): List<Programme> {
    val i = l.indexOfFirst { it.end > cutoff }
    return when (i) { -1 -> emptyList(); 0 -> l; else -> l.subList(i, l.size).toList() }
}

/** The programme on at [t] and the one after it, from a tidied (sorted, non-overlapping) list. */
fun nowNextIn(l: List<Programme>, t: Long): Pair<Programme?, Programme?> {
    val now = programmeAt(l, t)
    return now to l.firstOrNull { it.start > t }
}

/** The guide key holding [c]'s programmes: its tvg-id when the guide has it, else the id the server matched to its
 *  normalised tvg-name or name. An empty tvg-id or a name that normalises to nothing never matches anything. */
fun guideKeyFor(c: Channel, programmes: Map<String, *>, guideNames: Map<String, String>): String? {
    val id = c.tvgId.trim().lowercase()
    if (id.isNotEmpty() && programmes.containsKey(id)) return id
    for (n in listOf(c.tvgName, c.name)) {
        val k = normName(n); if (k.isEmpty()) continue
        val g = guideNames[k]?.takeIf { it.isNotEmpty() && programmes.containsKey(it) }
        if (g != null) return g
    }
    return null
}

// ---------- recents ----------

/** Recently watched, newest first: one entry per url, entries whose channel is gone left out once the channel list
 *  is known ([exists] null = not loaded yet, keep everything), at most [cap]. */
fun <T> tidyRecents(items: List<T>, url: (T) -> String, at: (T) -> Long, exists: ((String) -> Boolean)?, cap: Int = 20): List<T> =
    items.filter { url(it).isNotEmpty() }.sortedByDescending(at).distinctBy(url)
        .filter { exists == null || exists(url(it)) }.take(cap)

// ---------- logging ----------

private val CRED_PATH_RE = Regex("""^/(live|movie|series|timeshift)/[^/]+/[^/]+(/|$)""", RegexOption.IGNORE_CASE)
private val BARE_XC_RE = Regex("""^/[^/]+/[^/]+/(\d+(?:\.\w+)?)$""")

/** A link safe to write to the log: no user:pass@, query or fragment, and Xtream user/password path segments as ***. */
fun redactUrl(url: String): String {
    val noFrag = url.substringBefore('#').substringBefore('?')
    val schemeEnd = noFrag.indexOf("://")
    if (schemeEnd < 0) return noFrag.substringBefore('/').substringAfterLast('@').ifEmpty { "(link)" }
    val scheme = noFrag.substring(0, schemeEnd + 3)
    val rest = noFrag.substring(schemeEnd + 3)
    val auth = rest.substringBefore('/'); val path = rest.substring(auth.length)
    val host = auth.substringAfterLast('@')
    val p = when {
        CRED_PATH_RE.containsMatchIn(path) -> CRED_PATH_RE.replace(path) { "/${it.groupValues[1]}/***/***${it.groupValues[2]}" }
        BARE_XC_RE.matches(path) -> BARE_XC_RE.replace(path) { "/***/***/${it.groupValues[1]}" }
        else -> path
    }
    return scheme + host + p
}

// Guides mark titles with Unicode superscript/small-capital letters ("ᴸᶦᵛᵉ", "ᴺᵉʷ"). They render through a slow font
// fallback on every row (and look odd), so they become plain words: "Live", "New".
private const val SUPER_FROM = "ᴬᴮᴰᴱᴳᴴᴵᴶᴷᴸᴹᴺᴼᴾᴿᵀᵁⱽᵂᵃᵇᶜᵈᵉᶠᵍʰⁱʲᵏˡᵐⁿᵒᵖʳˢᵗᵘᵛʷˣʸᶻᶦᴀʙᴄᴅᴇꜰɢʜɪᴊᴋʟᴍɴᴏᴘʀꜱᴛᴜᴠᴡʏᴢ"
private const val SUPER_TO   = "ABDEGHIJKLMNOPRTUVWabcdefghijklmnoprstuvwxyziABCDEFGHIJKLMNOPRSTUVWYZ"
internal fun needsPlain(t: String) = t.any { SUPER_FROM.indexOf(it) >= 0 }
internal fun plainTitle(t: String): String {
    val sb = StringBuilder(t.length); var prevSuper = false
    for (ch in t) {
        val i = SUPER_FROM.indexOf(ch)
        if (i >= 0) { if (!prevSuper && sb.isNotEmpty() && sb.last() != ' ') sb.append(' '); sb.append(SUPER_TO[i]); prevSuper = true }
        else { prevSuper = false; sb.append(ch) }
    }
    return sb.toString().replace(Regex("\\s+"), " ").trim()
}
