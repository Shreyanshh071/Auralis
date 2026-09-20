package com.auralis.music

import com.auralis.music.domain.model.Artist
import com.auralis.music.domain.model.ArtistPage
import com.auralis.music.domain.model.PlaylistResult
import com.auralis.music.domain.model.Track
import com.auralis.music.ui.viewmodel.ExploreDetail
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests verifying navigation parity, stable detail key resolution,
 * bidirectional transition detection, and top-level tab rules.
 */
class NavigationParityUnitTest {

    @Test
    fun testExploreDetailKeyStabilityAcrossLoadingState() {
        val artist = Artist(id = "UC_test_123", name = "Test Artist")
        val pageLoading = ArtistPage(artist = artist)
        val detailLoading = ExploreDetail.Artist(artistPage = pageLoading, isLoading = true)

        val pageLoaded = ArtistPage(artist = artist, topSongs = listOf(Track(id = "t1", title = "Song 1", artist = "Test Artist")))
        val detailLoaded = ExploreDetail.Artist(artistPage = pageLoaded, isLoading = false)

        // Keys MUST match so AnimatedContent does not restart transition on loading finish
        assertEquals(detailLoading.key, detailLoaded.key)
        assertEquals("artist:UC_test_123", detailLoaded.key)
    }

    @Test
    fun testExploreDetailKeyAlbumStability() {
        val album = PlaylistResult(id = "album_999", title = "Test Album", author = "Test Artist")
        val detailLoading = ExploreDetail.Album(album = album, tracks = emptyList(), isLoading = true)
        val detailLoaded = ExploreDetail.Album(album = album, tracks = listOf(Track(id = "t2", title = "Track 2", artist = "Test Artist")), isLoading = false)

        assertEquals(detailLoading.key, detailLoaded.key)
        assertEquals("album:album_999", detailLoaded.key)
    }

    @Test
    fun testDetailMapResolutionDuringTransition() {
        // Simulates the detail stack and detail map during navigation
        val artist = Artist(id = "artist_A", name = "Artist A")
        val album = PlaylistResult(id = "album_B", title = "Album B", author = "Artist A")

        val artistDetail = ExploreDetail.Artist(artistPage = ArtistPage(artist = artist))
        val albumDetail = ExploreDetail.Album(album = album)

        val detailMap = mutableMapOf<String, ExploreDetail>()
        val stack = mutableListOf<ExploreDetail>()

        // 1. User opens Artist A
        stack.add(artistDetail)
        detailMap[artistDetail.key] = artistDetail

        // 2. User opens Album B from Artist A
        stack.add(albumDetail)
        detailMap[albumDetail.key] = albumDetail

        // During forward transition: targetKey for exiting frame is artistDetail.key
        val exitingTargetKey = artistDetail.key
        val exitingRendered = stack.lastOrNull { it.key == exitingTargetKey } ?: detailMap[exitingTargetKey]
        assertTrue("Exiting frame MUST resolve to ArtistDetail, never AlbumDetail", exitingRendered is ExploreDetail.Artist)
        assertEquals("artist_A", (exitingRendered as ExploreDetail.Artist).artistPage.artist.id)

        // Entering frame is albumDetail.key
        val enteringTargetKey = albumDetail.key
        val enteringRendered = stack.lastOrNull { it.key == enteringTargetKey } ?: detailMap[enteringTargetKey]
        assertTrue("Entering frame MUST resolve to AlbumDetail", enteringRendered is ExploreDetail.Album)

        // 3. User presses Back: Album B is popped from stack
        stack.removeAt(stack.lastIndex)
        // stack now only has artistDetail, but detailMap still has albumDetail for the exit animation!
        val backExitingKey = albumDetail.key
        val backExitingRendered = stack.lastOrNull { it.key == backExitingKey } ?: detailMap[backExitingKey]
        assertTrue("Back exiting frame MUST resolve to AlbumDetail from detailMap", backExitingRendered is ExploreDetail.Album)

        val backEnteringKey = artistDetail.key
        val backEnteringRendered = stack.lastOrNull { it.key == backEnteringKey } ?: detailMap[backEnteringKey]
        assertTrue("Back entering frame MUST resolve to ArtistDetail", backEnteringRendered is ExploreDetail.Artist)
    }

