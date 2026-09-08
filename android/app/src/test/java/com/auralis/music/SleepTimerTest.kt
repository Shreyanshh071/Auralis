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
        assertTrue(timer.isSet)

        timer.cancel()
        assertFalse(timer.isActive)
        assertFalse(timer.isSet)
        assertNull(timer.deadlineEpochMs)
        assertEquals(0L, timer.getRemainingSeconds())
    }

    @Test
    fun `isSet remains true when expired until cancelled to allow ticker to handle expiration`() {
        val timer = SleepTimerManager()
        val deadline = timer.setTimerSeconds(10)
        assertTrue(timer.isSet)
        assertTrue(timer.isActive)

        val expiredNow = deadline + 1000L
        // At or after deadline, isActive becomes false because deadline is no longer in future
        assertFalse(timer.isActive(nowEpochMs = expiredNow))
        // BUT isSet must remain true so while(timer.isSet) executes the expiration logic!
        assertTrue(timer.isSet)
        assertTrue(timer.isExpired(nowEpochMs = expiredNow))
        assertEquals(0L, timer.getRemainingSeconds(nowEpochMs = expiredNow))

        // Once ticker calls cancel(), isSet becomes false
        timer.cancel()
        assertFalse(timer.isSet)
    }

    @Test
    fun `setTimerSeconds sets correct deadline`() {
        val timer = SleepTimerManager()
        val now = System.currentTimeMillis()
        val deadline = timer.setTimerSeconds(45)
        assertTrue(deadline >= now + 44_000L && deadline <= now + 46_000L)
        assertTrue(timer.isSet)
        assertTrue(timer.isActive)
    }
}
