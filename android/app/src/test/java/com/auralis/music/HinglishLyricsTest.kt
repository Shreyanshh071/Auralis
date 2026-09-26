package com.auralis.music

import com.auralis.music.data.network.LyricsClient
import com.auralis.music.data.parser.HinglishScript
import com.auralis.music.data.parser.IndicScriptNormalizer
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
 * Indian-language lyrics always show in Latin letters, so a song never flips between Hinglish
 * and Devanagari depending on which source answered first or which one upgraded mid-song.
 */
class HinglishLyricsTest {

    // Shape of BetterLyrics' Apple TTML for "Mann Mera" (line-timed, hi-Latn transliteration in the head).
    private val appleLineTtml = """
        <tt xmlns="http://www.w3.org/ns/ttml" xmlns:itunes="http://music.apple.com/lyric-ttml-internal" itunes:timing="Line" xml:lang="en"><head><metadata><iTunesMetadata xmlns="http://music.apple.com/lyric-ttml-internal"><transliterations><transliteration xml:lang="hi-Latn"><text for="L2">Maane na meri mann mera</text><text for="L1">Saari raat aahein bharta, pal-pal yaadon mein marta</text></transliteration></transliterations></iTunesMetadata></metadata></head><body dur="00:03:24.110"><div begin="00:00:21.090" end="00:01:17.090"><p begin="00:00:21.090" end="00:00:26.820" itunes:key="L1">सारी रात आहें भरता, पल-पल यादों में मरता</p><p begin="00:00:26.820" end="00:00:30.680" itunes:key="L2">माने ना मेरी मन मेरा</p></div></body></tt>
    """.trimIndent()

    @Test
    fun `apple transliteration replaces devanagari line text`() {
        val parsed = TtmlParser.parse(appleLineTtml, LyricsProvider.BETTER_LYRICS)
        assertEquals(
            listOf("Saari raat aahein bharta, pal-pal yaadon mein marta", "Maane na meri mann mera"),
            parsed.lines.map { it.text }
        )
        assertEquals(listOf(21_090L, 26_820L), parsed.lines.map { it.time })
        assertFalse(HinglishScript.isMostlyIndic(parsed))
    }

    @Test
    fun `apple transliteration keeps word timing when words line up`() {
        val ttml = """
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:itunes="http://music.apple.com/lyric-ttml-internal"><head><metadata><iTunesMetadata xmlns="http://music.apple.com/lyric-ttml-internal"><transliterations><transliteration xml:lang="hi-Latn"><text for="L2">Maane na meri mann mera</text></transliteration></transliterations></iTunesMetadata></metadata></head><body><div><p begin="26.820" end="30.680" itunes:key="L2"><span begin="26.820" end="27.500">माने</span> <span begin="27.500" end="27.900">ना</span> <span begin="27.900" end="28.600">मेरी</span> <span begin="28.600" end="29.300">मन</span> <span begin="29.300" end="30.680">मेरा</span></p></div></body></tt>
        """.trimIndent()
        val line = TtmlParser.parse(ttml, LyricsProvider.BETTER_LYRICS).lines.single()
        assertEquals("Maane na meri mann mera", line.text)
        assertEquals(listOf("Maane ", "na ", "meri ", "mann ", "mera"), line.words!!.map { it.word })
        assertEquals(listOf(26_820L, 27_500L, 27_900L, 28_600L, 29_300L), line.words!!.map { it.time })
    }

    @Test
    fun `machine transliteration follows hindi schwa rules`() {
        fun t(s: String) = IndicScriptNormalizer.transliterateToReadableHinglish(s).lowercase()
        assertEquals("man", t("मन"))
        assertEquals("bharta", t("भरता"))
        assertEquals("karna", t("करना"))
        assertEquals("samajhna", t("समझना"))
        assertEquals("dil", t("दिल"))
        assertEquals("manjil", t("मंजिल"))
    }

    @Test
    fun `devanagari without transliteration is shown in latin letters with timing untouched`() {
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
        val shown = LyricsContentFilter.cleanForDisplay(data, "Mann Mera")
        val line = shown.lines.single()
        assertFalse(IndicScriptNormalizer.containsIndicScript(line.text))
        assertEquals(line.words!!.joinToString("") { it.word }, line.text)
        assertTrue(line.text.startsWith("M"))
        assertEquals(listOf(1_000L, 1_400L, 1_700L, 2_000L, 2_300L), line.words!!.map { it.time })
    }

    @Test
    fun `latin lyrics outrank machine-transliterated lyrics of the same timing tier`() {
        assertTrue(LyricsClient.outranks(
            tier = LyricsClient.TIER_WORD, score = 100.0,
            bestTier = LyricsClient.TIER_WORD, bestScore = 150.0,
            needsMachineScript = false, bestNeedsMachineScript = true
        ))
        assertFalse(LyricsClient.outranks(
            tier = LyricsClient.TIER_LINE, score = 150.0,
            bestTier = LyricsClient.TIER_LINE, bestScore = 80.0,
            needsMachineScript = true, bestNeedsMachineScript = false
        ))
    }

    @Test
    fun `timing still ranks above script`() {
        // Mann Mera: lrclib's Hinglish line sync starts 12 s early for the played cut; the
        // word-synced Devanagari answer is exact and is shown machine-romanized instead.
        assertTrue(LyricsClient.outranks(
            tier = LyricsClient.TIER_WORD, score = 150.0,
            bestTier = LyricsClient.TIER_LINE, bestScore = 80.0,
            needsMachineScript = true, bestNeedsMachineScript = false
        ))
    }
}
