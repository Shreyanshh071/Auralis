package com.auralis.music.data.parser

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
     * Checks whether a candidate lyric duration matches track duration within [maxToleranceSec] (default 4 seconds).
     */
    fun isDurationMatching(trackDurationSec: Long, lyricDurationSec: Long, maxToleranceSec: Long = 4): Boolean {
        if (trackDurationSec <= 0 || lyricDurationSec <= 0) return true // Allow if duration unknown
        return abs(trackDurationSec - lyricDurationSec) <= maxToleranceSec
    }

    /**
     * Matches title and artist candidates against query criteria using Dice coefficient threshold.
     */
    fun isCandidateAcceptable(
        queryTitle: String,
        queryArtist: String,
        candidateTitle: String,
        candidateArtist: String,
        titleThreshold: Double = 0.6,
        artistThreshold: Double = 0.5
    ): Boolean {
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
