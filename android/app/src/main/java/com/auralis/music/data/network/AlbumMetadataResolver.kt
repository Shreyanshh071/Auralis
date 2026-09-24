package com.auralis.music.data.network

import android.util.LruCache
import com.auralis.music.domain.search.SearchQueryMatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder

data class ResolvedAlbum(
    val albumTitle: String,
    val albumId: String? = null,
    val albumArt: String? = null,
    val artistName: String? = null,
    val isSingle: Boolean = false
)

object AlbumMetadataResolver {
    private const val ITUNES_SEARCH_API = "https://itunes.apple.com/search"
    internal val memoryCache = LruCache<String, ResolvedAlbum>(500)

    fun clearCache() {
        synchronized(memoryCache) {
            memoryCache.evictAll()
        }
    }

    /**
     * Cleans an album title by stripping soundtrack tags, deluxe/anniversary/remastered markers,
     * parentheticals, and punctuation to extract the core album name.
     */
    fun cleanAlbumTitle(title: String): String {
        return title
            .replace(Regex("""(?i)\s*[\(\[]\s*(original motion picture soundtrack|original soundtrack|motion picture soundtrack|soundtrack|from\s+["']?.*?["']?|deluxe(\s+edition)?|bonus(\s+track\s+version)?|special\s+edition|expanded(\s+edition)?|anniversary(\s+edition)?|remastered)\s*[\)\]]"""), "")
            .replace(Regex("""(?i)\s*-\s*(original motion picture soundtrack|original soundtrack|soundtrack|deluxe(\s+edition)?|ost)\b.*$"""), "")
            .replace(Regex("""(?i)\b(original motion picture soundtrack|original soundtrack)\b.*$"""), "")
            .replace(Regex("""["'“”‘’]"""), "")
            .trim()
            .ifBlank { title.trim() }
    }

    /**
     * Cleans a track title by stripping soundtrack tags, parentheticals, audio/video descriptors,
     * and punctuation for clean matching.
     */
    fun cleanTrackTitle(title: String): String {
        return title
            .replace(Regex("""(?i)[\(\[]\s*From\s+["']?.*?["']?\s*[\)\]]"""), "")
            .replace(Regex("""(?i)\b(official\s+video|official\s+audio|audio|video|lyric\s+video|visualizer|remix|live|feat\..*?|ft\..*?)\b"""), "")
            .replace(Regex("""[\(\[\{].*?[\)\]\}]"""), "")
            .replace(Regex("""["'“”‘’]"""), "")
            .trim()
            .ifBlank { title.trim() }
    }

    /**
     * Extracts soundtrack source if present, e.g. "Apna Bana Le (From "Bhediya")" -> "Bhediya".
     */
    fun extractSoundtrackTag(title: String): String? {
        val match = Regex("""(?i)[\(\[]\s*From\s+["']?([^"'\]\)]+)["']?\s*[\)\]]""").find(title)
        return match?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
    }

    private val defaultInnerTubeClient by lazy { InnerTubeClient() }

    /**
     * Synchronously retrieves a previously resolved authentic album from memory cache.
     */
    fun getCached(trackTitle: String, artistName: String): ResolvedAlbum? {
        val cleanTitle = cleanTrackTitle(trackTitle)
        val cleanArtist = artistName
            .split(",", "&", "feat.", "ft.", "/").firstOrNull()?.trim()
            ?: artistName.trim()
        val cacheKey = "${cleanArtist.lowercase()}::${cleanTitle.lowercase()}"
        synchronized(memoryCache) {
            return memoryCache.get(cacheKey)
        }
    }

