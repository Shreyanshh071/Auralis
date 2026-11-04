package com.auralis.music

import com.auralis.music.domain.model.LyricLine
import com.auralis.music.ui.lyrics.SpicyLyricsPresentation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpicyLineHoldTest {
    private fun line(start: Long, end: Long, text: String) = LyricLine(time = start, text = text, endTime = end)

    @Test fun `a sung line stays highlighted through a short rest`() {
        val lines = listOf(line(1_000, 3_000, "one"), line(4_000, 6_000, "two"))
        assertEquals(setOf(0), SpicyLyricsPresentation.activeIndices(lines, 3_500))
    }

    @Test fun `the next line takes over the moment it starts`() {
        val lines = listOf(line(1_000, 3_000, "one"), line(4_000, 6_000, "two"))
        assertEquals(setOf(1), SpicyLyricsPresentation.activeIndices(lines, 4_000))
    }

    @Test fun `a long instrumental break still releases the line`() {
        val lines = listOf(line(1_000, 3_000, "one"), line(20_000, 22_000, "two"))
        assertTrue(SpicyLyricsPresentation.activeIndices(lines, 10_000).isEmpty())
    }
}
