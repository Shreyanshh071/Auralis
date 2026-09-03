package com.auralis.music

import com.auralis.music.domain.model.LyricLine
import com.auralis.music.domain.model.LyricWord
import com.auralis.music.ui.lyrics.carriedPositionMs
import com.auralis.music.ui.screens.lyrics.LyricsEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Comprehensive test suite for Phase 3 on-screen word-by-word karaoke timing and mapping engine.
 */
class LyricsPhase3Test {

    // ── 1. WE ARE THE PEOPLE VOCAL REST TEST (Requirement 2) ──────────────────
    // "We are the people in 1975"
    // Pause between "people " and "in " must remain visibly unhighlighted.
    private val weAreThePeopleLine = LyricLine(
        time = 1000L,
        text = "We are the people in 1975",
        words = listOf(
            LyricWord(word = "We ", time = 1000L, duration = 400L),       // [1000, 1400]
            LyricWord(word = "are ", time = 1400L, duration = 300L),      // [1400, 1700]
            LyricWord(word = "the ", time = 1700L, duration = 300L),      // [1700, 2000]
            LyricWord(word = "people ", time = 2000L, duration = 600L),   // [2000, 2600]
            // Genuine vocal rest from 2600ms to 4500ms (1900ms silence)
            LyricWord(word = "in ", time = 4500L, duration = 300L),       // [4500, 4800]
            LyricWord(word = "1975", time = 4800L, duration = 1000L)      // [4800, 5800]
        )
    )

    @Test
    fun `we are the people vocal rest remains strictly unhighlighted during gap`() {
        val words = weAreThePeopleLine.words!!

        // At 2500ms (inside "people "):
        assertEquals(1.0f, LyricsEngine.calculateWordProgress(words[0], 2500L), 0.001f) // We
        assertEquals(1.0f, LyricsEngine.calculateWordProgress(words[1], 2500L), 0.001f) // are
        assertEquals(1.0f, LyricsEngine.calculateWordProgress(words[2], 2500L), 0.001f) // the
        val peopleProgress = LyricsEngine.calculateWordProgress(words[3], 2500L)        // people (500/600)
        assertEquals(500f / 600f, peopleProgress, 0.001f)
        assertEquals(0.0f, LyricsEngine.calculateWordProgress(words[4], 2500L), 0.001f) // in
        assertEquals(0.0f, LyricsEngine.calculateWordProgress(words[5], 2500L), 0.001f) // 1975

        // Sample every 50ms across the entire 1900ms silence [2600ms .. 4499ms]:
        // "We are the people " must stay 1.0f, and "in 1975" must stay 0.0f.
        for (t in 2600L until 4500L step 50L) {
            assertEquals("word 0 (We) at t=$t", 1.0f, LyricsEngine.calculateWordProgress(words[0], t), 0.001f)
            assertEquals("word 1 (are) at t=$t", 1.0f, LyricsEngine.calculateWordProgress(words[1], t), 0.001f)
            assertEquals("word 2 (the) at t=$t", 1.0f, LyricsEngine.calculateWordProgress(words[2], t), 0.001f)
            assertEquals("word 3 (people) at t=$t", 1.0f, LyricsEngine.calculateWordProgress(words[3], t), 0.001f)
            assertEquals("word 4 (in) at t=$t must not start during rest", 0.0f, LyricsEngine.calculateWordProgress(words[4], t), 0.001f)
            assertEquals("word 5 (1975) at t=$t must not start during rest", 0.0f, LyricsEngine.calculateWordProgress(words[5], t), 0.001f)
        }

        // At 4500ms, "in " begins
        assertEquals(0.0f, LyricsEngine.calculateWordProgress(words[4], 4500L), 0.001f)
        assertEquals(0.5f, LyricsEngine.calculateWordProgress(words[4], 4650L), 0.001f)
        assertEquals(1.0f, LyricsEngine.calculateWordProgress(words[4], 4800L), 0.001f)
    }

    // ── 2. WORD PROGRESS & DURATION == NULL STEP BEHAVIOR (Requirement 1) ─────

    @Test
    fun `null duration tokens step instantaneously without sweeping`() {
        val stepWord = LyricWord(word = "instant", time = 5000L, duration = null)

        // Before start -> 0.0f
        assertEquals(0.0f, LyricsEngine.calculateWordProgress(stepWord, 4999L), 0.001f)
        // Exactly at start -> 1.0f
        assertEquals(1.0f, LyricsEngine.calculateWordProgress(stepWord, 5000L), 0.001f)
        // After start -> 1.0f
        assertEquals(1.0f, LyricsEngine.calculateWordProgress(stepWord, 5001L), 0.001f)
        assertEquals(1.0f, LyricsEngine.calculateWordProgress(stepWord, 6000L), 0.001f)
    }

