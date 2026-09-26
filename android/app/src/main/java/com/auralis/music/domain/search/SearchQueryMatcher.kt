package com.auralis.music.domain.search

import com.auralis.music.domain.model.PlaylistResult
import com.auralis.music.domain.model.Track
import java.text.Normalizer
import java.util.Locale
import kotlin.math.min

/**
 * High-precision, unbiased search query matching and ranking engine:
 * - Evaluates candidate tracks and candidate albums against the query across explicit tiers:
 *   1. Exact title matches
 *   2. Very close title matches
 *   3. Artist matches
 *   4. Partial / relevant metadata matches
 * - Ranks songs and albums purely on relevance and YouTube Music popularity ML rank.
 * - Completely isolates recommendation candidates from actual search results.
 * - Caps supplementary recommendations at maximum 3 items.
 * - Guarantees zero duplicate songs across sections.
 */
object SearchQueryMatcher {

    enum class MatchTier(val priority: Int) {
        EXACT_TITLE(1),
        PREFIX_TITLE(2),
        CLOSE_TITLE(3),
        TYPO_MATCH(4),
        ARTIST_MATCH(5),
        METADATA_PARTIAL(6)
    }

    data class ScoredTrack(
        val track: Track,
        val tier: MatchTier,
        val score: Double,
        val originalIndex: Int = 0  // Preserves YouTube Music's popularity-based ML rank as tiebreaker
    )

    data class ScoredAlbum(
        val album: PlaylistResult,
        val tier: MatchTier,
        val score: Double,
        val originalIndex: Int = 0
    )

