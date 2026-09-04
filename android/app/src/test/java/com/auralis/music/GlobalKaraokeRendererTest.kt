package com.auralis.music

import com.auralis.music.data.parser.TtmlParser
import com.auralis.music.data.parser.WordTiming
import com.auralis.music.domain.model.LyricWord
import com.auralis.music.domain.model.LyricsProvider
import com.auralis.music.ui.screens.lyrics.LyricsEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Comprehensive test suite verifying the global reference-parity karaoke highlighting renderer:
 * 1. Long words
 * 2. Short words (200ms - 400ms)
 * 3. Consecutive short words
 * 4. Contiguous syllables merging
 * 5. Genuine rests preservation (zero creeping/bleeding across silence)
 * 6. Varied word durations in a single line
 * 7. Background vocals preservation
 * 8. Multiline lyrics span mapping
 * 9. Unicode and complex script graphemes (Hindi matras, Arabic RTL, CJK characters)
 * 10. Strict mathematical timing boundaries (no premature start, no late finish, zero multiplier)
 * 11. Regression tests for Radiohead - Creep and Ravyn Lenae - Love Me Not
 */
class GlobalKaraokeRendererTest {

    // ── 1. LONG WORDS ─────────────────────────────────────────────────────────

    @Test
    fun testLongWordProgressionAndBounds() {
        val longWord = LyricWord(word = "extraordinary ", time = 10000L, duration = 2500L)

        // Before start: strictly 0.0
        assertEquals(0.0f, LyricsEngine.calculateWordProgress(longWord, 9999L), 0.0001f)
        // At exact start: 0.0
        assertEquals(0.0f, LyricsEngine.calculateWordProgress(longWord, 10000L), 0.0001f)
        // 25% (625ms into 2500ms): 0.25
        assertEquals(0.25f, LyricsEngine.calculateWordProgress(longWord, 10625L), 0.0001f)
        // 50% (1250ms): 0.50
        assertEquals(0.50f, LyricsEngine.calculateWordProgress(longWord, 11250L), 0.0001f)
        // 75% (1875ms): 0.75
        assertEquals(0.75f, LyricsEngine.calculateWordProgress(longWord, 11875L), 0.0001f)
        // Exact finish (12500ms): 1.0
        assertEquals(1.0f, LyricsEngine.calculateWordProgress(longWord, 12500L), 0.0001f)
        // After finish: strictly 1.0 (never exceeds 1.0)
        assertEquals(1.0f, LyricsEngine.calculateWordProgress(longWord, 13000L), 0.0001f)
    }

    // ── 2. SHORT WORDS (200ms - 400ms) ────────────────────────────────────────

    @Test
    fun testShortWordProgressionAndBounds() {
        val shortWord = LyricWord(word = "go ", time = 5000L, duration = 200L)

        assertEquals(0.0f, LyricsEngine.calculateWordProgress(shortWord, 4999L), 0.0001f)
        assertEquals(0.0f, LyricsEngine.calculateWordProgress(shortWord, 5000L), 0.0001f)
        assertEquals(0.5f, LyricsEngine.calculateWordProgress(shortWord, 5100L), 0.0001f)
        assertEquals(1.0f, LyricsEngine.calculateWordProgress(shortWord, 5200L), 0.0001f)
        assertEquals(1.0f, LyricsEngine.calculateWordProgress(shortWord, 5250L), 0.0001f)
    }

    // ── 3. CONSECUTIVE SHORT WORDS ─────────────────────────────────────────────

