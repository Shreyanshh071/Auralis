package com.auralis.music.data.parser

import com.auralis.music.domain.model.LyricsData

object LyricsValidator {

    private val CORRUPT_QUESTION_MARK_REGEX = Regex("""\?{2,}|\?\s+\?""")
    private val PLACEHOLDER_REGEX = Regex(
        """(?i)\b(lyrics\s*(not\s*available|coming\s*soon|to\s*be\s*added)|add\s*lyrics|lorem\s*ipsum|text\s*not\s*found|lyrics\s*uploaded\s*by|synced\s*by|transcribed\s*by)\b"""
    )

    /**
     * Checks if a lyrics candidate contains corrupt encoding (e.g. "??? ?? ???"),
     * placeholder mock text, or broken character data.
     */
    fun isCorruptOrInvalid(lyricsData: LyricsData?): Boolean {
        if (lyricsData == null || lyricsData.lines.isEmpty()) return true

        val nonInstLines = lyricsData.lines.filter { !it.isInstrumental && it.text.isNotBlank() }
        if (nonInstLines.isEmpty()) return false // purely instrumental is fine

        val fullText = nonInstLines.joinToString("\n") { it.text }

        // 1. Multiple consecutive question marks (e.g. "??? ?? ????? ??..." from failed UTF-8 Devanagari uploads)
        if (CORRUPT_QUESTION_MARK_REGEX.containsMatchIn(fullText)) {
            return true
        }

        // 2. High ratio of question marks or replacement characters (indicates Unicode decoding failure)
        val questionMarkCount = fullText.count { it == '?' || it == '\uFFFD' }
        val letterOrDigitCount = fullText.count { it.isLetter() || it.isDigit() }
        if (letterOrDigitCount > 0) {
            val ratio = questionMarkCount.toDouble() / (letterOrDigitCount + questionMarkCount)
            if (ratio > 0.04) {
                return true
            }
        }

        // 3. Known placeholder / spam lines
        if (PLACEHOLDER_REGEX.containsMatchIn(fullText)) {
            return true
        }

        // 4. Incomplete 1-line or 2-line synced lyrics for standard length tracks
        if (lyricsData.lines.size <= 2 && nonInstLines.size <= 2) {
            val text = nonInstLines.firstOrNull()?.text?.lowercase() ?: ""
            if (text.contains("instrumental") || text.contains("music") || text.length < 5) {
                // allow short markers if intended
            } else {
                return true
            }
        }

        return false
    }
}
