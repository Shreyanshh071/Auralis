package com.auralis.music.data.network

import android.util.Log
import com.auralis.music.data.parser.IndicScriptNormalizer
import com.auralis.music.domain.model.Track
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

enum class TrackVersionType {
    STANDARD,
    SLOWED,
    SPED_UP,
    REMIX,
    LIVE,
    ACOUSTIC,
    INSTRUMENTAL,
    SLOWED_REVERB,
    NIGHTCORE,
    RADIO_EDIT,
    EXTENDED,
    PART
}

data class TrackMatchResult(
    val candidate: Track,
    val confidence: Int,
    val titleScore: Int,
    val artistScore: Int,
    val versionScore: Int,
    val durationScore: Int,
    val isAccepted: Boolean,
    val reason: String
)

/**
 * Intelligent Spotify Track Matcher & Candidate Scoring Engine.
 *
 * Implements strict precision-first candidate evaluation to guarantee that:
 * 1. Spotify track metadata remains the source of truth.
 * 2. Generic tokens (e.g. "slowed", "Part 1", "audio", "remix") cannot establish track identity on their own.
 * 3. Exact versions (Slowed vs Studio vs Live vs Acoustic) are properly discriminated.
 * 4. Duration and Artist agreement are strictly enforced.
 * 5. Low-confidence candidates (< 70%) are rejected instead of binding to a wrong song.
 */
object SpotifyTrackMatcher {

    private const val TAG = "SpotifyTrackMatcher"
    const val MIN_AUTO_MATCH_CONFIDENCE = 70

    // Non-identifying generic stopwords that must NOT establish track identity
    private val GENERIC_STOPWORDS = hashSetOf(
        "slowed", "slow", "reverb", "sped", "spedup", "speed", "speedup", "remix", "edit",
        "version", "ver", "part", "pt", "vol", "volume", "audio", "official", "video",
        "music", "song", "lyrics", "lyric", "visualizer", "full", "original", "soundtrack",
        "ost", "theme", "mix", "extended", "radio", "acoustic", "live", "instrumental",
        "nightcore", "karaoke", "clean", "explicit", "hd", "4k", "hq", "remastered", "remaster"
    )

    /**
     * Evaluates a list of candidates against the target Spotify track and selects
     * the best candidate that satisfies the minimum confidence threshold.
     */
    fun findBestMatch(
        spotifyTrack: Track,
        candidates: List<Track>,
        minConfidence: Int = MIN_AUTO_MATCH_CONFIDENCE
    ): TrackMatchResult? {
        if (candidates.isEmpty()) return null

        var bestResult: TrackMatchResult? = null

        for (candidate in candidates) {
            val result = evaluateCandidate(spotifyTrack, candidate, minConfidence)
            logCandidateEvaluation(spotifyTrack, candidate, result)

            if (result.isAccepted) {
                if (bestResult == null || result.confidence > bestResult.confidence) {
                    bestResult = result
                }
            }
        }

        return bestResult
    }

