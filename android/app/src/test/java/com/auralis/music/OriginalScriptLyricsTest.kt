package com.auralis.music

import com.auralis.music.data.network.LyricsClient
import com.auralis.music.data.parser.LyricsContentFilter
import com.auralis.music.data.parser.TtmlParser
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
 * Lyrics show in the script the source wrote them in. Forcing Hinglish cost sync: Apple's Latin
 * transliteration collapsed a word-synced line to one span whenever its word count differed,
 * and a Hinglish copy outranked a better-matched Devanagari one of the same timing tier.
 */
class OriginalScriptLyricsTest {

    @Test
    fun `apple ttml keeps its original script and every word time even with a transliteration block`() {
        // The transliteration has 4 words for 5 Devanagari words: forcing it used to collapse the
        // line into a single timed span.
        val ttml = """
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:itunes="http://music.apple.com/lyric-ttml-internal"><head><metadata><iTunesMetadata xmlns="http://music.apple.com/lyric-ttml-internal"><transliterations><transliteration xml:lang="hi-Latn"><text for="L2">Maane na meri mannmera</text></transliteration></transliterations></iTunesMetadata></metadata></head><body><div><p begin="26.820" end="30.680" itunes:key="L2"><span begin="26.820" end="27.500">माने</span> <span begin="27.500" end="27.900">ना</span> <span begin="27.900" end="28.600">मेरी</span> <span begin="28.600" end="29.300">मन</span> <span begin="29.300" end="30.680">मेरा</span></p></div></body></tt>
        """.trimIndent()
        val line = TtmlParser.parse(ttml, LyricsProvider.BETTER_LYRICS).lines.single()
        assertEquals("माने ना मेरी मन मेरा", line.text)
        assertEquals(listOf(26_820L, 27_500L, 27_900L, 28_600L, 29_300L), line.words!!.map { it.time })
    }

    @Test
    fun `display cleanup leaves devanagari and its timing as the source gave them`() {
        val data = LyricsData(
            syncType = SyncType.RICHSYNC,
            lines = listOf(
                LyricLine(1_000L, "माने ना मेरी मन मेरा", words = listOf(
                    LyricWord("माने ", 1_000L, 400L), LyricWord("ना ", 1_400L, 300L), LyricWord("मेरी ", 1_700L, 300L),
                    LyricWord("मन ", 2_000L, 300L), LyricWord("मेरा", 2_300L, 500L)
                ))
            ),
            provider = LyricsProvider.PAXSENIX
        )
        val line = LyricsContentFilter.cleanForDisplay(data, "Mann Mera").lines.single()
        assertEquals("माने ना मेरी मन मेरा", line.text)
        assertEquals(listOf("माने ", "ना ", "मेरी ", "मन ", "मेरा"), line.words!!.map { it.word })
        assertEquals(listOf(1_000L, 1_400L, 1_700L, 2_000L, 2_300L), line.words!!.map { it.time })
    }

    @Test
    fun `other scripts and hinglish sources pass through unchanged`() {
        val lines = listOf("உன்னை காதலிக்கிறேன்", "నిన్ను ప్రేమిస్తున్నాను", "Saari raat aahein bharta")
        val data = LyricsData(
            syncType = SyncType.LINE_SYNC,
            lines = lines.mapIndexed { i, t -> LyricLine(1_000L * (i + 1), t) },
            provider = LyricsProvider.LRCLIB
        )
        assertEquals(lines, LyricsContentFilter.cleanForDisplay(data, "Song").lines.map { it.text })
    }

    @Test
    fun `within one timing tier the better matched copy wins whatever its script`() {
        // Before: a Hinglish copy scoring 100 beat a Devanagari copy scoring 150.
        assertFalse(LyricsClient.outranks(
            tier = LyricsClient.TIER_WORD, score = 100.0,
            bestTier = LyricsClient.TIER_WORD, bestScore = 150.0
        ))
        assertTrue(LyricsClient.outranks(
            tier = LyricsClient.TIER_WORD, score = 150.0,
            bestTier = LyricsClient.TIER_LINE, bestScore = 80.0
        ))
    }
}
