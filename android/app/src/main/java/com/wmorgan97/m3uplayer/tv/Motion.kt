package com.wmorgan97.m3uplayer.tv

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListPrefetchScope
import androidx.compose.foundation.lazy.LazyListPrefetchStrategy
import androidx.compose.foundation.lazy.layout.LazyLayoutPrefetchState
import androidx.compose.foundation.lazy.layout.NestedPrefetchScope
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp

// How things scroll into view, app-wide. Every strategy moves on a no-bounce spring, so a new target mid-flight keeps
// the current speed instead of restarting from rest (a fixed-length tween restarted on each key repeat, which is what
// made held keys pile up). Which strategy applies where:
//  - rows of cards (sideways):  [TvScroll.keyline]   the focused card stays on the 48dp left keyline and the row slides
//                                                    under it (Google TV / Netflix); near the row's end it walks on.
//  - pages of rows (up/down):   [TvScroll.anchor]    the focused row always settles at the same height on the page.
//  - dense lists (channels, settings, menus): [TvScroll.edge]  scrolls only when the next item would be hidden, keeping
//                                                    one item of context beyond the highlight (TiviMate).
//  - episode lists:             [TvScroll.centered]  the focused item settles just above the middle.

object TvScroll {
    /** The default for anything that doesn't say otherwise (installed in TvActivity): edge scrolling with context. */
    val default: BringIntoViewSpec get() = edge

    /** Scrolls only as far as needed to show the item plus a little of the next one (no jump when there's room). */
    val edge: BringIntoViewSpec = object : BringIntoViewSpec {
        @Suppress("OVERRIDE_DEPRECATION") override val scrollAnimationSpec: AnimationSpec<Float> = Tv.scrollSpring
        override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
            val context = minOf(size * .5f, containerSize * .08f)   // a peek of the neighbour
            val end = offset + size
            return when {
                size >= containerSize -> offset
                offset < context -> offset - context
                end > containerSize - context -> end - (containerSize - context)
                else -> 0f
            }
        }
    }

    /** The focused item's leading edge pinned at [keylinePx] from the container's start. */
    fun keyline(keylinePx: Float, spec: AnimationSpec<Float> = Tv.scrollSpring): BringIntoViewSpec = object : BringIntoViewSpec {
        @Suppress("OVERRIDE_DEPRECATION") override val scrollAnimationSpec: AnimationSpec<Float> = spec
        override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
            if (size >= containerSize) offset else offset - keylinePx
    }

    /** Pages of rows: the focused item's top settles [anchorPx] below the page top (the row title sits above it). */
    fun anchor(anchorPx: Float): BringIntoViewSpec = keyline(anchorPx, Tv.rowScrollSpring)

    /** The focused item settles just above the middle (episode lists: what's next stays in view). */
    val centered: BringIntoViewSpec = object : BringIntoViewSpec {
        @Suppress("OVERRIDE_DEPRECATION") override val scrollAnimationSpec: AnimationSpec<Float> = Tv.rowScrollSpring
        override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
            if (size >= containerSize) offset else offset + size / 2 - containerSize * 0.45f
    }
}

/** Row keyline at the page margin: the focused card's left edge stays where the row's first card starts. */
@Composable
fun rememberRowKeyline(keyline: Dp = Tv.margin): BringIntoViewSpec {
    val px = with(LocalDensity.current) { keyline.toPx() }
    return remember(px) { TvScroll.keyline(px) }
}

/** A page of rows: the focused row's cards settle [cardTop] below the list's top, wherever on the page they are. */
@Composable
fun rememberRowAnchor(cardTop: Dp): BringIntoViewSpec {
    val px = with(LocalDensity.current) { cardTop.toPx() }
    return remember(px) { TvScroll.anchor(px) }
}

/** Provides [spec] to the scrollable containers composed in [content] (not to their parents). */
@Composable
fun WithScroll(spec: BringIntoViewSpec, content: @Composable () -> Unit) = CompositionLocalProvider(LocalBringIntoViewSpec provides spec, content = content)

// Older names, kept for call sites outside the shared files.
val QuickBringIntoView: BringIntoViewSpec get() = TvScroll.edge
val CenteredBringIntoView: BringIntoViewSpec get() = TvScroll.centered

/** Held D-pad pace. TvActivity drops arrow repeats that arrive sooner than this after the last move (and any that are
 *  already stale), so a held key moves at a steady, stoppable pace and nothing is left queued when it's let go.
 *  Pages of rows ask for a slower vertical pace while they're on screen ([HeldPace]). */
object KeyPace {
    var horizontalMs by mutableLongStateOf(Tv.heldCardMs)
    var verticalMs by mutableLongStateOf(Tv.heldListMs)
}

/** While composed, held Up/Down moves at most once per [verticalMs] (pages of big rows: ~6.5 rows a second). */
@Composable
fun HeldPace(verticalMs: Long = Tv.heldRowMs) {
    DisposableEffect(verticalMs) { val before = KeyPace.verticalMs; KeyPace.verticalMs = verticalMs; onDispose { KeyPace.verticalMs = before } }
}

/** Rows are prepared before they scroll into view (and the first cards of each row with them), so moving down a page
 *  never waits for a row to be built: the one cause of dropped frames on a Shield. Rows stay built for a screen's height
 *  above and below what's showing, so rows you scroll back to are still there. */
@Composable
fun rememberPageState() = androidx.compose.foundation.lazy.rememberLazyListState(prefetchStrategy = remember { AheadPrefetch(rows = 3, nested = 10) })

/** Keeps rows built above and below what's on screen, and prefetches each of a row's first cards as its own small unit
 *  (a whole row is more than a frame's worth, which Compose's prefetcher won't attempt; one poster is not). */
class AheadPrefetch(private val rows: Int = 3, private val nested: Int = 10) : LazyListPrefetchStrategy {
    private val done = HashMap<Int, LazyLayoutPrefetchState.PrefetchHandle>()
    override fun LazyListPrefetchScope.onScroll(delta: Float, layoutInfo: LazyListLayoutInfo) = schedule(layoutInfo)
    override fun LazyListPrefetchScope.onVisibleItemsUpdated(layoutInfo: LazyListLayoutInfo) = schedule(layoutInfo)
    private fun LazyListPrefetchScope.schedule(info: LazyListLayoutInfo) {
        val first = info.visibleItemsInfo.firstOrNull()?.index ?: return
        val last = info.visibleItemsInfo.lastOrNull()?.index ?: return
        val want = ((1..rows).map { last + it } + (1..rows).map { first - it }).filter { it in 0 until info.totalItemsCount }
        done.keys.filter { it !in want && it !in first..last }.forEach { done.remove(it)?.cancel() }
        for (i in want) if (i !in done) done[i] = schedulePrefetch(i)
    }
    override fun NestedPrefetchScope.onNestedPrefetch(firstVisibleItemIndex: Int) { repeat(nested) { schedulePrefetch(firstVisibleItemIndex + it) } }
}