    /**
     * Normalizes text for comparison:
     * - maps stylized characters (e.g. Λ -> a, $ -> s, @ -> a, etc.)
     * - Unicode NFKD compatibility decomposition
     * - lowercase
     * - strips accents/diacritics
     * - removes special characters/brackets
     * - replaces multiple whitespace with single space
     */
    fun normalize(text: String): String {
        if (text.isBlank()) return ""
        val preprocessed = text
            .replace("Λ", "a")
            .replace("λ", "a")
            .replace("ʌ", "a")
            .replace("▲", "a")
            .replace("Δ", "d")
            .replace("δ", "d")
            .replace("Σ", "e")
            .replace("σ", "e")
            .replace("€", "e")
            .replace("$", "s")
            .replace("@", "a")
            .replace("¥", "y")
            .replace("†", "t")
            .replace("ø", "o")
            .replace("Ø", "o")
            .replace("ł", "l")
            .replace("Ł", "l")
            .replace("æ", "ae")
            .replace("Æ", "ae")
            .replace("œ", "oe")
            .replace("Œ", "oe")
            .replace("&", "and")

        val nfkd = Normalizer.normalize(preprocessed.lowercase(Locale.ROOT), Normalizer.Form.NFKD)
        return nfkd.replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
            .replace(Regex("[^\\p{L}\\p{M}\\p{Nd}\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /**
     * Folds common Latin romanizations of Hindi/Urdu (and similar) words onto one spelling, for
     * comparing already-[normalize]d text: w/v, ph/f, q/k, and doubled vowels (aa, ee, oo, ii).
     */
    fun romanizationKey(normalized: String): String {
        if (normalized.isBlank()) return normalized
        return normalized
            .replace("ph", "f")
            .replace('w', 'v')
            .replace('q', 'k')
            .replace("aa", "a")
            .replace("ee", "i")
            .replace("ii", "i")
            .replace("oo", "u")
    }

    /**
     * Identifies spam, WhatsApp status clips, reels clickbait, or non-song noise uploads on YouTube.
     */
    fun isJunkOrSpam(title: String, artist: String, query: String = ""): Boolean {
        val lowerT = title.lowercase(Locale.ROOT)
        val lowerA = artist.lowercase(Locale.ROOT)
        val lowerQ = query.lowercase(Locale.ROOT)

        val queryWantsStatus = lowerQ.contains("status") || lowerQ.contains("reel") || lowerQ.contains("short")
        if (queryWantsStatus) return false

        // Common status / spam patterns in title
        val spamTitlePatterns = listOf(
            "whatsapp status", "status video", "romantic status", "sad status", "attitude status",
            "short status", "30 sec status", "30sec status", "full screen status", "fullscreen status",
            "lyrics status", "4k status", "status song", "status clip", "status 4k", "status hd",
            "bhojpuri status", "lofi status", "love status", "new status", "reels video", "instagram reels",
            "viral reels", "tiktok video", "shorts clip", "subscribe for more", "status video 202"
        )
        if (spamTitlePatterns.any { lowerT.contains(it) }) return true

        // Emoji clickbait spam check (e.g. 💋, 🔞, etc. in titles combined with words like "hot", "sexy", "status")
        val isSensationalSpam = (lowerT.contains("💋") || lowerT.contains("🔞") || lowerT.contains("hot ") || lowerT.startsWith("hot")) &&
                (lowerT.contains("status") || lowerT.contains("video") || lowerA.contains("status") || lowerT.contains("romantic"))
        if (isSensationalSpam) return true

        // Spam channel/artist names
        val spamChannelPatterns = listOf(
            "status", "status king", "status video", "status 4k", "status hd", "status zone",
            "status hub", "status world", "status creation", "status studio", "r status",
            "status maker", "status point", "status adda"
        )
        if (spamChannelPatterns.any { lowerA == it || lowerA.endsWith(" status") || lowerA.startsWith("status ") }) return true

        return false
    }

    /**
     * Matches a candidate track against the query.
     * Returns the best MatchTier and relevance score, or null if track is not a match.
     */
    fun evaluateMatch(track: Track, query: String): ScoredTrack? {
        if (isJunkOrSpam(track.title, track.artist, query)) return null

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
            // 1. Exact song title matches (ignoring case, punctuation, diacritics, and trailing plural 's')
            normTitle == normQuery -> ScoredTrack(track, MatchTier.EXACT_TITLE, 100.0)

            // Title without parenthetical extras (e.g. "Dracula (feat. JENNIE)" -> "Dracula")
            cleanTitle == normQuery -> ScoredTrack(track, MatchTier.EXACT_TITLE, 95.0)

            // Same title in another romanization ("Kya Hua Tera Vada" for "kya hua tera wada",
            // "Pyaar" / "Pyar", "Phir" / "Fir"). Hindi/Urdu titles have no single Latin spelling, so
            // these are the same title, not typos; they used to fall into TYPO_MATCH, below every
            // exact-spelling upload however obscure. A true exact spelling still scores higher.
            romanizationKey(normTitle) == romanizationKey(normQuery) -> ScoredTrack(track, MatchTier.EXACT_TITLE, 97.0)
            romanizationKey(cleanTitle) == romanizationKey(normQuery) -> ScoredTrack(track, MatchTier.EXACT_TITLE, 92.0)

            // Singular/plural stemming match (e.g. "flashing light" matching "flashing lights")
            normQuery.length >= 3 && (normTitle.removeSuffix("s") == normQuery.removeSuffix("s") || cleanTitle.removeSuffix("s") == normQuery.removeSuffix("s")) -> {
                ScoredTrack(track, MatchTier.EXACT_TITLE, 94.0)
            }

            // Query contains both Title and Artist (e.g. "Dracula Tame Impala" or "Tame Impala Dracula", "Love Me Not Ravyn Lenae" or "Ravyn Lenae Love Me Not")
            ((normQuery.startsWith(normTitle) || normQuery.startsWith(cleanTitle)) && normArtist.isNotBlank() &&
                    artistTokens.any { aTok -> aTok.length > 2 && normQuery.contains(aTok) }) ||
            (normArtist.isNotBlank() && (normQuery.startsWith(normArtist) || (artistTokens.isNotEmpty() && artistTokens.all { aTok -> aTok.length > 2 && normQuery.contains(aTok) })) &&
                    (normQuery.contains(normTitle) || normQuery.contains(cleanTitle))) -> {
                ScoredTrack(track, MatchTier.EXACT_TITLE, 98.0)
            }

            // Candidate Title contains Query and Candidate Artist contains Query Artist tokens
            (normTitle.startsWith(normQuery) || cleanTitle.startsWith(normQuery)) && normArtist.isNotBlank() &&
                    queryTokens.any { qTok -> qTok.length > 2 && normArtist.contains(qTok) } -> {
                ScoredTrack(track, MatchTier.EXACT_TITLE, 96.0)
            }

            // 2. Prefix song title matches (e.g. "Lovers Rock" for query "Lovers")
            normTitle.startsWith(normQuery) || cleanTitle.startsWith(normQuery) -> {
                val ratio = normQuery.length.toDouble() / normTitle.length.coerceAtLeast(1)
                ScoredTrack(track, MatchTier.PREFIX_TITLE, 85.0 + (ratio * 10.0))
            }

            // 3. Query appears as a complete phrase/sub-phrase in title
            normTitle.contains(normQuery) || cleanTitle.contains(normQuery) -> {
                ScoredTrack(track, MatchTier.CLOSE_TITLE, 80.0)
            }

            // 3b. Query = title + extra words (usually a remembered lyric), e.g. "chogada tara" for
            // "Chogada (From "Loveyatri")". This used to not match at all, so the song people
            // actually mean fell into Recommendations below low-play exact-title uploads.
            // Scored by how much of the query the title covers; popularity decides the rest.
            (cleanTitle.length >= 4 && normQuery.startsWith("$cleanTitle ")) ||
                (normTitle.length >= 4 && normQuery.startsWith("$normTitle ")) -> {
                val covered = maxOf(
                    if (normQuery.startsWith("$cleanTitle ")) cleanTitle.length else 0,
                    if (normQuery.startsWith("$normTitle ")) normTitle.length else 0
                )
                ScoredTrack(track, MatchTier.CLOSE_TITLE, 70.0 + 20.0 * covered / normQuery.length.coerceAtLeast(1))
            }

            // 4. Typo / Levenshtein distance <= 2 for short typo tolerance (e.g. "Dragula" for "dracula")
            dist <= 2 && normQuery.length >= 4 -> {
                ScoredTrack(track, MatchTier.TYPO_MATCH, 70.0 - dist)
            }

            // 5. Artist matches
            normArtist == normQuery -> ScoredTrack(track, MatchTier.ARTIST_MATCH, 65.0)
            normArtist.startsWith(normQuery) || normArtist.contains(normQuery) -> ScoredTrack(track, MatchTier.ARTIST_MATCH, 60.0)
            queryTokens.all { qTok -> artistTokens.any { aTok -> aTok.contains(qTok) } } -> ScoredTrack(track, MatchTier.ARTIST_MATCH, 55.0)

            // 6. Album name matches
            normAlbum.isNotBlank() && (normAlbum == normQuery || normAlbum.startsWith(normQuery)) -> ScoredTrack(track, MatchTier.ARTIST_MATCH, 50.0)

            // 7. Partial / relevant metadata matches
            matchedTokensCount == queryTokens.size -> ScoredTrack(track, MatchTier.METADATA_PARTIAL, 30.0)
            queryTokens.size >= 3 && matchedTokensCount.toDouble() / queryTokens.size >= 0.70 -> ScoredTrack(track, MatchTier.METADATA_PARTIAL, 20.0)

            else -> null
        }

        return result?.let {
            it.copy(score = adjustScoreForOriginalTrack(track, query, it.score))
        }
    }

    /**
     * Matches a candidate album against the query.
     */
    fun evaluateAlbumMatch(album: PlaylistResult, query: String): ScoredAlbum? {
        val normQuery = normalize(query)
        if (normQuery.isBlank()) return null

        val normTitle = normalize(album.title)
        val normAuthor = album.author?.let { normalize(it) } ?: ""

        val queryTokens = normQuery.split(" ").filter { it.isNotBlank() }
        if (queryTokens.isEmpty()) return null

        val cleanTitle = normalize(album.title.replace(Regex("\\(.*\\)|\\[.*\\]"), ""))
        val dist = levenshteinDistance(cleanTitle.ifBlank { normTitle }, normQuery)
        val authorTokens = normAuthor.split(" ").filter { it.isNotBlank() }
        val allMetadata = "$normTitle $normAuthor"
        val matchedTokensCount = queryTokens.count { token -> allMetadata.contains(token) }

        val isExpandedCandidate = normTitle.contains("expanded") ||
                normTitle.contains("deluxe") ||
                normTitle.contains("anniversary") ||
                normTitle.contains("bonus") ||
                normTitle.contains("special edition") ||
                normTitle.contains("tour edition")

        val queryWantsExpanded = normQuery.contains("expanded") ||
                normQuery.contains("deluxe") ||
                normQuery.contains("anniversary") ||
                normQuery.contains("bonus")

        val result = when {
            // 1. Exact album title matches
            normTitle == normQuery -> ScoredAlbum(album, MatchTier.EXACT_TITLE, 100.0)
            cleanTitle == normQuery && !isExpandedCandidate -> ScoredAlbum(album, MatchTier.EXACT_TITLE, 100.0)
            cleanTitle == normQuery && isExpandedCandidate && !queryWantsExpanded -> ScoredAlbum(album, MatchTier.EXACT_TITLE, 90.0)

            // Query contains both Album Title and Artist
            (normQuery.startsWith(normTitle) || normQuery.startsWith(cleanTitle)) && normAuthor.isNotBlank() &&
                    authorTokens.any { aTok -> aTok.length > 2 && normQuery.contains(aTok) } -> {
                val score = if (isExpandedCandidate && !queryWantsExpanded) 88.0 else 98.0
                ScoredAlbum(album, MatchTier.EXACT_TITLE, score)
            }

            // Album Title contains Query and Candidate Artist contains Query Artist tokens
            (normTitle.startsWith(normQuery) || cleanTitle.startsWith(normQuery)) && normAuthor.isNotBlank() &&
                    queryTokens.any { qTok -> qTok.length > 2 && normAuthor.contains(qTok) } -> {
                val score = if (isExpandedCandidate && !queryWantsExpanded) 86.0 else 96.0
                ScoredAlbum(album, MatchTier.EXACT_TITLE, score)
            }

            // 2. Prefix album title matches
            normTitle.startsWith(normQuery) || cleanTitle.startsWith(normQuery) -> {
                val ratio = normQuery.length.toDouble() / normTitle.length.coerceAtLeast(1)
                ScoredAlbum(album, MatchTier.PREFIX_TITLE, 85.0 + (ratio * 10.0))
            }

            normTitle.contains(normQuery) || cleanTitle.contains(normQuery) -> {
                ScoredAlbum(album, MatchTier.CLOSE_TITLE, 80.0)
            }

            dist <= 2 && normQuery.length >= 4 -> {
                ScoredAlbum(album, MatchTier.TYPO_MATCH, 70.0 - dist)
            }

            // 3. Artist/Author matches
            normAuthor == normQuery -> ScoredAlbum(album, MatchTier.ARTIST_MATCH, 65.0)
            normAuthor.startsWith(normQuery) || normAuthor.contains(normQuery) -> ScoredAlbum(album, MatchTier.ARTIST_MATCH, 60.0)
            queryTokens.all { qTok -> authorTokens.any { aTok -> aTok.contains(qTok) } } -> ScoredAlbum(album, MatchTier.ARTIST_MATCH, 55.0)

            // 4. Partial metadata
            matchedTokensCount == queryTokens.size -> ScoredAlbum(album, MatchTier.METADATA_PARTIAL, 30.0)
            queryTokens.size >= 3 && matchedTokensCount.toDouble() / queryTokens.size >= 0.70 -> ScoredAlbum(album, MatchTier.METADATA_PARTIAL, 20.0)

            else -> null
        }
        return result
    }

    fun parsePlayCount(str: String?): Long {
        if (str.isNullOrBlank()) return 0L
        val clean = str.trim().lowercase(Locale.ROOT)
            .replace("plays", "")
            .replace("views", "")
            .replace("play", "")
            .replace("view", "")
            .replace("streams", "")
            .replace("listeners", "")
            .replace("subscribers", "")
            .replace("+", "")
            .replace(",", "")
            .trim()

        return try {
            when {
                clean.endsWith("billion") || clean.endsWith("b") || clean.endsWith("bn") -> {
                    val num = clean.replace(Regex("(billion|bn|b)$"), "").trim().toDoubleOrNull() ?: 0.0
                    (num * 1_000_000_000L).toLong()
                }
                clean.endsWith("million") || clean.endsWith("m") || clean.endsWith("mn") -> {
                    val num = clean.replace(Regex("(million|mn|m)$"), "").trim().toDoubleOrNull() ?: 0.0
                    (num * 1_000_000L).toLong()
                }
                clean.endsWith("crore") || clean.endsWith("cr") -> {
                    val num = clean.replace(Regex("(crore|cr)$"), "").trim().toDoubleOrNull() ?: 0.0
                    (num * 10_000_000L).toLong()
                }
                clean.endsWith("lakh") || clean.endsWith("lac") -> {
                    val num = clean.replace(Regex("(lakh|lac)$"), "").trim().toDoubleOrNull() ?: 0.0
                    (num * 100_000L).toLong()
                }
                clean.endsWith("thousand") || clean.endsWith("k") -> {
                    val num = clean.replace(Regex("(thousand|k)$"), "").trim().toDoubleOrNull() ?: 0.0
                    (num * 1_000L).toLong()
                }
                else -> clean.toLongOrNull() ?: 0L
            }
        } catch (_: Exception) {
            0L
        }
    }

    /** A cover, slowed, karaoke, remake... version the query didn't ask for. */
    fun isUnwantedDerivative(track: Track, query: String): Boolean {
        val lowerTitle = track.title.lowercase(Locale.ROOT)
        val lowerArtist = track.artist.lowercase(Locale.ROOT)
        val lowerQuery = query.lowercase(Locale.ROOT)
        val derivative = listOf("cover", "piano version", "tribute", "karaoke", "slowed", "sped up", "8d audio",
            "lo-fi", "lofi", "remake", "orchestra", "symphony").any { lowerTitle.contains(it) } ||
            listOf("tribute", "karaoke", "cover", "orchestra", "symphony").any { lowerArtist.contains(it) }
        if (!derivative) return false
        return listOf("cover", "piano", "slowed", "karaoke", "remake", "lofi", "lo-fi", "orchestra", "symphony")
            .none { lowerQuery.contains(it) }
    }

    private fun isMusicVideoTitle(title: String): Boolean {
        val t = title.lowercase(Locale.ROOT)
        return listOf("official video", "music video", "(video)", "[video]", "official visualizer", "lyric video").any { t.contains(it) }
    }

    private fun adjustScoreForOriginalTrack(track: Track, query: String, baseScore: Double): Double {
        var score = baseScore
        val lowerTitle = track.title.lowercase(Locale.ROOT)
        val lowerArtist = track.artist.lowercase(Locale.ROOT)
        val lowerQuery = query.lowercase(Locale.ROOT)

        val isMusicVideo = lowerTitle.contains("official video") ||
                lowerTitle.contains("music video") ||
                lowerTitle.contains("official music video") ||
                lowerTitle.contains("(video)") ||
                lowerTitle.contains("[video]") ||
                lowerTitle.contains("official visualizer") ||
                lowerTitle.contains("lyric video")

        if (isMusicVideo && !lowerQuery.contains("video")) {
            score -= 25.0
        }

        // Authentic studio album track bonus
        if (!track.album.isNullOrBlank() && !isMusicVideo) {
            score += 15.0
        }

        // Popularity: +12 per tenfold plays from 1M (1M +5, 10M +17, 100M +29, 1B +41). Smooth, not
        // stepped: with steps, 727M and 207M plays scored the same and a plain-titled 207M upload
        // beat the 727M film recording titled "... (part 2)". Now a bracketed suffix (-5) needs
        // ~2.6x the plays to win, a prefix match (-10) ~7x, a title+lyric match (-20) ~46x.
        val playCount = parsePlayCount(track.views)
        if (playCount >= 1_000_000L) {
            score += (5.0 + 12.0 * kotlin.math.log10(playCount / 1_000_000.0)).coerceAtMost(45.0)
        }

        if (isUnwantedDerivative(track, query)) score -= 35.0
        return score
    }

    /**
     * Partitions candidates into:
     * - First: Verified query-matched songs, ranked strictly by MatchTier priority, view counts, and scores.
     *   Exact title matches (EXACT_TITLE) always outrank typo / fuzzy matches (TYPO_MATCH).
     * - Second: Genuinely relevant supplementary recommendations (sharing artist/tokens), capped at maximum 3 items.
     * Guarantees zero duplicate songs between matches and recommendations using SongFingerprint.
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

        val cleanCandidates = candidates.filterNot { isJunkOrSpam(it.title, it.artist, trimmed) }
        val scoredMatches = mutableListOf<ScoredTrack>()
        val potentialRecommendations = mutableListOf<Track>()

        for ((index, track) in cleanCandidates.withIndex()) {
            val eval = evaluateMatch(track, trimmed)
            if (eval != null) {
                scoredMatches.add(eval.copy(originalIndex = index))
            } else {
                potentialRecommendations.add(track)
            }
        }

        // Sort actual matches:
        // 1. Titles that ARE the query (brackets aside: "... (part 2)"), then titles that start with
        //    or contain it, then typo, artist and loose metadata matches. A partial title with 100x
        //    the plays of every exact one counts as exact ("chogada tara" -> Chogada, 1.3B plays,
        //    over a 97K-play upload titled exactly "Chogada Tara").
        // 2. Covers / slowed / karaoke versions and music videos the query didn't ask for go below
        //    the originals.
        // 3. Most plays first. That is the whole rule inside a group: no point formula.
        // 4. Score, then YouTube Music's own order, for songs without a play count.
        val exactPlays = scoredMatches.filter { it.tier == MatchTier.EXACT_TITLE }.maxOfOrNull { parsePlayCount(it.track.views) } ?: 0L
        fun group(st: ScoredTrack): Int = when (st.tier) {
            MatchTier.EXACT_TITLE -> 1
            MatchTier.PREFIX_TITLE, MatchTier.CLOSE_TITLE ->
                if (parsePlayCount(st.track.views) >= maxOf(exactPlays, 1L) * 100L) 1 else 2
            MatchTier.TYPO_MATCH -> 3
            MatchTier.ARTIST_MATCH -> 4
            MatchTier.METADATA_PARTIAL -> 5
        }
        val sortedMatchedTracks = scoredMatches
            .sortedWith(
                compareBy<ScoredTrack> { group(it) }
                    .thenBy { isUnwantedDerivative(it.track, trimmed) || (isMusicVideoTitle(it.track.title) && !trimmed.contains("video", ignoreCase = true)) }
                    .thenByDescending { parsePlayCount(it.track.views) }
                    .thenByDescending { it.score }
                    .thenBy { it.originalIndex }
            )
            .map { it.track }

        // Deduplicate matched songs so higher quality studio audio tracks take precedence over music videos,
        // while preserving distinct performance cuts (e.g. Live, Acoustic, Remix) for user search queries.
        val rankedMatches = com.auralis.music.domain.recommendations.TrackDeduplicator.deduplicateTracks(sortedMatchedTracks, matchAlternateVersions = false)

        val matchedFingerprints = rankedMatches.map { com.auralis.music.domain.recommendations.TrackDeduplicator.getSongFingerprint(it) }
        val matchedArtists = rankedMatches.map { normalize(it.artist) }.filter { it.isNotBlank() }.toSet()
        val queryTokens = normalize(trimmed).split(" ").filter { it.length > 2 }.toSet()

        // Recommendations: ONLY include items that are NOT duplicate cuts or video versions of any matched song.
        val nonMatchedCandidates = potentialRecommendations
            .filterNot { candidate ->
                val candFp = com.auralis.music.domain.recommendations.TrackDeduplicator.getSongFingerprint(candidate)
                matchedFingerprints.any { com.auralis.music.domain.recommendations.TrackDeduplicator.isDuplicateSong(it, candFp) }
            }
            .filter { !com.auralis.music.domain.recommendations.TrackDeduplicator.isVideoOrBloatedTrack(it) }

        // Prioritize items with token or artist overlap, sorted strictly by view counts
        val (related, other) = nonMatchedCandidates.partition { candidate ->
            val candArtist = normalize(candidate.artist)
            val candTitle = normalize(candidate.title)
            val isArtistRelated = matchedArtists.any { it.isNotBlank() && (candArtist.contains(it) || it.contains(candArtist)) }
            val hasTokenOverlap = queryTokens.any { qTok -> candTitle.contains(qTok) || candArtist.contains(qTok) }
            isArtistRelated || hasTokenOverlap
        }

        val sortedRelated = related.sortedByDescending { parsePlayCount(it.views) }
        val sortedOther = other.sortedByDescending { parsePlayCount(it.views) }

        // If there are genuine related recommendations (e.g. tracks by the same artist), use ONLY those.
        // Otherwise fall back to other non-matching candidates.
        val orderedRecommendations = if (sortedRelated.isNotEmpty()) sortedRelated else sortedOther
        val recommendations = com.auralis.music.domain.recommendations.TrackDeduplicator.deduplicateTracks(orderedRecommendations)
            .take(maxRecommendations)

        return Pair(rankedMatches, recommendations)
    }

    /**
     * Ranks candidate albums fairly and strictly by relevance and popularity ML rank.
     */
    fun rankAlbums(candidates: List<PlaylistResult>, query: String): List<PlaylistResult> {
        val trimmed = query.trim()
        if (trimmed.isBlank() || candidates.isEmpty()) return candidates

        val scored = mutableListOf<ScoredAlbum>()
        val nonMatching = mutableListOf<PlaylistResult>()

        for ((index, album) in candidates.withIndex()) {
            val eval = evaluateAlbumMatch(album, trimmed)
            if (eval != null) {
                scored.add(eval.copy(originalIndex = index))
            } else {
                nonMatching.add(album)
            }
        }

        val ranked = scored
            .sortedWith(
                compareBy<ScoredAlbum> { it.tier.priority }
                    .thenByDescending { it.score }
                    .thenBy { it.originalIndex }
            )
            .map { it.album }
            .distinctBy { it.id }

        val rankedIds = ranked.map { it.id }.toSet()
        val remaining = nonMatching.filterNot { rankedIds.contains(it.id) }.distinctBy { it.id }

        return ranked + remaining
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

        // A different recording can never be "close enough": remix / radio edit / part 2 /
        // an uncredited feature all sing the same words at different times.
        if (recordingMismatch(target, candidate) != null) return -1.0

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
                writtenInDifferentScripts(normTargetArtist, normCandArtist) -> score += 15.0 // "Hiroaki Tommy Tominaga" vs "富永TOMMY弘明": can't compare
                else -> {
                    // Fatal mismatch: candidate artist has zero relation to target artist (e.g. Mau P for Tame Impala)
                    return -1.0
                }
            }
        } else {
            score += 15.0 // Neutral when no target artist
        }

        // 2b. YouTube Music credits someone the source doesn't. Not a rejection: YouTube often adds
        // real co-singers Spotify omits, or writes names in another script ("富永TOMMY弘明").
        if (normTargetArtist.isNotBlank() &&
            hasUncreditedArtist(normalize("${target.artist} ${target.title} ${target.album.orEmpty()}"), candidate.artist)
        ) {
            score -= 20.0
        }

        // 3. YouTube Music ML Rank Bonus (0 - 15 points)
        if (index == 0) score += 15.0
        else if (index in 1..2) score += 8.0

        // 4. Duration Proximity (0 - 20 points; beyond MAX_RECORDING_DELTA_SEC was rejected above)
        val knowsDurations = target.duration > 0 && candidate.duration > 0
        if (knowsDurations) {
            val delta = kotlin.math.abs(target.duration - candidate.duration)
            when {
                delta <= 2 -> score += 20.0
                delta <= 5 -> score += 15.0
                delta <= 10 -> score += 8.0
            }
        }

        // 4b. Same album as the source (e.g. Spotify's "Hatful of Hollow" over a compilation remaster)
        // Same release, ignoring edition labels only: a prefix match would call
        // "Dracula (with JENNIE) + Instrumental" the same album as "Dracula".
        val targetAlbum = albumIdentity(target.album)
        val candAlbum = albumIdentity(candidate.album)
        if (targetAlbum.isNotBlank() && targetAlbum == candAlbum) {
            score += 25.0
        }

        // 4c. Extra credited artists the source doesn't list (e.g. "Tame Impala & JENNIE" for a
        // solo "Tame Impala" track) mark a collab/remix release; prefer the clean credit.
        if (normTargetArtist.isNotBlank() && hasUncreditedArtist("$normTargetArtist $normTargetTitle", candidate.artist)) {
            score -= 40.0
        }

        // 4d. Remaster tags don't change the song but do change the master; prefer the one the source names.
        if (hasRemasterTag(target.title) != hasRemasterTag(candidate.title)) score -= 10.0

        // 5. Anti-Derivative & Quality Filtering
        val lowerCandTitle = candidate.title.lowercase(Locale.ROOT)
        val lowerTargetTitle = target.title.lowercase(Locale.ROOT)
        for (keyword in derivativeKeywords) {
            if (lowerCandTitle.contains(keyword) && !lowerTargetTitle.contains(keyword)) {
                score -= 250.0 // Disqualify unwanted remixes, DJ versions, lofi, covers, ringtones
            }
        }

        val isCandVideo = com.auralis.music.domain.recommendations.TrackDeduplicator.isVideoOrBloatedTrack(candidate)
        if (isCandVideo && !lowerTargetTitle.contains("video")) {
            score -= 30.0
        }

        if (!candidate.album.isNullOrBlank()) {
            score += 20.0 // Authentic studio album release bonus
        }

        // Typical-length bonus only when we can't compare lengths: it would otherwise favour a
        // 4:36 radio edit over a 5:58 album cut.
        if (!knowsDurations && candidate.duration in 90..330) {
            score += 10.0 // Standard song length bonus
        } else if (candidate.duration > 330 && isCandVideo && !knowsDurations) {
            score -= 25.0
        }

        return score
    }

