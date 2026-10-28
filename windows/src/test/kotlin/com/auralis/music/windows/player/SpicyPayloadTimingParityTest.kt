package com.auralis.music.windows.player

import com.auralis.music.data.network.SpicyRecording
import com.auralis.music.data.parser.SpicyLyricsParser
import com.auralis.music.data.parser.WordTiming
import com.auralis.music.desktop.lyrics.LyricsDiskCache
import com.auralis.music.domain.model.*
import com.auralis.music.ui.lyrics.renderers.SpicyEmphasis
import org.json.JSONObject
import kotlin.test.*

class SpicyPayloadTimingParityTest {
    @Test fun samePayloadKeepsEverySplitAndRegularSpicyEmphasisDecisionThroughTheAppPipeline() {
        // Same source-syllable decision as Spicy's regular Syllable.ts:
        // each supplied duration >=1000ms uses Emphasize, ending 250ms early.
        // These are unrelated spellings and mixed/whole/letter patterns, not song exceptions.
        val patterns = listOf(784L to 2167L, 1700L to 500L, 816L to 944L,
            999L to 1000L, 1250L to 1400L)
        val content = org.json.JSONArray()
        patterns.forEachIndexed { i, (first, second) ->
            val start = 10000L + i * 10000L
            content.put(JSONObject().put("Type", "Vocal").put("Lead", JSONObject()
                .put("StartTime", start / 1000.0).put("EndTime", (start + first + second) / 1000.0)
                .put("Syllables", org.json.JSONArray()
                    .put(JSONObject().put("Text", "vel").put("StartTime", start / 1000.0)
                        .put("EndTime", (start + first) / 1000.0).put("IsPartOfWord", true))
                    .put(JSONObject().put("Text", "ora").put("StartTime", (start + first) / 1000.0)
                        .put("EndTime", (start + first + second) / 1000.0)))))
        }
        val response = JSONObject().put("Status", 200).put("Body", JSONObject()
            .put("id", "abcdefghijklmnopqrstuv").put("Type", "Syllable").put("source", "apple_music").put("Content", content))
        val parsed = assertNotNull(SpicyLyricsParser.parse(response,
            SpicyRecording("abcdefghijklmnopqrstuv", "Fixture", "Artist", 180000)))
        val cached = LyricsDiskCache.decode(LyricsDiskCache.encode(parsed))
        patterns.forEachIndexed { i, (first, second) ->
            val original = parsed.lines[i]
            val line = WordTiming.splitMergedWordsInLine(cached.lines[i])
            assertEquals(original, line, "Source parts must survive display preparation unchanged")
            val word = line.words!!.single()
            assertEquals("velora", line.text)
            val pieces = word.syllables!!
            assertEquals(listOf(first, second), pieces.map { it.duration })
            pieces.forEach { part ->
                val letters = SpicyEmphasis.letters(part.word, part.time, part.endTime)
                assertEquals(part.duration!! >= 1000L, letters != null)
                if (letters != null) {
                    assertEquals(part.time, letters.first().time.startMs)
                    assertEquals(part.endTime!! - 250L, letters.last().time.endMs)
                }
            }
        }
    }

    @Test fun spellingRepairCannotRedistributeAnExplicitlySplitWord() {
        val original = LyricWord("Iwant", 1000, 2100, syllables = listOf(
            LyricWord("I", 1000, 200), LyricWord("want", 1400, 1700)))
        assertEquals(listOf(original), WordTiming.splitMergedLyricWord(original))
        assertEquals(original.syllables, WordTiming.splitMergedWordsInLine(
            LyricLine(1000, "Iwant", words = listOf(original))).words!!.single().syllables)
    }
}
