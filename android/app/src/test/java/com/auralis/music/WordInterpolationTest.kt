package com.auralis.music

import com.auralis.music.domain.model.LyricWord
import com.auralis.music.ui.screens.lyrics.LyricsEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WordInterpolationTest {

    @Test
    fun `calculateWordProgress interpolates fill percentage across word duration`() {
        val word = LyricWord(word = "Blinded", time = 10_000L, duration = 1000L)

        // Before word starts -> 0.0f
        assertEquals(0.0f, LyricsEngine.calculateWordProgress(word, currentTimeMs = 9000L), 0.001f)

        // Exactly at start -> 0.0f
        assertEquals(0.0f, LyricsEngine.calculateWordProgress(word, currentTimeMs = 10_000L), 0.001f)

        // Halfway through (500ms into 1000ms) -> 0.5f
        assertEquals(0.5f, LyricsEngine.calculateWordProgress(word, currentTimeMs = 10_500L), 0.001f)

        // At end of word -> 1.0f
        assertEquals(1.0f, LyricsEngine.calculateWordProgress(word, currentTimeMs = 11_000L), 0.001f)

        // Past end of word -> 1.0f
        assertEquals(1.0f, LyricsEngine.calculateWordProgress(word, currentTimeMs = 15_000L), 0.001f)
    }

    @Test
    fun `calculateWordProgress respects manual sync offset`() {
        val word = LyricWord(word = "Lights", time = 20_000L, duration = 1000L)

        // 19500ms + 500ms offset = 20000ms (start of word) -> 0.0f
        assertEquals(0.0f, LyricsEngine.calculateWordProgress(word, currentTimeMs = 19_500L, offsetMs = 500L), 0.001f)

        // 20000ms + 500ms offset = 20500ms (halfway) -> 0.5f
        assertEquals(0.5f, LyricsEngine.calculateWordProgress(word, currentTimeMs = 20_000L, offsetMs = 500L), 0.001f)
    }

    @Test
    fun `null duration steps at the genuine start instead of sweeping`() {
        // Enhanced LRC states word starts and no ends. There is no measured length
        // to sweep across, so the word must flip at its start — a sweep here would
        // be painting across time the provider never described.
        val word = LyricWord(word = "said,", time = 12_500L, duration = null)

        assertEquals(0.0f, LyricsEngine.calculateWordProgress(word, currentTimeMs = 12_499L), 0.001f)
        assertEquals(1.0f, LyricsEngine.calculateWordProgress(word, currentTimeMs = 12_500L), 0.001f)
        assertEquals(1.0f, LyricsEngine.calculateWordProgress(word, currentTimeMs = 12_501L), 0.001f)
        // No intermediate value exists anywhere in between.
        for (t in 12_400L..12_600L step 10L) {
            val p = LyricsEngine.calculateWordProgress(word, currentTimeMs = t)
            assertTrue("progress $p at t=$t is neither 0 nor 1", p == 0.0f || p == 1.0f)
        }
    }

    @Test
    fun `non-positive duration is treated as unknown rather than swept`() {
        val zero = LyricWord(word = "ooh", time = 5_000L, duration = 0L)
        assertEquals(0.0f, LyricsEngine.calculateWordProgress(zero, currentTimeMs = 4_999L), 0.001f)
        assertEquals(1.0f, LyricsEngine.calculateWordProgress(zero, currentTimeMs = 5_000L), 0.001f)
    }
}
