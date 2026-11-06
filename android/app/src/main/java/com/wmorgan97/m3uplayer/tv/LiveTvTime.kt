package com.wmorgan97.m3uplayer.tv

// Small pure helpers for the Live TV and Guide screens (time left, progress, guide paging). Unit-tested.

/** How far through [p] we are at [now], 0..1 (0 for a programme that hasn't started, 1 once it has ended). */
fun liveFraction(p: Programme, now: Long): Float =
    if (p.end <= p.start) 0f else ((now - p.start).toFloat() / (p.end - p.start)).coerceIn(0f, 1f)

/** "45 min left", "1h 05m left", "Ending" (under a minute); null when [p] isn't on air at [now]. */
fun liveLeftLabel(p: Programme, now: Long): String? {
    if (p.start > now || p.end <= now) return null
    val min = ((p.end - now) / 60_000L).toInt()
    return when {
        min < 1 -> "Ending"
        min < 60 -> "$min min left"
        else -> "%dh %02dm left".format(min / 60, min % 60)
    }
}

/** "Starts in 20 min" / "Starts in 1h 10m" for a programme later today; null when it has started or is over 12 hours off. */
fun liveStartsIn(p: Programme, now: Long): String? {
    if (p.start <= now) return null
    val min = ((p.start - now + 59_999L) / 60_000L).toInt()
    return when {
        min < 60 -> "Starts in $min min"
        min < 12 * 60 -> "Starts in ${min / 60}h ${"%02d".format(min % 60)}m"
        else -> null
    }
}

const val GUIDE_HALF_HOUR = 30 * 60_000L
const val GUIDE_STEP = 5 * 60_000L

/** [t] rounded down to a whole half hour on the local clock ([offset] = the zone's offset at [t]). */
fun floorHalfHour(t: Long, offset: Long = java.util.TimeZone.getDefault().getOffset(t).toLong()): Long =
    Math.floorDiv(t + offset, GUIDE_HALF_HOUR) * GUIDE_HALF_HOUR - offset

/** Where the guide window starts when it is at "now": the current time rounded down to 5 minutes (so it doesn't jitter). */
fun guideStart(now: Long): Long = Math.floorDiv(now, GUIDE_STEP) * GUIDE_STEP

/** The next whole half hour after [t]. */
fun nextHalfHour(t: Long, offset: Long = java.util.TimeZone.getDefault().getOffset(t).toLong()): Long = floorHalfHour(t, offset) + GUIDE_HALF_HOUR

/** The window start half an hour (or less, back to the half-hour line) earlier, never before [earliest]. */
fun prevHalfHour(t: Long, earliest: Long, offset: Long = java.util.TimeZone.getDefault().getOffset(t).toLong()): Long {
    val f = floorHalfHour(t, offset)
    return (if (f < t) f else f - GUIDE_HALF_HOUR).coerceAtLeast(earliest)
}

/** The guide window moved a whole page ([pages] of [span], negative = back), kept inside [earliest]..[latest]; off the start it sits on a half hour. */
fun guidePage(viewStart: Long, pages: Int, span: Long, earliest: Long, latest: Long): Long {
    val t = (viewStart + pages * span).coerceAtMost(maxOf(earliest, latest - span)).coerceAtLeast(earliest)
    return if (t <= earliest) earliest else floorHalfHour(t).coerceAtLeast(earliest)
}

/** A programme (or, when [p] is null, a quiet "No information" gap) clipped to the window: [start]..[end] lie inside it. */
class GuideSlot(val p: Programme?, val start: Long, val end: Long)

/**
 * What a row draws in the window [viewStart]..[end]: only programmes still to finish at [now] (nothing already over is drawn),
 * clipped to the window. When the window starts at or before now and nothing covers its left edge, a gap slot
 * runs up to the next programme (or across the whole window), so the row never starts with a hole.
 */
fun guideSlots(all: List<Programme>, viewStart: Long, end: Long, now: Long): List<GuideSlot> {
    val from = maxOf(viewStart, now.coerceAtMost(end))
    val out = ArrayList<GuideSlot>()
    for (p in all) { if (p.end <= from || p.end <= p.start) continue; if (p.start >= end) break; out += GuideSlot(p, if (viewStart <= now && p.start <= now) viewStart else maxOf(p.start, viewStart), minOf(p.end, end)) }   // (the programme on now fills the row's left edge: the minutes since it started are not a gap)
    if (viewStart <= now && (out.isEmpty() || out[0].start > viewStart)) out.add(0, GuideSlot(null, viewStart, out.firstOrNull()?.start ?: end))
    return out
}

/** Half-hour tick times across the ruler: the first whole half hour at or after [viewStart], then every half hour up to [span]. */
fun guideTicks(viewStart: Long, span: Long, offset: Long = java.util.TimeZone.getDefault().getOffset(viewStart).toLong()): List<Long> {
    val first = floorHalfHour(viewStart, offset).let { if (it < viewStart) it + GUIDE_HALF_HOUR else it }
    val out = ArrayList<Long>(); var t = first
    while (t < viewStart + span) { out += t; t += GUIDE_HALF_HOUR }
    return out
}

/** Cells narrower than this draw no text at all (never "…" fragments); the time line needs more room still. */
const val GUIDE_TEXT_MIN_DP = 72
const val GUIDE_SUB_MIN_DP = 104

/** The first cell is never narrower than this: the programme on now always reads, however little of it is left. */
const val GUIDE_FIRST_MIN_DP = 140f

/** A [slot] placed on the grid, in dp. */
class GuidePlaced(val slot: GuideSlot, val x: Float, val w: Float)

/**
 * Places a row's [slots] on a grid [gridW] dp wide ([perMin] dp per minute from [viewStart]). The first cell gets at least [minFirst]
 * dp and pushes the later cells right by the difference; later cells are cut at the grid's right edge, and any that end up under
 * [minText] dp (they could show no text) are not drawn. The first cell also takes over a hole such a dropped cell leaves behind it.
 */
fun guideLayout(slots: List<GuideSlot>, viewStart: Long, perMin: Float, gridW: Float, minFirst: Float = GUIDE_FIRST_MIN_DP, minText: Float = GUIDE_TEXT_MIN_DP.toFloat()): List<GuidePlaced> {
    if (slots.isEmpty()) return emptyList()
    val f = slots[0]
    val fx = (f.start - viewStart) / 60_000f * perMin
    val fw = maxOf((f.end - f.start) / 60_000f * perMin, minFirst.coerceAtMost(gridW - fx))
    val shift = fw - (f.end - f.start) / 60_000f * perMin
    val out = ArrayList<GuidePlaced>(); out += GuidePlaced(f, fx, fw)
    for (i in 1 until slots.size) {
        val g = slots[i]
        val x = (g.start - viewStart) / 60_000f * perMin + shift
        val w = minOf((g.end - g.start) / 60_000f * perMin, gridW - x)
        if (w >= minText) out += GuidePlaced(g, x, w)
    }
    if (out.size > 1 && out[1].x - (fx + fw) < minText) out[0] = GuidePlaced(f, fx, out[1].x - fx)   // (a hole under a text's width: not a gap worth drawing)
    return out
}
