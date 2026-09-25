package com.auralis.music.ui.lyrics

import com.auralis.music.domain.model.LyricLine
import com.auralis.music.domain.model.LyricWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InstrumentalBreakWindowTest {

    @Test
    fun `word-synced break starts the moment the singing ends and runs to the next line`() {
        val line = LyricLine(
            time = 10_000L, text = "Pal bhar thahar jaao",
            words = listOf(LyricWord("Pal ", 10_000L, 400L), LyricWord("jaao", 12_000L, 1_000L))
        )
        // Singing ends at 13.0s, next line at 30.0s: a 17s break shown in full, not just the last 4.5s.
        assertEquals(13_000L to 30_000L, instrumentalBreakWindow(line, 30_000L))
    }

    @Test
    fun `short word-synced gaps are not breaks`() {
        val line = LyricLine(time = 10_000L, text = "a b", words = listOf(LyricWord("a ", 10_000L, 500L), LyricWord("b", 11_000L, 1_000L)))
        assertNull(instrumentalBreakWindow(line, 16_500L)) // 4.5s of silence
    }

    @Test
    fun `line-synced break starts after the estimated singing instead of 4_5s before the next line`() {
        val line = LyricLine(time = 20_000L, text = "Agar tum saath ho") // 4 words -> 2.6s estimate
        assertEquals(22_600L to 50_000L, instrumentalBreakWindow(line, 50_000L))
    }

    @Test
    fun `line-synced lines closer than 10s apart are normal cadence, not breaks`() {
        assertNull(instrumentalBreakWindow(LyricLine(time = 0L, text = "one two three"), 9_000L))
    }

    @Test
    fun `the sung-length estimate for a long line is capped at 7s`() {
        val line = LyricLine(time = 0L, text = (1..40).joinToString(" ") { "w$it" })
        assertEquals(7_000L to 11_000L, instrumentalBreakWindow(line, 11_000L))
    }
}
