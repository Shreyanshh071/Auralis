package com.auralis.music.domain.lyrics

import com.auralis.music.domain.model.LyricsData
import kotlin.math.abs

/**
 * Diagnostic alignment classification between candidate lyrics and the playback audio stream.
 *
 * Thresholds:
 * - [EXACT_MATCH]: |delta| <= 1.5s (1500ms). Identical master cut; minor differences are encoder padding.
 * - [COMPATIBLE_OFFSET]: |delta| > 1.5s and <= 3.5s (3500ms). Same vocal master with outro fade or intro padding differences.
 * - [MASTER_MISMATCH]: |delta| > 3.5s. Definite version mismatch (e.g. video edit vs album release).
 */
enum class MasterMatchStatus {
    EXACT_MATCH,
    COMPATIBLE_OFFSET,
    MASTER_MISMATCH
}

/**
 * Provider-to-Playback Alignment Engine (Phase 4A).
 *
 * Responsibilities:
 * 1. Evaluates master match between candidate lyrics and playback audio duration.
 * 2. Provides non-destructive intro alignment based on verified metadata.
 * 3. Guarantees 100% immutability of input lyrics and preserves genuine word durations.
 */
object LyricsAlignmentEngine {

    const val EXACT_MATCH_MAX_DELTA_MS = 1500L
    const val COMPATIBLE_OFFSET_MAX_DELTA_MS = 3500L

    /**
     * Evaluates whether the candidate lyrics match the duration of the playback audio stream.
     *
     * If [playbackDurationMs] <= 0 or lyrics duration cannot be determined,
     * returns [MasterMatchStatus.EXACT_MATCH] as a safe default.
     */
    fun evaluateMasterMatch(
        lyrics: LyricsData,
        playbackDurationMs: Long
    ): MasterMatchStatus {
        if (playbackDurationMs <= 0L) return MasterMatchStatus.EXACT_MATCH
        val lyricsDurationMs = lyrics.effectiveDurationMs
        if (lyricsDurationMs <= 0L) return MasterMatchStatus.EXACT_MATCH

        val deltaMs = abs(playbackDurationMs - lyricsDurationMs)
        return when {
            deltaMs <= EXACT_MATCH_MAX_DELTA_MS -> MasterMatchStatus.EXACT_MATCH
            deltaMs <= COMPATIBLE_OFFSET_MAX_DELTA_MS -> MasterMatchStatus.COMPATIBLE_OFFSET
            else -> MasterMatchStatus.MASTER_MISMATCH
        }
    }

    /**
     * Overload taking duration in seconds (e.g. from Track.duration).
     */
    fun evaluateMasterMatch(
        lyrics: LyricsData,
        playbackDurationSec: Long?
    ): MasterMatchStatus {
        if (playbackDurationSec == null || playbackDurationSec <= 0L) return MasterMatchStatus.EXACT_MATCH
        return evaluateMasterMatch(lyrics, playbackDurationSec * 1000L)
    }

    /**
     * Non-destructively aligns lyrics timestamps to playback audio stream.
     *
     * Invariants:
     * - Original [lyrics] is NEVER mutated in place.
     * - Word durations (d_word) are NEVER scaled, stretched, or compressed.
     * - When [audioLeadingSilenceMs] is provided and differs from provider [LyricsData.leadingSilenceMs],
     *   calculates safe intro offset: delta = audioLeadingSilenceMs - providerLeadingSilenceMs.
     * - Bounded strictly to [-2000ms, 2000ms] to prevent runaway shifts.
     * - If delta == 0, returns [lyrics] untouched.
     * - If [MasterMatchStatus.MASTER_MISMATCH] is detected, skips shift to prevent compounding error.
     */
    fun alignToPlayback(
        lyrics: LyricsData,
        playbackDurationMs: Long,
        audioLeadingSilenceMs: Long? = null
    ): LyricsData {
        if (lyrics.lines.isEmpty()) return lyrics
        val masterMatch = evaluateMasterMatch(lyrics, playbackDurationMs)
        if (masterMatch == MasterMatchStatus.MASTER_MISMATCH) {
            return lyrics
        }

        val providerLeadingSilence = lyrics.leadingSilenceMs
        val offsetMs = if (audioLeadingSilenceMs != null && providerLeadingSilence != null) {
            (audioLeadingSilenceMs - providerLeadingSilence).coerceIn(-2000L, 2000L)
        } else {
            0L
        }

        if (offsetMs == 0L) {
            return lyrics
        }

        val alignedLines = lyrics.lines.map { line ->
            val shiftedTime = (line.time + offsetMs).coerceAtLeast(0L)
            val shiftedWords = line.words?.map { word ->
                word.copy(time = (word.time + offsetMs).coerceAtLeast(0L))
            }
            line.copy(time = shiftedTime, words = shiftedWords)
        }

        return lyrics.copy(lines = alignedLines)
    }
}
