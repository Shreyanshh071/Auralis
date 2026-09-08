package com.auralis.music.data.network.provider

import android.util.Log
import com.auralis.music.data.network.NetworkClientProvider
import com.auralis.music.data.network.TitleCleaner
import com.auralis.music.data.parser.LrcParser
import com.auralis.music.data.parser.LyricsMatcher
import com.auralis.music.data.parser.TtmlParser
import com.auralis.music.domain.model.LyricsData
import com.auralis.music.domain.model.LyricsProvider
import com.auralis.music.domain.model.SyncType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Unison lyrics provider delivering genuine TTML rich-sync word/syllable synchronized lyrics.
 * Public REST API: https://unison.boidu.dev
 *
 * Lookup priority:
 * 1. Exact YouTube video ID: GET /lyrics?v=<videoId>
 * 2. Exact metadata: GET /lyrics?song=<song>&artist=<artist>&album=<album>&duration=<seconds>
 * 3. Controlled search fallback: GET /lyrics/search?q=<query>
 */
class UnisonLyricsSource(
    private val client: OkHttpClient = NetworkClientProvider.lyricsHttpClient
) : LyricsSource {

    override val provider: LyricsProvider = LyricsProvider.UNISON
    override val supportedSyncTypes: Set<SyncType> = setOf(SyncType.RICHSYNC, SyncType.LINE_SYNC, SyncType.PLAIN)

    companion object {
        private const val TAG = "UnisonLyricsSource"
        private const val BASE_URL = "https://unison.boidu.dev"
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"
    }

    override suspend fun search(query: LyricsSearchQuery): LyricsCandidate? = withContext(Dispatchers.IO) {
        val targetDurSec = query.durationSec?.takeIf { it > 0 }
            ?: query.durationMs?.let { it / 1000L }?.takeIf { it > 0 }

        // 1. Priority A: Exact YouTube video ID lookup if available
        if (!query.videoId.isNullOrBlank()) {
            val videoCandidate = fetchByVideoId(query.videoId, query, targetDurSec)
            if (videoCandidate != null) {
                return@withContext videoCandidate
            }
        }

        // 2. Priority B: Metadata lookup
        val cleanTitle = TitleCleaner.cleanCoreSongTitle(query.title)
        val cleanArtist = TitleCleaner.cleanArtist(query.artist)
        val primaryArtist = cleanArtist
            .split(Regex("""[,&/|]|(?:\s+feat\.?\s+)|\s+ft\.?\s+|\s+and\s+|\s+with\s+""", RegexOption.IGNORE_CASE))
            .firstOrNull()?.trim() ?: cleanArtist

        if (cleanTitle.isBlank()) return@withContext null

        val artistToUse = primaryArtist.ifBlank { cleanArtist }

        // Try direct metadata lookup with duration/album
        var cand = fetchByMetadata(cleanTitle, artistToUse, query, targetDurSec, useDuration = true, useAlbum = true)
        if (cand == null && artistToUse != cleanArtist) {
            cand = fetchByMetadata(cleanTitle, cleanArtist, query, targetDurSec, useDuration = true, useAlbum = true)
        }

        // If not found and duration was specified, try without duration (in case upstream has slight cut delta)
        if (cand == null && targetDurSec != null) {
            cand = fetchByMetadata(cleanTitle, artistToUse, query, targetDurSec = null, useDuration = false, useAlbum = false)
            if (cand == null && artistToUse != cleanArtist) {
                cand = fetchByMetadata(cleanTitle, cleanArtist, query, targetDurSec = null, useDuration = false, useAlbum = false)
            }
        }

        if (cand != null) {
            return@withContext cand
        }

        // 3. Priority C: Controlled search fallback
        fetchBySearch(cleanTitle, artistToUse, query, targetDurSec)
    }

    private fun fetchByVideoId(
        videoId: String,
        query: LyricsSearchQuery,
        targetDurSec: Long?
    ): LyricsCandidate? {
        try {
            val encVid = URLEncoder.encode(videoId, "UTF-8")
            val url = "$BASE_URL/lyrics?v=$encVid"
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .build()

            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null

                val body = resp.body?.string() ?: return null
                val json = JSONObject(body)
                if (!json.optBoolean("success", false)) return null

                val data = json.optJSONObject("data") ?: return null
                val isExactMatch = !query.videoId.isNullOrBlank() && videoId.equals(query.videoId, ignoreCase = true)
                return parseCandidateFromJson(
                    data = data,
                    query = query,
                    targetDurSec = targetDurSec,
                    defaultConfidence = 95,
                    isExactVideoMatch = isExactMatch,
                    matchedVideoId = if (isExactMatch) query.videoId else null
                )
            }
        } catch (e: Exception) {
            Log.d(TAG, "Unison videoId lookup failed: ${e.message}")
            return null
        }
    }

    private fun fetchByMetadata(
        cleanTitle: String,
        artistToUse: String,
        query: LyricsSearchQuery,
        targetDurSec: Long?,
        useDuration: Boolean,
        useAlbum: Boolean
    ): LyricsCandidate? {
        try {
            val encSong = URLEncoder.encode(cleanTitle, "UTF-8")
            val encArtist = URLEncoder.encode(artistToUse, "UTF-8")
            val durParam = if (useDuration && targetDurSec != null) "&duration=$targetDurSec" else ""
            val albumParam = if (useAlbum && !query.album.isNullOrBlank()) {
                "&album=${URLEncoder.encode(query.album, "UTF-8")}"
            } else ""

            val url = "$BASE_URL/lyrics?song=$encSong&artist=$encArtist$durParam$albumParam"
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .build()

            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null

                val body = resp.body?.string() ?: return null
                val json = JSONObject(body)
                if (!json.optBoolean("success", false)) return null

                val data = json.optJSONObject("data") ?: return null
                return parseCandidateFromJson(data, query, targetDurSec)
            }
        } catch (e: Exception) {
            Log.d(TAG, "Unison metadata lookup failed: ${e.message}")
            return null
        }
    }

    private fun fetchBySearch(
        cleanTitle: String,
        artistToUse: String,
        query: LyricsSearchQuery,
        targetDurSec: Long?
    ): LyricsCandidate? {
        try {
            val encQuery = URLEncoder.encode("$cleanTitle $artistToUse", "UTF-8")
            val url = "$BASE_URL/lyrics/search?q=$encQuery"
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .build()

            val chosenVideoId = client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null

                val body = resp.body?.string() ?: return null
                val json = JSONObject(body)
                if (!json.optBoolean("success", false)) return null

                val results = json.optJSONArray("data") ?: return null
                if (results.length() == 0) return null

                var bestVideoId: String? = null
                var bestScore = -1.0

                for (i in 0 until results.length()) {
                    val item = results.getJSONObject(i)
                    val itemSong = item.optString("song")
                    val itemArtist = item.optString("artist")
                    val itemVideoId = item.optString("videoId")
                    val itemDur = item.optLong("duration", 0L).takeIf { it > 0 }
                    val isWord = item.optString("syncType").equals("richsync", ignoreCase = true)

                    if (itemVideoId.isBlank()) continue

                    val isTitleMatch = LyricsMatcher.isTitleMatching(cleanTitle, itemSong)
                    if (!isTitleMatch) continue

                    val isArtistMatch = LyricsMatcher.isArtistMatching(artistToUse, itemArtist)
                    if (!isArtistMatch) continue

                    var itemScore = 50.0
                    if (isWord) itemScore += 30.0

                    if (targetDurSec != null && itemDur != null) {
                        val diff = kotlin.math.abs(targetDurSec - itemDur)
                        if (diff <= 3) itemScore += 20.0
                        else if (diff <= 8) itemScore += 10.0
                        else if (diff > 15) itemScore -= 30.0
                    }

                    if (itemScore > bestScore) {
                        bestScore = itemScore
                        bestVideoId = itemVideoId
                    }
                }
                bestVideoId
            } ?: return null

            return fetchByVideoId(chosenVideoId, query, targetDurSec)
        } catch (e: Exception) {
            Log.d(TAG, "Unison search fallback failed: ${e.message}")
            return null
        }
    }

    private fun parseCandidateFromJson(
        data: JSONObject,
        query: LyricsSearchQuery,
        targetDurSec: Long?,
        defaultConfidence: Int? = null,
        isExactVideoMatch: Boolean = false,
        matchedVideoId: String? = null
    ): LyricsCandidate? {
        val lyrics = data.optString("lyrics")
        if (lyrics.isBlank()) return null

        val format = data.optString("format", "ttml")
        val candTitle = data.optString("song").ifBlank { query.title }
        val candArtist = data.optString("artist").ifBlank { query.artist }
        val candDuration = data.optLong("duration", 0L).takeIf { it > 0 }

        val rawParsed = if (format.equals("ttml", ignoreCase = true) || lyrics.contains("<tt", ignoreCase = true)) {
            TtmlParser.parse(lyrics, LyricsProvider.UNISON)
        } else {
            LrcParser.parse(lyrics, LyricsProvider.UNISON)
        }

        if (rawParsed.lines.isEmpty()) return null

        val withMetadata = rawParsed.copy(
            trackName = candTitle,
            artistName = candArtist,
            durationMs = rawParsed.durationMs ?: candDuration?.let { it * 1000L },
            isExactVideoMatch = isExactVideoMatch,
            matchedVideoId = matchedVideoId
        )

        val aligned = LyricsMatcher.autoAlignLyrics(withMetadata, targetDurSec, candDuration)

        val confidence = defaultConfidence ?: LyricsMatcher.calculateConfidence(
            queryTitle = query.title,
            queryArtist = query.artist,
            candidateTitle = candTitle,
            candidateArtist = candArtist,
            queryDurationSec = targetDurSec,
            candidateDurationSec = candDuration,
            queryAlbum = query.album
        )

        if (confidence < 50) return null

        return LyricsCandidate(
            lyricsData = aligned,
            confidence = confidence,
            syncType = aligned.syncType,
            provider = LyricsProvider.UNISON,
            isExactVideoMatch = isExactVideoMatch,
            matchedVideoId = matchedVideoId
        )
    }
}