    @Test
    fun testConsecutiveShortWordsRemainSeparateWithoutDurationStretching() {
        val words = listOf(
            LyricWord(word = "I'll ", time = 18989L, duration = 245L),
            LyricWord(word = "meet ", time = 19234L, duration = 272L),
            LyricWord(word = "you ", time = 19506L, duration = 368L)
        )

        // Contiguous-syllable merge check: must NOT merge because each has trailing whitespace
        val afterMerge = WordTiming.mergeContiguousSyllables(words)
        assertNotNull(afterMerge)
        assertEquals(3, afterMerge!!.size)

        // Verify each word has strictly unaltered duration
        assertEquals(245L, afterMerge[0].duration)
        assertEquals(272L, afterMerge[1].duration)
        assertEquals(368L, afterMerge[2].duration)

        // Verify sequential progress: at 19100ms, word 0 is active, word 1 and 2 are 0
        val t = 19100L
        val p0 = LyricsEngine.calculateWordProgress(afterMerge[0], t)
        val p1 = LyricsEngine.calculateWordProgress(afterMerge[1], t)
        val p2 = LyricsEngine.calculateWordProgress(afterMerge[2], t)

        assertTrue("Word 0 must be in progress", p0 in 0.001f..0.999f)
        assertEquals("Word 1 must not have started", 0.0f, p1, 0.0001f)
        assertEquals("Word 2 must not have started", 0.0f, p2, 0.0001f)
    }

    // ── 4. CONTIGUOUS SYLLABLES MERGING ───────────────────────────────────────

    @Test
    fun testContiguousSyllablesMergeAcrossMultipleLanguages() {
        // English: "beauti" + "ful "
        val english = listOf(
            LyricWord(word = "beauti", time = 1000L, duration = 300L),
            LyricWord(word = "ful ", time = 1300L, duration = 400L)
        )
        val mergedEn = WordTiming.mergeContiguousSyllables(english)
        assertEquals(1, mergedEn!!.size)
        assertEquals("beautiful ", mergedEn[0].word)
        assertEquals(1000L, mergedEn[0].time)
        assertEquals(700L, mergedEn[0].duration)

        // German: "Wunder" + "bar "
        val german = listOf(
            LyricWord(word = "Wunder", time = 2000L, duration = 250L),
            LyricWord(word = "bar ", time = 2250L, duration = 350L)
        )
        val mergedDe = WordTiming.mergeContiguousSyllables(german)
        assertEquals(1, mergedDe!!.size)
        assertEquals("Wunderbar ", mergedDe[0].word)
        assertEquals(2000L, mergedDe[0].time)
        assertEquals(600L, mergedDe[0].duration)
    }

    // ── 5. GENUINE RESTS PRESERVATION ─────────────────────────────────────────

    @Test
    fun testGenuineRestsPreserveVocalSilenceWithoutBleeding() {
        // Word 1 ends at 2000ms. Word 2 begins at 3500ms (1500ms vocal rest / pause)
        val word1 = LyricWord(word = "breathe ", time = 1000L, duration = 1000L) // 1000..2000
        val word2 = LyricWord(word = "again ", time = 3500L, duration = 1000L)   // 3500..4500

        // At t = 2000ms: word 1 finishes
        assertEquals(1.0f, LyricsEngine.calculateWordProgress(word1, 2000L), 0.0001f)
        assertEquals(0.0f, LyricsEngine.calculateWordProgress(word2, 2000L), 0.0001f)

        // At t = 2750ms (dead center of 1500ms rest):
        // Word 1 is finished (1.0f), Word 2 has NOT started (0.0f).
        // Neither word is actively sweeping (0 < p < 1). The highlight sits completely motionless!
        val p1DuringRest = LyricsEngine.calculateWordProgress(word1, 2750L)
        val p2DuringRest = LyricsEngine.calculateWordProgress(word2, 2750L)
        assertEquals(1.0f, p1DuringRest, 0.0001f)
        assertEquals(0.0f, p2DuringRest, 0.0001f)

        // At t = 3499ms (1ms before Word 2 starts): Word 2 still strictly 0.0f
        assertEquals(0.0f, LyricsEngine.calculateWordProgress(word2, 3499L), 0.0001f)
    }

    // ── 6. VARIED DURATIONS IN SINGLE LINE ───────────────────────────────────