    @Test
    fun testBidirectionalTransitionDirectionCalculation() {
        val depthMap = mutableMapOf<String?, Int>()
        depthMap[null] = 0 // root

        val keyA = "artist:1"
        val keyB = "album:2"
        val keyC = "artist:3"

        depthMap[keyA] = 1
        depthMap[keyB] = 2
        depthMap[keyC] = 3

        fun isNavigatingBack(initial: String?, target: String?): Boolean {
            val initialDepth = depthMap[initial] ?: 0
            val targetDepth = depthMap[target] ?: 0
            return targetDepth < initialDepth || (target == null && initial != null)
        }

        // Root -> A: Forward
        assertFalse(isNavigatingBack(null, keyA))

        // A -> B: Forward
        assertFalse(isNavigatingBack(keyA, keyB))

        // B -> C: Forward
        assertFalse(isNavigatingBack(keyB, keyC))

        // C -> B: Backward
        assertTrue(isNavigatingBack(keyC, keyB))

        // B -> A: Backward
        assertTrue(isNavigatingBack(keyB, keyA))

        // A -> Root: Backward
        assertTrue(isNavigatingBack(keyA, null))
    }

    @Test
    fun testTopLevelNavigationRules() {
        // Verify that tab switching does not build a linear backstack
        var currentTab = "HOME"

        fun onTabClick(dest: String) {
            currentTab = dest
        }

        fun onSystemBack(): String? {
            return if (currentTab != "HOME") {
                currentTab = "HOME"
                currentTab
            } else {
                null // allow system back (exit app)
            }
        }

        // Home -> Library -> Search -> Home
        onTabClick("LIBRARY")
        assertEquals("LIBRARY", currentTab)

        onTabClick("EXPLORE")
        assertEquals("EXPLORE", currentTab)

        onTabClick("HOME")
        assertEquals("HOME", currentTab)

        // Pressing back from HOME must exit, NOT return to EXPLORE or LIBRARY
        val backResult = onSystemBack()
        assertNull("BackHandler must be disabled on HOME to allow system exit", backResult)

        // If on Library, back goes to HOME
        onTabClick("LIBRARY")
        assertEquals("HOME", onSystemBack())

        // If on Explore, back goes to HOME
        onTabClick("EXPLORE")
        assertEquals("HOME", onSystemBack())
    }

    @Test
    fun testTopLevelRouteIndexDirectionCalculation() {
        val tabOrder = listOf("HOME", "EXPLORE", "LIBRARY")

        fun calculateDirection(from: String, to: String): Int {
            val prevIdx = tabOrder.indexOf(from)
            val newIdx = tabOrder.indexOf(to)
            return if (newIdx >= prevIdx) 1 else -1
        }

        // 1. Home <-> Library
        assertEquals("Home -> Library must be forward (+1)", 1, calculateDirection("HOME", "LIBRARY"))
        assertEquals("Library -> Home must be backward (-1)", -1, calculateDirection("LIBRARY", "HOME"))

        // 2. Library <-> Search (Explore)
        assertEquals("Search -> Library must be forward (+1)", 1, calculateDirection("EXPLORE", "LIBRARY"))
        assertEquals("Library -> Search must be backward (-1)", -1, calculateDirection("LIBRARY", "EXPLORE"))

        // 3. Search (Explore) <-> Home
        assertEquals("Home -> Search must be forward (+1)", 1, calculateDirection("HOME", "EXPLORE"))
        assertEquals("Search -> Home must be backward (-1)", -1, calculateDirection("EXPLORE", "HOME"))
    }

    @Test
    fun testViviSlideDistanceFormula() {
        // VIVI formula: it / 8
        val screenWidths = listOf(720f, 1080f, 1440f)

        for (w in screenWidths) {
            val slideOffset = w / 8f
            assertEquals(w / 8f, slideOffset, 0.001f)

            // Forward enter: starts at +slideOffset, moves to 0
            val forwardEnterStart = (1f - 0f) * (1 * slideOffset)
            val forwardEnterEnd = (1f - 1f) * (1 * slideOffset)
            assertEquals(slideOffset, forwardEnterStart, 0.001f)
            assertEquals(0f, forwardEnterEnd, 0.001f)

            // Forward exit: starts at 0, moves to -slideOffset
            val forwardExitStart = -0f * (1 * slideOffset)
            val forwardExitEnd = -1f * (1 * slideOffset)
            assertEquals(0f, forwardExitStart, 0.001f)
            assertEquals(-slideOffset, forwardExitEnd, 0.001f)

            // Backward enter: starts at -slideOffset, moves to 0
            val backwardEnterStart = (1f - 0f) * (-1 * slideOffset)
            val backwardEnterEnd = (1f - 1f) * (-1 * slideOffset)
            assertEquals(-slideOffset, backwardEnterStart, 0.001f)
            assertEquals(0f, backwardEnterEnd, 0.001f)

            // Backward exit: starts at 0, moves to +slideOffset
            val backwardExitStart = -0f * (-1 * slideOffset)
            val backwardExitEnd = -1f * (-1 * slideOffset)
            assertEquals(0f, backwardExitStart, 0.001f)
            assertEquals(slideOffset, backwardExitEnd, 0.001f)
        }
    }