    /**
     * Computes multi-factor composite confidence (0–100%) for a single candidate.
     */
    fun evaluateCandidate(
        spotifyTrack: Track,
        candidate: Track,
        minConfidence: Int = MIN_AUTO_MATCH_CONFIDENCE
    ): TrackMatchResult {
        val targetTitle = spotifyTrack.title
        val targetArtist = spotifyTrack.artist
        val targetDuration = spotifyTrack.duration
        val targetAlbum = spotifyTrack.album

        val candTitle = candidate.title
        val candArtist = candidate.artist
        val candDuration = candidate.duration
        val candAlbum = candidate.album

        // 1. Core Title & Stopword Analysis
        val (coreTargetTokens, targetVersion) = extractCoreTokensAndVersion(targetTitle)
        val (coreCandTokens, candVersion) = extractCoreTokensAndVersion(candTitle)

        // Detect Suspicious / Low-Information Candidate
        // If candidate consists entirely or almost entirely of generic stopwords with no core identity
        val isCandidatePurelyGeneric = isPurelyGenericTitle(candTitle, coreCandTokens)

        val titleScore = if (isCandidatePurelyGeneric && coreTargetTokens.isNotEmpty()) {
            0 // Severe penalty: e.g. Candidate title is just "slowed" or "Part 1"
        } else {
            computeTitleSimilarity(coreTargetTokens, coreCandTokens, targetTitle, candTitle)
        }

        // 2. Artist Agreement
        val artistScore = computeArtistSimilarity(targetArtist, candArtist, candTitle)

        // 3. Version Compatibility
        val versionScore = computeVersionSimilarity(targetVersion, candVersion)

        // 4. Duration Proximity Curve
        val durationScore = computeDurationSimilarity(targetDuration, candDuration)

        // 5. Album Boost (Bonus up to 5%)
        val albumBoost = if (!targetAlbum.isNullOrBlank() && !candAlbum.isNullOrBlank() &&
            targetAlbum.equals(candAlbum, ignoreCase = true)
        ) 5 else 0

        // Composite Confidence Weighting:
        // Title (40%) + Artist (30%) + Version (15%) + Duration (15%) + AlbumBoost (5%)
        var compositeConfidence = (
            (titleScore * 0.40f) +
            (artistScore * 0.30f) +
            (versionScore * 0.15f) +
            (durationScore * 0.15f) +
            albumBoost
        ).toInt().coerceIn(0, 100)

        // Hard Failure Gates:
        var isAccepted = compositeConfidence >= minConfidence
        var reason = "Confidence $compositeConfidence% >= $minConfidence%"
        val hasExplicitTargetArtist = targetArtist.isNotBlank() && targetArtist != "Spotify Artist"

        if (isCandidatePurelyGeneric && coreTargetTokens.isNotEmpty()) {
            compositeConfidence = compositeConfidence.coerceAtMost(25)
            isAccepted = false
            reason = "Rejected: Candidate title is purely generic ('$candTitle')"
        } else if (titleScore < 35) {
            isAccepted = false
            reason = "Rejected: Title similarity too low ($titleScore%)"
        } else if (hasExplicitTargetArtist && artistScore < 25) {
            compositeConfidence = compositeConfidence.coerceAtMost(49)
            isAccepted = false
            reason = "Rejected: Artist mismatch ($artistScore% with target artist '$targetArtist')"
        } else if (versionScore == 0 && targetVersion != TrackVersionType.STANDARD) {
            isAccepted = false
            reason = "Rejected: Incompatible version ($targetVersion vs $candVersion)"
        } else if (durationScore == 0 && targetDuration > 0 && candDuration > 0 && abs(targetDuration - candDuration) > 45) {
            isAccepted = false
            reason = "Rejected: Duration mismatch (${targetDuration}s vs ${candDuration}s, delta > 45s)"
        }

        return TrackMatchResult(
            candidate = candidate,
            confidence = compositeConfidence,
            titleScore = titleScore,
            artistScore = artistScore,
            versionScore = versionScore,
            durationScore = durationScore,
            isAccepted = isAccepted,
            reason = reason
        )
    }

    /**
     * Determines if a title consists purely of generic stopwords (e.g. "slowed", "part 1", "remix").
     */
    fun isPurelyGenericTitle(rawTitle: String, coreTokens: Set<String>): Boolean {
        val allTokens = tokenize(rawTitle)
        if (allTokens.isEmpty()) return true

        val nonGenericTokens = allTokens.filter { it !in GENERIC_STOPWORDS && !it.matches(Regex("""^\d+$""")) }
        return nonGenericTokens.isEmpty()
    }

