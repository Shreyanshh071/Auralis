package com.auralis.music.ui.lyrics.renderers

import com.auralis.music.domain.model.LyricWord
import com.auralis.music.ui.screens.lyrics.LyricsEngine
import org.junit.Assert.*
import org.junit.Test

class SpicyBackingBoundaryTest {
    @Test fun cleanedParentsWithWrappedSyllablesKeepFirstAndLastWords() {
        val first = LyricWord("Be ", 1000, 422, true,
            listOf(LyricWord("(Be ", 1000, 422, true)))
        val last = LyricWord("baby", 3160, 724, true,
            listOf(LyricWord("baby)", 3160, 724, true)))
        val source = listOf(first, LyricWord("my, ", 1422, 953, true),
            LyricWord("be ", 2375, 299, true), LyricWord("my ", 2674, 486, true), last)
        val text = "Be my, be my baby"
        val unnormalized = LyricsEngine.mapWordsToLineSpans(text, source.flatMap { it.syllables ?: listOf(it) })
        assertEquals("Wrapped leaf tokens reproduce the vanished first/last words", 3, unnormalized.size)
        val normalized = SpicyBackingText.displayWords(text, source, true)!!
        val leaves = normalized.flatMap { it.syllables ?: listOf(it) }
        val spans = LyricsEngine.mapWordsToLineSpans(text, leaves)
        assertEquals(5, spans.size)
        assertEquals(0, spans.first().startIndex)
        assertEquals(text.length, spans.last().endIndex)
        assertEquals(source.flatMap { it.syllables ?: listOf(it) }.map { it.time to it.duration }, leaves.map { it.time to it.duration })
        assertEquals("(Be ", source.first().syllables!!.first().word)
        assertEquals("baby)", source.last().syllables!!.last().word)
    }

    @Test fun oneSidedWrappersAndMultipleNestedLayersAreNormalizedWithoutEatingLiteralParentheses() {
        for ((text, tokens) in listOf(
            "Stay here" to listOf("(Stay ", "here"),
            "Stay here" to listOf("Stay ", "here)"),
            "say (yes) softly" to listOf("(say ", "(yes) ", "softly)"))) {
            val source = tokens.mapIndexed { i, token -> LyricWord(token, i * 1000L, 800, true) }
            val result = SpicyBackingText.displayWords(text, source, true)!!
            assertEquals(text, result.joinToString("") { it.word })
            assertEquals(source.map { it.time to it.duration }, result.map { it.time to it.duration })
        }
        val nested = listOf(LyricWord("Oh", 1000, 2000, true, listOf(
            LyricWord("Oh", 1000, 2000, true, listOf(LyricWord("(Oh)", 1000, 2000, true))))))
        val result = SpicyBackingText.displayWords("Oh", nested, true)!!
        assertEquals("Oh", result.single().syllables!!.single().syllables!!.single().word)
        val wrong = listOf(LyricWord("(something else)", 1000, 1000, true))
        assertSame(wrong, SpicyBackingText.displayWords("Oh", wrong, true))
        assertSame(nested, SpicyBackingText.displayWords("Oh", nested, false))
    }
}
