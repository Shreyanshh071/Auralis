package com.auralis.music.ui.screens.lyrics

import com.auralis.music.domain.model.LyricLine
import com.auralis.music.domain.model.LyricWord

/**
 * Pure timing math for lyric rendering.
 *
 * Everything here is a direct function of the player position that the caller
 * passes in — the only permitted adjustment is the user's own manual
 * [offsetMs]. No constant nudges, no per-song compensation, no easing of time
 * itself: if the highlight is early or late, the fault is in the timestamps or
 * in the position clock, and it gets fixed there.
 */
object LyricsEngine {

    /**
     * Binary searches for the active lyric line index for [currentTimeMs] with the
     * user's manual [offsetMs] applied, in O(log N).
     */
    fun findActiveLyricIndex(lines: List<LyricLine>, currentTimeMs: Long, offsetMs: Long = 0): Int {
        if (lines.isEmpty()) return -1
        val adjustedTime = currentTimeMs + offsetMs

        // If before first line
        if (adjustedTime < lines[0].time) return -1

        var low = 0
        var high = lines.size - 1
        var result = -1

        while (low <= high) {
            val mid = (low + high) ushr 1
            if (lines[mid].time <= adjustedTime) {
                result = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }

        return result
    }

    /**
     * Fill progress [0.0f .. 1.0f] for a single word.
     *
     * A word only sweeps across a length the provider actually measured. When
     * [LyricWord.duration] is `null` the end of the word is unknown, so the word
     * flips from "not sung" to "sung" at its genuine start instead of sweeping
     * across an invented duration — an invented sweep would paint over vocal
     * rests, which is the exact bug this guards against.
     */
    fun calculateWordProgress(
        word: LyricWord,
        currentTimeMs: Long,
        offsetMs: Long = 0
    ): Float {
        val adjustedTime = currentTimeMs + offsetMs
        val start = word.time
        val duration = word.duration

        if (duration == null || duration <= 0L) {
            return if (adjustedTime >= start) 1.0f else 0.0f
        }

        val end = start + duration
        return when {
            adjustedTime < start -> 0.0f
            adjustedTime >= end -> 1.0f
            else -> ((adjustedTime - start).toFloat() / duration).coerceIn(0.0f, 1.0f)
        }
    }

    /**
     * A [LyricWord] mapped to its exact character range `[startIndex, endIndex)`
     * in the rendered line text.
     */
    data class WordRange(
        val word: LyricWord,
        val startIndex: Int,
        val endIndex: Int
    )

    /**
     * Maps each [LyricWord] to its character range `[startIndex, endIndex)` in [lineText].
     *
     * Computed once per lyric line when composition binds the line. Preserves original
     * grapheme clusters, matras, and glyph boundaries by referencing character indices
     * into the complete rendered line rather than slicing substrings.
     */
    fun mapWordsToLineSpans(lineText: String, words: List<LyricWord>?): List<WordRange> {
        if (words.isNullOrEmpty() || lineText.isEmpty()) return emptyList()
        val result = ArrayList<WordRange>(words.size)
        var cursor = 0

        for (word in words) {
            val token = word.word
            if (token.isEmpty()) continue

            // 1. Direct match from current cursor
            var idx = lineText.indexOf(token, cursor)
            var matchLength = token.length

            // 2. If trailing space was trimmed at line boundary, try trimmed end
            if (idx == -1) {
                val trimmedEnd = token.trimEnd()
                if (trimmedEnd.isNotEmpty()) {
                    idx = lineText.indexOf(trimmedEnd, cursor)
                    if (idx != -1) {
                        matchLength = trimmedEnd.length
                    }
                }
            }

            // 3. If still not found, try full trim
            if (idx == -1) {
                val fullTrim = token.trim()
                if (fullTrim.isNotEmpty()) {
                    idx = lineText.indexOf(fullTrim, cursor)
                    if (idx != -1) {
                        matchLength = fullTrim.length
                    }
                }
            }

            // 4. Fallback search from start if line formatting rearranged spaces
            if (idx == -1) {
                val fullTrim = token.trim()
                if (fullTrim.isNotEmpty()) {
                    idx = lineText.indexOf(fullTrim, 0)
                    if (idx != -1) {
                        matchLength = fullTrim.length
                    }
                }
            }

            if (idx != -1) {
                val start = idx
                val end = (idx + matchLength).coerceAtMost(lineText.length)
                if (start < end) {
                    result.add(WordRange(word = word, startIndex = start, endIndex = end))
                    cursor = end
                }
            }
        }
        return result
    }
}
