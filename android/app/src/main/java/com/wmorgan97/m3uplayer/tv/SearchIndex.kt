package com.wmorgan97.m3uplayer.tv

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.text.Normalizer

// Universal search, the in-memory part: channels and the loaded TV guide are indexed once (off the main thread) so a
// keystroke is a binary search over sorted words plus a check of the few candidates, never a scan of 20,000 names.

private val MARKS = Regex("""\p{Mn}+""")
private val NON_WORD = Regex("""[^\p{L}\p{N}]+""")

/** "Café  Ñews|HD" -> "cafe news hd": case, accents and punctuation folded, single spaces. */
fun foldSearch(s: String): String {
    if (s.isEmpty()) return s
    val d = Normalizer.normalize(s, Normalizer.Form.NFD).replace(MARKS, "")
    return d.lowercase().replace(NON_WORD, " ").trim()
}

/** Sorted unique words of a set of documents, each with the documents containing it. A query word matches any
 *  document word it is the start of ("spo" finds "sports"); every query word must match for a document to count. */
private class WordIndex(docs: List<String>) {
    private val words: Array<String>
    private val postings: Array<IntArray>
    private val padded: Array<String> = Array(docs.size) { " " + docs[it] }

    init {
        val map = HashMap<String, MutableList<Int>>()
        docs.forEachIndexed { i, d -> d.split(' ').forEach { w -> if (w.isNotEmpty()) map.getOrPut(w) { ArrayList(2) }.let { if (it.lastOrNull() != i) it += i } } }
        words = map.keys.toTypedArray().also { it.sort() }
        postings = Array(words.size) { map[words[it]]!!.toIntArray() }
    }

    /** Documents matching every word of an already-folded query. */
    fun find(q: String): IntArray {
        val toks = q.split(' ').filter { it.isNotEmpty() }
        if (toks.isEmpty()) return IntArray(0)
        val lead = toks.maxBy { it.length }               // the longest word has the narrowest range
        var lo = words.binarySearch(lead).let { if (it < 0) -it - 1 else it }
        val seen = HashSet<Int>()
        val out = ArrayList<Int>()
        while (lo < words.size && words[lo].startsWith(lead)) {
            for (d in postings[lo]) if (seen.add(d) && toks.all { t -> t === lead || padded[d].contains(" $t") }) out += d
            lo++
        }
        return out.toIntArray()
    }
}

/** Channels by name, number and group. */
class ChannelIndex private constructor(val live: Repo.Live) {
    private val chans = live.channels
    private val names = Array(chans.size) { foldSearch(chans[it].name) }
    private val idx = WordIndex(chans.indices.map { i -> val c = chans[i]; names[i] + " " + foldSearch(prettyGroup(c.group)) + (if (c.num > 0) " ${c.num}" else "") })

    fun search(q: String, limit: Int = 40): List<Channel> {
        val f = foldSearch(q); if (f.isEmpty()) return emptyList()
        val num = f.toIntOrNull()
        val hits = idx.find(f)
        fun rank(i: Int): Int { val n = names[i]; return when {
            num != null && chans[i].num == num -> 0
            n == f -> 1
            n.startsWith(f) -> 2
            (" $n").contains(" $f") -> 3
            f.split(' ').all { (" $n").contains(" $it") } -> 4
            else -> 5                                           // matched through the group or number only
        } }
        return hits.sortedWith(compareBy<Int>({ rank(it) }, { names[it].length }, { chans[it].num })).take(limit).map { chans[it] }
    }

    companion object {
        @Volatile private var cached: ChannelIndex? = null
        private val lock = Mutex()
        /** Built once per channel list, on a background thread; later calls get the same index at once. */
        suspend fun forLive(l: Repo.Live): ChannelIndex = cached?.takeIf { it.live === l } ?: lock.withLock {
            cached?.takeIf { it.live === l } ?: withContext(Dispatchers.Default) { ChannelIndex(l) }.also { cached = it }
        }
    }
}

/** A programme found in the guide, with the channel it's on. */
data class ProgrammeHit(val channel: Channel, val p: Programme) { fun onAir(t: Long = System.currentTimeMillis()) = p.start <= t && p.end > t }

/** Programme titles of the guide loaded so far (now and the next 24 hours), rebuilt when more of the guide arrives. */
class ProgrammeIndex private constructor(val live: Repo.Live, val guideVersion: Int) {
    private val hits: List<ProgrammeHit>
    private val titles: Array<String>
    private val idx: WordIndex

    init {
        val now = System.currentTimeMillis(); val until = now + 24 * 3600_000L
        val seenLists = java.util.IdentityHashMap<List<Programme>, Boolean>()   // HD/SD copies share one guide: list it once
        val out = ArrayList<ProgrammeHit>()
        for (c in live.channels) {
            val g = Repo.guideFor(c); if (g.isEmpty() || seenLists.put(g, true) != null) continue
            for (p in g) if (p.end > now && p.start < until && p.title.isNotBlank()) out += ProgrammeHit(c, p)
        }
        hits = out
        titles = Array(out.size) { foldSearch(out[it].p.title) }
        idx = WordIndex(titles.asList())
    }

    fun search(q: String, limit: Int = 40): List<ProgrammeHit> {
        val f = foldSearch(q); if (f.isEmpty()) return emptyList()
        val now = System.currentTimeMillis()
        return idx.find(f).filter { hits[it].p.end > now }
            .sortedWith(compareBy<Int>({ if (hits[it].onAir(now)) 0 else 1 }, { if (titles[it].startsWith(f)) 0 else 1 }, { hits[it].p.start }))
            .take(limit).map { hits[it] }
    }

    companion object {
        @Volatile private var cached: ProgrammeIndex? = null
        private val lock = Mutex()
        suspend fun forGuide(l: Repo.Live, version: Int): ProgrammeIndex = cached?.takeIf { it.live === l && it.guideVersion == version } ?: lock.withLock {
            cached?.takeIf { it.live === l && it.guideVersion == version } ?: withContext(Dispatchers.Default) { ProgrammeIndex(l, version) }.also { cached = it }
        }
    }
}

/** The search box on a remote: Down leaves it for the results (and puts the keyboard away) instead of being swallowed. */
@Composable
fun Modifier.searchFieldKeys(onSubmit: () -> Unit = {}): Modifier {
    val focus = LocalFocusManager.current
    val kb = LocalSoftwareKeyboardController.current
    return this.onPreviewKeyEvent { e ->
        when {
            e.type == KeyEventType.KeyDown && e.key == Key.DirectionDown -> { kb?.hide(); focus.moveFocus(FocusDirection.Down); true }
            // Enter / Search from a remote or keyboard: the same as the keyboard's Search key (OK still opens the keyboard).
            e.key == Key.Enter || e.key == Key.NumPadEnter || e.key == Key.Search -> { if (e.type == KeyEventType.KeyUp) onSubmit(); true }
            else -> false
        }
    }
}
