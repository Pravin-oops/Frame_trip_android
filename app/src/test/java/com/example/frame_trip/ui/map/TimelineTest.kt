package com.example.frame_trip.ui.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineTest {

    private val start = 1_262_304_000_000L // 2010-01-01
    private val end = 1_791_158_400_000L // 2026-10-04

    @Test
    fun sliderEndsMapExactlyOntoTheRange() {
        assertEquals(start, Timeline.timestampAt(0f, start, end))
        assertEquals(end, Timeline.timestampAt(1f, start, end))
    }

    @Test
    fun sliderNearTheEndStaysBeforeTheNewestPhoto() {
        // Converting the timestamp itself to Float would round it by minutes; the fraction must not.
        val almostEnd = Timeline.timestampAt(0.9999f, start, end)
        assertTrue(almostEnd < end)
        assertTrue(almostEnd > end - 24 * 60 * 60 * 1_000L) // within a day
    }

    @Test
    fun sliderMidpointInterpolates() {
        assertEquals(start + (end - start) / 2, Timeline.timestampAt(0.5f, start, end))
    }

    @Test
    fun countIncludesPhotosTakenExactlyAtTheTimestamp() {
        val timestamps = longArrayOf(10, 20, 20, 30)
        fun countUpTo(timestamp: Long) = Timeline.countUpTo(timestamps.size, timestamp) { timestamps[it] }

        assertEquals(0, countUpTo(5))
        assertEquals(1, countUpTo(10))
        assertEquals(1, countUpTo(19))
        assertEquals(3, countUpTo(20))
        assertEquals(4, countUpTo(30))
        assertEquals(4, countUpTo(99))
    }

    @Test
    fun countOfEmptyListIsZero() {
        assertEquals(0, Timeline.countUpTo(0, 99) { error("must not be read") })
    }
}
