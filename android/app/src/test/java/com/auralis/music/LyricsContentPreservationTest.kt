package com.auralis.music

import com.auralis.music.data.network.LyricsClient
import com.auralis.music.data.parser.TtmlParser
import com.auralis.music.domain.model.*
import org.junit.Assert.*
import org.junit.Test

class LyricsContentPreservationTest {
    private fun data(lines: List<LyricLine>) = LyricsData(
        syncType = SyncType.LINE_SYNC, lines = lines, durationMs = 120_000L,
        provider = LyricsProvider.LRCLIB
    )
    private val anchors = listOf(
        LyricLine(10_000L, "We return again"),
        LyricLine(20_000L, "The lights are here"),
        LyricLine(30_000L, "Another verse begins"),
        LyricLine(70_000L, "Now the bridge ends")
    )

    @Test fun `same phrase sung later in an internal gap is not discarded`() {
        val primary = data(anchors)
        val secondary = data((anchors + LyricLine(45_000L, anchors.first().text)).sortedBy { it.time })
        val filled = LyricsClient.fillLyricsGaps(primary, listOf(secondary))
        assertEquals(listOf(10_000L, 45_000L), filled.lines.filter { it.text == anchors.first().text }.map { it.time })
        assertEquals(5, filled.lines.size)
        assertTrue(filled.lines.all { it.words == null })
    }

    @Test fun `outro repeat retains its source timestamp and is not inserted twice`() {
        val primary = data(anchors)
        val secondary = data(anchors + LyricLine(95_000L, anchors.first().text))
        val once = LyricsClient.fillLyricsGaps(primary, listOf(secondary, secondary))
        val twice = LyricsClient.fillLyricsGaps(once, listOf(secondary))
        assertEquals(5, once.lines.size)
        assertEquals(once.lines, twice.lines)
        assertEquals(95_000L, once.lines.last().time)
    }

    @Test fun `another cut on a shifted clock cannot add repeat lyrics`() {
        val primary = data(anchors)
        val shifted = data(anchors.map { it.copy(time = it.time + 12_000L) } + LyricLine(45_000L, anchors.first().text))
        assertEquals(primary, LyricsClient.fillLyricsGaps(primary, listOf(shifted)))
    }

    @Test fun `existing occurrence on the same clock stays unchanged`() {
        val primary = data(anchors + LyricLine(95_000L, anchors.first().text))
        assertEquals(primary, LyricsClient.fillLyricsGaps(primary, listOf(primary)))
    }

    @Test fun `TTML loose repeated text is retained instead of suffix deduplicated`() {
        val parsed = TtmlParser.parse("""<tt><body><div><p begin="10s" end="12s"><span begin="10s" end="11s">la</span>la</p></div></body></tt>""")
        assertEquals("lala", parsed.lines.single().text)
        assertEquals(10_000L, parsed.lines.single().words!!.first().time)
        assertEquals(1000L, parsed.lines.single().words!!.first().duration)
    }

    @Test fun `TTML repeated punctuation is content not a duplicate span`() {
        val parsed = TtmlParser.parse("""<tt><body><div><p begin="10s" end="12s"><span begin="10s" end="11s">Go!</span>!</p></div></body></tt>""")
        assertEquals("Go!!", parsed.lines.single().text)
    }

    @Test fun `recorded provider outro retains full wording and all timed repetitions`() {
        val fixture = requireNotNull(javaClass.classLoader!!.getResource("provider-outro-repeat.ttml")).readText()
        val parsed = TtmlParser.parse(fixture)
        assertEquals(3, parsed.lines.size)
        assertEquals(listOf("It's all wrong", "It's all wrong", "It's all wrong"), parsed.lines.map { it.text })
        assertTrue(parsed.lines.zipWithNext().all { (a, b) -> a.time < b.time })
        assertTrue(parsed.lines.all { it.words!!.joinToString("") { w -> w.word }.trim() == it.text })
    }

    @Test fun `provider shorthand is preserved and never expanded by the parser`() {
        val parsed = TtmlParser.parse("""<tt><body><div><p begin="10s" end="12s"><span begin="10s" end="11s">S'all wrong</span></p><p begin="13s" end="15s">S'all wrong</p></div></body></tt>""")
        assertEquals(listOf("S'all wrong", "S'all wrong"), parsed.lines.map { it.text })
    }
}
