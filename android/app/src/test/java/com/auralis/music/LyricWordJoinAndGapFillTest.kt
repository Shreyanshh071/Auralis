package com.auralis.music

import com.auralis.music.data.network.LyricsClient
import com.auralis.music.data.parser.LrcParser
import com.auralis.music.data.parser.TtmlParser
import com.auralis.music.data.parser.WordTiming
import com.auralis.music.domain.model.LyricLine
import com.auralis.music.domain.model.LyricsData
import com.auralis.music.domain.model.LyricsProvider
import com.auralis.music.domain.model.SyncType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Syllable spans with no space between them are one word ("Dis|ap|point|ed" showed as
 * "Disap pointed", "Hys|ter|i|cal" as "Hyster ical"), and a secondary source may only fill gaps
 * when it's the same transcription on the same clock (lrclib's romanized "Mann Mera" was spliced
 * into the silent intro of the Hindi word-synced lyrics).
 */
class LyricWordJoinAndGapFillTest {

    private fun ttml(vararg paragraphs: String) =
        """<tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata"><body><div>${paragraphs.joinToString("")}</div></body></tt>"""

    @Test
    fun `unison Let Down syllables join into whole words`() {
        // Real Unison markup (unison.boidu.dev, "Let Down" by Radiohead).
        val parsed = TtmlParser.parse(
            ttml(
                """<p begin="0:43.250" end="0:47.499" ttm:agent="v1"><span begin="0:43.250" end="0:43.588">Dis</span><span begin="0:43.588" end="0:44.159">ap</span><span begin="0:44.159" end="0:44.709">point</span><span begin="0:44.709" end="0:45.329">ed</span> <span begin="0:45.329" end="0:46.787">peo</span><span begin="0:46.787" end="0:47.499">ple</span></p>""",
                """<p begin="1:30.000" end="1:34.000" ttm:agent="v1"><span begin="1:30.000" end="1:30.400">Hys</span><span begin="1:30.400" end="1:30.800">ter</span><span begin="1:30.800" end="1:31.100">i</span><span begin="1:31.100" end="1:31.600">cal</span> <span begin="1:31.600" end="1:32.000">and</span> <span begin="1:32.000" end="1:32.600">use</span><span begin="1:32.600" end="1:34.000">less</span></p>"""
            ),
            LyricsProvider.UNISON
        )
        assertEquals("Disappointed people", parsed.lines[0].text)
        assertEquals(listOf("Disappointed ", "people"), parsed.lines[0].words!!.map { it.word })
        assertEquals(43_250L, parsed.lines[0].words!![0].time)
        assertEquals("Hysterical and useless", parsed.lines[1].text)
        assertEquals(listOf("Hysterical ", "and ", "useless"), parsed.lines[1].words!!.map { it.word })
    }

    @Test
    fun `short dictionary syllables are not treated as words`() {
        // "a|way", "be|long", "a|live" and "girl|friend" are real BetterLyrics syllable splits.
        for ((left, right) in listOf("a" to "way", "be" to "long", "a" to "live", "girl" to "friend", "hand" to "made")) {
            assertTrue("$left|$right", WordTiming.isSameWordAcrossNoSpace(left, left, right))
        }
    }

    @Test
    fun `punctuation still ends a word`() {
        assertFalse(WordTiming.isSameWordAcrossNoSpace("so,", "so,", "so"))
        assertFalse(WordTiming.isSameWordAcrossNoSpace("feelin'", "feelin'", "good"))
        assertTrue(WordTiming.isSameWordAcrossNoSpace("could", "could", "n't"))
    }

    private fun lrc(text: String, provider: LyricsProvider) =
        LrcParser.parse(text, provider).copy(durationMs = 228_000L)

    // Word-synced winner (BetterLyrics / Paxsenix, "Mann Mera (Original Version)"): vocals at 21 s.
    private val hindi = lrc(
        """
        [00:21.01]सारी रात आहें भरता, पल-पल यादों में मरता
        [00:26.91]माने ना मेरी मन मेरा
        [00:30.56]सूनी-सूनी शाम, सूने सवेरे, बहते हैं आँसू मेरे
        [00:36.41]जाने कुछ भी ना मन मेरा
        [00:40.20]कभी तेरा था जो, अब बेगाना है वो
        """.trimIndent(),
        LyricsProvider.BETTER_LYRICS
    )

    @Test
    fun `a romanized copy never fills the intro of native-script lyrics`() {
        // lrclib's romanized entry for the same song, first line at 8.9 s.
        val romanized = lrc(
            """
            [00:08.91]Saari raat aahein bharta
            [00:12.66]Pal pal yaadon me marta
            [00:15.39]Maane na meri mann mera
            [00:18.20]Sooni sooni shaam, soone savere
            [00:21.50]Behte hain aansoo mere
            """.trimIndent(),
            LyricsProvider.LRCLIB
        )
        val filled = LyricsClient.fillLyricsGaps(hindi, listOf(romanized))
        assertEquals(hindi.lines.map { it.text }, filled.lines.map { it.text })
    }

    @Test
    fun `a sync for another cut never fills gaps even with the same text`() {
        // Same words, but every line 12 s earlier: another edit of the song.
        val shifted = hindi.copy(
            lines = listOf(LyricLine(2_000L, "Intro line nobody sings here")) +
                hindi.lines.map { it.copy(time = it.time - 12_000L) },
            provider = LyricsProvider.LRCLIB
        )
        val filled = LyricsClient.fillLyricsGaps(hindi, listOf(shifted))
        assertEquals(hindi.lines.size, filled.lines.size)
    }

    @Test
    fun `the same transcription on the same clock still fills a missing line`() {
        val withMissing = LyricsData(
            syncType = SyncType.LINE_SYNC,
            lines = hindi.lines + LyricLine(60_000L, "Kabhi chup-chup rahe"),
            provider = LyricsProvider.LRCLIB,
            durationMs = 228_000L
        )
        val primary = hindi.copy(lines = hindi.lines + LyricLine(75_000L, "Last line"))
        val filled = LyricsClient.fillLyricsGaps(primary, listOf(withMissing))
        assertTrue(filled.lines.any { it.text == "Kabhi chup-chup rahe" })
    }
}
