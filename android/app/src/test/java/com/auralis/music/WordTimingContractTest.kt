package com.auralis.music

import com.auralis.music.data.parser.BetterLyricsParser
import com.auralis.music.data.parser.LrcParser
import com.auralis.music.data.parser.MusixmatchRichsyncParser
import com.auralis.music.data.parser.TtmlParser
import com.auralis.music.data.parser.WordTiming
import com.auralis.music.data.parser.YrcParser
import com.auralis.music.domain.model.LyricLine
import com.auralis.music.domain.model.LyricsData
import com.auralis.music.domain.model.SyncType
import com.auralis.music.ui.lyrics.resolveEffectiveWords
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The timing-honesty contract, asserted across every parser at once.
 *
 * Two rules, and they are the whole point of the lyrics pipeline:
 *
 *  1. A word end that the provider did not state stays `null`. No 300 ms default,
 *     no 3000 ms line end, no borrowing the next word's start.
 *  2. Wherever an end *is* stated, `time + duration` never reaches past the next
 *     word's `time`. That is the direct guard against a highlight sweeping through
 *     a vocal rest.
 *
 * Note on labelling: a source that states starts but no ends (enhanced LRC, some
 * TTML exports) is still genuinely word-timed — it can be **stepped**, just not
 * swept. Such a result is legitimately RICHSYNC while
 * [WordTiming.hasGenuineWordTiming] stays false, which is what stops the renderer
 * from sweeping it.
 */
class WordTimingContractTest {

    // ---- fixtures: real formats, per-token ends absent ----

    private val enhancedLrcNoEnds = "[00:10.00]<00:10.00>Hold <00:13.00>on"

    private val qrcZeroLengths = "[00:10.00](10000,0)Hold (13000,0)on"

    private val yrcBracketZeroLengths = "[10000,3500](10000,0,0)Hold (13000,0,0)on"

    private val yrcJsonNoDurations =
        """[{"t":10000,"c":[{"tx":"Hold ","t":0},{"tx":"on","t":3000}]}]"""

    private val musixmatchNoLineEnd =
        """[{"ts":10.0,"x":"Hold","l":[{"c":"Hold","o":0.0}]}]"""

    private val ttmlNoEnds = """
        <?xml version="1.0" encoding="utf-8"?>
        <tt xmlns="http://www.w3.org/ns/ttml">
          <body><div>
            <p begin="00:00:10.000">
              <span begin="00:00:10.000">Hold </span>
              <span begin="00:00:13.000">on</span>
            </p>
          </div></body>
        </tt>
    """.trimIndent()

    /** A dense partition with a real rest in it — the positive control. */
    private val musixmatchWithRest = """
        [{"ts":10.0,"te":13.5,"x":"Hold on","l":[
          {"c":"Hold","o":0.0},
          {"c":" ","o":0.5},
          {"c":"on","o":3.0}
        ]}]
    """.trimIndent()

    private fun parsed(): Map<String, LyricsData> = mapOf(
        "enhanced LRC" to LrcParser.parse(enhancedLrcNoEnds),
        "QRC" to BetterLyricsParser.parseQrc(qrcZeroLengths)!!,
        "YRC (bracket)" to YrcParser.parse(yrcBracketZeroLengths)!!,
        "YRC (json)" to YrcParser.parse(yrcJsonNoDurations)!!,
        "Musixmatch richsync" to MusixmatchRichsyncParser.parse(musixmatchNoLineEnd)!!,
        "TTML" to TtmlParser.parse(ttmlNoEnds)
    )

    /**
     * The subset that states two or more distinct starts. Musixmatch is excluded by
     * construction, not by exception: its `l` array is a dense partition, so any
     * line with two tokens necessarily states the first token's end. A Musixmatch
     * line with no stated end therefore has exactly one token.
     */
    private fun stepCapable(): Map<String, LyricsData> =
        parsed().filterKeys { it != "Musixmatch richsync" }

    private fun assertNoWordOverlapsTheNext(label: String, lines: List<LyricLine>) {
        for (line in lines) {
            val words = line.words ?: continue
            for (i in 0 until words.size - 1) {
                val end = words[i].endTime ?: continue
                assertTrue(
                    "$label: word ${i} (\"${words[i].word}\") ends at $end, " +
                        "past the next word's start ${words[i + 1].time}",
                    end <= words[i + 1].time
                )
            }
        }
    }

