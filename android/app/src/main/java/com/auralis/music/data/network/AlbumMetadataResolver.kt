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
    private val memoryCache = LruCache<String, ResolvedAlbum>(500)

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

        if (rawAlb.endsWith("- single") || rawAlb.endsWith("(single)") ||
            rawAlb.endsWith("- ep") || rawAlb.endsWith("(ep)") ||
            rawAlb.endsWith(" - single") || rawAlb.endsWith(" (single)")) return true

        if (rawAlb.startsWith(cleanTit) && (rawAlb.contains("single") || rawAlb.contains("ep"))) return true
        if (rawAlb.contains("play") || rawAlb.contains("view") || rawAlb.contains("listener") || rawAlb.contains("subscriber")) return true

        return false
    }

    /**
     * Attempts to resolve the authentic parent studio album for a given track and artist.
     * Uses Apple Music / iTunes Search API and YouTube Music album queries to identify the genuine
     * parent collection, resolving its YouTube Music browse ID (MPRE...).
     */
    suspend fun resolveAlbum(
        trackTitle: String,
        artistName: String,
        innerTubeClient: InnerTubeClient? = null
    ): ResolvedAlbum? = withContext(Dispatchers.IO) {
        val cleanTitle = cleanTrackTitle(trackTitle)
        val soundtrackTag = extractSoundtrackTag(trackTitle)

        val cleanArtist = artistName
            .split(",", "&", "feat.", "ft.", "/").firstOrNull()?.trim()
            ?: artistName.trim()

        val cacheKey = "${cleanArtist.lowercase()}::${cleanTitle.lowercase()}"
        synchronized(memoryCache) {
            val cached = memoryCache.get(cacheKey)
            if (cached != null) return@withContext cached
        }

        // 1. If this is a movie/show soundtrack track, e.g. "Apna Bana Le (From "Bhediya")",
        // query YouTube Music for the official soundtrack album first
        if (soundtrackTag != null && innerTubeClient != null) {
            try {
                val ostSearch = innerTubeClient.search("$cleanArtist $soundtrackTag", InnerTubeClient.FILTER_ALBUMS)
                val ostMatch = ostSearch.albums.firstOrNull { cand ->
                    cand.title.contains(soundtrackTag, ignoreCase = true) &&
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
                val isSingle: Boolean
            )

            val candidates = mutableListOf<Candidate>()

            for (i in 0 until results.length()) {
                val item = results.optJSONObject(i) ?: continue
                val itArtist = item.optString("artistName", "")
                val itTrack = item.optString("trackName", "")
                val collectionName = item.optString("collectionName", "").trim()
                val rawArtwork = item.optString("artworkUrl100")

                val isArtistMatch = cleanArtist.isBlank() ||
                        SearchQueryMatcher.isAuthorMatch(itArtist, cleanArtist) ||
                        itArtist.contains(cleanArtist, ignoreCase = true) ||
                        cleanArtist.contains(itArtist, ignoreCase = true)

                val isTitleMatch = cleanTitle.isBlank() ||
                        itTrack.contains(cleanTitle, ignoreCase = true) ||
                        cleanTitle.contains(itTrack, ignoreCase = true)

                if (isArtistMatch && isTitleMatch && collectionName.isNotBlank()) {
                    val isSingle = isRedundantOrSingle(collectionName, cleanTitle)
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
                    if (!isSecondary) score += 20
                    if (soundtrackTag != null && collectionName.contains(soundtrackTag, ignoreCase = true)) {
                        score += 80
                    }

                    candidates.add(
                        Candidate(
                            score = score,
                            collectionName = collectionName,
                            artworkUrl = rawArtwork.ifBlank { null }?.replace("100x100bb", "1200x1200bb"),
                            itArtist = itArtist,
                            isSingle = isSingle
                        )
                    )
                }
            }

            val bestCandidate = candidates.maxByOrNull { it.score }
            if (bestCandidate != null && !bestCandidate.isSingle) {
                var ytmAlbumId: String? = null
                var ytmArt: String? = null
                if (innerTubeClient != null) {
                    try {
                        val albumSearch = innerTubeClient.search("$cleanArtist ${bestCandidate.collectionName}", InnerTubeClient.FILTER_ALBUMS)
                        val ytmMatch = albumSearch.albums.firstOrNull { cand ->
                            cand.title.equals(bestCandidate.collectionName, ignoreCase = true) ||
                            cand.title.contains(bestCandidate.collectionName, ignoreCase = true) ||
                            bestCandidate.collectionName.contains(cand.title, ignoreCase = true)
                        } ?: albumSearch.albums.firstOrNull()

                        ytmAlbumId = ytmMatch?.id
                        ytmArt = ytmMatch?.thumbnail
                    } catch (_: Exception) {}
                }

                val resolved = ResolvedAlbum(
                    albumTitle = bestCandidate.collectionName,
                    albumId = ytmAlbumId,
                    albumArt = ytmArt ?: bestCandidate.artworkUrl,
                    artistName = bestCandidate.itArtist.ifBlank { cleanArtist },
                    isSingle = false
                )

                synchronized(memoryCache) {
                    memoryCache.put(cacheKey, resolved)
                }
                return@withContext resolved
            }
        } catch (_: Exception) {}

        // 3. Fallback: Search YouTube Music Albums directly for artist + soundtrackTag or artist albums
        if (innerTubeClient != null) {
            try {
                val query = if (soundtrackTag != null) "$cleanArtist $soundtrackTag" else "$cleanArtist $cleanTitle"
                val ytmAlbums = innerTubeClient.search(query, InnerTubeClient.FILTER_ALBUMS).albums
                val match = ytmAlbums.firstOrNull { cand ->
                    !isRedundantOrSingle(cand.title, cleanTitle) &&
                    SearchQueryMatcher.isAuthorMatch(cand.author, cleanArtist) &&
                    (soundtrackTag != null && cand.title.contains(soundtrackTag, ignoreCase = true) ||
                     cand.title.contains(cleanTitle, ignoreCase = true))
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
            } catch (_: Exception) {}
        }

        null
    }
}
