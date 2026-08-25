package com.auralis.music.data.remote

import com.auralis.music.data.network.NetworkClientProvider
import com.auralis.music.data.network.TitleCleaner
import com.auralis.music.data.parser.LrcParser
import com.auralis.music.data.parser.LyricsMatcher
import com.auralis.music.data.parser.TtmlParser
import com.auralis.music.domain.model.LyricsData
import com.auralis.music.domain.model.LyricsProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder

/**
 * AMLL (Apple Music Like Lyrics) API client for syllable-level richsync TTML karaoke lyrics.
 */
class AmllApi(
    private val client: OkHttpClient = NetworkClientProvider.okHttpClient,
    private val baseUrl: String = DEFAULT_BASE_URL
) {
    companion object {
        const val DEFAULT_BASE_URL = "https://api.amll.dev/v1"
        private const val CLIENT_HEADER = "Auralis-Music-Android/2.0.0 (https://github.com/shreyanshchoubey09/Auralis)"
    }

    /**
     * Searches AMLL for richsync syllable-level TTML lyrics.
     */
    suspend fun searchRichSyncLyrics(
        title: String,
        artist: String,
        durationSec: Long? = null
    ): LyricsData? = withContext(Dispatchers.IO) {
        val cleanTitle = TitleCleaner.cleanTitle(title)
        val cleanArtist = artist.trim()

        try {
            val q = URLEncoder.encode("$cleanTitle $cleanArtist", "UTF-8")
            val url = "$baseUrl/search/lyrics?q=$q"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", CLIENT_HEADER)
                .header("Accept", "application/json")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return@withContext null

            val body = response.body?.string() ?: return@withContext null
            val json = JSONObject(body)
            val data = json.optJSONArray("data") ?: return@withContext null

            for (i in 0 until data.length()) {
                val item = data.optJSONObject(i) ?: continue
                val candTitle = item.optString("trackName")
                val candArtist = item.optString("artistName")
                val candDur = item.optLong("duration", 0)

                if (LyricsMatcher.isCandidateAcceptable(cleanTitle, cleanArtist, candTitle, candArtist)) {
                    if (durationSec == null || durationSec <= 0 || LyricsMatcher.isDurationMatching(durationSec, candDur)) {
                        val ttml = item.optString("ttml")
                        if (ttml.isNotBlank()) {
                            return@withContext TtmlParser.parse(ttml, LyricsProvider.AMLL).copy(
                                trackName = candTitle,
                                artistName = candArtist
                            )
                        }

                        val lrc = item.optString("lrc")
                        if (lrc.isNotBlank()) {
                            return@withContext LrcParser.parse(lrc, LyricsProvider.AMLL).copy(
                                trackName = candTitle,
                                artistName = candArtist
                            )
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // Silently fall back to next tier
        }
        null
    }
}
