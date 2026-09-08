package com.auralis.music

import com.auralis.music.data.parser.TtmlParser
import com.auralis.music.data.parser.WordTiming
import com.auralis.music.domain.model.LyricWord
import com.auralis.music.domain.model.LyricsProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SyllableMergeUnitTest {

    // ── 1. CONTIGUOUS SYLLABLES MERGING INTO ONE WORD ──────────────────────────

    @Test
    fun testContiguousSyllablesMergeIntoSingleWord() {
        val input = listOf(
            LyricWord(word = "beauti", time = 45626L, duration = 256L),
            LyricWord(word = "ful ", time = 45882L, duration = 684L)
        )

        val merged = WordTiming.mergeContiguousSyllables(input)
        assertNotNull(merged)
        assertEquals("Two contiguous syllables must merge into 1 word", 1, merged!!.size)
        assertEquals("beautiful ", merged[0].word)
        assertEquals("Start time must be first syllable start", 45626L, merged[0].time)
        assertEquals("Duration must be exact combined interval (46566 - 45626)", 940L, merged[0].duration)
    }

    @Test
    fun testThreeContiguousSyllablesMerge() {
        val input = listOf(
            LyricWord(word = "un", time = 1000L, duration = 200L),
            LyricWord(word = "break", time = 1200L, duration = 300L),
            LyricWord(word = "able ", time = 1500L, duration = 500L)
        )

        val merged = WordTiming.mergeContiguousSyllables(input)
        assertNotNull(merged)
        assertEquals(1, merged!!.size)
        assertEquals("unbreakable ", merged[0].word)
        assertEquals(1000L, merged[0].time)
        assertEquals(1000L, merged[0].duration) // 2000 - 1000
    }

    // ── 2. WHITESPACE-SEPARATED WORDS REMAIN SEPARATE ──────────────────────────

    @Test
    fun testWhitespaceSeparatedWordsRemainSeparate() {
        val input = listOf(
            LyricWord(word = "I'll ", time = 18989L, duration = 245L),
            LyricWord(word = "meet ", time = 19234L, duration = 272L),
            LyricWord(word = "you ", time = 19506L, duration = 368L)
        )

        val merged = WordTiming.mergeContiguousSyllables(input)
        assertNotNull(merged)
        assertEquals("Words with trailing space must remain separate", 3, merged!!.size)
        assertEquals("I'll ", merged[0].word)
        assertEquals(18989L, merged[0].time)
        assertEquals(245L, merged[0].duration)

        assertEquals("meet ", merged[1].word)
        assertEquals(19234L, merged[1].time)
        assertEquals(272L, merged[1].duration)

        assertEquals("you ", merged[2].word)
        assertEquals(19506L, merged[2].time)
        assertEquals(368L, merged[2].duration)
    }

    // ── 3. PUNCTUATION AND HYPHEN BEHAVIOR ────────────────────────────────────

    @Test
    fun testHyphenatedSyllablesMerge() {
        // "well-" has no trailing space, so it merges with "known "
        val input = listOf(
            LyricWord(word = "well-", time = 2000L, duration = 300L),
            LyricWord(word = "known ", time = 2300L, duration = 400L)
        )

        val merged = WordTiming.mergeContiguousSyllables(input)
        assertNotNull(merged)
        assertEquals(1, merged!!.size)
        assertEquals("well-known ", merged[0].word)
        assertEquals(2000L, merged[0].time)
        assertEquals(700L, merged[0].duration)
    }

    @Test
    fun testPunctuationWithSpaceDoesNotMerge() {
        // "now, " has a trailing space, so it must not merge with next word "I "
        val input = listOf(
            LyricWord(word = "now, ", time = 17405L, duration = 405L),
            LyricWord(word = "I ", time = 17938L, duration = 275L)
        )

        val merged = WordTiming.mergeContiguousSyllables(input)
        assertNotNull(merged)
        assertEquals(2, merged!!.size)
        assertEquals("now, ", merged[0].word)
        assertEquals("I ", merged[1].word)
    }

    // ── 4. EXACT COMBINED START/END TIMING ─────────────────────────────────────

    @Test
    fun testExactCombinedStartAndEndTimingCalculation() {
        val input = listOf(
            LyricWord(word = "con", time = 5000L, duration = 150L),      // 5000 -> 5150
            LyricWord(word = "nec", time = 5150L, duration = 250L),      // 5150 -> 5400
            LyricWord(word = "tion ", time = 5400L, duration = 600L)     // 5400 -> 6000
        )

        val merged = WordTiming.mergeContiguousSyllables(input)
        assertNotNull(merged)
        assertEquals(1, merged!!.size)
        val word = merged[0]
        assertEquals("connection ", word.word)
        assertEquals("Start must match first syllable start exactly", 5000L, word.time)
        assertEquals("Duration must span from 5000L to 6000L (1000L)", 1000L, word.duration)
    }

    @Test
    fun testContiguousSyllablesWithInterveningGapPreservesTotalInterval() {
        // Even if there is a tiny acoustic gap between syllables, interval is [start1, end2]
        val input = listOf(
            LyricWord(word = "star", time = 1000L, duration = 300L),    // 1000 -> 1300
            LyricWord(word = "light ", time = 1350L, duration = 450L)   // 1350 -> 1800 (gap 50ms)
        )

        val merged = WordTiming.mergeContiguousSyllables(input)
        assertNotNull(merged)
        assertEquals(1, merged!!.size)
        assertEquals("starlight ", merged[0].word)
        assertEquals(1000L, merged[0].time)
        assertEquals(800L, merged[0].duration) // 1800 - 1000 = 800ms
    }

    // ── 5. EXISTING BACKGROUND-VOCAL BEHAVIOR ─────────────────────────────────

    @Test
    fun testBackgroundAndLeadSyllablesDoNotMergeTogether() {
        val input = listOf(
            LyricWord(word = "lead", time = 1000L, duration = 500L, isBackground = false),
            LyricWord(word = "(bg)", time = 1500L, duration = 500L, isBackground = true)
        )

        val merged = WordTiming.mergeContiguousSyllables(input)
        assertNotNull(merged)
        assertEquals("Lead and background words must not merge", 2, merged!!.size)
        assertFalse(merged[0].isBackground)
        assertTrue(merged[1].isBackground)
    }

    // ── 6. CJK PRESERVATION (CHARACTER-LEVEL TIMING KEPT UNMERGED) ────────────

    @Test
    fun testCjkCharactersRemainUnmerged() {
        val cjkInput = listOf(
            LyricWord(word = "我", time = 1000L, duration = 500L),
            LyricWord(word = "爱", time = 1500L, duration = 500L),
            LyricWord(word = "你", time = 2000L, duration = 500L)
        )

        val merged = WordTiming.mergeContiguousSyllables(cjkInput)
        assertNotNull(merged)
        assertEquals("CJK characters must not be merged; character timing preserved", 3, merged!!.size)
        assertEquals("我", merged[0].word)
        assertEquals("爱", merged[1].word)
        assertEquals("你", merged[2].word)
    }

    // ── 7. REAL-WORLD TTML: RADIOHEAD - CREEP ("beautiful" 940ms) ─────────────

    @Test
    fun testRealCreepTtmlMergesBeautifulIntoSingle940msWord() {
        val creepFile = File("c:/Users/shrey/OneDrive/Desktop/Auralis/creep_raw.ttml")
        assertTrue("creep_raw.ttml must exist", creepFile.exists())

        val lyrics = TtmlParser.parse(creepFile.readText(), LyricsProvider.BETTER_LYRICS)
        val line = lyrics.lines.firstOrNull { it.text.contains("beautiful", ignoreCase = true) }
        assertNotNull("Line with 'beautiful' must be found", line)
        assertEquals("In a beautiful world", line!!.text)

        val words = line.words
        assertNotNull("Line must have word timing", words)
        assertEquals("Must have 4 words: 'In', 'a', 'beautiful', 'world'", 4, words!!.size)

        val inWord = words[0]
        val aWord = words[1]
        val beautifulWord = words[2]
        val worldWord = words[3]

        assertEquals("In ", inWord.word)
        assertEquals(45146L, inWord.time)
        assertEquals(321L, inWord.duration)

        assertEquals("a ", aWord.word)
        assertEquals(45467L, aWord.time)
        assertEquals(159L, aWord.duration)

        assertEquals("beautiful ", beautifulWord.word)
        assertEquals("beautiful start must be 45626ms", 45626L, beautifulWord.time)
        assertEquals("beautiful duration must be exact combined 940ms", 940L, beautifulWord.duration)

        assertEquals("world", worldWord.word)
        assertEquals(46567L, worldWord.time)
        assertEquals(1443L, worldWord.duration)
    }

    // ── 8. REAL-WORLD TTML: RAVYN LENAE - LOVE ME NOT (SEPARATE WORDS KEPT) ───

    @Test
    fun testRealLoveMeNotTtmlKeepsSeparateWordsSeparate() {
        val lmnFile = File("C:/Users/shrey/.gemini/antigravity-ide/brain/11fe7950-e4f6-477b-b08d-8884078404a2/scratch/love_me_not.ttml")
        assertTrue("love_me_not.ttml must exist", lmnFile.exists())

        val lyrics = TtmlParser.parse(lmnFile.readText(), LyricsProvider.BETTER_LYRICS)
        val line = lyrics.lines.firstOrNull { it.text.contains("meet you", ignoreCase = true) }
        assertNotNull("Line 0 of Love Me Not must be found", line)
        assertEquals("See, right now, I need you, I'll meet you somewhere now", line!!.text)

        val words = line.words
        assertNotNull("Line must have word timing", words)
        assertEquals("All 11 genuine words in Love Me Not must remain separate", 11, words!!.size)

        val expectedWords = listOf(
            "See, ", "right ", "now, ", "I ", "need ", "you, ",
            "I'll ", "meet ", "you ", "somewhere ", "now"
        )
        for (i in expectedWords.indices) {
            assertEquals("Word $i text mismatch", expectedWords[i], words[i].word)
        }

        // Verify exact timings of Phrase 2 words are untouched
        val ill = words[6]
        assertEquals("I'll ", ill.word)
        assertEquals(18989L, ill.time)
        assertEquals(245L, ill.duration)

        val meet = words[7]
        assertEquals("meet ", meet.word)
        assertEquals(19234L, meet.time)
        assertEquals(272L, meet.duration)

        val you = words[8]
        assertEquals("you ", you.word)
        assertEquals(19506L, you.time)
        assertEquals(368L, you.duration)
    }

    // ── 9. FAKE PLASTIC TREES WORD SPACING REGRESSION ─────────────────────────

    @Test
    fun testFakePlasticTreesMultiWordPreservation() {
        val input = listOf(
            LyricWord(word = "A", time = 3346L, duration = 309L),
            LyricWord(word = "green", time = 3655L, duration = 311L),
            LyricWord(word = "plastic", time = 3966L, duration = 785L),
            LyricWord(word = "watering ", time = 4751L, duration = 1529L),
            LyricWord(word = "can ", time = 6280L, duration = 903L)
        )

        val merged = WordTiming.mergeContiguousSyllables(input)
        assertNotNull(merged)
        assertEquals("Must have 5 separate words with proper spacing preserved", 5, merged!!.size)
        assertEquals("A ", merged[0].word)
        assertEquals("green ", merged[1].word)
        assertEquals("plastic ", merged[2].word)
        assertEquals("watering ", merged[3].word)
        assertEquals("can ", merged[4].word)

        // Raw TTML snippet simulation
        val ttmlXml = """
            <tt xmlns="http://www.w3.org/ns/ttml">
            <body>
            <div>
            <p begin="3.346" end="7.183">
            <span begin="3.346" end="3.655">A</span> <span begin="3.655" end="3.966">green</span> <span begin="3.966" end="4.751">plastic</span><span begin="4.751" end="6.280">watering</span> <span begin="6.280" end="7.183">can</span>
            </p>
            </div>
            </body>
            </tt>
        """.trimIndent()

        val parsed = TtmlParser.parse(ttmlXml, LyricsProvider.BETTER_LYRICS)
        assertEquals(1, parsed.lines.size)
        val line = parsed.lines[0]
        assertEquals("A green plastic watering can", line.text)
        assertNotNull(line.words)
        assertEquals(5, line.words!!.size)
        assertEquals("plastic ", line.words!![2].word)
        assertEquals("watering ", line.words!![3].word)
    }

    // ── 10. TITLE MATCHER SUBTITLE & SOUNDTRACK MATCHING ──────────────────────

    @Test
    fun testTitleMatcherWithSoundtrackAndSubtitles() {
        assertTrue(
            "Sunflower must match Sunflower (Spider-Man: Into the Spider-Verse)",
            com.auralis.music.data.parser.LyricsMatcher.isTitleMatching(
                "Sunflower",
                "Sunflower (Spider-Man: Into the Spider-Verse)"
            )
        )
        assertTrue(
            "Starboy must match Starboy (feat. Daft Punk)",
            com.auralis.music.data.parser.LyricsMatcher.isTitleMatching(
                "Starboy",
                "Starboy (feat. Daft Punk)"
            )
        )
        assertTrue(
            "Bitter Sweet Symphony must match Bitter Sweet Symphony (Radio Edit)",
            com.auralis.music.data.parser.LyricsMatcher.isTitleMatching(
                "Bitter Sweet Symphony",
                "Bitter Sweet Symphony (Radio Edit)"
            )
        )
    }

    // ── 11. UNSPACED SYLLABLE MERGING REGRESSION (EYES WITHOUT A FACE) ──────

    @Test
    fun testUnspacedSyllableFragmentsMergeIntoCompleteWords() {
        // "ea" + "sy " -> "easy "
        val easyInput = listOf(
            LyricWord(word = "ea", time = 49248L, duration = 650L),
            LyricWord(word = "sy ", time = 49898L, duration = 377L)
        )
        val mergedEasy = WordTiming.mergeContiguousSyllables(easyInput)
        assertNotNull(mergedEasy)
        assertEquals(1, mergedEasy!!.size)
        assertEquals("easy ", mergedEasy[0].word)
        assertEquals(49248L, mergedEasy[0].time)
        assertEquals(1027L, mergedEasy[0].duration)

        // "vi" + "sage)" -> "visage)"
        val visageInput = listOf(
            LyricWord(word = "vi", time = 58334L, duration = 736L),
            LyricWord(word = "sage)", time = 59070L, duration = 1322L)
        )
        val mergedVisage = WordTiming.mergeContiguousSyllables(visageInput)
        assertNotNull(mergedVisage)
        assertEquals(1, mergedVisage!!.size)
        assertEquals("visage)", mergedVisage[0].word)

        // "de" + "ceive " -> "deceive "
        val deceiveInput = listOf(
            LyricWord(word = "de", time = 40000L, duration = 400L),
            LyricWord(word = "ceive ", time = 40400L, duration = 600L)
        )
        val mergedDeceive = WordTiming.mergeContiguousSyllables(deceiveInput)
        assertNotNull(mergedDeceive)
        assertEquals(1, mergedDeceive!!.size)
        assertEquals("deceive ", mergedDeceive[0].word)

        // "re" + "lease " -> "release "
        val releaseInput = listOf(
            LyricWord(word = "re", time = 50000L, duration = 300L),
            LyricWord(word = "lease ", time = 50300L, duration = 700L)
        )
        val mergedRelease = WordTiming.mergeContiguousSyllables(releaseInput)
        assertNotNull(mergedRelease)
        assertEquals(1, mergedRelease!!.size)
        assertEquals("release ", mergedRelease[0].word)
    }

    @Test
    fun testEyesWithoutAFaceTtmlSnippetParsesWithoutSplitWords() {
        val ttml = """
            <tt xmlns="http://www.w3.org/ns/ttml">
            <body>
            <div>
            <p begin="48.000" end="51.000">
            <span begin="48.000" end="49.000">It's </span><span begin="49.248" end="49.898">ea</span><span begin="49.898" end="50.275">sy </span><span begin="50.275" end="50.500">to </span><span begin="50.500" end="51.000">tease</span>
            </p>
            <p begin="57.000" end="61.000">
            <span begin="57.000" end="57.500">(Les </span><span begin="57.500" end="57.800">yeux </span><span begin="57.800" end="58.334">sans </span><span begin="58.334" end="59.070">vi</span><span begin="59.070" end="60.392">sage)</span>
            </p>
            </div>
            </body>
            </tt>
        """.trimIndent()

        val parsed = TtmlParser.parse(ttml, LyricsProvider.BETTER_LYRICS)
        assertEquals(2, parsed.lines.size)

        val line1 = parsed.lines[0]
        assertEquals("It's easy to tease", line1.text)
        assertNotNull(line1.words)
        assertEquals(4, line1.words!!.size)
        assertEquals("It's ", line1.words!![0].word)
        assertEquals("easy ", line1.words!![1].word)
        assertEquals("to ", line1.words!![2].word)
        assertEquals("tease", line1.words!![3].word)

        val line2 = parsed.lines[1]
        assertEquals("(Les yeux sans visage)", line2.text)
        assertNotNull(line2.words)
        assertEquals(4, line2.words!!.size)
        assertEquals("(Les ", line2.words!![0].word)
        assertEquals("yeux ", line2.words!![1].word)
        assertEquals("sans ", line2.words!![2].word)
        assertEquals("visage)", line2.words!![3].word)
    }
}