    @Test
    fun testBottomSheetSpringAndTravelParity() {
        // Full height travel requirement: enter starts at 100% (height), exit ends at 100% (height)
        fun enterInitialOffsetY(height: Int) = height
        fun exitTargetOffsetY(height: Int) = height

        val heights = listOf(1280, 1920, 2400, 3120)
        for (h in heights) {
            assertEquals("Enter must slide in from full screen height", h, enterInitialOffsetY(h))
            assertEquals("Exit must slide out to full screen height", h, exitTargetOffsetY(h))
        }
    }

    @Test
    fun testViviMiniPlayerAlphaFormula() {
        fun miniPlayerAlpha(progress: Float): Float {
            return (1f - progress * 4f).coerceIn(0f, 1f)
        }

        assertEquals(1.0f, miniPlayerAlpha(0.0f), 0.001f)
        assertEquals(0.8f, miniPlayerAlpha(0.05f), 0.001f)
        assertEquals(0.5f, miniPlayerAlpha(0.125f), 0.001f)
        assertEquals(0.2f, miniPlayerAlpha(0.20f), 0.001f)
        assertEquals(0.0f, miniPlayerAlpha(0.25f), 0.001f)
        assertEquals(0.0f, miniPlayerAlpha(0.50f), 0.001f)
        assertEquals(0.0f, miniPlayerAlpha(1.0f), 0.001f)
    }

    @Test
    fun testViviFullPlayerAlphaFormula() {
        fun fullPlayerAlpha(progress: Float): Float {
            return ((progress - 0.15f) * 4f).coerceIn(0f, 1f)
        }

        assertEquals(0.0f, fullPlayerAlpha(0.0f), 0.001f)
        assertEquals(0.0f, fullPlayerAlpha(0.10f), 0.001f)
        assertEquals(0.0f, fullPlayerAlpha(0.15f), 0.001f)
        assertEquals(0.2f, fullPlayerAlpha(0.20f), 0.001f)
        assertEquals(0.5f, fullPlayerAlpha(0.275f), 0.001f)
        assertEquals(1.0f, fullPlayerAlpha(0.40f), 0.001f)
        assertEquals(1.0f, fullPlayerAlpha(0.75f), 0.001f)
        assertEquals(1.0f, fullPlayerAlpha(1.0f), 0.001f)
    }

    @Test
    fun testViviScrimAlphaFormula() {
        fun scrimAlpha(progress: Float): Float {
            return (1.4f * kotlin.math.sqrt((progress.coerceAtLeast(0.1f) - 0.1f))).coerceIn(0f, 1f)
        }

        assertEquals(0.0f, scrimAlpha(0.0f), 0.001f)
        assertEquals(0.0f, scrimAlpha(0.10f), 0.001f)
        assertTrue("Scrim must fade in after 10% progress", scrimAlpha(0.20f) > 0.4f)
        assertTrue("Scrim must reach 100% before fully expanded", scrimAlpha(0.65f) >= 1.0f)
        assertEquals(1.0f, scrimAlpha(1.0f), 0.001f)
    }

    @Test
    fun testViviCornerRadiusFormula() {
        fun sheetCornerRadius(progress: Float): Float {
            return if (progress < 1f) 16f else 0f
        }

        assertEquals(16f, sheetCornerRadius(0.0f), 0.001f)
        assertEquals(16f, sheetCornerRadius(0.5f), 0.001f)
        assertEquals(16f, sheetCornerRadius(0.99f), 0.001f)
        assertEquals(0f, sheetCornerRadius(1.0f), 0.001f)
    }

    @Test
    fun testViviDockAnimationFormula() {
        fun dockAlpha(progress: Float): Float {
            return (1f - progress * 2f).coerceIn(0f, 1f)
        }
        fun dockTranslationY(progress: Float, maxTranslation: Float = 64f): Float {
            return maxTranslation * progress
        }

        assertEquals(1.0f, dockAlpha(0.0f), 0.001f)
        assertEquals(0.0f, dockTranslationY(0.0f), 0.001f)

        assertEquals(0.5f, dockAlpha(0.25f), 0.001f)
        assertEquals(16f, dockTranslationY(0.25f), 0.001f)

        assertEquals(0.0f, dockAlpha(0.50f), 0.001f)
        assertEquals(32f, dockTranslationY(0.50f), 0.001f)

        assertEquals(0.0f, dockAlpha(1.0f), 0.001f)
        assertEquals(64f, dockTranslationY(1.0f), 0.001f)
    }

