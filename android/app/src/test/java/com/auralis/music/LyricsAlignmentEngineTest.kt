package com.auralis.music

import com.auralis.music.data.network.LyricsClient
import com.auralis.music.data.parser.LrcParser
import com.auralis.music.data.parser.TtmlParser
import com.auralis.music.domain.lyrics.LyricsAlignmentEngine
import com.auralis.music.domain.lyrics.MasterMatchStatus
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
 * Phase 4A: Unit tests for Provider-to-Playback Alignment Layer.
 *
 * Verifies:
 * 1. Master mismatch detection for Love Me Not (222s vs 213.46s => MASTER_MISMATCH)
 * 2. Compatible offset detection for Creep (236.54s vs 238.64s => COMPATIBLE_OFFSET)
 * 3. Boundary exact matching (<= 1.5s)
 * 4. Provider ranking: matching line-sync beats mismatched word-sync
 * 5. Metadata parsing (<body dur>, iTunesMetadata leadingSilence, LRC [length:])
 * 6. Immutability guarantees (original timestamps & durations untouched)
 */
class LyricsAlignmentEngineTest {

    @Test
    fun `Love Me Not audio 222s vs TTML 213_46s evaluates to MASTER_MISMATCH`() {
        val loveMeNotLyrics = LyricsData(
            syncType = SyncType.RICHSYNC,
            provider = LyricsProvider.BETTER_LYRICS,
            durationMs = 213_461L,
            leadingSilenceMs = 300L,
            lines = listOf(
                LyricLine(
                    time = 16_834L,
                    text = "See, right now, I need you, I'll meet you somewhere now",
                    words = listOf(
                        LyricWord("See,", 16_834L, 355L),
                        LyricWord("right", 17_189L, 216L)
                    )
                ),
                LyricLine(
                    time = 201_849L,
                    text = "Oh, no, I don't need you, but I miss you, come here",
                    words = listOf(
                        LyricWord("come", 201_122L, 727L)
                    )
                )
            )
        )

        // YouTube audio duration is 3:42 = 222.0 seconds = 222,000 ms
        val playbackDurationMs = 222_000L
        val matchStatus = LyricsAlignmentEngine.evaluateMasterMatch(loveMeNotLyrics, playbackDurationMs)

        assertEquals(MasterMatchStatus.MASTER_MISMATCH, matchStatus)
        // Also verify overload with seconds
        assertEquals(MasterMatchStatus.MASTER_MISMATCH, LyricsAlignmentEngine.evaluateMasterMatch(loveMeNotLyrics, 222L))
    }

    @Test
    fun `Creep audio 236_54s vs TTML 238_64s evaluates to COMPATIBLE_OFFSET`() {
        val creepLyrics = LyricsData(
            syncType = SyncType.RICHSYNC,
            provider = LyricsProvider.BETTER_LYRICS,
            durationMs = 238_640L,
            leadingSilenceMs = 940L,
            lines = listOf(
                LyricLine(
                    time = 19_764L,
                    text = "When you were here before",
                    words = listOf(
                        LyricWord("When", 19_764L, 341L),
                        LyricWord("you were", 20_105L, 221L)
                    )
                ),
                LyricLine(
                    time = 230_693L,
                    text = "I don't belong here",
                    words = listOf(
                        LyricWord("here", 229_695L, 998L)
                    )
                )
            )
        )

        // YouTube audio stream duration is 3:56.54 = 236,540 ms
        val playbackDurationMs = 236_540L
        val matchStatus = LyricsAlignmentEngine.evaluateMasterMatch(creepLyrics, playbackDurationMs)

        assertEquals(MasterMatchStatus.COMPATIBLE_OFFSET, matchStatus)
    }

    @Test
    fun `exact match boundaries are strictly enforced`() {
        fun makeLyricsWithDuration(durMs: Long): LyricsData = LyricsData(
            syncType = SyncType.LINE_SYNC,
            provider = LyricsProvider.LRCLIB,
            durationMs = durMs,
            lines = listOf(LyricLine(time = 10_000L, text = "Test"))
        )

        val targetMs = 200_000L

        // Exact match within 1500ms
        assertEquals(MasterMatchStatus.EXACT_MATCH, LyricsAlignmentEngine.evaluateMasterMatch(makeLyricsWithDuration(200_000L), targetMs))
        assertEquals(MasterMatchStatus.EXACT_MATCH, LyricsAlignmentEngine.evaluateMasterMatch(makeLyricsWithDuration(201_500L), targetMs))
        assertEquals(MasterMatchStatus.EXACT_MATCH, LyricsAlignmentEngine.evaluateMasterMatch(makeLyricsWithDuration(198_500L), targetMs))

        // Compatible offset between 1501ms and 3500ms
        assertEquals(MasterMatchStatus.COMPATIBLE_OFFSET, LyricsAlignmentEngine.evaluateMasterMatch(makeLyricsWithDuration(201_501L), targetMs))
        assertEquals(MasterMatchStatus.COMPATIBLE_OFFSET, LyricsAlignmentEngine.evaluateMasterMatch(makeLyricsWithDuration(203_500L), targetMs))
        assertEquals(MasterMatchStatus.COMPATIBLE_OFFSET, LyricsAlignmentEngine.evaluateMasterMatch(makeLyricsWithDuration(196_500L), targetMs))

        // Master mismatch above 3500ms
        assertEquals(MasterMatchStatus.MASTER_MISMATCH, LyricsAlignmentEngine.evaluateMasterMatch(makeLyricsWithDuration(203_501L), targetMs))
        assertEquals(MasterMatchStatus.MASTER_MISMATCH, LyricsAlignmentEngine.evaluateMasterMatch(makeLyricsWithDuration(196_499L), targetMs))
    }

