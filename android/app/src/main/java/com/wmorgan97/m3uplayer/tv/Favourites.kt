package com.wmorgan97.m3uplayer.tv

// Favourites are shared by every device through /sync, and each device saves the link it played: the TV builds Xtream
// links as .../live/user/pass/<id>.ts, the web app and iPhone as .m3u8, an M3U playlist may give .../user/pass/<id>,
// and a provider can move to a new host. Matching on the exact link alone (as Live TV and the Guide each did, slightly
// differently) lost most of them. One rule for every screen: the exact link, then the same stream in another link form,
// then the tvg-id with the same name, then the same name.

/** A favourite as saved in sync: only what matching needs. */
data class FavRef(val url: String, val name: String, val tvgId: String)

private val streamTail = Regex("""/([^/]+)/([^/]+)/(\d+)(?:\.[a-z0-9]+)?$""", RegexOption.IGNORE_CASE)

/** The same stream whatever the link's form: user/pass/stream-id for Xtream-style links, else the link without its
 *  extension. */
internal fun streamKey(url: String): String {
    val u = url.trim().substringBefore('?').substringBefore('#')
    streamTail.find(u)?.let { m -> return "x:" + m.groupValues[1] + "/" + m.groupValues[2] + "/" + m.groupValues[3] }
    return "u:" + u.replace(Regex("""\.(ts|m3u8|mpegts)$""", RegexOption.IGNORE_CASE), "").lowercase()
}

private fun nameKey(n: String) = n.lowercase().replace(Regex("[^a-z0-9]+"), "")

/** The channels of [channels] that are favourites, in the order the favourites were added, one per favourite (never the
 *  same link twice). */
fun matchFavourites(favs: List<FavRef>, channels: List<Channel>): List<Channel> {
    if (favs.isEmpty() || channels.isEmpty()) return emptyList()
    val byUrl = HashMap<String, Channel>(); val byStream = HashMap<String, Channel>(); val byId = HashMap<String, Channel>(); val byName = HashMap<String, Channel>()
    for (c in channels) {
        byUrl.putIfAbsent(c.url, c); byStream.putIfAbsent(streamKey(c.url), c)
        val n = nameKey(c.name)
        if (c.tvgId.isNotBlank()) byId.putIfAbsent(c.tvgId.trim().lowercase() + "|" + n, c)
        if (n.isNotEmpty()) byName.putIfAbsent(n, c)
    }
    val out = LinkedHashMap<String, Channel>()
    for (f in favs) {
        val n = nameKey(f.name)
        val c = byUrl[f.url] ?: byStream[streamKey(f.url)]
            ?: f.tvgId.takeIf { it.isNotBlank() }?.let { byId[it.trim().lowercase() + "|" + n] }
            ?: n.takeIf { it.isNotEmpty() }?.let { byName[it] }
            ?: continue
        out.putIfAbsent(c.url, c)
    }
    return out.values.toList()
}
