package com.auralis.music.data.remote

import com.auralis.music.data.network.NetworkClientProvider
import com.auralis.music.data.network.TitleCleaner
import com.auralis.music.data.parser.LrcParser
import com.auralis.music.data.parser.LyricsMatcher
import com.auralis.music.domain.model.LyricLine
import com.auralis.music.domain.model.LyricsData
import com.auralis.music.domain.model.LyricsProvider
import com.auralis.music.domain.model.SyncType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * LRCLIB Remote API client for synchronized and plain lyrics.
 */
class LrcLibApi(
    private val client: OkHttpClient = NetworkClientProvider.okHttpClient,
    private val baseUrl: String = DEFAULT_BASE_URL
) {
    companion object {
        const val DEFAULT_BASE_URL = "https://lrclib.net/api"
        private const val CLIENT_HEADER = "Auralis-Music-Android/1.0.0 (https://github.com/shreyanshchoubey09/Auralis)"
    }

    /**
     * Attempts exact match query for a track on LRCLIB.
     */
    suspend fun getExactLyrics(
        title: String,
        artist: String,
        album: String? = null,
        durationSec: Long? = null
    ): LyricsData? = withContext(Dispatchers.IO) {
        val cleanTitle = TitleCleaner.cleanTitle(title)
        val cleanArtist = artist.trim()

        try {
            val encTitle = URLEncoder.encode(cleanTitle, "UTF-8")
            val encArtist = URLEncoder.encode(cleanArtist, "UTF-8")
            var url = "$baseUrl/get?track_name=$encTitle&artist_name=$encArtist"
            if (!album.isNullOrBlank()) {
                url += "&album_name=${URLEncoder.encode(album.trim(), "UTF-8")}"
            }
            if (durationSec != null && durationSec > 0) {
                url += "&duration=$durationSec"
            }

            val request = Request.Builder()
                .url(url)
                .header("Lrclib-Client", CLIENT_HEADER)
                .header("User-Agent", CLIENT_HEADER)
                .header("Accept", "application/json")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return@withContext null

            val body = response.body?.string() ?: return@withContext null
            val json = JSONObject(body)
            parseLrclibItem(json)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Performs fuzzy search on LRCLIB when exact match fails.
     */
    suspend fun searchLyrics(
        title: String,
        artist: String,
        durationSec: Long? = null
    ): LyricsData? = withContext(Dispatchers.IO) {
        val cleanTitle = TitleCleaner.cleanTitle(title)
        val cleanArtist = artist.trim()

        try {
            val encQuery = URLEncoder.encode("$cleanTitle $cleanArtist", "UTF-8")
            val url = "$baseUrl/search?q=$encQuery"

            val request = Request.Builder()
                .url(url)
                .header("Lrclib-Client", CLIENT_HEADER)
                .header("User-Agent", CLIENT_HEADER)
                .header("Accept", "application/json")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return@withContext null

            val body = response.body?.string() ?: return@withContext null
            val array = JSONArray(body)

            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val candTitle = item.optString("trackName")
                val candArtist = item.optString("artistName")
                val candDur = item.optLong("duration", 0)

                if (LyricsMatcher.isCandidateAcceptable(cleanTitle, cleanArtist, candTitle, candArtist)) {
                    if (durationSec == null || durationSec <= 0 || LyricsMatcher.isDurationMatching(durationSec, candDur)) {
                        val parsed = parseLrclibItem(item)
                        if (parsed != null) return@withContext parsed
                    }
                }
            }
        } catch (_: Exception) {
            // Error handling
        }
        null
    }

    private fun parseLrclibItem(json: JSONObject): LyricsData? {
        val synced = json.optString("syncedLyrics")
        val plain = json.optString("plainLyrics")
        val trackName = json.optString("trackName")
        val artistName = json.optString("artistName")

        if (synced.isNotBlank()) {
            val parsed = LrcParser.parse(synced, LyricsProvider.LRCLIB)
            return parsed.copy(trackName = trackName, artistName = artistName)
        }
        if (plain.isNotBlank()) {
            return LyricsData(
                syncType = SyncType.PLAIN,
                lines = plain.lines().map { LyricLine(time = 0, text = it) },
                plainLyrics = plain,
                provider = LyricsProvider.LRCLIB,
                trackName = trackName,
                artistName = artistName
            )
        }
        return null
    }
}
