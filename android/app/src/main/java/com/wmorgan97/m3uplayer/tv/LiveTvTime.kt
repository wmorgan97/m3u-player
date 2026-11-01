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

/** The guide window moved a whole page ([pages] of [span], negative = back), kept inside [earliest]..[latest]. */
fun guidePage(viewStart: Long, pages: Int, span: Long, earliest: Long, latest: Long): Long =
    (viewStart + pages * span).coerceAtMost(maxOf(earliest, latest - span)).coerceAtLeast(earliest)
