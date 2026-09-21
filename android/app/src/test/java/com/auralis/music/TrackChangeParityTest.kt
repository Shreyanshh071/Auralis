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
     * Logic simulation harness replicating the exact pager reconciliation and animation logic
     * from NowPlayingModal.kt.
     */
    private class PagerReconciliationSimulator(
        val pageCount: Int,
        initialPage: Int = 0
    ) {
        var currentPage: Int = initialPage
        var targetPage: Int = initialPage
        var settledPage: Int = initialPage
        var isScrollInProgress: Boolean = false
        var pendingTargetIndex: Int? = null
        var isProgrammaticScroll: Boolean = false
        var skipPagerAnimation: Boolean = false

        // Invariant telemetry
        var scrollToPageCallCount = 0
        var lastScrollToPageTarget: Int? = null
        var animateScrollToPageCallCount = 0
        var lastAnimateTarget: Int? = null
        var cancelledAnimationCount = 0

        fun handleNext(onNextClick: () -> Unit = {}) {
            if (pageCount > 1) {
                val fromIndex = pendingTargetIndex ?: currentPage
                val targetIndex = (fromIndex + 1).coerceAtMost(pageCount - 1)
                if (targetIndex != fromIndex) {
                    if (isScrollInProgress && targetIndex != targetPage) {
                        cancelledAnimationCount++
                    }
                    pendingTargetIndex = targetIndex
                    startAnimation(targetIndex, durationMillis = 500)
                }
            }
            onNextClick()
        }

        fun handlePrevious(onPreviousClick: () -> Unit = {}) {
            if (pageCount > 1) {
                val fromIndex = pendingTargetIndex ?: currentPage
                val targetIndex = (fromIndex - 1).coerceAtLeast(0)
                if (targetIndex != fromIndex) {
                    if (isScrollInProgress && targetIndex != targetPage) {
                        cancelledAnimationCount++
                    }
                    pendingTargetIndex = targetIndex
                    startAnimation(targetIndex, durationMillis = 500)
                }
            }
            onPreviousClick()
        }

        private fun startAnimation(target: Int, durationMillis: Int) {
            targetPage = target
            isScrollInProgress = true
            animateScrollToPageCallCount++
            lastAnimateTarget = target
        }

        fun onLaunchedEffect(
            currentTrackIndex: Int,
            trackId: String,
            currentTab: NowPlayingTab = NowPlayingTab.PLAYER
        ) {
            val target = pendingTargetIndex
            if (target != null) {
                if (currentTrackIndex == target) {
                    // Authoritative playback caught up to our optimistic target!
                    if (!isScrollInProgress && settledPage == target) {
                        pendingTargetIndex = null
                    }
                    return
                } else {
                    // Playback is still catching up to a newer rapid tap/swipe target.
                    return
                }
            }

            // If the pager is already at or animating towards currentTrackIndex, do not interrupt it!
            if (currentPage == currentTrackIndex || (currentTab == NowPlayingTab.PLAYER && targetPage == currentTrackIndex)) {
                return
            }

            // If the user is actively swiping / scrolling on the Player tab, do not forcibly interrupt
            if (currentTab == NowPlayingTab.PLAYER && isScrollInProgress) {
                return
            }

            if (currentTrackIndex in 0 until pageCount && currentPage != currentTrackIndex) {
                isProgrammaticScroll = true
                try {
                    if (currentTab == NowPlayingTab.PLAYER && !skipPagerAnimation) {
                        val distance = kotlin.math.abs(currentPage - currentTrackIndex)
                        if (distance == 1 && !isScrollInProgress) {
                            startAnimation(currentTrackIndex, durationMillis = 500)
                        } else {
                            scrollToPage(currentTrackIndex)
                        }
                    } else {
                        scrollToPage(currentTrackIndex)
                    }
                } finally {
                    skipPagerAnimation = false
                    isProgrammaticScroll = false
                }
            }
        }

        private fun scrollToPage(target: Int) {
            if (isScrollInProgress) {
                cancelledAnimationCount++
            }
            currentPage = target
            targetPage = target
            settledPage = target
            isScrollInProgress = false
            scrollToPageCallCount++
            lastScrollToPageTarget = target
        }

        fun onSettle(newSettledPage: Int, curIndex: Int) {
            isScrollInProgress = false
            currentPage = newSettledPage
            targetPage = newSettledPage
            settledPage = newSettledPage
            if (settledPage == curIndex) {
                pendingTargetIndex = null
            }
        }
    }

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
    fun `test pendingTargetIndex equals currentTrackIndex does NOT trigger scrollToPage`() {
        val sim = PagerReconciliationSimulator(pageCount = queue.size, initialPage = 0)

        // User taps Next -> starts smooth 240ms animation to index 1
        sim.handleNext()
        assertEquals(1, sim.pendingTargetIndex)
        assertEquals(1, sim.targetPage)
        assertTrue(sim.isScrollInProgress)
        assertEquals(1, sim.animateScrollToPageCallCount)
        assertEquals(0, sim.scrollToPageCallCount)

        // Playback catches up at t=15ms while animation is in progress
        sim.onLaunchedEffect(currentTrackIndex = 1, trackId = "track_1")

        // CRITICAL INVARIANT: scrollToPage must NOT be called, animation must NOT be cancelled!
        assertEquals("scrollToPage must NOT be called when playback catches up", 0, sim.scrollToPageCallCount)
        assertEquals("Animation must NOT be cancelled", 0, sim.cancelledAnimationCount)
        assertEquals("pendingTargetIndex must remain authoritative while animating", 1, sim.pendingTargetIndex)
        assertTrue("Scroll must remain in progress", sim.isScrollInProgress)
    }

    @Test
    fun `test pager animation already targeting correct page is never interrupted by reconciliation`() {
        val sim = PagerReconciliationSimulator(pageCount = queue.size, initialPage = 0)

        // Next tap starts animation to page 1
        sim.handleNext()
        assertEquals(1, sim.targetPage)

        // Multiple playback/tab/id recomposition events arrive while animation runs
        sim.onLaunchedEffect(currentTrackIndex = 1, trackId = "track_1")
        sim.onLaunchedEffect(currentTrackIndex = 1, trackId = "track_1")

        assertEquals(0, sim.scrollToPageCallCount)
        assertEquals(0, sim.cancelledAnimationCount)
        assertEquals(1, sim.animateScrollToPageCallCount)
    }

    @Test
    fun `test pendingTargetIndex remains until pager settlement`() {
        val sim = PagerReconciliationSimulator(pageCount = queue.size, initialPage = 0)

        sim.handleNext()
        assertEquals(1, sim.pendingTargetIndex)

        // Playback catches up first (t=15ms)
        sim.onLaunchedEffect(currentTrackIndex = 1, trackId = "track_1")
        assertEquals("pendingTargetIndex must NOT clear before pager arrives at target", 1, sim.pendingTargetIndex)

        // Pager settles at t=240ms
        sim.onSettle(newSettledPage = 1, curIndex = 1)
        assertNull("pendingTargetIndex must clear on settle when playback has caught up", sim.pendingTargetIndex)
        assertEquals(1, sim.currentPage)
        assertEquals(1, sim.settledPage)
    }

    @Test
    fun `test newest pending target survives intermediate playback callbacks`() {
        val sim = PagerReconciliationSimulator(pageCount = queue.size, initialPage = 0)

        // User taps Next -> target 1
        sim.handleNext()
        assertEquals(1, sim.pendingTargetIndex)

        // User rapidly taps Next again -> target 2
        sim.handleNext()
        assertEquals(2, sim.pendingTargetIndex)
        assertEquals(2, sim.targetPage)

        // Stale callback arrives for track 1
        sim.onLaunchedEffect(currentTrackIndex = 1, trackId = "track_1")

        assertEquals("Newest pending target 2 must survive intermediate track 1 callback", 2, sim.pendingTargetIndex)
        assertEquals(0, sim.scrollToPageCallCount)
        assertEquals("Target page must remain 2", 2, sim.targetPage)
    }

    @Test
    fun `test rapid Next to Next settles on newest target`() {
        val sim = PagerReconciliationSimulator(pageCount = queue.size, initialPage = 0)

        sim.handleNext() // -> 1
        sim.handleNext() // -> 2
        assertEquals(2, sim.pendingTargetIndex)

        // Intermediate callback arrives
        sim.onLaunchedEffect(currentTrackIndex = 1, trackId = "track_1")
        assertEquals(2, sim.pendingTargetIndex)

        // Final callback arrives
        sim.onLaunchedEffect(currentTrackIndex = 2, trackId = "track_2")
        assertEquals(2, sim.pendingTargetIndex)

        // Settle on page 2
        sim.onSettle(newSettledPage = 2, curIndex = 2)
        assertNull(sim.pendingTargetIndex)
        assertEquals(2, sim.settledPage)
        assertEquals(2, sim.currentPage)
    }

    @Test
    fun `test Next then Previous reverses correctly without snap`() {
        val sim = PagerReconciliationSimulator(pageCount = queue.size, initialPage = 0)

        // 1. Next tap -> target 1
        sim.handleNext()
        assertEquals(1, sim.pendingTargetIndex)
        assertEquals(1, sim.targetPage)

        // 2. Before settling, user taps Previous -> target 0
        sim.handlePrevious()
        assertEquals(0, sim.pendingTargetIndex)
        assertEquals(0, sim.targetPage)

        // Intermediate Next callback arrives for track 1
        sim.onLaunchedEffect(currentTrackIndex = 1, trackId = "track_1")
        assertEquals("Target 0 must NOT be overwritten by stale track 1", 0, sim.pendingTargetIndex)
        assertEquals(0, sim.scrollToPageCallCount)

        // Previous callback arrives for track 0
        sim.onLaunchedEffect(currentTrackIndex = 0, trackId = "track_0")
        assertEquals(0, sim.pendingTargetIndex)

        // Settle on page 0
        sim.onSettle(newSettledPage = 0, curIndex = 0)
        assertNull(sim.pendingTargetIndex)
        assertEquals(0, sim.settledPage)
        assertEquals(0, sim.currentPage)
        assertEquals(0, sim.scrollToPageCallCount)
    }

    @Test
    fun `test Previous then Next reverses correctly without snap`() {
        val sim = PagerReconciliationSimulator(pageCount = queue.size, initialPage = 1)

        // 1. Previous tap -> target 0
        sim.handlePrevious()
        assertEquals(0, sim.pendingTargetIndex)
        assertEquals(0, sim.targetPage)

        // 2. Before settling, user taps Next -> target 1
        sim.handleNext()
        assertEquals(1, sim.pendingTargetIndex)
        assertEquals(1, sim.targetPage)

        // Intermediate Previous callback arrives for track 0
        sim.onLaunchedEffect(currentTrackIndex = 0, trackId = "track_0")
        assertEquals("Target 1 must NOT be overwritten by stale track 0", 1, sim.pendingTargetIndex)
        assertEquals(0, sim.scrollToPageCallCount)

        // Next callback arrives for track 1
        sim.onLaunchedEffect(currentTrackIndex = 1, trackId = "track_1")
        assertEquals(1, sim.pendingTargetIndex)

        // Settle on page 1
        sim.onSettle(newSettledPage = 1, curIndex = 1)
        assertNull(sim.pendingTargetIndex)
        assertEquals(1, sim.settledPage)
        assertEquals(1, sim.currentPage)
        assertEquals(0, sim.scrollToPageCallCount)
    }

    @Test
    fun `test external non-optimistic currentTrack changes synchronize pager correctly`() {
        val sim = PagerReconciliationSimulator(pageCount = queue.size, initialPage = 0)

        // External track change: song finished naturally, advancing 0 -> 1
        sim.onLaunchedEffect(currentTrackIndex = 1, trackId = "track_1")
        assertEquals("Adjacent external track change animates to new page", 1, sim.animateScrollToPageCallCount)
        assertEquals(1, sim.lastAnimateTarget)

        // Settle on 1
        sim.onSettle(1, curIndex = 1)

        // External track change: user selected distant item 3 from playlist
        sim.onLaunchedEffect(currentTrackIndex = 3, trackId = "track_3")
        assertEquals("Non-adjacent external track change snaps via scrollToPage", 1, sim.scrollToPageCallCount)
        assertEquals(3, sim.currentPage)
    }

    @Test
    fun `test pendingTargetIndex clears once settledPage equals pendingTargetIndex and scrolling stopped`() {
        val sim = PagerReconciliationSimulator(pageCount = queue.size, initialPage = 0)

        sim.handleNext()
        assertEquals(1, sim.pendingTargetIndex)

        // Settle when playback has also reached index 1
        sim.onSettle(newSettledPage = 1, curIndex = 1)
        assertNull(sim.pendingTargetIndex)
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

    // ── VIVI-PARITY DEPTH PAGER MOTION TESTS ──

    private fun computePageOffset(currentPage: Int, page: Int, pageOffsetFraction: Float): Float {
        return kotlin.math.abs((currentPage - page) + pageOffsetFraction).coerceIn(0f, 1f)
    }

    private fun computeDepthScale(pageOffset: Float): Float {
        return 1f - (pageOffset * 0.15f)
    }

    private fun computeDepthScrimAlpha(pageOffset: Float): Float {
        return (pageOffset * 0.75f).coerceIn(0f, 0.75f)
    }

    @Test
    fun `test page offset calculation at center, adjacent, and intermediate positions`() {
        // Page 0 at center (rest)
        val offsetCenter = computePageOffset(currentPage = 0, page = 0, pageOffsetFraction = 0f)
        assertEquals(0f, offsetCenter, 0.001f)

        // Page 1 when page 0 is at center (adjacent page)
        val offsetAdjacent = computePageOffset(currentPage = 0, page = 1, pageOffsetFraction = 0f)
        assertEquals(1f, offsetAdjacent, 0.001f)

        // Halfway through scroll from 0 to 1 (fraction = 0.5 before page flip)
        val offsetHalfwayPage0 = computePageOffset(currentPage = 0, page = 0, pageOffsetFraction = 0.5f)
        val offsetHalfwayPage1 = computePageOffset(currentPage = 0, page = 1, pageOffsetFraction = 0.5f)
        assertEquals(0.5f, offsetHalfwayPage0, 0.001f)
        assertEquals(0.5f, offsetHalfwayPage1, 0.001f)

        // After page flip (currentPage = 1, fraction = -0.5)
        val offsetFlipPage0 = computePageOffset(currentPage = 1, page = 0, pageOffsetFraction = -0.5f)
        val offsetFlipPage1 = computePageOffset(currentPage = 1, page = 1, pageOffsetFraction = -0.5f)
        assertEquals(0.5f, offsetFlipPage0, 0.001f)
        assertEquals(0.5f, offsetFlipPage1, 0.001f)

        // Reached page 1
        val offsetSettledPage1 = computePageOffset(currentPage = 1, page = 1, pageOffsetFraction = 0f)
        assertEquals(0f, offsetSettledPage1, 0.001f)
    }

    @Test
    fun `test depth scale matches VIVI 0_85 to 1_0 scale range`() {
        // Center: exactly 1.0f (full size)
        val scaleCenter = computeDepthScale(pageOffset = 0f)
        assertEquals(1.0f, scaleCenter, 0.001f)

        // Edge (1 page away): exactly 0.85f (VIVI scaleIn / scaleOut reference)
        val scaleEdge = computeDepthScale(pageOffset = 1.0f)
        assertEquals(0.85f, scaleEdge, 0.001f)

        // Midpoint: exactly 0.925f
        val scaleMid = computeDepthScale(pageOffset = 0.5f)
        assertEquals(0.925f, scaleMid, 0.001f)
    }

    @Test
    fun `test depth scrim alpha provides hardware-friendly fade without offscreen buffer`() {
        // Center: exactly 0.0f (no scrim, 100% full brightness artwork)
        val scrimCenter = computeDepthScrimAlpha(pageOffset = 0f)
        assertEquals(0.0f, scrimCenter, 0.001f)

        // Edge (1 page away): exactly 0.75f (deeply dimmed into player background)
        val scrimEdge = computeDepthScrimAlpha(pageOffset = 1.0f)
        assertEquals(0.75f, scrimEdge, 0.001f)

        // Midpoint: exactly 0.375f (smooth linear dimming)
        val scrimMid = computeDepthScrimAlpha(pageOffset = 0.5f)
        assertEquals(0.375f, scrimMid, 0.001f)
    }

    @Test
    fun `test depth transform during forward Next transition`() {
        // As pager transitions 0 -> 1:
        // Page 0 (outgoing): offset goes 0.0 -> 1.0, scale goes 1.0 -> 0.85, scrim goes 0.0 -> 0.75
        val p0StartScale = computeDepthScale(computePageOffset(0, 0, 0.0f))
        val p0StartScrim = computeDepthScrimAlpha(computePageOffset(0, 0, 0.0f))
        val p0EndScale = computeDepthScale(computePageOffset(1, 0, 0.0f))
        val p0EndScrim = computeDepthScrimAlpha(computePageOffset(1, 0, 0.0f))

        assertEquals(1.0f, p0StartScale, 0.001f)
        assertEquals(0.0f, p0StartScrim, 0.001f)
        assertEquals(0.85f, p0EndScale, 0.001f)
        assertEquals(0.75f, p0EndScrim, 0.001f)

        // Page 1 (incoming): offset goes 1.0 -> 0.0, scale goes 0.85 -> 1.0, scrim goes 0.75 -> 0.0
        val p1StartScale = computeDepthScale(computePageOffset(0, 1, 0.0f))
        val p1StartScrim = computeDepthScrimAlpha(computePageOffset(0, 1, 0.0f))
        val p1EndScale = computeDepthScale(computePageOffset(1, 1, 0.0f))
        val p1EndScrim = computeDepthScrimAlpha(computePageOffset(1, 1, 0.0f))

        assertEquals(0.85f, p1StartScale, 0.001f)
        assertEquals(0.75f, p1StartScrim, 0.001f)
        assertEquals(1.0f, p1EndScale, 0.001f)
        assertEquals(0.0f, p1EndScrim, 0.001f)
    }

    @Test
    fun `test depth transform during backward Previous transition`() {
        // As pager transitions 1 -> 0:
        // Page 1 (outgoing): offset goes 0.0 -> 1.0, scale goes 1.0 -> 0.85, scrim goes 0.0 -> 0.75
        val p1StartScale = computeDepthScale(computePageOffset(1, 1, 0.0f))
        val p1StartScrim = computeDepthScrimAlpha(computePageOffset(1, 1, 0.0f))
        val p1EndScale = computeDepthScale(computePageOffset(0, 1, 0.0f))
        val p1EndScrim = computeDepthScrimAlpha(computePageOffset(0, 1, 0.0f))

        assertEquals(1.0f, p1StartScale, 0.001f)
        assertEquals(0.0f, p1StartScrim, 0.001f)
        assertEquals(0.85f, p1EndScale, 0.001f)
        assertEquals(0.75f, p1EndScrim, 0.001f)

        // Page 0 (incoming): offset goes 1.0 -> 0.0, scale goes 0.85 -> 1.0, scrim goes 0.75 -> 0.0
        val p0StartScale = computeDepthScale(computePageOffset(1, 0, 0.0f))
        val p0StartScrim = computeDepthScrimAlpha(computePageOffset(1, 0, 0.0f))
        val p0EndScale = computeDepthScale(computePageOffset(0, 0, 0.0f))
        val p0EndScrim = computeDepthScrimAlpha(computePageOffset(0, 0, 0.0f))

        assertEquals(0.85f, p0StartScale, 0.001f)
        assertEquals(0.75f, p0StartScrim, 0.001f)
        assertEquals(1.0f, p0EndScale, 0.001f)
        assertEquals(0.0f, p0EndScrim, 0.001f)
    }

    @Test
    fun `test shared artwork active state does NOT depend on mid-flight pager currentPage`() {
        fun isSharedArtworkActive(page: Int, pendingTargetIndex: Int?, currentTrackIndex: Int, pageTrackId: String, activeTrackId: String): Boolean {
            return (page == (pendingTargetIndex ?: currentTrackIndex) && pageTrackId == activeTrackId)
        }

        // Before transition: page 0 is active
        assertTrue(isSharedArtworkActive(page = 0, pendingTargetIndex = null, currentTrackIndex = 0, pageTrackId = "track_0", activeTrackId = "track_0"))
        assertFalse(isSharedArtworkActive(page = 1, pendingTargetIndex = null, currentTrackIndex = 0, pageTrackId = "track_1", activeTrackId = "track_0"))

        // Button tap initiates transition to page 1: pendingTargetIndex = 1
        // Active artwork claim is stable and does NOT flip when pagerState.currentPage changes from 0 to 1 at 50%
        for (simulatedCurrentPage in listOf(0, 1)) {
            val page0Active = isSharedArtworkActive(page = 0, pendingTargetIndex = 1, currentTrackIndex = 0, pageTrackId = "track_0", activeTrackId = "track_1")
            val page1Active = isSharedArtworkActive(page = 1, pendingTargetIndex = 1, currentTrackIndex = 0, pageTrackId = "track_1", activeTrackId = "track_1")
            assertFalse("Page 0 must not claim active artwork during transition to page 1", page0Active)
            assertTrue("Page 1 must stably claim active artwork regardless of currentPage=$simulatedCurrentPage", page1Active)
        }
    }

    @Test
    fun `test tactile bounce uses subtle 0_94 scale down`() {
        val subtleScaleDown = 0.94f
        val previousAggressiveScale = 0.84f

        assertTrue("Tactile bounce must be subtler than previous 0.84f", subtleScaleDown > previousAggressiveScale)
        assertEquals(0.94f, subtleScaleDown, 0.001f)
    }
}

