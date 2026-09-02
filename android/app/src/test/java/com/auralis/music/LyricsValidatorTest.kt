package com.auralis.music

import com.auralis.music.data.parser.LyricsValidator
import com.auralis.music.domain.model.LyricLine
import com.auralis.music.domain.model.LyricsData
import com.auralis.music.domain.model.LyricsProvider
import com.auralis.music.domain.model.SyncType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsValidatorTest {

    @Test
    fun testCorruptQuestionMarkEncodingRejection() {
        // Real corrupt payload from LRCLIB for Sadiya by Pawan Singh
        val corruptLines = listOf(
            LyricLine(time = 3790, text = "(??? ?? ????? ??...)"),
            LyricLine(time = 7340, text = "(??? ?? ????? ??...)"),
            LyricLine(time = 23970, text = "?? ???? ???? decorate, ???"),
            LyricLine(time = 27770, text = "????? ???, ???? set, ???"),
            LyricLine(time = 34760, text = "???, ?? ???? ???? decorate, ???"),
            LyricLine(time = 38930, text = "????? ???, ???? set, ???")
        )
        val corruptLyrics = LyricsData(
            provider = LyricsProvider.LRCLIB,
            syncType = SyncType.LINE_SYNC,
            lines = corruptLines,
            trackName = "Sadiya",
            artistName = "Pawan Singh"
        )

        assertTrue(
            "Corrupt question mark encoding from LRCLIB must be rejected",
            LyricsValidator.isCorruptOrInvalid(corruptLyrics)
        )
    }

    @Test
    fun testPlaceholderLyricsRejection() {
        val placeholderLyrics = LyricsData(
            provider = LyricsProvider.LRCLIB,
            syncType = SyncType.PLAIN,
            lines = listOf(
                LyricLine(time = 0, text = "Lyrics not available for this song"),
                LyricLine(time = 5000, text = "Coming soon")
            ),
            trackName = "Sample Song",
            artistName = "Sample Artist"
        )

        assertTrue(
            "Placeholder lyrics must be rejected",
            LyricsValidator.isCorruptOrInvalid(placeholderLyrics)
        )
    }

    @Test
    fun testValidDevanagariAndEnglishLyricsPass() {
        val validDevanagari = LyricsData(
            provider = LyricsProvider.LRCLIB,
            syncType = SyncType.LINE_SYNC,
            lines = listOf(
                LyricLine(time = 1000, text = "सादिया ए जान"),
                LyricLine(time = 4500, text = "आल्पिन से खोस देब"),
                LyricLine(time = 8200, text = "पवन सिंह के गाना बाजता")
            ),
            trackName = "Sadiya",
            artistName = "Pawan Singh"
        )

        assertFalse(
            "Valid Devanagari lyrics must pass validation",
            LyricsValidator.isCorruptOrInvalid(validDevanagari)
        )

        val validEnglish = LyricsData(
            provider = LyricsProvider.LRCLIB,
            syncType = SyncType.LINE_SYNC,
            lines = listOf(
                LyricLine(time = 1200, text = "Why do you love me not?"),
                LyricLine(time = 3400, text = "I thought we had a shot"),
                LyricLine(time = 6700, text = "Dancing in the shadows alone")
            ),
            trackName = "Love Me Not",
            artistName = "Artist"
        )

        assertFalse(
            "Valid English lyrics must pass validation",
            LyricsValidator.isCorruptOrInvalid(validEnglish)
        )
    }
}