    @Test
    fun `an unstated word end stays null in every parser`() {
        for ((label, data) in parsed()) {
            val words = data.lines.flatMap { it.words.orEmpty() }
            assertTrue("$label: fixture produced no words at all", words.isNotEmpty())
            for (w in words) {
                assertNull(
                    "$label: word \"${w.word}\" was given a duration the source never stated",
                    w.duration
                )
            }
        }
    }

    @Test
    fun `nothing sweeps when no end was stated`() {
        for ((label, data) in parsed()) {
            assertFalse(
                "$label: was reported as sweep-capable without a single stated word end",
                WordTiming.hasGenuineWordTiming(data.lines)
            )
        }
    }

    @Test
    fun `no word end reaches past the next word start in any parser`() {
        for ((label, data) in parsed()) {
            assertNoWordOverlapsTheNext(label, data.lines)
        }
        val partition = MusixmatchRichsyncParser.parse(musixmatchWithRest)!!
        assertNoWordOverlapsTheNext("Musixmatch (dense partition)", partition.lines)
    }

    @Test
    fun `a dense partition keeps its stated timing and leaves the rest unpainted`() {
        val data = MusixmatchRichsyncParser.parse(musixmatchWithRest)!!
        assertEquals(SyncType.RICHSYNC, data.syncType)
        assertTrue(WordTiming.hasGenuineWordTiming(data.lines))

        val words = data.lines[0].words!!
        assertEquals(2, words.size)
        assertEquals("Hold ", words[0].word)
        assertEquals(10_000L, words[0].time)
        assertEquals(500L, words[0].duration)
        assertEquals("on", words[1].word)
        assertEquals(13_000L, words[1].time)
        // Ends at the stated line end, not at some invented default.
        assertEquals(500L, words[1].duration)
        // 10500 -> 13000 belongs to no word.
        assertEquals(10_500L, words[0].endTime)
    }

    @Test
    fun `starts without ends are still labelled word-synced so they can step`() {
        // Stepping at a genuine start is real information; discarding it would lose
        // timing the source did supply. Sweeping it would invent timing it did not.
        for ((label, data) in stepCapable()) {
            assertTrue(
                "$label: genuine distinct word starts were not recognised",
                WordTiming.hasGenuineWordStarts(data.lines)
            )
            assertEquals("$label: sync label", SyncType.RICHSYNC, data.syncType)
        }
    }

    @Test
    fun `a lone untimed token degrades to line sync rather than claiming word sync`() {
        // Musixmatch with no "te": one token, no end, nothing to step between.
        val data = MusixmatchRichsyncParser.parse(musixmatchNoLineEnd)!!
        assertEquals(1, data.lines[0].words!!.size)
        assertFalse(WordTiming.hasGenuineWordStarts(data.lines))
        assertEquals(SyncType.LINE_SYNC, data.syncType)
    }

    @Test
    fun `a word list sharing one timestamp is not word timing`() {
        // What a token splitter produces: N "words", all stamped with the line time.
        val fabricated = listOf(
            LyricLine(
                time = 10_000L,
                text = "Hold on",
                words = listOf(
                    com.auralis.music.domain.model.LyricWord("Hold ", 10_000L),
                    com.auralis.music.domain.model.LyricWord("on", 10_000L)
                )
            )
        )
        assertFalse(WordTiming.hasGenuineWordStarts(fabricated))
        assertFalse(WordTiming.hasGenuineWordTiming(fabricated))
        assertFalse(WordTiming.statesMoreThanLineTime(fabricated))
    }

    @Test
    fun `mergeMicroFragments does not manufacture words from line timestamps`() {
        // Fragments arrive with only a line time each. Merging them may combine the
        // text, but it must not claim to know when each token was sung.
        val fragments = listOf(
            LyricLine(time = 10_000L, text = "Hold"),
            LyricLine(time = 10_600L, text = "on"),
            LyricLine(time = 11_200L, text = "tight"),
            LyricLine(time = 20_000L, text = "Somewhere else entirely now")
        )
        val merged = LrcParser.mergeMicroFragments(fragments)

        assertTrue("nothing merged, fixture is not exercising the path", merged.size < fragments.size)
        for (line in merged) {
            assertNull("merge invented a word list for \"${line.text}\"", line.words)
        }
        assertFalse(WordTiming.hasGenuineWordStarts(merged))
    }

