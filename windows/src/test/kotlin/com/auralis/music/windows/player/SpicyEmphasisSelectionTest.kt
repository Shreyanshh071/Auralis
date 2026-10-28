package com.auralis.music.windows.player

import com.auralis.music.ui.lyrics.renderers.SpicyEmphasis
import com.auralis.music.data.parser.SpicyLyricsParser
import com.auralis.music.data.parser.WordTiming
import com.auralis.music.data.network.SpicyRecording
import org.json.JSONObject
import kotlin.test.*

class SpicyEmphasisSelectionTest {
    @Test fun selectionUsesEverySyllablesDurationRegardlessOfSpelling() {
        // Generated spellings ensure examples from a particular song cannot pass this test alone.
        for (n in 1..80) {
            val text = (0 until 1 + n % 13).map { ('a'.code + (n + it * 7) % 26).toChar() }.joinToString("")
            assertNull(SpicyEmphasis.letters(text, 2000, 2999))
            val held = assertNotNull(SpicyEmphasis.letters(text, 2000, 3000))
            assertEquals(text, held.joinToString("") { text.substring(it.from, it.to) })
            assertEquals(2000L, held.first().time.startMs)
            assertEquals(2750L, held.last().time.endMs)
        }
        assertNull(SpicyEmphasis.letters("unknown end", 2000, null))
    }

    @Test fun lettersKeepAccentsConjunctsAndEmojiIntact() {
        for (text in listOf("étoile", "e\u0301toile", "दिल", "கொடி", "👩‍🎤", "星光")) {
            val held = assertNotNull(SpicyEmphasis.letters(text, 1000, 2500))
            val pieces = held.map { text.substring(it.from, it.to) }
            assertEquals(text, pieces.joinToString(""))
            assertTrue(pieces.none { Character.getType(it.first()) in listOf(
                Character.NON_SPACING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt()) })
        }
        assertEquals(1, SpicyEmphasis.letters("👩‍🎤", 1000, 2500)!!.size)
        assertNull(SpicyEmphasis.letters("سلام", 1000, 2500), "Connected RTL ink stays a whole shaped unit")
    }

    @Test fun explicitThreePartWordRetainsMixedEmphasisAfterRepairAndCacheRoundTrip() {
        val payload = JSONObject("""{
          "Status":200,"Body":{"id":"0123456789ABCDEFGHIJKL","source":"spotify",
          "Type":"Syllable","Content":[{"Type":"Vocal","Lead":{"StartTime":1,"EndTime":3.4,
          "Syllables":[{"Text":"xy","StartTime":1,"EndTime":1.3,"IsPartOfWord":true},
          {"Text":"n","StartTime":1.3,"EndTime":2.7,"IsPartOfWord":true},
          {"Text":"dra","StartTime":2.7,"EndTime":3.4,"IsPartOfWord":false}]}}]}}
        """)
        val parsed = assertNotNull(SpicyLyricsParser.parse(payload,
            SpicyRecording("0123456789ABCDEFGHIJKL", "Generated", "Test", durationMs = 4000)))
        val repaired = WordTiming.splitMergedWordsInLine(parsed.lines.single())
        assertEquals("xyndra", repaired.text)
        val cached = com.auralis.music.desktop.lyrics.LyricsDiskCache.decode(
            com.auralis.music.desktop.lyrics.LyricsDiskCache.encode(parsed.copy(lines = listOf(repaired))))
        val parts = assertNotNull(cached.lines.single().words!!.single().syllables)
        assertEquals(listOf("xy", "n", "dra"), parts.map { it.word })
        assertEquals(listOf(false, true, false), parts.map {
            SpicyEmphasis.letters(it.word, it.time, it.endTime) != null
        })
        assertEquals(listOf(1000L, 1300L, 2700L), parts.map { it.time })
    }
}
