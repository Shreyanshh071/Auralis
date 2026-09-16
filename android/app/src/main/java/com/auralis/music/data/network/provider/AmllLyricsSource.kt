package com.auralis.music.data.network.provider

import android.util.Log
import com.auralis.music.data.network.NetworkClientProvider
import com.auralis.music.data.network.TitleCleaner
import com.auralis.music.data.parser.LyricsMatcher
import com.auralis.music.data.parser.TtmlParser
import com.auralis.music.data.parser.WordTiming
import com.auralis.music.domain.lyrics.LyricsAlignmentEngine
import com.auralis.music.domain.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import kotlin.math.abs

/**
 * AMLL (Apple Music-like Lyrics) Provider connected directly to the community-maintained
 * AMLL TTML Database (https://github.com/amll-dev/amll-ttml-db).
 *
 * All TTML files in this repository are CC0 1.0 Universal (Public Domain) and provide
 * genuine syllable-level karaoke synchronization without synthetic stretching.
 *
 * Resolution hierarchy:
 * 1. Direct Apple Music Track ID (query.appleMusicId) -> am-lyrics/{id}.ttml
 * 2. Direct Spotify Track ID (query.spotifyId) -> spotify-lyrics/{id}.ttml
 * 3. Apple Music Catalog Search -> verified AppleMusicTrack -> am-lyrics/{id}.ttml
 * 4. NetEase Cloud Music Search -> verified songId -> ncm-lyrics/{id}.ttml
 * 5. Fast global edge CDN (jsDelivr) with automatic fallback to GitHub raw.
 */
