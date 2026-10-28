package com.wmorgan97.m3uplayer.tv

import android.content.Context
import android.util.Log
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
                   val num: Int, val headers: Map<String, String> = emptyMap(),
                   val catchupDays: Int = 0, val catchupMode: String = "", val catchupSource: String = "") {
    val title get() = name
    /** Whether the provider keeps past programmes of this channel (catch-up / archive). */
    val hasCatchup get() = catchupDays > 0
}

data class Reminder(val url: String, val channel: String, val title: String, val start: Long)

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
    /** Channel numbers in lists, the guide and the player banner (off by default: provider numbers like 2710 mean little). */
    var showNumbers: Boolean get() = prefs.getBoolean("showNumbers", false); set(v) { prefs.edit().putBoolean("showNumbers", v).apply(); version++ }
    var preview: Boolean get() = prefs.getBoolean("preview", true); set(v) { prefs.edit().putBoolean("preview", v).apply(); version++ }
    var fourKFirst: Boolean get() = prefs.getBoolean("fourK", true); set(v) = prefs.edit().putBoolean("fourK", v).apply()
    var lastChannelUrl: String? get() = prefs.getString("lastChannel", null); set(v) = prefs.edit().putString("lastChannel", v).apply()

    // ---------- this TV's own choices: parental lock, hidden / ordered groups, hidden channels, reminders ----------
    private fun strSet(k: String): Set<String> = prefs.getStringSet(k, emptySet())?.toSet() ?: emptySet()
    private fun putSet(k: String, v: Set<String>) { prefs.edit().putStringSet(k, v).apply(); version++ }
    /** The parental PIN, kept only as a salted hash ("" = no PIN). Set it with the plain digits. */
    var pin: String get() = prefs.getString("pinHash", "") ?: ""
        set(v) {
            // Removing the PIN also forgets which groups were locked, so a later PIN doesn't silently re-lock them.
            val e = prefs.edit().putString("pinHash", if (v.isEmpty()) "" else sha("m3u-pin|$v")).remove("pin")
            if (v.isEmpty()) { e.remove("lockedGroups"); unlocked.clear() }
            e.apply(); version++
        }
    fun checkPin(p: String) = pin.isNotEmpty() && sha("m3u-pin|$p") == pin
    /** A channel whose group is locked (and not unlocked this session): playing it asks for the PIN. */
    fun isChannelLocked(c: Channel) = isLocked(c.group)
    val lockedGroups get() = strSet("lockedGroups")
    fun toggleLock(g: String) = putSet("lockedGroups", lockedGroups.let { if (g in it) it - g else it + g })
    /** Groups unlocked with the PIN until the app is closed. */
    val unlocked: MutableSet<String> = ConcurrentHashMap.newKeySet()
    fun isLocked(g: String) = pin.isNotEmpty() && g in lockedGroups && g !in unlocked
    val hiddenGroups get() = strSet("hiddenGroups")
    fun toggleHidden(g: String) = putSet("hiddenGroups", hiddenGroups.let { if (g in it) it - g else it + g })
    /** Groups moved to the top of the list, in the order they were moved. */
    val pinnedGroups: List<String> get() = (prefs.getString("pinnedGroups", "") ?: "").split('\u0001').filter { it.isNotEmpty() }
    fun togglePinned(g: String) { val l = pinnedGroups.toMutableList(); if (!l.remove(g)) l += g; prefs.edit().putString("pinnedGroups", l.joinToString("\u0001")).apply(); version++ }
    val hiddenChannels get() = strSet("hiddenChannels")
    fun toggleHiddenChannel(url: String) = putSet("hiddenChannels", hiddenChannels.let { if (url in it) it - url else it + url })
    fun showAllHidden() { prefs.edit().remove("hiddenGroups").remove("hiddenChannels").apply(); version++ }
    /** The group list as arranged on this TV: moved-to-top first, hidden ones left out. */
    fun arrange(groups: List<String>): List<String> { val hid = hiddenGroups; val pins = pinnedGroups.filter { it in groups && it !in hid }; return pins + groups.filter { it !in pins && it !in hid } }

    var reminders: List<Reminder>
        get() = runCatching { JSONArray(prefs.getString("reminders", "[]")).let { a -> (0 until a.length()).map { val o = a.getJSONObject(it); Reminder(o.optString("url"), o.optString("channel"), o.optString("title"), o.optLong("start")) } } }.getOrDefault(emptyList())
        set(v) { prefs.edit().putString("reminders", JSONArray(v.map { JSONObject().put("url", it.url).put("channel", it.channel).put("title", it.title).put("start", it.start) }).toString()).apply(); version++ }
    fun hasReminder(url: String, start: Long) = reminders.any { it.url == url && it.start == start }
    fun toggleReminder(c: Channel, p: Programme) {
        val l = reminders.filter { it.start > System.currentTimeMillis() - 3600_000L }
        reminders = if (l.any { it.url == c.url && it.start == p.start }) l.filterNot { it.url == c.url && it.start == p.start } else l + Reminder(c.url, c.name, p.title, p.start)
    }

    /** False while the server can't be reached: screens show the saved copies and say so. */
    private val onlineState = androidx.compose.runtime.mutableStateOf(true)
    val online: Boolean get() = onlineState.value
    private fun markOnline(ok: Boolean) { if (onlineState.value != ok) onlineState.value = ok }
    /** Set when no playlist could be loaded (every source failed and none has a saved copy): a short, human-readable
     *  reason without links or logins. Null once a load succeeds. */
    private val liveErrorState = androidx.compose.runtime.mutableStateOf<String?>(null)
    val liveError: String? get() = liveErrorState.value
    /** Set when the last sync with the server failed (human-readable, no links); null after a successful sync. */
    private val lastSyncErrorState = androidx.compose.runtime.mutableStateOf<String?>(null)
    val lastSyncError: String? get() = lastSyncErrorState.value

    private const val TAG = "M3U"
    /** Logs a failure without its message (which can quote links or server replies), only the redacted link. */
    private fun warn(what: String, e: Throwable, url: String? = null) =
        Log.w(TAG, what + (url?.let { " (" + redactUrl(it) + ")" } ?: "") + ": " + e.javaClass.simpleName)
    private class HttpError(val code: Int) : Exception("HTTP $code")
    /** What went wrong, in words for the screen. */
    private fun describe(e: Throwable, who: String): String = when (e) {
        is java.net.UnknownHostException, is java.net.ConnectException, is java.net.NoRouteToHostException -> "Couldn't reach $who."
        is java.net.SocketTimeoutException -> "$who took too long to answer.".replaceFirstChar { it.uppercase() }
        is HttpError -> when (e.code) { 401, 403 -> "$who refused the login.".replaceFirstChar { it.uppercase() }; 404 -> "$who doesn't have that list any more.".replaceFirstChar { it.uppercase() }; else -> "$who replied with an error (${e.code}).".replaceFirstChar { it.uppercase() } }
        is java.io.IOException -> "The connection to $who failed."
        else -> "The list from $who couldn't be read."
    }

    /** Bumped whenever favourites / cached data change, so screens showing them redraw. */
    private val versionState = androidx.compose.runtime.mutableIntStateOf(0)
    var version: Int get() = versionState.intValue; set(v) { versionState.intValue = v }
    fun clearCache() { app.filesDir.listFiles()?.forEach { if (it.name.startsWith("vod-") || it.name.startsWith("live-") || it.name.startsWith("group-") || it.name.startsWith("eps-") || it.name.startsWith("tmdb-") || it.name == "guide.json") it.delete() }; overviews.clear(); groupCache.clear(); live = null; version++ }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    fun api(path: String) = "$server/$path?key=${enc(key)}"
    /** The original picture address behind one of our server's /img links (null if it isn't one). */
    fun imgOriginal(url: String?): String? = url?.takeIf { it.startsWith("$server/img?") }?.substringAfter("&u=", "")?.substringBefore('&')?.ifEmpty { null }?.let { java.net.URLDecoder.decode(it, "UTF-8") }
    /** Warms the picture caches with these channels' logos (same address and size the rows use). */
    fun prefetchLogos(c: Context, chans: List<Channel>) {
        if (com.wmorgan97.m3uplayer.PlayerActivity.current != null) return   // never while watching full screen
        val loader = coil.Coil.imageLoader(c)
        for (ch in chans) img(ch.logo, 120)?.let { loader.enqueue(coil.request.ImageRequest.Builder(c).data(it).size(120, 72).build()) }
    }
    fun img(url: String?, w: Int = 300): String? = if (url.isNullOrBlank()) null else if (!url.startsWith("http")) url else "$server/img?key=${enc(key)}&w=$w&u=${enc(url)}"

    private fun http(url: String, body: String? = null, timeout: Int = 30_000): String {
        if (!url.startsWith(server)) return httpRaw(url, body, timeout)
        // Only "couldn't reach it" counts as offline; a reply with an error code still means the server is there.
        return try { httpRaw(url, body, timeout).also { markOnline(true) } } catch (e: java.io.IOException) { markOnline(false); throw e }
    }
    /** Like http(), but hands the reply to [read] as a stream (big replies, e.g. the guide, never become one string). */
    private fun <T> httpStream(url: String, body: String?, timeout: Int, read: (java.io.Reader) -> T): T {
        try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 15_000; c.readTimeout = timeout
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android TV) M3UPlayer")
            if (body != null) { c.requestMethod = "POST"; c.doOutput = true; c.setRequestProperty("Content-Type", "application/json"); c.outputStream.use { it.write(body.toByteArray()) } }
            val code = c.responseCode
            if (code !in 200..299) { c.errorStream?.close(); throw HttpError(code) }
            return c.inputStream.bufferedReader().use { r -> read(r) }.also { if (url.startsWith(server)) markOnline(true) }
        } catch (e: java.io.IOException) { if (url.startsWith(server) && e !is HttpError) markOnline(false); throw e }
    }
    private fun httpRaw(url: String, body: String?, timeout: Int): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000; c.readTimeout = timeout
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android TV) M3UPlayer")
        if (body != null) { c.requestMethod = "POST"; c.doOutput = true; c.setRequestProperty("Content-Type", "application/json"); c.outputStream.use { it.write(body.toByteArray()) } }
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        if (code !in 200..299) throw HttpError(code)
        return text
    }

    // ---------- sync: logins, favourites, history, progress ----------

    @Volatile var syncData: JSONObject = JSONObject()
    suspend fun sync(): JSONObject = withContext(Dispatchers.IO) {
        val d = try { JSONObject(http(api("sync"), timeout = 15_000)) } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            warn("sync failed", e)
            lastSyncErrorState.value = if (e is org.json.JSONException) "Your server's reply couldn't be read." else describe(e, "your server")
            throw e
        }
        lastSyncErrorState.value = null
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
        val items = (0 until r.length()).mapNotNull { r.optJSONObject(it) }.filter { val u = it.optString("url"); !u.contains("/movie/") && !u.contains("/series/") }
        val known = live?.urls
        return tidyRecents(items, { it.optString("url") }, { it.optLong("at") }, known?.let { k -> { u: String -> u in k } }, 20)
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
        try { syncData = JSONObject(http(api("sync"), body.toString())) } catch (e: Exception) { warn("saving favourites failed", e) }
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
        try { syncData = JSONObject(http(api("sync"), JSONObject().put("progress", JSONObject().put(url, entry)).toString())) } catch (e: Exception) { warn("saving progress failed", e) }
    }
    suspend fun saveRecent(item: JSONObject) = withContext(Dispatchers.IO) {
        try { syncData = JSONObject(http(api("sync"), JSONObject().put("recents", JSONArray().put(JSONObject(item.toString()).put("at", System.currentTimeMillis()))).toString())) } catch (e: Exception) { warn("saving recent failed", e) }
    }

    // ---------- live channels ----------

    // "Prefer 4K": the 4K copy of each channel by normalised name, built once with the channel list (off the main thread).
    // Looking it up by scanning ~19k channels on every channel start froze the screen for ~2 s on the Shield.
    @Volatile private var uhdIndex: Map<String, Channel> = emptyMap()
    fun uhdFor(c: Channel): Channel? = if (isUhdName(c.name)) null else uhdIndex[liveBase(c.name)]?.takeIf { it.url != c.url }

    data class Live(val channels: List<Channel>, val groups: List<String>, val epgUrls: List<String>) {
        val urls: Set<String> by lazy { channels.mapTo(HashSet()) { it.url } }
    }
    @Volatile var live: Live? = null

    /** The live channels of every playlist: the saved copy at once, a fresh download when it's over 12 hours old. */
    suspend fun liveChannels(refresh: Boolean = false): Live = withContext(Dispatchers.IO) {
        live?.takeIf { !refresh }?.let { return@withContext it }
        val sources = syncData.optJSONObject("sources") ?: JSONObject()
        val all = ArrayList<Channel>(); val epg = ArrayList<String>()
        var tried = 0; var loaded = 0; var firstError: String? = null
        fun failed(what: String, e: Exception, url: String?, who: String) { warn(what, e, url); if (firstError == null) firstError = describe(e, who) }
        for (k in sources.keys()) {
            val v = sources.optJSONObject(k) ?: continue
            if (v.optBoolean("deleted") || v.optString("content") == "vod") continue
            when (v.optString("kind")) {
                "url" -> {
                    val src = v.optString("sourceUrl"); if (src.isEmpty()) continue
                    tried++
                    val file = File(app.filesDir, "live-" + sha(src).take(16) + ".m3u")
                    val stale = !file.exists() || System.currentTimeMillis() - file.lastModified() > 12 * 3600_000L
                    if (stale) try {
                        val text = http(src, timeout = 120_000)
                        if (text.contains("#EXTINF")) file.writeText(text) else throw IllegalStateException("not a playlist")
                    } catch (e: Exception) { failed("playlist download failed", e, src, "the playlist provider") }
                    if (file.exists()) try { val (chs, hdr) = parseM3U(file.readText(), all.size); all += chs; epg += hdr; loaded++ }
                        catch (e: Exception) { failed("playlist parse failed", e, src, "the playlist provider") }
                }
                "xtream" -> {
                    val x = v.optJSONObject("xtream") ?: continue
                    val xs = Xtream(x.optString("server").trimEnd('/'), x.optString("user"), x.optString("pass"))
                    // Saved on the TV and refreshed twice a day, like playlist files: the channel list opens at once
                    // and still works when the provider's API is slow or down.
                    val base = "live-x-" + sha(xs.server + "|" + xs.user).take(12)
                    val fc = File(app.filesDir, "$base-cats.json"); val fs = File(app.filesDir, "$base-streams.json")
                    tried++
                    if (!fs.exists() || System.currentTimeMillis() - fs.lastModified() > 12 * 3600_000L) try {
                        val c = http(playerApi(xs, "get_live_categories")); val l = http(playerApi(xs, "get_live_streams"), timeout = 120_000)
                        if (l.trimStart().startsWith("[")) { fc.writeText(c); fs.writeText(l) } else throw IllegalStateException("not a channel list")
                    } catch (e: Exception) { failed("Xtream download failed", e, xs.server, "the Xtream provider") }
                    if (fs.exists()) try {
                        val cats = JSONArray(fc.readText())
                        val names = (0 until cats.length()).associate { cats.getJSONObject(it).optString("category_id") to cats.getJSONObject(it).optString("category_name") }
                        val list = JSONArray(fs.readText())
                        for (i in 0 until list.length()) {
                            val s = list.getJSONObject(i)
                            val days = if (s.optInt("tv_archive") == 1) s.optInt("tv_archive_duration", 3).coerceAtLeast(1) else 0
                            all += Channel(s.optString("name"), "${xs.server}/live/${xs.user}/${xs.pass}/${s.optLong("stream_id")}.ts", names[s.optString("category_id")] ?: "",
                                s.optString("stream_icon"), s.optString("epg_channel_id"), s.optString("name"), all.size + 1, catchupDays = days, catchupMode = if (days > 0) "xc" else "")
                        }
                        epg += "${xs.server}/xmltv.php?username=${enc(xs.user)}&password=${enc(xs.pass)}"
                        loaded++
                    } catch (e: Exception) { failed("Xtream channel list unreadable", e, xs.server, "the Xtream provider") }
                }
            }
        }
        liveErrorState.value = if (tried > 0 && loaded == 0) (firstError ?: "Your playlists couldn't be loaded.") else null
        val groups = LinkedHashSet<String>(); all.forEach { groups += it.group }
        Live(all, groups.toList(), epg.distinct()).also { live = it
            val m = HashMap<String, Channel>()
            for (c in all) if (isUhdName(c.name) && !c.name.trimStart().startsWith("#")) liveBase(c.name).takeIf { it.isNotEmpty() }?.let { m.putIfAbsent(it, c) }
            uhdIndex = m }
    }

    // The playlist parser and catch-up links live in Parsing.kt (pure, unit-tested).

    /** The link for a past programme on a channel with an archive (see Parsing.kt). */
    fun catchupUrl(c: Channel, start: Long, stop: Long, now: Long = System.currentTimeMillis()): String? =
        com.wmorgan97.m3uplayer.tv.catchupUrl(c, start, stop, now)

    // ---------- TV guide ----------

    private val programmes = ConcurrentHashMap<String, List<Programme>>()
    private val guideNames = ConcurrentHashMap<String, String>()
    private val asked = ConcurrentHashMap<String, Long>()
    private fun chanKey(c: Channel) = c.tvgId.trim().lowercase().ifEmpty { normName(c.tvgName.ifEmpty { c.name }) }
    /** A channel's programmes, sorted by start with no duplicates or overlaps (tidied once, when the data arrives). */
    fun guideFor(c: Channel): List<Programme> = guideKeyFor(c, programmes, guideNames)?.let { programmes[it] } ?: emptyList()
    fun nowNext(c: Channel, t: Long = System.currentTimeMillis()): Pair<Programme?, Programme?> = nowNextIn(guideFor(c), t)

    private const val KEEP_PAST = 6 * 3600_000L
    private const val KEEP_AHEAD = 30 * 3600_000L   // the guide keeps 6 h back to 30 h ahead in memory (the server sends ~100 h)
    private const val SAVED_GUIDE_MAX_AGE = 36 * 3600_000L
    private fun parseProgrammes(arr: JSONArray, cutoff: Long) =
        tidyProgrammes((0 until arr.length()).map { val a = arr.getJSONArray(it); Programme(a.optLong(0), a.optLong(1), a.optString(2), a.optString(3)) }, cutoff)
    /** Drops programmes that ended over 6 hours ago from memory (channels left with none are forgotten). */
    private fun pruneGuide(cutoff: Long) {
        for ((k, l) in programmes) { val kept = dropFinished(l, cutoff); if (kept.isEmpty()) programmes.remove(k, l) else if (kept.size != l.size) programmes.replace(k, l, kept) }
    }

    // The guide is saved on the TV (6 hours back to 30 ahead), so it shows at once on the next start and while the server is away.
    private var guideSave: kotlinx.coroutines.Job? = null
    private fun saveGuideSoon() {
        if (guideSave?.isActive == true) return   // one save at a time, at most every 30 s (it used to rebuild the whole guide as one string every few seconds)
        guideSave = guideScope.launch {
            kotlinx.coroutines.delay(30_000)
            val now = System.currentTimeMillis()
            runCatching {
                val tmp = File(app.filesDir, "guide.json.tmp")
                android.util.JsonWriter(tmp.bufferedWriter()).use { w ->
                    w.beginObject(); w.name("savedAt").value(now); w.name("programmes").beginObject()
                    for ((k, l) in programmes.entries.toList()) {
                        val keep = l.filter { it.end > now - KEEP_PAST && it.start < now + KEEP_AHEAD }
                        if (keep.isEmpty()) continue
                        w.name(k).beginArray(); for (it in keep) { w.beginArray(); w.value(it.start); w.value(it.end); w.value(it.title); w.value(it.desc.take(400)); w.endArray() }; w.endArray()
                    }
                    w.endObject(); w.name("names").beginObject(); for ((k, v) in guideNames.entries.toList()) w.name(k).value(v); w.endObject(); w.endObject()
                }
                tmp.renameTo(File(app.filesDir, "guide.json"))
            }.onFailure { warn("saving the guide failed", it) }
        }
    }
    @Volatile private var guideLoaded = false
    /** Reads the saved guide (once, in the background) so rows show what's on before the server answers. */
    fun loadSavedGuide() {
        if (guideLoaded) return; guideLoaded = true
        guideScope.launch {
            runCatching {
                val f = File(app.filesDir, "guide.json"); if (!f.exists()) return@runCatching
                val now = System.currentTimeMillis()
                if (now - f.lastModified() > SAVED_GUIDE_MAX_AGE) return@runCatching   // too old to trust; wait for the server
                val cutoff = now - KEEP_PAST
                pruneGuide(cutoff)
                android.util.JsonReader(f.bufferedReader()).use { r -> readGuide(r, cutoff, now, keepExisting = true) }
                guideState.intValue++
            }.onFailure { warn("reading the saved guide failed", it) }
        }
    }

    /** Bumped when guide data arrives: anything showing what's on redraws. */
    private val guideState = androidx.compose.runtime.mutableIntStateOf(0)
    val guideVersion: Int get() = guideState.intValue
    private val guideScope = kotlinx.coroutines.CoroutineScope(Dispatchers.IO + kotlinx.coroutines.SupervisorJob())

    /** Asks the server for the guide of these channels (the ones on screen), 3-hourly at most per channel. Runs in its
     *  own scope, so moving to another group never cancels a download half-way (which left channels marked as asked). */
    fun requestGuide(channels: List<Channel>) {
        // One download at a time: requests made meanwhile are merged (newest first, capped), so holding Down through the
        // groups no longer starts a download per group (that ran the Shield out of memory).
        synchronized(guidePending) {
            val merged = LinkedHashMap<String, Channel>(); for (c in channels + guidePending) merged.putIfAbsent(c.url, c)
            guidePending.clear(); guidePending += merged.values.take(600)
            if (guideWorker?.isActive == true) return
            guideWorker = guideScope.launch {
                // Background work: low CPU priority, and it waits while a channel is playing full screen so it never takes
                // bandwidth or CPU from the picture (the guide catches up the moment you come back).
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
                while (com.wmorgan97.m3uplayer.PlayerActivity.current != null) kotlinx.coroutines.delay(5_000)
                while (true) {
                    val next = synchronized(guidePending) { if (guidePending.isEmpty()) { guideWorker = null; null } else guidePending.toList().also { guidePending.clear() } } ?: break
                    if (loadGuide(next)) guideState.intValue++
                }
            }
        }
    }
    private val guidePending = ArrayList<Channel>()
    @Volatile private var guideWorker: kotlinx.coroutines.Job? = null

    /** Reads a guide object ({programmes:{key:[[start,end,title,desc]…]}, names:{…}}) as a stream, keeping only the window shown. */
    private fun readGuide(r: android.util.JsonReader, cutoff: Long, now: Long, keepExisting: Boolean) {
        r.beginObject()
        while (r.hasNext()) when (r.nextName()) {
            "programmes" -> { r.beginObject(); while (r.hasNext()) {
                val k = r.nextName(); val l = ArrayList<Programme>()
                r.beginArray(); while (r.hasNext()) {
                    r.beginArray(); val s = r.nextLong(); val e = r.nextLong(); val t = if (r.peek() == android.util.JsonToken.NULL) { r.nextNull(); "" } else r.nextString()
                    val d = if (r.hasNext() && r.peek() != android.util.JsonToken.NULL) r.nextString() else { if (r.hasNext()) r.nextNull(); "" }
                    while (r.hasNext()) r.skipValue(); r.endArray()
                    if (e > cutoff && s < now + KEEP_AHEAD) l += Programme(s, e, t, if (d.length > 600) d.take(600) else d)
                }; r.endArray()
                if (k.isEmpty() || (keepExisting && programmes.containsKey(k))) continue
                val tidy = tidyProgrammes(l, cutoff); if (tidy.isEmpty()) programmes.remove(k) else programmes[k] = tidy
            }; r.endObject() }
            "names" -> { r.beginObject(); while (r.hasNext()) { val k = r.nextName(); val v = r.nextString(); if (k.isNotEmpty()) { if (keepExisting) guideNames.putIfAbsent(k, v) else guideNames[k] = v } }; r.endObject() }
            else -> r.skipValue()
        }
        r.endObject()
    }

    suspend fun loadGuide(channels: List<Channel>): Boolean = withContext(Dispatchers.IO + kotlinx.coroutines.NonCancellable) {
        val urls = live?.epgUrls ?: return@withContext false
        if (urls.isEmpty()) return@withContext false
        val now = System.currentTimeMillis()
        val want = channels.filter { val k = chanKey(it); k.isNotEmpty() && (asked[k] ?: 0) < now - 3 * 3600_000L }
        if (want.isEmpty()) return@withContext false
        want.forEach { asked[chanKey(it)] = now }
        var got = false
        for (batch in want.chunked(200)) {
            val ids = JSONArray(); val names = JSONArray()
            batch.forEach { c ->
                val id = c.tvgId.trim().lowercase(); if (id.isNotEmpty()) ids.put(id)
                normName(c.name).takeIf { it.isNotEmpty() }?.let { names.put(it) }
                if (c.tvgName.isNotEmpty()) normName(c.tvgName).takeIf { it.isNotEmpty() }?.let { names.put(it) }
            }
            try {
                val now2 = System.currentTimeMillis(); val cutoff = now2 - KEEP_PAST
                httpStream(api("guide"), JSONObject().put("urls", JSONArray(urls)).put("ids", ids).put("names", names).toString(), 60_000) { rd ->
                    android.util.JsonReader(rd).use { readGuide(it, cutoff, now2, keepExisting = false) }
                }
                pruneGuide(cutoff)
                got = true
                saveGuideSoon()
            } catch (e: Exception) { warn("guide batch failed", e); batch.forEach { asked.remove(chanKey(it)) } }
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
                try { file.writeText(http(api("vod"), vodBody(x, kind).toString(), 120_000)); overviews.remove(kind); groupCache.clear(); version++ } catch (e: Exception) { warn("VOD refresh failed", e) } finally { refreshing.remove(kind) }
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
        // Saved per row: a copy under 6 hours old is used as is, an older one when the server can't be reached.
        val file = File(app.filesDir, "group-$kind-" + sha(tmdbId).take(12) + ".json")
        val text = if (file.exists() && System.currentTimeMillis() - file.lastModified() < 6 * 3600_000L) file.readText()
            else try { http(api("vod"), vodBody(x, kind).put("tmdb", tmdbId).toString(), 60_000).also { file.writeText(it) } } catch (e: Exception) { if (file.exists()) file.readText() else throw e }
        tidy(arr(JSONObject(text), "items", kind, cats)).also { groupCache["$kind|$tmdbId"] = it }
    }

    suspend fun search(q: String): Pair<List<Vod>, List<Vod>> = coroutineScope {
        val x = xtream() ?: return@coroutineScope emptyList<Vod>() to emptyList()
        val r = listOf("movies", "series").map { kind ->
            async(Dispatchers.IO) {
                try {
                    val cats = overview(kind)?.catNames ?: emptyMap()
                    tidy(arr(JSONObject(http(api("vod"), vodBody(x, kind).put("q", q).toString())), "items", kind, cats)).take(40)
                } catch (e: Exception) { warn("search failed", e); emptyList() }
            }
        }.awaitAll()
        r[0] to r[1]
    }

    fun movieUrl(v: Vod): String? = xtream()?.let { "${it.server}/movie/${it.user}/${it.pass}/${v.id}.${v.ext}" }

    suspend fun episodes(v: Vod): Map<Int, List<Episode>> = withContext(Dispatchers.IO) {
        val x = xtream() ?: return@withContext emptyMap()
        val file = File(app.filesDir, "eps-${v.id}.json")
        val text = try { http(playerApi(x, "get_series_info", "&series_id=${v.id}"), timeout = 60_000).also { file.writeText(it) } } catch (e: Exception) { if (file.exists()) file.readText() else throw e }
        val d = JSONObject(text)
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
        // Film details hardly ever change: kept on the TV for 30 days, so a details page opens at once the second time.
        val file = File(app.filesDir, "tmdb-" + sha(k).take(16) + ".json")
        if (file.exists() && System.currentTimeMillis() - file.lastModified() < 30 * 86_400_000L)
            runCatching { JSONObject(file.readText()) }.getOrNull()?.let { tmdb[k] = it; return@withContext it }
        try {
            val d = JSONObject(http(api("tmdb/info") + "&kind=${if (v.kind == "series") "tv" else "movie"}&name=${enc(v.title)}&year=${v.year ?: ""}"))
            if (d.has("tid")) d.also { tmdb[k] = it; runCatching { file.writeText(it.toString()) } } else null
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            if (file.exists()) runCatching { JSONObject(file.readText()) }.getOrNull()?.also { tmdb[k] = it } else null   // offline: an older copy
        }
    }


    private fun sha(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
