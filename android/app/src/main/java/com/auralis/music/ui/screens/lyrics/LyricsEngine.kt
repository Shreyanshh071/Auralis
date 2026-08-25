package com.auralis.music.ui.screens.lyrics

import com.auralis.music.domain.model.LyricLine
import com.auralis.music.domain.model.LyricWord

object LyricsEngine {

    /**
     * Binary searches for the active lyric line index for [currentTimeMs] with [offsetMs] compensation in O(log N).
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
     * Calculates the fill progress [0.0f .. 1.0f] for a word based on current playback time.
     */
    fun calculateWordProgress(
        word: LyricWord,
        currentTimeMs: Long,
        offsetMs: Long = 0,
        defaultDurationMs: Long = 400
    ): Float {
        val adjustedTime = currentTimeMs + offsetMs
        val start = word.time
        val duration = word.duration?.coerceAtLeast(50) ?: defaultDurationMs
        val end = start + duration

        return when {
            adjustedTime < start -> 0.0f
            adjustedTime >= end -> 1.0f
            else -> ((adjustedTime - start).toFloat() / duration).coerceIn(0.0f, 1.0f)
        }
    }
}
