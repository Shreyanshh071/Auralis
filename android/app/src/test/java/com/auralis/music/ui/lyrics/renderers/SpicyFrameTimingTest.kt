package com.auralis.music.ui.lyrics.renderers

import com.auralis.music.domain.model.LyricWord
import org.junit.Assert.*
import org.junit.Test

class SpicyFrameTimingTest {
    @Test fun delayedFramesAtNormalPlaybackSpeedsDoNotBecomeSeeks() {
        for (delayMs in listOf(8L, 16L, 100L, 283L, 350L, 500L, 800L)) {
            for (rate in listOf(.5, 1.0, 2.0)) {
                val step = SpicyFrameTiming.sample(1_000_000_000L,
                    1_000_000_000L + delayMs * 1_000_000L,
                    99000L, 99000L + (delayMs * rate).toLong())
                assertFalse("$rate x playback after $delayMs ms must preserve motion", step.reset)
                assertEquals(minOf(delayMs / 1000f, .5f), step.seconds, .0001f)
            }
        }
    }

    @Test fun initialFrameAndRealSeeksResetButPausesDoNot() {
        assertTrue(SpicyFrameTiming.sample(-1, 1_000_000_000, 0, 99000).reset)
        assertTrue(SpicyFrameTiming.sample(1_000_000_000, 1_016_000_000, 99000, 110000).reset)
        assertTrue(SpicyFrameTiming.sample(1_000_000_000, 1_350_000_000, 99000, 90000).reset)
        assertFalse(SpicyFrameTiming.sample(1_000_000_000, 1_350_000_000, 99000, 99000).reset)
    }

    @Test fun aDelayedSwellRetainsItsSpringInsteadOfSnappingToTheGoal() {
        val spring = SpicySpring(.88f, .64f)
        spring.drive(.95f, 0f, true)
        val step = SpicyFrameTiming.sample(1_000_000_000, 1_400_000_000, 10000, 10400)
        val shown = spring.drive(1.05f, step.seconds, step.reset)
        assertTrue(shown.isFinite())
        assertTrue("A 400ms delayed frame must integrate rather than snap", kotlin.math.abs(shown - 1.05f) > .0001f)
        assertEquals(1.05f, spring.drive(1.05f, 0f, true), 0f)
    }

    @Test fun wordHighlightKeepsTheSuppliedDurationAcrossDifferentWords() {
        for (text in listOf("good", "together", "आवाज़", "unrelated")) {
            for (duration in listOf(250L, 832L, 1800L, 3500L)) {
                val word = LyricWord(text, 1000L, duration)
                assertEquals(0f, SpicyLyricsMotion.progress(word, 999L), 0f)
                assertEquals(.5f, SpicyLyricsMotion.progress(word, 1000L + duration / 2), .003f)
                assertEquals(1f, SpicyLyricsMotion.progress(word, 1000L + duration), 0f)
            }
        }
    }
}
