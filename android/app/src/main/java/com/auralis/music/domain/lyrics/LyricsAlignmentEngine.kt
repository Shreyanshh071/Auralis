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
     * Versions that fundamentally alter vocal phrasing, tempo, or song arrangement.
     */
    val TIMING_ALTERING_VERSIONS = setOf(
        "live", "acoustic", "unplugged", "remix", "mix", "club mix", "vip mix",
        "instrumental", "karaoke", "slowed", "slowed + reverb", "slowed and reverb",
        "sped up", "speed up", "nightcore", "orchestral", "piano version"
    )

    fun isTimingAlteringVersion(version: String?): Boolean {
        if (version == null) return false
        val lower = version.lowercase().trim()
        return TIMING_ALTERING_VERSIONS.any { lower.contains(it) }
    }

    /**
     * Checks if a title indicates a music video or visual take with extraneous intro/outro content.
     */
    fun isMusicVideoOrVisualizer(title: String?, channelTitle: String? = null): Boolean {
        if (title.isNullOrBlank()) return false
        val lowerTitle = title.lowercase()
        val isVideoTitle = lowerTitle.contains("official video") ||
                lowerTitle.contains("music video") ||
                lowerTitle.contains("official music video") ||
                lowerTitle.contains("(video)") ||
                lowerTitle.contains("[video]") ||
                lowerTitle.contains("official visualizer") ||
                lowerTitle.contains("lyric video")
        if (isVideoTitle) return true

        if (channelTitle != null && !channelTitle.endsWith(" - Topic", ignoreCase = true)) {
            val lowerChannel = channelTitle.lowercase()
            if (lowerChannel.contains("vevo") && (lowerTitle.contains("video") || lowerTitle.contains("visualizer"))) {
                return true
            }
        }
        return false
    }

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
     * Enhanced master evaluation considering version compatibility, video cut status, and audio duration.
     */
    fun evaluateMasterMatch(
        lyrics: LyricsData,
        playbackDurationMs: Long,
        playbackTitle: String? = null,
        candidateTitle: String? = null,
        playbackChannelTitle: String? = null
    ): MasterMatchStatus {
        // 1. Version incompatibility check
        if (!playbackTitle.isNullOrBlank()) {
            val qVersion = com.auralis.music.data.network.TitleCleaner.extractVersion(playbackTitle)
            val cTitle = candidateTitle?.takeIf { it.isNotBlank() } ?: lyrics.trackName ?: ""
            val cVersion = if (cTitle.isNotBlank()) com.auralis.music.data.network.TitleCleaner.extractVersion(cTitle) else null

            if (isTimingAlteringVersion(qVersion) || isTimingAlteringVersion(cVersion)) {
                if (qVersion == null && cVersion != null) {
                    return MasterMatchStatus.MASTER_MISMATCH
                }
                if (qVersion != null && cVersion == null) {
                    return MasterMatchStatus.MASTER_MISMATCH
                }
                if (qVersion != null && cVersion != null && !qVersion.equals(cVersion, ignoreCase = true)) {
                    return MasterMatchStatus.MASTER_MISMATCH
                }
            }

            // Music video check: if playback is a music video and duration delta exceeds EXACT_MATCH threshold (1.5s),
            // it's a video-edit cut mismatch rather than studio master
            if (isMusicVideoOrVisualizer(playbackTitle, playbackChannelTitle)) {
                val lyricsDurationMs = lyrics.effectiveDurationMs
                if (playbackDurationMs > 0L && lyricsDurationMs > 0L) {
                    val deltaMs = abs(playbackDurationMs - lyricsDurationMs)
                    if (deltaMs > EXACT_MATCH_MAX_DELTA_MS) {
                        return MasterMatchStatus.MASTER_MISMATCH
                    }
                }
            }
        }

        // 2. Pure duration delta check
        return evaluateMasterMatch(lyrics, playbackDurationMs)
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
     * Overload taking duration in seconds with title & channel context.
     */
    fun evaluateMasterMatch(
        lyrics: LyricsData,
        playbackDurationSec: Long?,
        playbackTitle: String? = null,
        candidateTitle: String? = null,
        playbackChannelTitle: String? = null
    ): MasterMatchStatus {
        if (playbackDurationSec == null || playbackDurationSec <= 0L) {
            return evaluateMasterMatch(lyrics, 0L, playbackTitle, candidateTitle, playbackChannelTitle)
        }
        return evaluateMasterMatch(lyrics, playbackDurationSec * 1000L, playbackTitle, candidateTitle, playbackChannelTitle)
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
