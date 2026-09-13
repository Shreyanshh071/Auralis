package com.auralis.music

import com.auralis.music.domain.model.LyricLine
import com.auralis.music.domain.model.LyricWord
import com.auralis.music.ui.lyrics.findExperimentalActiveLineIndices
import com.auralis.music.ui.screens.lyrics.LyricsEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsGapHighlightingTest {

    // 1. Gaps between lyrics: Line A remains highlighted until Line B starts
    @Test
    fun testGapBetweenLyricsRemainsHighlighted() {
        val lineA = LyricLine(time = 10_000L, endTime = 13_000L, text = "Line A")
        val lineB = LyricLine(time = 14_000L, endTime = 17_000L, text = "Line B")
        val lines = listOf(lineA, lineB)

        // Before first line
        assertEquals(emptySet<Int>(), LyricsEngine.findVisualActiveLineIndices(lines, 5_000L))
        assertEquals(emptySet<Int>(), findExperimentalActiveLineIndices(lines, 5_000L))

        // During Line A
        assertEquals(setOf(0), LyricsEngine.findVisualActiveLineIndices(lines, 10_000L))
        assertEquals(setOf(0), LyricsEngine.findVisualActiveLineIndices(lines, 12_000L))
        assertEquals(setOf(0), LyricsEngine.findVisualActiveLineIndices(lines, 13_000L))
        assertEquals(setOf(0), findExperimentalActiveLineIndices(lines, 12_000L))

        // In gap between Line A (ends 13.0s) and Line B (starts 14.0s)
        // Line A MUST remain highlighted at 13.5s and 13.999s
        assertEquals(setOf(0), LyricsEngine.findVisualActiveLineIndices(lines, 13_500L))
        assertEquals(setOf(0), LyricsEngine.findVisualActiveLineIndices(lines, 13_999L))
        assertEquals(setOf(0), findExperimentalActiveLineIndices(lines, 13_500L))
        assertEquals(setOf(0), findExperimentalActiveLineIndices(lines, 13_999L))

        // At 14.0s: Line A becomes dim, Line B becomes highlighted
        assertEquals(setOf(1), LyricsEngine.findVisualActiveLineIndices(lines, 14_000L))
        assertEquals(setOf(1), findExperimentalActiveLineIndices(lines, 14_000L))

        // During Line B
        assertEquals(setOf(1), LyricsEngine.findVisualActiveLineIndices(lines, 15_500L))

        // At Line B end: Line B is active
        assertEquals(setOf(1), LyricsEngine.findVisualActiveLineIndices(lines, 17_000L))

        // After all lines have ended: active set becomes empty per design convention,
        // while findActiveLyricIndex anchors on final line (index 1) to prevent flicker.
        assertEquals(emptySet<Int>(), LyricsEngine.findVisualActiveLineIndices(lines, 20_000L))
        assertEquals(emptySet<Int>(), findExperimentalActiveLineIndices(lines, 20_000L))
        assertEquals(1, LyricsEngine.findActiveLyricIndex(lines, 20_000L))
    }

    // 2. Explicit endTime: Do not let endTime disable visual highlight before next line starts
    @Test
    fun testExplicitEndTimeDoesNotDimActiveLine() {
        val lineA = LyricLine(time = 10_000L, endTime = 12_000L, text = "Line A")
        val lineB = LyricLine(time = 15_000L, endTime = 18_000L, text = "Line B")
        val lines = listOf(lineA, lineB)

        // At 12.5s, 13s, 14.5s (after endTime = 12s, before line B = 15s)
        assertEquals(setOf(0), LyricsEngine.findVisualActiveLineIndices(lines, 12_500L))
        assertEquals(setOf(0), LyricsEngine.findVisualActiveLineIndices(lines, 13_000L))
        assertEquals(setOf(0), LyricsEngine.findVisualActiveLineIndices(lines, 14_500L))
        assertEquals(setOf(0), LyricsEngine.findVisualActiveLineIndices(lines, 14_999L))
        assertEquals(setOf(0), findExperimentalActiveLineIndices(lines, 13_000L))

        // At 15.0s: switches to Line B
        assertEquals(setOf(1), LyricsEngine.findVisualActiveLineIndices(lines, 15_000L))
        assertEquals(setOf(1), findExperimentalActiveLineIndices(lines, 15_000L))
    }

    // 3. Back-to-back lines: A ends at 13s, B starts at 13s -> instant switch
    @Test
    fun testBackToBackLinesTransitionInstantly() {
        val lineA = LyricLine(time = 10_000L, endTime = 13_000L, text = "Line A")
        val lineB = LyricLine(time = 13_000L, endTime = 16_000L, text = "Line B")
        val lines = listOf(lineA, lineB)

        assertEquals(setOf(0), LyricsEngine.findVisualActiveLineIndices(lines, 12_999L))
        assertEquals(setOf(1), LyricsEngine.findVisualActiveLineIndices(lines, 13_000L))
        assertEquals(setOf(0), findExperimentalActiveLineIndices(lines, 12_999L))
        assertEquals(setOf(1), findExperimentalActiveLineIndices(lines, 13_000L))
    }

    // 4. Seeking: Immediately resolves to latest lyric whose startTime <= position
    @Test
    fun testSeekingImmediatelyResolvesToLatestStartedLine() {
        val lines = listOf(
            LyricLine(time = 10_000L, endTime = 13_000L, text = "Line 1"),
            LyricLine(time = 14_000L, endTime = 17_000L, text = "Line 2"),
            LyricLine(time = 20_000L, endTime = 24_000L, text = "Line 3")
        )

        // Seek into gap between Line 1 and 2
        assertEquals(setOf(0), LyricsEngine.findVisualActiveLineIndices(lines, 13_500L))
        assertEquals(setOf(0), findExperimentalActiveLineIndices(lines, 13_500L))

        // Seek into gap between Line 2 and 3
        assertEquals(setOf(1), LyricsEngine.findVisualActiveLineIndices(lines, 18_500L))
        assertEquals(setOf(1), findExperimentalActiveLineIndices(lines, 18_500L))

        // Seek backwards to mid Line 1
        assertEquals(setOf(0), LyricsEngine.findVisualActiveLineIndices(lines, 11_000L))

        // Seek to before first line
        assertEquals(emptySet<Int>(), LyricsEngine.findVisualActiveLineIndices(lines, 5_000L))
        assertEquals(emptySet<Int>(), findExperimentalActiveLineIndices(lines, 5_000L))
    }

    // 5. Before first line: returns empty
    @Test
    fun testBeforeFirstLineReturnsEmpty() {
        val lines = listOf(LyricLine(time = 8_000L, endTime = 12_000L, text = "First"))
        assertEquals(emptySet<Int>(), LyricsEngine.findVisualActiveLineIndices(lines, 0L))
        assertEquals(emptySet<Int>(), LyricsEngine.findVisualActiveLineIndices(lines, 5_000L))
        assertEquals(emptySet<Int>(), LyricsEngine.findVisualActiveLineIndices(lines, 7_999L))
        assertEquals(setOf(0), LyricsEngine.findVisualActiveLineIndices(lines, 8_000L))
    }

    // 6. After final line starts: remains highlighted or follows design convention
    @Test
    fun testFinalLineRemainsHighlightedOrFollowsDesignConvention() {
        // A. Line-synced lyrics with no explicit end time: remains highlighted indefinitely
        val linesNoEndTime = listOf(
            LyricLine(time = 10_000L, text = "A"),
            LyricLine(time = 14_000L, text = "B")
        )
        assertEquals(setOf(1), LyricsEngine.findVisualActiveLineIndices(linesNoEndTime, 16_000L))
        assertEquals(setOf(1), LyricsEngine.findVisualActiveLineIndices(linesNoEndTime, 30_000L))
        assertEquals(setOf(1), LyricsEngine.findVisualActiveLineIndices(linesNoEndTime, 100_000L))
        assertEquals(setOf(1), findExperimentalActiveLineIndices(linesNoEndTime, 100_000L))

        // B. Timed lyrics with explicit end time: active during singing; after completion,
        // active set empties while findActiveLyricIndex maintains final line anchor (no flicker)
        val linesWithEndTime = listOf(
            LyricLine(time = 10_000L, endTime = 12_000L, text = "A"),
            LyricLine(time = 14_000L, endTime = 16_000L, text = "B")
        )
        assertEquals(setOf(1), LyricsEngine.findVisualActiveLineIndices(linesWithEndTime, 16_000L))
        assertEquals(emptySet<Int>(), LyricsEngine.findVisualActiveLineIndices(linesWithEndTime, 16_001L))
        assertEquals(emptySet<Int>(), LyricsEngine.findVisualActiveLineIndices(linesWithEndTime, 30_000L))
        assertEquals(1, LyricsEngine.findActiveLyricIndex(linesWithEndTime, 30_000L))
    }

    // 7. Word-synced lyrics: words finish singing, line remains highlighted
    @Test
    fun testWordSyncedLyricsLineRemainsHighlightedAfterLastWord() {
        val wordLine = LyricLine(
            time = 10_000L,
            endTime = 13_000L,
            text = "Hello world",
            words = listOf(
                LyricWord(word = "Hello ", time = 10_000L, duration = 1_000L),
                LyricWord(word = "world", time = 11_500L, duration = 1_500L) // ends at 13_000L
            )
        )
        val nextLine = LyricLine(time = 16_000L, endTime = 19_000L, text = "Next")
        val lines = listOf(wordLine, nextLine)

        // At 14.0s: both words are complete (progress = 1.0f)
        assertEquals(1.0f, LyricsEngine.calculateWordProgress(wordLine.words!![0], 14_000L), 0.001f)
        assertEquals(1.0f, LyricsEngine.calculateWordProgress(wordLine.words!![1], 14_000L), 0.001f)

        // But line MUST remain visually active until next line starts at 16_000L
        assertEquals(setOf(0), LyricsEngine.findVisualActiveLineIndices(lines, 14_000L))
        assertEquals(setOf(0), LyricsEngine.findVisualActiveLineIndices(lines, 15_999L))
        assertEquals(setOf(1), LyricsEngine.findVisualActiveLineIndices(lines, 16_000L))
        assertEquals(setOf(0), findExperimentalActiveLineIndices(lines, 14_000L))
        assertEquals(setOf(1), findExperimentalActiveLineIndices(lines, 16_000L))
    }

    // 8. Multi-active duets / background vocals: lead vocal stays active when BG ends early
    @Test
    fun testDuetLeadAndBackgroundVocals() {
        val leadA = LyricLine(
            time = 10_000L,
            endTime = 15_000L,
            text = "Lead singing",
            agent = "v1"
        )
        val bgB = LyricLine(
            time = 11_000L,
            endTime = 13_500L,
            text = "(Background)",
            agent = "v2",
            isBackground = true
        )
        val leadC = LyricLine(
            time = 18_000L,
            endTime = 22_000L,
            text = "Next lead line",
            agent = "v1"
        )
        val lines = listOf(leadA, bgB, leadC)

        // At 10.5s: only Lead A active
        assertEquals(setOf(0), LyricsEngine.findVisualActiveLineIndices(lines, 10_500L))

        // At 12.0s: BOTH active simultaneously
        assertEquals(setOf(0, 1), LyricsEngine.findVisualActiveLineIndices(lines, 12_000L))
        assertEquals(setOf(0, 1), findExperimentalActiveLineIndices(lines, 12_000L))

        // At 14.0s: BG ends at 13.5s, Lead A MUST remain active
        assertEquals(setOf(0), LyricsEngine.findVisualActiveLineIndices(lines, 14_000L))
        assertEquals(setOf(0), findExperimentalActiveLineIndices(lines, 14_000L))

        // At 16.0s (Lead A ended at 15.0s, Next Lead at 18.0s):
        // Gap between leads: Lead A MUST remain highlighted
        assertEquals(setOf(0), LyricsEngine.findVisualActiveLineIndices(lines, 16_000L))
        assertEquals(setOf(0), findExperimentalActiveLineIndices(lines, 16_000L))

        // At 18.0s: Next Lead starts
        assertEquals(setOf(2), LyricsEngine.findVisualActiveLineIndices(lines, 18_000L))
        assertEquals(setOf(2), findExperimentalActiveLineIndices(lines, 18_000L))
    }

    // 9. Musical break (>= 6s): highlight dims after 2.5s grace period instead of staying active for minutes
    @Test
    fun testExtendedInstrumentalBreakDimsHighlightAfterGracePeriod() {
        val lineA = LyricLine(time = 10_000L, endTime = 13_000L, text = "Line A")
        val lineB = LyricLine(time = 73_000L, endTime = 78_000L, text = "Line B") // 60s gap >= 6s
        val lines = listOf(lineA, lineB)

        // During Line A singing
        assertEquals(setOf(0), LyricsEngine.findVisualActiveLineIndices(lines, 11_000L))

        // During 2.5s grace period after Line A ends (at 13s)
        assertEquals(setOf(0), LyricsEngine.findVisualActiveLineIndices(lines, 14_500L))
        assertEquals(setOf(0), LyricsEngine.findVisualActiveLineIndices(lines, 15_500L)) // 13s + 2.5s = 15.5s

        // After grace period during musical break (>= 6s), line dims
        assertEquals(emptySet<Int>(), LyricsEngine.findVisualActiveLineIndices(lines, 15_600L))
        assertEquals(emptySet<Int>(), LyricsEngine.findVisualActiveLineIndices(lines, 40_000L))
        assertEquals(emptySet<Int>(), LyricsEngine.findVisualActiveLineIndices(lines, 72_000L))
        assertEquals(emptySet<Int>(), findExperimentalActiveLineIndices(lines, 40_000L))

        // When next line begins at 73s, it becomes active
        assertEquals(setOf(1), LyricsEngine.findVisualActiveLineIndices(lines, 73_000L))
        assertEquals(setOf(1), findExperimentalActiveLineIndices(lines, 73_000L))
    }

    // 10. Medium musical break (8s gap): dims after 2.5s grace period and activates next line at start
    @Test
    fun testMediumMusicalBreakDimsAfterGracePeriod() {
        val lineA = LyricLine(time = 10_000L, endTime = 14_000L, text = "Line A")
        val lineB = LyricLine(time = 22_000L, endTime = 26_000L, text = "Line B") // 8s gap >= 6s
        val lines = listOf(lineA, lineB)

        // While Line A is singing
        assertEquals(setOf(0), LyricsEngine.findVisualActiveLineIndices(lines, 12_000L))
        // During 2.5s grace period (14s to 16.5s)
        assertEquals(setOf(0), LyricsEngine.findVisualActiveLineIndices(lines, 16_000L))
        // After grace period, before next line
        assertEquals(emptySet<Int>(), LyricsEngine.findVisualActiveLineIndices(lines, 17_000L))
        assertEquals(emptySet<Int>(), LyricsEngine.findVisualActiveLineIndices(lines, 21_500L))
        // At 22s: Line B begins
        assertEquals(setOf(1), LyricsEngine.findVisualActiveLineIndices(lines, 22_000L))
    }
}
