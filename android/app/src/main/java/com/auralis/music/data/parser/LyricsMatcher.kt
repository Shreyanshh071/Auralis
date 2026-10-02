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
    /**
     * Flexible track title matcher with version awareness and Indic transliteration cross-checking.
     */
    fun isTitleMatching(queryTitle: String, candTitle: String): Boolean {
        val qBare = TitleCleaner.extractBareSongTitle(queryTitle).lowercase().trim()
        val cBare = TitleCleaner.extractBareSongTitle(candTitle).lowercase().trim()
        if (qBare.isBlank() || cBare.isBlank()) return false
        if (qBare == cBare) return true

        // Reject multi-song mashup/medleys when query is a single song
        val isQueryMashup = qBare.contains("mashup") || qBare.contains("medley") || qBare.contains(" / ")
        val isCandMashup = cBare.contains("mashup") || cBare.contains("medley") || cBare.contains(" / ") || cBare.contains("sangeet mix") || cBare.contains("sangeet")
        if (!isQueryMashup && isCandMashup) {
            return false
        }

        // Subtitle / Soundtrack / Parenthetical extension match:
        // e.g. query "Sunflower", candidate "Sunflower (Spider-Man: Into the Spider-Verse)"
        // or query "Starboy", candidate "Starboy (feat. Daft Punk)"
        val cWithoutSub = cBare.substringBefore(" (").substringBefore(" [").substringBefore(" - ").substringBefore(": ").trim()
        val qWithoutSub = qBare.substringBefore(" (").substringBefore(" [").substringBefore(" - ").substringBefore(": ").trim()
        if ((cWithoutSub.isNotBlank() && qBare == cWithoutSub) ||
            (qWithoutSub.isNotBlank() && cBare == qWithoutSub) ||
            (qWithoutSub.isNotBlank() && qWithoutSub == cWithoutSub)
        ) {
            return true
        }

        // Prefix check for small queries: if candidate starts with full query and subsequent text is parenthetical/separator
        if (cBare.startsWith(qBare) && (cBare.length == qBare.length || cBare[qBare.length] in " ([-:")) {
            return true
        }
        if (qBare.startsWith(cBare) && (qBare.length == cBare.length || qBare[cBare.length] in " ([-:")) {
            return true
        }

        val qPhonetic = IndicScriptNormalizer.transliterateToPhoneticLatin(qBare)
        val cPhonetic = IndicScriptNormalizer.transliterateToPhoneticLatin(cBare)
        if (qPhonetic == cPhonetic) return true

        val qCanonical = IndicScriptNormalizer.toPhoneticCanonical(qPhonetic)
        val cCanonical = IndicScriptNormalizer.toPhoneticCanonical(cPhonetic)
        if (qCanonical == cCanonical) return true

        val qTokens = tokenize(qCanonical)
        val cTokens = tokenize(cCanonical)
        if (qTokens.isEmpty() || cTokens.isEmpty()) return false

        // Exact prefix token match (e.g. query ["sunflower"] in ["sunflower", "spider", "man", ...])
        if (cTokens.size >= qTokens.size && cTokens.subList(0, qTokens.size) == qTokens) {
            return true
        }

        val qSet = qTokens.toSet()
        val cSet = cTokens.toSet()
        val intersection = qSet.intersect(cSet).size
        val dice = (2.0 * intersection) / (qSet.size + cSet.size)

        val matches = qTokens.count { qWord ->
            cSet.any { cWord ->
                qWord == cWord || (qWord.length >= 4 && cWord.length >= 4 && (qWord.contains(cWord) || cWord.contains(qWord)))
            }
        }
        val matchRatio = matches.toDouble() / qTokens.size
        val cMatchRatio = matches.toDouble() / cTokens.size

        // When query is small (1 or 2 words), ALL query words must be present in candidate.
        // A single shared common word (e.g. "Let" between "Let Down" and "Let You", or "Bad" between "Bad Guy" and "Bad Boy")
        // is NOT a match and represents a completely different song!
        if (qTokens.size <= 2) {
            if (matchRatio < 1.0) return false
            return cTokens.size <= 4 && (dice >= 0.50 || cMatchRatio >= 0.50)
        }

        if (dice >= 0.70) return true

        return (matchRatio >= 0.80 && cMatchRatio >= 0.50) || (matches >= 2 && matches == qTokens.size && cMatchRatio >= 0.50)
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
        val qCoreTitle = TitleCleaner.extractBareSongTitle(queryTitle)
        val cCoreTitle = TitleCleaner.extractBareSongTitle(candidateTitle)

        val qPhoneticCore = IndicScriptNormalizer.transliterateToPhoneticLatin(qCoreTitle)
        val cPhoneticCore = IndicScriptNormalizer.transliterateToPhoneticLatin(cCoreTitle)
        val qCanonicalCore = IndicScriptNormalizer.toPhoneticCanonical(qPhoneticCore)
        val cCanonicalCore = IndicScriptNormalizer.toPhoneticCanonical(cPhoneticCore)

        // 1. Title Score (0.0 to 1.0)
        val isTitleMatch = isTitleMatching(queryTitle, candidateTitle)
        if (!isTitleMatch) {
            return 0
        }

        val directTitleDice = diceCoefficient(qCoreTitle, cCoreTitle)
        val phoneticTitleDice = diceCoefficient(qPhoneticCore, cPhoneticCore)
        val canonicalTitleDice = diceCoefficient(qCanonicalCore, cCanonicalCore)
        val isExact = if (qCoreTitle.equals(cCoreTitle, ignoreCase = true) || qPhoneticCore.equals(cPhoneticCore, ignoreCase = true) || qCanonicalCore.equals(cCanonicalCore, ignoreCase = true)) 1.0 else 0.0
        val isPrefix = cCanonicalCore.startsWith(qCanonicalCore) || qCanonicalCore.startsWith(cCanonicalCore)
        val rawTitleScore = maxOf(isExact, directTitleDice, phoneticTitleDice, canonicalTitleDice)
        val titleScore = if (isExact == 1.0) 1.0 else if (isPrefix) maxOf(rawTitleScore, 0.85) else rawTitleScore

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

        val hasKnownQueryArtist = qArtistClean.isNotBlank() &&
            !qArtistClean.equals("Unknown Artist", ignoreCase = true) &&
            !qArtistClean.equals("YouTube Music", ignoreCase = true)
        val hasKnownCandArtist = cArtistClean.isNotBlank() &&
            !cArtistClean.equals("Unknown Artist", ignoreCase = true)

        // Strict guard 1: If both artists are known non-generic entities and have ZERO relationship,
        // it CANNOT be the same song (e.g. "Alessandro Veloz" or "Radiohead" vs "Cheryl").
        if (hasKnownQueryArtist && hasKnownCandArtist && artistScore == 0.0) {
            return 0
        }

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

        // Strict guard 2: if artist fails AND duration fails (>15s mismatch), it is a different song
        if (artistScore == 0.0 && durationScore <= 0.40) {
            return 0
        }

        // 4. Version Score (0.0 to 1.0)
        val qVersion = TitleCleaner.extractVersion(queryTitle)
            ?: queryAlbum?.let { TitleCleaner.extractVersion(it) }
        val cVersion = TitleCleaner.extractVersion(candidateTitle)
            ?: candidateAlbum?.let { TitleCleaner.extractVersion(it) }

        val isQTimingAltering = com.auralis.music.domain.lyrics.LyricsAlignmentEngine.isTimingAlteringVersion(qVersion)
        val isCTimingAltering = com.auralis.music.domain.lyrics.LyricsAlignmentEngine.isTimingAlteringVersion(cVersion)

        val versionScore: Double = when {
            qVersion == null && cVersion == null -> 1.0
            qVersion != null && cVersion != null && (qVersion.equals(cVersion, ignoreCase = true) || qVersion.contains(cVersion, ignoreCase = true) || cVersion.contains(qVersion, ignoreCase = true)) -> 1.0
            isQTimingAltering && !isCTimingAltering -> 0.10 // Severely penalize matching studio cut when acoustic/live/remix/orchestral requested
            !isQTimingAltering && isCTimingAltering -> 0.10
            else -> 0.20
        }

        // Strict guard 3: Instrumental / Orchestral version protection
        // When query is an orchestral, instrumental, piano, or karaoke version,
        // it must match the exact core song and have compatible artist
        val isQueryInstrumentalOrOrchestral = qVersion != null && (
            qVersion.equals("Orchestral", ignoreCase = true) ||
            qVersion.equals("Instrumental", ignoreCase = true) ||
            qVersion.equals("Karaoke", ignoreCase = true) ||
            qVersion.equals("Piano Version", ignoreCase = true)
        )
        if (isQueryInstrumentalOrOrchestral && (artistScore == 0.0 || isExact == 0.0)) {
            return 0
        }

        // Version clash penalty when timing-altering versions differ
        val versionClashPenalty = if ((isQTimingAltering || isCTimingAltering) &&
            (qVersion == null || cVersion == null || !qVersion.equals(cVersion, ignoreCase = true))
        ) {
            25
        } else {
            0
        }

        // 5. Album Score & Consistency Check
        val albumBonus = if (!queryAlbum.isNullOrBlank() && !candidateAlbum.isNullOrBlank()) {
            val qAlb = queryAlbum.trim().lowercase()
            val cAlb = candidateAlbum.trim().lowercase()
            if (qAlb == cAlb || qAlb.contains(cAlb) || cAlb.contains(qAlb) || diceCoefficient(qAlb, cAlb) >= 0.70) {
                5 // Bonus for verified album match
            } else {
                0
            }
        } else {
            0
        }

        val composite = (titleScore * 0.40) + (artistScore * 0.30) + (durationScore * 0.20) + (versionScore * 0.10)
        return ((composite * 100).toInt() + albumBonus - versionClashPenalty).coerceIn(0, 100)
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
