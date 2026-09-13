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
     * Returns all lyric line indices that are actively singing at [currentTimeMs]
     * with the user's manual [offsetMs] applied.
     *
     * Supports multiple independently timed lines being active simultaneously
     * (e.g. duet lines, lead vocal + background vocal, overlapping vocal agents).
     *
     * Strict timing contract:
     * - A line is active if and only if [adjustedTime] falls within its genuine
     *   interval: `adjustedTime in line.time..lineEndMs`.
     * - [lineEndMs] prefers the provider's genuine [LyricLine.effectiveEndTime]
     *   (from explicit line end or last word end).
     * - For line-synced lyrics with no end timestamps, the line lasts until the
     *   next subsequent line start.
     */
    fun findActiveLyricIndices(
        lines: List<LyricLine>,
        currentTimeMs: Long,
        offsetMs: Long = 0
    ): Set<Int> {
        if (lines.isEmpty()) return emptySet()
        val adjustedTime = currentTimeMs + offsetMs
        if (adjustedTime < lines[0].time) return emptySet()

        val active = mutableSetOf<Int>()
        val hasWordTimings = lines.any { it.hasWordTiming }

        for (index in lines.indices) {
            val line = lines[index]
            if (line.time > adjustedTime) {
                // Lines are ordered chronologically by start time.
                // Any line starting after adjustedTime cannot be active yet.
                break
            }

            val lineEndMs: Long = line.effectiveEndTime
                ?: if (!line.words.isNullOrEmpty()) {
                    line.words.last().endTime ?: line.words.last().time
                } else {
                    // Fallback for line-synced lyrics with no explicit end time:
                    // transition when the next non-simultaneous line begins
                    var nextStart = Long.MAX_VALUE
                    for (j in index + 1 until lines.size) {
                        if (lines[j].time > line.time) {
                            nextStart = lines[j].time
                            break
                        }
                    }
                    nextStart
                }

            if (adjustedTime >= line.time && adjustedTime <= lineEndMs) {
                active.add(index)
            }
        }

        // For standard line-synced lyrics with NO word timing and NO explicit end times,
        // prevent runaway overlap of sequential lines (while preserving simultaneous lines
        // that share timestamps or distinct agents/background).
        if (!hasWordTimings && lines.all { it.effectiveEndTime == null } && active.size > 1) {
            val mainActive = active.filter { !lines[it].isBackground }
            if (mainActive.size > 1) {
                val agents = mainActive.mapNotNull { lines[it].agent }.toSet()
                if (agents.size <= 1) {
                    val maxTime = mainActive.maxOf { lines[it].time }
                    active.removeAll { it in mainActive && lines[it].time < maxTime }
                }
            }
        }

        return active
    }

    /**
     * Finds the primary active lyric line index for [currentTimeMs] with the
     * user's manual [offsetMs] applied.
     *
     * When multiple lines are simultaneously active (e.g. duet or lead + background),
     * this returns the primary lead vocal line to act as a stable scroll anchor
     * without causing list jumping.
     *
     * When in a vocal rest between lines, returns the last completed line index
     * (or -1 if before the first line).
     */
    fun findActiveLyricIndex(lines: List<LyricLine>, currentTimeMs: Long, offsetMs: Long = 0): Int {
        if (lines.isEmpty()) return -1
        val activeSet = findActiveLyricIndices(lines, currentTimeMs, offsetMs)
        if (activeSet.isNotEmpty()) {
            // Anchor scroll on lead vocal / primary line (v1 or non-background)
            return activeSet.firstOrNull { !lines[it].isBackground && lines[it].agent != "v2" }
                ?: activeSet.firstOrNull { !lines[it].isBackground }
                ?: activeSet.first()
        }

        val adjustedTime = currentTimeMs + offsetMs
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
     * Identifies all lyric line indices that should be visually highlighted/active at [currentTimeMs]
     * with the user's manual [offsetMs] applied.
     *
     * In contrast to [findActiveLyricIndices] (which strictly tracks who is singing for audio synchronization),
     * visual highlighting ownership guarantees:
     * - The current lyric line remains highlighted across vocal rests until the next lyric line starts.
     * - Does NOT use a line's endTime as the condition to clear highlight before the next line starts.
     * - All lines actively singing within their genuine singing interval (duets, overlapping vocals, background vocals)
     *   are highlighted simultaneously.
     * - If playback is before the first line, returns an empty set.
     * - When all lines of the song have ended, returns an empty set (the UI maintains the last line).
     */
    fun findVisualActiveLineIndices(
        lines: List<LyricLine>,
        currentTimeMs: Long,
        offsetMs: Long = 0
    ): Set<Int> {
        if (lines.isEmpty()) return emptySet()
        val adjustedTime = currentTimeMs + offsetMs
        if (adjustedTime < lines[0].time) return emptySet()

        val active = mutableSetOf<Int>()

        for (index in lines.indices) {
            val line = lines[index]
            if (line.time > adjustedTime) break

            val effEnd = line.effectiveEndTime
            val expEnd = line.endTime
            val lastWordEnd = line.words?.lastOrNull()?.let { it.endTime ?: (it.time + (it.duration ?: 0L)) }
            val singingEnd = effEnd ?: expEnd ?: lastWordEnd

            val nextStart: Long? = if (line.isBackground) {
                (index + 1 until lines.size).firstOrNull { lines[it].time > line.time }?.let { lines[it].time }
            } else {
                (index + 1 until lines.size).firstOrNull { lines[it].time > line.time && !lines[it].isBackground }?.let { lines[it].time }
                    ?: (index + 1 until lines.size).firstOrNull { lines[it].time > line.time }?.let { lines[it].time }
            }

            val isLineActive = when {
                // Line-synced lyric without explicit end time: active until next line starts
                singingEnd == null -> {
                    val end = nextStart ?: Long.MAX_VALUE
                    adjustedTime >= line.time && adjustedTime < end
                }
                // Multi-singer / duet: singing interval extends past the next line's start
                nextStart != null && singingEnd > nextStart -> {
                    adjustedTime >= line.time && adjustedTime <= singingEnd
                }
                // Standard main lyric line: gap bridging keeps line highlighted across vocal rests until next line begins.
                // For extended instrumental breaks (>35s), release highlight after singing ends (+ grace period) instead of holding for minutes.
                nextStart != null && !line.isBackground -> {
                    val isExtendedGap = singingEnd != null && (nextStart - singingEnd) > 35_000L
                    if (isExtendedGap) {
                        adjustedTime >= line.time && adjustedTime <= (singingEnd + 3_000L)
                    } else {
                        adjustedTime >= line.time && adjustedTime < nextStart
                    }
                }
                // Background vocal with next line ahead: active strictly while singing
                nextStart != null && line.isBackground -> {
                    adjustedTime >= line.time && adjustedTime <= singingEnd
                }
                // Final line of song: active through its singing end
                else -> {
                    adjustedTime >= line.time && adjustedTime <= singingEnd
                }
            }

            if (isLineActive) {
                active.add(index)
            }
        }

        return active
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
