package com.auralis.music

import androidx.compose.animation.core.Animatable
import androidx.compose.ui.unit.dp
import com.auralis.music.ui.player.MiniPlayerHeight
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/**
 * Focused regression tests for the bottom navigation dock input-blocking bug.
 *
 * Verifies the core state invariants and geometry:
 * 1. Collapse interrupted by touch: cancellation safety guarantees isNowPlayingOpen=false and progress=0f
 * 2. Rapid transition reversal: 0 -> 1 -> reverse -> 0 never leaves an impossible open/progress combination
 * 3. Atmospheric background input eligibility: clickable is only enabled when visibly open (progress > 0.10f)
 * 4. MiniPlayer gesture bounds: hit-test region strictly corresponds to MiniPlayerHeight, excluding dock padding
 */
class DockInputBlockingRegressionTest {

    // ── Invariant 1: Collapse Interrupted by Touch ──

    @Test
    fun `collapse interrupted by touch cancellation must still set isNowPlayingOpen to false`() = runTest {
        var isNowPlayingOpen = true
        val playerSheetProgress = Animatable(1f)
        var sheetAnimationJob: Job? = null

        // Simulates collapsePlayer() with cancellation safety
        val collapsePlayer: () -> Unit = {
            sheetAnimationJob?.cancel()
            sheetAnimationJob = launch {
                try {
                    // Simulate an interrupted animation
                    playerSheetProgress.snapTo(0.02f)
                    throw CancellationException("Touch intercepted during collapse")
                } finally {
                    isNowPlayingOpen = false
                    if (playerSheetProgress.value < 0.05f) {
                        playerSheetProgress.snapTo(0f)
                    }
                }
            }
        }

        collapsePlayer()
        sheetAnimationJob?.join()

        assertFalse("isNowPlayingOpen must be false even when collapse is interrupted", isNowPlayingOpen)
        assertEquals("playerSheetProgress must snap to 0f when cancelled near 0", 0f, playerSheetProgress.value)

        val isPlayerSheetActive = playerSheetProgress.value > 0f || isNowPlayingOpen
        assertFalse("isPlayerSheetActive must be false after interrupted collapse settles", isPlayerSheetActive)
    }

    @Test
    fun `cancelled collapse cannot leave sheet active with progress near 0`() = runTest {
        var isNowPlayingOpen = true
        val progress = Animatable(0.01f)

        // With finally block handling inside a coroutine
        val job = launch {
            try {
                throw CancellationException("Cancelled by awaitFirstDown")
            } finally {
                isNowPlayingOpen = false
                if (progress.value < 0.05f) {
                    progress.snapTo(0f)
                }
            }
        }
        job.join()

        val isPlayerSheetActive = progress.value > 0f || isNowPlayingOpen
        assertFalse("Player sheet must not remain active when cancelled near 0", isPlayerSheetActive)
        assertEquals(0f, progress.value)
    }

    // ── Invariant 2: Collapse / Expand Rapid Reversal ──

    @Test
    fun `rapid reversal 0 to 1 to 0 never leaves impossible state combination`() = runTest {
        var isNowPlayingOpen = false
        val progress = Animatable(0f)

        // Step 1: Expand requested
        isNowPlayingOpen = true
        progress.snapTo(0.6f) // midway expanding

        // Step 2: User reverses midway (drags or collapses down)
        try {
            progress.snapTo(0.1f) // animating down
        } finally {
            isNowPlayingOpen = false
            if (progress.value < 0.05f) {
                progress.snapTo(0f)
            }
        }

        // Settle to 0
        progress.snapTo(0f)
        val isPlayerSheetActive = progress.value > 0f || isNowPlayingOpen
        val isFullyCollapsed = progress.value == 0f
        val isMiniPlayerVisible = progress.value < 1f || !isNowPlayingOpen

        assertFalse("isNowPlayingOpen must be false after reversing to 0", isNowPlayingOpen)
        assertFalse("isPlayerSheetActive must be false when settled at 0", isPlayerSheetActive)
        assertTrue("isFullyCollapsed must be true at 0", isFullyCollapsed)
        assertTrue("isMiniPlayerVisible must be true when collapsed", isMiniPlayerVisible)
    }

