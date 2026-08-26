package com.auralis.music

import com.auralis.music.domain.model.AudioQueueManager
import com.auralis.music.domain.model.RepeatMode
import com.auralis.music.domain.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class TrackTransitionTest {

    private val trackA = Track(id = "A", title = "Song A", artist = "Artist A", duration = 180, thumbnail = "https://thumbA")
    private val trackB = Track(id = "B", title = "Song B", artist = "Artist B", duration = 200, thumbnail = "https://thumbB")
    private val trackC = Track(id = "C", title = "Song C", artist = "Artist C", duration = 220, thumbnail = "https://thumbC")

    /**
     * Simulates the transition engine with session completion deduplication
     */
    private class TrackTransitionArbitrator {
        val currentSessionId = AtomicLong(0L)
        val lastCompletedSessionId = AtomicLong(-1L)
        var currentlyPlayingTrack: Track? = null
        var isPlaying: Boolean = false
        val completedDispatchCount = AtomicInteger(0)
        var isExoPlayerStopped: Boolean = false
        var isYouTubeEngineStopped: Boolean = false

        fun play(track: Track): Long {
            val reqId = currentSessionId.incrementAndGet()
            // Synchronously stop all previous media
            isExoPlayerStopped = true
            isYouTubeEngineStopped = true
            currentlyPlayingTrack = track
            isPlaying = true
            return reqId
        }

        fun onPlaybackEnded(reqId: Long, callback: () -> Unit) {
            if (reqId != currentSessionId.get()) return
            if (lastCompletedSessionId.getAndSet(reqId) != reqId) {
                completedDispatchCount.incrementAndGet()
                isExoPlayerStopped = true
                isPlaying = false
                callback.invoke()
            }
        }
    }

    @Test
    fun `natural track completion advances queue cleanly exactly once without duplicate dispatch`() {
        val arbitrator = TrackTransitionArbitrator()
        val queueManager = AudioQueueManager()
        queueManager.setQueue(listOf(trackA, trackB, trackC), 0)

        // 1. Play Track A
        val reqA = arbitrator.play(trackA)
        assertEquals("A", arbitrator.currentlyPlayingTrack?.id)
        assertTrue(arbitrator.isPlaying)

        // 2. Track A completes -> fires onPlaybackEnded with reqA
        arbitrator.onPlaybackEnded(reqA) {
            val next = queueManager.advanceNext()
            assertNotNull(next)
            arbitrator.play(next!!)
        }

        // Verify Track B is now playing and completed count is 1
        assertEquals("B", arbitrator.currentlyPlayingTrack?.id)
        assertEquals(1, arbitrator.completedDispatchCount.get())
        assertTrue(arbitrator.isExoPlayerStopped)

        // 3. Stale completion from Track A (e.g. late JS bridge callback with reqA)
        arbitrator.onPlaybackEnded(reqA) {
            val next = queueManager.advanceNext()
            if (next != null) arbitrator.play(next)
        }

        // Verify that stale duplicate completion was dropped and did NOT advance to Track C
        assertEquals("B", arbitrator.currentlyPlayingTrack?.id)
        assertEquals(1, arbitrator.completedDispatchCount.get())
    }

    @Test
    fun `manual skip resets session and cleanly stops prior audio`() {
        val arbitrator = TrackTransitionArbitrator()
        val queueManager = AudioQueueManager()
        queueManager.setQueue(listOf(trackA, trackB, trackC), 0)

        arbitrator.play(trackA)
        assertEquals("A", arbitrator.currentlyPlayingTrack?.id)

        // User manually skips to Track B
        val next = queueManager.advanceNext()
        assertNotNull(next)
        val reqB = arbitrator.play(next!!)

        assertEquals("B", arbitrator.currentlyPlayingTrack?.id)
        assertEquals(2L, reqB)
        assertTrue("Previous audio must be synchronously stopped", arbitrator.isExoPlayerStopped && arbitrator.isYouTubeEngineStopped)
    }

    @Test
    fun `repeat mode ONE re-plays the same track on completion`() {
        val queueManager = AudioQueueManager()
        queueManager.setQueue(listOf(trackA, trackB), 0)
        queueManager.setRepeatMode(RepeatMode.ONE)

        val next = queueManager.advanceNext()
        assertEquals("Track A should repeat when RepeatMode is ONE", "A", next?.id)
    }
}
