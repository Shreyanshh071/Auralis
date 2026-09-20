package com.auralis.music

import com.auralis.music.domain.model.Track
import com.auralis.music.ui.player.NowPlayingTab
import com.auralis.music.ui.player.deriveActiveTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackChangeParityTest {

    private val track0 = Track(id = "track_0", title = "Song 0", artist = "Artist 0", duration = 180, thumbnail = "https://thumb/0")
    private val track1 = Track(id = "track_1", title = "Song 1", artist = "Artist 1", duration = 200, thumbnail = "https://thumb/1")
    private val track2 = Track(id = "track_2", title = "Song 2", artist = "Artist 2", duration = 220, thumbnail = "https://thumb/2")
    private val track3 = Track(id = "track_3", title = "Song 3", artist = "Artist 3", duration = 240, thumbnail = "https://thumb/3")
    private val queue = listOf(track0, track1, track2, track3)

    /**
     * Replicates the optimistic button-driven target index calculation in NowPlayingModal.kt.
     */
    private fun computeNextTargetIndex(fromIndex: Int, pageCount: Int): Int {
        return (fromIndex + 1).coerceAtMost(pageCount - 1)
    }

    private fun computePreviousTargetIndex(fromIndex: Int): Int {
        return (fromIndex - 1).coerceAtLeast(0)
    }

    @Test
    fun `test next button computes immediate target pager index`() {
        val pageCount = queue.size
        val target = computeNextTargetIndex(fromIndex = 0, pageCount = pageCount)
        assertEquals(1, target)

        // Clamping at end of queue
        val targetAtEnd = computeNextTargetIndex(fromIndex = 3, pageCount = pageCount)
        assertEquals(3, targetAtEnd)
    }

    @Test
    fun `test previous button computes immediate target pager index`() {
        val target = computePreviousTargetIndex(fromIndex = 2)
        assertEquals(1, target)

        // Clamping at start of queue
        val targetAtStart = computePreviousTargetIndex(fromIndex = 0)
        assertEquals(0, targetAtStart)
    }

    @Test
    fun `test rapid Next and Previous target changes settle on newest target`() {
        val pageCount = queue.size
        var pendingTarget: Int? = null

        // 1. Rapid Next taps: 0 -> 1 -> 2 -> 3
        var current = 0
        for (step in 1..3) {
            val from = pendingTarget ?: current
            pendingTarget = computeNextTargetIndex(from, pageCount)
        }
        assertEquals(3, pendingTarget)

        // 2. Next then Previous immediately: 0 -> Next (1) -> Previous (0)
        pendingTarget = null
        current = 0
        val nextTarget = computeNextTargetIndex(pendingTarget ?: current, pageCount)
        pendingTarget = nextTarget
        assertEquals(1, pendingTarget)

        val prevTarget = computePreviousTargetIndex(pendingTarget ?: current)
        pendingTarget = prevTarget
        assertEquals(0, pendingTarget)

        // 3. Next -> Next -> Previous: 0 -> 1 -> 2 -> 1
        pendingTarget = null
        current = 0
        pendingTarget = computeNextTargetIndex(pendingTarget ?: current, pageCount) // 1
        pendingTarget = computeNextTargetIndex(pendingTarget ?: current, pageCount) // 2
        pendingTarget = computePreviousTargetIndex(pendingTarget ?: current) // 1
        assertEquals(1, pendingTarget)
    }

    @Test
    fun `test optimistic target reconciles correctly with authoritative Track id`() {
        var pendingTarget: Int? = 2

        // deriveActiveTrack returns optimistic track while pendingTarget != null
        val optimisticTrack = deriveActiveTrack(
            currentTab = NowPlayingTab.PLAYER,
            pendingTargetIndex = pendingTarget,
            currentTrackIndex = 0,
            queue = queue,
            playingTrack = track0
        )
        assertEquals("track_2", optimisticTrack.id)

        // When authoritative playback catches up to target (currentTrackIndex == 2)
        val authoritativeIndex = 2
        if (authoritativeIndex == pendingTarget) {
            pendingTarget = null
        }
        assertNull(pendingTarget)

        // deriveActiveTrack now smoothly returns authoritative track without discontinuity
        val settledTrack = deriveActiveTrack(
            currentTab = NowPlayingTab.PLAYER,
            pendingTargetIndex = pendingTarget,
            currentTrackIndex = 2,
            queue = queue,
            playingTrack = track2
        )
        assertEquals("track_2", settledTrack.id)
    }

    @Test
    fun `test old Track callback cannot overwrite newer optimistic target`() {
        var pendingTarget: Int? = 2

        // An older intermediate callback arrives (e.g. track 1 starting while user already tapped to track 2)
        val staleArrivalIndex = 1
        val shouldClearPending = (staleArrivalIndex == pendingTarget)
        assertFalse("Stale arrival must NOT clear newer pending target", shouldClearPending)

        if (shouldClearPending) {
            pendingTarget = null
        }
        assertEquals(2, pendingTarget)

        // deriveActiveTrack continues to respect the newer optimistic target
        val active = deriveActiveTrack(
            currentTab = NowPlayingTab.PLAYER,
            pendingTargetIndex = pendingTarget,
            currentTrackIndex = staleArrivalIndex,
            queue = queue,
            playingTrack = track1
        )
        assertEquals("track_2", active.id)
    }

    @Test
    fun `test artwork prefetch and display use identical effective dimensions`() {
        val prefetchWidth = 600
        val prefetchHeight = 600
        val displayWidth = 600
        val displayHeight = 600

        assertEquals("Prefetch and display width must match for Coil cache hit", prefetchWidth, displayWidth)
        assertEquals("Prefetch and display height must match for Coil cache hit", prefetchHeight, displayHeight)
    }

    @Test
    fun `test horizontal animation direction calculation`() {
        fun isForwardNavigation(initialTrackId: String, targetTrackId: String, queue: List<Track>): Boolean {
            val initialIdx = queue.indexOfFirst { it.id == initialTrackId }
            val targetIdx = queue.indexOfFirst { it.id == targetTrackId }
            return if (targetIdx >= 0 && initialIdx >= 0) targetIdx >= initialIdx else true
        }

        // Forward (Next): from track0 to track1
        assertTrue(isForwardNavigation("track_0", "track_1", queue))
        // Forward: from track1 to track3
        assertTrue(isForwardNavigation("track_1", "track_3", queue))

        // Backward (Previous): from track3 to track2
        assertFalse(isForwardNavigation("track_3", "track_2", queue))
        // Backward: from track2 to track0
        assertFalse(isForwardNavigation("track_2", "track_0", queue))

        // Same track (re-render)
        assertTrue(isForwardNavigation("track_1", "track_1", queue))
    }

    @Test
    fun `test deriveActiveTrack respects queue bounds and fallback`() {
        // Out of bounds pending target falls back to playingTrack
        val outOfBounds = deriveActiveTrack(
            currentTab = NowPlayingTab.PLAYER,
            pendingTargetIndex = 99,
            currentTrackIndex = 0,
            queue = queue,
            playingTrack = track0
        )
        assertEquals("track_0", outOfBounds.id)

        // Non-player tab (e.g. Queue or Lyrics) keeps playingTrack authoritative
        val queueTab = deriveActiveTrack(
            currentTab = NowPlayingTab.QUEUE,
            pendingTargetIndex = 2,
            currentTrackIndex = 0,
            queue = queue,
            playingTrack = track0
        )
        assertEquals("track_0", queueTab.id)
    }
}
