package com.auralis.music

import com.auralis.music.domain.model.LyricLine
import com.auralis.music.domain.model.LyricWord
import com.auralis.music.ui.screens.lyrics.LyricsEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The "never stretch a word across silence" requirement, encoded.
 *
 * A line with a real rest inside it: "Hold" is sung over [0, 500] and "on" over
 * [3000, 3500], with two and a half seconds of nothing in between. Nothing may
 * animate across that gap — the sung word must be finished and the next word must
 * not have started — while the line itself stays on screen.
 */
class PauseGapTest {

    private val gapLine = LyricLine(
        time = 0L,
        text = "Hold on",
        words = listOf(
            LyricWord(word = "Hold ", time = 0L, duration = 500L),
            LyricWord(word = "on", time = 3000L, duration = 500L)
        )
    )

    private fun progressAt(index: Int, t: Long): Float =
        LyricsEngine.calculateWordProgress(gapLine.words!![index], t)

    @Test
    fun `mid-gap paints neither word partially`() {
        val t = 1500L
        assertEquals("first word must be complete, not still sweeping", 1.0f, progressAt(0, t), 0.0001f)
        assertEquals("second word must not have started", 0.0f, progressAt(1, t), 0.0001f)
    }

    @Test
    fun `nothing moves anywhere inside the rest`() {
        // Sampled every 10 ms across the whole silence: the pair of progress values
        // is constant. A fabricated duration would make the first value climb.
        for (t in 500L..3000L step 10L) {
            assertEquals("word 0 changed at t=$t", 1.0f, progressAt(0, t), 0.0001f)
            assertEquals("word 1 changed at t=$t", 0.0f, progressAt(1, t), 0.0001f)
        }
    }

    @Test
    fun `no word is active during the rest`() {
        val t = 1500L
        val active = gapLine.words!!.firstOrNull { w ->
            t >= w.time && (w.endTime?.let { t < it } ?: false)
        }
        assertNull("a word was reported as mid-sweep during silence", active)
    }

    @Test
    fun `each word still sweeps inside its own measured span`() {
        assertEquals(0.5f, progressAt(0, 250L), 0.0001f)
        assertEquals(0.5f, progressAt(1, 3250L), 0.0001f)
    }

    @Test
    fun `line remains active throughout the rest`() {
        val lines = listOf(gapLine, LyricLine(time = 6000L, text = "Next line"))
        // The rest belongs to this line, so the reader keeps reading it.
        for (t in 0L..5999L step 250L) {
            assertEquals("line changed at t=$t", 0, LyricsEngine.findActiveLyricIndex(lines, t))
        }
        assertEquals(1, LyricsEngine.findActiveLyricIndex(lines, 6000L))
    }

    @Test
    fun `word ends never overlap the next word start`() {
        // The invariant that makes the above possible, stated directly.
        val words = gapLine.words!!
        for (i in 0 until words.size - 1) {
            val end = words[i].endTime ?: continue
            assertTrue(
                "word $i ends at $end, past the next word's start ${words[i + 1].time}",
                end <= words[i + 1].time
            )
        }
    }
}
