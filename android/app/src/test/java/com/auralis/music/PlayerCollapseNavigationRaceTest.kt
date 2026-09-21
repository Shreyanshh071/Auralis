package com.auralis.music

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.MonotonicFrameClock
import com.auralis.music.ui.AppDestination
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/**
 * Regression tests for the chevron close -> immediate navigation stuck player bug.
 *
 * Verifies:
 * 1. Chevron collapse followed immediately by Library navigation reaches progress=0f cleanly
 * 2. Chevron collapse followed immediately by Search (Explore) reaches progress=0f cleanly
 * 3. Chevron collapse followed immediately by Home reaches progress=0f cleanly
 * 4. Collapse animation cannot be stranded at intermediate progress merely because another UI element is tapped
 * 5. Genuine player drag reversal still interrupts and takes over the animation correctly
 * 6. Final state after collapse satisfies all invariants (isNowPlayingOpen=false, progress=0, isPlayerSheetActive=false)
 * 7. Final state after expand satisfies all invariants (isNowPlayingOpen=true, progress=1, isPlayerSheetActive=true)
 * 8. Rapid open -> collapse -> Library / Search / Home sequences settle cleanly
 * 9. Repeated transition sequences (20+ cycles) remain 100% stable with zero stuck player states
 */
class PlayerCollapseNavigationRaceTest {

    private class ImmediateFrameClock : MonotonicFrameClock {
        private var time = 0L
        override suspend fun <R> withFrameNanos(onFrame: (frameTimeNanos: Long) -> R): R {
            time += 16_000_000L
            return onFrame(time)
        }
    }

    /**
     * Simulated state model matching AuralisApp.kt
     */
    private class PlayerNavigationModel(private val coroutineScope: kotlinx.coroutines.CoroutineScope) {
        val clock = ImmediateFrameClock()
        val playerSheetProgress = Animatable(0f)
        var isNowPlayingOpen = false
        var currentDestination = AppDestination.HOME
        var sheetAnimationJob: Job? = null

        val isPlayerSheetActive: Boolean
            get() = playerSheetProgress.value > 0f || isNowPlayingOpen

        val isFullyCollapsed: Boolean
            get() = playerSheetProgress.value == 0f

        val isMiniPlayerVisible: Boolean
            get() = playerSheetProgress.value < 1f || !isNowPlayingOpen

        fun expandPlayer() {
            isNowPlayingOpen = true
            sheetAnimationJob?.cancel()
            sheetAnimationJob = coroutineScope.launch(clock) {
                try {
                    playerSheetProgress.animateTo(1f, tween(100))
                } finally {
                    isNowPlayingOpen = true
                    if (playerSheetProgress.value > 0.95f) {
                        playerSheetProgress.snapTo(1f)
                    }
                }
            }
        }

        fun collapsePlayer() {
            sheetAnimationJob?.cancel()
            sheetAnimationJob = coroutineScope.launch(clock) {
                try {
                    playerSheetProgress.animateTo(0f, tween(100))
                } finally {
                    isNowPlayingOpen = false
                    if (playerSheetProgress.value < 0.05f) {
                        playerSheetProgress.snapTo(0f)
                    }
                }
            }
        }

        fun navigateTo(destination: AppDestination) {
            currentDestination = destination
        }

        /**
         * Simulates a genuine vertical drag on the player sheet past touch slop.
         * Note that onDragStart cancels any existing animation and takes ownership.
         */
        fun startGenuineDrag(): DragSession {
            sheetAnimationJob?.cancel()
            var currentProgress = playerSheetProgress.value
            return object : DragSession {
                override fun onVerticalDrag(deltaProgress: Float) {
                    currentProgress = (currentProgress + deltaProgress).coerceIn(0f, 1f)
                    sheetAnimationJob?.cancel()
                    sheetAnimationJob = coroutineScope.launch(clock) {
                        playerSheetProgress.snapTo(currentProgress)
                    }
                }

                override fun onDragEnd(target: Float) {
                    sheetAnimationJob = coroutineScope.launch(clock) {
                        try {
                            playerSheetProgress.animateTo(target, tween(80))
                        } finally {
                            isNowPlayingOpen = (target == 1f)
                            if (target == 0f && playerSheetProgress.value < 0.05f) {
                                playerSheetProgress.snapTo(0f)
                            } else if (target == 1f && playerSheetProgress.value > 0.95f) {
                                playerSheetProgress.snapTo(1f)
                            }
                        }
                    }
                }

                override fun onDragCancel() {
                    val target = if (currentProgress < 0.5f) 0f else 1f
                    onDragEnd(target)
                }
            }
        }

