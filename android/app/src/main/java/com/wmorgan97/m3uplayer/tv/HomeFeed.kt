package com.wmorgan97.m3uplayer.tv

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

// Home's rows: films and series only (live TV has its own pages). Built off the main thread from what the TV already
// keeps (the two catalogue overviews, TMDB's genre lists, watch history, saved titles) and cached, so returning to
// Home or a recomposition never rebuilds them. The order is deliberate: what you're in the middle of, what everyone
// is watching, what suits you, what's new, then broader browsing.

data class HomeRow(val id: String, val title: String, val items: List<Vod>, val ranked: Boolean = false)

/** One genre row, merged from the film and series lists that make it up (round-robin, so both kinds show). */
private data class GenreRow(val id: String, val title: String, val tmdb: Set<String>, val sources: List<Pair<String, String>>)

object HomeFeed {
    private val GENRES = listOf(
        GenreRow("g-action", "Action & adventure", setOf("Action", "Adventure", "Action & Adventure"), listOf("movies" to "genre:28", "series" to "genre:10759", "movies" to "genre:12")),
        GenreRow("g-comedy", "Comedy", setOf("Comedy"), listOf("movies" to "genre:35", "series" to "genre:35")),
        GenreRow("g-thriller", "Thrillers & crime", setOf("Thriller", "Crime", "Mystery"), listOf("movies" to "genre:53", "series" to "genre:80", "movies" to "genre:80")),
        GenreRow("g-scifi", "Sci-fi & fantasy", setOf("Science Fiction", "Fantasy", "Sci-Fi & Fantasy"), listOf("movies" to "genre:878", "series" to "genre:10765", "movies" to "genre:14")),
        GenreRow("g-drama", "Drama", setOf("Drama"), listOf("movies" to "genre:18", "series" to "genre:18")),
        GenreRow("g-family", "Family & animation", setOf("Family", "Animation", "Kids"), listOf("movies" to "genre:10751", "movies" to "genre:16", "series" to "genre:16")),
    )
    /** TMDB genre name -> that kind's genre list on the server ("Because you watched"). */
    private val MOVIE_GENRE = mapOf("Action" to 28, "Adventure" to 12, "Animation" to 16, "Comedy" to 35, "Crime" to 80, "Documentary" to 99, "Drama" to 18,
        "Family" to 10751, "Fantasy" to 14, "History" to 36, "Horror" to 27, "Music" to 10402, "Mystery" to 9648, "Romance" to 10749, "Science Fiction" to 878,
        "Thriller" to 53, "War" to 10752, "Western" to 37)
    private val TV_GENRE = mapOf("Action & Adventure" to 10759, "Animation" to 16, "Comedy" to 35, "Crime" to 80, "Documentary" to 99, "Drama" to 18,
        "Family" to 10751, "Kids" to 10762, "Mystery" to 9648, "Reality" to 10764, "Sci-Fi & Fantasy" to 10765, "War & Politics" to 10768, "Western" to 37)

    /** Rows besides Continue watching: ten in all at most, so Home stays calm. */
    private const val MAX_ROWS = 9
    private const val MIN_ITEMS = 6

    @Volatile private var cache: Pair<String, List<HomeRow>>? = null
    fun cached(): List<HomeRow>? = cache?.second

    private fun sig(movies: Overview?, series: Overview?): String {
        val p = Repo.progress().filter(::isVodProgress).take(8).joinToString { it.url }
        val f = savedTitles().joinToString { "${it.kind}${it.id}" }
        return "${System.identityHashCode(movies)}|${System.identityHashCode(series)}|$p|$f"
    }

    /** The rows that need nothing fetched (shown at once while the rest are put together). */
    fun quick(movies: Overview?, series: Overview?): List<HomeRow> = assemble(movies, series, null, emptyList(), emptyMap(), emptyList())

