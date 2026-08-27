package com.auralis.music.data.network.provider

import com.auralis.music.data.network.NetworkClientProvider
import com.auralis.music.data.network.TitleCleaner
import com.auralis.music.data.parser.LrcParser
import com.auralis.music.data.parser.LyricsMatcher
import com.auralis.music.domain.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

class LrcLibLyricsSource(
    private val client: OkHttpClient = NetworkClientProvider.okHttpClient
) : LyricsSource {

    override val provider: LyricsProvider = LyricsProvider.LRCLIB
    override val supportedSyncTypes: Set<SyncType> = setOf(SyncType.LINE_SYNC, SyncType.PLAIN)

    companion object {
        private const val BASE_URL = "https://lrclib.net/api"
        private const val CLIENT_HEADER = "Auralis-Music-Android/2.0.0 (https://github.com/shreyanshchoubey09/Auralis)"
    }

    override suspend fun search(query: LyricsSearchQuery): LyricsCandidate? = withContext(Dispatchers.IO) {
        val cleanTitle = TitleCleaner.cleanTitle(query.title)
        val cleanArtist = TitleCleaner.cleanArtist(query.artist)

        // 1. Try exact get
        val exactCandidate = getExact(cleanTitle, cleanArtist, query.durationSec, query.album)
        if (exactCandidate != null) return@withContext exactCandidate

        // 2. Try search queries
        searchQuery("$cleanTitle $cleanArtist", query)
            ?: (if (cleanTitle != query.title) searchQuery(cleanTitle, query) else null)
    }

    private fun getExact(title: String, artist: String, durationSec: Long?, album: String?): LyricsCandidate? {
        try {
            val encTitle = URLEncoder.encode(title, "UTF-8")
            val encArtist = URLEncoder.encode(artist, "UTF-8")
            var url = "$BASE_URL/get?track_name=$encTitle&artist_name=$encArtist"
            if (!album.isNullOrBlank()) {
                url += "&album_name=${URLEncoder.encode(album.trim(), "UTF-8")}"
            }
            if (durationSec != null && durationSec > 0) {
                url += "&duration=$durationSec"
            }

            val req = Request.Builder()
                .url(url)
                .header("User-Agent", CLIENT_HEADER)
                .header("Lrclib-Client", CLIENT_HEADER)
                .build()

            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) return null

            val body = resp.body?.string() ?: return null
            val json = JSONObject(body)
            val lyricsData = parseLrcItem(json) ?: return null

            val confidence = LyricsMatcher.calculateConfidence(
                queryTitle = title,
                queryArtist = artist,
                candidateTitle = lyricsData.trackName ?: title,
                candidateArtist = lyricsData.artistName ?: artist,
                queryDurationSec = durationSec,
                candidateDurationSec = json.optLong("duration", 0L)
            )

            return LyricsCandidate(
                lyricsData = lyricsData,
                confidence = confidence.coerceAtLeast(85),
                syncType = lyricsData.syncType,
                provider = LyricsProvider.LRCLIB
            )
        } catch (_: Exception) {
            return null
        }
    }

    private fun searchQuery(searchTerm: String, query: LyricsSearchQuery): LyricsCandidate? {
        try {
            val encQuery = URLEncoder.encode(searchTerm.trim(), "UTF-8")
            val url = "$BASE_URL/search?q=$encQuery"

            val req = Request.Builder()
                .url(url)
                .header("User-Agent", CLIENT_HEADER)
                .header("Lrclib-Client", CLIENT_HEADER)
                .build()

            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) return null

            val body = resp.body?.string() ?: return null
            val array = JSONArray(body)

            var bestCandidate: LyricsCandidate? = null
            var bestConfidence = 0

            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val candTitle = item.optString("trackName")
                val candArtist = item.optString("artistName")
                val candDuration = item.optLong("duration", 0L)

                val confidence = LyricsMatcher.calculateConfidence(
                    queryTitle = query.title,
                    queryArtist = query.artist,
                    candidateTitle = candTitle,
                    candidateArtist = candArtist,
                    queryDurationSec = query.durationSec,
                    candidateDurationSec = candDuration
                )

                if (confidence >= 50 && confidence > bestConfidence) {
                    val parsed = parseLrcItem(item)
                    if (parsed != null && parsed.lines.isNotEmpty()) {
                        bestConfidence = confidence
                        bestCandidate = LyricsCandidate(
                            lyricsData = parsed,
                            confidence = confidence,
                            syncType = parsed.syncType,
                            provider = LyricsProvider.LRCLIB
                        )
                        if (parsed.syncType == SyncType.LINE_SYNC && confidence >= 80) {
                            return bestCandidate
                        }
                    }
                }
            }

            return bestCandidate
        } catch (_: Exception) {
            return null
        }
    }

    private fun parseLrcItem(json: JSONObject): LyricsData? {
        val syncedLyrics = json.optString("syncedLyrics")
        val plainLyrics = json.optString("plainLyrics")
        val trackName = json.optString("trackName")
        val artistName = json.optString("artistName")

        if (syncedLyrics.isNotBlank()) {
            return LrcParser.parse(syncedLyrics, LyricsProvider.LRCLIB).copy(
                trackName = trackName,
                artistName = artistName,
                plainLyrics = plainLyrics.ifBlank { null }
            )
        } else if (plainLyrics.isNotBlank()) {
            val lines = plainLyrics.lines()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .map { LyricLine(time = 0L, text = it) }
            return LyricsData(
                provider = LyricsProvider.LRCLIB,
                syncType = SyncType.PLAIN,
                lines = lines,
                plainLyrics = plainLyrics,
                trackName = trackName,
                artistName = artistName
            )
        }
        return null
    }
}
