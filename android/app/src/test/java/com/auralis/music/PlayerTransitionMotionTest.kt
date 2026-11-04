package com.auralis.music

import androidx.compose.animation.core.TargetBasedAnimation
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntOffset
import com.auralis.music.ui.player.PlayerTransitionMotion
import com.auralis.music.ui.player.PaletteBlend
import com.auralis.music.ui.player.lerpArtworkPalette
import com.auralis.music.ui.player.rememberPaletteBlend
import com.auralis.music.ui.theme.ArtworkPalette
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerTransitionMotionTest {
    @Test
    fun classicPanelLeavesTheWindowRatherThanStoppingBehindMiniPlayer() {
        // Status/navigation insets and dock space are already included in these window bounds.
        for (height in listOf(1280f, 2400f, 3200f)) {
            for (collapsedHeight in listOf(96f, 240f, 380f)) {
                val travel = PlayerTransitionMotion.sheetTravelDistance(height, collapsedHeight, true)
                assertTrue("Collapsed classic panel must be outside the window", travel >= height)
                assertEquals(height - collapsedHeight,
                    PlayerTransitionMotion.sheetTravelDistance(height, collapsedHeight, false), 0f)
            }
        }
    }

    @Test
    fun classicMiniPlayerWaitsForPanelToClearAndReturnsContinuously() {
        val height = 2400f
        val collapsedHeight = 320f
        val miniHeight = 160f
        fun alpha(p: Float) = PlayerTransitionMotion.classicMiniPlayerAlpha(p, height, collapsedHeight, miniHeight)
        assertEquals(0f, alpha(1f), 0f)
        assertEquals(0f, alpha(.3f), 0f) // Previously the mini player appeared over the sheet header here.
        assertEquals(0f, alpha(collapsedHeight / height), .00001f)
        assertEquals(.5f, alpha((collapsedHeight - miniHeight / 2f) / height), .00001f)
        assertEquals(1f, alpha((collapsedHeight - miniHeight) / height), .00001f)
        assertEquals(1f, alpha(0f), 0f)
        var previous = 0f
        for (step in 100 downTo 0) {
            val current = alpha(step / 100f)
            assertTrue(current in previous..1f)
            previous = current
        }
    }

    @Test
    fun playerSheetSettlesAt60And120HzWithoutDependingOnFrameCount() = runTest {
        for (frameNanos in listOf(16_666_667L, 8_333_333L)) {
            val clock = BroadcastFrameClock()
            val progress = Animatable(0f)
            val destinations = mutableListOf<Boolean>()
            val job = launch(clock) {
                PlayerTransitionMotion.settleSheet(progress, 1f, false) { destinations += it }
            }
            runCurrent()
            var nanos = 0L
            var previous = 0f
            while (job.isActive && nanos <= 1_000_000_000L) {
                clock.sendFrame(nanos)
                runCurrent()
                assertTrue(progress.value >= previous)
                assertTrue(progress.value in 0f..1f)
                previous = progress.value
                nanos += frameNanos
            }
            assertTrue("Sheet must settle within a second", job.isCompleted)
            assertEquals(1f, progress.value, 0f)
            assertEquals(listOf(true), destinations)
        }
    }

    @Test
    fun reversingPlayerSheetKeepsVisiblePositionAndDoesNotCommitCancelledDestination() = runTest {
        val clock = BroadcastFrameClock()
        val progress = Animatable(0f)
        val destinations = mutableListOf<Boolean>()
        val opening = launch(clock) {
            PlayerTransitionMotion.settleSheet(progress, 1f, false) { destinations += it }
        }
        runCurrent()
        for (millis in 0L..96L step 16L) {
            clock.sendFrame(millis * 1_000_000L)
            runCurrent()
        }
        val visible = progress.value
        val velocity = progress.velocity
        assertTrue(visible > 0f && visible < 1f)
        opening.cancel()
        runCurrent()
        assertTrue(destinations.isEmpty())
        val closing = launch(clock) {
            PlayerTransitionMotion.settleSheet(progress, 0f, false, velocity) { destinations += it }
        }
        runCurrent()
        assertEquals(visible, progress.value, 0f)
        for (millis in 112L..1_312L step 16L) {
            if (!closing.isActive) break
            clock.sendFrame(millis * 1_000_000L)
            runCurrent()
        }
        assertTrue(closing.isCompleted)
        assertEquals(0f, progress.value, 0f)
        assertEquals(listOf(false), destinations)
    }

    @Test
    fun queueDisplacementStartsOnFirstFrameAndSettlesWithoutOvershootInEitherDirection() {
        for (distance in listOf(-204, -68, 68, 204)) {
            val animation = TargetBasedAnimation(
                PlayerTransitionMotion.queuePlacement,
                IntOffset.VectorConverter,
                IntOffset.Zero,
                IntOffset(0, distance)
            )
            // The configured glide eases in rather than jumping eight pixels on its first frame.
            assertTrue(kotlin.math.abs(animation.getValueFromNanos(32_000_000).y) >= 1)
            var previous = 0
            for (millis in 0..PlayerTransitionMotion.QueuePlacementMillis step 8) {
                val displacement = animation.getValueFromNanos(millis * 1_000_000L).y
                assertTrue(displacement in minOf(0, distance)..maxOf(0, distance))
                assertTrue(kotlin.math.abs(displacement) >= kotlin.math.abs(previous))
                previous = displacement
            }
            assertEquals(IntOffset(0, distance), animation.getValueFromNanos(PlayerTransitionMotion.QueuePlacementMillis * 1_000_000L))
        }
    }

    @Test
    fun interruptedQueuePlacementContinuesFromVisiblePosition() {
        val first = TargetBasedAnimation(
            PlayerTransitionMotion.queuePlacement, IntOffset.VectorConverter,
            IntOffset.Zero, IntOffset(0, 68)
        )
        val visible = first.getValueFromNanos(48_000_000)
        val reversed = TargetBasedAnimation(
            PlayerTransitionMotion.queuePlacement, IntOffset.VectorConverter,
            visible, IntOffset.Zero
        )
        assertEquals(visible, reversed.getValueFromNanos(0))
        assertTrue(reversed.getValueFromNanos(PlayerTransitionMotion.QueuePlacementMillis * 500_000L).y < visible.y)
        assertEquals(IntOffset.Zero, reversed.getValueFromNanos(PlayerTransitionMotion.QueuePlacementMillis * 1_000_000L))
    }

    private fun palette(color: Color) = ArtworkPalette(
        primary = color, secondary = color, tertiary = color,
        glowColors = List(6) { color }
    )

    // Exercise the real composable/LaunchedEffect/Animatable using Compose's frame clock.
    // No device, bitmap/network mocks, or duplicate transition state machine is needed.
    @Test
    fun normalAndRapidPaletteChangesStayContinuousAndConvergeToLatestTarget() = runTest {
        val frameClock = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + frameClock)
        val runner = launch(frameClock) { recomposer.runRecomposeAndApplyChanges() }
        val composition = Composition(object : AbstractApplier<Unit>(Unit) {
            override fun insertTopDown(index: Int, instance: Unit) = Unit
            override fun insertBottomUp(index: Int, instance: Unit) = Unit
            override fun remove(index: Int, count: Int) = Unit
            override fun move(from: Int, to: Int, count: Int) = Unit
            override fun onClear() = Unit
        }, recomposer)
        val a = palette(Color.Red)
        val b = palette(Color.Green)
        val c = palette(Color.Blue)
        val d = palette(Color.Yellow)
        val target = mutableStateOf(a)
        var blend: PaletteBlend? = null
        // What the background draws: the blend's two fixed palettes mixed by its draw-time progress.
        fun visible(): ArtworkPalette =
            blend!!.let { lerpArtworkPalette(it.from, it.to, it.progress.value) }
        var nanos = 0L

        fun applyAtCurrentTime() {
            // Flush animation writes and their recomposition at the same timestamp.
            // Otherwise `visible` is one composition behind the Animatable at retarget time.
            repeat(3) {
                Snapshot.sendApplyNotifications()
                runCurrent()
                frameClock.sendFrame(nanos)
                runCurrent()
            }
        }

        fun frames(count: Int) {
            repeat(count) {
                nanos += 16_000_000L
                applyAtCurrentTime()
                assertEquals(1f, visible().primary.alpha)
                assertTrue(visible().primary != Color.Black)
            }
        }

        try {
            composition.setContent { blend = rememberPaletteBlend(target.value) }
            frames(2)
            assertEquals(a, visible())
            target.value = b
            frames(5)
            assertTrue(visible().primary != a.primary && visible().primary != b.primary)
            frames(30)
            assertEquals(b, visible())

            // Return to A, then interrupt A -> B -> C -> D on consecutive short intervals.
            target.value = a
            frames(35)
            for (next in listOf(b, c, d)) {
                val before = visible()
                target.value = next
                applyAtCurrentTime()
                assertEquals("Retargeting must preserve the visible color", before.primary, visible().primary)
                frames(3)
            }
            frames(30)
            assertEquals(d, visible())
            frames(35)
            assertEquals("No obsolete transition may roll the background back", d, visible())

            // Rapid Previous/Next reversals use the same driver.
            for (next in listOf(c, b, c, d, c)) {
                target.value = next
                frames(3)
            }
            frames(35)
            assertEquals(c, visible())
        } finally {
            composition.dispose()
            recomposer.cancel()
            runner.join()
        }
    }
}