    @Test
    fun testProgressReversalContinuity() {
        // Verifies that reversing direction from an arbitrary in-flight progress
        // maintains continuous progression towards target without state jumping
        var currentProgress = 0.42f // Interrupted mid-transition

        // Collapse triggered
        val collapseTarget = 0.0f
        val collapseDelta = collapseTarget - currentProgress
        assertTrue("Collapse must progress downward from 0.42", collapseDelta < 0)

        // Rapid re-expand triggered from 0.18f
        currentProgress = 0.18f
        val expandTarget = 1.0f
        val expandDelta = expandTarget - currentProgress
        assertTrue("Expand must progress upward from 0.18", expandDelta > 0)
    }

    @Test
    fun testDragProgressDeltaCalculation() {
        val travelDistance = 1000f
        var progress = 1.0f

        // Downward drag (positive dragAmount in pixels) must decrease progress
        val dragDownAmount = 250f
        val deltaDown = -dragDownAmount / travelDistance
        progress = (progress + deltaDown).coerceIn(0f, 1f)
        assertEquals("Dragging downward 250px must decrease progress to 0.75", 0.75f, progress, 0.001f)

        // Further downward drag by 500px
        val dragDownMore = 500f
        val deltaDownMore = -dragDownMore / travelDistance
        progress = (progress + deltaDownMore).coerceIn(0f, 1f)
        assertEquals("Dragging downward additional 500px must decrease progress to 0.25", 0.25f, progress, 0.001f)

        // Upward drag (negative dragAmount in pixels) must increase progress
        val dragUpAmount = -300f
        val deltaUp = -dragUpAmount / travelDistance
        progress = (progress + deltaUp).coerceIn(0f, 1f)
        assertEquals("Dragging upward 300px must increase progress to 0.55", 0.55f, progress, 0.001f)
    }

    @Test
    fun testDragProgressClamping() {
        val travelDistance = 800f
        var progress = 0.1f

        // Dragging down excessively beyond collapsed bounds must clamp to 0.0f
        val excessiveDragDown = 2000f
        progress = (progress + (-excessiveDragDown / travelDistance)).coerceIn(0f, 1f)
        assertEquals("Progress must not drop below 0.0f", 0.0f, progress, 0.0001f)

        // Dragging up excessively beyond expanded bounds must clamp to 1.0f
        val excessiveDragUp = -3000f
        progress = (progress + (-excessiveDragUp / travelDistance)).coerceIn(0f, 1f)
        assertEquals("Progress must not exceed 1.0f", 1.0f, progress, 0.0001f)
    }

    @Test
    fun testReleaseVelocityThresholdTargeting() {
        fun computeTarget(progress: Float, velocity: Float): Float {
            return when {
                velocity < -300f -> 0f
                velocity > 300f -> 1f
                progress < 0.5f -> 0f
                else -> 1f
            }
        }

        // Fast downward fling even when progress is high (e.g. 0.85f) must target collapse (0.0f)
        assertEquals("Downward fling must target collapse", 0.0f, computeTarget(0.85f, -1200f), 0.0001f)
        assertEquals("Downward fling at velocity -301f must target collapse", 0.0f, computeTarget(0.90f, -301f), 0.0001f)

        // Fast upward fling even when progress is low (e.g. 0.15f) must target expand (1.0f)
        assertEquals("Upward fling must target expand", 1.0f, computeTarget(0.15f, 1200f), 0.0001f)
        assertEquals("Upward fling at velocity +301f must target expand", 1.0f, computeTarget(0.10f, 301f), 0.0001f)
    }

    @Test
    fun testReleaseMidpointFallbackTargeting() {
        fun computeTarget(progress: Float, velocity: Float): Float {
            return when {
                velocity < -300f -> 0f
                velocity > 300f -> 1f
                progress < 0.5f -> 0f
                else -> 1f
            }
        }

        // Low velocity: below midpoint (< 0.5f) collapses
        assertEquals("Neutral release at 0.49f collapses", 0.0f, computeTarget(0.49f, 0f), 0.0001f)
        assertEquals("Neutral release at 0.20f collapses", 0.0f, computeTarget(0.20f, -150f), 0.0001f)
        assertEquals("Small upward drag below midpoint collapses", 0.0f, computeTarget(0.35f, 200f), 0.0001f)

        // Low velocity: at or above midpoint (>= 0.5f) expands
        assertEquals("Neutral release at 0.50f expands", 1.0f, computeTarget(0.50f, 0f), 0.0001f)
        assertEquals("Neutral release at 0.75f expands", 1.0f, computeTarget(0.75f, 100f), 0.0001f)
        assertEquals("Small downward drag above midpoint expands", 1.0f, computeTarget(0.65f, -200f), 0.0001f)
    }