    /**
     * The same recording on Spotify and YouTube Music is 0–2 s apart (measured over 150 library
     * songs: 109 were exactly 1 s, from rounding). Another recording can be only 4–15 s off:
     * "Mere Mehboob Qayamat Hogi" Revival 243 s vs the 1964 original 229 s, "Moral of the Story"
     * feat. Niall Horan 198 s vs solo 202 s (YouTube Music often lists only the lead artist on a
     * featured version, so the length is what tells them apart).
     */
    const val MAX_RECORDING_DELTA_SEC = 3L

    // Tags that mean a different recording or arrangement. Matched only inside bracketed or
    // " - " suffix segments, so a title like "DJ Got Us Fallin' in Love" is safe.
    private val VERSION_MARKERS = listOf(
        "remix" to Regex("""\bre-?mix(ed)?\b"""),
        "mix" to Regex("""\b(mix|mixed)\b"""),
        "edit" to Regex("""\bedit\b"""),
        "extended" to Regex("""\bextended\b"""),
        "live" to Regex("""\blive\b"""),
        "acoustic" to Regex("""\b(acoustic|unplugged)\b"""),
        "instrumental" to Regex("""\b(instrumental|karaoke)\b"""),
        "slowed" to Regex("""\b(slowed|reverb|sped\s*up|speed\s*up|nightcore|lo-?fi|8d|bass\s*boosted)\b"""),
        "cover" to Regex("""\bcover\b"""),
        "mashup" to Regex("""\bmash-?up\b"""),
        "demo" to Regex("""\bdemo\b"""),
        "reprise" to Regex("""\breprise\b"""),
        "part" to Regex("""\b(part|pt)\.?\s*\d+\b"""),
        "dj" to Regex("""\bdj\b"""),
        "version" to Regex("""\bversion\b""")
    )

