package com.auralis.music.data.parser

import com.auralis.music.domain.model.LyricsData

/**
 * Auralis always shows Indian-language lyrics in Latin letters (Hinglish), never Devanagari or
 * another Indic script. Sources answer the same song in different scripts (lrclib often Hinglish,
 * Apple-sourced word sync in Devanagari), and which one wins varied per play and even mid-song
 * as an upgrade landed, so the lyrics flipped between scripts.
 *
 * Preference order: a source written in Latin letters, or Apple's own transliteration shipped in
 * the TTML (applied by [TtmlParser]); then, only when neither exists, [toLatin]'s machine
 * transliteration, which ranks below both in the lyrics race.
 */
object HinglishScript {

    /** True when most sung lines are still in an Indic script. */
    fun isMostlyIndic(data: LyricsData): Boolean {
        val sung = data.lines.filter { !it.isInstrumental && it.text.isNotBlank() }
        if (sung.isEmpty()) return false
        return sung.count { IndicScriptNormalizer.containsIndicScript(it.text) } * 2 >= sung.size
    }

    /** Rewrites every Indic-script line (text and each timed word) in Latin letters; timing is untouched. */
    fun toLatin(data: LyricsData): LyricsData {
        if (data.lines.none { IndicScriptNormalizer.containsIndicScript(it.text) }) return data
        val lines = data.lines.map { line ->
            if (!IndicScriptNormalizer.containsIndicScript(line.text)) return@map line
            // Sentence case, like written Hinglish lyrics: the transliterator capitalises every word.
            val words = line.words?.mapIndexed { i, w ->
                val latin = if (IndicScriptNormalizer.containsIndicScript(w.word)) {
                    transliterateKeepingSpaces(w.word).lowercase()
                } else w.word
                w.copy(word = if (i == 0) capitalizeFirstLetter(latin) else latin)
            }
            // With word timing the line text is rebuilt from the words, so the two always agree.
            val text = words?.joinToString("") { it.word }?.trim()?.takeIf { it.isNotBlank() }
                ?: capitalizeFirstLetter(IndicScriptNormalizer.transliterateToReadableHinglish(line.text).lowercase())
            line.copy(text = text, words = words)
        }
        return data.copy(lines = lines, plainLyrics = lines.joinToString("\n") { it.text })
    }

    private fun capitalizeFirstLetter(text: String): String {
        val idx = text.indexOfFirst { it.isLetter() }
        return if (idx < 0) text else text.substring(0, idx) + text[idx].uppercaseChar() + text.substring(idx + 1)
    }

    private fun transliterateKeepingSpaces(token: String): String {
        if (!IndicScriptNormalizer.containsIndicScript(token)) return token
        val lead = token.takeWhile { it.isWhitespace() }
        val trail = token.takeLastWhile { it.isWhitespace() }
        val core = token.trim()
        return lead + IndicScriptNormalizer.transliterateToReadableHinglish(core) + trail
    }
}
