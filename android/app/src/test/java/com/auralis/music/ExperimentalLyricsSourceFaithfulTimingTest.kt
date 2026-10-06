package com.auralis.music

import com.auralis.music.domain.model.AppearanceSettings
import com.auralis.music.domain.model.LyricLine
import com.auralis.music.domain.model.LyricWord
import com.auralis.music.domain.model.LyricsAnimationMode
import com.auralis.music.domain.model.SyncType
import com.auralis.music.ui.lyrics.experimentalWordProgress
import com.auralis.music.ui.lyrics.ExperimentalWordTimestamp
import com.auralis.music.ui.lyrics.mapShapedTimedRanges
import com.auralis.music.ui.lyrics.presentationWordTimestamps
import com.auralis.music.ui.lyrics.resolveExperimentalWordTimestamps
import com.auralis.music.ui.lyrics.shapedFragmentSweep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExperimentalLyricsSourceFaithfulTimingTest {

    @Test
    fun `instrumental rest folded into watching does not delay you at two fifty two`() {
        // NetEase RichSync for The Police, Every Breath You Take at the reported point.
        val source = listOf(
            ExperimentalWordTimestamp("I'll ", 170.75, 171.05),
            ExperimentalWordTimestamp("be ", 171.05, 171.23),
            ExperimentalWordTimestamp("watching ", 171.23, 184.07),
            ExperimentalWordTimestamp("you", 184.07, 184.61)
        )

        val shown = presentationWordTimestamps(source)

        assertEquals(171.83, shown[2].endTime!!, 0.001)
        // Subsequent words must remain anchored to audio start time so they don't rush ahead of the singer
        assertEquals(184.07, shown[3].startTime, 0.001)
        assertEquals(184.61, shown[3].endTime!!, 0.001)
        assertEquals(0f, experimentalWordProgress(shown[3], 172_000L), 0.0f)
        assertTrue(experimentalWordProgress(shown[3], 184_200L) > 0f)
        assertEquals(184.07, source[3].startTime, 0.0)
    }

    @Test
    fun `ordinary extended word keeps its source timing`() {
        // NetEase RichSync for The Police, Every Breath You Take (first chorus).
        val source = listOf(
            ExperimentalWordTimestamp("I'll ", 27.65, 27.92),
            ExperimentalWordTimestamp("be ", 27.92, 28.10),
            ExperimentalWordTimestamp("watching ", 28.10, 29.06),
            ExperimentalWordTimestamp("you", 29.06, 29.78)
        )

        assertEquals(source, presentationWordTimestamps(source))
    }

    @Test
    fun `ordinary word timing and a sustained final word stay source accurate`() {
        val source = listOf(
            ExperimentalWordTimestamp("I ", 10.0, 10.2),
            ExperimentalWordTimestamp("will ", 10.3, 10.65),
            ExperimentalWordTimestamp("watch ", 10.8, 11.4),
            ExperimentalWordTimestamp("you", 11.5, 13.5)
        )

        assertEquals(source, presentationWordTimestamps(source))
    }

    @Test
    fun `three word lines can also finish an isolated slow sweep`() {
        val source = listOf(
            ExperimentalWordTimestamp("be ", 1.0, 1.2),
            ExperimentalWordTimestamp("watching ", 1.2, 6.2),
            ExperimentalWordTimestamp("you", 6.2, 6.5)
        )

        val shown = presentationWordTimestamps(source)
        assertEquals(1.8, shown[1].endTime!!, 0.001)
        assertEquals(6.2, shown[2].startTime, 0.001)
    }

    @Test
    fun `unknown word end remains a source timed step`() {
        val source = listOf(
            ExperimentalWordTimestamp("I'll ", 1.0, 1.2),
            ExperimentalWordTimestamp("be ", 1.3, 1.5),
            ExperimentalWordTimestamp("watching ", 1.6, null),
            ExperimentalWordTimestamp("you", 2.0, 2.3)
        )

        assertEquals(source, presentationWordTimestamps(source))
    }

    @Test
    fun `final timed fragment completes only its visible Hindi word`() {
        val text = "भाग भाग मिल्खा"
        val words = listOf(
            com.auralis.music.ui.lyrics.ExperimentalWordTimestamp("भाग", 1.0, 1.2),
            com.auralis.music.ui.lyrics.ExperimentalWordTimestamp("भाग", 1.3, 1.5),
            com.auralis.music.ui.lyrics.ExperimentalWordTimestamp("मिल्", 1.6, 1.8),
            com.auralis.music.ui.lyrics.ExperimentalWordTimestamp("खा", 1.8, 2.0)
        )

        val ranges = mapShapedTimedRanges(text, words)
        assertEquals("भाग", text.substring(ranges[0]!!.completedStart, ranges[0]!!.completedEnd))
        assertEquals("मिल्", text.substring(ranges[2]!!.completedStart, ranges[2]!!.completedEnd))
        assertEquals("मिल्खा", text.substring(ranges[3]!!.completedStart, ranges[3]!!.completedEnd))
    }

    @Test
    fun `shaped Hindi fragments sweep across the word rather than fading it all at once`() {
        val ranges = mapShapedTimedRanges(
            "मिल्खा",
            listOf(
                com.auralis.music.ui.lyrics.ExperimentalWordTimestamp("मिल्", 1.0, 1.5),
                com.auralis.music.ui.lyrics.ExperimentalWordTimestamp("खा", 1.5, 2.0)
            )
        )
        val first = ranges[0]!!
        val last = ranges[1]!!

        assertEquals(0f, shapedFragmentSweep(first, 0.5f, false).first, 0.001f)
        assertTrue(shapedFragmentSweep(first, 0.5f, false).second in 0f..0.5f)
        assertEquals(shapedFragmentSweep(first, 1f, true).second,
            shapedFragmentSweep(last, 0f, false).first, 0.001f)
        assertEquals(1f, shapedFragmentSweep(last, 1f, true).second, 0.001f)
    }

    @Test
    fun `one shaped word has a left-to-right partial sweep`() {
        val range = mapShapedTimedRanges(
            "बबुआन",
            listOf(com.auralis.music.ui.lyrics.ExperimentalWordTimestamp("बबुआन", 1.0, 2.0))
        ).single()!!

        assertEquals(0.5f, shapedFragmentSweep(range, 0.5f, false).second, 0.001f)
        assertEquals(1f, shapedFragmentSweep(range, 1f, true).second, 0.001f)
    }

    @Test
    fun `known source start and end are preserved exactly`() {
        val line = LyricLine(
            time = 10_000L,
            text = "Hold",
            words = listOf(LyricWord("Hold", 10_000L, 425L))
        )

        val word = resolveExperimentalWordTimestamps(line, SyncType.RICHSYNC)!!.single()

        assertEquals(10.0, word.startTime, 0.0)
        assertEquals(10.425, word.endTime!!, 0.0)
        assertEquals(0.5f, experimentalWordProgress(word, 10_212L), 0.002f)
    }

    @Test
    fun `twenty and forty millisecond source intervals are not stretched`() {
        val line = LyricLine(
            time = 1_000L,
            text = "Go now",
            words = listOf(
                LyricWord("Go ", 1_000L, 20L),
                LyricWord("now", 1_040L, 40L)
            )
        )

        val words = resolveExperimentalWordTimestamps(line, SyncType.RICHSYNC)!!

        assertEquals(1.020, words[0].endTime!!, 0.0)
        assertEquals(1.080, words[1].endTime!!, 0.0)
        assertEquals(0.5f, experimentalWordProgress(words[0], 1_010L), 0.001f)
        assertEquals(1.0f, experimentalWordProgress(words[0], 1_020L), 0.001f)
        assertEquals(0.5f, experimentalWordProgress(words[1], 1_060L), 0.001f)
    }

    @Test
    fun `known start with unknown end steps without a synthetic sweep`() {
        val line = LyricLine(
            time = 12_500L,
            text = "said",
            words = listOf(LyricWord("said", 12_500L, null))
        )

        val word = resolveExperimentalWordTimestamps(line, SyncType.RICHSYNC)!!.single()

        assertNull(word.endTime)
        assertEquals(0f, experimentalWordProgress(word, 12_499L), 0f)
        assertEquals(1f, experimentalWordProgress(word, 12_500L), 0f)
        assertEquals(1f, experimentalWordProgress(word, 12_800L), 0f)
    }

    @Test
    fun `line-only lyrics do not generate word timestamps`() {
        val plainLine = LyricLine(time = 20_000L, text = "Nothing is fabricated")
        val misleadingAttachedWords = plainLine.copy(
            words = listOf(
                LyricWord("Nothing ", 20_000L, 180L),
                LyricWord("is fabricated", 20_030L, 180L)
            )
        )

        assertNull(resolveExperimentalWordTimestamps(plainLine, SyncType.LINE_SYNC))
        assertNull(resolveExperimentalWordTimestamps(misleadingAttachedWords, SyncType.LINE_SYNC))
        assertNull(resolveExperimentalWordTimestamps(plainLine, SyncType.PLAIN))
    }

    @Test
    fun `hyphenated source word remains one timed unit`() {
        val line = LyricLine(
            time = 30_000L,
            text = "long-awaited",
            words = listOf(LyricWord("long-awaited", 30_000L, 240L))
        )

        val words = resolveExperimentalWordTimestamps(line, SyncType.RICHSYNC)!!

        assertEquals(1, words.size)
        assertEquals("long-awaited", words.single().text)
        assertEquals(30.0, words.single().startTime, 0.0)
        assertEquals(30.240, words.single().endTime!!, 0.0)
    }

    @Test
    fun `repeated words preserve source order and independent intervals`() {
        val line = LyricLine(
            time = 40_000L,
            text = "touch touch touch",
            words = listOf(
                LyricWord("touch ", 40_000L, 200L),
                LyricWord("touch ", 40_300L, null),
                LyricWord("touch", 40_700L, 40L)
            )
        )

        val words = resolveExperimentalWordTimestamps(line, SyncType.RICHSYNC)!!

        assertEquals(listOf("touch ", "touch ", "touch"), words.map { it.text })
        assertEquals(listOf(40.0, 40.3, 40.7), words.map { it.startTime })
        assertEquals(40.2, words[0].endTime!!, 0.0)
        assertNull(words[1].endTime)
        assertEquals(40.74, words[2].endTime!!, 0.0)
    }

    @Test
    fun `overlapping lead and background words retain independent source timing`() {
        val lead = LyricLine(
            time = 50_000L,
            endTime = 52_000L,
            text = "Lead vocal",
            words = listOf(LyricWord("Lead vocal", 50_000L, 2_000L)),
            agent = "v1"
        )
        val background = LyricLine(
            time = 50_500L,
            endTime = 51_200L,
            text = "(ooh)",
            words = listOf(LyricWord("(ooh)", 50_500L, 700L, isBackground = true)),
            isBackground = true,
            agent = "v2"
        )

        val leadWord = resolveExperimentalWordTimestamps(lead, SyncType.RICHSYNC)!!.single()
        val backgroundWord = resolveExperimentalWordTimestamps(background, SyncType.RICHSYNC)!!.single()

        assertEquals(0.5f, experimentalWordProgress(leadWord, 51_000L), 0.001f)
        assertTrue(experimentalWordProgress(backgroundWord, 51_000L) in 0f..1f)
        assertEquals(50.5, backgroundWord.startTime, 0.0)
        assertEquals(51.2, backgroundWord.endTime!!, 0.0)
    }

    @Test
    fun `MetroLyrics still routes to Experimental source-faithful timing`() {
        val settings = AppearanceSettings(
            experimentalLyrics = false,
            lyricsAnimation = LyricsAnimationMode.METRO_LYRICS.displayName
        )
        val line = LyricLine(
            time = 60_000L,
            text = "Metro",
            words = listOf(LyricWord("Metro", 60_000L, null))
        )

        assertTrue(settings.shouldUseExperimentalLyrics)
        val word = resolveExperimentalWordTimestamps(line, SyncType.RICHSYNC)?.single()
        assertNotNull(word)
        assertNull(word!!.endTime)
    }
}