    /**
     * Checks whether an album string is just redundant with the track title or represents a single.
     */
    fun isRedundantOrSingle(album: String?, trackTitle: String): Boolean {
        if (album.isNullOrBlank()) return true
        val cleanAlb = cleanTrackTitle(album).lowercase()
        val cleanTit = cleanTrackTitle(trackTitle).lowercase()
        if (cleanAlb.isBlank() || cleanAlb == cleanTit) return true

        val rawAlb = album.trim().lowercase()
        val rawTit = trackTitle.trim().lowercase()
        if (rawAlb == rawTit) return true

        // Check for compound single / mashup / collaboration titles:
        // e.g. "Song A / Song B", "Track 1 & Track 2", "Track A x Track B", "Track A vs. Track B"
        if (album.contains("/") || album.contains("&") || album.contains("+") || album.contains(" - ") ||
            album.contains(Regex("""(?i)\s+(?:x|vs\.?|feat\.?|ft\.?)\s+"""))) {
            val separators = Regex("""(?i)\s*[/&+]\s*|\s+-\s+|\s+(?:x|vs\.?|feat\.?|ft\.?)\s+""")
            val parts = album.split(separators).map { cleanTrackTitle(it).trim().lowercase() }.filter { it.isNotBlank() }
            if (parts.any { it == cleanTit || it == rawTit || (cleanTit.length > 3 && it.contains(cleanTit)) || (it.length > 3 && cleanTit.contains(it)) }) {
                return true
            }
        }

        // Standard single / EP suffixes and single variants
        if (rawAlb.endsWith("- single") || rawAlb.endsWith("(single)") ||
            rawAlb.endsWith("- ep") || rawAlb.endsWith("(ep)") ||
            rawAlb.endsWith(" - single") || rawAlb.endsWith(" (single)") ||
            rawAlb.endsWith("- 2 track single") || rawAlb.contains("single version") ||
            rawAlb.contains("2-track single") || rawAlb.contains("sampler")) return true

        if (rawAlb.startsWith(cleanTit) && (rawAlb.contains("single") || rawAlb.contains("ep"))) return true
        if (rawAlb.contains("mashup") || rawAlb.contains("bootleg") || (rawAlb.contains("remix") && !rawAlb.contains("remixes"))) return true
        if (rawAlb.contains("play") || rawAlb.contains("view") || rawAlb.contains("listener") || rawAlb.contains("subscriber")) return true

        return false
    }

    /**
     * Overload for resolveAlbum with optional knownAlbum title.
     */
    suspend fun resolveAlbum(
        trackTitle: String,
        artistName: String,
        knownAlbum: String?
    ): ResolvedAlbum? = resolveAlbum(trackTitle, artistName, null, knownAlbum)