    // "Version" labels that name the original rather than a different cut.
    private val NEUTRAL_VERSION_REGEX = Regex("""\b(album|original|single|studio|mono|stereo|explicit|clean|main|full)\s+version\b""")
    private val REMASTER_REGEX = Regex("""(?i)\bremaster(ed)?\b""")
    private val FEATURE_REGEX = Regex("""(?i)\b(?:feat\.?|ft\.?|featuring|with)\s+([^()\[\]\-]+)""")

    /** Bracketed segments plus any " - suffix": the parts of a title that describe the version. */
    private fun tagSegments(title: String): String {
        val lower = title.lowercase(Locale.ROOT)
        val bracketed = Regex("""[\(\[\{]([^)\]\}]*)[\)\]\}]""").findAll(lower).map { it.groupValues[1] }.toList()
        val dashSuffix = lower.split(Regex("""\s[-–—]\s""")).drop(1)
        return (bracketed + dashSuffix).joinToString(" | ")
    }

    private fun versionMarkers(title: String): Set<String> {
        val tags = NEUTRAL_VERSION_REGEX.replace(tagSegments(title), " ")
        return VERSION_MARKERS.filter { (_, regex) -> regex.containsMatchIn(tags) }.map { it.first }.toSet()
    }

    private fun hasRemasterTag(title: String): Boolean = REMASTER_REGEX.containsMatchIn(tagSegments(title))

