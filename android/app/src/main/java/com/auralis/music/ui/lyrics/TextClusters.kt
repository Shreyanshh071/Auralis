package com.auralis.music.ui.lyrics

import java.text.BreakIterator

/**
 * Viramas: the Indic "join with the next consonant" marks. A consonant carrying one forms a
 * conjunct with the following consonant (श् + क → श्क in "इश्क").
 */
private val VIRAMAS = setOf(
    '्', // Devanagari (Hindi, Marathi, Nepali)
    '্', // Bengali, Assamese
    '੍', // Gurmukhi (Punjabi)
    '્', // Gujarati
    '୍', // Oriya
    '்', // Tamil
    '్', // Telugu
    '್', // Kannada
    '്', // Malayalam
    '්', // Sinhala
    '္', // Myanmar
    '្'  // Khmer
)

/** Indic letters must be shaped with their surrounding text, not drawn as isolated glyphs. */
internal fun String.requiresWholeRunShaping(): Boolean = any { char ->
    when (Character.UnicodeScript.of(char.code)) {
        Character.UnicodeScript.DEVANAGARI,
        Character.UnicodeScript.BENGALI,
        Character.UnicodeScript.GURMUKHI,
        Character.UnicodeScript.GUJARATI,
        Character.UnicodeScript.ORIYA,
        Character.UnicodeScript.TAMIL,
        Character.UnicodeScript.TELUGU,
        Character.UnicodeScript.KANNADA,
        Character.UnicodeScript.MALAYALAM,
        Character.UnicodeScript.SINHALA,
        Character.UnicodeScript.MYANMAR,
        Character.UnicodeScript.KHMER -> true
        else -> false
    }
}

/**
 * Splits text into the smallest pieces that can each be measured and drawn on their own without
 * breaking how the font shapes them.
 *
 * Grapheme clusters are almost that, but `BreakIterator` (on the Android versions we support)
 * puts a virama-ended consonant and the consonant it joins in separate clusters. Drawn apart,
 * "इश्क" rendered as "इश्" with a bare virama and a stray "क" placed inside a conjunct that no
 * longer existed. So a cluster ending in a virama is kept together with the one after it.
 */
internal fun String.toShapingClusters(): List<String> {
    if (isEmpty()) return emptyList()
    val clusters = mutableListOf<String>()
    val it = BreakIterator.getCharacterInstance()
    it.setText(this)
    var start = it.first()
    var end = it.next()
    while (end != BreakIterator.DONE) {
        val cluster = substring(start, end)
        val previous = clusters.lastOrNull()
        if (previous != null && previous.last() in VIRAMAS && cluster.first().isLetter()) {
            clusters[clusters.lastIndex] = previous + cluster
        } else {
            clusters += cluster
        }
        start = end
        end = it.next()
    }
    return clusters
}
