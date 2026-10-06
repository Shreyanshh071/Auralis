package com.auralis.music

import com.auralis.music.data.service.measuredProgressMs
import org.junit.Assert.assertEquals
import org.junit.Test

class ListeningProgressTest {
    @Test
    fun stalledAtZeroDoesNotAccumulateListeningTime() {
        assertEquals(0L, measuredProgressMs(0L, 0L, 10_000L, 1_000L))
    }

    @Test
    fun playbackProgressCountsOnlyElapsedTime() {
        assertEquals(1_000L, measuredProgressMs(0L, 1_000L, 1_000L, 1_000L))
        assertEquals(1_000L, measuredProgressMs(1_000L, 2_000L, 1_000L, 1_000L))
    }

    @Test
    fun seekAndRewindDoNotBecomeListeningTime() {
        assertEquals(0L, measuredProgressMs(1_000L, 90_000L, 1_000L, 1_000L))
        assertEquals(0L, measuredProgressMs(90_000L, 10_000L, 1_000L, 1_000L))
    }
}
