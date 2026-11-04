package com.wmorgan97.m3uplayer.tv

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** The few hundred channels the Sports page last used (those showing a game, the club channels, the sports channels),
 *  saved on the TV. After a cold start the page works out its cards from these at once, while the full ~19k-channel
 *  playlist is still being read (or downloaded again); the page is worked out afresh when that's in. */
object SportsSnapshot {
    private const val TAG = "M3U"
    @Volatile private var savedSig = 0
    @Volatile private var mem: Pair<String, Repo.Live>? = null

    private fun file(c: Context) = File(c.filesDir, "sports-chans" + (if (Repo.sportsAll) "-all" else "") + ".json")

    /** The saved channels as a small list, or null (never saved, or unreadable). */
    fun load(c: Context): Repo.Live? {
        val name = file(c).name
        mem?.takeIf { it.first == name }?.let { return it.second }
        return runCatching {
            val f = file(c); if (!f.exists()) return null
            val o = JSONObject(f.readText())
            val a = o.getJSONArray("channels")
            val chans = (0 until a.length()).map { i ->
                val j = a.getJSONObject(i)
                val h = j.optJSONObject("h")
                Channel(j.optString("n"), j.optString("u"), j.optString("g"), j.optString("l"), j.optString("id"), j.optString("tn"), j.optInt("num"),
                    h?.let { x -> x.keys().asSequence().associateWith { x.optString(it) } } ?: emptyMap(),
                    j.optInt("cd"), j.optString("cm"), j.optString("cs"))
            }
            val epg = o.optJSONArray("epg")?.let { e -> (0 until e.length()).map { e.getString(it) } }.orEmpty()
            Repo.Live(chans, chans.map { it.group }.distinct(), epg).also { mem = name to it }
        }.onFailure { Log.w(TAG, "sports snapshot unreadable: ${it.javaClass.simpleName}") }.getOrNull()
    }

    /** Saves [chans] (deduplicated, at most 2000) when they differ from what was saved last. Call off the main thread. */
    fun save(c: Context, l: Repo.Live, chans: Collection<Channel>) {
        val list = LinkedHashMap<String, Channel>().apply { for (ch in chans) putIfAbsent(ch.url, ch) }.values.take(2000)
        if (list.isEmpty()) return
        val sig = list.fold(file(c).name.hashCode()) { h, ch -> h * 31 + ch.url.hashCode() } * 31 + l.epgUrls.hashCode()
        if (sig == savedSig) return
        runCatching {
            val a = JSONArray()
            for (ch in list) a.put(JSONObject().put("n", ch.name).put("u", ch.url).put("g", ch.group).put("l", ch.logo).put("id", ch.tvgId).put("tn", ch.tvgName).put("num", ch.num)
                .apply { if (ch.headers.isNotEmpty()) put("h", JSONObject(ch.headers as Map<*, *>)); if (ch.catchupDays > 0) put("cd", ch.catchupDays).put("cm", ch.catchupMode).put("cs", ch.catchupSource) })
            val f = file(c); val tmp = File(f.path + ".tmp")
            tmp.writeText(JSONObject().put("channels", a).put("epg", JSONArray(l.epgUrls)).toString())
            tmp.renameTo(f)
            savedSig = sig; mem = null
            SportsPerf.mark("snapshot saved (${list.size} channels)")
        }.onFailure { Log.w(TAG, "sports snapshot not saved: ${it.javaClass.simpleName}") }
    }
}