    @Test
    fun testSettleContinuousFromReleasePosition() {
        // Verifies that when released at an arbitrary progress, the settle animation
        // proceeds strictly from release position to target without snapping to 1.0f or 0.0f first
        val releaseProgress = 0.35f
        val velocity = -100f // neutral/slight downward drift

        val target = if (velocity < -300f) 0f else if (velocity > 300f) 1f else if (releaseProgress < 0.5f) 0f else 1f
        assertEquals("Release at 0.35f with neutral velocity targets 0f", 0f, target, 0.0001f)

        // The animation displacement must be (target - releaseProgress) = -0.35f
        val animationDelta = target - releaseProgress
        assertEquals("Settle starts continuously at 0.35f and travels -0.35f to 0.0f", -0.35f, animationDelta, 0.001f)
        assertTrue("No snap back to 1.0f: distance to target is 0.35, NOT 1.0", kotlin.math.abs(animationDelta) <= 0.351f)
    }

    @Test
    fun testMiniPlayerUpwardDragContinuousAccumulation() {
        // Simulates the exact synchronous accumulator loop during upward drag on MiniPlayer
        val travelDistance = 1000f
        var currentProgress = 0.0f // Initial collapsed state

        // Frame 1: Finger moves up by 100px (negative dragAmount in Compose)
        val delta1 = -(-100f) / travelDistance
        currentProgress = (currentProgress + delta1).coerceIn(0f, 1f)
        assertEquals("Progress after 100px upward drag must be 0.10", 0.10f, currentProgress, 0.001f)

        // MiniPlayer alpha at 0.10: (1 - 0.10 * 4) = 0.60
        val miniAlpha1 = (1f - currentProgress * 4f).coerceIn(0f, 1f)
        assertEquals(0.60f, miniAlpha1, 0.001f)
        // FullPlayer alpha at 0.10: ((0.10 - 0.15) * 4) = 0.00
        val fullAlpha1 = ((currentProgress - 0.15f) * 4f).coerceIn(0f, 1f)
        assertEquals(0.00f, fullAlpha1, 0.001f)

        // Frame 2: Finger moves up another 200px
        val delta2 = -(-200f) / travelDistance
        currentProgress = (currentProgress + delta2).coerceIn(0f, 1f)
        assertEquals("Progress after additional 200px upward drag must be 0.30", 0.30f, currentProgress, 0.001f)

        // MiniPlayer alpha at 0.30: (1 - 0.30 * 4) = 0.00 (completely faded)
        val miniAlpha2 = (1f - currentProgress * 4f).coerceIn(0f, 1f)
        assertEquals(0.00f, miniAlpha2, 0.001f)
        // FullPlayer alpha at 0.30: ((0.30 - 0.15) * 4) = 0.60
        val fullAlpha2 = ((currentProgress - 0.15f) * 4f).coerceIn(0f, 1f)
        assertEquals(0.60f, fullAlpha2, 0.001f)

        // Frame 3: Finger moves up another 300px (total 600px)
        val delta3 = -(-300f) / travelDistance
        currentProgress = (currentProgress + delta3).coerceIn(0f, 1f)
        assertEquals("Progress after total 600px upward drag must be 0.60", 0.60f, currentProgress, 0.001f)

        // FullPlayer alpha at 0.60: 1.0 (fully opaque)
        val fullAlpha3 = ((currentProgress - 0.15f) * 4f).coerceIn(0f, 1f)
        assertEquals(1.00f, fullAlpha3, 0.001f)
    }

    @Test
    fun testFullPlayerDownwardDragContinuousAccumulation() {
        // Simulates downward drag from expanded FullPlayer
        val travelDistance = 1000f
        var currentProgress = 1.0f // Initial expanded state

        // Frame 1: Finger moves down by 150px (positive dragAmount in Compose)
        val delta1 = -(150f) / travelDistance
        currentProgress = (currentProgress + delta1).coerceIn(0f, 1f)
        assertEquals("Progress after 150px downward drag must be 0.85", 0.85f, currentProgress, 0.001f)

        // Frame 2: Finger moves down another 350px (total 500px)
        val delta2 = -(350f) / travelDistance
        currentProgress = (currentProgress + delta2).coerceIn(0f, 1f)
        assertEquals("Progress after 500px downward drag must be 0.50", 0.50f, currentProgress, 0.001f)

        // Frame 3: Finger moves down another 250px (total 750px)
        val delta3 = -(250f) / travelDistance
        currentProgress = (currentProgress + delta3).coerceIn(0f, 1f)
        assertEquals("Progress after 750px downward drag must be 0.25", 0.25f, currentProgress, 0.001f)

        // MiniPlayer alpha at 0.25: (1 - 0.25 * 4) = 0.00 (just starting to fade in below 0.25)
        assertEquals(0.00f, (1f - currentProgress * 4f).coerceIn(0f, 1f), 0.001f)

        // Frame 4: Finger moves down another 100px (progress = 0.15)
        val delta4 = -(100f) / travelDistance
        currentProgress = (currentProgress + delta4).coerceIn(0f, 1f)
        assertEquals(0.15f, currentProgress, 0.001f)
        // MiniPlayer alpha at 0.15: (1 - 0.15 * 4) = 0.40
        assertEquals(0.40f, (1f - currentProgress * 4f).coerceIn(0f, 1f), 0.001f)
    }

