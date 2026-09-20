package com.auralis.music

import com.auralis.music.domain.model.AppearanceSettings
import com.auralis.music.domain.model.LyricLine
import com.auralis.music.domain.model.LyricWord
import com.auralis.music.domain.model.LyricsAnimationMode
import com.auralis.music.domain.model.SyncType
import com.auralis.music.ui.lyrics.experimentalWordProgress
import com.auralis.music.ui.lyrics.resolveExperimentalWordTimestamps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExperimentalLyricsSourceFaithfulTimingTest {

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
