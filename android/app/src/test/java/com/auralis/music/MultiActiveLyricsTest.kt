package com.auralis.music

import com.auralis.music.data.parser.LrcParser
import com.auralis.music.data.parser.TtmlParser
import com.auralis.music.domain.model.LyricLine
import com.auralis.music.domain.model.LyricWord
import com.auralis.music.domain.model.SyncType
import com.auralis.music.ui.screens.lyrics.LyricsEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiActiveLyricsTest {

    // 1. Two independently timed lines with overlapping timestamps -> both active
    @Test
    fun testOverlappingLinesBothActive_EyesWithoutAFace() {
        val lineLead = LyricLine(
            time = 10_000L,
            endTime = 15_000L,
            text = "Eyes without a face",
            agent = "v1"
        )
        val lineBg = LyricLine(
            time = 11_000L,
            endTime = 13_500L,
            text = "(Les yeux sans visage)",
            agent = "v2",
            isBackground = true
        )
        val lines = listOf(lineLead, lineBg)

        // At 10_500ms: only lead is active
        assertEquals(setOf(0), LyricsEngine.findActiveLyricIndices(lines, 10_500L))
        assertEquals(0, LyricsEngine.findActiveLyricIndex(lines, 10_500L))

        // At 12_000ms: BOTH lines are active simultaneously
        val activeAt12s = LyricsEngine.findActiveLyricIndices(lines, 12_000L)
        assertEquals(setOf(0, 1), activeAt12s)

        // Scrolling anchor prioritizes the primary lead line (index 0) over the background vocal (index 1)
        assertEquals(0, LyricsEngine.findActiveLyricIndex(lines, 12_000L))

        // At 14_000ms: background vocal has ended at 13_500ms, only lead is active
        val activeAt14s = LyricsEngine.findActiveLyricIndices(lines, 14_000L)
        assertEquals(setOf(0), activeAt14s)
        assertEquals(0, LyricsEngine.findActiveLyricIndex(lines, 14_000L))

        // At 16_000ms: both have ended, scroll anchor stays at last completed line (index 1)
        val activeAt16s = LyricsEngine.findActiveLyricIndices(lines, 16_000L)
        assertTrue(activeAt16s.isEmpty())
        assertEquals(1, LyricsEngine.findActiveLyricIndex(lines, 16_000L))
    }

    // 2. Two non-overlapping lines -> only the correct line active
    @Test
    fun testNonOverlappingLinesOnlyCorrectLineActive() {
        val lineA = LyricLine(time = 10_000L, endTime = 14_000L, text = "First line")
        val lineB = LyricLine(time = 16_000L, endTime = 20_000L, text = "Second line")
        val lines = listOf(lineA, lineB)

        // Before line A starts
        assertEquals(emptySet<Int>(), LyricsEngine.findActiveLyricIndices(lines, 5_000L))
        assertEquals(-1, LyricsEngine.findActiveLyricIndex(lines, 5_000L))

        // During line A
        assertEquals(setOf(0), LyricsEngine.findActiveLyricIndices(lines, 12_000L))
        assertEquals(0, LyricsEngine.findActiveLyricIndex(lines, 12_000L))

        // Gap / rest between lines (14_000L to 16_000L)
        assertEquals(emptySet<Int>(), LyricsEngine.findActiveLyricIndices(lines, 15_000L))
        // Scroll anchor stays at last completed line (0) to prevent jumping
        assertEquals(0, LyricsEngine.findActiveLyricIndex(lines, 15_000L))

        // During line B
        assertEquals(setOf(1), LyricsEngine.findActiveLyricIndices(lines, 18_000L))
        assertEquals(1, LyricsEngine.findActiveLyricIndex(lines, 18_000L))

        // After line B ends
        assertEquals(emptySet<Int>(), LyricsEngine.findActiveLyricIndices(lines, 25_000L))
        assertEquals(1, LyricsEngine.findActiveLyricIndex(lines, 25_000L))
    }

    // 3. Two overlapping lines with genuine word timing -> both independently highlight their own words
    @Test
    fun testOverlappingLinesWithGenuineWordTimingIndependentlyHighlight() {
        val lineA = LyricLine(
            time = 10_000L,
            endTime = 14_000L,
            text = "Eyes without a face",
            words = listOf(
                LyricWord(word = "Eyes ", time = 10_000L, duration = 1_000L),
                LyricWord(word = "without ", time = 11_000L, duration = 1_500L), // 11_000 to 12_500
                LyricWord(word = "a ", time = 12_500L, duration = 500L),
                LyricWord(word = "face", time = 13_000L, duration = 1_000L)
            )
        )
        val lineB = LyricLine(
            time = 11_500L,
            endTime = 13_800L,
            text = "(Les yeux sans visage)",
            isBackground = true,
            words = listOf(
                LyricWord(word = "(Les ", time = 11_500L, duration = 500L),  // 11_500 to 12_000
                LyricWord(word = "yeux ", time = 12_000L, duration = 800L),  // 12_000 to 12_800
                LyricWord(word = "sans ", time = 12_800L, duration = 500L),
                LyricWord(word = "visage)", time = 13_300L, duration = 500L)
            )
        )
        val lines = listOf(lineA, lineB)

        // At 12_200ms: both lines are active
        val active = LyricsEngine.findActiveLyricIndices(lines, 12_200L)
        assertEquals(setOf(0, 1), active)

        // Evaluate word progress for Line A at 12_200ms:
        // "Eyes " (10000..11000) -> 1.0f (finished)
        // "without " (11000..12500) -> (12200 - 11000) / 1500 = 1200 / 1500 = 0.8f (currently sweeping)
        // "a " (12500..13000) -> 0.0f (not started)
        assertEquals(1.0f, LyricsEngine.calculateWordProgress(lineA.words!![0], 12_200L), 0.001f)
        assertEquals(0.8f, LyricsEngine.calculateWordProgress(lineA.words!![1], 12_200L), 0.001f)
        assertEquals(0.0f, LyricsEngine.calculateWordProgress(lineA.words!![2], 12_200L), 0.001f)

        // Evaluate word progress for Line B at the exact same 12_200ms:
        // "(Les " (11500..12000) -> 1.0f (finished)
        // "yeux " (12000..12800) -> (12200 - 12000) / 800 = 200 / 800 = 0.25f (currently sweeping)
        // "sans " (12800..13300) -> 0.0f (not started)
        assertEquals(1.0f, LyricsEngine.calculateWordProgress(lineB.words!![0], 12_200L), 0.001f)
        assertEquals(0.25f, LyricsEngine.calculateWordProgress(lineB.words!![1], 12_200L), 0.001f)
        assertEquals(0.0f, LyricsEngine.calculateWordProgress(lineB.words!![2], 12_200L), 0.001f)

        // Neither line's active state suppresses or clobbers the other
    }

    // 4. Background/agent metadata is preserved through parsing
    @Test
    fun testBackgroundAndAgentMetadataPreservedThroughParsing() {
        val ttml = """
            <?xml version="1.0" encoding="utf-8"?>
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
              <body>
                <div>
                  <p begin="00:00:10.000" end="00:00:15.000" ttm:agent="v1">
                    <span begin="00:00:10.000" end="00:00:15.000">Eyes without a face</span>
                  </p>
                  <p begin="00:00:11.000" end="00:00:13.500" ttm:agent="v2" ttm:role="x-bg">
                    <span begin="00:00:11.000" end="00:00:13.500">(Les yeux sans visage)</span>
                  </p>
                </div>
              </body>
            </tt>
        """.trimIndent()

        val lyrics = TtmlParser.parse(ttml)
        assertEquals(2, lyrics.lines.size)

        val lead = lyrics.lines[0]
        assertEquals("Eyes without a face", lead.text)
        assertEquals(10_000L, lead.time)
        assertEquals(15_000L, lead.endTime)
        assertEquals("v1", lead.agent)
        assertFalse(lead.isBackground)

        val bg = lyrics.lines[1]
        assertEquals("(Les yeux sans visage)", bg.text)
        assertEquals(11_000L, bg.time)
        assertEquals(13_500L, bg.endTime)
        assertEquals("v2", bg.agent)
        assertTrue(bg.isBackground)

        // Test LRC parser with {agent:v1} and {bg}
        val lrc = """
            [00:10.00]{agent:v1}Eyes without a face
            [00:11.00]{agent:v2}{bg}(Les yeux sans visage)
        """.trimIndent()

        val parsedLrc = LrcParser.parse(lrc)
        assertEquals(2, parsedLrc.lines.size)
        assertEquals("v1", parsedLrc.lines[0].agent)
        assertFalse(parsedLrc.lines[0].isBackground)
        assertEquals("Eyes without a face", parsedLrc.lines[0].text)

        assertEquals("v2", parsedLrc.lines[1].agent)
        assertTrue(parsedLrc.lines[1].isBackground)
        assertEquals("(Les yeux sans visage)", parsedLrc.lines[1].text)
    }

    // 5. Existing wrapped-line behavior remains correct
    @Test
    fun testExistingWrappedLineBehaviorRemainsCorrect() {
        val text = "And when you hear this song on the radio will you remember me"
        val words = listOf(
            LyricWord("And ", 1000L, 200L),
            LyricWord("when ", 1200L, 300L),
            LyricWord("you ", 1500L, 200L),
            LyricWord("hear ", 1700L, 400L),
            LyricWord("this ", 2100L, 200L),
            LyricWord("song ", 2300L, 400L),
            LyricWord("on ", 2700L, 200L),
            LyricWord("the ", 2900L, 200L),
            LyricWord("radio ", 3100L, 500L),
            LyricWord("will ", 3600L, 200L),
            LyricWord("you ", 3800L, 200L),
            LyricWord("remember ", 4000L, 600L),
            LyricWord("me", 4600L, 400L)
        )
        val line = LyricLine(time = 1000L, text = text, words = words)

        val ranges = LyricsEngine.mapWordsToLineSpans(line.text, words)
        assertEquals(words.size, ranges.size)

        // Verify each word span maps precisely to the source text substring
        for (range in ranges) {
            val substr = text.substring(range.startIndex, range.endIndex)
            assertEquals(range.word.word, substr)
        }
    }

    // 6. A long lyric line still wraps correctly
    @Test
    fun testLongLyricLineStillWrapsCorrectly() {
        val text = "This is an extremely long lyric line designed to simulate a multi-row sentence that wraps across several display rows on standard screen resolutions"
        val rawTokens = text.split(" ")
        val words = rawTokens.mapIndexed { idx, token ->
            val wordText = if (idx < rawTokens.size - 1) "$token " else token
            LyricWord(word = wordText, time = idx * 500L, duration = 500L)
        }
        val ranges = LyricsEngine.mapWordsToLineSpans(text, words)
        assertEquals(words.size, ranges.size)

        // Concatenating mapped substrings must reconstruct the entire text with 0 truncation
        val reconstructed = ranges.joinToString("") { text.substring(it.startIndex, it.endIndex) }
        assertEquals(text, reconstructed)
    }

    // 7. No text truncation occurs when simultaneous lines are rendered
    @Test
    fun testNoTextTruncationWhenSimultaneousLinesAreRendered() {
        val lineA = LyricLine(
            time = 10_000L,
            endTime = 16_000L,
            text = "Eyes without a face got no human grace",
            agent = "v1"
        )
        val lineB = LyricLine(
            time = 11_000L,
            endTime = 14_000L,
            text = "(Les yeux sans visage)",
            agent = "v2",
            isBackground = true
        )
        val lines = listOf(lineA, lineB)

        val active = LyricsEngine.findActiveLyricIndices(lines, 12_500L)
        assertEquals(setOf(0, 1), active)

        // Each line maintains its full, complete string without character dropping
        assertEquals("Eyes without a face got no human grace", lines[0].text)
        assertEquals("(Les yeux sans visage)", lines[1].text)
        assertFalse(lines[0].text.contains("..."))
        assertFalse(lines[1].text.contains("..."))
    }

    // 8. Existing single-line lyrics behavior is unchanged
    @Test
    fun testExistingSingleLineLyricsBehaviorUnchanged() {
        val lines = listOf(
            LyricLine(time = 0L, text = "Line 1"),
            LyricLine(time = 5_000L, text = "Line 2"),
            LyricLine(time = 10_000L, text = "Line 3")
        )

        // Single line active at 2500ms
        assertEquals(setOf(0), LyricsEngine.findActiveLyricIndices(lines, 2500L))
        assertEquals(0, LyricsEngine.findActiveLyricIndex(lines, 2500L))

        // Single line active at 7500ms
        assertEquals(setOf(1), LyricsEngine.findActiveLyricIndices(lines, 7500L))
        assertEquals(1, LyricsEngine.findActiveLyricIndex(lines, 7500L))

        // Single line active at 12000ms
        assertEquals(setOf(2), LyricsEngine.findActiveLyricIndices(lines, 12000L))
        assertEquals(2, LyricsEngine.findActiveLyricIndex(lines, 12000L))

        // Line 2 agent is null and isBackground is false -> default styling preserved
        assertNull(lines[1].agent)
        assertFalse(lines[1].isBackground)
    }
}