    @Test
    fun `zero or negative duration tokens step instantaneously`() {
        val zeroWord = LyricWord(word = "zero", time = 3000L, duration = 0L)
        assertEquals(0.0f, LyricsEngine.calculateWordProgress(zeroWord, 2999L), 0.001f)
        assertEquals(1.0f, LyricsEngine.calculateWordProgress(zeroWord, 3000L), 0.001f)
    }

    // ── 3. CHARACTER MAPPING & TEXT SPACING (Requirements 4 & 5) ─────────────

    @Test
    fun `mapWordsToLineSpans maps standard words and spaces accurately`() {
        val lineText = "We are the people in 1975"
        val ranges = LyricsEngine.mapWordsToLineSpans(lineText, weAreThePeopleLine.words)

        assertEquals(6, ranges.size)
        // "We " -> [0, 3)
        assertEquals("We ", lineText.substring(ranges[0].startIndex, ranges[0].endIndex))
        assertEquals(0, ranges[0].startIndex)
        assertEquals(3, ranges[0].endIndex)

        // "are " -> [3, 7)
        assertEquals("are ", lineText.substring(ranges[1].startIndex, ranges[1].endIndex))
        assertEquals(3, ranges[1].startIndex)
        assertEquals(7, ranges[1].endIndex)

        // "the " -> [7, 11)
        assertEquals("the ", lineText.substring(ranges[2].startIndex, ranges[2].endIndex))
        assertEquals(7, ranges[2].startIndex)
        assertEquals(11, ranges[2].endIndex)

        // "people " -> [11, 18)
        assertEquals("people ", lineText.substring(ranges[3].startIndex, ranges[3].endIndex))
        assertEquals(11, ranges[3].startIndex)
        assertEquals(18, ranges[3].endIndex)

        // "in " -> [18, 21)
        assertEquals("in ", lineText.substring(ranges[4].startIndex, ranges[4].endIndex))
        assertEquals(18, ranges[4].startIndex)
        assertEquals(21, ranges[4].endIndex)

        // "1975" -> [21, 25)
        assertEquals("1975", lineText.substring(ranges[5].startIndex, ranges[5].endIndex))
        assertEquals(21, ranges[5].startIndex)
        assertEquals(25, ranges[5].endIndex)
    }

    @Test
    fun `mapWordsToLineSpans preserves syllable joining without artificial spaces`() {
        val lineText = "before sunrise"
        val words = listOf(
            LyricWord(word = "be", time = 1000L, duration = 200L),
            LyricWord(word = "fore ", time = 1200L, duration = 400L),
            LyricWord(word = "sunrise", time = 1600L, duration = 600L)
        )

        val ranges = LyricsEngine.mapWordsToLineSpans(lineText, words)
        assertEquals(3, ranges.size)

        assertEquals("be", lineText.substring(ranges[0].startIndex, ranges[0].endIndex))
        assertEquals(0, ranges[0].startIndex)
        assertEquals(2, ranges[0].endIndex)

        assertEquals("fore ", lineText.substring(ranges[1].startIndex, ranges[1].endIndex))
        assertEquals(2, ranges[1].startIndex)
        assertEquals(7, ranges[1].endIndex)

        assertEquals("sunrise", lineText.substring(ranges[2].startIndex, ranges[2].endIndex))
        assertEquals(7, ranges[2].startIndex)
        assertEquals(14, ranges[2].endIndex)
    }

    @Test
    fun `mapWordsToLineSpans handles trailing space trimmed on last word`() {
        val lineText = "I will stay"
        val words = listOf(
            LyricWord(word = "I ", time = 0L, duration = 500L),
            LyricWord(word = "will ", time = 500L, duration = 500L),
            LyricWord(word = "stay ", time = 1000L, duration = 800L) // Source has trailing space, line does not
        )

        val ranges = LyricsEngine.mapWordsToLineSpans(lineText, words)
        assertEquals(3, ranges.size)
        assertEquals("stay", lineText.substring(ranges[2].startIndex, ranges[2].endIndex))
        assertEquals(7, ranges[2].startIndex)
        assertEquals(11, ranges[2].endIndex)
    }

    @Test
    fun `mapWordsToLineSpans preserves complex Devanagari script grapheme clusters`() {
        val lineText = "रन्झाना हुआ मैं तेरा"
        val words = listOf(
            LyricWord(word = "रन्झाना ", time = 1000L, duration = 800L),
            LyricWord(word = "हुआ ", time = 1800L, duration = 400L),
            LyricWord(word = "मैं ", time = 2200L, duration = 400L),
            LyricWord(word = "तेरा", time = 2600L, duration = 600L)
        )

        val ranges = LyricsEngine.mapWordsToLineSpans(lineText, words)
        assertEquals(4, ranges.size)
        assertEquals("रन्झाना ", lineText.substring(ranges[0].startIndex, ranges[0].endIndex))
        assertEquals("हुआ ", lineText.substring(ranges[1].startIndex, ranges[1].endIndex))
        assertEquals("मैं ", lineText.substring(ranges[2].startIndex, ranges[2].endIndex))
        assertEquals("तेरा", lineText.substring(ranges[3].startIndex, ranges[3].endIndex))
    }