    @Test
    fun testVariedDurationsInSingleLine() {
        val lineWords = listOf(
            LyricWord(word = "A ", time = 1000L, duration = 150L),         // short
            LyricWord(word = "quick ", time = 1200L, duration = 450L),     // medium
            LyricWord(word = "conversation ", time = 1700L, duration = 1200L), // long
            LyricWord(word = "now", time = 3000L, duration = 300L)         // short
        )

        val lineText = "A quick conversation now"
        val spans = LyricsEngine.mapWordsToLineSpans(lineText, lineWords)
        assertEquals(4, spans.size)

        assertEquals("A ", lineText.substring(spans[0].startIndex, spans[0].endIndex))
        assertEquals("quick ", lineText.substring(spans[1].startIndex, spans[1].endIndex))
        assertEquals("conversation ", lineText.substring(spans[2].startIndex, spans[2].endIndex))
        assertEquals("now", lineText.substring(spans[3].startIndex, spans[3].endIndex))
    }

    // ── 7. BACKGROUND VOCALS PRESERVATION ─────────────────────────────────────

    @Test
    fun testBackgroundVocalsPreservation() {
        val lead = LyricWord(word = "Never ", time = 1000L, duration = 500L, isBackground = false)
        val bg = LyricWord(word = "(never) ", time = 1200L, duration = 500L, isBackground = true)

        val merged = WordTiming.mergeContiguousSyllables(listOf(lead, bg))
        assertEquals("Lead and background must never merge together", 2, merged!!.size)
        assertFalse(merged[0].isBackground)
        assertTrue(merged[1].isBackground)
    }

    // ── 8. MULTILINE LYRICS SPAN MAPPING ──────────────────────────────────────

    @Test
    fun testMultilineLyricsSpanMapping() {
        val lineText = "This is a very long lyric line that wraps across multiple display lines in the player view"
        val words = lineText.split(" ").mapIndexed { idx, token ->
            val trailing = if (idx == lineText.split(" ").lastIndex) "" else " "
            LyricWord(word = token + trailing, time = 1000L + idx * 500L, duration = 450L)
        }

        val spans = LyricsEngine.mapWordsToLineSpans(lineText, words)
        assertEquals(words.size, spans.size)

        // Spans must be strictly monotonically increasing and non-overlapping
        for (i in 1 until spans.size) {
            assertTrue(
                "Span $i start (${spans[i].startIndex}) must be >= previous end (${spans[i - 1].endIndex})",
                spans[i].startIndex >= spans[i - 1].endIndex
            )
        }
    }

    // ── 9. UNICODE AND COMPLEX SCRIPTS ────────────────────────────────────────

    @Test
    fun testUnicodeAndComplexScriptGraphemes() {
        // Devanagari (Hindi) with matras and conjuncts
        val hindiText = "नमस्ते दुनिया कैसे हो"
        val hindiWords = listOf(
            LyricWord(word = "नमस्ते ", time = 1000L, duration = 600L),
            LyricWord(word = "दुनिया ", time = 1700L, duration = 700L),
            LyricWord(word = "कैसे ", time = 2500L, duration = 500L),
            LyricWord(word = "हो", time = 3100L, duration = 400L)
        )
        val hindiSpans = LyricsEngine.mapWordsToLineSpans(hindiText, hindiWords)
        assertEquals(4, hindiSpans.size)
        assertEquals("नमस्ते ", hindiText.substring(hindiSpans[0].startIndex, hindiSpans[0].endIndex))
        assertEquals("दुनिया ", hindiText.substring(hindiSpans[1].startIndex, hindiSpans[1].endIndex))

        // Arabic RTL
        val arabicText = "مرحبا بالعالم"
        val arabicWords = listOf(
            LyricWord(word = "مرحبا ", time = 1000L, duration = 500L),
            LyricWord(word = "بالعالم", time = 1600L, duration = 600L)
        )
        val arabicSpans = LyricsEngine.mapWordsToLineSpans(arabicText, arabicWords)
        assertEquals(2, arabicSpans.size)
        assertEquals("مرحبا ", arabicText.substring(arabicSpans[0].startIndex, arabicSpans[0].endIndex))
        assertEquals("بالعالم", arabicText.substring(arabicSpans[1].startIndex, arabicSpans[1].endIndex))

        // CJK: must never merge
        val cjkWords = listOf(
            LyricWord(word = "今", time = 1000L, duration = 300L),
            LyricWord(word = "日", time = 1300L, duration = 300L),
            LyricWord(word = "は", time = 1600L, duration = 300L)
        )
        val mergedCjk = WordTiming.mergeContiguousSyllables(cjkWords)
        assertEquals(3, mergedCjk!!.size)
    }