    @Test
    fun `a plain line-synced lyric is never labelled word-synced`() {
        val plainLrc = "[00:10.00]Hold on\n[00:13.00]Tight"
        val data = LrcParser.parse(plainLrc)

        assertEquals(SyncType.LINE_SYNC, data.syncType)
        assertEquals(2, data.lines.size)
        for (line in data.lines) {
            assertNull("line-synced LRC produced a word list", line.words)
        }
    }

    @Test
    fun `a genuinely swept lyric survives the contract intact`() {
        val ttml = """
            <?xml version="1.0" encoding="utf-8"?>
            <tt xmlns="http://www.w3.org/ns/ttml">
              <body><div>
                <p begin="00:00:10.000" end="00:00:10.500">
                  <span begin="00:00:10.000" end="00:00:10.200">Hold </span>
                  <span begin="00:00:10.200" end="00:00:10.500">on</span>
                </p>
              </div></body>
            </tt>
        """.trimIndent()

        val data = TtmlParser.parse(ttml)
        assertEquals(SyncType.RICHSYNC, data.syncType)
        assertTrue(WordTiming.hasGenuineWordTiming(data.lines))

        val words = data.lines[0].words
        assertNotNull(words)
        assertEquals(200L, words!![0].duration)
        assertEquals(300L, words[1].duration)
        assertNoWordOverlapsTheNext("TTML (with ends)", data.lines)
    }

    @Test
    fun `TIER_LINE lyric does not receive fabricated word timing`() {
        // Line-synced track (e.g. Touch by Cigarettes After Sex from LRCLIB)
        val line = LyricLine(
            time = 145_000L,
            text = "And I know it's been awhile since I needed a distraction",
            words = null
        )

        val result = resolveEffectiveWords(line, SyncType.LINE_SYNC)
        assertNull("TIER_LINE lyric must return null words to prevent fabricated word sweep", result)

        // Line with empty words list under LINE_SYNC
        val emptyWordsLine = LyricLine(
            time = 145_000L,
            text = "And I know it's been awhile since I needed a distraction",
            words = emptyList()
        )
        assertNull("Empty words under LINE_SYNC must resolve to null", resolveEffectiveWords(emptyWordsLine, SyncType.LINE_SYNC))

        // Line that has words attached but syncType is LINE_SYNC: must still be rejected
        val fakeWordsLine = LyricLine(
            time = 145_000L,
            text = "Hold on",
            words = listOf(
                com.auralis.music.domain.model.LyricWord("Hold ", 145_000L),
                com.auralis.music.domain.model.LyricWord("on", 145_500L)
            )
        )
        assertNull("TIER_LINE must reject word timing even if words are present on LyricLine", resolveEffectiveWords(fakeWordsLine, SyncType.LINE_SYNC))

        // PLAIN sync must also resolve to null
        assertNull("PLAIN sync must resolve to null words", resolveEffectiveWords(line, SyncType.PLAIN))
    }

    @Test
    fun `genuine TIER_WORD lyrics retain their exact word timings`() {
        val genuineWords = listOf(
            com.auralis.music.domain.model.LyricWord("I ", 12_000L, 220L, isBackground = false),
            com.auralis.music.domain.model.LyricWord("said ", 12_260L, 300L, isBackground = false),
            com.auralis.music.domain.model.LyricWord("ooh", 12_600L, 480L, isBackground = false),
            com.auralis.music.domain.model.LyricWord("(yeah)", 13_100L, 350L, isBackground = true)
        )
        val line = LyricLine(
            time = 12_000L,
            text = "I said ooh (yeah)",
            words = genuineWords
        )

        val result = resolveEffectiveWords(line, SyncType.RICHSYNC)
        assertNotNull("TIER_WORD with genuine words must return non-null words", result)
        assertEquals("Word count must match exactly", genuineWords.size, result!!.size)
        assertEquals("Genuine word list must be returned verbatim", genuineWords, result)

        // Verify each word timestamp, duration, background flag is 100% preserved
        for (i in genuineWords.indices) {
            assertEquals("Word text must be unchanged", genuineWords[i].word, result[i].word)
            assertEquals("Word time must be unchanged", genuineWords[i].time, result[i].time)
            assertEquals("Word duration must be unchanged", genuineWords[i].duration, result[i].duration)
            assertEquals("Word isBackground must be unchanged", genuineWords[i].isBackground, result[i].isBackground)
        }
    }
}
