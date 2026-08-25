package com.auralis.music.data.parser

import com.auralis.music.data.network.TitleCleaner
import kotlin.math.abs

object LyricsMatcher {

    /**
     * Calculates the token-level Dice coefficient between two strings.
     * Returns a float in range [0.0, 1.0].
     */
    fun diceCoefficient(str1: String, str2: String): Double {
        val tokens1 = tokenize(str1)
        val tokens2 = tokenize(str2)

        if (tokens1.isEmpty() && tokens2.isEmpty()) return 1.0
        if (tokens1.isEmpty() || tokens2.isEmpty()) return 0.0

        val set1 = tokens1.toSet()
        val set2 = tokens2.toSet()

        val intersectionSize = set1.count { it in set2 }
        return (2.0 * intersectionSize) / (set1.size + set2.size)
    }

    /**
     * Checks whether a candidate lyric duration matches track duration within [maxToleranceSec] (default 15 seconds).
     */
    fun isDurationMatching(trackDurationSec: Long, lyricDurationSec: Long, maxToleranceSec: Long = 15): Boolean {
        if (trackDurationSec <= 0 || lyricDurationSec <= 0) return true // Allow if duration unknown
        return abs(trackDurationSec - lyricDurationSec) <= maxToleranceSec
    }

    /**
     * Extracts all individual artists from a combined artist string (handles commas, &, feat, ft, and).
     */
    private fun getArtistTokens(artist: String): List<String> {
        return artist
            .replace(Regex("(?i)(?: - Topic|Official|VEVO)$"), "")
            .lowercase()
            .split(Regex("""[,&/]|(?:\s+feat\.?\s+)|\s+ft\.?\s+|\s+and\s+"""))
            .map { it.trim().replace(Regex("""[^\p{L}\p{Nd}\s]"""), "") }
            .filter { it.isNotBlank() }
    }

    /**
     * Flexible multi-artist matcher.
     */
    fun isArtistMatching(queryArtist: String, candArtist: String): Boolean {
        val qTokens = getArtistTokens(queryArtist)
        val cTokens = getArtistTokens(candArtist)
        if (qTokens.isEmpty() || cTokens.isEmpty()) return true

        for (q in qTokens) {
            for (c in cTokens) {
                if (q == c || q.contains(c) || c.contains(q)) return true
            }
        }
        return false
    }

    /**
     * Flexible track title matcher.
     */
    fun isTitleMatching(queryTitle: String, candTitle: String): Boolean {
        val qClean = TitleCleaner.cleanTitle(queryTitle).lowercase().trim()
        val cClean = TitleCleaner.cleanTitle(candTitle).lowercase().trim()
        if (qClean.isBlank() || cClean.isBlank()) return true
        if (qClean == cClean) return true
        if (qClean.contains(cClean) || cClean.contains(qClean)) return true

        val qTokens = tokenize(qClean)
        val cTokens = tokenize(cClean).toSet()
        if (qTokens.isEmpty() || cTokens.isEmpty()) return false

        val matches = qTokens.count { it in cTokens }
        return matches >= Math.min(qTokens.size, 2)
    }

    /**
     * Comprehensive candidate match for both title and artist.
     */
    fun isCandidateAcceptable(
        queryTitle: String,
        queryArtist: String,
        candidateTitle: String,
        candidateArtist: String,
        titleThreshold: Double = 0.5,
        artistThreshold: Double = 0.3
    ): Boolean {
        if (isTitleMatching(queryTitle, candidateTitle) && isArtistMatching(queryArtist, candidateArtist)) {
            return true
        }
        val titleScore = diceCoefficient(queryTitle, candidateTitle)
        val artistScore = diceCoefficient(queryArtist, candidateArtist)
        return titleScore >= titleThreshold && artistScore >= artistThreshold
    }

    private fun tokenize(input: String): List<String> {
        return input.lowercase()
            .replace(Regex("""[^\p{L}\p{Nd}\s]"""), " ")
            .split(Regex("""\s+"""))
            .filter { it.isNotBlank() }
    }
}
