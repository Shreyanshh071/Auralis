package com.auralis.music

import com.auralis.music.data.parser.WordTiming
import com.auralis.music.domain.model.LyricLine
import com.auralis.music.domain.model.LyricWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The only derivation this codebase permits: subdividing a span that already
 * carries a provider-measured duration into its words, proportionally by
 * character count. It must invent no interval — every piece stays inside
 * `[start, start + duration]`, the pieces are monotonic and contiguous, and they
 * cover the span exactly.
 */
class SubdivisionTest {

    @Test
    fun `two-word span is split inside its own interval`() {
        val span = LyricWord(word = "can try", time = 16_000L, duration = 1500L)
        val pieces = WordTiming.subdivideByCharCount(span)

        assertEquals(2, pieces.size)
        assertEquals("can ", pieces[0].word)
        assertEquals("try", pieces[1].word)

        assertEquals(span.time, pieces.first().time)
        assertEquals(span.time + span.duration!!, pieces.last().endTime)
    }

    @Test
    fun `pieces are contiguous monotonic and cover the span exactly`() {
        val span = LyricWord(word = "I will always love you", time = 42_000L, duration = 4321L)
        val pieces = WordTiming.subdivideByCharCount(span)

        assertEquals(5, pieces.size)

        var cursor = span.time
        for (p in pieces) {
            assertEquals("piece does not start where the previous one ended", cursor, p.time)
            val dur = p.duration!!
            assertTrue("piece has non-positive length", dur > 0L)
            cursor = p.time + dur
        }
        // Exact coverage: no rounding slack left over, and nothing past the end.
        assertEquals(span.time + span.duration!!, cursor)
    }

    @Test
    fun `no piece escapes the measured interval`() {
        val span = LyricWord(word = "a bb ccc dddd", time = 1000L, duration = 7L)
        val pieces = WordTiming.subdivideByCharCount(span)
        val spanEnd = span.time + span.duration!!

        for (p in pieces) {
            assertTrue("piece starts before the span", p.time >= span.time)
            assertTrue("piece ends after the span", (p.endTime ?: p.time) <= spanEnd)
        }
    }

    @Test
    fun `longer words get proportionally more of the span`() {
        val span = LyricWord(word = "a bbbbbbbbb", time = 0L, duration = 1000L)
        val pieces = WordTiming.subdivideByCharCount(span)

        assertEquals(2, pieces.size)
        assertTrue(
            "the 9-character word should hold longer than the 1-character word",
            pieces[1].duration!! > pieces[0].duration!!
        )
    }

    @Test
    fun `a span with no genuine duration is never subdivided`() {
        // Unknown length means unknown length. Splitting it would have to invent
        // per-piece starts out of nothing.
        val span = LyricWord(word = "can try", time = 16_000L, duration = null)
        assertEquals(listOf(span), WordTiming.subdivideByCharCount(span))
    }

    @Test
    fun `single word and blank spans pass through untouched`() {
        val single = LyricWord(word = "blinded", time = 5L, duration = 400L)
        assertEquals(listOf(single), WordTiming.subdivideByCharCount(single))

        val trailingSpace = LyricWord(word = "Take ", time = 5L, duration = 400L)
        assertEquals(listOf(trailingSpace), WordTiming.subdivideByCharCount(trailingSpace))
    }

    @Test
    fun `subdivision preserves the line text verbatim`() {
        val line = LyricLine(
            time = 0L,
            text = "we can try again",
            words = listOf(
                LyricWord(word = "we ", time = 0L, duration = 300L),
                LyricWord(word = "can try ", time = 300L, duration = 900L),
                LyricWord(word = "again", time = 1200L, duration = 500L)
            )
        )
        val before = line.words!!.joinToString("") { it.word }
        val after = WordTiming.subdivideLines(listOf(line))[0].words!!.joinToString("") { it.word }

        assertEquals(before, after)
        assertEquals("we can try again", after.trim())
    }

    @Test
    fun `subdividing a line cannot push a word past the next word start`() {
        val line = LyricLine(
            time = 0L,
            text = "hold on tight",
            words = listOf(
                LyricWord(word = "hold on ", time = 0L, duration = 500L),
                // A real rest follows, then the next span.
                LyricWord(word = "tight", time = 3000L, duration = 400L)
            )
        )
        val words = WordTiming.subdivideLines(listOf(line))[0].words!!

        for (i in 0 until words.size - 1) {
            val end = words[i].endTime ?: continue
            assertTrue(
                "word $i ends at $end, past the next start ${words[i + 1].time}",
                end <= words[i + 1].time
            )
        }
    }
}
