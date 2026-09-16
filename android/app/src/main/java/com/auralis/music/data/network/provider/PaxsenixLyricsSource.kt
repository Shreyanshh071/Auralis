package com.auralis.music.data.network.provider

import android.util.Log
import com.auralis.music.data.network.TitleCleaner
import com.auralis.music.data.parser.LyricsMatcher
import com.auralis.music.data.parser.TtmlParser
import com.auralis.music.domain.lyrics.LyricsAlignmentEngine
import com.auralis.music.domain.lyrics.MasterMatchStatus
import com.auralis.music.domain.model.LyricsData
import com.auralis.music.domain.model.LyricsProvider
import com.auralis.music.domain.model.SyncType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Metadata for a track returned from Apple Music catalog search.
 */
data class AppleMusicTrack(
    val id: String,
    val name: String,
    val artistName: String,
    val albumName: String? = null,
    val durationInMillis: Long? = null,
    val isrc: String? = null
)

/**
 * Paxsenix lyrics provider delivering genuine Apple Music syllable-synchronized TTML lyrics.
 *
 * Architecture:
 * 1. Obtains Apple Music Web JWT via [AppleTokenManager].
 * 2. Searches Apple Music Catalog: GET https://amp-api.music.apple.com/v1/catalog/us/search
 * 3. Defensively filters candidates using Auralis's strict title, artist, version, and duration matching.
 * 4. Enforces strict master alignment (duration delta <= 3.5s). Wrong masters are strictly rejected.
 * 5. Fetches genuine syllable TTML from Paxsenix: GET https://lyrics.paxsenix.org/apple-music/lyrics?id=<id>
 * 6. Parses TTML via [TtmlParser], guaranteeing zero synthetic word timestamps and complete master integrity.
 */
