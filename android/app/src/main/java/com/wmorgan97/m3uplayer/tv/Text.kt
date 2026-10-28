package com.wmorgan97.m3uplayer.tv

// Name cleaning, ported from the web app (vod.js, epg.js, app.js) so titles, groups and guide matching read
// exactly as they do on the iPhone and the Mac.

private val SUPER_RE = Regex("[ʰ-˿ᴬ-ᵫ⁰-₟]+")
private val TAG_RE = Regex("""\s*(?:\[[^\]]*\]|\{[^\}]*\})""")
private val PREFIX_RE = Regex("""^(?:[A-Z0-9+&]{1,5}(?:-[A-Z0-9+&]{1,5})*\s*[|:\-]\s+)+""")
private val EP_RE = Regex("""(?:^|[\s.\-_(])(?:S(\d{1,2})\s*[.\-_]?\s*E(\d{1,3})|(\d{1,2})x(\d{1,3})|Season\s*(\d{1,2})\s*[.\-_]?\s*(?:Episode|Ep\.?|E)\s*(\d{1,3})|E(?:pisode|p)?\.?\s*(\d{1,3}))(?=[\s.\-_)]|$)""", RegexOption.IGNORE_CASE)
private val YEAR_RE = Regex("""(?:^|[\s(\[])((?:19|20)\d{2})(?=[)\]\s]|$)[)\]]?""")
private val NOISE_RE = Regex("""(?:^|[\s\-|:·.,]+)(?:HD|FHD|UHD|SD|4K|8K|HDR|HDR10|DV|(?:480|720|1080|2160)[pi]|HEVC|x26[45]|H\.?26[45]|WEB-?(?:DL|Rip)|Blu-?Ray|BRRip|HDTV|DVDRip|MULTI|VOSTFR|VF|VO|VOST|DUB(?:BED)?|SUB(?:BED|S)?|LATINO|DUAL)\s*$""", RegexOption.IGNORE_CASE)
private val TRIM_RE = Regex("""^[\s\-|:·,._]+|[\s\-|:·,._]+$""")
private val WS_RE = Regex("""\s+""")

data class Meta(val title: String, val year: Int?, val season: Int?, val episode: Int?)

private fun cleanTitle(s: String): String {
    var t = s.replace(WS_RE, " ").replace(TRIM_RE, "")
    while (true) { val n = t.replace(NOISE_RE, "").replace(TRIM_RE, ""); if (n == t || n.isEmpty()) break; t = n }
    if (t.isNotEmpty() && !t.contains(' ') && t.contains('.')) t = t.replace('.', ' ')
    return t
}

fun vodMeta(name: String?): Meta {
    var s = (name ?: "").replace(SUPER_RE, "").replace(TAG_RE, "").replace(PREFIX_RE, "")
    var season: Int? = null; var episode: Int? = null; var year: Int? = null
    EP_RE.find(s)?.let { m ->
        val g = m.groupValues
        season = (g[1].ifEmpty { g[3].ifEmpty { g[5] } }).toIntOrNull() ?: 1
        episode = (g[2].ifEmpty { g[4].ifEmpty { g[6].ifEmpty { g[7] } } }).toIntOrNull()
        s = s.substring(0, m.range.first)
    }
    val last = YEAR_RE.findAll(s).lastOrNull()
    if (last != null && last.range.first > 0) {
        year = last.groupValues[1].toInt()
        s = s.substring(0, last.range.first) + " " + s.substring(last.range.last + 1)
    }
    return Meta(cleanTitle(s), year, season, episode)
}

/** "Inception" from "4K-EN - Inception (2010) (US)". */
fun displayTitle(name: String?): String {
    val t = (vodMeta(name).title.ifEmpty { name ?: "" }).replace(Regex("""^\d{1,3}\.\s+(?=\D)"""), "")
    return t.replace(Regex("""\s*\((?:[A-Z]{2}(?:/[A-Z]{2})?)\)"""), "").replace(Regex("""\s*\(\s*\)"""), "").trim().ifEmpty { name ?: "" }
}

/** "Clean Entry" from "EN - S.W.A.T. Exiles (2026) (US) - S01E01 - Clean Entry" ("" when there is none). */
fun epTitle(name: String?): String {
    val n = (name ?: "").trim()
    val t = n.split(Regex("""\bS\d+\s*E\d+\s*[-–:]?\s*""", RegexOption.IGNORE_CASE)).last().trim()
    return if (t.isNotEmpty() && t != n) t else ""
}

private val NON_LATIN = Regex("[Ͱ-ϿЀ-ӿ֐-׿؀-ۿݐ-ݿﭐ-﷿ﹰ-﻿]")
private val LANG_PREFIX = Regex("""^\s*(?:(ES|LA|BR|AF|FR|QC|IN|SO|PK|BN|DE|IT|NL|BE|PL|GR|IL|RU|MT|BG|RO|AL|EX|KU|TR|IR|CN|PH|JP|KR)\s*-|PT/BR\b)""", RegexOption.IGNORE_CASE)
private val LANG_WORD = Regex("""(?:^|[^A-ZÀ-ÿ])(NORDIC|SVENSKA?|DANSKE?|NORGE|NORSK|SUOMEN|SUOMI|ÍSLANDS|TURKISH|TURKSIH|GREECE|GREEK|ITALY|FRANCE|QUÉBEC|ALBANIA|INDIAN?|HINDI|SOMALIA|PAKISTAN|NETHERLANDS|VIDEOLAND|BELGIUM|GERMANY|POLSKA|ESPAÑA|LATINO|PERSIAN|KURDISH|HEBREW|ROMANIAN|BULGARIYA|HUNGARY|RUSS[AI]+N|AFRICA|CHINA|PHILIPPINES|MALTA|JOYN|ASIA|MENA|ARABIA)(?:$|[^A-ZÀ-ÿ])""", RegexOption.IGNORE_CASE)