    @Test
    fun `matching line-synced provider beats mismatched word-synced provider`() {
        // BetterLyrics with 8.5s mismatch on Love Me Not
        val mismatchedWordTier = LyricsClient.TIER_WORD
        val mismatchedWordScore = 150.0
        val mismatchedWordStatus = MasterMatchStatus.MASTER_MISMATCH

        // LRCLIB with exact match to YouTube audio
        val matchingLineTier = LyricsClient.TIER_LINE
        val matchingLineScore = 95.0
        val matchingLineStatus = MasterMatchStatus.EXACT_MATCH

        // The matching line-synced candidate MUST outrank the mismatched word-synced candidate
        assertTrue(
            "Matching line-sync must beat mismatched word-sync",
            LyricsClient.outranks(
                tier = matchingLineTier,
                score = matchingLineScore,
                bestTier = mismatchedWordTier,
                bestScore = mismatchedWordScore,
                masterMatch = matchingLineStatus,
                bestMasterMatch = mismatchedWordStatus
            )
        )

        // The mismatched word-synced candidate must NOT displace an already-established matching line-synced leader
        assertFalse(
            "Mismatched word-sync must not beat matching line-sync",
            LyricsClient.outranks(
                tier = mismatchedWordTier,
                score = mismatchedWordScore,
                bestTier = matchingLineTier,
                bestScore = matchingLineScore,
                masterMatch = mismatchedWordStatus,
                bestMasterMatch = matchingLineStatus
            )
        )
    }

    @Test
    fun `instant winner strictly rejects MASTER_MISMATCH`() {
        // High score word-tier with master mismatch cannot win instantly
        assertFalse(
            "Instant winner must reject MASTER_MISMATCH",
            LyricsClient.isInstantWinner(
                tier = LyricsClient.TIER_WORD,
                score = 160.0,
                masterMatch = MasterMatchStatus.MASTER_MISMATCH
            )
        )

        // Compatible offset (e.g. Creep) CAN win instantly
        assertTrue(
            "Instant winner permits COMPATIBLE_OFFSET",
            LyricsClient.isInstantWinner(
                tier = LyricsClient.TIER_WORD,
                score = 160.0,
                masterMatch = MasterMatchStatus.COMPATIBLE_OFFSET
            )
        )

        // Exact match CAN win instantly
        assertTrue(
            "Instant winner permits EXACT_MATCH",
            LyricsClient.isInstantWinner(
                tier = LyricsClient.TIER_WORD,
                score = 160.0,
                masterMatch = MasterMatchStatus.EXACT_MATCH
            )
        )
    }

    @Test
    fun `TtmlParser extracts body dur and iTunesMetadata leadingSilence`() {
        val ttml = """
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:itunes="http://music.apple.com/lyric-ttml-internal" itunes:timing="Word">
              <head>
                <metadata>
                  <iTunesMetadata xmlns="http://music.apple.com/lyric-ttml-internal" leadingSilence="0.300" />
                </metadata>
              </head>
              <body dur="3:33.461">
                <div>
                  <p begin="16.834" end="21.056">
                    <span begin="16.834" end="17.189">See,</span> <span begin="17.189" end="17.405">right</span>
                  </p>
                </div>
              </body>
            </tt>
        """.trimIndent()

        val parsed = TtmlParser.parse(ttml, LyricsProvider.BETTER_LYRICS)

        assertEquals(213_461L, parsed.durationMs)
        assertEquals(300L, parsed.leadingSilenceMs)
        assertEquals(213_461L, parsed.effectiveDurationMs)
    }

    @Test
    fun `LrcParser extracts length tag into durationMs`() {
        val lrc = """
            [ti:Love Me Not]
            [ar:Ravyn Lenae]
            [length: 03:34.00]
            [00:16.83]See, right now
            [00:21.13]You up now
        """.trimIndent()

        val parsed = LrcParser.parse(lrc, LyricsProvider.LRCLIB)

        assertEquals(214_000L, parsed.durationMs)
        assertEquals(214_000L, parsed.effectiveDurationMs)
    }

    @Test
    fun `alignToPlayback preserves immutability and genuine word durations`() {
        val originalWord = LyricWord("Hello", 10_000L, 500L)
        val originalLine = LyricLine(
            time = 10_000L,
            text = "Hello",
            words = listOf(originalWord)
        )
        val originalLyrics = LyricsData(
            syncType = SyncType.RICHSYNC,
            provider = LyricsProvider.BETTER_LYRICS,
            durationMs = 180_000L,
            leadingSilenceMs = 400L,
            lines = listOf(originalLine)
        )

        // Audio has leading silence of 600ms (+200ms offset)
        val aligned = LyricsAlignmentEngine.alignToPlayback(
            lyrics = originalLyrics,
            playbackDurationMs = 180_000L,
            audioLeadingSilenceMs = 600L
        )

        // 1. Aligned copy received +200ms shift
        assertEquals(10_200L, aligned.lines[0].time)
        assertEquals(10_200L, aligned.lines[0].words!![0].time)

        // 2. Word duration is strictly preserved (NOT scaled)
        assertEquals(500L, aligned.lines[0].words!![0].duration)

        // 3. Original lyrics instances remain 100% untouched
        assertEquals(10_000L, originalLyrics.lines[0].time)
        assertEquals(10_000L, originalLine.time)
        assertEquals(10_000L, originalWord.time)
        assertEquals(500L, originalWord.duration)
    }
}
