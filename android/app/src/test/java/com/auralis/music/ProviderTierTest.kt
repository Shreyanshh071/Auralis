package com.auralis.music

import com.auralis.music.data.network.LyricsClient
import com.auralis.music.domain.model.LyricLine
import com.auralis.music.domain.model.LyricWord
import com.auralis.music.domain.model.LyricsData
import com.auralis.music.domain.model.LyricsProvider
import com.auralis.music.domain.model.SyncType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Provider selection must rank by timing *format* before anything else.
 *
 * The bug these tests lock down: the old flat quality score handed LRCLIB a
 * +10 source bonus, a +15 intro bonus and up to +24 for line count, while
 * word-synced data earned only +8. A line-synced lyric therefore beat a
 * syllable-timed one routinely, and the >= 145 instant win could end the race
 * before a word source had answered at all.
 */
class ProviderTierTest {

    private fun wordSynced(): LyricsData = LyricsData(
        syncType = SyncType.RICHSYNC,
        provider = LyricsProvider.BETTER_LYRICS,
        lines = listOf(
            LyricLine(
                time = 12_000L,
                text = "I said ooh",
                words = listOf(
                    LyricWord("I", 12_000L, 220L),
                    LyricWord("said", 12_260L, 300L),
                    LyricWord("ooh", 12_600L, 480L)
                )
            )
        )
    )

    private fun lineSynced(): LyricsData = LyricsData(
        syncType = SyncType.LINE_SYNC,
        provider = LyricsProvider.LRCLIB,
        lines = listOf(
            LyricLine(time = 12_000L, text = "I said ooh"),
            LyricLine(time = 15_400L, text = "Bitter sweet symphony")
        )
    )

    private fun plain(): LyricsData = LyricsData(
        syncType = SyncType.PLAIN,
        provider = LyricsProvider.GENIUS,
        lines = listOf(
            LyricLine(time = 0L, text = "I said ooh"),
            LyricLine(time = 0L, text = "Bitter sweet symphony")
        )
    )

    @Test
    fun `tierOf reads the timing format out of the data`() {
        assertEquals(LyricsClient.TIER_WORD, LyricsClient.tierOf(wordSynced()))
        assertEquals(LyricsClient.TIER_LINE, LyricsClient.tierOf(lineSynced()))
        assertEquals(LyricsClient.TIER_NONE, LyricsClient.tierOf(plain()))
    }

    @Test
    fun `a RICHSYNC label with no real durations is not word tier`() {
        // Exactly what the fabricating parsers produce today: word entries whose
        // durations are absent. Claiming the word tier for these would enter the
        // sweep renderer with nothing to sweep.
        val mislabelled = LyricsData(
            syncType = SyncType.RICHSYNC,
            provider = LyricsProvider.MUSIXMATCH,
            lines = listOf(
                LyricLine(
                    time = 12_000L,
                    text = "I said ooh",
                    words = listOf(
                        LyricWord("I", 12_000L, null),
                        LyricWord("said", 12_260L, null),
                        LyricWord("ooh", 12_600L, null)
                    )
                )
            )
        )
        assertEquals(LyricsClient.TIER_LINE, LyricsClient.tierOf(mislabelled))
    }

    @Test
    fun `a single timed word is a parser artefact, not word sync`() {
        val oneWord = LyricsData(
            syncType = SyncType.RICHSYNC,
            provider = LyricsProvider.NETEASE,
            lines = listOf(
                LyricLine(
                    time = 12_000L,
                    text = "I said ooh",
                    words = listOf(
                        LyricWord("I", 12_000L, 220L),
                        LyricWord("said", 12_260L, null)
                    )
                )
            )
        )
        assertEquals(LyricsClient.TIER_LINE, LyricsClient.tierOf(oneWord))
    }

    @Test
    fun `instrumental lines cannot supply the word tier`() {
        val instrumentalOnly = LyricsData(
            syncType = SyncType.RICHSYNC,
            provider = LyricsProvider.KUGOU,
            lines = listOf(
                LyricLine(
                    time = 0L,
                    text = "♪",
                    isInstrumental = true,
                    words = listOf(
                        LyricWord("♪", 0L, 500L),
                        LyricWord("♪", 500L, 500L)
                    )
                ),
                LyricLine(time = 12_000L, text = "I said ooh")
            )
        )
        assertEquals(LyricsClient.TIER_LINE, LyricsClient.tierOf(instrumentalOnly))
    }

    @Test
    fun `a word-synced candidate scoring 60 beats a line-synced one scoring 150`() {
        assertTrue(
            LyricsClient.outranks(
                tier = LyricsClient.TIER_WORD, score = 60.0,
                bestTier = LyricsClient.TIER_LINE, bestScore = 150.0
            )
        )
    }

    @Test
    fun `a line-synced candidate never displaces a word-synced one`() {
        assertFalse(
            LyricsClient.outranks(
                tier = LyricsClient.TIER_LINE, score = 199.0,
                bestTier = LyricsClient.TIER_WORD, bestScore = 51.0
            )
        )
    }

    @Test
    fun `score still decides inside a tier`() {
        assertTrue(
            LyricsClient.outranks(
                tier = LyricsClient.TIER_WORD, score = 120.0,
                bestTier = LyricsClient.TIER_WORD, bestScore = 119.9
            )
        )
        assertFalse(
            LyricsClient.outranks(
                tier = LyricsClient.TIER_WORD, score = 119.9,
                bestTier = LyricsClient.TIER_WORD, bestScore = 120.0
            )
        )
        // Ties keep the earlier arrival, which is the higher-priority provider.
        assertFalse(
            LyricsClient.outranks(
                tier = LyricsClient.TIER_LINE, score = 100.0,
                bestTier = LyricsClient.TIER_LINE, bestScore = 100.0
            )
        )
    }

    @Test
    fun `the instant win does not fire for a line-synced candidate`() {
        assertFalse(LyricsClient.isInstantWinner(LyricsClient.TIER_LINE, 200.0))
        assertFalse(LyricsClient.isInstantWinner(LyricsClient.TIER_NONE, 500.0))
        assertTrue(LyricsClient.isInstantWinner(LyricsClient.TIER_WORD, LyricsClient.INSTANT_WIN_SCORE))
        assertFalse(
            LyricsClient.isInstantWinner(
                LyricsClient.TIER_WORD,
                LyricsClient.INSTANT_WIN_SCORE - 0.1
            )
        )
    }

    @Test
    fun `a late word-synced arrival still displaces an early line-synced leader`() {
        // Sequenced the way the race sees it: LRCLIB lands first and fast, Better
        // Lyrics answers ~1s later. The second must take over.
        var bestTier = LyricsClient.TIER_NONE
        var bestScore = 0.0

        val early = LyricsClient.tierOf(lineSynced()) to 152.0
        if (LyricsClient.outranks(early.first, early.second, bestTier, bestScore)) {
            bestTier = early.first
            bestScore = early.second
        }
        assertEquals(LyricsClient.TIER_LINE, bestTier)

        val late = LyricsClient.tierOf(wordSynced()) to 71.0
        if (LyricsClient.outranks(late.first, late.second, bestTier, bestScore)) {
            bestTier = late.first
            bestScore = late.second
        }
        assertEquals(LyricsClient.TIER_WORD, bestTier)
        assertEquals(71.0, bestScore, 0.001)
    }
}