    // ── 10. STRICT MATHEMATICAL TIMING BOUNDS ─────────────────────────────────

    @Test
    fun testProgressionNeverFinishesLaterThanProviderWordEnd() {
        val word = LyricWord(word = "exact", time = 5000L, duration = 300L)

        // At end: 1.0f
        assertEquals(1.0f, LyricsEngine.calculateWordProgress(word, 5300L), 0.0001f)
        // 1ms past end: strictly 1.0f
        assertEquals(1.0f, LyricsEngine.calculateWordProgress(word, 5301L), 0.0001f)
        // 100ms past end: strictly 1.0f
        assertEquals(1.0f, LyricsEngine.calculateWordProgress(word, 5400L), 0.0001f)
        // Never finishes later than provider word end
    }

    @Test
    fun testProgressionNeverStartsBeforeProviderWordStart() {
        val word = LyricWord(word = "exact", time = 5000L, duration = 300L)

        // 1ms before start: strictly 0.0f
        assertEquals(0.0f, LyricsEngine.calculateWordProgress(word, 4999L), 0.0001f)
        // 100ms before start: strictly 0.0f
        assertEquals(0.0f, LyricsEngine.calculateWordProgress(word, 4900L), 0.0001f)
    }

    // ── 11. REGRESSION TESTS (CREEP & LOVE ME NOT) ─────────────────────────────

    @Test
    fun testCreepRegressionBeautiful940msPreserved() {
        val creepFile = File("c:/Users/shrey/OneDrive/Desktop/Auralis/creep_raw.ttml")
        assertTrue(creepFile.exists())

        val lyrics = TtmlParser.parse(creepFile.readText(), LyricsProvider.BETTER_LYRICS)
        val line = lyrics.lines.first { it.text.contains("beautiful", ignoreCase = true) }
        val beautifulWord = line.words!!.first { it.word.contains("beautiful", ignoreCase = true) }

        assertEquals(45626L, beautifulWord.time)
        assertEquals(940L, beautifulWord.duration)

        // At 45626ms: exactly 0.0f
        assertEquals(0.0f, LyricsEngine.calculateWordProgress(beautifulWord, 45626L), 0.0001f)
        // At 46096ms (midpoint 470ms): exactly 0.5f
        assertEquals(0.5f, LyricsEngine.calculateWordProgress(beautifulWord, 46096L), 0.0001f)
        // At 46566ms: exactly 1.0f
        assertEquals(1.0f, LyricsEngine.calculateWordProgress(beautifulWord, 46566L), 0.0001f)
    }

    @Test
    fun testLoveMeNotRegressionSeparateWordsPreserved() {
        val lmnFile = File("C:/Users/shrey/.gemini/antigravity-ide/brain/11fe7950-e4f6-477b-b08d-8884078404a2/scratch/love_me_not.ttml")
        assertTrue(lmnFile.exists())

        val lyrics = TtmlParser.parse(lmnFile.readText(), LyricsProvider.BETTER_LYRICS)
        val line = lyrics.lines.first { it.text.contains("meet you", ignoreCase = true) }
        val words = line.words!!

        assertEquals(11, words.size)
        val ill = words[6]
        val meet = words[7]
        val you = words[8]

        assertEquals("I'll ", ill.word)
        assertEquals(18989L, ill.time)
        assertEquals(245L, ill.duration)

        assertEquals("meet ", meet.word)
        assertEquals(19234L, meet.time)
        assertEquals(272L, meet.duration)

        assertEquals("you ", you.word)
        assertEquals(19506L, you.time)
        assertEquals(368L, you.duration)
    }

