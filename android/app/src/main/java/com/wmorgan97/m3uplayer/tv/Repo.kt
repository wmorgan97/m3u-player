package com.wmorgan97.m3uplayer.tv

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

// ---------- models ----------

data class Channel(val name: String, val url: String, val group: String, val logo: String, val tvgId: String, val tvgName: String,
                   val num: Int, val headers: Map<String, String> = emptyMap()) {
    val title get() = name
}

data class Vod(
    val kind: String,          // movie | series
    val id: Long,              // stream_id / series_id
    val name: String,
    val poster: String,
    val group: String,
    val rating: Double?,
    val year: Int?,
    val ext: String,
    val backdrop: String = "",
    val overview: String = "",
    val copies: Int = 1,
) {
    val title get() = displayTitle(name)
    val label get() = if (year != null) "$title ($year)" else title
}

data class Episode(val id: String, val season: Int, val num: Int, val title: String, val ext: String, val still: String, val plot: String, val duration: String)

data class Programme(val start: Long, val end: Long, val title: String, val desc: String)

data class Progress(val url: String, val t: Double, val d: Double, val done: Boolean, val at: Long, val item: JSONObject)

data class Xtream(val server: String, val user: String, val pass: String)

data class Overview(val trending: List<Vod>, val top: List<Vod>, val fresh: List<Vod>, val recent: List<Vod>, val groups: List<Pair<String, String>>, val catNames: Map<String, String>)

/**
 * Everything the TV screens show comes from the same server the iPhone and Mac use: logins and watch history
 * (/sync), films and series (/vod), the TV guide (/guide), posters (/img), film details (/tmdb/info). Live channel
 * lists come from the playlist itself (cached on the Shield, refreshed in the background).
 */
object Repo {
    const val DEFAULT_SERVER = "https://win-69ffbub31ra.tail0f14c6.ts.net:8443"
    private lateinit var app: Context
    private val prefs get() = app.getSharedPreferences("tv", Context.MODE_PRIVATE)
    val server get() = prefs.getString("server", DEFAULT_SERVER)!!.trimEnd('/')
    val key get() = prefs.getString("key", "") ?: ""
    fun init(c: Context) { app = c.applicationContext }
    fun hasKey() = key.isNotEmpty()
    fun saveConfig(key: String, server: String?) { prefs.edit().putString("key", key).apply { if (!server.isNullOrBlank()) putString("server", server) }.apply() }

    // Settings kept on the TV (the rest live in the web app's Settings)
    var englishOnly: Boolean get() = prefs.getBoolean("englishOnly", true); set(v) { prefs.edit().putBoolean("englishOnly", v).apply(); overviews.clear(); groupCache.clear() }
    var fourKFirst: Boolean get() = prefs.getBoolean("fourK", true); set(v) = prefs.edit().putBoolean("fourK", v).apply()
    var lastChannelUrl: String? get() = prefs.getString("lastChannel", null); set(v) = prefs.edit().putString("lastChannel", v).apply()
    /** Bumped whenever favourites / cached data change, so screens showing them redraw. */
    private val versionState = androidx.compose.runtime.mutableIntStateOf(0)
    var version: Int get() = versionState.intValue; set(v) { versionState.intValue = v }
    fun clearCache() { app.filesDir.listFiles()?.forEach { if (it.name.startsWith("vod-") || it.name.startsWith("live-")) it.delete() }; overviews.clear(); groupCache.clear(); live = null; version++ }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    fun api(path: String) = "$server/$path?key=${enc(key)}"
    fun img(url: String?, w: Int = 300): String? = if (url.isNullOrBlank()) null else if (!url.startsWith("http")) url else "$server/img?key=${enc(key)}&w=$w&u=${enc(url)}"

