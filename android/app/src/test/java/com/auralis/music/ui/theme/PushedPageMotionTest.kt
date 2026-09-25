package com.auralis.music.ui.theme

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Settings / section / Profile sub-page push timing, driven through the real
 * animateFloatAsState on Compose's frame clock (no device needed).
 */
class PushedPageMotionTest {

    private class Harness(scope: TestScope, reducedMotion: Boolean) {
        val frameClock = BroadcastFrameClock()
        val recomposer = Recomposer(scope.coroutineContext + frameClock)
        val runner = scope.launch(frameClock) { recomposer.runRecomposeAndApplyChanges() }
        val composition = Composition(object : AbstractApplier<Unit>(Unit) {
            override fun insertTopDown(index: Int, instance: Unit) = Unit
            override fun insertBottomUp(index: Int, instance: Unit) = Unit
            override fun remove(index: Int, count: Int) = Unit
            override fun move(from: Int, to: Int, count: Int) = Unit
            override fun onClear() = Unit
        }, recomposer)
        val pushed = mutableStateOf(false)
        var progress: State<Float>? = null
        var nanos = 0L
        private val testScope = scope

        init {
            composition.setContent {
                CompositionLocalProvider(LocalReducedMotion provides reducedMotion) {
                    progress = rememberPushProgress(pushed.value)
                }
            }
        }

        fun frame(ms: Long = 16) {
            nanos += ms * 1_000_000L
            repeat(3) {
                Snapshot.sendApplyNotifications()
                testScope.runCurrent()
                frameClock.sendFrame(nanos)
                testScope.runCurrent()
            }
        }

        fun value() = progress!!.value

        suspend fun close() {
            composition.dispose()
            recomposer.cancel()
            runner.join()
        }
    }

    @Test
    fun pushOpensOver200msAndPopReversesFromTheVisiblePosition() = runTest {
        val h = Harness(this, reducedMotion = false)
        try {
            h.frame()
            assertEquals(0f, h.value())

            h.pushed.value = true
            h.frame()
            var previous = h.value()
            repeat(5) {
                h.frame()
                assertTrue("push progress must rise monotonically", h.value() >= previous)
                previous = h.value()
            }
            // Mid-flight: visibly moving, not a one-frame swap.
            assertTrue(h.value() in 0.2f..0.99f)

            // Back pressed mid-push: continues from where it is, no jump.
            val before = h.value()
            h.pushed.value = false
            h.frame(0)
            assertEquals(before, h.value(), 0.02f)
            repeat(20) { h.frame() }
            assertEquals(0f, h.value())

            // A full push lands in ~200ms (VIVI's page transition), well within 20 frames.
            h.pushed.value = true
            repeat(16) { h.frame() }
            assertEquals(1f, h.value())
        } finally {
            h.close()
        }
    }

    @Test
    fun reducedMotionSwapsImmediately() = runTest {
        val h = Harness(this, reducedMotion = true)
        try {
            h.frame()
            h.pushed.value = true
            h.frame()
            h.frame()
            assertEquals(1f, h.value())
            h.pushed.value = false
            h.frame()
            h.frame()
            assertEquals(0f, h.value())
        } finally {
            h.close()
        }
    }
}