    // ── 12. WRAPPED MULTI-LINE LYRIC ROW ISOLATION ────────────────────────────

    @Test
    fun testWrappedMultiLineLyricRowIsolation() {
        val lineText = "I need you right now baby"
        val words = listOf(
            LyricWord(word = "I ", time = 1000L, duration = 200L),
            LyricWord(word = "need ", time = 1200L, duration = 300L),
            LyricWord(word = "you ", time = 1500L, duration = 250L),
            LyricWord(word = "right ", time = 1750L, duration = 400L),
            LyricWord(word = "now ", time = 2150L, duration = 300L),
            LyricWord(word = "baby", time = 2450L, duration = 500L)
        )

        val spans = LyricsEngine.mapWordsToLineSpans(lineText, words)
        assertEquals(6, spans.size)

        val rightSpan = spans[3]
        val nowSpan = spans[4]
        val babySpan = spans[5]

        // 1. Verify character ranges: right is [11, 17), now is [17, 21), baby is [21, 25)
        assertEquals(11, rightSpan.startIndex)
        assertEquals(17, rightSpan.endIndex)
        assertEquals(17, nowSpan.startIndex)
        assertEquals(21, nowSpan.endIndex)
        assertEquals(21, babySpan.startIndex)
        assertEquals(25, babySpan.endIndex)

        // 2. Verify trimmed glyph ranges: right's glyph end is 16 ('t'), which does NOT reach now (17)
        val rightGlyphEnd = rightSpan.startIndex + rightSpan.word.word.trimEnd().length
        assertEquals(16, rightGlyphEnd)
        assertTrue("Right glyph range must end before now starts", rightGlyphEnd <= nowSpan.startIndex)

        val nowGlyphEnd = nowSpan.startIndex + nowSpan.word.word.trimEnd().length
        assertEquals(20, nowGlyphEnd)
        assertTrue("Now glyph range must end before baby starts", nowGlyphEnd <= babySpan.startIndex)

        // 3. Verify independent word progression:
        // While "right" is in progress (e.g. at 1950ms):
        val tRight = 1950L
        val pRight = LyricsEngine.calculateWordProgress(rightSpan.word, tRight)
        val pNow = LyricsEngine.calculateWordProgress(nowSpan.word, tRight)
        val pBaby = LyricsEngine.calculateWordProgress(babySpan.word, tRight)

        assertTrue("Right must be actively sweeping", pRight in 0.01f..0.99f)
        assertEquals("Now must not have started while right is playing", 0.0f, pNow, 0.0001f)
        assertEquals("Baby must not have started while right is playing", 0.0f, pBaby, 0.0001f)

        // When "right" finishes and "now" starts (e.g. at 2250ms):
        val tNow = 2250L
        val pRightFinished = LyricsEngine.calculateWordProgress(rightSpan.word, tNow)
        val pNowActive = LyricsEngine.calculateWordProgress(nowSpan.word, tNow)
        val pBabyWaiting = LyricsEngine.calculateWordProgress(babySpan.word, tNow)

        assertEquals("Right must be fully completed", 1.0f, pRightFinished, 0.0001f)
        assertTrue("Now must be actively sweeping independently", pNowActive in 0.01f..0.99f)
        assertEquals("Baby must not have started while now is playing", 0.0f, pBabyWaiting, 0.0001f)

        // When "baby" is active (e.g. at 2600ms):
        val tBaby = 2600L
        val pNowFinished = LyricsEngine.calculateWordProgress(nowSpan.word, tBaby)
        val pBabyActive = LyricsEngine.calculateWordProgress(babySpan.word, tBaby)

        assertEquals("Now must be fully completed", 1.0f, pNowFinished, 0.0001f)
        assertTrue("Baby must be actively sweeping independently", pBabyActive in 0.01f..0.99f)
    }
}