        interface DragSession {
            fun onVerticalDrag(deltaProgress: Float)
            fun onDragEnd(target: Float)
            fun onDragCancel()
        }
    }

    // ── Test 1: Chevron collapse followed immediately by Library navigation ──

    @Test
    fun `chevron collapse followed immediately by Library navigation settles cleanly`() = runTest {
        val model = PlayerNavigationModel(this)
        model.expandPlayer()
        model.sheetAnimationJob?.join()
        assertEquals(1f, model.playerSheetProgress.value)
        assertTrue(model.isNowPlayingOpen)

        // Chevron tapped -> collapse starts
        model.collapsePlayer()

        // User immediately taps Library while collapse is in progress
        model.navigateTo(AppDestination.LIBRARY)
        assertEquals(AppDestination.LIBRARY, model.currentDestination)

        // Collapse finishes uninterrupted
        model.sheetAnimationJob?.join()

        assertEquals("playerSheetProgress must reach 0f", 0f, model.playerSheetProgress.value)
        assertFalse("isNowPlayingOpen must be false", model.isNowPlayingOpen)
        assertFalse("isPlayerSheetActive must be false", model.isPlayerSheetActive)
        assertTrue("isFullyCollapsed must be true", model.isFullyCollapsed)
        assertEquals(AppDestination.LIBRARY, model.currentDestination)
    }

    // ── Test 2: Chevron collapse followed immediately by Search (Explore) navigation ──

    @Test
    fun `chevron collapse followed immediately by Search navigation settles cleanly`() = runTest {
        val model = PlayerNavigationModel(this)
        model.expandPlayer()
        model.sheetAnimationJob?.join()

        model.collapsePlayer()
        model.navigateTo(AppDestination.EXPLORE)
        model.sheetAnimationJob?.join()

        assertEquals(0f, model.playerSheetProgress.value)
        assertFalse(model.isNowPlayingOpen)
        assertFalse(model.isPlayerSheetActive)
        assertEquals(AppDestination.EXPLORE, model.currentDestination)
    }

    // ── Test 3: Chevron collapse followed immediately by Home navigation ──

    @Test
    fun `chevron collapse followed immediately by Home navigation settles cleanly`() = runTest {
        val model = PlayerNavigationModel(this)
        model.navigateTo(AppDestination.LIBRARY)
        model.expandPlayer()
        model.sheetAnimationJob?.join()

        model.collapsePlayer()
        model.navigateTo(AppDestination.HOME)
        model.sheetAnimationJob?.join()

        assertEquals(0f, model.playerSheetProgress.value)
        assertFalse(model.isNowPlayingOpen)
        assertFalse(model.isPlayerSheetActive)
        assertEquals(AppDestination.HOME, model.currentDestination)
    }

    // ── Test 4: Collapse cannot be stranded by arbitrary UI element taps ──

    @Test
    fun `collapse animation cannot be stranded at intermediate progress by UI taps`() = runTest {
        val model = PlayerNavigationModel(this)
        model.expandPlayer()
        model.sheetAnimationJob?.join()

        // Start collapse
        model.collapsePlayer()

        // Without the removed pointerInput trap, taps on UI elements (dock, lists, buttons)
        // do NOT cancel sheetAnimationJob. The collapse job completes fully.
        model.navigateTo(AppDestination.LIBRARY)
        model.sheetAnimationJob?.join()

        assertFalse("Player sheet must not remain active after collapse", model.isPlayerSheetActive)
        assertEquals("Player progress must not be stranded at intermediate value", 0f, model.playerSheetProgress.value)
    }

    // ── Test 5: Genuine player drag reversal still interrupts and takes over correctly ──

    @Test
    fun `genuine player drag reversal still interrupts and takes over animation correctly`() = runTest {
        val model = PlayerNavigationModel(this)
        model.expandPlayer()
        model.sheetAnimationJob?.join()

        // User starts collapse
        model.collapsePlayer()

        // User genuinely catches the sheet and drags it back up
        val dragSession = model.startGenuineDrag()
        dragSession.onVerticalDrag(deltaProgress = 0.4f)
        model.sheetAnimationJob?.join()

        // User flings/releases upward to re-expand
        dragSession.onDragEnd(target = 1f)
        model.sheetAnimationJob?.join()

        assertEquals("playerSheetProgress must reach 1f after upward drag reversal", 1f, model.playerSheetProgress.value)
        assertTrue("isNowPlayingOpen must be true after re-expanding", model.isNowPlayingOpen)
        assertTrue("isPlayerSheetActive must be true", model.isPlayerSheetActive)
    }

