package com.auralis.music.ui.lyrics.vivi

import com.auralis.music.domain.model.LyricLine
import com.auralis.music.domain.model.LyricWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ViviLyricsSupportTest {
    @Test
    fun `unspaced syllables join into one written word with source timing`() {
        val words = groupWords(listOf(
            LyricWord("Hel", 1_000L, 200L),
            LyricWord("lo ", 1_200L, 300L),
            LyricWord("world", 1_600L, 400L)
        ))!!
        assertEquals(listOf("Hello", "world"), words.map { it.text })
        assertEquals(1.0, words[0].startTime, 0.0001)
        assertEquals(1.5, words[0].endTime, 0.0001)
        assertEquals(2.0, words[1].endTime, 0.0001)
    }

    @Test
    fun `a word without an end time is not given a guessed length`() {
        val word = groupWords(listOf(LyricWord("hey", 3_000L, null)))!!.single()
        assertEquals(word.startTime, word.endTime, 0.0)
    }

    @Test
    fun `synced lyrics get Vivi's blank head entry and keep agents`() {
        val entries = toViviEntries(
            listOf(LyricLine(time = 500L, text = "a", agent = "v2"), LyricLine(time = 900L, text = "b", isBackground = true)),
            isSynced = true, wordSynced = false
        )
        assertEquals(listOf("", "a", "b"), entries.map { it.text })
        assertEquals("v2", entries[1].agent)
        assertEquals(true, entries[2].isBackground)
        assertNull(entries[1].words)
    }

    @Test
    fun `current line is the last one starting within 300 ms`() {
        val entries = toViviEntries(listOf(LyricLine(time = 1_000L, text = "a"), LyricLine(time = 5_000L, text = "b")), true, false)
        assertEquals(0, findCurrentLineIndex(entries, 500L))
        assertEquals(1, findCurrentLineIndex(entries, 800L))
        assertEquals(2, findCurrentLineIndex(entries, 4_800L))
    }

    @Test
    fun `every style uses Spicy Lyrics' text size`() {
        assertEquals(34f, spicyLyricsTextSize(22f), 0.001f)
        assertEquals(34f * 1.4f, spicyLyricsTextSize(40f), 0.001f)
        assertEquals(34f * 0.8f, spicyLyricsTextSize(10f), 0.001f)
        assertEquals(22f, spicyLyricsTextSize(22f, synced = false), 0.001f)
    }
}