    // ── 4. LINE-SYNC FALLBACK & BACKGROUND VOCALS (Requirements 6 & 7) ────────

    @Test
    fun `line-sync fallback is preserved when words list is null or empty`() {
        val lineNoWords = LyricLine(time = 10000L, text = "Plain line sync text", words = null)
        assertFalse(lineNoWords.hasWordTiming)
        val rangesNull = LyricsEngine.mapWordsToLineSpans(lineNoWords.text, lineNoWords.words)
        assertTrue(rangesNull.isEmpty())

        val lineEmptyWords = LyricLine(time = 10000L, text = "Empty words list", words = emptyList())
        assertFalse(lineEmptyWords.hasWordTiming)
        val rangesEmpty = LyricsEngine.mapWordsToLineSpans(lineEmptyWords.text, lineEmptyWords.words)
        assertTrue(rangesEmpty.isEmpty())
    }

    @Test
    fun `background vocals preserve isBackground flag and word timing`() {
        val bgLine = LyricLine(
            time = 15000L,
            text = "(Yeah, yeah)",
            words = listOf(
                LyricWord(word = "(Yeah, ", time = 15000L, duration = 500L, isBackground = true),
                LyricWord(word = "yeah)", time = 15500L, duration = 500L, isBackground = true)
            ),
            isBackground = true
        )

        assertTrue(bgLine.isBackground)
        assertTrue(bgLine.hasWordTiming)
        assertEquals(true, bgLine.words!![0].isBackground)
        assertEquals(true, bgLine.words!![1].isBackground)

        val ranges = LyricsEngine.mapWordsToLineSpans(bgLine.text, bgLine.words)
        assertEquals(2, ranges.size)
        assertTrue(ranges[0].word.isBackground)
        assertEquals("(Yeah, ", bgLine.text.substring(ranges[0].startIndex, ranges[0].endIndex))
        assertEquals("yeah)", bgLine.text.substring(ranges[1].startIndex, ranges[1].endIndex))
    }

    // ── 5. CLOCK SEEK & PLAYBACK SPEED (Requirements 9 & 10) ──────────────────

    @Test
    fun `clock seek snaps immediately to new raw reading without catch-up lag`() {
        // At t = 1000, anchor is set at 1000
        val posBeforeSeek = carriedPositionMs(
            rawMs = 1000L,
            anchorRawMs = 1000L,
            anchorWallMs = 5000L,
            nowWallMs = 5050L,
            speed = 1.0f,
            isPlaying = true
        )
        assertEquals(1050L, posBeforeSeek)

        // Seek occurs: engine jumps to 40000L
        val posAfterSeek = carriedPositionMs(
            rawMs = 40000L,
            anchorRawMs = 1000L, // Old anchor
            anchorWallMs = 5000L,
            nowWallMs = 5060L,
            speed = 1.0f,
            isPlaying = true
        )
        // Must snap immediately to rawMs (40000L)
        assertEquals(40000L, posAfterSeek)
    }

    @Test
    fun `clock scales carry by playback speed accurately`() {
        // At 0.5x speed, 100ms wall time should advance position by 50ms
        val posHalfSpeed = carriedPositionMs(
            rawMs = 10000L,
            anchorRawMs = 10000L,
            anchorWallMs = 1000L,
            nowWallMs = 1100L,
            speed = 0.5f,
            isPlaying = true
        )
        assertEquals(10050L, posHalfSpeed)

        // At 2.0x speed, 50ms wall time should advance position by 100ms
        val posDoubleSpeed = carriedPositionMs(
            rawMs = 10000L,
            anchorRawMs = 10000L,
            anchorWallMs = 1000L,
            nowWallMs = 1050L,
            speed = 2.0f,
            isPlaying = true
        )
        assertEquals(10100L, posDoubleSpeed)
    }

    @Test
    fun `clock clamps carry so buffering stall cannot drift ahead`() {
        // Engine stalled (rawMs didn't advance for 500ms)
        val posStalled = carriedPositionMs(
            rawMs = 10000L,
            anchorRawMs = 10000L,
            anchorWallMs = 1000L,
            nowWallMs = 1500L, // 500ms later
            speed = 1.0f,
            isPlaying = true
        )
        // Carry is clamped to MAX_CLOCK_CARRY_MS (100ms)
        assertEquals(10100L, posStalled)
    }

    @Test
    fun `clock freezes mid-word when paused`() {
        val posPaused = carriedPositionMs(
            rawMs = 10250L,
            anchorRawMs = 10250L,
            anchorWallMs = 1000L,
            nowWallMs = 2000L, // 1000ms later while paused
            speed = 1.0f,
            isPlaying = false
        )
        assertEquals("must freeze exactly at rawMs when paused", 10250L, posPaused)
    }
}