class PaxsenixLyricsSource(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .followRedirects(true)
        .build(),
    private val tokenManager: AppleTokenManager = AppleTokenManager(client)
) : LyricsSource {

    override val provider: LyricsProvider = LyricsProvider.PAXSENIX
    override val supportedSyncTypes: Set<SyncType> = setOf(SyncType.RICHSYNC, SyncType.LINE_SYNC, SyncType.PLAIN)

    companion object {
        private const val TAG = "PaxsenixLyricsSource"
        private const val APPLE_MUSIC_API_BASE = "https://amp-api.music.apple.com/v1/catalog/us"
        private const val PAXSENIX_API_BASE = "https://lyrics.paxsenix.org"
        private const val USER_AGENT = "Auralis/1.0.0"
    }

    override suspend fun search(query: LyricsSearchQuery): LyricsCandidate? = withContext(Dispatchers.IO) {
        val cleanTitle = TitleCleaner.cleanCoreSongTitle(query.title)
        val cleanArtist = TitleCleaner.cleanArtist(query.artist)
        if (cleanTitle.isBlank()) return@withContext null

        val targetDurationMs = query.durationMs?.takeIf { it > 0L }
            ?: (query.durationSec?.let { it * 1000L }?.takeIf { it > 0L })

        // Step 1: Search Apple Music catalog
        val matchedTrack = searchAppleMusicTrack(cleanTitle, cleanArtist, targetDurationMs, query.album)
            ?: return@withContext null

        Log.d(TAG, "Selected Apple Music track: '${matchedTrack.name}' by '${matchedTrack.artistName}' (id=${matchedTrack.id}, dur=${matchedTrack.durationInMillis}ms)")

        // Step 2: Strict master matching gate before network retrieval
        if (targetDurationMs != null && targetDurationMs > 0L && matchedTrack.durationInMillis != null && matchedTrack.durationInMillis > 0L) {
            val deltaMs = abs(targetDurationMs - matchedTrack.durationInMillis)
            if (deltaMs > LyricsAlignmentEngine.COMPATIBLE_OFFSET_MAX_DELTA_MS) {
                Log.w(TAG, "[Master Mismatch] Rejecting '${matchedTrack.name}' due to duration delta ${deltaMs}ms > 3.5s (playback=${targetDurationMs}ms, apple=${matchedTrack.durationInMillis}ms)")
                return@withContext null
            }
        }

        // Step 3: Fetch lyrics from Paxsenix
        fetchPaxsenixLyrics(matchedTrack, query, targetDurationMs)
    }

    /**
     * Searches Apple Music catalog API for candidate songs, with automatic 401 retry.
     */
    internal suspend fun searchAppleMusicTrack(
        cleanTitle: String,
        cleanArtist: String,
        targetDurationMs: Long?,
        album: String?
    ): AppleMusicTrack? {
        val primaryArtist = cleanArtist
            .split(Regex("""[,&/|]|(?:\s+feat\.?\s+)|\s+ft\.?\s+|\s+and\s+|\s+with\s+""", RegexOption.IGNORE_CASE))
            .firstOrNull()?.trim() ?: cleanArtist

        val searchTerm = "$cleanTitle $primaryArtist".trim()

        var token = tokenManager.getToken() ?: return null
        var tracks = executeSearch(searchTerm, token)

        if (tracks == null) {
            // Token might be expired (401); refresh token and retry once
            Log.d(TAG, "Search returned null / 401, forcing token refresh...")
            tokenManager.clearToken()
            token = tokenManager.getToken(forceRefresh = true) ?: return null
            tracks = executeSearch(searchTerm, token)
        }

        val candidates = tracks ?: emptyList()
        if (candidates.isEmpty()) {
            Log.d(TAG, "No Apple Music tracks found for term '$searchTerm'")
            return null
        }

        return selectBestMatch(candidates, cleanTitle, cleanArtist, targetDurationMs, album)
    }

    private fun executeSearch(term: String, token: String): List<AppleMusicTrack>? {
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
                if (resp.code == 401) {
                    Log.w(TAG, "Apple Music search returned HTTP 401 Unauthorized")
                    return null
                }
                if (!resp.isSuccessful) {
                    Log.w(TAG, "Apple Music search failed: HTTP ${resp.code}")
                    return emptyList()
                }

                val body = resp.body?.string() ?: return emptyList()
                return parseAppleMusicSearchResults(body)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Exception during Apple Music search: ${e.message}")
            return emptyList()
        }
    }

    internal fun parseAppleMusicSearchResults(jsonStr: String): List<AppleMusicTrack> {
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
        } catch (e: Exception) {
            Log.w(TAG, "Failed parsing Apple Music search results: ${e.message}")
        }
        return tracks
    }

    /**
     * Defensively scores candidates using Auralis's matching rules:
     * - Strict title & artistDice
     * - Version marker matching (rejecting remix/live/acoustic when query is original)
     * - Strict duration window matching (penalizing >3.5s mismatches)
     */
    internal fun selectBestMatch(
        tracks: List<AppleMusicTrack>,
        queryTitle: String,
        queryArtist: String,
        targetDurationMs: Long?,
        queryAlbum: String? = null
    ): AppleMusicTrack? {
        if (tracks.isEmpty()) return null

        val cleanQTitle = TitleCleaner.cleanCoreSongTitle(queryTitle).lowercase()
        val qVersion = TitleCleaner.extractVersion(queryTitle)
        val isQTimingAltering = LyricsAlignmentEngine.isTimingAlteringVersion(qVersion)

        var bestTrack: AppleMusicTrack? = null
        var bestScore = -1000.0

        for (t in tracks) {
            var score = 0.0
            val cleanTTitle = TitleCleaner.cleanCoreSongTitle(t.name).lowercase()

            // 1. Title matching
            val titleDice = LyricsMatcher.diceCoefficient(cleanQTitle, cleanTTitle)
            if (cleanQTitle == cleanTTitle) {
                score += 40.0
            } else if (titleDice >= 0.8) {
                score += 30.0
            } else if (titleDice >= 0.5) {
                score += 15.0
            } else if (cleanTTitle.contains(cleanQTitle) || cleanQTitle.contains(cleanTTitle)) {
                score += 10.0
            } else {
                continue // Title too dissimilar
            }

            // 2. Artist matching
            val artistMatches = LyricsMatcher.isArtistMatching(queryArtist, t.artistName)
            if (artistMatches) {
                score += 30.0
            } else {
                score -= 20.0
            }

            // 3. Version mismatch protection
            val tVersion = TitleCleaner.extractVersion(t.name)
            val isTTimingAltering = LyricsAlignmentEngine.isTimingAlteringVersion(tVersion)
            if (isTTimingAltering && !isQTimingAltering) {
                score -= 60.0 // Candidate is remix/live/acoustic but original requested
            } else if (!isTTimingAltering && isQTimingAltering) {
                score -= 40.0
            } else if (qVersion != null && tVersion != null && qVersion.equals(tVersion, ignoreCase = true)) {
                score += 20.0
            }

            // 4. Duration matching
            if (targetDurationMs != null && targetDurationMs > 0L && t.durationInMillis != null && t.durationInMillis > 0L) {
                val deltaMs = abs(targetDurationMs - t.durationInMillis)
                when {
                    deltaMs <= LyricsAlignmentEngine.EXACT_MATCH_MAX_DELTA_MS -> score += 40.0
                    deltaMs <= LyricsAlignmentEngine.COMPATIBLE_OFFSET_MAX_DELTA_MS -> score += 20.0
                    deltaMs <= 10_000L -> score -= 40.0
                    else -> score -= 80.0 // Severe mismatch (e.g. 357s album cut vs 276s radio edit)
                }
            }

            // 5. Album match bonus
            if (!queryAlbum.isNullOrBlank() && !t.albumName.isNullOrBlank()) {
                val albumDice = LyricsMatcher.diceCoefficient(queryAlbum, t.albumName)
                if (albumDice >= 0.7) {
                    score += 10.0
                }
            }

            if (score > bestScore) {
                bestScore = score
                bestTrack = t
            }
        }

        return if (bestScore > 0.0) bestTrack else null
    }

    /**
     * Fetches the raw Apple Music TTML lyrics from Paxsenix and parses them into a [LyricsCandidate].
     */
    internal fun fetchPaxsenixLyrics(
        track: AppleMusicTrack,
        query: LyricsSearchQuery,
        targetDurationMs: Long?
    ): LyricsCandidate? {
        try {
            val url = "$PAXSENIX_API_BASE/apple-music/lyrics?id=${URLEncoder.encode(track.id, "UTF-8")}"
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .build()

            val (isSuccess, respCode, bodyStr) = client.newCall(req).execute().use { resp ->
                Triple(resp.isSuccessful, resp.code, if (resp.isSuccessful) resp.body?.string() else null)
            }

            if (!isSuccess || bodyStr.isNullOrBlank()) {
                Log.w(TAG, "Paxsenix request for track ID ${track.id} failed: HTTP $respCode; checking AMLL TTML DB fallback...")
                return fetchAmllTtmlDbFallback(track, query, targetDurationMs)
            }

            return parsePaxsenixResponse(bodyStr, track, query, targetDurationMs)
                ?: fetchAmllTtmlDbFallback(track, query, targetDurationMs)
        } catch (e: Exception) {
            Log.w(TAG, "Exception during Paxsenix lyrics fetch: ${e.message}; checking AMLL TTML DB fallback...")
            return fetchAmllTtmlDbFallback(track, query, targetDurationMs)
        }
    }

    private fun fetchAmllTtmlDbFallback(
        track: AppleMusicTrack,
        query: LyricsSearchQuery,
        targetDurationMs: Long?
    ): LyricsCandidate? {
        try {
            val amllSource = AmllLyricsSource(client = client, tokenManager = tokenManager)
            val ttmlContent = amllSource.fetchTtmlFromDb("am-lyrics", track.id) ?: return null
            if (ttmlContent.isBlank()) return null

            val parsed = TtmlParser.parse(ttmlContent, LyricsProvider.PAXSENIX)
            if (parsed.lines.isEmpty()) return null

            val effectiveDurMs = track.durationInMillis ?: parsed.effectiveDurationMs
            if (targetDurationMs != null && targetDurationMs > 0L && effectiveDurMs > 0L) {
                val deltaMs = abs(targetDurationMs - effectiveDurMs)
                if (deltaMs > LyricsAlignmentEngine.COMPATIBLE_OFFSET_MAX_DELTA_MS) {
                    return null
                }
            }

            val candDurSec = (effectiveDurMs.takeIf { it > 0L } ?: 0L) / 1000L
            val confidence = LyricsMatcher.calculateConfidence(
                queryTitle = query.title,
                queryArtist = query.artist,
                candidateTitle = track.name,
                candidateArtist = track.artistName,
                queryDurationSec = query.durationSec,
                candidateDurationSec = candDurSec.takeIf { it > 0L },
                queryAlbum = query.album
            )
            if (confidence < 50) return null

            val isWord = com.auralis.music.data.parser.WordTiming.hasGenuineWordStarts(parsed.lines)
            val syncType = if (isWord) SyncType.RICHSYNC else SyncType.LINE_SYNC

            val candidateData = parsed.copy(
                trackName = track.name,
                artistName = track.artistName,
                durationMs = effectiveDurMs.takeIf { it > 0L } ?: parsed.durationMs,
                syncType = syncType
            )

            Log.d(TAG, "Recovered Apple Music TTML from AMLL TTML DB for track '${track.name}' (id=${track.id})")
            return LyricsCandidate(
                lyricsData = candidateData,
                confidence = confidence,
                syncType = syncType,
                provider = LyricsProvider.PAXSENIX
            )
        } catch (_: Exception) {
            return null
        }
    }

    internal fun parsePaxsenixResponse(
        jsonStr: String,
        track: AppleMusicTrack,
        query: LyricsSearchQuery,
        targetDurationMs: Long?
    ): LyricsCandidate? {
        try {
            val json = JSONObject(jsonStr)
            val type = json.optString("type", "")
            val ttmlContent = json.optString("ttmlContent", "")

            if (ttmlContent.isBlank()) {
                Log.w(TAG, "Paxsenix response missing ttmlContent for track ${track.id}")
                return null
            }

            val parsed = TtmlParser.parse(ttmlContent, LyricsProvider.PAXSENIX)
            if (parsed.lines.isEmpty()) {
                Log.w(TAG, "TtmlParser produced 0 lines for track ${track.id}")
                return null
            }

            val effectiveDurMs = track.durationInMillis ?: parsed.effectiveDurationMs

            // Strict master alignment check against parsed lyrics duration
            if (targetDurationMs != null && targetDurationMs > 0L && effectiveDurMs > 0L) {
                val deltaMs = abs(targetDurationMs - effectiveDurMs)
                if (deltaMs > LyricsAlignmentEngine.COMPATIBLE_OFFSET_MAX_DELTA_MS) {
                    Log.w(TAG, "[Master Mismatch] Rejecting Paxsenix candidate due to parsed duration delta ${deltaMs}ms > 3.5s (playback=${targetDurationMs}ms, lyric=${effectiveDurMs}ms)")
                    return null
                }
            }

            val isSyllable = type.equals("Syllable", ignoreCase = true) || parsed.syncType == SyncType.RICHSYNC
            val resolvedSyncType = if (isSyllable && parsed.syncType == SyncType.RICHSYNC) {
                SyncType.RICHSYNC
            } else {
                SyncType.LINE_SYNC
            }

            val resolvedLines = if (resolvedSyncType == SyncType.LINE_SYNC) {
                // When line sync, ensure words are null so timing contract is honoured
                parsed.lines.map { it.copy(words = null) }
            } else {
                parsed.lines
            }

            val lyricsData = LyricsData(
                syncType = resolvedSyncType,
                lines = resolvedLines,
                plainLyrics = parsed.plainLyrics,
                translatedPlainLyrics = parsed.translatedPlainLyrics,
                translatedLanguage = parsed.translatedLanguage,
                provider = LyricsProvider.PAXSENIX,
                trackName = track.name,
                artistName = track.artistName,
                durationMs = effectiveDurMs,
                leadingSilenceMs = parsed.leadingSilenceMs,
                isExactVideoMatch = false,
                matchedVideoId = null
            )

            val confidence = if (resolvedSyncType == SyncType.RICHSYNC) 95 else 85
            return LyricsCandidate(
                lyricsData = lyricsData,
                confidence = confidence,
                syncType = resolvedSyncType,
                provider = LyricsProvider.PAXSENIX,
                isExactVideoMatch = false,
                matchedVideoId = null
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse Paxsenix JSON response: ${e.message}")
            return null
        }
    }
}
