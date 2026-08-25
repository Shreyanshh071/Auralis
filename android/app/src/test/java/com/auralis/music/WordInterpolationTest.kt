package com.auralis.music

import com.auralis.music.domain.model.LyricWord
import com.auralis.music.ui.screens.lyrics.LyricsEngine
import org.junit.Assert.assertEquals
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
}