    private fun http(url: String, body: String? = null, timeout: Int = 30_000): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000; c.readTimeout = timeout
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android TV) M3UPlayer")
        if (body != null) { c.requestMethod = "POST"; c.doOutput = true; c.setRequestProperty("Content-Type", "application/json"); c.outputStream.use { it.write(body.toByteArray()) } }
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        if (code !in 200..299) throw Exception(text.ifBlank { "The server replied $code" })
        return text
    }

    // ---------- sync: logins, favourites, history, progress ----------

    @Volatile var syncData: JSONObject = JSONObject()
    suspend fun sync(): JSONObject = withContext(Dispatchers.IO) {
        val d = JSONObject(http(api("sync")))
        syncData = d
        version++
        File(app.filesDir, "sync.json").writeText(d.toString())
        d
    }
    fun cachedSync(): JSONObject = try { JSONObject(File(app.filesDir, "sync.json").readText()).also { syncData = it } } catch (e: Exception) { JSONObject() }

    fun xtream(): Xtream? {
        val s = syncData.optJSONObject("sources") ?: return null
        for (k in s.keys()) {
            val v = s.optJSONObject(k) ?: continue
            if (v.optBoolean("deleted") || v.optString("kind") != "xtream") continue
            if (v.optString("content") == "live") continue
            val x = v.optJSONObject("xtream") ?: continue
            return Xtream(x.optString("server").trimEnd('/'), x.optString("user"), x.optString("pass"))
        }
        return null
    }

    fun progress(): List<Progress> {
        val p = syncData.optJSONObject("progress") ?: return emptyList()
        val out = ArrayList<Progress>()
        for (u in p.keys()) {
            val v = p.optJSONObject(u) ?: continue
            out += Progress(u, v.optDouble("t", 0.0), v.optDouble("d", 0.0), v.optBoolean("done"), v.optLong("at"), v.optJSONObject("item") ?: JSONObject())
        }
        return out.sortedByDescending { it.at }
    }
    fun continueWatching() = progress().filter { !it.done && it.t > 30 && !it.item.optBoolean("hidden") && it.item.optString("url").isNotEmpty() }.take(20)
    fun progressFor(url: String): Progress? = progress().firstOrNull { it.url == url }

    fun recentChannels(): List<JSONObject> {
        val r = syncData.optJSONArray("recents") ?: return emptyList()
        return (0 until r.length()).mapNotNull { r.optJSONObject(it) }.filter { val u = it.optString("url"); !u.contains("/movie/") && !u.contains("/series/") }.take(20)
    }
    fun favorites(): List<JSONObject> {
        val f = syncData.optJSONArray("favorites") ?: return emptyList()
        return (0 until f.length()).mapNotNull { f.optJSONObject(it) }
    }
    fun isFavourite(url: String) = favorites().any { it.optString("url") == url }
    /** Adds or removes a channel from the favourites every device shares. */
    suspend fun toggleFavourite(c: Channel) = withContext(Dispatchers.IO) {
        val list = favorites().filter { it.optString("url") != c.url }.toMutableList()
        if (!isFavourite(c.url)) list += JSONObject().put("kind", "live").put("name", c.name).put("url", c.url).put("logo", c.logo).put("group", c.group).put("tvgId", c.tvgId)
        val body = JSONObject().put("favorites", JSONArray(list)).put("favAt", System.currentTimeMillis())
        syncData.put("favorites", JSONArray(list)).put("favAt", System.currentTimeMillis())
        try { syncData = JSONObject(http(api("sync"), body.toString())) } catch (_: Exception) {}
        version++
    }
    fun channelByUrl(url: String): Channel? = live?.channels?.firstOrNull { it.url == url }
    fun channelByNumber(n: Int): Channel? = live?.channels?.firstOrNull { it.num == n }

    /** Saves where you are in a film / episode (every device sees it). */
    suspend fun saveProgress(url: String, t: Double, d: Double, item: JSONObject, hide: Boolean = false) = withContext(Dispatchers.IO) {
        if (d < 60) return@withContext
        val done = t >= d * 0.95 || d - t < 90
        val entry = JSONObject().put("t", t).put("d", d).put("v", 3).put("at", System.currentTimeMillis()).put("done", done).put("item", if (hide) JSONObject(item.toString()).put("hidden", true) else item)
        syncData.optJSONObject("progress")?.put(url, entry)
        try { syncData = JSONObject(http(api("sync"), JSONObject().put("progress", JSONObject().put(url, entry)).toString())) } catch (_: Exception) {}
    }
    suspend fun saveRecent(item: JSONObject) = withContext(Dispatchers.IO) {
        try { syncData = JSONObject(http(api("sync"), JSONObject().put("recents", JSONArray().put(JSONObject(item.toString()).put("at", System.currentTimeMillis()))).toString())) } catch (_: Exception) {}
    }

    // ---------- live channels ----------

    data class Live(val channels: List<Channel>, val groups: List<String>, val epgUrls: List<String>)
    @Volatile var live: Live? = null

    /** The live channels of every playlist: the saved copy at once, a fresh download when it's over 12 hours old. */
    suspend fun liveChannels(refresh: Boolean = false): Live = withContext(Dispatchers.IO) {
        live?.takeIf { !refresh }?.let { return@withContext it }
        val sources = syncData.optJSONObject("sources") ?: JSONObject()
        val all = ArrayList<Channel>(); val epg = ArrayList<String>()
        for (k in sources.keys()) {
            val v = sources.optJSONObject(k) ?: continue
            if (v.optBoolean("deleted") || v.optString("content") == "vod") continue
            when (v.optString("kind")) {
                "url" -> {
                    val src = v.optString("sourceUrl"); if (src.isEmpty()) continue
                    val file = File(app.filesDir, "live-" + sha(src).take(16) + ".m3u")
                    val stale = !file.exists() || System.currentTimeMillis() - file.lastModified() > 12 * 3600_000L
                    if (stale) try { val text = http(src, timeout = 120_000); if (text.contains("#EXTINF")) file.writeText(text) } catch (_: Exception) {}
                    if (file.exists()) { val (chs, hdr) = parseM3U(file.readText(), all.size); all += chs; epg += hdr }
                }
                "xtream" -> {
                    val x = v.optJSONObject("xtream") ?: continue
                    val xs = Xtream(x.optString("server").trimEnd('/'), x.optString("user"), x.optString("pass"))
                    try {
                        val cats = JSONArray(http(playerApi(xs, "get_live_categories")))
                        val names = (0 until cats.length()).associate { cats.getJSONObject(it).optString("category_id") to cats.getJSONObject(it).optString("category_name") }
                        val list = JSONArray(http(playerApi(xs, "get_live_streams"), timeout = 120_000))
                        for (i in 0 until list.length()) {
                            val s = list.getJSONObject(i)
                            all += Channel(s.optString("name"), "${xs.server}/live/${xs.user}/${xs.pass}/${s.optLong("stream_id")}.ts", names[s.optString("category_id")] ?: "",
                                s.optString("stream_icon"), s.optString("epg_channel_id"), s.optString("name"), all.size + 1)
                        }
                        epg += "${xs.server}/xmltv.php?username=${enc(xs.user)}&password=${enc(xs.pass)}"
                    } catch (_: Exception) {}
                }
            }
        }
        val groups = LinkedHashSet<String>(); all.forEach { groups += it.group }
        Live(all, groups.toList(), epg.distinct()).also { live = it }
    }

    private val ATTR_RE = Regex("""([\w-]+)=("([^"]*)"|'([^']*)'|(\S+))""")
    private val SEP_RE = Regex("""^\s*[#*=_~·•▬—–-]{2,}|[#*=_~·•▬—–-]{2,}\s*$""")
    private val PROMO_RE = Regex("""⬇️|release update|learn that|^epgenius$""", RegexOption.IGNORE_CASE)   // provider notice rows, not channels
    private fun parseM3U(text: String, offset: Int): Pair<List<Channel>, List<String>> {
        val out = ArrayList<Channel>(20000); val epg = ArrayList<String>()
        var attrs: Map<String, String> = emptyMap(); var title = ""; var grp = ""; val opts = HashMap<String, String>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#EXTM3U", true)) {
                ATTR_RE.findAll(line).forEach { m -> if (m.groupValues[1].lowercase() in setOf("url-tvg", "x-tvg-url")) epg += m.groupValues.drop(3).first { it.isNotEmpty() }.split(Regex("[\\s,]+")).filter { it.isNotBlank() } }
                continue
            }
            if (line.startsWith("#EXTINF:", true)) {
                val body = line.substring(8)
                var q: Char? = null; var cut = -1
                for (i in body.indices) { val ch = body[i]; if (q != null) { if (ch == q) q = null } else if ((ch == '"' || ch == '\'') && i > 0 && body[i - 1] == '=') q = ch else if (ch == ',') { cut = i; break } }
                val head = if (cut >= 0) body.substring(0, cut) else body
                title = if (cut >= 0) body.substring(cut + 1).trim() else ""
                attrs = ATTR_RE.findAll(head).associate { it.groupValues[1].lowercase() to it.groupValues.drop(3).firstOrNull { v -> v.isNotEmpty() }.orEmpty() }
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
            val isLive = !u.contains("/series/") && !u.contains("/movie/") && !Regex("""\.(mp4|mkv|avi|mov|m4v)$""").containsMatchIn(u)
            val name = title.ifEmpty { attrs["tvg-name"] ?: line }
            val g = attrs["group-title"]?.ifEmpty { null } ?: grp
            if (isLive && !SEP_RE.containsMatchIn(name) && name.any { it.isLetterOrDigit() } && !PROMO_RE.containsMatchIn(name) && !PROMO_RE.containsMatchIn(g) && !u.contains("epgenius.org"))
                out += Channel(name, line, attrs["group-title"]?.ifEmpty { null } ?: grp, attrs["tvg-logo"] ?: "", attrs["tvg-id"] ?: "", attrs["tvg-name"] ?: "",
                    attrs["tvg-chno"]?.toIntOrNull() ?: (offset + out.size + 1), HashMap(opts))
            attrs = emptyMap(); title = ""; grp = ""; opts.clear()
        }
        return out to epg
    }

    // ---------- TV guide ----------

    private val programmes = ConcurrentHashMap<String, List<Programme>>()
    private val guideNames = ConcurrentHashMap<String, String>()
    private val asked = ConcurrentHashMap<String, Long>()
    private fun chanKey(c: Channel) = c.tvgId.lowercase().ifEmpty { normName(c.tvgName.ifEmpty { c.name }) }
    fun guideFor(c: Channel): List<Programme> {
        val id = c.tvgId.lowercase()
        val key = if (id.isNotEmpty() && programmes.containsKey(id)) id else guideNames[normName(c.tvgName)] ?: guideNames[normName(c.name)]
        return key?.let { programmes[it] } ?: emptyList()
    }
    fun nowNext(c: Channel, t: Long = System.currentTimeMillis()): Pair<Programme?, Programme?> {
        val l = guideFor(c); val i = l.indexOfLast { it.start <= t }
        return (if (i >= 0 && l[i].end > t) l[i] else null) to l.getOrNull(i + 1)
    }

    /** Bumped when guide data arrives: anything showing what's on redraws. */
    private val guideState = androidx.compose.runtime.mutableIntStateOf(0)
    val guideVersion: Int get() = guideState.intValue
    private val guideScope = kotlinx.coroutines.CoroutineScope(Dispatchers.IO + kotlinx.coroutines.SupervisorJob())

    /** Asks the server for the guide of these channels (the ones on screen), 3-hourly at most per channel. Runs in its
     *  own scope, so moving to another group never cancels a download half-way (which left channels marked as asked). */
    fun requestGuide(channels: List<Channel>) { guideScope.launch { if (loadGuide(channels)) guideState.intValue++ } }

    suspend fun loadGuide(channels: List<Channel>): Boolean = withContext(Dispatchers.IO + kotlinx.coroutines.NonCancellable) {
        val urls = live?.epgUrls ?: return@withContext false
        if (urls.isEmpty()) return@withContext false
        val now = System.currentTimeMillis()
        val want = channels.filter { val k = chanKey(it); k.isNotEmpty() && (asked[k] ?: 0) < now - 3 * 3600_000L }
        if (want.isEmpty()) return@withContext false
        want.forEach { asked[chanKey(it)] = now }
        var got = false
        for (batch in want.chunked(600)) {
            val ids = JSONArray(); val names = JSONArray()
            batch.forEach { c -> if (c.tvgId.isNotEmpty()) ids.put(c.tvgId.lowercase()); names.put(normName(c.name)); if (c.tvgName.isNotEmpty()) names.put(normName(c.tvgName)) }
            try {
                val d = JSONObject(http(api("guide"), JSONObject().put("urls", JSONArray(urls)).put("ids", ids).put("names", names).toString(), 60_000))
                val p = d.optJSONObject("programmes") ?: JSONObject()
                for (k in p.keys()) {
                    val arr = p.getJSONArray(k)
                    programmes[k] = (0 until arr.length()).map { val a = arr.getJSONArray(it); Programme(a.optLong(0), a.optLong(1), a.optString(2), a.optString(3)) }
                }
                d.optJSONObject("names")?.let { n -> for (k in n.keys()) guideNames[k] = n.getString(k) }
                got = true
            } catch (_: Exception) { batch.forEach { asked.remove(chanKey(it)) } }
        }
        got
    }

    // ---------- films & series ----------

    fun playerApi(x: Xtream, action: String, extra: String = "") = "${x.server}/player_api.php?username=${enc(x.user)}&password=${enc(x.pass)}&action=$action$extra"

    private val overviews = ConcurrentHashMap<String, Overview>()
    private fun vodBody(x: Xtream, kind: String) = JSONObject().put("server", x.server).put("user", x.user).put("pass", x.pass).put("kind", kind)

    private fun toVod(kind: String, o: JSONObject, cats: Map<String, String>): Vod {
        val tm = o.optJSONObject("tm")
        val name = o.optString("name")
        val year = o.optString("year").take(4).toIntOrNull() ?: o.optString("releaseDate").take(4).toIntOrNull() ?: vodMeta(name).year
        return Vod(if (kind == "series") "series" else "movie", if (kind == "series") o.optLong("series_id") else o.optLong("stream_id"), name,
            o.optString(if (kind == "series") "cover" else "stream_icon"), cats[o.optString("category_id")] ?: "",
            o.optDouble("rating").takeIf { !it.isNaN() && it > 0 && it < 9.5 }, year, o.optString("container_extension").ifEmpty { "mp4" },
            tm?.optString("backdrop") ?: "", tm?.optString("overview") ?: o.optString("plot"))
    }
    /** English groups only, one entry per title (the best copy), as on the other devices. */
    private fun tidy(list: List<Vod>): List<Vod> {
        val by = LinkedHashMap<String, Vod>()
        for (v in list) {
            if (englishOnly && !isEnglishGroup(v.group)) continue
            val k = v.title.lowercase().replace("&", "and").replace(Regex("[^a-z0-9]+"), "") + "|" + (v.year ?: "")
            val prev = by[k]
            by[k] = if (prev == null) v else if (qualityScore(v.name, v.group) > qualityScore(prev.name, prev.group)) v.copy(copies = prev.copies + 1, backdrop = v.backdrop.ifEmpty { prev.backdrop }) else prev.copy(copies = prev.copies + 1)
        }
        return by.values.toList()
    }
    private fun arr(o: JSONObject, k: String, kind: String, cats: Map<String, String>) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { toVod(kind, a.getJSONObject(it), cats) } } ?: emptyList()

    private val refreshing = ConcurrentHashMap<String, Boolean>()
    /** The saved copy at once (instant first screen); a fresh one is fetched behind it when it's over 3 hours old. */
    suspend fun overview(kind: String): Overview? = withContext(Dispatchers.IO) {
        overviews[kind]?.let { return@withContext it }
        val x = xtream() ?: return@withContext null
        val file = File(app.filesDir, "vod-$kind.json")
        val fresh = file.exists() && System.currentTimeMillis() - file.lastModified() < 3 * 3600_000L
        val text = if (file.exists()) {
            if (!fresh && refreshing.putIfAbsent(kind, true) == null) kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
                try { file.writeText(http(api("vod"), vodBody(x, kind).toString(), 120_000)); overviews.remove(kind); groupCache.clear(); version++ } catch (_: Exception) {} finally { refreshing.remove(kind) }
            }
            file.readText()
        } else http(api("vod"), vodBody(x, kind).toString(), 120_000).also { file.writeText(it) }
        val d = JSONObject(text)
        val ca = d.optJSONArray("cats") ?: JSONArray()
        val cats = (0 until ca.length()).associate { ca.getJSONObject(it).optString("category_id") to ca.getJSONObject(it).optString("category_name") }
        val tm = d.optJSONArray("tmdbCats") ?: JSONArray()
        val groups = (0 until tm.length()).map { tm.getJSONObject(it).optString("id") to tm.getJSONObject(it).optString("name") }
        Overview(tidy(arr(d, "trending", kind, cats)), tidy(arr(d, "top", kind, cats)), tidy(arr(d, "fresh", kind, cats)), tidy(arr(d, "recent", kind, cats)), groups, cats).also { overviews[kind] = it; prefetchGroups(kind, it) }
    }

    private val groupCache = ConcurrentHashMap<String, List<Vod>>()
    fun cachedGroup(kind: String, tmdbId: String): List<Vod>? = groupCache["$kind|$tmdbId"]
    fun cachedOverview(kind: String): Overview? = overviews[kind]
    private val prefetching = ConcurrentHashMap<String, Boolean>()
    /** Every genre / list of a catalogue, fetched in the background (4 at a time) so each row has its titles the moment
     *  it scrolls into view. Without this a row arrived while you were moving, and its build landed mid-movement. */
    fun prefetchGroups(kind: String, o: Overview) {
        if (prefetching.putIfAbsent(kind, true) != null) return
        kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
            try {
                val sem = kotlinx.coroutines.sync.Semaphore(4)
                o.groups.map { (id, _) -> async { sem.withPermit { runCatching { group(kind, id) } } } }.awaitAll()
            } finally { prefetching.remove(kind) }
        }
    }
    /** A TMDB list or genre ("list:popular", "genre:28"). */
    suspend fun group(kind: String, tmdbId: String): List<Vod> = withContext(Dispatchers.IO) {
        groupCache["$kind|$tmdbId"]?.let { return@withContext it }
        val x = xtream() ?: return@withContext emptyList()
        val cats = overview(kind)?.catNames ?: emptyMap()
        val d = JSONObject(http(api("vod"), vodBody(x, kind).put("tmdb", tmdbId).toString(), 60_000))
        tidy(arr(d, "items", kind, cats)).also { groupCache["$kind|$tmdbId"] = it }
    }

    suspend fun search(q: String): Pair<List<Vod>, List<Vod>> = coroutineScope {
        val x = xtream() ?: return@coroutineScope emptyList<Vod>() to emptyList()
        val r = listOf("movies", "series").map { kind ->
            async(Dispatchers.IO) {
                try {
                    val cats = overview(kind)?.catNames ?: emptyMap()
                    tidy(arr(JSONObject(http(api("vod"), vodBody(x, kind).put("q", q).toString())), "items", kind, cats)).take(40)
                } catch (_: Exception) { emptyList() }
            }
        }.awaitAll()
        r[0] to r[1]
    }

    fun movieUrl(v: Vod): String? = xtream()?.let { "${it.server}/movie/${it.user}/${it.pass}/${v.id}.${v.ext}" }

    suspend fun episodes(v: Vod): Map<Int, List<Episode>> = withContext(Dispatchers.IO) {
        val x = xtream() ?: return@withContext emptyMap()
        val d = JSONObject(http(playerApi(x, "get_series_info", "&series_id=${v.id}"), timeout = 60_000))
        val eps = d.optJSONObject("episodes") ?: return@withContext emptyMap()
        val out = sortedMapOf<Int, List<Episode>>()
        for (s in eps.keys()) {
            val a = eps.optJSONArray(s) ?: continue
            out[s.toIntOrNull() ?: 1] = (0 until a.length()).map { i ->
                val e = a.getJSONObject(i); val info = e.optJSONObject("info") ?: JSONObject()
                Episode(e.optString("id"), s.toIntOrNull() ?: 1, e.optInt("episode_num", i + 1), epTitle(e.optString("title")).ifEmpty { "Episode ${e.optInt("episode_num", i + 1)}" },
                    e.optString("container_extension").ifEmpty { "mp4" }, info.optString("movie_image"), info.optString("plot"), info.optString("duration"))
            }.sortedBy { it.num }
        }
        out
    }
    fun episodeUrl(e: Episode): String? = xtream()?.let { "${it.server}/series/${it.user}/${it.pass}/${e.id}.${e.ext}" }

    private val tmdb = ConcurrentHashMap<String, JSONObject>()
    suspend fun tmdbInfo(v: Vod): JSONObject? = withContext(Dispatchers.IO) {
        val k = "${v.kind}|${v.title}|${v.year}"
        tmdb[k]?.let { return@withContext it }
        try {
            val d = JSONObject(http(api("tmdb/info") + "&kind=${if (v.kind == "series") "tv" else "movie"}&name=${enc(v.title)}&year=${v.year ?: ""}"))
            if (d.has("tid")) d.also { tmdb[k] = it } else null
        } catch (_: Exception) { null }
    }

    private fun sha(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
