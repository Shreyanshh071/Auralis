package com.auralis.music

import androidx.compose.animation.core.TargetBasedAnimation
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
import com.auralis.music.ui.player.animateArtworkPalette
import com.auralis.music.ui.theme.ArtworkPalette
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerTransitionMotionTest {
    @Test
    fun queueDisplacementStartsOnFirstFrameAndSettlesWithoutOvershootInEitherDirection() {
        for (distance in listOf(-204, -68, 68, 204)) {
            val animation = TargetBasedAnimation(
                PlayerTransitionMotion.queuePlacement,
                IntOffset.VectorConverter,
                IntOffset.Zero,
                IntOffset(0, distance)
            )
            // A visible first-frame move, rather than the slow start of a soft spring.
            assertTrue(kotlin.math.abs(animation.getValueFromNanos(16_000_000).y) >= 8)
            var previous = 0
            for (millis in 0..160 step 8) {
                val displacement = animation.getValueFromNanos(millis * 1_000_000L).y
                assertTrue(displacement in minOf(0, distance)..maxOf(0, distance))
                assertTrue(kotlin.math.abs(displacement) >= kotlin.math.abs(previous))
                previous = displacement
            }
            assertEquals(IntOffset(0, distance), animation.getValueFromNanos(160_000_000))
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
        assertTrue(reversed.getValueFromNanos(16_000_000).y < visible.y)
        assertEquals(IntOffset.Zero, reversed.getValueFromNanos(160_000_000))
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
        var visible = a
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
                assertEquals(1f, visible.primary.alpha)
                assertTrue(visible.primary != Color.Black)
            }
        }

        try {
            composition.setContent { visible = animateArtworkPalette(target.value) }
            frames(2)
            assertEquals(a, visible)
            target.value = b
            frames(5)
            assertTrue(visible.primary != a.primary && visible.primary != b.primary)
            frames(16)
            assertEquals(b, visible)

            // Return to A, then interrupt A -> B -> C -> D on consecutive short intervals.
            target.value = a
            frames(20)
            for (next in listOf(b, c, d)) {
                val before = visible
                target.value = next
                applyAtCurrentTime()
                assertEquals("Retargeting must preserve the visible color", before.primary, visible.primary)
                frames(3)
            }
            frames(14)
            assertEquals(d, visible)
            frames(20)
            assertEquals("No obsolete transition may roll the background back", d, visible)

            // Rapid Previous/Next reversals use the same driver.
            for (next in listOf(c, b, c, d, c)) {
                target.value = next
                frames(3)
            }
            frames(20)
            assertEquals(c, visible)
        } finally {
            composition.dispose()
            recomposer.cancel()
            runner.join()
        }
    }
}