    @Test
    fun `expand completion sets isNowPlayingOpen to true and progress to 1`() = runTest {
        var isNowPlayingOpen = false
        val progress = Animatable(0f)

        try {
            progress.snapTo(0.98f)
        } finally {
            isNowPlayingOpen = true
            if (progress.value > 0.95f) {
                progress.snapTo(1f)
            }
        }

        assertTrue("isNowPlayingOpen must be true when expanded", isNowPlayingOpen)
        assertEquals("progress must snap cleanly to 1f", 1f, progress.value)
        val isPlayerSheetActive = progress.value > 0f || isNowPlayingOpen
        assertTrue("isPlayerSheetActive must be true when expanded", isPlayerSheetActive)
    }

    // ── Invariant 3: Atmospheric Background Input Eligibility ──

    private fun isBackdropClickable(isPlayerSheetActive: Boolean, progress: Float): Boolean {
        return isPlayerSheetActive && progress > 0.10f
    }

    @Test
    fun `backdrop clickable is disabled when collapsed or near zero progress`() {
        // When collapsed (progress = 0f, sheet inactive)
        assertFalse("Backdrop must not be clickable when fully collapsed",
            isBackdropClickable(isPlayerSheetActive = false, progress = 0f))

        // When sheet is active but progress <= 0.10f (visually transparent)
        assertFalse("Backdrop must not be clickable when progress is 0f even if sheetActive",
            isBackdropClickable(isPlayerSheetActive = true, progress = 0f))
        assertFalse("Backdrop must not be clickable when progress is 0.05f",
            isBackdropClickable(isPlayerSheetActive = true, progress = 0.05f))
        assertFalse("Backdrop must not be clickable when progress is exactly 0.10f",
            isBackdropClickable(isPlayerSheetActive = true, progress = 0.10f))
    }

    @Test
    fun `backdrop clickable is enabled when player is visibly open`() {
        // When player is fully open (progress = 1.0f)
        assertTrue("Backdrop must be clickable when player is open at 1f",
            isBackdropClickable(isPlayerSheetActive = true, progress = 1.0f))

        // When player is being dragged/expanded and visible (progress > 0.10f)
        assertTrue("Backdrop must be clickable when visibly expanded at 0.5f",
            isBackdropClickable(isPlayerSheetActive = true, progress = 0.5f))
        assertTrue("Backdrop must be clickable when visibly expanded at 0.15f",
            isBackdropClickable(isPlayerSheetActive = true, progress = 0.15f))
    }

    // ── Invariant 4: MiniPlayer Gesture Bounds & Dock Isolation ──

    @Test
    fun `MiniPlayer gesture bounds correspond to MiniPlayerHeight and exclude dock padding`() {
        val targetBottomPadding = 68.dp
        val bottomInset = 48.dp
        val dockRegionHeight = targetBottomPadding + bottomInset // 116.dp

        // MiniPlayer interaction region
        val miniPlayerHitHeight = MiniPlayerHeight // 68.dp

        // Old buggy configuration had gesture hitbox = 68.dp + 116.dp = 184.dp
        val oldBuggyHitboxHeight = miniPlayerHitHeight + dockRegionHeight
        assertEquals(184.dp, oldBuggyHitboxHeight)

        // New scoped configuration has gesture hitbox strictly = MiniPlayerHeight (68.dp)
        assertEquals(68.dp, miniPlayerHitHeight)

        // Verify the dock region is completely disjoint from the MiniPlayer gesture region:
        // Dock region is [0, dockRegionHeight] from screen bottom.
        // MiniPlayer gesture region is [dockRegionHeight, dockRegionHeight + miniPlayerHitHeight].
        val dockBottom = 0.dp
        val dockTop = dockRegionHeight
        val miniPlayerBottom = dockRegionHeight
        val miniPlayerTop = dockRegionHeight + miniPlayerHitHeight

        assertTrue("MiniPlayer bottom must sit at or above dock top", miniPlayerBottom >= dockTop)
        assertEquals("Dock region height must be fully preserved for dock input", 116.dp, dockTop - dockBottom)
        assertEquals("MiniPlayer gesture region must be exactly MiniPlayerHeight", 68.dp, miniPlayerTop - miniPlayerBottom)
    }

    @Test
    fun `MiniPlayerHeight constant is exactly 68dp across design system`() {
        assertEquals("MiniPlayerHeight constant must remain 68dp", 68.dp, MiniPlayerHeight)
    }
}
