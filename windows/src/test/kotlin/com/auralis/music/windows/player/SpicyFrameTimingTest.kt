package com.auralis.music.windows.player

import com.auralis.music.ui.lyrics.renderers.SpicyFrameTiming
import kotlin.test.*

class SpicyFrameTimingTest {
    @Test fun missedFramesDoNotSnapShortSyllableMotionToItsGoal() {
        // Raster preparation or a compositor hitch can occupy an entire short syllable.
        for (delayMs in listOf(16L, 100L, 283L, 350L, 500L, 800L)) {
            for (rate in listOf(.5, 1.0, 2.0)) {
                val step = SpicyFrameTiming.sample(1_000_000_000L,
                    1_000_000_000L + delayMs * 1_000_000L, 99000L, 99000L + (delayMs * rate).toLong())
                assertFalse(step.reset, "Normal $rate x playback after a $delayMs ms UI delay must retain spring velocity")
                assertEquals(minOf(delayMs / 1000f, .5f), step.seconds, .0001f)
            }
        }
    }

    @Test fun realSeeksStillLandImmediatelyAndPausesDoNotCreateASeek() {
        assertTrue(SpicyFrameTiming.sample(-1, 1_000_000_000, 0, 99000).reset)
        assertTrue(SpicyFrameTiming.sample(1_000_000_000, 1_016_000_000, 99000, 110000).reset)
        assertTrue(SpicyFrameTiming.sample(1_000_000_000, 1_350_000_000, 99000, 90000).reset)
        assertFalse(SpicyFrameTiming.sample(1_000_000_000, 1_350_000_000, 99000, 99000).reset)
    }
}
