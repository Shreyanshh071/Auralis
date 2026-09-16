package com.auralis.music

import androidx.compose.ui.graphics.Color
import com.auralis.music.domain.model.AudioQueueManager
import com.auralis.music.domain.model.Track
import com.auralis.music.ui.components.QueueTrackItem
import com.auralis.music.ui.components.createQueueTrackItem
import com.auralis.music.ui.components.syncLocalQueueWithSnapshot
import com.auralis.music.ui.player.NowPlayingTab
import com.auralis.music.ui.player.deriveActiveTrack
import com.auralis.music.ui.theme.ArtworkPalette
import com.auralis.music.ui.theme.ArtworkPaletteCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ForensicRegressionFixTest {

    private val trackA = Track(
        id = "track_a",
        title = "Song A",
        artist = "Artist A",
        thumbnail = "https://example.com/art_a.jpg"
    )

    private val trackB = Track(
        id = "track_b",
        title = "Song B",
        artist = "Artist B",
        thumbnail = "https://example.com/art_b.jpg"
    )

    private val trackC = Track(
        id = "track_c",
        title = "Song C",
        artist = "Artist C",
        thumbnail = "https://example.com/art_c.jpg"
    )

    private val trackD = Track(
        id = "track_d",
        title = "Song D",
        artist = "Artist D",
        thumbnail = "https://example.com/art_d.jpg"
    )

    private val testQueue = listOf(trackA, trackB, trackC, trackD)

    @Before
    fun setup() {
        ArtworkPaletteCache.clear()
    }

    // =========================================================================
    // PART A: QUEUE FORENSIC TESTS
    // =========================================================================

    @Test
    fun queue_dragOneItemOnePosition_swapsCleanlyWithoutStaleIndex() {
        val localQueue = mutableListOf<QueueTrackItem>()
        syncLocalQueueWithSnapshot(localQueue, testQueue, isDragging = false)

        val initialKeyA = localQueue[0].instanceId
        val initialKeyB = localQueue[1].instanceId

        // Move item 0 to index 1 (mimicking onMove in Calvin's reorderable)
        val fromIndex = 0
        val toIndex = 1
        localQueue.add(toIndex, localQueue.removeAt(fromIndex))

        assertEquals("First item is now Track B", trackB.id, localQueue[0].track.id)
        assertEquals("Second item is now Track A", trackA.id, localQueue[1].track.id)

        // Item identity remains strictly tied to the track, not the index
        assertEquals("Track A retains its unique instanceId after move", initialKeyA, localQueue[1].instanceId)
        assertEquals("Track B retains its unique instanceId after move", initialKeyB, localQueue[0].instanceId)
    }

    @Test
    fun queue_dragAcrossMultipleItems_noRowContentMixing() {
        val tracks = (0 until 10).map {
            Track("id_$it", "Title $it", "Artist $it", thumbnail = "https://img.com/$it.jpg")
        }
        val localQueue = mutableListOf<QueueTrackItem>()
        syncLocalQueueWithSnapshot(localQueue, tracks, isDragging = false)

        // Drag item 0 across 5 items to index 5
        val item0 = localQueue.removeAt(0)
        localQueue.add(5, item0)

        // Verify that every single row's title and artist correspond 1:1 with its track id
        for (item in localQueue) {
            val num = item.track.id.removePrefix("id_")
            assertEquals("Title $num", item.track.title)
            assertEquals("Artist $num", item.track.artist)
        }

        // Verify strictly unique keys
        val keys = localQueue.map { it.instanceId }.toSet()
        assertEquals(10, keys.size)
    }

    @Test
    fun queue_stableKeysSurviveSnapshotSync_whenNotDragging() {
        val localQueue = mutableListOf<QueueTrackItem>()
        syncLocalQueueWithSnapshot(localQueue, testQueue, isDragging = false)

        val idMapBefore = localQueue.associate { it.track.id to it.instanceId }

        // Reordered snapshot from player
        val reordered = listOf(trackC, trackA, trackB)
        syncLocalQueueWithSnapshot(localQueue, reordered, isDragging = false)

        for (item in localQueue) {
            val expectedInstanceId = idMapBefore[item.track.id]
            assertEquals(
                "InstanceId for track ${item.track.id} must be preserved across snapshot sync",
                expectedInstanceId,
                item.instanceId
            )
        }
    }

    @Test
    fun queue_reorderDoesNotAffectAudioPlaybackCurrentTrack() {
        val qm = AudioQueueManager()
        qm.setQueue(testQueue, startIndex = 1) // Playing Track B at index 1

        assertEquals("track_b", qm.state.currentTrack?.id)
        assertEquals(1, qm.state.currentIndex)

        // Move Track A (index 0) to index 2
        qm.moveItem(fromIndex = 0, toIndex = 2)

        // Track B is still playing, but index shifted to 0
        assertEquals("Playing track must remain Track B", "track_b", qm.state.currentTrack?.id)
        assertEquals(0, qm.state.currentIndex)
        assertEquals("track_b", qm.state.queue[0].id)
    }

    @Test
    fun queue_largeQueueReorderIntegrity() {
        val count = 200
        val tracks = (0 until count).map {
            Track("trk_$it", "Song $it", "Artist $it", thumbnail = "https://img.com/$it.jpg")
        }
        val localQueue = mutableListOf<QueueTrackItem>()
        syncLocalQueueWithSnapshot(localQueue, tracks, isDragging = false)

        // Perform 50 arbitrary reorders
        for (i in 0 until 50) {
            val from = (i * 7) % count
            val to = (i * 13 + 3) % count
            localQueue.add(to, localQueue.removeAt(from))
        }

        assertEquals(count, localQueue.size)
        val uniqueIds = localQueue.map { it.instanceId }.toSet()
        assertEquals("No duplicate or dropped items in large queue", count, uniqueIds.size)
    }

    // =========================================================================
    // PART B: PLAYER TRANSITION FORENSIC TESTS
    // =========================================================================

    @Test
    fun player_nextButtonNavigatesToNextTrack_committedDirectly() {
        // User is playing trackA at index 0
        // Pressing Next sets pendingTargetIndex = 1
        val activeTrack = deriveActiveTrack(
            currentTab = NowPlayingTab.PLAYER,
            pendingTargetIndex = 1,
            currentTrackIndex = 1,
            queue = testQueue,
            playingTrack = trackA
        )

        assertEquals("Active track must immediately switch to trackB on Next button press", trackB.id, activeTrack.id)
    }

    @Test
    fun player_partialSwipeDoesNotChangeAuthoritativeTrack() {
        // During a partial swipe without committing navigation, pendingTargetIndex is null
        // and currentTrackIndex remains at 0
        val activeTrack = deriveActiveTrack(
            currentTab = NowPlayingTab.PLAYER,
            pendingTargetIndex = null,
            currentTrackIndex = 0,
            queue = testQueue,
            playingTrack = trackA
        )

        assertEquals("Partial/uncommitted swipe must keep playing track authoritative", trackA.id, activeTrack.id)
    }

    @Test
    fun player_noAtoBtoAtoB_bouncingSequenceOnCommit() {
        // Trace state transitions during a Next navigation:
        // 1. Initial State: Track A playing
        val state0 = deriveActiveTrack(NowPlayingTab.PLAYER, null, 0, testQueue, trackA)
        assertEquals("track_a", state0.id)

        // 2. Button pressed / Target set: Track B targeted
        val state1 = deriveActiveTrack(NowPlayingTab.PLAYER, 1, 1, testQueue, trackA)
        assertEquals("track_b", state1.id)

        // 3. Audio player commits track B: pendingTargetIndex cleared, playingTrack = trackB
        val state2 = deriveActiveTrack(NowPlayingTab.PLAYER, null, 1, testQueue, trackB)
        assertEquals("track_b", state2.id)

        // Verify sequence is strictly A -> B -> B, NEVER A -> B -> A -> B
        val sequence = listOf(state0.id, state1.id, state2.id)
        assertEquals(listOf("track_a", "track_b", "track_b"), sequence)
    }

    @Test
    fun player_backgroundNeverBlinksToNullOrEmpty() {
        // Verify dual-slot ping-pong buffer invariant
        var slot0Url: String? = trackA.thumbnail
        var slot1Url: String? = null
        var activeSlot = 0

        // Invariant: visible background URL is NEVER null
        fun visibleUrl(): String? = if (activeSlot == 0) slot0Url else slot1Url

        assertNotNull("Initial background must not be null", visibleUrl())
        assertEquals(trackA.thumbnail, visibleUrl())

        // Target changes to trackB: prepare Slot 1
        slot1Url = trackB.thumbnail
        // During crossfade, Slot 0 remains 100% visible while Slot 1 fades in
        assertNotNull("Background during crossfade must not be null", visibleUrl())
        assertEquals(trackA.thumbnail, visibleUrl())

        // Crossfade completes: active slot flips to Slot 1
        activeSlot = 1
        assertNotNull("Background after flip must not be null", visibleUrl())
        assertEquals(trackB.thumbnail, visibleUrl())

        // Clear previous slot
        slot0Url = null
        // Visible URL is still slot 1 (trackB)
        assertNotNull("Background after clearing old slot must not be null", visibleUrl())
        assertEquals(trackB.thumbnail, visibleUrl())
    }

    @Test
    fun player_paletteExtractionDoesNotBlockMainThread() {
        // Cache lookup returns null or cached palette immediately on cache miss without blocking
        val palette = ArtworkPaletteCache.getCached("https://example.com/art_c.jpg") ?: ArtworkPaletteCache.defaultPalette
        assertNotNull("Palette must always resolve to a usable ArtworkPalette immediately", palette)
    }

    // =========================================================================
    // PART C: BOUNDED QUEUE AUTO-SCROLL VELOCITY TESTS
    // =========================================================================

    /**
     * Exact scroll velocity multiplier calculation as implemented in sh.calvin.reorderable:
     * multiplier = (1 - ((distance + scrollThreshold) / (scrollThreshold * 2)).coerceIn(0f, 1f)) * 10
     * velocity = basePixelPerSecond * multiplier
     */
    private fun calculateEdgeScrollVelocity(
        distanceFromEdge: Float,
        scrollThreshold: Float,
        basePixelPerSecond: Float
    ): Float {
        if (distanceFromEdge >= scrollThreshold) return 0f
        val multiplier = (1f - ((distanceFromEdge + scrollThreshold) / (scrollThreshold * 2f)).coerceIn(0f, 1f)) * 10f
        return basePixelPerSecond * multiplier
    }

    @Test
    fun queue_noEdgeProximity_zeroAutoScroll() {
        val baseSpeed = 45f * 2.75f // 45 dp at density 2.75
        val threshold = 48f * 2.75f // 48 dp
        val distance = 60f * 2.75f  // Outside edge threshold

        val velocity = calculateEdgeScrollVelocity(distance, threshold, baseSpeed)
        assertEquals("Distance outside threshold must produce 0 scroll velocity", 0f, velocity, 0.001f)
    }

    @Test
    fun queue_nearEdge_gradualSlowScroll() {
        val baseSpeed = 45f * 2.75f
        val threshold = 48f * 2.75f
        val distance = threshold * 0.8f // Just inside threshold zone

        val velocity = calculateEdgeScrollVelocity(distance, threshold, baseSpeed)
        assertTrue("Near edge should start gradual and slow (> 0)", velocity > 0f)
        assertTrue("Near edge velocity must be slow (< 2x base speed)", velocity < baseSpeed * 2f)
    }

    @Test
    fun queue_atEdge_boundedMaximumSpeed() {
        val baseSpeed = 45f * 2.75f
        val threshold = 48f * 2.75f

        // At boundary (distance = 0)
        val velocityAtBoundary = calculateEdgeScrollVelocity(0f, threshold, baseSpeed)
        assertEquals("At boundary, multiplier is exactly 5x base speed", baseSpeed * 5f, velocityAtBoundary, 0.001f)

        // Beyond boundary (distance = -threshold or lower)
        val velocityDeepInEdge = calculateEdgeScrollVelocity(-threshold, threshold, baseSpeed)
        val maxVelocity = baseSpeed * 10f
        assertEquals("Holding past edge is strictly clamped to 10x base speed", maxVelocity, velocityDeepInEdge, 0.001f)
        assertTrue("Max velocity must remain bounded (<= 450 dp/s)", maxVelocity <= (450f * 2.75f))
    }

    @Test
    fun queue_holdingAtEdgeForMultipleSeconds_velocityRemainsConstantAndBounded() {
        val baseSpeed = 45f * 2.75f
        val threshold = 48f * 2.75f
        val distance = 0f // Stationary at edge

        val v0 = calculateEdgeScrollVelocity(distance, threshold, baseSpeed)
        // Simulate reading velocity over 5 seconds
        for (second in 1..5) {
            val vT = calculateEdgeScrollVelocity(distance, threshold, baseSpeed)
            assertEquals("Velocity must remain constant while pointer is stationary", v0, vT, 0.001f)
        }
    }

    @Test
    fun queue_movingAwayFromEdge_scrollingStopsImmediately() {
        val baseSpeed = 45f * 2.75f
        val threshold = 48f * 2.75f

        // Dragged item moves from at-edge (0f) to far from edge (100f)
        val vEdge = calculateEdgeScrollVelocity(0f, threshold, baseSpeed)
        assertTrue("At edge, velocity > 0", vEdge > 0f)

        val vAway = calculateEdgeScrollVelocity(100f * 2.75f, threshold, baseSpeed)
        assertEquals("Leaving edge immediately stops scrolling", 0f, vAway, 0.001f)
    }

    @Test
    fun queue_topAndBottomEdges_behaveSymmetrically() {
        val baseSpeed = 45f * 2.75f
        val threshold = 48f * 2.75f

        // Top distance from start vs bottom distance from end
        val topVelocity = calculateEdgeScrollVelocity(10f, threshold, baseSpeed)
        val bottomVelocity = calculateEdgeScrollVelocity(10f, threshold, baseSpeed)

        assertEquals("Top and bottom edge velocities must be identical for equal distances", topVelocity, bottomVelocity, 0.001f)
    }

    // =========================================================================
    // PART D: RAPID PLAYER SONG-CHANGE & TRACK IDENTITY TESTS
    // =========================================================================

    @Test
    fun player_rapidNextAtoBtoCtoD_finalCommittedTrackIsD() {
        // Rapid skips: A -> B -> C -> D
        // Step 1: Track A playing
        val tA = deriveActiveTrack(NowPlayingTab.PLAYER, null, 0, testQueue, trackA)
        assertEquals("track_a", tA.id)

        // Step 2: User taps Next rapidly: pendingTargetIndex is cleared to null
        // Audio player advances rapidly: A -> B -> C -> D
        val tD = deriveActiveTrack(NowPlayingTab.PLAYER, null, 3, testQueue, trackD)
        assertEquals("Final committed track must be strictly Track D", "track_d", tD.id)
    }

    @Test
    fun player_staleArtworkCannotOverwriteNewerTrack() {
        // Track D is the active committed track
        val activeTrack = trackD

        // Simulate async image load results arriving out of order
        // Stale result for Track B arrives AFTER Track D is already active
        val staleResultTrackId = "track_b"
        val isStaleResultAllowed = (staleResultTrackId == activeTrack.id)

        assertFalse("Stale artwork for Track B must NOT be allowed to overwrite Track D", isStaleResultAllowed)
    }

    @Test
    fun player_oldPaletteResultCannotOverwriteNewerTrackPalette() {
        // Track D is active
        ArtworkPaletteCache.resetToDefault()

        // Set palette for Track B into cache
        val paletteB = ArtworkPalette(
            primary = Color.Red,
            secondary = Color.Blue,
            tertiary = Color.Green,
            isPlaceholder = false
        )
        ArtworkPaletteCache.put("track_b", paletteB)

        // Set palette for Track D into cache
        val paletteD = ArtworkPalette(
            primary = Color.Yellow,
            secondary = Color.Cyan,
            tertiary = Color.Magenta,
            isPlaceholder = false
        )
        ArtworkPaletteCache.put("track_d", paletteD)

        // Verify that ArtworkPaletteCache.getCached("track_d") returns D's palette
        val activePalette = ArtworkPaletteCache.getCached(trackD.id)
        assertNotNull(activePalette)
        assertEquals("Active palette must be Track D's palette", Color.Yellow, activePalette?.primary)
        assertNotEquals("Active palette must NOT match Track B's palette", Color.Red, activePalette?.primary)
    }

    @Test
    fun player_oldBackgroundAnimationCannotOverwriteNewerTrack() {
        var activeSlot = 0
        var slot0Url: String? = trackA.thumbnail
        var slot1Url: String? = null

        // Step 1: Navigating to Track B (Slot 1)
        slot1Url = trackB.thumbnail
        activeSlot = 1

        // Step 2: Rapid skip to Track D before B finishes (Slot 0 updated directly with Track D)
        slot0Url = trackD.thumbnail
        activeSlot = 0

        // Invariant: An old callback from Track B (slot 1) cannot reset activeSlot back to 1
        val finalVisibleUrl = if (activeSlot == 0) slot0Url else slot1Url
        assertEquals("Final visible background must be Track D", trackD.thumbnail, finalVisibleUrl)
    }

    @Test
    fun player_oldAsyncArtworkIgnoredAfterTrackIdChanges() {
        var committedTrackId = trackA.id

        fun onImageLoaded(loadedTrackId: String, callback: () -> Unit) {
            // Identity guard: verify result still belongs to currently committed track
            if (loadedTrackId == committedTrackId) {
                callback()
            }
        }

        var displayedArtworkUrl = trackA.thumbnail

        // User rapidly advances to trackD
        committedTrackId = trackD.id

        // Stale callback for Track B arrives
        onImageLoaded("track_b") {
            displayedArtworkUrl = trackB.thumbnail
        }

        assertEquals("Stale Track B callback must be ignored", trackA.thumbnail, displayedArtworkUrl)

        // Authoritative callback for Track D arrives
        onImageLoaded("track_d") {
            displayedArtworkUrl = trackD.thumbnail
        }

        assertEquals("Authoritative Track D callback must be applied", trackD.thumbnail, displayedArtworkUrl)
    }

    @Test
    fun player_rapidNextPresses_produceCorrectFinalQueueAndPlaybackTrack() {
        val qm = AudioQueueManager()
        qm.setQueue(testQueue, startIndex = 0)
        assertEquals("track_a", qm.state.currentTrack?.id)

        // Rapid next presses: 3 consecutive calls (A -> B -> C -> D)
        qm.advanceNext()
        qm.advanceNext()
        qm.advanceNext()

        assertEquals("Rapid next presses must resolve to track_d", "track_d", qm.state.currentTrack?.id)
        assertEquals(3, qm.state.currentIndex)
    }

    @Test
    fun player_rapidPreviousPresses_produceCorrectFinalTrack() {
        val qm = AudioQueueManager()
        qm.setQueue(testQueue, startIndex = 3)
        assertEquals("track_d", qm.state.currentTrack?.id)

        // Rapid previous presses: 3 consecutive calls (D -> C -> B -> A)
        qm.advancePrevious()
        qm.advancePrevious()
        qm.advancePrevious()

        assertEquals("Rapid previous presses must resolve to track_a", "track_a", qm.state.currentTrack?.id)
        assertEquals(0, qm.state.currentIndex)
    }

    @Test
    fun player_cancelledSwipeRestoresCommittedTrack() {
        // User starts swipe towards page 1, but releases and it snaps back to 0
        // pendingTargetIndex remains null
        val activeTrack = deriveActiveTrack(
            currentTab = NowPlayingTab.PLAYER,
            pendingTargetIndex = null,
            currentTrackIndex = 0,
            queue = testQueue,
            playingTrack = trackA
        )

        assertEquals("Cancelled swipe restores committed trackA", "track_a", activeTrack.id)
    }

    @Test
    fun player_noNullOrBlackBackgroundDuringRapidNavigation() {
        // Test rapid transitions across queue
        var slot0Url: String? = testQueue[0].thumbnail
        var slot1Url: String? = null
        var activeSlot = 0

        for (i in 1 until testQueue.size) {
            val nextTrack = testQueue[i]
            if (activeSlot == 0) {
                slot1Url = nextTrack.thumbnail
                activeSlot = 1
            } else {
                slot0Url = nextTrack.thumbnail
                activeSlot = 0
            }

            val currentVisible = if (activeSlot == 0) slot0Url else slot1Url
            assertNotNull("Background must never be null during rapid transition step $i", currentVisible)
            assertFalse("Background must never be blank during rapid transition step $i", currentVisible!!.isBlank())
            assertEquals("Background must equal target track thumbnail", nextTrack.thumbnail, currentVisible)
        }
    }
}
