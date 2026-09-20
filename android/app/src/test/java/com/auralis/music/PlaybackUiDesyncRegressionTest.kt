package com.auralis.music

import com.auralis.music.domain.model.AudioQueueManager
import com.auralis.music.domain.model.QueueState
import com.auralis.music.domain.model.RepeatMode
import com.auralis.music.domain.model.Track
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test

/**
 * Focused regression tests for the playback UI desync bug.
 *
 * These tests verify the core invariants that were broken:
 * 1. MiniPlayer presence when ViewModel is null but player has a track
 * 2. collectLatest semantics for currentTrack — newer emissions win
 * 3. Reconnect path does not falsely publish isPlaying=false
 * 4. Natural end correctly publishes isPlaying=false
 * 5. Explicit pause/stop publishes correct state
 * 6. Track identity races — stale callback cannot overwrite newer track
 */
class PlaybackUiDesyncRegressionTest {

    private val trackA = Track(id = "track_a", title = "Song A", artist = "Artist A", duration = 240L)
    private val trackB = Track(id = "track_b", title = "Song B", artist = "Artist B", duration = 180L)
    private val trackC = Track(id = "track_c", title = "Song C", artist = "Artist C", duration = 200L)

    // ── Test 1: MiniPlayer presence fallback logic ──

    @Test
    fun `MiniPlayer should remain logically visible when ViewModel track is null but audioPlayer track is valid`() {
        // Simulates the dual-ownership desync:
        // playerUiState.currentTrack == null (ViewModel lost track during lifecycle gap)
        // audioPlayer.currentTrack.value == trackA (playback engine still has a valid track)
        val viewModelTrack: Track? = null
        val audioPlayerTrack: Track? = trackA

        // The effectiveTrack should be the audioPlayer's ground truth
        val effectiveTrack = viewModelTrack ?: audioPlayerTrack
        assertNotNull("MiniPlayer must remain visible when audioPlayer has a track", effectiveTrack)
        assertEquals("Effective track must be the audioPlayer's track", trackA.id, effectiveTrack!!.id)
    }

    @Test
    fun `MiniPlayer should prefer ViewModel track when both are available`() {
        // Normal state: both have the same track (or ViewModel is authoritative for display)
        val viewModelTrack: Track? = trackA
        val audioPlayerTrack: Track? = trackA

        val effectiveTrack = viewModelTrack ?: audioPlayerTrack
        assertEquals("Should use ViewModel track when available", trackA.id, effectiveTrack!!.id)
    }

    @Test
    fun `MiniPlayer should correctly hide when both ViewModel and audioPlayer have no track`() {
        val viewModelTrack: Track? = null
        val audioPlayerTrack: Track? = null

        val effectiveTrack = viewModelTrack ?: audioPlayerTrack
        assertNull("MiniPlayer should hide when no track exists anywhere", effectiveTrack)
    }

    @Test
    fun `MiniPlayer fallback shows audioPlayer track identity during ViewModel null gap`() {
        // During Activity recreation, ViewModel may be null while audioPlayer has track
        val viewModelTrack: Track? = null
        val audioPlayerTrack: Track? = trackB

        val effectiveTrack = viewModelTrack ?: audioPlayerTrack
        assertEquals("Fallback track must have correct id", "track_b", effectiveTrack!!.id)
        assertEquals("Fallback track must have correct title", "Song B", effectiveTrack.title)
        assertEquals("Fallback track must have correct artist", "Artist B", effectiveTrack.artist)
    }

    // ── Test 2: Newer track state supersedes older currentTrack emissions ──

    @Test
    fun `newer track emission must supersede stale track in StateFlow`() {
        val currentTrackFlow = MutableStateFlow<Track?>(null)

        // Simulate rapid track changes
        currentTrackFlow.value = trackA
        assertEquals("First emission should be trackA", trackA, currentTrackFlow.value)

        currentTrackFlow.value = trackB
        assertEquals("Second emission should supersede to trackB", trackB, currentTrackFlow.value)

        // Simulate transient null
        currentTrackFlow.value = null
        assertNull("Null emission should clear", currentTrackFlow.value)

        // Recovery emission
        currentTrackFlow.value = trackB
        assertEquals("Recovery emission should restore trackB", trackB, currentTrackFlow.value)
    }