    suspend fun build(movies: Overview?, series: Overview?): List<HomeRow> {
        val key = sig(movies, series)
        cache?.takeIf { it.first == key }?.let { return it.second }
        val history = Repo.progress().filter(::isVodProgress).distinctBy { showKey(it.item) }.take(6)
        val rows = coroutineScope {
            // Your taste: TMDB genres of what you watched lately (cached on the TV for 30 days, so usually instant).
            val infos = history.map { p -> async { withTimeoutOrNull(4000) { runCatching { Repo.tmdbInfo(progressVod(p.item)) }.getOrNull() } } }.awaitAll()
            val tasteGenres = infos.flatMap { genresOf(it) }
            val because = history.firstOrNull()?.let { p -> becauseYouWatched(p, genresOf(infos.firstOrNull()), history) }
            val groups = GENRES.flatMap { it.sources }.distinct().map { (kind, id) -> async { (kind to id) to (runCatching { Repo.group(kind, id) }.getOrNull() ?: emptyList()) } }.awaitAll().toMap()
            val hi = listOf("movies", "series").map { k -> async { runCatching { Repo.group(k, "list:top") }.getOrNull() ?: emptyList() } }.awaitAll()
            assemble(movies, series, because, tasteGenres, groups, interleave(hi.map { l -> l.filter { (it.rating ?: 0.0) >= 7.5 && it.votes >= 400 } }))
        }
        cache = key to rows
        return rows
    }

    private fun assemble(movies: Overview?, series: Overview?, because: HomeRow?, taste: List<String>, groups: Map<Pair<String, String>, List<Vod>>, highlyRated: List<Vod>): List<HomeRow> {
        val fixedTop = listOfNotNull(
            movies?.trending?.take(10)?.takeIf { it.size >= 5 }?.let { HomeRow("top-m", "Top 10 movies this week", it, ranked = true) },
            series?.trending?.take(10)?.takeIf { it.size >= 5 }?.let { HomeRow("top-s", "Top 10 series this week", it, ranked = true) },
            because,
            HomeRow("new", "New on your playlist", interleave(listOf(movies?.fresh ?: emptyList(), series?.fresh ?: emptyList())).take(40)),
        )
        val saved = savedTitles()
        val tail = listOfNotNull(
            saved.takeIf { it.isNotEmpty() }?.let { HomeRow("mine", "My list", it) },
            HomeRow("rated", "Highly rated", highlyRated.take(40)),
        )
        // Genres: the ones you watch most first (else the default order), as many as fit under the row cap.
        val score = taste.groupingBy { g -> GENRES.indexOfFirst { g in it.tmdb } }.eachCount()
        val genreRows = GENRES.withIndex().sortedWith(compareByDescending<IndexedValue<GenreRow>> { score[it.index] ?: 0 }.thenBy { it.index }).map { it.value }
            .map { g -> HomeRow(g.id, g.title, interleave(g.sources.map { groups[it] ?: emptyList() }).take(40)) }
        val ordered = fixedTop + genreRows + tail
        val usable = dedupe(ordered).filter { it.items.size >= (if (it.id == "mine") 1 else MIN_ITEMS) }
        val nonGenre = usable.count { !it.id.startsWith("g-") }
        var genresLeft = (MAX_ROWS - nonGenre).coerceIn(2, 4)
        return usable.filter { !it.id.startsWith("g-") || genresLeft-- > 0 }
    }

