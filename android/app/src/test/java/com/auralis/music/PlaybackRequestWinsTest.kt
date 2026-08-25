package com.auralis.music

import androidx.compose.ui.graphics.Color
import com.auralis.music.domain.model.AudioQueueManager
import com.auralis.music.domain.model.Track
import com.auralis.music.ui.theme.ArtworkPalette
import com.auralis.music.ui.theme.ArtworkPaletteCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

class PlaybackRequestWinsTest {

    private val trackA = Track(id = "A", title = "Track A", artist = "Artist A", duration = 180, thumbnail = "https://thumbA")
    private val trackB = Track(id = "B", title = "Track B", artist = "Artist B", duration = 200, thumbnail = "https://thumbB")
    private val trackC = Track(id = "C", title = "Track C", artist = "Artist C", duration = 220, thumbnail = "https://thumbC")

    /**
     * Simulates the latest-request-wins arbitration state machine
     */
    private class PlaybackSessionArbitrator {
        val currentRequestId = AtomicLong(0L)
        var currentlyPlayingTrackId: String? = null
        var isPlaying: Boolean = false
        var completedCount: Int = 0

        fun requestPlay(track: Track): Long {
            val reqId = currentRequestId.incrementAndGet()
            currentlyPlayingTrackId = track.id
            isPlaying = true
            return reqId
        }

        fun onStateChange(state: Int, reqId: Long) {
            // Guard: Stale request events must be dropped immediately
            if (reqId != currentRequestId.get()) return

            when (state) {
                0 -> { // Ended
                    isPlaying = false
                    completedCount++
                }
                1 -> isPlaying = true
                2 -> isPlaying = false
            }
        }
    }

    @Test
    fun `rapid tap sequence A to B to C results in only C active and drops stale callbacks from A and B`() {
        val arbitrator = PlaybackSessionArbitrator()

        // 1. Rapid tap on Track A
        val reqA = arbitrator.requestPlay(trackA)
        assertEquals(1L, reqA)
        assertEquals("A", arbitrator.currentlyPlayingTrackId)

        // 2. Rapid tap on Track B
        val reqB = arbitrator.requestPlay(trackB)
        assertEquals(2L, reqB)
        assertEquals("B", arbitrator.currentlyPlayingTrackId)

        // 3. Rapid tap on Track C
        val reqC = arbitrator.requestPlay(trackC)
        assertEquals(3L, reqC)
        assertEquals("C", arbitrator.currentlyPlayingTrackId)

        // Stale events arriving late from Track A or Track B
        arbitrator.onStateChange(state = 0, reqId = reqA) // Stale onended from A
        arbitrator.onStateChange(state = 1, reqId = reqB) // Stale playing from B
        arbitrator.onStateChange(state = 2, reqId = reqA) // Stale pause from A

        // Verify that stale events did NOT affect current state
        assertEquals(0, arbitrator.completedCount)
        assertEquals("C", arbitrator.currentlyPlayingTrackId)
        assertTrue(arbitrator.isPlaying)

        // Valid event arriving from Track C
        arbitrator.onStateChange(state = 0, reqId = reqC) // Legitimate completion of C
        assertEquals(1, arbitrator.completedCount)
        assertFalse(arbitrator.isPlaying)
    }

    @Test
    fun `artwork palette cache stores, retrieves, and returns cached palettes without redundant extraction`() {
        val testKey = "track_123"
        val customPalette = ArtworkPalette(
            primary = Color(0xFFFF0055),
            secondary = Color(0xFF00FFCC),
            tertiary = Color(0xFF9900FF)
        )

        // Verify initially uncached
        assertNull(ArtworkPaletteCache.getCached(testKey))

        // Put in cache
        ArtworkPaletteCache.put(testKey, customPalette)

        // Verify retrieved exactly
        val cached = ArtworkPaletteCache.getCached(testKey)
        assertNotNull(cached)
        assertEquals(customPalette.primary, cached?.primary)
        assertEquals(customPalette.secondary, cached?.secondary)
        assertEquals(customPalette.tertiary, cached?.tertiary)
    }

    @Test
    fun `audioQueueManager immediate index synchronization on new track selection`() {
        val queueManager = AudioQueueManager()
        val playlist = listOf(trackA, trackB, trackC)
        queueManager.setQueue(playlist, startIndex = 0)

        assertEquals("A", queueManager.state.currentTrack?.id)
        assertEquals(0, queueManager.state.currentIndex)

        // Direct tap on Track C in queue
        queueManager.playTrack(trackC)
        assertEquals("C", queueManager.state.currentTrack?.id)
        assertEquals(2, queueManager.state.currentIndex)
    }
}