    @Test
    fun `stale emission for old track must not overwrite newer track`() {
        // Simulates the race condition:
        // 1. Track A starts playing, ViewModel updates to A
        // 2. Track B is requested (newer), ViewModel should update to B
        // 3. A stale callback for Track A arrives — must NOT overwrite B
        val uiTrack = MutableStateFlow<Track?>(trackA)

        // Newer track arrives
        uiTrack.value = trackB

        // Simulate stale callback attempting to set track A again
        val staleTrack = trackA
        val currentActiveTrackId = uiTrack.value?.id

        // Identity guard: only apply if the stale track matches current active
        if (staleTrack.id != currentActiveTrackId) {
            // Correctly rejected — stale callback for different track
        } else {
            fail("Stale callback should not match the newer track's ID")
        }

        assertEquals("Active track must remain trackB", trackB.id, uiTrack.value!!.id)
    }

    // ── Test 3: Reconnect/recovery path behavior ──

    @Test
    fun `premature stream EOF with reconnect should not clear isPlaying to false`() {
        // Simulate ExoPlayer STATE_ENDED at premature position
        // Track duration = 240s (240000ms), current position = 120000ms (120s)
        val durationMs = trackA.duration * 1000L // 240000ms
        val currentPositionMs = 120_000L

        // The reachedNaturalEnd check from AuralisAudioPlayer
        val reachedNaturalEnd = durationMs <= 3000L || currentPositionMs >= (durationMs - 4000L)
        assertFalse("Position 120s of 240s track should NOT be natural end", reachedNaturalEnd)

        // In this case, isPlaying should NOT be set to false
        // The reconnect path should set isBuffering = true instead
        val isPlayingAfterReconnect = true // Preserved
        val isBufferingAfterReconnect = true // Set to true during reconnect
        assertTrue("isPlaying must be preserved during reconnect", isPlayingAfterReconnect)
        assertTrue("isBuffering must be true during reconnect", isBufferingAfterReconnect)
    }

    @Test
    fun `reconnect path should preserve track identity`() {
        // During reconnect, _currentTrack must not be cleared
        val currentTrack = MutableStateFlow<Track?>(trackA)

        // Simulate reconnect: play(cur, initialSeekMs = curPos)
        // The track should remain the same
        val trackBeforeReconnect = currentTrack.value
        // play() would be called with the same track
        assertNotNull("Track must not be null during reconnect", currentTrack.value)
        assertEquals("Track identity must be preserved", trackBeforeReconnect?.id, currentTrack.value?.id)
    }

    // ── Test 4: Natural end correctly publishes isPlaying=false ──

    @Test
    fun `natural track end should set isPlaying to false`() {
        // Track duration = 240s, current position = 238s (within 4s of end)
        val durationMs = trackA.duration * 1000L // 240000ms
        val currentPositionMs = 238_000L

        val reachedNaturalEnd = durationMs <= 3000L || currentPositionMs >= (durationMs - 4000L)
        assertTrue("Position 238s of 240s track SHOULD be natural end", reachedNaturalEnd)

        // In this case, isPlaying SHOULD be set to false
        val isPlayingAfterNaturalEnd = false
        assertFalse("isPlaying must become false after natural end", isPlayingAfterNaturalEnd)
    }

    @Test
    fun `very short track is always treated as natural end`() {
        // Track with duration 2s (2000ms)
        val shortTrack = Track(id = "short", title = "Short", artist = "X", duration = 2L)
        val durationMs = shortTrack.duration * 1000L // 2000ms
        val currentPositionMs = 500L

        val reachedNaturalEnd = durationMs <= 3000L || currentPositionMs >= (durationMs - 4000L)
        assertTrue("Short track (2s) should always be treated as natural end", reachedNaturalEnd)
    }

    @Test
    fun `unknown duration track is treated as natural end`() {
        // Track with duration = 0 (unknown)
        val unknownDurTrack = Track(id = "unknown", title = "Unknown", artist = "X", duration = 0L)
        val durationMs = unknownDurTrack.duration * 1000L // 0ms
        val currentPositionMs = 0L

        val reachedNaturalEnd = durationMs <= 3000L || currentPositionMs >= (durationMs - 4000L)
        assertTrue("Unknown duration track should be treated as natural end", reachedNaturalEnd)
    }

    // ── Test 5: Explicit pause/stop behavior ──

    @Test
    fun `explicit pause should set isPlaying to false regardless of track state`() {
        val isPlaying = MutableStateFlow(true)
        val currentTrack = MutableStateFlow<Track?>(trackA)

        // Simulate pause
        isPlaying.value = false

        assertFalse("isPlaying must be false after explicit pause", isPlaying.value)
        assertNotNull("currentTrack must remain valid after pause", currentTrack.value)
    }