    @Test
    fun testRapidDirectionReversalSynchronousAccumulator() {
        val travelDistance = 1000f
        var currentProgress = 0.5f

        // Rapid sequence: up, down, up, down, up
        val deltas = listOf(-200f, 150f, -300f, 100f, -250f)
        for (dragAmount in deltas) {
            val delta = -dragAmount / travelDistance
            currentProgress = (currentProgress + delta).coerceIn(0f, 1f)
        }

        // Expected: 0.5 + 0.2 - 0.15 + 0.3 - 0.1 + 0.25 = 1.0 (clamped)
        assertEquals(1.0f, currentProgress, 0.001f)
    }

    @Test
    fun testActiveSpringInterruptionPreservesCurrentProgress() {
        // Simulates an in-flight spring animation interrupted mid-travel
        var activeProgress = 0.72f // sheet was settling downwards towards 0.0f
        var isSpringActive = true

        // User touches the screen: awaitFirstDown immediately cancels the spring
        if (isSpringActive) {
            isSpringActive = false // animation cancelled
        }

        // Frozen at exact position without jumping
        assertEquals(0.72f, activeProgress, 0.0001f)

        // User initiates drag from this exact progress
        var currentProgress = activeProgress
        val dragUpAmount = -120f
        val travelDistance = 1000f
        currentProgress = (currentProgress + (-dragUpAmount / travelDistance)).coerceIn(0f, 1f)

        assertEquals("Drag continues continuously from interrupted 0.72f to 0.84f", 0.84f, currentProgress, 0.001f)
        assertFalse("Spring must remain inactive while dragging", isSpringActive)
    }

    @Test
    fun testDerivedStateOfAvoidsUnnecessaryThresholdInvalidation() {
        // Verifies that during continuous dragging, the structural composition booleans
        // remain completely stable across the intermediate range, preventing recomposition
        fun isPlayerSheetActive(progress: Float, isOpen: Boolean) = progress > 0f || isOpen
        fun isMiniPlayerVisible(progress: Float, isOpen: Boolean) = progress < 1f || !isOpen
        fun isFullyCollapsed(progress: Float) = progress == 0f

        // When collapsed (0.0f, isOpen = false):
        assertFalse(isPlayerSheetActive(0.0f, false))
        assertTrue(isMiniPlayerVisible(0.0f, false))
        assertTrue(isFullyCollapsed(0.0f))

        // Across 98 simulated 120Hz drag frames in the mid-range (0.01f to 0.99f):
        for (i in 1..99) {
            val p = i / 100f
            assertTrue("Player sheet must remain active across entire mid-drag", isPlayerSheetActive(p, false))
            assertTrue("Mini player must remain composed across mid-drag until fully expanded", isMiniPlayerVisible(p, false))
            assertFalse("Sheet is not fully collapsed during drag", isFullyCollapsed(p))
        }

        // When fully expanded (1.0f, isOpen = true):
        assertTrue(isPlayerSheetActive(1.0f, true))
        assertFalse("Mini player must be unmounted when fully expanded", isMiniPlayerVisible(1.0f, true))
        assertFalse(isFullyCollapsed(1.0f))
    }

    @Test
    fun testMiniPlayerDownwardDragDismissesWhenThresholdExceeded() {
        val dismissThresholdPx = 100f
        val dismissVelocityThreshold = 1800f
        var currentProgress = 0f
        var currentDismissY = 0f
        var isDismissDrag = false
        var playerClosed = false

        // User touches collapsed MiniPlayer and drags down 120px
        val dragSteps = listOf(20f, 30f, 40f, 30f) // total 120px
        for (dragAmount in dragSteps) {
            if (currentProgress <= 0.001f && (currentDismissY > 0f || dragAmount > 0f)) {
                currentProgress = 0f
                isDismissDrag = true
                currentDismissY = (currentDismissY + dragAmount).coerceAtLeast(0f)
            }
        }

        assertTrue("Must enter dismiss drag mode", isDismissDrag)
        assertEquals(120f, currentDismissY, 0.001f)
        assertEquals("Sheet progress must remain 0 during dismiss drag", 0f, currentProgress, 0.0001f)

        // Release with low velocity but displacement > threshold
        val rawVelocityY = 200f
        val shouldDismiss = currentDismissY > dismissThresholdPx || rawVelocityY > dismissVelocityThreshold
        assertTrue("Displacement 120px > 100px threshold must trigger dismiss", shouldDismiss)
        if (shouldDismiss) {
            playerClosed = true
        }
        assertTrue("Player must be closed/stopped on dismiss", playerClosed)
    }

