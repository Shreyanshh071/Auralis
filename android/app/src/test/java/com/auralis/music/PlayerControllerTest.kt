package com.auralis.music

import com.auralis.music.domain.model.AudioQueueManager
import com.auralis.music.domain.model.RepeatMode
import com.auralis.music.domain.model.SleepTimerManager
import com.auralis.music.domain.model.Track
import com.auralis.music.domain.model.TrackSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerControllerTest {

    private val track1 = Track(id = "1", title = "Track 1", artist = "Artist A", duration = 180, thumbnail = "https://thumb1")
    private val track2 = Track(id = "2", title = "Track 2", artist = "Artist B", duration = 200, thumbnail = "https://thumb2")
    private val track3 = Track(id = "3", title = "Track 3", artist = "Artist C", duration = 220, thumbnail = "https://thumb3")

    @Test
    fun `audioQueueManager advances sequentially through queue in RepeatMode OFF`() {
        val manager = AudioQueueManager()
        manager.setQueue(listOf(track1, track2, track3), startIndex = 0)

        assertEquals(track1, manager.state.currentTrack)
        assertEquals(0, manager.state.currentIndex)

        val next = manager.advanceNext()
        assertEquals(track2, next)
        assertEquals(1, manager.state.currentIndex)

        val next2 = manager.advanceNext()
        assertEquals(track3, next2)
        assertEquals(2, manager.state.currentIndex)

        // At end of queue, next should be null when RepeatMode is OFF
        val next3 = manager.advanceNext()
        assertNull(next3)
    }

    @Test
    fun `audioQueueManager wraps around queue in RepeatMode ALL`() {
        val manager = AudioQueueManager()
        manager.setQueue(listOf(track1, track2, track3), startIndex = 2)
        manager.setRepeatMode(RepeatMode.ALL)

        val next = manager.advanceNext()
        assertEquals(track1, next)
        assertEquals(0, manager.state.currentIndex)
    }

    @Test
    fun `audioQueueManager repeats same track in RepeatMode ONE`() {
        val manager = AudioQueueManager()
        manager.setQueue(listOf(track1, track2, track3), startIndex = 1)
        manager.setRepeatMode(RepeatMode.ONE)

        val next = manager.advanceNext()
        assertEquals(track2, next)
        assertEquals(1, manager.state.currentIndex)
    }

    @Test
    fun `audioQueueManager shuffle preserves current track at index 0`() {
        val manager = AudioQueueManager()
        manager.setQueue(listOf(track1, track2, track3), startIndex = 1)

        val shuffledState = manager.toggleShuffle()
        assertTrue(shuffledState.isShuffled)
        assertEquals(track2, shuffledState.currentTrack)
        assertEquals(0, shuffledState.currentIndex)
        assertEquals(3, shuffledState.queue.size)
    }

    @Test
    fun `sleepTimerManager sets, computes remaining time, and expires accurately`() {
        val timer = SleepTimerManager()
        assertFalse(timer.isActive)

        val start = System.currentTimeMillis()
        timer.setTimer(15) // 15 minutes
        assertTrue(timer.isActive)

        val remainingSec = timer.getRemainingSeconds(start)
        assertEquals(15 * 60L, remainingSec)

        // Simulate 16 minutes in the future
        val future = start + (16 * 60 * 1000L)
        assertTrue(timer.isExpired(future))

        timer.cancel()
        assertFalse(timer.isActive)
    }
}
