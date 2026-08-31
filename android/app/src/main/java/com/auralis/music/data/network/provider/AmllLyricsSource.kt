package com.auralis.music.data.network.provider

import com.auralis.music.data.network.NetworkClientProvider
import com.auralis.music.data.network.TitleCleaner
import com.auralis.music.data.parser.LrcParser
import com.auralis.music.data.parser.LyricsMatcher
import com.auralis.music.data.parser.TtmlParser
import com.auralis.music.domain.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder

class AmllLyricsSource(
    private val client: OkHttpClient = NetworkClientProvider.lyricsHttpClient
) : LyricsSource {

    override val provider: LyricsProvider = LyricsProvider.AMLL
    override val supportedSyncTypes: Set<SyncType> = setOf(SyncType.RICHSYNC, SyncType.LINE_SYNC)

    companion object {
        /**
         * NOTE: this endpoint currently answers 404, so the provider is inert in
         * practice — it is kept because it costs nothing and would resume working
         * if the host returns. Do not treat AMLL as a live word-timing source when
         * reasoning about which provider supplied a track's timings.
         */
        private const val BASE_URL = "https://api.amll.dev/v1"
        private const val CLIENT_HEADER = "Auralis-Music-Android/2.0.0 (https://github.com/shreyanshchoubey09/Auralis)"
    }

    override suspend fun search(query: LyricsSearchQuery): LyricsCandidate? = withContext(Dispatchers.IO) {
        val cleanTitle = TitleCleaner.cleanTitle(query.title)
        val cleanArtist = TitleCleaner.cleanArtist(query.artist)

        searchAmll("$cleanTitle $cleanArtist", query)
            ?: (if (cleanTitle != query.title) searchAmll(cleanTitle, query) else null)
    }

    private fun searchAmll(searchTerm: String, query: LyricsSearchQuery): LyricsCandidate? {
        try {
            val q = URLEncoder.encode(searchTerm.trim(), "UTF-8")
            val url = "$BASE_URL/search/lyrics?q=$q"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", CLIENT_HEADER)
                .header("Accept", "application/json")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return null

            val body = response.body?.string() ?: return null
            val json = JSONObject(body)
            val data = json.optJSONArray("data") ?: return null

            for (i in 0 until data.length()) {
                val item = data.optJSONObject(i) ?: continue
                val candTitle = item.optString("trackName")
                val candArtist = item.optString("artistName")
                val candDur = item.optLong("duration", 0L)

                val confidence = LyricsMatcher.calculateConfidence(
                    queryTitle = query.title,
                    queryArtist = query.artist,
                    candidateTitle = candTitle,
                    candidateArtist = candArtist,
                    queryDurationSec = query.durationSec,
                    candidateDurationSec = candDur
                )

                if (confidence >= 50) {
                    val ttml = item.optString("ttml")
                    if (ttml.isNotBlank()) {
                        val parsed = TtmlParser.parse(ttml, LyricsProvider.AMLL).copy(
                            trackName = candTitle,
                            artistName = candArtist
                        )
                        // Report what the parser actually found. TTML without per-word
                        // end timestamps comes back as LINE_SYNC, and claiming RICHSYNC
                        // for it would enter the word-sync tier with no word timing.
                        return LyricsCandidate(
                            lyricsData = parsed,
                            confidence = confidence,
                            syncType = parsed.syncType,
                            provider = LyricsProvider.AMLL
                        )
                    }

                    val lrc = item.optString("lrc")
                    if (lrc.isNotBlank()) {
                        val parsed = LrcParser.parse(lrc, LyricsProvider.AMLL).copy(
                            trackName = candTitle,
                            artistName = candArtist
                        )
                        return LyricsCandidate(
                            lyricsData = parsed,
                            confidence = confidence,
                            syncType = parsed.syncType,
                            provider = LyricsProvider.AMLL
                        )
                    }
                }
            }
            return null
        } catch (_: Exception) {
            return null
        }
    }
}