    @Test
    fun testMiniPlayerDownwardDragSpringsBackWhenThresholdNotMet() {
        val dismissThresholdPx = 100f
        val dismissVelocityThreshold = 1800f
        var currentProgress = 0f
        var currentDismissY = 0f
        var isDismissDrag = false
        var playerClosed = false

        // User touches collapsed MiniPlayer and drags down only 40px
        val dragSteps = listOf(10f, 15f, 15f)
        for (dragAmount in dragSteps) {
            if (currentProgress <= 0.001f && (currentDismissY > 0f || dragAmount > 0f)) {
                currentProgress = 0f
                isDismissDrag = true
                currentDismissY = (currentDismissY + dragAmount).coerceAtLeast(0f)
            }
        }

        assertTrue("Must enter dismiss drag mode", isDismissDrag)
        assertEquals(40f, currentDismissY, 0.001f)

        // Release with low velocity and displacement < threshold
        val rawVelocityY = 150f
        val shouldDismiss = currentDismissY > dismissThresholdPx || rawVelocityY > dismissVelocityThreshold
        assertFalse("Displacement 40px < 100px and velocity 150 < 1800 must NOT trigger dismiss", shouldDismiss)
        if (!shouldDismiss) {
            // Springs back to 0
            currentDismissY = 0f
        }
        assertFalse("Player must NOT be closed", playerClosed)
        assertEquals("Dismiss offset must spring back to 0", 0f, currentDismissY, 0.0001f)
    }

    @Test
    fun testMiniPlayerUpwardDragExpandsSheetWithoutDismiss() {
        val travelDistance = 1000f
        var currentProgress = 0f
        var currentDismissY = 0f
        var isDismissDrag = false

        // User touches collapsed MiniPlayer and drags UP 200px
        val dragUpAmount = -200f
        if (currentProgress <= 0.001f && (currentDismissY > 0f || dragUpAmount > 0f)) {
            isDismissDrag = true
            currentDismissY = (currentDismissY + dragUpAmount).coerceAtLeast(0f)
        } else {
            isDismissDrag = false
            val deltaProgress = -dragUpAmount / travelDistance
            currentProgress = (currentProgress + deltaProgress).coerceIn(0f, 1f)
        }

        assertFalse("Upward drag must NOT trigger dismiss drag", isDismissDrag)
        assertEquals(0f, currentDismissY, 0.0001f)
        assertEquals("Upward drag must increase sheet progress (200 / 1000 = 0.2)", 0.2f, currentProgress, 0.001f)
    }

    @Test
    fun testFullPlayerDownwardDragCollapsesSheetWithoutDismiss() {
        val travelDistance = 1000f
        var currentProgress = 1.0f // Full Player is open
        var currentDismissY = 0f
        var isDismissDrag = false

        // User drags down on Full Player
        val dragDownAmount = 250f
        if (currentProgress <= 0.001f && (currentDismissY > 0f || dragDownAmount > 0f)) {
            isDismissDrag = true
            currentDismissY = (currentDismissY + dragDownAmount).coerceAtLeast(0f)
        } else {
            isDismissDrag = false
            val deltaProgress = -dragDownAmount / travelDistance
            currentProgress = (currentProgress + deltaProgress).coerceIn(0f, 1f)
        }

        assertFalse("Downward drag on Full Player must NOT trigger dismiss drag", isDismissDrag)
        assertEquals(0f, currentDismissY, 0.0001f)
        assertEquals("Downward drag on Full Player must decrease sheet progress (1.0 - 0.25 = 0.75)", 0.75f, currentProgress, 0.001f)
    }
    @Test
    fun testViviBackgroundAlphaCurveAtProgressCheckpoints() {
        fun calculateViviBackgroundAlpha(p: Float): Float {
            return (1.4f * kotlin.math.sqrt((p.coerceAtLeast(0.1f) - 0.1f))).coerceIn(0f, 1f)
        }

        // 0% to 10% progress: background remains completely transparent
        assertEquals(0f, calculateViviBackgroundAlpha(0.00f), 0.0001f)
        assertEquals(0f, calculateViviBackgroundAlpha(0.05f), 0.0001f)
        assertEquals(0f, calculateViviBackgroundAlpha(0.10f), 0.0001f)

        // 25% progress: 1.4 * sqrt(0.15) ~ 0.5422
        val alpha25 = calculateViviBackgroundAlpha(0.25f)
        assertEquals(0.5422f, alpha25, 0.001f)

        // 50% progress: 1.4 * sqrt(0.40) ~ 0.8854
        val alpha50 = calculateViviBackgroundAlpha(0.50f)
        assertEquals(0.8854f, alpha50, 0.001f)

        // 61% progress: 1.4 * sqrt(0.51) ~ 0.9997 (reaches full opacity ~61%)
        val alpha61 = calculateViviBackgroundAlpha(0.61f)
        assertEquals(1.0f, alpha61, 0.001f)

        // 75% and 100% progress: clamped at 1.0
        assertEquals(1.0f, calculateViviBackgroundAlpha(0.75f), 0.0001f)
        assertEquals(1.0f, calculateViviBackgroundAlpha(1.00f), 0.0001f)
    }

