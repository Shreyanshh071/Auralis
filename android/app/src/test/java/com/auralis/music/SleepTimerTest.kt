package com.auralis.music

import com.auralis.music.domain.model.SleepTimerManager
import org.junit.Assert.*
import org.junit.Test

class SleepTimerTest {

    @Test
    fun `setTimer calculates absolute deadline correctly and activates`() {
        val timer = SleepTimerManager()
        assertFalse(timer.isActive)

        val now = 1_000_000_000L
        val deadline = timer.setTimer(durationMinutes = 15)
        assertTrue(timer.isActive)
        assertNotNull(timer.deadlineEpochMs)

        // Check remaining seconds calculation
        val remaining = timer.getRemainingSeconds(nowEpochMs = deadline - 60_000)
        assertEquals(60L, remaining)

        // Expiration check
        assertFalse(timer.isExpired(nowEpochMs = deadline - 1000))
        assertTrue(timer.isExpired(nowEpochMs = deadline + 1000))
    }

    @Test
    fun `cancel resets deadline and deactivates timer`() {
        val timer = SleepTimerManager()
        timer.setTimer(durationMinutes = 30)
        assertTrue(timer.isActive)

        timer.cancel()
        assertFalse(timer.isActive)
        assertNull(timer.deadlineEpochMs)
        assertEquals(0L, timer.getRemainingSeconds())
    }
}