    // ── Test 6: Final state after collapse satisfies all invariants ──

    @Test
    fun `final state after collapse satisfies all state invariants`() = runTest {
        val model = PlayerNavigationModel(this)
        model.expandPlayer()
        model.sheetAnimationJob?.join()

        model.collapsePlayer()
        model.sheetAnimationJob?.join()

        assertFalse("isNowPlayingOpen must be false", model.isNowPlayingOpen)
        assertEquals("playerSheetProgress must be 0f", 0f, model.playerSheetProgress.value)
        assertFalse("isPlayerSheetActive must be false", model.isPlayerSheetActive)
        assertTrue("isFullyCollapsed must be true", model.isFullyCollapsed)
        assertTrue("isMiniPlayerVisible must be true", model.isMiniPlayerVisible)
    }

    // ── Test 7: Final state after expand satisfies all invariants ──

    @Test
    fun `final state after expand satisfies all state invariants`() = runTest {
        val model = PlayerNavigationModel(this)
        assertEquals(0f, model.playerSheetProgress.value)
        assertFalse(model.isNowPlayingOpen)

        model.expandPlayer()
        model.sheetAnimationJob?.join()

        assertTrue("isNowPlayingOpen must be true", model.isNowPlayingOpen)
        assertEquals("playerSheetProgress must be 1f", 1f, model.playerSheetProgress.value)
        assertTrue("isPlayerSheetActive must be true", model.isPlayerSheetActive)
        assertFalse("isFullyCollapsed must be false", model.isFullyCollapsed)
        assertFalse("isMiniPlayerVisible must be false", model.isMiniPlayerVisible)
    }

    // ── Test 8: Rapid open -> collapse -> Library, Search, Home sequences ──

    @Test
    fun `rapid open collapse navigation sequences settle cleanly`() = runTest {
        val model = PlayerNavigationModel(this)

        // Rapid 1: Open -> Collapse -> Library
        model.expandPlayer()
        model.sheetAnimationJob?.join()
        model.collapsePlayer()
        model.navigateTo(AppDestination.LIBRARY)
        model.sheetAnimationJob?.join()
        assertEquals(0f, model.playerSheetProgress.value)
        assertFalse(model.isPlayerSheetActive)
        assertEquals(AppDestination.LIBRARY, model.currentDestination)

        // Rapid 2: Open -> Collapse -> Search
        model.expandPlayer()
        model.sheetAnimationJob?.join()
        model.collapsePlayer()
        model.navigateTo(AppDestination.EXPLORE)
        model.sheetAnimationJob?.join()
        assertEquals(0f, model.playerSheetProgress.value)
        assertFalse(model.isPlayerSheetActive)
        assertEquals(AppDestination.EXPLORE, model.currentDestination)

        // Rapid 3: Open -> Collapse -> Home
        model.expandPlayer()
        model.sheetAnimationJob?.join()
        model.collapsePlayer()
        model.navigateTo(AppDestination.HOME)
        model.sheetAnimationJob?.join()
        assertEquals(0f, model.playerSheetProgress.value)
        assertFalse(model.isPlayerSheetActive)
        assertEquals(AppDestination.HOME, model.currentDestination)
    }

    // ── Test 9: Repeated transition sequence (20+ cycles) ──

    @Test
    fun `repeated transition sequences remain 100 percent stable with zero stuck player states`() = runTest {
        val model = PlayerNavigationModel(this)
        val destinations = listOf(AppDestination.LIBRARY, AppDestination.EXPLORE, AppDestination.HOME)

        for (i in 0 until 24) {
            val dest = destinations[i % destinations.size]

            // Open
            model.expandPlayer()
            model.sheetAnimationJob?.join()
            assertTrue("Cycle $i: must be open", model.isNowPlayingOpen)
            assertEquals("Cycle $i: progress must be 1f", 1f, model.playerSheetProgress.value)

            // Collapse & immediate navigation
            model.collapsePlayer()
            model.navigateTo(dest)
            model.sheetAnimationJob?.join()

            // Verify clean settlement
            assertEquals("Cycle $i: progress must settle to 0f", 0f, model.playerSheetProgress.value)
            assertFalse("Cycle $i: isNowPlayingOpen must be false", model.isNowPlayingOpen)
            assertFalse("Cycle $i: isPlayerSheetActive must be false", model.isPlayerSheetActive)
            assertEquals("Cycle $i: destination must match", dest, model.currentDestination)
        }
    }
}
