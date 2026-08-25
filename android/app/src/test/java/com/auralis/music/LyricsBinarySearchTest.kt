package com.auralis.music

import com.auralis.music.domain.model.LyricLine
import com.auralis.music.ui.screens.lyrics.LyricsEngine
import org.junit.Assert.assertEquals
import org.junit.Test

class LyricsBinarySearchTest {

    @Test
    fun `findActiveLyricIndex returns exact active line index for standard time positions`() {
        val lines = listOf(
            LyricLine(time = 10_000L, text = "Line 1"),
            LyricLine(time = 20_000L, text = "Line 2"),
            LyricLine(time = 30_000L, text = "Line 3"),
            LyricLine(time = 40_000L, text = "Line 4")
        )

        // Before first line -> -1
        assertEquals(-1, LyricsEngine.findActiveLyricIndex(lines, currentTimeMs = 5_000L))

        // Exactly on line 1 -> 0
        assertEquals(0, LyricsEngine.findActiveLyricIndex(lines, currentTimeMs = 10_000L))

        // Between line 1 and 2 -> 0
        assertEquals(0, LyricsEngine.findActiveLyricIndex(lines, currentTimeMs = 15_000L))

        // Exactly on line 2 -> 1
        assertEquals(1, LyricsEngine.findActiveLyricIndex(lines, currentTimeMs = 20_000L))

        // Past last line -> 3
        assertEquals(3, LyricsEngine.findActiveLyricIndex(lines, currentTimeMs = 50_000L))
    }

    @Test
    fun `findActiveLyricIndex respects manual offset adjustments`() {
        val lines = listOf(
            LyricLine(time = 10_000L, text = "Line 1"),
            LyricLine(time = 20_000L, text = "Line 2")
        )

        // Current time 9500ms + 500ms offset = 10000ms -> activates Line 1
        assertEquals(0, LyricsEngine.findActiveLyricIndex(lines, currentTimeMs = 9_500L, offsetMs = 500L))

        // Current time 10200ms - 500ms offset = 9700ms -> before Line 1 (-1)
        assertEquals(-1, LyricsEngine.findActiveLyricIndex(lines, currentTimeMs = 10_200L, offsetMs = -500L))
    }
}
