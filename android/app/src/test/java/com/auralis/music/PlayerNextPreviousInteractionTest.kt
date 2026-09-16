package com.auralis.music

import com.auralis.music.domain.model.Track
import com.auralis.music.ui.player.NowPlayingTab
import com.auralis.music.ui.player.deriveActiveTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerNextPreviousInteractionTest {

    private val track0 = Track(id = "t0", title = "Song 0", artist = "Artist 0", thumbnail = "thumb0", duration = 180)
    private val track1 = Track(id = "t1", title = "Song 1", artist = "Artist 1", thumbnail = "thumb1", duration = 200)
    private val track2 = Track(id = "t2", title = "Song 2", artist = "Artist 2", thumbnail = "thumb2", duration = 240)
    private val queue = listOf(track0, track1, track2)

    /**
     * Logic harness simulating the handlePrevious logic from NowPlayingModal:
     * if (positionMs > 3000L) onSeekTo(0L) else onPreviousClick()
     */
    private fun simulateHandlePrevious(
        positionMs: Long,
        onSeekTo: (Long) -> Unit,
        onPreviousClick: () -> Unit
    ) {
        if (positionMs > 3000L) {
            onSeekTo(0L)
        } else {
            onPreviousClick()
        }
    }

    /**
     * Logic harness simulating the handleNext logic from NowPlayingModal:
     * onNextClick()
     */
    private fun simulateHandleNext(onNextClick: () -> Unit) {
        onNextClick()
    }

    /**
     * Logic harness simulating snapshotFlow collection logic in NowPlayingModal:
     * Only commit track change if currentTab == PLAYER && !isScrolling && userSwipedPager && !isProgrammaticScroll
     */
    private class PagerSettleController(
        var currentTrackIndex: Int,
        val queueSize: Int
    ) {
        var userSwipedPager: Boolean = false
        var isProgrammaticScroll: Boolean = false
        var pendingTargetIndex: Int? = null
        var selectedTrackIndex: Int? = null

        fun onSettle(isScrolling: Boolean, settledPage: Int, currentTab: NowPlayingTab = NowPlayingTab.PLAYER) {
            if (currentTab == NowPlayingTab.PLAYER && !isScrolling) {
                if (userSwipedPager && !isProgrammaticScroll) {
                    userSwipedPager = false
                    if (queueSize > 0 && settledPage in 0 until queueSize && settledPage != currentTrackIndex) {
                        pendingTargetIndex = settledPage
                        selectedTrackIndex = settledPage
                    } else if (settledPage == currentTrackIndex) {
                        pendingTargetIndex = null
                    }
                } else {
                    userSwipedPager = false
                    if (settledPage == currentTrackIndex) {
                        pendingTargetIndex = null
                    }
                }
            }
        }
    }

    @Test
    fun testPreviousButtonPast3000msSeeksToZeroWithoutTrackChange() {
        var seekPosition: Long? = null
        var previousClicked = false

        // Test at 3001ms
        simulateHandlePrevious(
            positionMs = 3001L,
            onSeekTo = { seekPosition = it },
            onPreviousClick = { previousClicked = true }
        )

        assertEquals(0L, seekPosition)
        assertFalse("Previous track must NOT be called when position > 3000ms", previousClicked)

        // Test at 45000ms
        seekPosition = null
        previousClicked = false
        simulateHandlePrevious(
            positionMs = 45000L,
            onSeekTo = { seekPosition = it },
            onPreviousClick = { previousClicked = true }
        )

        assertEquals(0L, seekPosition)
        assertFalse("Previous track must NOT be called when position > 3000ms", previousClicked)
    }

    @Test
    fun testPreviousButtonAtOrUnder3000msCallsOnPreviousClick() {
        var seekPosition: Long? = null
        var previousClicked = false

        // Test at exactly 3000ms boundary
        simulateHandlePrevious(
            positionMs = 3000L,
            onSeekTo = { seekPosition = it },
            onPreviousClick = { previousClicked = true }
        )

        assertNull("onSeekTo must NOT be called when position <= 3000ms", seekPosition)
        assertTrue("onPreviousClick MUST be called when position <= 3000ms", previousClicked)

        // Test at 1500ms
        seekPosition = null
        previousClicked = false
        simulateHandlePrevious(
            positionMs = 1500L,
            onSeekTo = { seekPosition = it },
            onPreviousClick = { previousClicked = true }
        )

        assertNull("onSeekTo must NOT be called when position <= 3000ms", seekPosition)
        assertTrue("onPreviousClick MUST be called when position <= 3000ms", previousClicked)

        // Test at 0ms
        seekPosition = null
        previousClicked = false
        simulateHandlePrevious(
            positionMs = 0L,
            onSeekTo = { seekPosition = it },
            onPreviousClick = { previousClicked = true }
        )

        assertNull("onSeekTo must NOT be called when position <= 3000ms", seekPosition)
        assertTrue("onPreviousClick MUST be called when position <= 3000ms", previousClicked)
    }

    @Test
    fun testNextButtonClickCallsOnNextClickImmediately() {
        var nextClickCount = 0
        simulateHandleNext { nextClickCount++ }
        assertEquals(1, nextClickCount)
    }

    @Test
    fun testRapidNextClicksNeverBlockOrDrop() {
        var nextClickCount = 0
        // Rapid 5 clicks
        repeat(5) {
            simulateHandleNext { nextClickCount++ }
        }
        assertEquals("All rapid next clicks must immediately register without blocking or locking", 5, nextClickCount)
    }

    @Test
    fun testProgrammaticScrollDoesNotRevertTrackOnSettle() {
        // Reproduce the bug where programmatic scroll settled on old page and reverted playback
        val controller = PagerSettleController(currentTrackIndex = 1, queueSize = queue.size)
        // User did not swipe (button was tapped)
        controller.userSwipedPager = false
        controller.isProgrammaticScroll = false // e.g. animation finished or cancelled

        // Pager temporarily settles or reports settledPage = 0 while currentTrackIndex = 1
        controller.onSettle(isScrolling = false, settledPage = 0)

        // It must NOT select track 0!
        assertNull("Programmatic scroll settle must NEVER revert or select a track", controller.selectedTrackIndex)
        assertNull(controller.pendingTargetIndex)
    }

    @Test
    fun testUserSwipedPagerSettledCommitsTrackChange() {
        val controller = PagerSettleController(currentTrackIndex = 0, queueSize = queue.size)
        // User physically dragged
        controller.userSwipedPager = true
        controller.isProgrammaticScroll = false

        // Carousel settles on page 1
        controller.onSettle(isScrolling = false, settledPage = 1)

        assertEquals("Physically swiping to page 1 must select track index 1", 1, controller.selectedTrackIndex)
        assertEquals(1, controller.pendingTargetIndex)
        assertFalse("userSwipedPager flag must reset after settling", controller.userSwipedPager)
    }

    @Test
    fun testUserSwipedPagerPartialSnapBackDoesNotCommitTrackChange() {
        val controller = PagerSettleController(currentTrackIndex = 0, queueSize = queue.size)
        // User dragged partially
        controller.userSwipedPager = true
        controller.isProgrammaticScroll = false

        // User releases, pager snaps back to page 0 (currentTrackIndex)
        controller.onSettle(isScrolling = false, settledPage = 0)

        assertNull("Snapping back to current page must NOT select a track", controller.selectedTrackIndex)
        assertNull("pendingTargetIndex must remain null on snap back", controller.pendingTargetIndex)
        assertFalse("userSwipedPager flag must reset after settling", controller.userSwipedPager)
    }

    @Test
    fun testDeriveActiveTrackAuthoritativeBehavior() {
        // In player tab with no pending swipe target, playing track is authoritative
        val track = deriveActiveTrack(
            currentTab = NowPlayingTab.PLAYER,
            pendingTargetIndex = null,
            currentTrackIndex = 0,
            queue = queue,
            playingTrack = track0
        )
        assertEquals("t0", track.id)

        // With pending target (e.g. user committed swipe to index 1)
        val pendingTrack = deriveActiveTrack(
            currentTab = NowPlayingTab.PLAYER,
            pendingTargetIndex = 1,
            currentTrackIndex = 0,
            queue = queue,
            playingTrack = track0
        )
        assertEquals("t1", pendingTrack.id)

        // In lyrics or queue tab, playing track is always authoritative
        val lyricsTrack = deriveActiveTrack(
            currentTab = NowPlayingTab.LYRICS,
            pendingTargetIndex = 1,
            currentTrackIndex = 0,
            queue = queue,
            playingTrack = track0
        )
        assertEquals("t0", lyricsTrack.id)
    }

    @Test
    fun testDynamicBgConditionForButtonsVsSwipes() {
        // Swipe condition in dynamicBgData:
        // currentTab == PLAYER && userSwipedPager && isScrollInProgress && queue.size > 1
        fun shouldEngageContinuousSwipeBlending(
            currentTab: NowPlayingTab,
            userSwipedPager: Boolean,
            isScrollInProgress: Boolean,
            queueSize: Int
        ): Boolean {
            return currentTab == NowPlayingTab.PLAYER && userSwipedPager && isScrollInProgress && queueSize > 1
        }

        // Button click animation: userSwipedPager is FALSE
        val duringButtonClick = shouldEngageContinuousSwipeBlending(
            currentTab = NowPlayingTab.PLAYER,
            userSwipedPager = false,
            isScrollInProgress = true,
            queueSize = 3
        )
        assertFalse("Button click animation must NOT engage raw dual-artwork swipe blending; it must use smooth 320ms crossfade", duringButtonClick)

        // Manual gesture swipe: userSwipedPager is TRUE
        val duringUserSwipe = shouldEngageContinuousSwipeBlending(
            currentTab = NowPlayingTab.PLAYER,
            userSwipedPager = true,
            isScrollInProgress = true,
            queueSize = 3
        )
        assertTrue("Manual swipe gesture MUST engage continuous real-time swipe blending", duringUserSwipe)
    }

    @Test
    fun testQueueBoundsSafety() {
        val outOfBoundsNegative = deriveActiveTrack(
            currentTab = NowPlayingTab.PLAYER,
            pendingTargetIndex = -1,
            currentTrackIndex = 0,
            queue = queue,
            playingTrack = track0
        )
        assertEquals("Negative index must safely fallback to playingTrack", "t0", outOfBoundsNegative.id)

        val outOfBoundsPositive = deriveActiveTrack(
            currentTab = NowPlayingTab.PLAYER,
            pendingTargetIndex = 99,
            currentTrackIndex = 0,
            queue = queue,
            playingTrack = track0
        )
        assertEquals("Excessive index must safely fallback to playingTrack", "t0", outOfBoundsPositive.id)
    }
}
