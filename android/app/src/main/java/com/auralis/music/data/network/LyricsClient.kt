package com.auralis.music.data.network

import com.auralis.music.data.parser.LrcParser
import com.auralis.music.data.parser.LyricsMatcher
import com.auralis.music.data.parser.TtmlParser
import com.auralis.music.domain.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

class LyricsClient(
    private val client: OkHttpClient = NetworkClientProvider.okHttpClient
) {
    companion object {
        private const val LRCLIB_BASE_URL = "https://lrclib.net/api"
        private const val AMLL_BASE_URL = "https://api.amll.dev/v1"
        private const val CLIENT_HEADER = "Auralis-Music-Android/2.0.0 (https://github.com/shreyanshchoubey09/Auralis)"
    }

    private fun isInvalidArtist(artist: String?): Boolean {
        if (artist.isNullOrBlank()) return true
        val lower = artist.trim().lowercase()
        return lower in listOf(
            "shreyanshh", "shreyansh", "youtube music", "youtube", "artist",
            "unknown artist", "various artists", "various", "topic", "guest listener", "admin"
        ) || lower.startsWith("user_") || lower.startsWith("yt_")
    }

    private fun cleanArtistName(artist: String): String {
        return artist.replace(Regex("(?i) - Topic$"), "")
            .replace(Regex("(?i)Official$"), "")
            .replace(Regex("(?i)VEVO$"), "")
            .trim()
    }

    /**
     * Multi-tier Synced Lyrics Resolver:
     * 1. LRCLIB Exact Match (Clean Title + Real Artist)
     * 2. AMLL Syllable-Level / RichSync
     * 3. LRCLIB Intelligent Synced Search (Prioritizes syncedLyrics > 0)
     * 4. Fallback Plain Lyrics if synced is unavailable (Never removes lyrics)
     */
    suspend fun getLyrics(
        title: String,
        artist: String,
        durationSec: Long? = null
    ): LyricsData? = withContext(Dispatchers.IO) {
        var cleanTitle = TitleCleaner.cleanTitle(title)
        var cleanArtist = cleanArtistName(artist)

        if (title.contains(" - ")) {
            val parts = title.split(" - ", limit = 2)
            if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
                if (isInvalidArtist(cleanArtist)) {
                    cleanArtist = cleanArtistName(parts[0].trim())
                }
                cleanTitle = TitleCleaner.cleanTitle(parts[1].trim())
            }
        }

        val hasValidArtist = !isInvalidArtist(cleanArtist)

        // 1. Try LRCLIB Exact (without strict duration first for maximum hit rate)
        if (hasValidArtist) {
            val lrclibExact = fetchLrclibExact(cleanTitle, cleanArtist, null)
            if (lrclibExact != null && lrclibExact.lines.isNotEmpty()) {
                return@withContext lrclibExact
            }
        }

        // 2. Try AMLL RichSync
        if (hasValidArtist) {
            val amllLyrics = fetchAmllLyrics(cleanTitle, cleanArtist, durationSec)
            if (amllLyrics != null && amllLyrics.lines.isNotEmpty()) {
                return@withContext amllLyrics
            }
        }

        // 3. Try LRCLIB Search with (Title + Artist) prioritizing synced lyrics
        if (hasValidArtist) {
            val lrclibSearch = fetchLrclibSearch("$cleanTitle $cleanArtist", cleanTitle, cleanArtist, durationSec, strictArtist = true)
            if (lrclibSearch != null && lrclibSearch.lines.isNotEmpty()) {
                return@withContext lrclibSearch
            }
        }

        // 4. Fallback LRCLIB Search with (Title only)
        val titleOnlySearch = fetchLrclibSearch(cleanTitle, cleanTitle, null, durationSec, strictArtist = false)
        if (titleOnlySearch != null && titleOnlySearch.lines.isNotEmpty()) {
            return@withContext titleOnlySearch
        }

        null
    }

    private suspend fun fetchAmllLyrics(title: String, artist: String, durationSec: Long?): LyricsData? {
        try {
            val q = URLEncoder.encode("$title $artist", "UTF-8")
            val url = "$AMLL_BASE_URL/search/lyrics?q=$q"
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
                val candDur = item.optLong("duration", 0)

                if (LyricsMatcher.isCandidateAcceptable(title, artist, candTitle, candArtist)) {
                    if (durationSec == null || durationSec <= 0 || LyricsMatcher.isDurationMatching(durationSec, candDur, 10)) {
                        val ttml = item.optString("ttml")
                        if (ttml.isNotBlank()) {
                            return TtmlParser.parse(ttml, LyricsProvider.AMLL).copy(
                                trackName = candTitle,
                                artistName = candArtist
                            )
                        }
                        val lrc = item.optString("lrc")
                        if (lrc.isNotBlank()) {
                            return LrcParser.parse(lrc, LyricsProvider.AMLL).copy(
                                trackName = candTitle,
                                artistName = candArtist
                            )
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return null
    }

    private suspend fun fetchLrclibExact(title: String, artist: String, durationSec: Long?): LyricsData? {
        try {
            val encTitle = URLEncoder.encode(title, "UTF-8")
            val encArtist = URLEncoder.encode(artist, "UTF-8")
            var url = "$LRCLIB_BASE_URL/get?track_name=$encTitle&artist_name=$encArtist"
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
            if (!response.isSuccessful) return null

            val body = response.body?.string() ?: return null
            val json = JSONObject(body)
            return parseLrclibItem(json)
        } catch (_: Exception) {
            return null
        }
    }

    private suspend fun fetchLrclibSearch(
        query: String,
        targetTitle: String,
        targetArtist: String?,
        durationSec: Long?,
        strictArtist: Boolean
    ): LyricsData? {
        try {
            val encQuery = URLEncoder.encode(query, "UTF-8")
            val url = "$LRCLIB_BASE_URL/search?q=$encQuery"

            val request = Request.Builder()
                .url(url)
                .header("Lrclib-Client", CLIENT_HEADER)
                .header("User-Agent", CLIENT_HEADER)
                .header("Accept", "application/json")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return null

            val body = response.body?.string() ?: return null
            val array = JSONArray(body)

            var fallbackPlain: LyricsData? = null

            // Prioritize items with syncedLyrics first
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val candTitle = item.optString("trackName")
                val candArtist = item.optString("artistName")
                val synced = item.optString("syncedLyrics")

                val titleScore = LyricsMatcher.diceCoefficient(targetTitle, candTitle)
                val artistScore = if (targetArtist != null) LyricsMatcher.diceCoefficient(targetArtist, candArtist) else 1.0

                val isMatch = if (strictArtist && targetArtist != null) {
                    titleScore >= 0.50 && artistScore >= 0.40
                } else {
                    titleScore >= 0.55
                }

                if (isMatch) {
                    val parsed = parseLrclibItem(item)
                    if (parsed != null) {
                        if (synced.isNotBlank()) {
                            return parsed // Found synced lyrics!
                        } else if (fallbackPlain == null) {
                            fallbackPlain = parsed
                        }
                    }
                }
            }

            return fallbackPlain
        } catch (_: Exception) {
            return null
        }
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
