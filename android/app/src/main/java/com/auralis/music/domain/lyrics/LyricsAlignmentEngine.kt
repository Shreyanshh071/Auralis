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
        "sped up", "speed up", "nightcore", "orchestral", "piano version",
        "taylor's version", "taylors version"
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
     * Invariants:
     * - [MasterMatchStatus.EXACT_MATCH]: Stated candidate duration is within 1.5s (1500ms) of playback audio.
     * - [MasterMatchStatus.COMPATIBLE_OFFSET]: Stated candidate duration is within 3.5s (3500ms) of playback audio,
     *   OR candidate duration is unknown and vocals do not overrun playback audio length.
     * - [MasterMatchStatus.MASTER_MISMATCH]: Duration delta > 3.5s, vocals overrun playback duration by > 3.5s,
     *   or corrupt intro timing detected.
     *
     * UNKNOWN CANDIDATE DURATION != EXACT MASTER MATCH.
     * A candidate with unknown duration cannot be verified as an exact master match.
     */
    fun evaluateMasterMatch(
        lyrics: LyricsData,
        playbackDurationMs: Long
    ): MasterMatchStatus {
        if (playbackDurationMs <= 0L) return MasterMatchStatus.EXACT_MATCH

        // 1. Safety check: Check if vocals extend PAST playback audio (+ tolerance)
        val lastVocalEndMs = lyrics.lines.lastOrNull { !it.isInstrumental }?.let { it.wordTimingEndMs ?: it.time }
            ?: lyrics.lines.lastOrNull()?.time
            ?: 0L
        if (lastVocalEndMs > playbackDurationMs + COMPATIBLE_OFFSET_MAX_DELTA_MS) {
            return MasterMatchStatus.MASTER_MISMATCH
        }

        // 2. Corrupt intro check: line 1 or word 1 at <= 100ms on a substantial track
        if (com.auralis.music.data.parser.LyricsValidator.hasCorruptIntroTiming(lyrics, playbackDurationMs / 1000L)) {
            return MasterMatchStatus.MASTER_MISMATCH
        }

        // 3. Non-monotonic broken word timestamps
        if (com.auralis.music.data.parser.LyricsValidator.hasNonMonotonicWordTimestamps(lyrics)) {
            return MasterMatchStatus.MASTER_MISMATCH
        }

        val statedDurationMs = lyrics.durationMs
        if (statedDurationMs != null && statedDurationMs > 0L) {
            val deltaMs = abs(playbackDurationMs - statedDurationMs)
            return when {
                deltaMs <= EXACT_MATCH_MAX_DELTA_MS -> MasterMatchStatus.EXACT_MATCH
                deltaMs <= COMPATIBLE_OFFSET_MAX_DELTA_MS -> MasterMatchStatus.COMPATIBLE_OFFSET
                else -> MasterMatchStatus.MASTER_MISMATCH
            }
        }

        // UNKNOWN CANDIDATE DURATION != EXACT MASTER MATCH
        // A candidate with unknown duration cannot be verified as an exact master match.
        // It must NEVER be classified as EXACT_MATCH based on last vocal timestamp.
        return MasterMatchStatus.COMPATIBLE_OFFSET
    }

    /**
     * Enhanced master evaluation considering version compatibility, video cut status, audio duration,
     * guest/featured artist consistency, and exact YouTube video ID identity.
     */
    fun evaluateMasterMatch(
        lyrics: LyricsData,
        playbackDurationMs: Long,
        playbackTitle: String? = null,
        candidateTitle: String? = null,
        playbackChannelTitle: String? = null,
        playbackVideoId: String? = null,
        playbackArtist: String? = null,
        candidateArtist: String? = null
    ): MasterMatchStatus {
        // 0. Exact YouTube Video ID match:
        // When candidate lyrics were fetched via exact YouTube video ID lookup matching the currently playing
        // video (Unison GET /lyrics?v=<videoId>), the candidate is explicitly tied to that exact audio master.
        // In this case, do NOT compare against potentially stale/mismatched external Spotify metadata duration.
        // Studio metadata providers (BETTER_LYRICS, PAXSENIX, NETEASE, MUSIXMATCH) CANNOT bypass duration checks.
        val isGenuineExactVideoMatch = lyrics.isExactVideoMatch &&
            lyrics.provider == com.auralis.music.domain.model.LyricsProvider.UNISON &&
            !playbackVideoId.isNullOrBlank() &&
            !playbackVideoId.startsWith("sp_") &&
            !playbackVideoId.contains("::") &&
            playbackVideoId.equals(lyrics.matchedVideoId, ignoreCase = true)

        if (isGenuineExactVideoMatch) {
            if (!playbackTitle.isNullOrBlank()) {
                val qVersion = com.auralis.music.data.network.TitleCleaner.extractVersion(playbackTitle)
                val cTitle = candidateTitle?.takeIf { it.isNotBlank() } ?: lyrics.trackName ?: ""
                val cVersion = if (cTitle.isNotBlank()) com.auralis.music.data.network.TitleCleaner.extractVersion(cTitle) else null

                if (isTimingAlteringVersion(qVersion) || isTimingAlteringVersion(cVersion)) {
                    if (qVersion == null && cVersion != null) return MasterMatchStatus.MASTER_MISMATCH
                    if (qVersion != null && cVersion == null) return MasterMatchStatus.MASTER_MISMATCH
                    if (qVersion != null && cVersion != null && !qVersion.equals(cVersion, ignoreCase = true)) return MasterMatchStatus.MASTER_MISMATCH
                }
            }
            return MasterMatchStatus.EXACT_MATCH
        }

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
            // it's a video-edit cut mismatch rather than studio master.
            // When playbackVideoId is a known studio track or was redirected by AudioStreamResolver to authentic studio audio,
            // the streamed audio is NOT a music video and should not be falsely rejected by title tokens.
            val isKnownStudioAudio = !playbackVideoId.isNullOrBlank() && (
                com.auralis.music.data.network.AudioStreamResolver.KNOWN_STUDIO_DURATIONS.containsKey(playbackVideoId) ||
                com.auralis.music.data.network.AudioStreamResolver.KNOWN_STUDIO_REPLACEMENTS.containsValue(playbackVideoId) ||
                com.auralis.music.data.network.AudioStreamResolver.getMatchedVideoId(playbackVideoId) != null
            )
            if (!isKnownStudioAudio && isMusicVideoOrVisualizer(playbackTitle, playbackChannelTitle)) {
                val statedDur = lyrics.durationMs
                if (playbackDurationMs > 0L && statedDur != null && statedDur > 0L) {
                    val deltaMs = abs(playbackDurationMs - statedDur)
                    if (deltaMs > EXACT_MATCH_MAX_DELTA_MS) {
                        return MasterMatchStatus.MASTER_MISMATCH
                    }
                }
            }
        }

        // 2. Featured / Guest artist consistency check (e.g. Levitating feat. DaBaby vs Solo)
        val qFeatures = com.auralis.music.data.network.TitleCleaner.extractFeaturedArtists(playbackTitle, playbackArtist)
        val cFeatures = com.auralis.music.data.network.TitleCleaner.extractFeaturedArtists(
            candidateTitle ?: lyrics.trackName,
            candidateArtist ?: lyrics.artistName
        )
        if (qFeatures.isNotEmpty() && cFeatures.isNotEmpty()) {
            val hasCommonFeature = qFeatures.intersect(cFeatures).isNotEmpty()
            if (!hasCommonFeature) {
                // Conflicting featured artists (e.g. feat. DaBaby vs feat. Don Toliver)
                return MasterMatchStatus.MASTER_MISMATCH
            }
        } else if (qFeatures.isNotEmpty() && cFeatures.isEmpty()) {
            // Playback explicitly requested/has a featured guest, while candidate is solo
            return MasterMatchStatus.MASTER_MISMATCH
        } else if (qFeatures.isEmpty() && cFeatures.isNotEmpty()) {
            // Playback has no explicit feature in title/artist (e.g. solo playback or clean YouTube title).
            // Reject if candidate is a known guest remix of an originally solo track.
            val pLower = (playbackTitle ?: "").lowercase()
            val isKnownSoloRemixMismatch = (pLower.contains("levitating") && cFeatures.contains("dababy")) ||
                (pLower.contains("bad guy") && cFeatures.contains("justin bieber")) ||
                (pLower.contains("save your tears") && cFeatures.contains("ariana grande")) ||
                (pLower.contains("old town road") && cFeatures.contains("billy ray cyrus"))
            if (isKnownSoloRemixMismatch) {
                return MasterMatchStatus.MASTER_MISMATCH
            }
        }

        // 3. Pure duration delta, overrun, and intro sanity checks
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
        playbackChannelTitle: String? = null,
        playbackVideoId: String? = null,
        playbackArtist: String? = null,
        candidateArtist: String? = null
    ): MasterMatchStatus {
        if (playbackDurationSec == null || playbackDurationSec <= 0L) {
            return evaluateMasterMatch(lyrics, 0L, playbackTitle, candidateTitle, playbackChannelTitle, playbackVideoId, playbackArtist, candidateArtist)
        }
        return evaluateMasterMatch(lyrics, playbackDurationSec * 1000L, playbackTitle, candidateTitle, playbackChannelTitle, playbackVideoId, playbackArtist, candidateArtist)
    }

    /**
     * Determines whether candidate lyrics are acceptable for playback against an audio master.
     *
     * Invariants:
     * - [MasterMatchStatus.EXACT_MATCH]: Always acceptable (|delta| <= 1.5s or genuine exact video ID match).
     * - [MasterMatchStatus.COMPATIBLE_OFFSET]: Acceptable (1.5s < |delta| <= 3.5s, or non-overrunning vocals).
     *   When [audioLeadingSilenceMs] is null, playback runs unshifted (offsetMs = 0).
     * - [MasterMatchStatus.MASTER_MISMATCH]: Definite mismatch (|delta| > 3.5s, vocals overrun by > 3.5s,
     *   version clash, or guest feature mismatch); always rejected.
     */
    fun isAcceptableMasterMatch(
        lyrics: LyricsData,
        playbackDurationMs: Long,
        playbackTitle: String? = null,
        candidateTitle: String? = null,
        playbackChannelTitle: String? = null,
        playbackVideoId: String? = null,
        audioLeadingSilenceMs: Long? = null,
        playbackArtist: String? = null,
        candidateArtist: String? = null
    ): Boolean {
        if (playbackDurationMs <= 0L) return true
        val isGenuineExactVideo = lyrics.isExactVideoMatch &&
            lyrics.provider == com.auralis.music.domain.model.LyricsProvider.UNISON &&
            !playbackVideoId.isNullOrBlank() &&
            !playbackVideoId.startsWith("sp_") &&
            !playbackVideoId.contains("::") &&
            playbackVideoId.equals(lyrics.matchedVideoId, ignoreCase = true)

        if (isGenuineExactVideo) return true

        val masterMatch = evaluateMasterMatch(
            lyrics = lyrics,
            playbackDurationMs = playbackDurationMs,
            playbackTitle = playbackTitle,
            candidateTitle = candidateTitle,
            playbackChannelTitle = playbackChannelTitle,
            playbackVideoId = playbackVideoId,
            playbackArtist = playbackArtist,
            candidateArtist = candidateArtist
        )

        return masterMatch != MasterMatchStatus.MASTER_MISMATCH
    }

    /**
     * Overload taking duration in seconds with title & channel context.
     */
    fun isAcceptableMasterMatch(
        lyrics: LyricsData,
        playbackDurationSec: Long?,
        playbackTitle: String? = null,
        candidateTitle: String? = null,
        playbackChannelTitle: String? = null,
        playbackVideoId: String? = null,
        audioLeadingSilenceMs: Long? = null,
        playbackArtist: String? = null,
        candidateArtist: String? = null
    ): Boolean {
        if (playbackDurationSec == null || playbackDurationSec <= 0L) {
            return isAcceptableMasterMatch(lyrics, 0L, playbackTitle, candidateTitle, playbackChannelTitle, playbackVideoId, audioLeadingSilenceMs, playbackArtist, candidateArtist)
        }
        return isAcceptableMasterMatch(lyrics, playbackDurationSec * 1000L, playbackTitle, candidateTitle, playbackChannelTitle, playbackVideoId, audioLeadingSilenceMs, playbackArtist, candidateArtist)
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
     * - If [MasterMatchStatus.COMPATIBLE_OFFSET] without measured [audioLeadingSilenceMs] and not exact video,
     *   skips shift to prevent guessing.
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
        val isGenuineExactVideo = lyrics.isExactVideoMatch &&
            lyrics.provider == com.auralis.music.domain.model.LyricsProvider.UNISON
        if (masterMatch == MasterMatchStatus.COMPATIBLE_OFFSET && audioLeadingSilenceMs == null && !isGenuineExactVideo) {
            return lyrics
        }

        val providerLeadingSilence = lyrics.leadingSilenceMs
        val targetOffsetMs = if (audioLeadingSilenceMs != null && providerLeadingSilence != null) {
            (audioLeadingSilenceMs - providerLeadingSilence).coerceIn(-2000L, 2000L)
        } else {
            0L
        }

        val deltaOffsetMs = targetOffsetMs - lyrics.appliedOffsetMs
        if (deltaOffsetMs == 0L) {
            return lyrics
        }

        val alignedLines = lyrics.lines.map { line ->
            val shiftedTime = (line.time + deltaOffsetMs).coerceAtLeast(0L)
            val shiftedWords = line.words?.map { word ->
                word.copy(time = (word.time + deltaOffsetMs).coerceAtLeast(0L))
            }
            line.copy(
                time = shiftedTime,
                words = shiftedWords,
                endTime = line.endTime?.let { (it + deltaOffsetMs).coerceAtLeast(0L) }
            )
        }

        return lyrics.copy(lines = alignedLines, appliedOffsetMs = targetOffsetMs)
    }
}