    private val ARTIST_SEPARATOR_REGEX = Regex("""(?i)[,&/+]|\b(?:feat\.?|ft\.?|featuring|with|and|x|vs\.?)\s""")

    /** True if raw [candArtists] names someone whose words appear nowhere in the [normalize]d [creditedText]. */
    private fun writtenInDifferentScripts(a: String, b: String): Boolean {
        fun nonLatin(t: String) = t.any { it.isLetter() && it.code > 0x024F }
        return nonLatin(a) != nonLatin(b)
    }

    private fun hasUncreditedArtist(creditedText: String, candArtists: String): Boolean {
        if (candArtists.isBlank()) return false
        val credited = creditedText.split(" ").filter { it.length > 1 }.toSet()
        return candArtists.split(ARTIST_SEPARATOR_REGEX)
            .map { normalize(it) }
            .map { name -> name.split(" ").filter { it.length > 1 } }
            .filter { it.isNotEmpty() }
            .any { tokens -> tokens.none { it in credited } }
    }

    private val EDITION_WORDS = Regex(
        """\b(deluxe|remaster(ed)?|expanded|edition|anniversary|super|bonus|special|collector'?s|digital|version|tracks?|reissue|\d{2,4}|years?|th|single|ep|mono|stereo)\b""",
        RegexOption.IGNORE_CASE
    )