    /** Titles from your catalogue like the last thing you watched: same kind, sharing its first two TMDB genres where
     *  enough do, else its first genre. Anything you've already watched is left out. */
    private suspend fun becauseYouWatched(p: Progress, genres: List<String>, history: List<Progress>): HomeRow? {
        val src = progressVod(p.item)
        val kind = if (src.kind == "series") "series" else "movies"
        val map = if (kind == "series") TV_GENRE else MOVIE_GENRE
        val ids = genres.mapNotNull { map[it] }.distinct().take(2)
        if (ids.isEmpty()) return null
        val lists = ids.map { runCatching { Repo.group(kind, "genre:$it") }.getOrNull() ?: emptyList() }
        val watched = history.map { titleKey(progressVod(it.item)) }.toSet() + titleKey(src)
        val first = lists[0].filter { titleKey(it) !in watched }
        val both = if (lists.size > 1) { val second = lists[1].map(::titleKey).toSet(); first.filter { titleKey(it) in second } } else emptyList()
        val items = (both + first).distinctBy(::titleKey).take(30)
        return items.takeIf { it.size >= MIN_ITEMS }?.let { HomeRow("because", "Because you watched ${src.title}", it) }
    }

    /** Saved films and series (the web and iPhone apps' "Watchlist"), newest first. */
    private fun savedTitles(): List<Vod> = Repo.favorites().filter { val k = it.optString("kind"); k == "movie" || k == "series" }.reversed().mapNotNull { f ->
        val id = f.optString("id").drop(1).toLongOrNull() ?: f.optLong("sid").takeIf { it > 0 } ?: return@mapNotNull null
        val name = f.optString("name")
        Vod(f.optString("kind"), id, name, f.optString("logo"), f.optString("group"), f.optDouble("rating").takeIf { !it.isNaN() && it > 0 },
            f.optInt("year").takeIf { it > 0 } ?: vodMeta(name).year, f.optString("ext").ifEmpty { "mp4" })
    }.take(30)

    private fun genresOf(info: JSONObject?): List<String> = info?.optJSONArray("genres")?.let { g -> (0 until g.length()).map { g.optString(it) } } ?: emptyList()
}

fun isVodProgress(p: Progress): Boolean {
    val k = p.item.optString("kind")
    return (k == "movie" || k == "episode" || p.url.contains("/movie/") || p.url.contains("/series/")) && k != "live"
}

/** A film or a whole show for a watch-history entry (an episode stands for its series): for TMDB lookups and titles. */
fun progressVod(item: JSONObject): Vod {
    val ep = item.optString("kind") == "episode"
    val name = if (ep) item.optString("series").ifEmpty { item.optString("group").substringBefore(" · Season") }.ifEmpty { displayTitle(item.optString("name")) } else item.optString("name")
    val year = if (ep) null else item.optString("year").take(4).toIntOrNull() ?: vodMeta(name).year
    return Vod(if (ep) "series" else "movie", 0, name, item.optString("logo"), item.optString("group"), null, year, "mp4")
}

private fun showKey(item: JSONObject) = titleKey(progressVod(item))
fun titleKey(v: Vod) = v.title.lowercase().replace("&", "and").replace(Regex("[^a-z0-9]+"), "")

/** a0, b0, c0, a1, b1, … with each title once. */
fun interleave(lists: List<List<Vod>>): List<Vod> {
    val out = ArrayList<Vod>(); val seen = HashSet<String>()
    val n = lists.maxOfOrNull { it.size } ?: 0
    for (i in 0 until n) for (l in lists) l.getOrNull(i)?.let { if (seen.add(titleKey(it))) out += it }
    return out
}

/** Keeps the start of each row fresh: a title already near the front of an earlier row is dropped (Top 10 rows are
 *  kept whole). Where that would leave a row too thin, only the row just above is checked instead. */
fun dedupe(rows: List<HomeRow>, front: Int = 10, minItems: Int = 6): List<HomeRow> {
    val seen = HashSet<String>(); var prev = emptySet<String>()
    return rows.map { r ->
        val out = if (r.ranked || r.id == "mine") r else {   // ranked lists and your own list are never trimmed
            val all = r.items.filter { titleKey(it) !in seen }
            r.copy(items = if (all.size >= minItems) all else r.items.filter { titleKey(it) !in prev })
        }
        val head = out.items.take(front).map(::titleKey)
        seen += head; prev = head.toSet()
        out
    }
}
