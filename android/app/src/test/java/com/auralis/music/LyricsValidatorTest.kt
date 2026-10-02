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

    @Test
    fun testCapturedNetEaseAllINeedDefectiveMicroTimingRejected() {
        // Real captured failing payload from NetEase YRC for "All I Need" by Radiohead
        // displaying shorthand "S'all wrong" and "S'alright" with consecutive 180ms lines
        val capturedNetEaseLines = listOf(
            LyricLine(time = 160250, text = "Lying in the reeds"),
            LyricLine(
                time = 198350,
                text = "S'all wrong",
                words = listOf(
                    com.auralis.music.domain.model.LyricWord(word = "S'all ", time = 198350, duration = 9595),
                    com.auralis.music.domain.model.LyricWord(word = "wrong", time = 207945, duration = 9595)
                )
            ),
            LyricLine(
                time = 217540,
                text = "S'alright",
                words = listOf(com.auralis.music.domain.model.LyricWord(word = "S'alright", time = 217540, duration = 180))
            ),
            LyricLine(
                time = 217720,
                text = "S'alright",
                words = listOf(com.auralis.music.domain.model.LyricWord(word = "S'alright", time = 217720, duration = 260))
            ),
            LyricLine(
                time = 217980,
                text = "S'all wrong",
                words = listOf(
                    com.auralis.music.domain.model.LyricWord(word = "S'all ", time = 217980, duration = 1125),
                    com.auralis.music.domain.model.LyricWord(word = "wrong", time = 219105, duration = 1125)
                )
            ),
            LyricLine(
                time = 220230,
                text = "S'alright",
                words = listOf(com.auralis.music.domain.model.LyricWord(word = "S'alright", time = 220230, duration = 180))
            ),
            LyricLine(
                time = 220410,
                text = "S'alright",
                words = listOf(com.auralis.music.domain.model.LyricWord(word = "S'alright", time = 220410, duration = 180))
            ),
            LyricLine(
                time = 220590,
                text = "S'alright",
                words = listOf(com.auralis.music.domain.model.LyricWord(word = "S'alright", time = 220590, duration = 180))
            )
        )

        val netEaseCandidate = LyricsData(
            provider = LyricsProvider.NETEASE,
            syncType = SyncType.RICHSYNC,
            lines = capturedNetEaseLines,
            trackName = "All I Need",
            artistName = "Radiohead",
            durationMs = 228746L
        )

        assertTrue(
            "Captured NetEase YRC payload with 180ms duplicate lines must be flagged as defective micro-timing",
            LyricsValidator.hasDefectiveMicroTiming(netEaseCandidate)
        )
        assertTrue(
            "Captured NetEase YRC payload must be rejected by isCorruptOrInvalid",
            LyricsValidator.isCorruptOrInvalid(netEaseCandidate)
        )
    }

    @Test
    fun testAppleMusicTtmlAllINeedOutroRepetitionPassesValidation() {
        // Legitimate Apple Music TTML with properly phrased sung repetitions (~2.7s apart)
        val ttmlLines = listOf(
            LyricLine(
                time = 192758,
                text = "It's all wrong",
                words = listOf(
                    com.auralis.music.domain.model.LyricWord("It's", 192758, 350),
                    com.auralis.music.domain.model.LyricWord("all", 193108, 615),
                    com.auralis.music.domain.model.LyricWord("wrong", 193723, 1622)
                )
            ),
            LyricLine(
                time = 195485,
                text = "It's all wrong",
                words = listOf(
                    com.auralis.music.domain.model.LyricWord("It's", 195485, 350),
                    com.auralis.music.domain.model.LyricWord("all", 195835, 634),
                    com.auralis.music.domain.model.LyricWord("wrong", 196469, 1835)
                )
            ),
            LyricLine(
                time = 198304,
                text = "It's all wrong",
                words = listOf(
                    com.auralis.music.domain.model.LyricWord("It's", 198304, 352),
                    com.auralis.music.domain.model.LyricWord("all", 198656, 581),
                    com.auralis.music.domain.model.LyricWord("wrong", 199237, 1579)
                )
            ),
            LyricLine(
                time = 200960,
                text = "It's all right",
                words = listOf(
                    com.auralis.music.domain.model.LyricWord("It's", 200960, 416),
                    com.auralis.music.domain.model.LyricWord("all", 201376, 568),
                    com.auralis.music.domain.model.LyricWord("right", 201944, 1767)
                )
            )
        )

        val ttmlCandidate = LyricsData(
            provider = LyricsProvider.BETTER_LYRICS,
            syncType = SyncType.RICHSYNC,
            lines = ttmlLines,
            trackName = "All I Need",
            artistName = "Radiohead",
            durationMs = 228746L
        )

        assertFalse(
            "Legitimate Apple Music TTML outro repetitions must NOT be flagged as defective micro-timing",
            LyricsValidator.hasDefectiveMicroTiming(ttmlCandidate)
        )
        assertFalse(
            "Legitimate Apple Music TTML must pass isCorruptOrInvalid validation",
            LyricsValidator.isCorruptOrInvalid(ttmlCandidate)
        )
    }

    @Test
    fun testLrcLibAllINeedFullWordingPassesValidation() {
        // Legitimate LRCLIB lines with full wording and line-level sync
        val lrcLines = listOf(
            LyricLine(time = 194260, text = "It's all wrong, it's all wrong, it's all wrong"),
            LyricLine(time = 207860, text = "It's alright, it's alright, it's alright"),
            LyricLine(time = 215180, text = "It's all wrong, it's alright"),
            LyricLine(time = 224460, text = "It's alright, it's alright")
        )

        val lrcCandidate = LyricsData(
            provider = LyricsProvider.LRCLIB,
            syncType = SyncType.LINE_SYNC,
            lines = lrcLines,
            trackName = "All I Need",
            artistName = "Radiohead",
            durationMs = 228746L
        )

        assertFalse(
            "Legitimate LRCLIB lines must NOT be flagged as defective",
            LyricsValidator.hasDefectiveMicroTiming(lrcCandidate)
        )
        assertFalse(
            "Legitimate LRCLIB lines must pass isCorruptOrInvalid validation",
            LyricsValidator.isCorruptOrInvalid(lrcCandidate)
        )
    }
}