    /**
     * Extracts canonical version type and remaining core title tokens.
     */
    fun extractCoreTokensAndVersion(title: String): Pair<Set<String>, TrackVersionType> {
        val lower = title.lowercase()
        val version = when {
            lower.contains("slowed") && lower.contains("reverb") -> TrackVersionType.SLOWED_REVERB
            lower.contains("slowed") || lower.contains("slow") -> TrackVersionType.SLOWED
            lower.contains("sped up") || lower.contains("speed up") || lower.contains("sped") -> TrackVersionType.SPED_UP
            lower.contains("nightcore") -> TrackVersionType.NIGHTCORE
            lower.contains("acoustic") || lower.contains("unplugged") -> TrackVersionType.ACOUSTIC
            lower.contains("live") || lower.contains("concert") -> TrackVersionType.LIVE
            lower.contains("instrumental") || lower.contains("karaoke") -> TrackVersionType.INSTRUMENTAL
            lower.contains("radio edit") || lower.contains("radio mix") -> TrackVersionType.RADIO_EDIT
            lower.contains("extended") || lower.contains("club mix") -> TrackVersionType.EXTENDED
            lower.contains("remix") || lower.contains("mix") -> TrackVersionType.REMIX
            lower.contains("part ") || lower.contains("pt.") || lower.contains("pt ") -> TrackVersionType.PART
            else -> TrackVersionType.STANDARD
        }

        // Clean title with Indic normalization support
        val normalized = IndicScriptNormalizer.normalizeIndicText(title)
        val transliterated = IndicScriptNormalizer.transliterateToPhoneticLatin(normalized)
        val tokens = tokenize(transliterated)

        // Filter out generic stopwords for the core comparison
        val coreTokens = tokens.filter { it !in GENERIC_STOPWORDS && it.length > 1 }.toSet()

        return Pair(coreTokens.ifEmpty { tokens.toSet() }, version)
    }

    private fun computeTitleSimilarity(
        targetCore: Set<String>,
        candCore: Set<String>,
        rawTarget: String,
        rawCand: String
    ): Int {
        if (targetCore.isEmpty() || candCore.isEmpty()) {
            return if (rawTarget.equals(rawCand, ignoreCase = true)) 100 else 0
        }

        // Exact match of core token sets
        if (targetCore == candCore) return 100

        // Subset containment (e.g. target="stalk ur socials", cand="stalk ur socials official" or target="Sorry Sorry - From Bhojpuriya Raja", cand="Sorry Sorry")
        val intersection = targetCore.intersect(candCore)
        if (intersection.size == targetCore.size) return 95
        if (intersection.size == candCore.size && candCore.isNotEmpty()) return 90

        val union = targetCore.union(candCore)
        val jaccard = (intersection.size.toFloat() / union.size.toFloat()) * 100f

        // Levenshtein distance on full normalized title
        val normTarget = normalizeString(rawTarget)
        val normCand = normalizeString(rawCand)
        val levSim = calculateLevenshteinSimilarity(normTarget, normCand) * 100f

        return max(jaccard, levSim).toInt().coerceIn(0, 100)
    }

    private fun computeArtistSimilarity(
        targetArtist: String,
        candArtist: String,
        candTitle: String
    ): Int {
        if (targetArtist.isBlank() || targetArtist == "Spotify Artist") return 70 // Neutral when no target artist

        val normTarget = IndicScriptNormalizer.transliterateToPhoneticLatin(IndicScriptNormalizer.normalizeIndicText(targetArtist))
        val normCand = IndicScriptNormalizer.transliterateToPhoneticLatin(IndicScriptNormalizer.normalizeIndicText(candArtist))
        val normCandTitle = IndicScriptNormalizer.transliterateToPhoneticLatin(IndicScriptNormalizer.normalizeIndicText(candTitle))

        val targetTokens = tokenize(normTarget).filter { it !in GENERIC_STOPWORDS && it !in listOf("feat", "ft", "and", "the") }.toSet()
        val candTokens = tokenize(normCand).filter { it !in GENERIC_STOPWORDS && it !in listOf("feat", "ft", "and", "the") }.toSet()
        val candTitleTokens = tokenize(normCandTitle).toSet()

        if (targetTokens.isEmpty()) return 70

        // 1. Direct overlap between artist strings
        val artistOverlap = targetTokens.intersect(candTokens)
        if (artistOverlap.isNotEmpty()) {
            val ratio = artistOverlap.size.toFloat() / targetTokens.size.toFloat()
            return (ratio * 100f).toInt().coerceIn(0, 100)
        }

        // 2. YouTube videos often put artist inside video title ("Artist - Song")
        val titleOverlap = targetTokens.intersect(candTitleTokens)
        if (titleOverlap.isNotEmpty()) {
            val ratio = titleOverlap.size.toFloat() / targetTokens.size.toFloat()
            return ((ratio * 90f)).toInt().coerceIn(0, 100)
        }

        // 3. String contains check
        val cleanTargetStr = normTarget.lowercase().replace(Regex("[^a-z0-9]"), "")
        val cleanCandStr = normCand.lowercase().replace(Regex("[^a-z0-9]"), "")
        if (cleanTargetStr.isNotBlank() && (cleanCandStr.contains(cleanTargetStr) || cleanTargetStr.contains(cleanCandStr))) {
            return 85
        }

        return 0
    }

