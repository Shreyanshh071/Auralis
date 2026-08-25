package com.auralis.music

import com.auralis.music.domain.model.QueueOperations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayCountAnalyticsTest {

    @Test
    fun `recent play count has maximum 2x boost`() {
        val now = 1_000_000_000L
        val score = QueueOperations.calculateRecencyScore(count = 10, lastPlayedEpochMs = now, nowEpochMs = now)
        // age = 0 -> boost = 1 + (1 - 0) = 2.0 -> score = 20.0
        assertEquals(20.0, score, 0.001)
    }

    @Test
    fun `15-day old play count has 1_5x boost`() {
        val now = 1_000_000_000L
        val fifteenDaysMs = 15L * 24 * 60 * 60 * 1000
        val lastPlayed = now - fifteenDaysMs
        val score = QueueOperations.calculateRecencyScore(count = 10, lastPlayedEpochMs = lastPlayed, nowEpochMs = now)
        // age = 15 days -> boost = 1 + (1 - 0.5) = 1.5 -> score = 15.0
        assertEquals(15.0, score, 0.001)
    }

    @Test
    fun `over 30-day old play count drops to 0_5x fallback`() {
        val now = 1_000_000_000L
        val fortyDaysMs = 40L * 24 * 60 * 60 * 1000
        val lastPlayed = now - fortyDaysMs
        val score = QueueOperations.calculateRecencyScore(count = 10, lastPlayedEpochMs = lastPlayed, nowEpochMs = now)
        // age >= 30 days -> boost = 0.5 -> score = 5.0
        assertEquals(5.0, score, 0.001)
    }

    @Test
    fun `monotonically higher plays with same recency score higher`() {
        val now = System.currentTimeMillis()
        val scoreA = QueueOperations.calculateRecencyScore(count = 5, lastPlayedEpochMs = now, nowEpochMs = now)
        val scoreB = QueueOperations.calculateRecencyScore(count = 10, lastPlayedEpochMs = now, nowEpochMs = now)
        assertTrue(scoreB > scoreA)
    }
}
