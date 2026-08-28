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
        val score: Double
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

        // 1. Exact song title matches (ignoring case, punctuation, diacritics)
        if (normTitle == normQuery) {
            return ScoredTrack(track, MatchTier.EXACT_TITLE, 100.0)
        }

        // Title without parenthetical extras (e.g. "Blinding Lights (Official Video)" -> "Blinding Lights")
        val cleanTitle = normalize(track.title.replace(Regex("\\(.*\\)|\\[.*\\]"), ""))
        if (cleanTitle == normQuery) {
            return ScoredTrack(track, MatchTier.EXACT_TITLE, 95.0)
        }

        // 2. Very close song title matches
        // Substring / prefix match: e.g. "Blinding Light" in "Blinding Lights" or "Blinding Lights - Remastered"
        if (normTitle.startsWith(normQuery) || cleanTitle.startsWith(normQuery)) {
            val ratio = normQuery.length.toDouble() / normTitle.length.coerceAtLeast(1)
            return ScoredTrack(track, MatchTier.CLOSE_TITLE, 85.0 + (ratio * 10.0))
        }

        // Query appears as a complete phrase in title
        if (normTitle.contains(normQuery) || cleanTitle.contains(normQuery)) {
            return ScoredTrack(track, MatchTier.CLOSE_TITLE, 80.0)
        }

        // Levenshtein distance <= 2 for short typo tolerance on title
        val dist = levenshteinDistance(cleanTitle.ifBlank { normTitle }, normQuery)
        if (dist <= 2 && normQuery.length >= 4) {
            return ScoredTrack(track, MatchTier.CLOSE_TITLE, 75.0 - dist)
        }

        // 3. Artist matches (searching an artist returns that artist's songs)
        if (normArtist == normQuery) {
            return ScoredTrack(track, MatchTier.ARTIST_MATCH, 70.0)
        }
        if (normArtist.startsWith(normQuery) || normArtist.contains(normQuery)) {
            return ScoredTrack(track, MatchTier.ARTIST_MATCH, 65.0)
        }
        val artistTokens = normArtist.split(" ").filter { it.isNotBlank() }
        if (queryTokens.all { qTok -> artistTokens.any { aTok -> aTok.contains(qTok) } }) {
            return ScoredTrack(track, MatchTier.ARTIST_MATCH, 60.0)
        }

        // 4. Album matches
        if (normAlbum.isNotBlank()) {
            if (normAlbum == normQuery) {
                return ScoredTrack(track, MatchTier.ALBUM_MATCH, 50.0)
            }
            if (normAlbum.contains(normQuery)) {
                return ScoredTrack(track, MatchTier.ALBUM_MATCH, 45.0)
            }
        }

        // 5. Partial / relevant metadata matches
        // All query tokens appear somewhere across title, artist, or album
        val allMetadata = "$normTitle $normArtist $normAlbum"
        val matchedTokensCount = queryTokens.count { token ->
            allMetadata.contains(token)
        }

        if (matchedTokensCount == queryTokens.size) {
            return ScoredTrack(track, MatchTier.METADATA_PARTIAL, 30.0)
        }

        // If query has multiple tokens (e.g. 3+), match if at least 70% of tokens match
        if (queryTokens.size >= 3 && matchedTokensCount.toDouble() / queryTokens.size >= 0.70) {
            return ScoredTrack(track, MatchTier.METADATA_PARTIAL, 20.0)
        }

        // Candidate does not match any query criteria -> classified as non-match
        return null
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

        for (track in candidates) {
            val eval = evaluateMatch(track, trimmed)
            if (eval != null) {
                scoredMatches.add(eval)
            } else {
                nonMatching.add(track)
            }
        }

        // Sort actual matches: highest priority tier first, then highest score
        val rankedMatches = scoredMatches
            .sortedWith(
                compareBy<ScoredTrack> { it.tier.priority }
                    .thenByDescending { it.score }
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
