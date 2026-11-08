package com.wmorgan97.m3uplayer.tv

/** One hidden / shown mark as /sync keeps it: [hidden] false = "shown again" marker. Newest [at] wins (server/sync.js mergeHidden). */
data class HEntry(val hidden: Boolean, val at: Long)

/**
 * Hidden groups and channels shared through /sync's `hidden` map (per viewer profile). Keys: "g\u0001<playlist key>\u0001<group>"
 * ("*" = every playlist, which is what this TV writes: it has no per-playlist hiding) and "c\u0001<channel url>".
 * The web app writes a group under its playlist's key; here a group is hidden when the newest entry for its name,
 * from any playlist, says hidden.
 */
object HiddenSync {
    const val SEP = '\u0001'
    fun groupKey(g: String) = "g$SEP*$SEP$g"
    fun channelKey(url: String) = "c$SEP$url"
    /** The group name in a "g" key, or null for anything else. */
    fun groupOf(key: String): String? = if (key.startsWith("g$SEP")) key.substring(2).substringAfter(SEP, "").takeIf { key.count { it == SEP } >= 2 } else null
    fun channelOf(key: String): String? = if (key.startsWith("c$SEP")) key.substring(2) else null

    /** What this TV sends: every hidden item (stamp, or 1 for hides from before sync existed) and every stamped "shown again". */
    fun local(groups: Set<String>, chans: Set<String>, at: Map<String, Long>): Map<String, HEntry> {
        val out = HashMap<String, HEntry>()
        for (g in groups) out[groupKey(g)] = HEntry(true, at[groupKey(g)] ?: 1L)
        for (c in chans) out[channelKey(c)] = HEntry(true, at[channelKey(c)] ?: 1L)
        for ((k, t) in at) if (k !in out) out[k] = HEntry(false, t)
        return out
    }

    data class Result(val groups: Set<String>, val chans: Set<String>, val at: Map<String, Long>, val changed: Boolean)

    /** Takes in the server's map: per item the newer edit wins, so a hide or a show on another device arrives here. */
    fun adopt(groups: Set<String>, chans: Set<String>, at: Map<String, Long>, remote: Map<String, HEntry>): Result {
        val g = groups.toMutableSet(); val c = chans.toMutableSet(); val t = at.toMutableMap(); var changed = false
        val newestG = HashMap<String, HEntry>()
        for ((k, e) in remote) { val n = groupOf(k) ?: continue; val o = newestG[n]; if (o == null || e.at > o.at || (e.at == o.at && e.hidden)) newestG[n] = e }
        for ((n, e) in newestG) {
            val key = groupKey(n)
            if (e.at <= (t[key] ?: if (n in g) 1L else 0L)) continue
            if (e.hidden) g += n else g -= n
            t[key] = e.at; changed = true
        }
        for ((k, e) in remote) {
            val u = channelOf(k) ?: continue
            if (e.at <= (t[k] ?: if (u in c) 1L else 0L)) continue
            if (e.hidden) c += u else c -= u
            t[k] = e.at; changed = true
        }
        return Result(g, c, t, changed)
    }
}