/** English-language groups only (the app's default "English only" setting). */
fun isEnglishGroup(n: String?): Boolean { val s = n ?: ""; return !NON_LATIN.containsMatchIn(s) && !LANG_PREFIX.containsMatchIn(s) && !LANG_WORD.containsMatchIn(s) }

/** "UK| Sky Sports ᴳᴬᴺᴶᴬ" -> "UK · Sky Sports". */
fun prettyGroup(name: String?): String = (name ?: "")
    .replace(Regex("[ᴬ-ᵪ⁰-₟ᵃ-ᶿⱽ²³¹]+"), "")
    .replace(Regex("""\s*[|/►▶]\s*"""), " · ").replace(WS_RE, " ").replace(Regex("""\s·\s*$"""), "").trim()

private val NUM_WORDS = mapOf("one" to "1", "two" to "2", "three" to "3", "four" to "4", "five" to "5", "six" to "6", "seven" to "7", "eight" to "8", "nine" to "9", "ten" to "10")
/** Channel names from playlists and guides differ in small ways; this is the web app's matching key. */
fun normName(s: String?): String = (s ?: "").lowercase()
    .replace(Regex("[ᴬ-ᵪ²³¹⁰-₟]+"), "")
    .replace(Regex("""^\s*(?:uk|gb|us|usa|ca|ie|au|nz|eu)\s*[:|\-]\s*"""), "")
    .replace(Regex("""\(.*?\)|\[.*?\]"""), "")
    .replace(Regex("""\b(hd|fhd|uhd|4k|sd|hevc|h265|backup|raw|vip|low|50fps)\b"""), "")
    .replace(Regex("""\b(one|two|three|four|five|six|seven|eight|nine|ten)\b""")) { NUM_WORDS[it.value] ?: it.value }
    .replace(Regex("""[^\p{L}\p{N}+]+"""), "")

/** Higher = better copy of the same title (4K, HDR, Dolby; other languages last). */
fun qualityScore(name: String, group: String): Int {
    val t = "$name $group"
    val res = when {
        Regex("""\b(4k|uhd|2160p?)\b""", RegexOption.IGNORE_CASE).containsMatchIn(t) -> 40
        Regex("""\b(1080p?|fhd|full ?hd)\b""", RegexOption.IGNORE_CASE).containsMatchIn(t) -> 30
        Regex("""\b720p?\b""", RegexOption.IGNORE_CASE).containsMatchIn(t) -> 20
        Regex("""\b(sd|480p?)\b""", RegexOption.IGNORE_CASE).containsMatchIn(t) -> 10
        else -> 25
    }
    val hdr = Regex("""hdr|dolby ?vision|\bdv\b""", RegexOption.IGNORE_CASE).containsMatchIn(t)
    val atmos = Regex("""atmos|dolby(?! ?vision)|ddp|eac3|5\.1|7\.1""", RegexOption.IGNORE_CASE).containsMatchIn(t)
    return res + (if (hdr) 4 else 0) + (if (atmos) 1 else 0) - (if (isEnglishGroup(group)) 0 else 60)
}

// Live 4K first, the same way as the iPhone/web app (js/app.js liveBase / IS_UHD): "Sky Sports F1" and
// "Sky Sports F1 ᵁᴴᴰ ᴴᴰᴿ" are the same channel. Only the channel's own name decides it is the 4K copy.
private val IS_UHD = Regex("""\b(4k|uhd|2160p?|3840p?)\b|ᵁᴴᴰ""", RegexOption.IGNORE_CASE)
private val SUPERSCRIPTS = Regex("""[ʰ-˿ᴬ-ᵫ⁰-₟]+""")
private val BRACKETS = Regex("""\[[^\]]*\]|\([^)]*\)""")
private val QUALITY_WORDS = Regex("""\b(4k|uhd|hdr|fhd|hd|sd|2160p?|3840p?|1080p?|720p?|backup|raw|hevc)\b""", RegexOption.IGNORE_CASE)
private val NOT_ALNUM = Regex("""[^a-z0-9:]+""")
fun isUhdName(name: String) = IS_UHD.containsMatchIn(name)
fun liveBase(name: String): String = name.replace(SUPERSCRIPTS, " ").replace(BRACKETS, " ").replace(QUALITY_WORDS, " ").lowercase().replace(NOT_ALNUM, "")

fun quality(name: String, group: String): String? {
    val t = "$name $group"
    return when {
        Regex("""\b(4k|uhd|2160p?)\b|ᵁᴴᴰ""", RegexOption.IGNORE_CASE).containsMatchIn(t) -> "4K"
        Regex("""\b(1080p?|fhd|hd)\b|ᴴᴰ""", RegexOption.IGNORE_CASE).containsMatchIn(t) -> "HD"
        else -> null
    }
}

fun clock(seconds: Long): String {
    val s = seconds.coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

fun hhmm(ms: Long): String = java.text.SimpleDateFormat("HH:mm", java.util.Locale.UK).format(java.util.Date(ms))

/** "2h 28m" from minutes. */
fun runtime(min: Int): String = if (min >= 60) "${min / 60}h ${min % 60}m" else "${min}m"
