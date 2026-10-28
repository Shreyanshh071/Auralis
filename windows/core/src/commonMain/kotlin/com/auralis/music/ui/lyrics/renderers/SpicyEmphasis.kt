package com.auralis.music.ui.lyrics.renderers

/** A shaped text unit, with offsets into its supplied syllable, never a guessed syllable. */
data class SpicyEmphasizedLetter(val from: Int, val to: Int, val time: SpicyLetterTime)

/** Regular Spicy's IsLetterCapable + SplitLetters + Emphasize selection.
 * Every source syllable is considered independently, regardless of its spelling.
 */
object SpicyEmphasis {
    private val graphemes = Regex("\\X")
    private val joinsNext = setOf('्', '্', '੍', '્', '୍', '்', '్', '್', '്', '්', '္', '្', '\u200D')

    fun letters(text: String, startMs: Long, endMs: Long?): List<SpicyEmphasizedLetter>? {
        if (endMs == null || endMs - startMs < 1000L || text.isBlank()) return null
        if (text.any { Character.getDirectionality(it) in listOf(
                Character.DIRECTIONALITY_RIGHT_TO_LEFT, Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC) }) return null
        val units = mutableListOf<IntRange>()
        for (match in graphemes.findAll(text)) {
            val previous = units.lastOrNull()
            if (previous != null && text[previous.last] in joinsNext && match.value.isNotBlank()) {
                units[units.lastIndex] = previous.first..match.range.last
            } else units += match.range
        }
        if (units.isEmpty()) return null
        val finish = maxOf(endMs - SpicyLyricsMotion.LETTER_LEAD_MS, startMs + (endMs - startMs) / 2)
        return units.mapIndexed { index, range ->
            SpicyEmphasizedLetter(range.first, range.last + 1, SpicyLetterTime(
                startMs + (finish - startMs) * index / units.size,
                startMs + (finish - startMs) * (index + 1) / units.size))
        }
    }
}
