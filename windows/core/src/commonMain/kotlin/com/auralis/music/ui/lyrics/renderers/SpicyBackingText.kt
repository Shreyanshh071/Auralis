package com.auralis.music.ui.lyrics.renderers

import com.auralis.music.domain.model.LyricWord

/** Match displayed backing-vocal text to its source tokens without changing any clock. */
object SpicyBackingText {
    fun displayText(text: String, background: Boolean): String =
        if (background) text.trim().removeSurrounding("(", ")").trim() else text

    fun displayWords(text: String, words: List<LyricWord>?, background: Boolean): List<LyricWord>? {
        if (!background || words.isNullOrEmpty()) return words
        val joined = words.joinToString("") { it.word }.trim()
        // Strip only a source wrapper that the displayed line has also removed.
        // Parentheses inside the sung text remain literal text.
        if (joined.removePrefix("(").removeSuffix(")").filterNot(Char::isWhitespace) !=
            text.filterNot(Char::isWhitespace)) return words
        return trimBoundary(trimBoundary(words, opening = true), opening = false)
    }

    private fun trimBoundary(words: List<LyricWord>, opening: Boolean): List<LyricWord> {
        val index = if (opening) words.indexOfFirst { it.word.isNotBlank() }
            else words.indexOfLast { it.word.isNotBlank() }
        if (index < 0) return words
        val word = words[index]
        val at = if (opening) word.word.indexOfFirst { !it.isWhitespace() }
            else word.word.indexOfLast { !it.isWhitespace() }
        // A provider/repair pass may already have cleaned the parent token while
        // preserving wrapped syllables underneath it. Normalize each boundary
        // independently: the renderer uses those leaf timings, not just the parent.
        val cleaned = if (at >= 0 && word.word[at] == if (opening) '(' else ')')
            word.word.removeRange(at, at + 1) else word.word
        val parts = word.syllables?.let { trimBoundary(it, opening) }
        if (cleaned == word.word && parts === word.syllables) return words
        val replacement = word.copy(word = cleaned, syllables = parts)
        return words.toMutableList().also { it[index] = replacement }
    }
}