class AmllLyricsSource(
    private val client: OkHttpClient = NetworkClientProvider.lyricsHttpClient,
    private val tokenManager: AppleTokenManager = AppleTokenManager(client)
) : LyricsSource {

    override val provider: LyricsProvider = LyricsProvider.AMLL
    override val supportedSyncTypes: Set<SyncType> = setOf(SyncType.RICHSYNC, SyncType.LINE_SYNC, SyncType.PLAIN)

    companion object {
        private const val TAG = "AmllLyricsSource"
        private const val JSDELIVR_BASE = "https://cdn.jsdelivr.net/gh/amll-dev/amll-ttml-db@main"
        private const val GITHUB_RAW_BASE = "https://raw.githubusercontent.com/amll-dev/amll-ttml-db/main"
        private const val APPLE_MUSIC_API_BASE = "https://amp-api.music.apple.com/v1/catalog/us"
        private const val NETEASE_SEARCH_URL = "https://music.163.com/api/search/get"
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
    }

    /**
     * Fetches raw TTML content from the AMLL TTML DB via fast edge CDN with GitHub raw fallback.
     */
    fun fetchTtmlFromDb(folder: String, id: String): String? {
        if (id.isBlank() || folder.isBlank()) return null
        val cdnUrl = "$JSDELIVR_BASE/$folder/$id.ttml"
        try {
            val req = Request.Builder()
                .url(cdnUrl)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/xml, text/xml, */*")
                .build()

            val cdnResult = client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.string() else null
            }
            if (!cdnResult.isNullOrBlank()) {
                return cdnResult
            }
        } catch (_: Exception) {}

        // Fallback directly to GitHub raw if CDN has not cached or is unreachable
        try {
            val rawUrl = "$GITHUB_RAW_BASE/$folder/$id.ttml"
            val req = Request.Builder()
                .url(rawUrl)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/xml, text/xml, */*")
                .build()

            return client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.string() else null
            }
        } catch (_: Exception) {
            return null
        }
    }

    override suspend fun search(query: LyricsSearchQuery): LyricsCandidate? = withContext(Dispatchers.IO) {
        val cleanTitle = TitleCleaner.cleanCoreSongTitle(query.title)
        val cleanArtist = TitleCleaner.cleanArtist(query.artist)
        if (cleanTitle.isBlank()) return@withContext null

        val targetDurationMs = query.durationMs?.takeIf { it > 0L }
            ?: (query.durationSec?.let { it * 1000L }?.takeIf { it > 0L })

        // 1. Direct platform track ID lookups if provided
        if (!query.appleMusicId.isNullOrBlank()) {
            fetchTtmlCandidate("am-lyrics", query.appleMusicId, cleanTitle, cleanArtist, targetDurationMs, query)?.let {
                Log.d(TAG, "Resolved AMLL TTML via explicit Apple Music ID: ${query.appleMusicId}")
                return@withContext it
            }
        }

        if (!query.spotifyId.isNullOrBlank()) {
            fetchTtmlCandidate("spotify-lyrics", query.spotifyId, cleanTitle, cleanArtist, targetDurationMs, query)?.let {
                Log.d(TAG, "Resolved AMLL TTML via explicit Spotify ID: ${query.spotifyId}")
                return@withContext it
            }
        }

        // 2. Resolve via Apple Music Catalog Search -> am-lyrics/{appleMusicId}.ttml
        searchViaAppleMusicCatalog(cleanTitle, cleanArtist, targetDurationMs, query)?.let {
            return@withContext it
        }

        // 3. Resolve via NetEase Search -> ncm-lyrics/{ncmMusicId}.ttml
        searchViaNetEase(cleanTitle, cleanArtist, targetDurationMs, query)?.let {
            return@withContext it
        }

        null
    }

    private fun fetchTtmlCandidate(
        folder: String,
        id: String,
        fallbackTitle: String,
        fallbackArtist: String,
        targetDurationMs: Long?,
        query: LyricsSearchQuery
    ): LyricsCandidate? {
        val ttmlContent = fetchTtmlFromDb(folder, id) ?: return null
        if (ttmlContent.isBlank()) return null

        val parsed = TtmlParser.parse(ttmlContent, LyricsProvider.AMLL)
        if (parsed.lines.isEmpty()) return null

        val candTitle = parsed.trackName?.ifBlank { null } ?: fallbackTitle
        val candArtist = parsed.artistName?.ifBlank { null } ?: fallbackArtist
        val effectiveDurMs = parsed.durationMs ?: parsed.effectiveDurationMs

        // Master alignment gate
        if (targetDurationMs != null && targetDurationMs > 0L && effectiveDurMs > 0L) {
            val deltaMs = abs(targetDurationMs - effectiveDurMs)
            if (deltaMs > LyricsAlignmentEngine.COMPATIBLE_OFFSET_MAX_DELTA_MS) {
                Log.w(TAG, "[Master Mismatch] Rejecting AMLL candidate '$candTitle' ($folder/$id) due to duration delta ${deltaMs}ms > 3.5s")
                return null
            }
        }

        val candDurSec = (effectiveDurMs.takeIf { it > 0L } ?: 0L) / 1000L
        val confidence = LyricsMatcher.calculateConfidence(
            queryTitle = query.title,
            queryArtist = query.artist,
            candidateTitle = candTitle,
            candidateArtist = candArtist,
            queryDurationSec = query.durationSec,
            candidateDurationSec = candDurSec.takeIf { it > 0L },
            queryAlbum = query.album
        )

        if (confidence < 50) {
            Log.d(TAG, "Rejecting AMLL candidate '$candTitle' due to confidence $confidence < 50")
            return null
        }

        val hasWords = WordTiming.hasGenuineWordStarts(parsed.lines)
        val resolvedSyncType = if (hasWords) SyncType.RICHSYNC else SyncType.LINE_SYNC

        val resolvedData = parsed.copy(
            trackName = candTitle,
            artistName = candArtist,
            durationMs = effectiveDurMs.takeIf { it > 0L } ?: parsed.durationMs,
            syncType = resolvedSyncType
        )

        return LyricsCandidate(
            lyricsData = resolvedData,
            confidence = confidence,
            syncType = resolvedSyncType,
            provider = LyricsProvider.AMLL
        )
    }

    private suspend fun searchViaAppleMusicCatalog(
        cleanTitle: String,
        cleanArtist: String,
        targetDurationMs: Long?,
        query: LyricsSearchQuery
    ): LyricsCandidate? {
        try {
            val primaryArtist = cleanArtist
                .split(Regex("""[,&/|]|(?:\s+feat\.?\s+)|\s+ft\.?\s+|\s+and\s+|\s+with\s+""", RegexOption.IGNORE_CASE))
                .firstOrNull()?.trim() ?: cleanArtist

            val searchTerm = "$cleanTitle $primaryArtist".trim()
            var token = tokenManager.getToken() ?: return null
            var tracks = executeAppleMusicSearch(searchTerm, token)

            if (tracks == null) {
                tokenManager.clearToken()
                token = tokenManager.getToken(forceRefresh = true) ?: return null
                tracks = executeAppleMusicSearch(searchTerm, token)
            }

            val candidates = tracks ?: emptyList()
            if (candidates.isEmpty()) return null

            val bestTrack = selectBestAppleTrack(candidates, cleanTitle, cleanArtist, targetDurationMs, query.album)
                ?: return null

            // Enforce master match window
            if (targetDurationMs != null && targetDurationMs > 0L && bestTrack.durationInMillis != null && bestTrack.durationInMillis > 0L) {
                val deltaMs = abs(targetDurationMs - bestTrack.durationInMillis)
                if (deltaMs > LyricsAlignmentEngine.COMPATIBLE_OFFSET_MAX_DELTA_MS) {
                    return null
                }
            }

            return fetchTtmlCandidate("am-lyrics", bestTrack.id, bestTrack.name, bestTrack.artistName, targetDurationMs, query)
        } catch (e: Exception) {
            Log.w(TAG, "Error in searchViaAppleMusicCatalog: ${e.message}")
            return null
        }
    }

    private fun executeAppleMusicSearch(term: String, token: String): List<AppleMusicTrack>? {
        try {
            val encodedTerm = URLEncoder.encode(term, "UTF-8")
            val url = "$APPLE_MUSIC_API_BASE/search?term=$encodedTerm&types=songs&limit=10"
            val req = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $token")
                .header("Origin", "https://music.apple.com")
                .header("Referer", "https://music.apple.com/")
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .build()

            client.newCall(req).execute().use { resp ->
                if (resp.code == 401) return null
                if (!resp.isSuccessful) return emptyList()
                val body = resp.body?.string() ?: return emptyList()
                return parseAppleMusicResults(body)
            }
        } catch (_: Exception) {
            return emptyList()
        }
    }

    private fun parseAppleMusicResults(jsonStr: String): List<AppleMusicTrack> {
        val tracks = mutableListOf<AppleMusicTrack>()
        try {
            val root = JSONObject(jsonStr)
            val results = root.optJSONObject("results") ?: return emptyList()
            val songs = results.optJSONObject("songs") ?: return emptyList()
            val data = songs.optJSONArray("data") ?: return emptyList()

            for (i in 0 until data.length()) {
                val item = data.optJSONObject(i) ?: continue
                val id = item.optString("id")
                val attr = item.optJSONObject("attributes") ?: continue
                val name = attr.optString("name")
                val artistName = attr.optString("artistName")
                val albumName = attr.optString("albumName").takeIf { it.isNotBlank() }
                val durationMs = if (attr.has("durationInMillis")) attr.optLong("durationInMillis") else null
                val isrc = attr.optString("isrc").takeIf { it.isNotBlank() }

                if (id.isNotBlank() && name.isNotBlank() && artistName.isNotBlank()) {
                    tracks.add(
                        AppleMusicTrack(
                            id = id,
                            name = name,
                            artistName = artistName,
                            albumName = albumName,
                            durationInMillis = durationMs,
                            isrc = isrc
                        )
                    )
                }
            }
        } catch (_: Exception) {}
        return tracks
    }

    private fun selectBestAppleTrack(
        tracks: List<AppleMusicTrack>,
        cleanTitle: String,
        cleanArtist: String,
        targetDurationMs: Long?,
        album: String?
    ): AppleMusicTrack? {
        var bestTrack: AppleMusicTrack? = null
        var bestScore = -100.0

        for (t in tracks) {
            val titleMatches = LyricsMatcher.isTitleMatching(cleanTitle, t.name)
            if (!titleMatches) continue

            val artistMatches = LyricsMatcher.isArtistMatching(cleanArtist, t.artistName)
            if (!artistMatches) continue

            var score = LyricsMatcher.diceCoefficient(cleanTitle, t.name) * 50.0 +
                    LyricsMatcher.diceCoefficient(cleanArtist, t.artistName) * 30.0

            if (targetDurationMs != null && t.durationInMillis != null) {
                val delta = abs(targetDurationMs - t.durationInMillis)
                if (delta <= 1500L) score += 20.0
                else if (delta <= 3500L) score += 10.0
                else continue // Delta exceeds acceptable window
            }

            if (!album.isNullOrBlank() && !t.albumName.isNullOrBlank() &&
                t.albumName.contains(album, ignoreCase = true)
            ) {
                score += 15.0
            }

            if (score > bestScore) {
                bestScore = score
                bestTrack = t
            }
        }
        return bestTrack
    }

    private fun searchViaNetEase(
        cleanTitle: String,
        cleanArtist: String,
        targetDurationMs: Long?,
        query: LyricsSearchQuery
    ): LyricsCandidate? {
        try {
            val encQuery = URLEncoder.encode("$cleanTitle $cleanArtist", "UTF-8")
            val url = "$NETEASE_SEARCH_URL?csrf_token=&type=1&offset=0&total=true&limit=5&s=$encQuery"
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Referer", "http://music.163.com")
                .header("Cookie", "os=pc")
                .build()

            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) return null
            val body = resp.body?.string() ?: return null
            val json = JSONObject(body)
            val songs = json.optJSONObject("result")?.optJSONArray("songs") ?: return null

            for (i in 0 until songs.length()) {
                val songObj = songs.optJSONObject(i) ?: continue
                val songId = songObj.optLong("id", 0L)
                if (songId == 0L) continue

                val songName = songObj.optString("name")
                val artists = songObj.optJSONArray("artists")
                val artistName = if (artists != null && artists.length() > 0) {
                    artists.optJSONObject(0)?.optString("name") ?: ""
                } else ""
                val durationMs = songObj.optLong("duration", 0L).takeIf { it > 0L }

                if (!LyricsMatcher.isTitleMatching(cleanTitle, songName)) continue
                if (!LyricsMatcher.isArtistMatching(cleanArtist, artistName)) continue

                if (targetDurationMs != null && durationMs != null) {
                    val delta = abs(targetDurationMs - durationMs)
                    if (delta > LyricsAlignmentEngine.COMPATIBLE_OFFSET_MAX_DELTA_MS) continue
                }

                val cand = fetchTtmlCandidate("ncm-lyrics", songId.toString(), songName, artistName, targetDurationMs, query)
                if (cand != null) {
                    return cand
                }
            }
        } catch (_: Exception) {}
        return null
    }
}
