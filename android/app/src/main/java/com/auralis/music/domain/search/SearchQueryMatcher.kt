package com.auralis.music.domain.search

import com.auralis.music.domain.model.Track
import java.text.Normalizer
import java.util.Locale
import kotlin.math.min

/**
 * High-precision search query matching and ranking engine:
 * - Evaluates candidate tracks against the query across 5 explicit tiers:
 *   1. Exact song title matches
 *   2. Very close song title matches
 *   3. Artist matches
 *   4. Album matches
 *   5. Partial / relevant metadata matches
 * - Completely isolates recommendation candidates from actual search results.
 * - Caps supplementary recommendations at maximum 3 items.
 * - Guarantees zero duplicate songs across sections.
 */
object SearchQueryMatcher {

    enum class MatchTier(val priority: Int) {
        EXACT_TITLE(1),
        CLOSE_TITLE(2),
        ARTIST_MATCH(3),
        ALBUM_MATCH(4),
        METADATA_PARTIAL(5)
    }

    data class ScoredTrack(
        val track: Track,
        val tier: MatchTier,
        val score: Double,
        val originalIndex: Int = 0  // Preserves YouTube Music's popularity-based ML rank as tiebreaker
    )

    /**
     * Normalizes text for comparison:
     * - lowercase
     * - strips accents/diacritics
     * - removes special characters/brackets
     * - replaces multiple whitespace with single space
     */
    fun normalize(text: String): String {
        val nfd = Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        return nfd.replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /**
     * Matches a candidate track against the query.
     * Returns the best MatchTier and relevance score, or null if track is not a match.
     */
    fun evaluateMatch(track: Track, query: String): ScoredTrack? {
        val normQuery = normalize(query)
        if (normQuery.isBlank()) return null

        val normTitle = normalize(track.title)
        val normArtist = normalize(track.artist)
        val normAlbum = track.album?.let { normalize(it) } ?: ""

        val queryTokens = normQuery.split(" ").filter { it.isNotBlank() }
        if (queryTokens.isEmpty()) return null

        val cleanTitle = normalize(track.title.replace(Regex("\\(.*\\)|\\[.*\\]"), ""))
        val dist = levenshteinDistance(cleanTitle.ifBlank { normTitle }, normQuery)
        val artistTokens = normArtist.split(" ").filter { it.isNotBlank() }
        val allMetadata = "$normTitle $normArtist $normAlbum"
        val matchedTokensCount = queryTokens.count { token ->
            allMetadata.contains(token)
        }

        val result = when {
            // 1. Exact song title matches (ignoring case, punctuation, diacritics)
            normTitle == normQuery -> ScoredTrack(track, MatchTier.EXACT_TITLE, 100.0)

            // Title without parenthetical extras (e.g. "Blinding Lights (Official Video)" -> "Blinding Lights")
            cleanTitle == normQuery -> ScoredTrack(track, MatchTier.EXACT_TITLE, 95.0)

            // Query contains both Title and Artist (e.g. Query "Babuaan Pawan Singh" matching Song "Babuaan" by "Pawan Singh")
            (normQuery.startsWith(normTitle) || normQuery.startsWith(cleanTitle)) && normArtist.isNotBlank() &&
                    artistTokens.any { aTok -> aTok.length > 2 && normQuery.contains(aTok) } -> {
                ScoredTrack(track, MatchTier.EXACT_TITLE, 98.0)
            }

            // Candidate Title contains Query and Candidate Artist contains Query Artist tokens
            (normTitle.startsWith(normQuery) || cleanTitle.startsWith(normQuery)) && normArtist.isNotBlank() &&
                    queryTokens.any { qTok -> qTok.length > 2 && normArtist.contains(qTok) } -> {
                ScoredTrack(track, MatchTier.EXACT_TITLE, 96.0)
            }

            // 2. Very close song title matches
            normTitle.startsWith(normQuery) || cleanTitle.startsWith(normQuery) -> {
                val ratio = normQuery.length.toDouble() / normTitle.length.coerceAtLeast(1)
                ScoredTrack(track, MatchTier.CLOSE_TITLE, 85.0 + (ratio * 10.0))
            }

            // Query appears as a complete phrase in title
            normTitle.contains(normQuery) || cleanTitle.contains(normQuery) -> {
                ScoredTrack(track, MatchTier.CLOSE_TITLE, 80.0)
            }

            // Levenshtein distance <= 2 for short typo tolerance on title
            dist <= 2 && normQuery.length >= 4 -> {
                ScoredTrack(track, MatchTier.CLOSE_TITLE, 75.0 - dist)
            }

            // 3. Artist matches (searching an artist returns that artist's songs)
            normArtist == normQuery -> ScoredTrack(track, MatchTier.ARTIST_MATCH, 70.0)
            normArtist.startsWith(normQuery) || normArtist.contains(normQuery) -> ScoredTrack(track, MatchTier.ARTIST_MATCH, 65.0)
            queryTokens.all { qTok -> artistTokens.any { aTok -> aTok.contains(qTok) } } -> ScoredTrack(track, MatchTier.ARTIST_MATCH, 60.0)

            // 4. Album matches
            normAlbum.isNotBlank() && normAlbum == normQuery -> ScoredTrack(track, MatchTier.ALBUM_MATCH, 50.0)
            normAlbum.isNotBlank() && normAlbum.contains(normQuery) -> ScoredTrack(track, MatchTier.ALBUM_MATCH, 45.0)

            // 5. Partial / relevant metadata matches
            matchedTokensCount == queryTokens.size -> ScoredTrack(track, MatchTier.METADATA_PARTIAL, 30.0)
            queryTokens.size >= 3 && matchedTokensCount.toDouble() / queryTokens.size >= 0.70 -> ScoredTrack(track, MatchTier.METADATA_PARTIAL, 20.0)

            else -> null
        }

        return result?.let {
            it.copy(score = adjustScoreForOriginalTrack(track, query, it.score))
        }
    }

    private fun adjustScoreForOriginalTrack(track: Track, query: String, baseScore: Double): Double {
        var score = baseScore
        val lowerTitle = track.title.lowercase(Locale.ROOT)
        val lowerArtist = track.artist.lowercase(Locale.ROOT)
        val lowerQuery = query.lowercase(Locale.ROOT)

        val isCoverOrDerivative = lowerTitle.contains("cover") ||
                lowerTitle.contains("piano version") ||
                lowerTitle.contains("piano cover") ||
                lowerTitle.contains("tribute") ||
                lowerTitle.contains("karaoke") ||
                lowerTitle.contains("slowed") ||
                lowerTitle.contains("sped up") ||
                lowerTitle.contains("8d audio") ||
                lowerTitle.contains("lo-fi") ||
                lowerTitle.contains("lofi") ||
                lowerTitle.contains("guitar cover") ||
                lowerTitle.contains("remake") ||
                lowerArtist.contains("tribute") ||
                lowerArtist.contains("karaoke") ||
                lowerArtist.contains("cover")

        if (isCoverOrDerivative) {
            val queryWantsDerivative = lowerQuery.contains("cover") ||
                    lowerQuery.contains("piano") ||
                    lowerQuery.contains("slowed") ||
                    lowerQuery.contains("karaoke") ||
                    lowerQuery.contains("remake") ||
                    lowerQuery.contains("lofi") ||
                    lowerQuery.contains("lo-fi")
            if (!queryWantsDerivative) {
                score -= 35.0
            }
        } else {
            // Boost original studio releases, official audio, remastered album tracks
            if (!track.album.isNullOrBlank() || lowerTitle.contains("official") || lowerTitle.contains("remastered")) {
                score += 5.0
            }
        }
        return score
    }

    /**
     * Partitions candidates into:
     * - First: Verified query-matched songs, ranked by MatchTier and score.
     * - Second: Non-matching candidate recommendations, capped at maximum 3 items.
     * Guarantees zero duplicate songs between recommendations and matched results.
     */
    fun partitionResults(
        candidates: List<Track>,
        query: String,
        maxRecommendations: Int = 3
    ): Pair<List<Track>, List<Track>> {
        val trimmed = query.trim()
        if (trimmed.isBlank() || candidates.isEmpty()) {
            return Pair(emptyList(), emptyList())
        }

        val scoredMatches = mutableListOf<ScoredTrack>()
        val nonMatching = mutableListOf<Track>()

        for ((index, track) in candidates.withIndex()) {
            val eval = evaluateMatch(track, trimmed)
            if (eval != null) {
                scoredMatches.add(eval.copy(originalIndex = index))
            } else {
                nonMatching.add(track)
            }
        }

        // Sort actual matches: highest priority tier first, then highest score,
        // then by original YouTube Music ML rank (lower index = more popular/viewed)
        val rankedMatches = scoredMatches
            .sortedWith(
                compareBy<ScoredTrack> { it.tier.priority }
                    .thenByDescending { it.score }
                    .thenBy { it.originalIndex }
            )
            .map { it.track }
            .distinctBy { it.id }

        val matchedIds = rankedMatches.map { it.id }.toSet()

        // Recommendations: items that do NOT match the query and are NOT in the matched results, capped at maxRecommendations
        val recommendations = nonMatching
            .filterNot { matchedIds.contains(it.id) }
            .distinctBy { it.id }
            .take(maxRecommendations)

        return Pair(rankedMatches, recommendations)
    }

    /**
     * Specialized, high-precision matching of a target track (e.g. from Spotify or Room DB)
     * against candidate YouTube tracks.
     * Evaluates title, artist, duration, and channel metadata to guarantee the exact song is selected.
     */
    fun scoreTrackCandidate(
        target: Track,
        candidate: Track,
        index: Int = 0
    ): Double {
        val normTargetTitle = normalize(target.title)
        val cleanTargetTitle = normalize(target.title.replace(Regex("\\(.*\\)|\\[.*\\]|(?i)- (from|original|remix|audio).*"), ""))
        val normTargetArtist = if (target.artist.equals("Spotify Artist", ignoreCase = true)) "" else normalize(target.artist)
        
        // Extract primary artist (first artist before commas, &, feat, ft)
        val primaryTargetArtist = normTargetArtist.split(Regex("[,&/]|\\b(feat|ft|with)\\b")).firstOrNull()?.trim() ?: normTargetArtist
        val primaryArtistTokens = primaryTargetArtist.split(" ").filter { it.length > 1 && it !in listOf("feat", "ft", "and", "the") }
        val targetArtistTokens = normTargetArtist.split(" ").filter { it.length > 1 && it !in listOf("feat", "ft", "and", "the") }

        val derivativeKeywords = listOf("remix", "slowed", "reverb", "sped up", "speed up", "lofi", "lo-fi", "8d", "bass boosted", "mashup", "dj", "cover", "status", "ringtone", "instrumental", "karaoke", "teaser", "dialogue", "scene", "preview")

        val normCandTitle = normalize(candidate.title)
        val cleanCandTitle = normalize(candidate.title.replace(Regex("\\(.*\\)|\\[.*\\]|(?i)- (from|original|remix|audio).*"), ""))
        val normCandArtist = normalize(candidate.artist)
        val candArtistTokens = normCandArtist.split(" ").filter { it.length > 1 && it !in listOf("feat", "ft", "and", "the") }
        val candTitleTokens = normCandTitle.split(" ").filter { it.length > 1 }

        var score = 0.0

        // 1. Title Match (0 - 55 points)
        when {
            normCandTitle == normTargetTitle || cleanCandTitle == cleanTargetTitle -> score += 55.0
            cleanCandTitle == normTargetTitle || normCandTitle == cleanTargetTitle -> score += 50.0
            cleanCandTitle.startsWith(cleanTargetTitle) || cleanTargetTitle.startsWith(cleanCandTitle) -> score += 45.0
            cleanCandTitle.contains(cleanTargetTitle) || cleanTargetTitle.contains(cleanCandTitle) -> score += 40.0
            else -> {
                val targetTitleTokens = cleanTargetTitle.split(" ").filter { it.length > 1 }
                val overlap = targetTitleTokens.intersect(candTitleTokens.toSet())
                if (overlap.isNotEmpty() && targetTitleTokens.isNotEmpty()) {
                    val ratio = overlap.size.toDouble() / targetTitleTokens.size.toDouble()
                    score += ratio * 35.0
                }
            }
        }

        if (score < 15.0) return -1.0 // Skip non-matching titles

        // 2. Artist Agreement (0 - 35 points) - Prioritize Primary Artist
        if (primaryArtistTokens.isNotEmpty()) {
            val primaryOverlap = primaryArtistTokens.intersect(candArtistTokens.toSet())
            val titlePrimaryOverlap = primaryArtistTokens.intersect(candTitleTokens.toSet())
            val allArtistOverlap = targetArtistTokens.intersect(candArtistTokens.toSet())

            when {
                normCandArtist.contains(primaryTargetArtist) || primaryTargetArtist.contains(normCandArtist) -> score += 35.0
                primaryOverlap.size == primaryArtistTokens.size -> score += 32.0
                allArtistOverlap.isNotEmpty() -> {
                    val ratio = allArtistOverlap.size.toDouble() / targetArtistTokens.size.toDouble()
                    score += ratio * 30.0
                }
                titlePrimaryOverlap.size == primaryArtistTokens.size -> score += 25.0
                else -> {
                    score -= 50.0 // Heavy penalty when candidate artist does not match target artist
                }
            }
        } else {
            score += 15.0 // Neutral when no target artist
        }

        // 3. YouTube Music ML Rank Bonus (0 - 15 points)
        if (index == 0) score += 15.0
        else if (index in 1..2) score += 8.0

        // 4. Duration Proximity (0 - 15 points)
        if (target.duration > 0 && candidate.duration > 0) {
            val delta = kotlin.math.abs(target.duration - candidate.duration)
            when {
                delta <= 5 -> score += 15.0
                delta <= 15 -> score += 10.0
                delta <= 30 -> score += 5.0
                delta > 60 -> score -= 100.0 // Heavy penalty for long videos/mixes
                delta > 120 -> score -= 500.0 // Immediate disqualification for full mixes
            }
        }

        // 5. Anti-Derivative & Quality Filtering
        val lowerCandTitle = candidate.title.lowercase(Locale.ROOT)
        val lowerTargetTitle = target.title.lowercase(Locale.ROOT)
        for (keyword in derivativeKeywords) {
            if (lowerCandTitle.contains(keyword) && !lowerTargetTitle.contains(keyword)) {
                score -= 250.0 // Disqualify unwanted remixes, DJ versions, lofi, covers, ringtones
            }
        }

        if (!candidate.album.isNullOrBlank()) {
            score += 8.0 // Authentic studio album release bonus
        }

        return score
    }

    fun findBestCandidateForTrack(
        target: Track,
        candidates: List<Track>
    ): Track? {
        if (candidates.isEmpty()) return null

        var bestScore = -1.0
        var bestCandidate: Track? = null

        for ((index, candidate) in candidates.withIndex()) {
            val score = scoreTrackCandidate(target, candidate, index)
            if (score > bestScore && score >= 40.0) {
                bestScore = score
                bestCandidate = candidate
            }
        }

        return bestCandidate
    }

    private fun levenshteinDistance(s1: String, s2: String): Int {
        val dp = Array(s1.length + 1) { IntArray(s2.length + 1) }
        for (i in 0..s1.length) dp[i][0] = i
        for (j in 0..s2.length) dp[0][j] = j

        for (i in 1..s1.length) {
            for (j in 1..s2.length) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                dp[i][j] = min(
                    dp[i - 1][j] + 1,
                    min(dp[i][j - 1] + 1, dp[i - 1][j - 1] + cost)
                )
            }
        }
        return dp[s1.length][s2.length]
    }
}
