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

/**
 * JioSaavn Lyrics Provider: First-class support for Bhojpuri, Hindi, Punjabi, Bengali,
 * Tamil, Telugu, Marathi, Gujarati, Kannada, Malayalam, Urdu, and Indian regional music.
 */
class JioSaavnLyricsSource(
    private val client: OkHttpClient = NetworkClientProvider.okHttpClient
) : LyricsSource {

    override val provider: LyricsProvider = LyricsProvider.JIOSAAVN
    override val supportedSyncTypes: Set<SyncType> = setOf(SyncType.LINE_SYNC, SyncType.PLAIN)

    companion object {
        private const val BASE_URL = "https://www.jiosaavn.com/api.php"
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"
    }

    override suspend fun search(query: LyricsSearchQuery): LyricsCandidate? = withContext(Dispatchers.IO) {
        val cleanTitle = TitleCleaner.cleanTitle(query.title)
        val cleanArtist = TitleCleaner.cleanArtist(query.artist)

        // Try primary query first, fallback to title-only if artist is ambiguous or omitted
        val candidate = searchJioSaavn("$cleanTitle $cleanArtist", query)
            ?: searchJioSaavn(cleanTitle, query)

        candidate
    }

    private fun searchJioSaavn(searchTerm: String, query: LyricsSearchQuery): LyricsCandidate? {
        try {
            val encQuery = URLEncoder.encode(searchTerm.trim(), "UTF-8")
            val searchUrl = "$BASE_URL?__call=search.getResults&_format=json&_marker=0&ctx=android&n=5&p=1&q=$encQuery"

            val req = Request.Builder()
                .url(searchUrl)
                .header("User-Agent", USER_AGENT)
                .build()

            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) return null

            val body = resp.body?.string() ?: return null
            val json = JSONObject(body)
            val results = json.optJSONArray("results") ?: return null

            var bestCandidate: LyricsCandidate? = null
            var bestConfidence = 0

            for (i in 0 until results.length()) {
                val item = results.optJSONObject(i) ?: continue
                val songId = item.optString("id")
                val songTitle = cleanHtmlEntities(item.optString("song").ifBlank { item.optString("title") })
                val moreInfo = item.optJSONObject("more_info")
                val songArtist = cleanHtmlEntities(
                    moreInfo?.optString("primary_artists") ?: item.optString("primary_artists", "")
                )
                val duration = item.optLong("duration", moreInfo?.optLong("duration", 0L) ?: 0L)
                val hasLyrics = item.optString("has_lyrics") == "true" || moreInfo?.optString("has_lyrics") == "true"

                if (songId.isBlank() || !hasLyrics) continue

                val confidence = LyricsMatcher.calculateConfidence(
                    queryTitle = query.title,
                    queryArtist = query.artist,
                    candidateTitle = songTitle,
                    candidateArtist = songArtist,
                    queryDurationSec = query.durationSec,
                    candidateDurationSec = duration
                )

                if (confidence >= 50 && confidence > bestConfidence) {
                    val lyricsData = fetchLyricsById(songId, songTitle, songArtist)
                    if (lyricsData != null && lyricsData.lines.isNotEmpty()) {
                        bestConfidence = confidence
                        bestCandidate = LyricsCandidate(
                            lyricsData = lyricsData,
                            confidence = confidence,
                            syncType = lyricsData.syncType,
                            provider = LyricsProvider.JIOSAAVN
                        )
                        // If line-synced and confidence is very high, return immediately
                        if (lyricsData.syncType == SyncType.LINE_SYNC && confidence >= 80) {
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

    private fun fetchLyricsById(lyricsId: String, trackName: String, artistName: String): LyricsData? {
        try {
            val url = "$BASE_URL?__call=lyrics.getLyrics&_format=json&_marker=0&ctx=android&lyrics_id=$lyricsId"
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .build()

            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) return null

            val body = resp.body?.string() ?: return null
            val json = JSONObject(body)
            val rawLyrics = json.optString("lyrics")
            if (rawLyrics.isBlank()) return null

            val formattedText = cleanHtmlEntities(rawLyrics.replace("<br/>", "\n").replace("<br>", "\n"))

            // Check if lyrics contain LRC timestamp formatting
            if (formattedText.contains(Regex("""\[\d{2}:\d{2}"""))) {
                val parsed = LrcParser.parse(formattedText, LyricsProvider.JIOSAAVN)
                return parsed.copy(
                    trackName = trackName,
                    artistName = artistName,
                    plainLyrics = formattedText
                )
            }

            // Plain lyrics
            val lines = formattedText.lines()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .map { LyricLine(time = 0L, text = it) }

            return LyricsData(
                syncType = SyncType.PLAIN,
                lines = lines,
                plainLyrics = formattedText,
                provider = LyricsProvider.JIOSAAVN,
                trackName = trackName,
                artistName = artistName
            )
        } catch (_: Exception) {
            return null
        }
    }

    private fun cleanHtmlEntities(text: String): String {
        return text
            .replace("&quot;", "\"")
            .replace("&amp;", "&")
            .replace("&#039;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace(Regex("""&[a-zA-Z0-9#]+;"""), " ")
            .trim()
    }
}
