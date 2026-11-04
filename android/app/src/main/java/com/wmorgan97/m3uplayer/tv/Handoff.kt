package com.wmorgan97.m3uplayer.tv

import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.wmorgan97.m3uplayer.PlayerActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * "Play on TV": the phone puts one request on the server ({ id, kind, url, name, t, sent… }, server/sync.js `handoff`).
 * While this app is open (any screen, or the player) it asks for just that every few seconds (GET /sync?handoff=1, a few
 * hundred bytes), and plays a request once: only when it's under a minute old by the server's clock, never one it has
 * already played, and never one another TV took. Films and episodes start where the phone was (the resume rule: from
 * 5 s in, unless finished); live is that channel, in its group. A small toast says where it came from.
 */
object Handoff {
    const val FRESH_MS = 60_000L
    private const val POLL_MS = 3_000L

    data class Request(val id: String, val kind: String, val url: String, val name: String, val logo: String, val group: String,
                       val t: Double, val d: Double, val from: String, val sent: Long, val taken: String, val item: JSONObject)

    fun parse(o: JSONObject?): Request? {
        o ?: return null
        val id = o.optString("id"); val url = o.optString("url")
        if (id.isEmpty() || !(url.startsWith("http://") || url.startsWith("https://"))) return null
        return Request(id, o.optString("kind", "movie"), url, o.optString("name"), o.optString("logo"), o.optString("group"),
            o.optDouble("t", 0.0).takeIf { !it.isNaN() } ?: 0.0, o.optDouble("d", 0.0).takeIf { !it.isNaN() } ?: 0.0,
            o.optString("from").ifEmpty { "your phone" }, o.optLong("sent"), o.optString("taken"), o)
    }

    /** The request in a poll reply to act on now, or null (none, already handled, taken, or stale). */
    fun due(view: JSONObject, lastId: String?): Request? {
        val r = parse(view.optJSONObject("handoff")) ?: return null
        return r.takeIf { isDue(it.id, it.taken, it.sent, view.optLong("now", System.currentTimeMillis()), lastId) }
    }

    /** Act once: not the one handled last, not taken by another TV, and under a minute old by the server's clock. */
    fun isDue(id: String, taken: String, sent: Long, now: Long, lastId: String?): Boolean =
        id.isNotEmpty() && id != lastId && taken.isEmpty() && (now - sent) in -5_000 until FRESH_MS

    /** Where a film / episode starts: the phone's point, by the same rule as Continue watching (5 s in, not finished). */
    fun startFor(t: Double, d: Double): Double = if (t > RESUME_MIN_S && !(d > 0 && com.wmorgan97.m3uplayer.tv.player.Resume.finished(t, d))) t else 0.0
    fun startFor(r: Request): Double = startFor(r.t, r.d)

    /** Polls while the app is in front (TvActivity started, or its player open). Call once, from TvActivity.onCreate. */
    fun start(act: TvActivity) {
        val prefs = act.getSharedPreferences("handoff", Context.MODE_PRIVATE)
        act.lifecycleScope.launch {
            while (true) {
                delay(POLL_MS)
                if (!Repo.hasKey()) continue
                val front = act.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) || PlayerActivity.current?.isFinishing == false
                if (!front) continue
                val view = withContext(Dispatchers.IO) { runCatching { Repo.handoffPoll() }.getOrNull() } ?: continue
                val r = due(view, prefs.getString("last", null)) ?: continue
                prefs.edit().putString("last", r.id).apply()          // once, even if the app restarts mid-way
                withContext(Dispatchers.IO) { Repo.handoffTaken(r.id) }
                runCatching { play(act, r) }.onFailure { android.util.Log.w("M3U", "handoff play failed", it) }
            }
        }
    }

    private suspend fun play(act: TvActivity, r: Request) {
        android.widget.Toast.makeText(act.applicationContext, "Playing from your ${r.from}", android.widget.Toast.LENGTH_SHORT).show()
        if (r.kind == "live") {
            if (Repo.live == null) withContext(Dispatchers.IO) { runCatching { Repo.liveChannels() } }
            val c = Repo.channelByUrl(r.url) ?: Channel(r.name, r.url, r.group, r.logo, r.item.optString("tvgId"), "", 0)
            val group = Repo.live?.channels?.filter { it.group == c.group && c.group.isNotEmpty() }?.takeIf { it.contains(c) } ?: listOf(c)
            act.playLive(group, group.indexOf(c).coerceAtLeast(0))
            return
        }
        // Films, episodes, catch-up: one item, like Continue watching. Catch-up links are archive recordings: no history.
        val sync = JSONObject().put("kind", r.kind).put("name", r.name).put("url", r.url).put("logo", r.logo).put("group", r.group)
        r.item.optString("src").takeIf { it.isNotEmpty() }?.let { sync.put("src", it) }
        if (r.kind == "episode") { r.item.opt("season")?.let { sync.put("season", it) }; r.item.opt("num")?.let { sync.put("num", it) } }
        val title = if (r.kind == "catchup") r.name else progressTitle(sync)
        val item = JSONObject().put("url", r.url).put("title", title).put("subtitle", if (r.kind == "episode") epTitle(r.name) else "")
            .put("live", false).put("start", startFor(r)).put("art", r.logo).put("listTitle", title)
        if (r.kind != "catchup") item.put("sync", sync)
        act.play(listOf(item), 0)
    }
}