    private fun computeVersionSimilarity(
        targetVersion: TrackVersionType,
        candVersion: TrackVersionType
    ): Int {
        if (targetVersion == candVersion) return 100

        return when (targetVersion) {
            TrackVersionType.SLOWED -> {
                if (candVersion == TrackVersionType.SLOWED_REVERB) 85
                else 0 // Incompatible
            }
            TrackVersionType.SLOWED_REVERB -> {
                if (candVersion == TrackVersionType.SLOWED) 85
                else 0
            }
            TrackVersionType.SPED_UP -> {
                if (candVersion == TrackVersionType.NIGHTCORE) 80
                else 0
            }
            TrackVersionType.NIGHTCORE -> {
                if (candVersion == TrackVersionType.SPED_UP) 80
                else 0
            }
            TrackVersionType.STANDARD -> {
                // If target is standard studio, candidate being standard is 100%,
                // radio edit is 90%, but remix/slowed/acoustic is penalized (25%).
                when (candVersion) {
                    TrackVersionType.RADIO_EDIT -> 90
                    TrackVersionType.EXTENDED -> 80
                    TrackVersionType.STANDARD -> 100
                    else -> 25
                }
            }
            else -> {
                if (targetVersion == candVersion) 100 else 0
            }
        }
    }

    private fun computeDurationSimilarity(targetDur: Long, candDur: Long): Int {
        if (targetDur <= 0 || candDur <= 0) return 75 // Neutral when duration unknown

        val delta = abs(targetDur - candDur)
        return when {
            delta <= 5 -> 100
            delta <= 15 -> 85
            delta <= 30 -> 50
            delta <= 45 -> 25
            else -> 0 // Delta > 45s is heavily penalized
        }
    }

    private fun tokenize(text: String): List<String> {
        return text.lowercase()
            .replace(Regex("""[()\[\]{},._\-–—/|:;!?'"&]"""), " ")
            .split(Regex("""\s+"""))
            .map { it.trim() }
            .filter { it.isNotBlank() }
    }

    private fun normalizeString(text: String): String {
        return text.lowercase()
            .replace(Regex("""[()\[\]{},._\-–—/|:;!?'"&]"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun calculateLevenshteinSimilarity(s1: String, s2: String): Float {
        if (s1 == s2) return 1.0f
        if (s1.isEmpty()) return 0.0f
        if (s2.isEmpty()) return 0.0f

        val maxLen = max(s1.length, s2.length)
        val distance = levenshteinDistance(s1, s2)
        return 1.0f - (distance.toFloat() / maxLen.toFloat())
    }

    private fun levenshteinDistance(s1: String, s2: String): Int {
        val dp = Array(s1.length + 1) { IntArray(s2.length + 1) }
        for (i in 0..s1.length) dp[i][0] = i
        for (j in 0..s2.length) dp[0][j] = j

        for (i in 1..s1.length) {
            for (j in 1..s2.length) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                dp[i][j] = min(
                    min(dp[i - 1][j] + 1, dp[i][j - 1] + 1),
                    dp[i - 1][j - 1] + cost
                )
            }
        }
        return dp[s1.length][s2.length]
    }

    private fun logCandidateEvaluation(target: Track, cand: Track, res: TrackMatchResult) {
        Log.d(
            TAG,
            """
            [Match Evaluation]
            Target: '${target.title}' by '${target.artist}' (${target.duration}s)
            Candidate: '${cand.title}' by '${cand.artist}' (${cand.duration}s, id=${cand.id})
            Scores -> Total: ${res.confidence}% (Title: ${res.titleScore}%, Artist: ${res.artistScore}%, Version: ${res.versionScore}%, Duration: ${res.durationScore}%)
            Result: ${if (res.isAccepted) "ACCEPTED" else "REJECTED"} -> ${res.reason}
            """.trimIndent()
        )
    }
}