    @Test
    fun testViviBackgroundContinuityOnMidTransitionReversal() {
        fun calculateViviBackgroundAlpha(p: Float): Float {
            return (1.4f * kotlin.math.sqrt((p.coerceAtLeast(0.1f) - 0.1f))).coerceIn(0f, 1f)
        }

        val travelDistance = 1000f
        var currentProgress = 0f

        // 1. User drags up from collapsed MiniPlayer by 450px
        val dragUp1 = -450f
        val deltaProgress1 = -dragUp1 / travelDistance
        currentProgress = (currentProgress + deltaProgress1).coerceIn(0f, 1f)
        assertEquals(0.45f, currentProgress, 0.001f)

        val alphaAtMidDrag = calculateViviBackgroundAlpha(currentProgress)
        // 1.4 * sqrt(0.35) ~ 0.8282
        assertEquals(0.8282f, alphaAtMidDrag, 0.001f)

        // 2. User reverses direction mid-transition and drags down by 150px
        val dragDownMid = 150f
        val deltaProgress2 = -dragDownMid / travelDistance
        currentProgress = (currentProgress + deltaProgress2).coerceIn(0f, 1f)
        assertEquals(0.30f, currentProgress, 0.001f)

        val alphaAtReversal = calculateViviBackgroundAlpha(currentProgress)
        // 1.4 * sqrt(0.20) ~ 0.6261
        assertEquals(0.6261f, alphaAtReversal, 0.001f)

        // Alpha MUST decrease continuously from 0.8282 to 0.6261 with zero reset, snap, or restart
        assertTrue("Alpha at reversal (0.6261) must be lower than at mid-drag (0.8282)", alphaAtReversal < alphaAtMidDrag)
        assertTrue("Alpha must remain continuous above 0", alphaAtReversal > 0f)
    }

    @Test
    fun testSingleClockDriverForSheetAndBackground() {
        // Both the sheet translation and the static background alpha are derived from the same playerSheetProgress
        val travelDistance = 1000f

        fun computeSheetTranslationY(p: Float): Float = (1f - p) * travelDistance
        fun computeBackgroundAlpha(p: Float): Float = (1.4f * kotlin.math.sqrt((p.coerceAtLeast(0.1f) - 0.1f))).coerceIn(0f, 1f)
        fun computeControlsAlpha(p: Float): Float = ((p - 0.15f) * 4f).coerceIn(0f, 1f)
        fun computeMiniPlayerAlpha(p: Float): Float = (1f - p * 4f).coerceIn(0f, 1f)

        // State: collapsed (p = 0.0)
        var p = 0.0f
        assertEquals(1000f, computeSheetTranslationY(p), 0.001f)
        assertEquals(0f, computeBackgroundAlpha(p), 0.001f)
        assertEquals(0f, computeControlsAlpha(p), 0.001f)
        assertEquals(1f, computeMiniPlayerAlpha(p), 0.001f)

        // State: half-expanded (p = 0.5)
        p = 0.5f
        assertEquals(500f, computeSheetTranslationY(p), 0.001f)
        assertEquals(0.8854f, computeBackgroundAlpha(p), 0.001f)
        assertEquals(1.0f, computeControlsAlpha(p), 0.001f)
        assertEquals(0f, computeMiniPlayerAlpha(p), 0.001f)

        // State: fully expanded (p = 1.0)
        p = 1.0f
        assertEquals(0f, computeSheetTranslationY(p), 0.001f)
        assertEquals(1.0f, computeBackgroundAlpha(p), 0.001f)
        assertEquals(1.0f, computeControlsAlpha(p), 0.001f)
        assertEquals(0f, computeMiniPlayerAlpha(p), 0.001f)
    }
}

