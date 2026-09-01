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
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
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
            // 1. Exact song title matches (ignoring case, punctuation, diacritics)
            normTitle == normQuery -> ScoredTrack(track, MatchTier.EXACT_TITLE, 100.0)

            // Title without parenthetical extras (e.g. "Dracula (feat. JENNIE)" -> "Dracula")
            cleanTitle == normQuery -> ScoredTrack(track, MatchTier.EXACT_TITLE, 95.0)

            // Query contains both Title and Artist (e.g. "Dracula Tame Impala")
            (normQuery.startsWith(normTitle) || normQuery.startsWith(cleanTitle)) && normArtist.isNotBlank() &&
                    artistTokens.any { aTok -> aTok.length > 2 && normQuery.contains(aTok) } -> {
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

        // View count / Popularity boost (Most viewed tracks like The Weeknd - Starboy with billions of plays rank #1)
        val playCount = parsePlayCount(track.views)
        if (playCount > 0) {
            when {
                playCount >= 1_000_000_000L -> score += 35.0 // Billion+ plays
                playCount >= 100_000_000L   -> score += 25.0 // 100M+ plays
                playCount >= 10_000_000L    -> score += 15.0 // 10M+ plays
                playCount >= 1_000_000L     -> score += 5.0  // 1M+ plays
            }
        }

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
                lowerTitle.contains("orchestra") ||
                lowerTitle.contains("symphony") ||
                lowerArtist.contains("tribute") ||
                lowerArtist.contains("karaoke") ||
                lowerArtist.contains("cover") ||
                lowerArtist.contains("orchestra") ||
                lowerArtist.contains("symphony")

        if (isCoverOrDerivative) {
            val queryWantsDerivative = lowerQuery.contains("cover") ||
                    lowerQuery.contains("piano") ||
                    lowerQuery.contains("slowed") ||
                    lowerQuery.contains("karaoke") ||
                    lowerQuery.contains("remake") ||
                    lowerQuery.contains("lofi") ||
                    lowerQuery.contains("lo-fi") ||
                    lowerQuery.contains("orchestra") ||
                    lowerQuery.contains("symphony")
            if (!queryWantsDerivative) {
                score -= 35.0
            }
        }
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
        // 1. Tier priority (EXACT_TITLE -> PREFIX_TITLE -> CLOSE_TITLE -> TYPO_MATCH -> ARTIST_MATCH -> METADATA_PARTIAL)
        // 2. Highest view / play counts within that tier (e.g. Tame Impala Dracula with 232M views beats smaller Draculas)
        // 3. Score
        // 4. Original index
        val sortedMatchedTracks = scoredMatches
            .sortedWith(
                compareBy<ScoredTrack> {
                    when (it.tier) {
                        MatchTier.EXACT_TITLE -> 1
                        MatchTier.PREFIX_TITLE, MatchTier.CLOSE_TITLE -> 2
                        MatchTier.TYPO_MATCH -> 3
                        MatchTier.ARTIST_MATCH -> 4
                        MatchTier.METADATA_PARTIAL -> 5
                    }
                }
                    .thenByDescending { parsePlayCount(it.track.views) }
                    .thenByDescending { it.score }
                    .thenBy { it.originalIndex }
            )
            .map { it.track }

        // Deduplicate matched songs so higher quality studio audio tracks take precedence over music videos
        val rankedMatches = com.auralis.music.domain.recommendations.TrackDeduplicator.deduplicateTracks(sortedMatchedTracks)

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

        val isCandVideo = com.auralis.music.domain.recommendations.TrackDeduplicator.isVideoOrBloatedTrack(candidate)
        if (isCandVideo && !lowerTargetTitle.contains("video")) {
            score -= 30.0
        }

        if (!candidate.album.isNullOrBlank()) {
            score += 20.0 // Authentic studio album release bonus
        }

        if (candidate.duration in 90..330) {
            score += 10.0 // Standard song length bonus
        } else if (candidate.duration > 330 && isCandVideo) {
            score -= 25.0
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
