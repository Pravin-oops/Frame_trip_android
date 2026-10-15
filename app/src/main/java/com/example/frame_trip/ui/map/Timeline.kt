package com.example.frame_trip.ui.map

import com.example.frame_trip.data.PhotoNode

internal object Timeline {
    /**
     * Maps a slider position in 0..1 onto [start]..[end].
     *
     * The slider works on a normalised fraction rather than raw timestamps because a Float cannot
     * hold epoch milliseconds precisely (around 2 minutes of error today), which would make the
     * newest photo disappear with the slider at its end. The ends are therefore pinned exactly.
     */
    fun timestampAt(fraction: Float, start: Long, end: Long): Long = when {
        fraction <= 0f -> start
        fraction >= 1f -> end
        else -> start + ((end - start) * fraction.toDouble()).toLong()
    }

    /**
     * Number of leading elements whose timestamp is at or before [timestamp], found by binary
     * search. The timestamps returned by [timestampOf] must be in ascending order.
     */
    fun countUpTo(size: Int, timestamp: Long, timestampOf: (index: Int) -> Long): Int {
        var low = 0
        var high = size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (timestampOf(mid) <= timestamp) low = mid + 1 else high = mid
        }
        return low
    }
}

/** Number of photos taken at or before [timestamp]. The list must be sorted oldest first. */
internal fun List<PhotoNode>.countTakenUpTo(timestamp: Long): Int =
    Timeline.countUpTo(size, timestamp) { this[it].timestamp }
