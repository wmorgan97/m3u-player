package com.wmorgan97.m3uplayer.tv

// Watch later: films and series saved for later, shared with the phone and the web app through /sync as a map keyed
// "<movie|series>:<id>" (the provider's stream / series id, the same on every device). Newest edit (at) wins; a removal
// goes up as a { deleted } marker. The same keys and rules as the web app's js/watchlater.js.
// Pure (no JSON, no Android), so the rules are unit-tested; Repo does the storage and the syncing.

data class WatchLaterEntry(
    val key: String, val kind: String, val id: String, val name: String, val logo: String = "", val year: Int? = null,
    val rating: Double? = null, val group: String = "", val ext: String = "", val at: Long = 0, val deleted: Boolean = false,
) {
    /** The title to open: Details looks everything else up from its id. */
    fun toVod() = Vod(kind, id.toLongOrNull() ?: 0, name, logo, group, rating, year, ext.ifEmpty { "mp4" })
}

object WatchLater {
    fun key(v: Vod) = "${v.kind}:${v.id}"
    fun entry(v: Vod, at: Long) = WatchLaterEntry(key(v), v.kind, v.id.toString(), v.name, v.poster, v.year, v.rating, v.group, if (v.kind == "movie") v.ext else "", at)
    fun removal(key: String, at: Long) = WatchLaterEntry(key, key.substringBefore(':'), "", "", at = at, deleted = true)

    private val MOVIE_ID = Regex("""/movie/[^?#]*/(\d+)\.\w+(?:[?#]|$)""")
    /** A film's provider id from its link (".../movie/<user>/<pass>/<id>.<ext>"), or null. */
    fun movieIdOf(url: String): String? = MOVIE_ID.find(url)?.groupValues?.get(1)

    /**
     * The Watch later row: most recently added first. A film finished since it was added drops off by itself (started
     * ones stay); removals are never shown. [finished]: when each film (by provider id) was last finished.
     */
    fun visible(all: Collection<WatchLaterEntry>, finished: Map<String, Long>): List<WatchLaterEntry> =
        all.filter { !it.deleted && it.name.isNotBlank() && (it.kind == "movie" || it.kind == "series") && !(it.kind == "movie" && (finished[it.id] ?: 0) > it.at) }
            .sortedByDescending { it.at }

    /** When each film was finished, by provider id, from the shared progress (url, done, at). */
    fun finished(progress: List<Triple<String, Boolean, Long>>): Map<String, Long> {
        val out = HashMap<String, Long>()
        for ((url, done, at) in progress) if (done) movieIdOf(url)?.let { id -> if (at > (out[id] ?: 0)) out[id] = at }
        return out
    }

    /** Newest edit per key wins. */
    fun adopt(mine: Map<String, WatchLaterEntry>, remote: Map<String, WatchLaterEntry>): Map<String, WatchLaterEntry> {
        val out = mine.toMutableMap()
        for ((k, v) in remote) if ((out[k]?.at ?: -1) < v.at) out[k] = v
        return out
    }

    /** Removals older than 90 days are forgotten (the server forgets them too). */
    fun prune(all: Map<String, WatchLaterEntry>, now: Long) = all.filterValues { !it.deleted || it.at > now - 90 * 86_400_000L }
}
