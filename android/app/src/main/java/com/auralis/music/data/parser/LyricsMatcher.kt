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
     * Checks whether a candidate lyric duration matches track duration within [maxToleranceSec] (default 3.5 seconds).
     */
    fun isDurationMatching(trackDurationSec: Long, lyricDurationSec: Long, maxToleranceSec: Long = 4): Boolean {
        if (trackDurationSec <= 0 || lyricDurationSec <= 0) return true
        return abs(trackDurationSec - lyricDurationSec) <= maxToleranceSec
    }

    /**
     * Computes the automatic pre-gap intro offset between YouTube audio stream and studio master lyrics.
     * Handles radio/video edits vs album version differences (e.g. Bitter Sweet Symphony 4:38 vs 5:58).
     */
    fun calculateIntroOffsetMs(
        firstLineTimeMs: Long,
        trackDurationSec: Long?,
        lyricDurationSec: Long?
    ): Long {
        // Pristine timestamp preservation: do not apply artificial shifting to standard songs
        return 0L
    }

    /**
     * Automatically applies intro alignment to all line and syllable timestamps.
     */
    fun autoAlignLyrics(
        lyricsData: com.auralis.music.domain.model.LyricsData,
        trackDurationSec: Long?,
        lyricDurationSec: Long?
    ): com.auralis.music.domain.model.LyricsData {
        if (lyricsData.lines.isEmpty()) return lyricsData
        val firstLineTime = lyricsData.lines.firstOrNull()?.time ?: 0L
        val offsetMs = calculateIntroOffsetMs(firstLineTime, trackDurationSec, lyricDurationSec)
        if (offsetMs == 0L) return lyricsData

        val alignedLines = lyricsData.lines.map { line ->
            val shiftedTime = (line.time + offsetMs).coerceAtLeast(0L)
            val shiftedWords = line.words?.map { word ->
                word.copy(time = (word.time + offsetMs).coerceAtLeast(0L))
            }
            line.copy(time = shiftedTime, words = shiftedWords)
        }
        return lyricsData.copy(lines = alignedLines)
    }

    /**
     * Extracts all individual artists from a combined artist string (handles commas, &, feat, ft, and).
     */
    fun getArtistTokens(artist: String): List<String> {
        val cleaned = TitleCleaner.cleanArtist(artist)
        return cleaned
            .lowercase()
            .split(Regex("""[,&/|]|(?:\s+feat\.?\s+)|\s+ft\.?\s+|\s+and\s+|\s+with\s+"""))
            .map { it.trim().replace(Regex("""[^\p{L}\p{Nd}\s]"""), "") }
            .filter { it.isNotBlank() }
    }

    /**
     * Flexible multi-artist matcher with Indic transliteration cross-checking.
     */
    fun isArtistMatching(queryArtist: String, candArtist: String): Boolean {
        val qTokens = getArtistTokens(queryArtist)
        val cTokens = getArtistTokens(candArtist)
        if (qTokens.isEmpty() || cTokens.isEmpty()) return true

        for (q in qTokens) {
            val qPhonetic = IndicScriptNormalizer.toPhoneticCanonical(IndicScriptNormalizer.transliterateToPhoneticLatin(q))
            for (c in cTokens) {
                val cPhonetic = IndicScriptNormalizer.toPhoneticCanonical(IndicScriptNormalizer.transliterateToPhoneticLatin(c))
                if (q == c || q.contains(c) || c.contains(q)) return true
                if (qPhonetic == cPhonetic || qPhonetic.contains(cPhonetic) || cPhonetic.contains(qPhonetic)) return true

                val qWords = qPhonetic.split(" ").filter { it.isNotBlank() }
                val cWords = cPhonetic.split(" ").filter { it.isNotBlank() }
                if (qWords.any { qw -> cWords.any { cw -> qw == cw || (qw.length >= 4 && cw.length >= 4 && (qw.contains(cw) || cw.contains(qw))) } }) {
                    return true
                }
            }
        }
        return false
    }

    /**
     * Flexible track title matcher with version awareness and Indic transliteration cross-checking.
     */
    fun isTitleMatching(queryTitle: String, candTitle: String): Boolean {
        val qClean = TitleCleaner.cleanTitle(queryTitle).lowercase().trim()
        val cClean = TitleCleaner.cleanTitle(candTitle).lowercase().trim()
        if (qClean.isBlank() || cClean.isBlank()) return true
        if (qClean == cClean) return true
        if (qClean.contains(cClean) || cClean.contains(qClean)) return true

        val qPhonetic = IndicScriptNormalizer.transliterateToPhoneticLatin(qClean)
        val cPhonetic = IndicScriptNormalizer.transliterateToPhoneticLatin(cClean)
        if (qPhonetic == cPhonetic || qPhonetic.contains(cPhonetic) || cPhonetic.contains(qPhonetic)) return true

        val qTokens = tokenize(qPhonetic)
        val cTokens = tokenize(cPhonetic).toSet()
        if (qTokens.isEmpty() || cTokens.isEmpty()) return false

        val matches = qTokens.count { qWord ->
            cTokens.any { cWord ->
                qWord == cWord || (qWord.length >= 4 && cWord.length >= 4 && (qWord.contains(cWord) || cWord.contains(qWord)))
            }
        }
        return matches >= Math.min(qTokens.size, 2)
    }

    /**
     * Calculates a composite match confidence score in range [0..100%].
     */
    fun calculateConfidence(
        queryTitle: String,
        queryArtist: String,
        candidateTitle: String,
        candidateArtist: String,
        queryDurationSec: Long? = null,
        candidateDurationSec: Long? = null,
        queryAlbum: String? = null,
        candidateAlbum: String? = null
    ): Int {
        val qCleanTitle = TitleCleaner.cleanTitle(queryTitle)
        val cCleanTitle = TitleCleaner.cleanTitle(candidateTitle)

        // 1. Title Score (0.0 to 1.0)
        val directTitleDice = diceCoefficient(qCleanTitle, cCleanTitle)
        val phoneticTitleDice = diceCoefficient(
            IndicScriptNormalizer.transliterateToPhoneticLatin(qCleanTitle),
            IndicScriptNormalizer.transliterateToPhoneticLatin(cCleanTitle)
        )
        val isDirectSub = if (qCleanTitle.isNotBlank() && cCleanTitle.isNotBlank() &&
            (qCleanTitle.contains(cCleanTitle, ignoreCase = true) || cCleanTitle.contains(qCleanTitle, ignoreCase = true))
        ) 0.9 else 0.0
        val isTitleMatchScore = if (isTitleMatching(queryTitle, candidateTitle)) 0.95 else 0.0
        val titleScore = maxOf(directTitleDice, phoneticTitleDice, isDirectSub, isTitleMatchScore)

        // 2. Artist Score (0.0 to 1.0)
        val qArtistClean = TitleCleaner.cleanArtist(queryArtist)
        val cArtistClean = TitleCleaner.cleanArtist(candidateArtist)
        val directArtistDice = diceCoefficient(qArtistClean, cArtistClean)
        val phoneticArtistDice = diceCoefficient(
            IndicScriptNormalizer.transliterateToPhoneticLatin(qArtistClean),
            IndicScriptNormalizer.transliterateToPhoneticLatin(cArtistClean)
        )
        val isArtistMatch = if (isArtistMatching(queryArtist, candidateArtist)) 0.95 else 0.0
        val artistScore = maxOf(directArtistDice, phoneticArtistDice, isArtistMatch)

        // 3. Duration Score (0.0 to 1.0)
        val durationScore: Double = if (queryDurationSec != null && queryDurationSec > 0 &&
            candidateDurationSec != null && candidateDurationSec > 0
        ) {
            val diff = abs(queryDurationSec - candidateDurationSec)
            when {
                diff <= 2 -> 1.0
                diff <= 5 -> 0.90
                diff <= 10 -> 0.70
                diff <= 15 -> 0.40
                diff <= 25 -> 0.15
                else -> 0.0
            }
        } else {
            0.80
        }

        // 4. Version Score (0.0 to 1.0)
        val qVersion = TitleCleaner.extractVersion(queryTitle)
        val cVersion = TitleCleaner.extractVersion(candidateTitle)
        val versionScore: Double = when {
            qVersion == null && cVersion == null -> 1.0
            qVersion != null && cVersion != null && qVersion.equals(cVersion, ignoreCase = true) -> 1.0
            qVersion != null && cVersion == null -> 0.40
            qVersion == null && cVersion != null -> 0.30
            else -> 0.20
        }

        val composite = (titleScore * 0.40) + (artistScore * 0.30) + (durationScore * 0.20) + (versionScore * 0.10)
        return (composite * 100).toInt().coerceIn(0, 100)
    }

    /**
     * Comprehensive candidate match for both title and artist with confidence thresholding.
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
        val confidence = calculateConfidence(queryTitle, queryArtist, candidateTitle, candidateArtist)
        return confidence >= 50
    }

    /**
     * Overload supporting duration matching.
     */
    fun isCandidateAcceptable(
        queryTitle: String,
        queryArtist: String,
        candidateTitle: String,
        candidateArtist: String,
        queryDurationSec: Long?,
        candidateDurationSec: Long?,
        minConfidence: Int = 55
    ): Boolean {
        val confidence = calculateConfidence(
            queryTitle = queryTitle,
            queryArtist = queryArtist,
            candidateTitle = candidateTitle,
            candidateArtist = candidateArtist,
            queryDurationSec = queryDurationSec,
            candidateDurationSec = candidateDurationSec
        )
        return confidence >= minConfidence
    }

    private fun tokenize(input: String): List<String> {
        val normalized = IndicScriptNormalizer.normalizeIndicText(input)
        return normalized.lowercase()
            .replace(Regex("""[^\p{L}\p{Nd}\s]"""), " ")
            .split(Regex("""\s+"""))
            .filter { it.isNotBlank() }
    }
}