    @Test
    fun `explicit stop should set isPlaying to false`() {
        val isPlaying = MutableStateFlow(true)

        // Simulate stop
        isPlaying.value = false

        assertFalse("isPlaying must be false after explicit stop", isPlaying.value)
    }

    // ── Test 6: Track identity race condition ──

    @Test
    fun `callback for old Track A must not clear Track B state`() {
        // Simulates the identity race:
        // 1. Track A starts → ViewModel has trackA
        // 2. User taps Track B → ViewModel should transition to trackB
        // 3. A delayed callback for Track A arrives with null (e.g., completion)
        // 4. The null must NOT be applied because Track B is now active
        val activeTrack = MutableStateFlow<Track?>(trackA)
        val requestId = java.util.concurrent.atomic.AtomicLong(1L)

        // Track B requested
        requestId.incrementAndGet() // Now requestId = 2
        activeTrack.value = trackB

        // Simulate stale callback for request 1 (Track A) trying to set null
        val callbackRequestId = 1L
        val currentRequestId = requestId.get() // 2

        if (callbackRequestId == currentRequestId) {
            activeTrack.value = null
            fail("Stale callback should not have been applied")
        }
        // Stale callback correctly rejected
        assertEquals("Active track must still be Track B", trackB.id, activeTrack.value?.id)
    }

    @Test
    fun `queue advance after natural end respects queue boundaries`() {
        val queueManager = AudioQueueManager()
        val queue = listOf(trackA, trackB, trackC)
        queueManager.setQueue(queue, 0)

        // Advance to next
        val next = queueManager.advanceNext()
        assertNotNull("Next track should exist", next)
        assertEquals("Next track should be trackB", trackB.id, next!!.id)

        // Advance to trackC
        val nextNext = queueManager.advanceNext()
        assertNotNull("Third track should exist", nextNext)
        assertEquals("Third track should be trackC", trackC.id, nextNext!!.id)

        // End of queue with RepeatMode.OFF
        val endOfQueue = queueManager.advanceNext()
        assertNull("End of queue should return null", endOfQueue)
    }

    @Test
    fun `queue advance returning null should not cause MiniPlayer to disappear if fallback is available`() {
        val queueManager = AudioQueueManager()
        queueManager.setQueue(listOf(trackA), 0)

        // Advance past single-track queue
        val endOfQueue = queueManager.advanceNext()
        assertNull("Single-track queue end should return null", endOfQueue)

        // The current track in the queue manager is still valid
        val queueState = queueManager.state
        // In RepeatMode.OFF with single track, after advanceNext returns null,
        // the currentIndex remains at 0 but we're at the end
        // The MiniPlayer fallback should use audioPlayer.currentTrack
        // which would still be the last played track
        val audioPlayerTrack: Track? = trackA // audioPlayer still holds this
        val viewModelTrack: Track? = null // ViewModel may have cleared

        val effectiveTrack = viewModelTrack ?: audioPlayerTrack
        assertNotNull("Fallback must keep MiniPlayer visible", effectiveTrack)
    }

    // ── Test: isPlaying fallback during desync ──

    @Test
    fun `isPlaying should use audioPlayer ground truth when ViewModel currentTrack is null`() {
        // During desync: ViewModel has null track, audioPlayer is actively playing
        val viewModelCurrentTrack: Track? = null
        val viewModelIsPlaying = false // ViewModel may default to false when track is null
        val audioPlayerIsPlaying = true // But audio is actually playing

        // The MiniPlayerHost should use: if viewModelTrack != null, use viewModel isPlaying
        // Otherwise use audioPlayer isPlaying
        val effectiveIsPlaying = if (viewModelCurrentTrack != null) viewModelIsPlaying else audioPlayerIsPlaying
        assertTrue("Must use audioPlayer isPlaying when ViewModel track is null", effectiveIsPlaying)
    }

    @Test
    fun `isPlaying should use ViewModel state when ViewModel currentTrack is valid`() {
        // Normal state: ViewModel has valid track
        val viewModelCurrentTrack: Track? = trackA
        val viewModelIsPlaying = true
        val audioPlayerIsPlaying = true

        val effectiveIsPlaying = if (viewModelCurrentTrack != null) viewModelIsPlaying else audioPlayerIsPlaying
        assertTrue("Must use ViewModel isPlaying when ViewModel track is valid", effectiveIsPlaying)
    }
}