    /**
     * An album name reduced to what identifies the release: "Urban Hymns (Remastered 2016)",
     * "Urban Hymns (Super Deluxe / Remastered 2016)" and "Urban Hymns" are one album, while
     * "Dracula (with JENNIE) + Instrumental" stays distinct from "Dracula". Bracketed parts are
     * dropped only when they hold nothing but edition words.
     */
    fun albumIdentity(album: String?): String {
        if (album.isNullOrBlank()) return ""
        var a = album.replace(Regex("""\s+-\s+(single|ep)\s*$""", RegexOption.IGNORE_CASE), "")
        a = Regex("""[\(\[]([^\)\]]*)[\)\]]""").replace(a) { m ->
            val rest = EDITION_WORDS.replace(m.groupValues[1], " ").replace(Regex("""[^\p{L}\p{Nd}]+"""), "")
            if (rest.isEmpty()) " " else m.value
        }
        return normalize(a)
    }

    /**
     * Why [candidate] can't be the same recording as [target], or null if it can be.
     * Hard gates only: a wrong version plays the right words at the wrong times, which
     * breaks lyric sync no matter which lyrics provider answers.
     */
    fun recordingMismatch(target: Track, candidate: Track): String? {
        if (target.duration > 0 && candidate.duration > 0) {
            val delta = kotlin.math.abs(target.duration - candidate.duration)
            if (delta > MAX_RECORDING_DELTA_SEC) return "length ${candidate.duration}s vs ${target.duration}s"
        }

        val extraMarkers = versionMarkers(candidate.title) - versionMarkers(target.title)
        if (extraMarkers.isNotEmpty()) return "version tag ${extraMarkers.joinToString()}"

        // Someone the source never credits, named anywhere on the candidate: "(feat. X)" in its
        // title, its artist list ("Tame Impala & JENNIE"), or its release ("Dracula (with JENNIE)").
        // YouTube Music lists the JENNIE version as plain "Dracula" by "Tame Impala", same length.
        val targetArtist = if (target.artist.equals("Spotify Artist", ignoreCase = true)) "" else target.artist
        if (targetArtist.isNotBlank()) {
            val credited = normalize("$targetArtist ${target.title} ${target.album.orEmpty()}")
            val features = (FEATURE_REGEX.findAll(tagSegments(candidate.title)) +
                FEATURE_REGEX.findAll(tagSegments(candidate.album.orEmpty()))).map { it.groupValues[1] }
            if (features.any { hasUncreditedArtist(credited, it) }) return "uncredited featured artist"

        }
        return null
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

    /**
     * Strictly verifies whether an album's author matches the target artist name.
     * Prevents unrelated artists (e.g. Ojax, Various Artists, Karaoke bands)
     * from being falsely attributed to the target artist.
     */
    fun isAuthorMatch(albumAuthor: String?, artistName: String): Boolean {
        if (albumAuthor.isNullOrBlank() || artistName.isBlank()) return false
        val normAuth = normalize(albumAuthor)
        val normArt = normalize(artistName)
        if (normAuth.isBlank() || normArt.isBlank()) return false
        if (normAuth == normArt) return true

        // Reject generic author strings that do not represent the specific artist
        val genericAuthors = setOf("various artists", "various", "unknown artist", "soundtrack", "original soundtrack", "va")
        if (normAuth in genericAuthors) return false

        // Split multi-artist tokens (handles commas, &, feat, ft, with, /)
        val splitRegex = Regex("[,&/]|\\b(feat|ft|with)\\b")
        val authArtists = normAuth.split(splitRegex).map { it.trim() }.filter { it.length > 1 }
        val targetArtists = normArt.split(splitRegex).map { it.trim() }.filter { it.length > 1 }

        for (target in targetArtists) {
            for (auth in authArtists) {
                if (auth == target) return true
                if (auth.length >= 4 && target.length >= 4) {
                    if (auth.contains(target) || target.contains(auth)) return true
                }
            }
        }
        return false
    }
}