    /**
     * Attempts to resolve the authentic parent studio album for a given track and artist.
     * Uses Apple Music / iTunes Search API and YouTube Music album queries to identify the genuine
     * parent collection, resolving its YouTube Music browse ID (MPRE...).
     */
    suspend fun resolveAlbum(
        trackTitle: String,
        artistName: String,
        innerTubeClient: InnerTubeClient? = null,
        knownAlbum: String? = null
    ): ResolvedAlbum? = withContext(Dispatchers.IO) {
        val cleanTitle = cleanTrackTitle(trackTitle)
        val soundtrackTag = extractSoundtrackTag(trackTitle)
        val cleanKnownAlbum = knownAlbum?.let { cleanAlbumTitle(it) }?.takeIf { !isRedundantOrSingle(it, trackTitle) }

        val cleanArtist = artistName
            .split(",", "&", "feat.", "ft.", "/").firstOrNull()?.trim()
            ?: artistName.trim()

        val cacheKey = "${cleanArtist.lowercase()}::${cleanTitle.lowercase()}"
        synchronized(memoryCache) {
            val cached = memoryCache.get(cacheKey)
            if (cached != null) return@withContext cached
        }

        val ytm = innerTubeClient ?: defaultInnerTubeClient

        // 0. If a valid known album is provided (e.g. "Currents"), directly resolve its YouTube Music album ID
        if (!cleanKnownAlbum.isNullOrBlank()) {
            try {
                val directAlbumQueries = listOfNotNull(
                    "$cleanArtist $cleanKnownAlbum".takeIf { cleanArtist.isNotBlank() },
                    cleanKnownAlbum
                ).distinct()
                for (q in directAlbumQueries) {
                    val ytmAlbums = ytm.search(q, InnerTubeClient.FILTER_ALBUMS).albums
                    val match = ytmAlbums.firstOrNull { cand ->
                        val candClean = cleanAlbumTitle(cand.title)
                        val candAuthor = cand.author.orEmpty()
                        val isArtistMatch = cleanArtist.isBlank() ||
                                SearchQueryMatcher.isAuthorMatch(candAuthor, cleanArtist) ||
                                candAuthor.contains(cleanArtist, ignoreCase = true) ||
                                cleanArtist.contains(candAuthor, ignoreCase = true)
                        val isMashup = cand.title.contains(Regex("""(?i)\s+(x|vs\.?|mashup)\s+""")) ||
                                cand.title.contains("mashup", ignoreCase = true)

                        !isMashup && isArtistMatch &&
                        (candClean.equals(cleanKnownAlbum, ignoreCase = true) ||
                         cand.title.equals(cleanKnownAlbum, ignoreCase = true) ||
                         (cleanKnownAlbum.length > 3 && candClean.contains(cleanKnownAlbum, ignoreCase = true)))
                    }
                    if (match != null) {
                        val resolved = ResolvedAlbum(
                            albumTitle = match.title,
                            albumId = match.id,
                            albumArt = match.thumbnail,
                            artistName = match.author ?: cleanArtist,
                            isSingle = false
                        )
                        synchronized(memoryCache) {
                            memoryCache.put(cacheKey, resolved)
                        }
                        return@withContext resolved
                    }
                }
            } catch (_: Exception) {}
        }

        // 1. If this is a movie/show soundtrack track, e.g. "Apna Bana Le (From "Bhediya")",
        // query YouTube Music for the official soundtrack album first
        if (soundtrackTag != null) {
            try {
                val ostQueries = listOf(
                    soundtrackTag,
                    "$cleanArtist $soundtrackTag"
                ).distinct()
                for (q in ostQueries) {
                    val ostSearch = ytm.search(q, InnerTubeClient.FILTER_ALBUMS)
                    val ostMatch = ostSearch.albums.firstOrNull { cand ->
                        val candClean = cleanAlbumTitle(cand.title)
                        (candClean.equals(soundtrackTag, ignoreCase = true) ||
                         cand.title.contains(soundtrackTag, ignoreCase = true) ||
                         candClean.contains(soundtrackTag, ignoreCase = true)) &&
                        !isRedundantOrSingle(cand.title, cleanTitle)
                    }
                    if (ostMatch != null) {
                        val resolved = ResolvedAlbum(
                            albumTitle = ostMatch.title,
                            albumId = ostMatch.id,
                            albumArt = ostMatch.thumbnail,
                            artistName = ostMatch.author ?: cleanArtist,
                            isSingle = false
                        )
                        synchronized(memoryCache) {
                            memoryCache.put(cacheKey, resolved)
                        }
                        return@withContext resolved
                    }
                }
            } catch (_: Exception) {}
        }

        // 2. Query Apple Music / iTunes Search API
        try {
            val query = "$cleanArtist $cleanTitle".trim()
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val url = "$ITUNES_SEARCH_API?term=$encodedQuery&entity=song&limit=15"

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .build()

            val response = NetworkClientProvider.okHttpClient.newCall(request).execute()
            if (!response.isSuccessful) return@withContext null

            val body = response.body?.string() ?: return@withContext null
            val json = JSONObject(body)
            val results = json.optJSONArray("results") ?: return@withContext null

            data class Candidate(
                val score: Int,
                val collectionName: String,
                val artworkUrl: String?,
                val itArtist: String,
                val isSingle: Boolean,
                val releaseDate: String
            )

            val candidates = mutableListOf<Candidate>()

            for (i in 0 until results.length()) {
                val item = results.optJSONObject(i) ?: continue
                val itArtist = item.optString("artistName", "")
                val itTrack = item.optString("trackName", "")
                val collectionName = item.optString("collectionName", "").trim()
                val rawArtwork = item.optString("artworkUrl100")
                val releaseDate = item.optString("releaseDate", "")

                val isArtistMatch = cleanArtist.isBlank() ||
                        SearchQueryMatcher.isAuthorMatch(itArtist, cleanArtist) ||
                        itArtist.contains(cleanArtist, ignoreCase = true) ||
                        cleanArtist.contains(itArtist, ignoreCase = true)

                val isTitleMatch = cleanTitle.isBlank() ||
                        itTrack.contains(cleanTitle, ignoreCase = true) ||
                        cleanTitle.contains(itTrack, ignoreCase = true)

                val isCandidateMashup = collectionName.contains("mashup", ignoreCase = true) ||
                        collectionName.contains(Regex("""(?i)\s+(x|vs\.?|mashup)\s+"""))

                if (!isCandidateMashup && isArtistMatch && isTitleMatch && collectionName.isNotBlank()) {
                    // A same-named collection is only a single if it is actually small: title-track
                    // albums ("Starboy", "Currents") share the song's name but have 10+ tracks, and
                    // treating them as singles made the resolver skip the real album entirely.
                    val trackCount = item.optInt("trackCount", 0)
                    val isLabelledSingle = Regex("""(?i)\s-\s(single|ep)$""").containsMatchIn(collectionName)
                    val isSingle = isLabelledSingle ||
                        (isRedundantOrSingle(collectionName, cleanTitle) && (trackCount == 0 || trackCount <= 3))
                    val isExactTitle = itTrack.equals(cleanTitle, ignoreCase = true)
                    val lowerCol = collectionName.lowercase()
                    val lowerTrack = itTrack.lowercase()
                    val isSecondary = lowerCol.contains("remix") || lowerCol.contains("live") ||
                            lowerCol.contains("karaoke") || lowerCol.contains("tribute") ||
                            lowerCol.contains("acoustic") || lowerTrack.contains("remix") ||
                            lowerTrack.contains("live")

                    var score = 0
                    if (!isSingle) score += 100
                    if (isExactTitle) score += 50
                    // Live / remix / karaoke collections are never the song's home album.
                    if (!isSecondary) score += 20 else score -= 40
                    if (soundtrackTag != null && collectionName.contains(soundtrackTag, ignoreCase = true)) {
                        score += 80
                    }
                    if (!cleanKnownAlbum.isNullOrBlank() &&
                        (collectionName.contains(cleanKnownAlbum, ignoreCase = true) || cleanKnownAlbum.contains(collectionName, ignoreCase = true))) {
                        score += 200
                    }
                    // Hits compilations re-release the song later ("The Highlights" for Starboy);
                    // the parent studio album is what "View album" should open.
                    val isCompilation = Regex("""(?i)\b(greatest hits|the hits|best of|the best|highlights|essentials|anthology|collection|the very best|hits)\b""")
                        .containsMatchIn(collectionName) ||
                        item.optString("collectionArtistName").equals("Various Artists", ignoreCase = true)
                    if (isCompilation) score -= 40

                    candidates.add(
                        Candidate(
                            score = score,
                            collectionName = collectionName,
                            artworkUrl = rawArtwork.ifBlank { null }?.replace("100x100bb", "1200x1200bb"),
                            itArtist = itArtist,
                            isSingle = isSingle,
                            releaseDate = releaseDate
                        )
                    )
                }
            }

            // Among equally good matches, the earliest release is the original album; later
            // releases of the same recording are compilations / reissues. iTunes' own order
            // (the previous tie-break) often put the compilation first.
            val bestCandidate = candidates.sortedWith(
                compareByDescending<Candidate> { it.score }
                    .thenBy { it.releaseDate.ifBlank { "9999" } }
            ).firstOrNull()
            if (bestCandidate != null && !bestCandidate.isSingle) {
                var ytmAlbumId: String? = null
                var ytmArt: String? = null
                var ytmAuthor: String? = null
                var ytmTitle: String? = null
                try {
                    val rawColName = bestCandidate.collectionName
                    val cleanColName = cleanAlbumTitle(rawColName)

                    val queriesToTry = listOfNotNull(
                        cleanColName.takeIf { it.isNotBlank() },
                        "$cleanArtist $cleanColName".takeIf { cleanArtist.isNotBlank() && it != cleanColName },
                        rawColName.takeIf { it != cleanColName }
                    ).distinct()

                    for (q in queriesToTry) {
                        val albumSearch = ytm.search(q, InnerTubeClient.FILTER_ALBUMS)
                        val ytmMatch = albumSearch.albums.firstOrNull { cand ->
                            val candClean = cleanAlbumTitle(cand.title)
                            val candAuthor = cand.author.orEmpty()
                            val isCandArtistMatch = cleanArtist.isBlank() ||
                                    candAuthor.contains("Various", ignoreCase = true) ||
                                    candAuthor.contains("Soundtrack", ignoreCase = true) ||
                                    candAuthor.contains("OST", ignoreCase = true) ||
                                    soundtrackTag != null ||
                                    bestCandidate.collectionName.contains("soundtrack", ignoreCase = true) ||
                                    SearchQueryMatcher.isAuthorMatch(candAuthor, cleanArtist) ||
                                    candAuthor.contains(cleanArtist, ignoreCase = true) ||
                                    cleanArtist.contains(candAuthor, ignoreCase = true) ||
                                    candAuthor.contains(bestCandidate.itArtist, ignoreCase = true) ||
                                    bestCandidate.itArtist.contains(candAuthor, ignoreCase = true)
                            val isCandMashup = cand.title.contains(Regex("""(?i)\s+(x|vs\.?|mashup)\s+""")) ||
                                    cand.title.contains("mashup", ignoreCase = true)

                            !isCandMashup && isCandArtistMatch &&
                            (candClean.equals(cleanColName, ignoreCase = true) ||
                            cand.title.equals(rawColName, ignoreCase = true) ||
                            (cleanColName.length > 3 && candClean.contains(cleanColName, ignoreCase = true)) ||
                            (candClean.length > 3 && cleanColName.contains(candClean, ignoreCase = true)))
                        }
                        if (ytmMatch != null) {
                            ytmAlbumId = ytmMatch.id
                            ytmArt = ytmMatch.thumbnail
                            ytmAuthor = ytmMatch.author
                            ytmTitle = ytmMatch.title
                            break
                        }
                    }
                } catch (_: Exception) {}

                val resolved = ResolvedAlbum(
                    // Label with the YouTube Music album's own title when we found it, so the name
                    // matches the page "View album" opens (iTunes says "Starboy (Deluxe)" for YTM's "Starboy").
                    albumTitle = ytmTitle?.takeIf { it.isNotBlank() } ?: bestCandidate.collectionName,
                    albumId = ytmAlbumId,
                    albumArt = ytmArt ?: bestCandidate.artworkUrl,
                    artistName = ytmAuthor ?: bestCandidate.itArtist.ifBlank { cleanArtist },
                    isSingle = false
                )

                synchronized(memoryCache) {
                    memoryCache.put(cacheKey, resolved)
                }
                return@withContext resolved
            }
        } catch (_: Exception) {}

        // 3. Fallback: Search YouTube Music Albums directly with strict artist & mashup filtering
        try {
            val queries = listOfNotNull(
                soundtrackTag,
                "$cleanArtist $cleanKnownAlbum".takeIf { !cleanKnownAlbum.isNullOrBlank() && cleanArtist.isNotBlank() },
                cleanKnownAlbum,
                "$cleanArtist $cleanTitle".takeIf { cleanArtist.isNotBlank() },
                cleanTitle
            ).distinct()

            for (q in queries) {
                val ytmAlbums = ytm.search(q, InnerTubeClient.FILTER_ALBUMS).albums
                val match = ytmAlbums.firstOrNull { cand ->
                    val candClean = cleanAlbumTitle(cand.title)
                    val candAuthor = cand.author.orEmpty()
                    val isArtistMatch = cleanArtist.isBlank() ||
                            candAuthor.contains("Various", ignoreCase = true) ||
                            candAuthor.contains("Soundtrack", ignoreCase = true) ||
                            candAuthor.contains("OST", ignoreCase = true) ||
                            soundtrackTag != null ||
                            SearchQueryMatcher.isAuthorMatch(candAuthor, cleanArtist) ||
                            candAuthor.contains(cleanArtist, ignoreCase = true) ||
                            cleanArtist.contains(candAuthor, ignoreCase = true)

                    val isMashup = cand.title.contains(Regex("""(?i)\s+(x|vs\.?|mashup)\s+""")) ||
                            cand.title.contains("mashup", ignoreCase = true)

                    !isMashup &&
                    isArtistMatch &&
                    !isRedundantOrSingle(cand.title, cleanTitle) &&
                    (candClean.equals(cleanKnownAlbum, ignoreCase = true) ||
                     candClean.equals(cleanTitle, ignoreCase = true) ||
                     (soundtrackTag != null && candClean.contains(soundtrackTag, ignoreCase = true)) ||
                     (cleanTitle.length > 3 && candClean.contains(cleanTitle, ignoreCase = true)))
                }
                if (match != null) {
                    val resolved = ResolvedAlbum(
                        albumTitle = match.title,
                        albumId = match.id,
                        albumArt = match.thumbnail,
                        artistName = match.author ?: cleanArtist,
                        isSingle = false
                    )
                    synchronized(memoryCache) {
                        memoryCache.put(cacheKey, resolved)
                    }
                    return@withContext resolved
                }
            }
        } catch (_: Exception) {}

        null
    }
}
