package com.auralis.music.data.network

import android.util.Base64
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
    private val innerTubeClient: InnerTubeClient = InnerTubeClient(),
    private val client: OkHttpClient = NetworkClientProvider.okHttpClient
) {
    companion object {
        private const val LRCLIB_BASE_URL = "https://lrclib.net/api"
        private const val AMLL_BASE_URL = "https://api.amll.dev/v1"
        private const val KUGOU_SEARCH_URL = "http://mobileservice.kugou.com/api/v3/lyric/search"
        private const val KUGOU_KRCS_URL = "http://krcs.kugou.com/search"
        private const val KUGOU_DOWNLOAD_URL = "http://lyrics.kugou.com/download"
        private const val CLIENT_HEADER = "Auralis-Music-Android/2.0.0 (https://github.com/shreyanshchoubey09/Auralis)"
    }

    private var musixmatchToken: String? = null

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
     * Metrolist Multi-Provider Cascading Synced Lyrics Engine:
     * 1. Check LRCLIB Exact Match without strict duration (High Hit-Rate)
     * 2. Check KuGou (200M+ synchronized .lrc database with millisecond timestamps)
     * 3. Check AMLL RichSync for syllable/line timestamps
     * 4. Check LRCLIB Search with (Title + Artist) prioritizing Synced
     * 5. Check LRCLIB Search with (Title only) prioritizing Synced
     * 6. Fallback to Plain Lyrics (LRCLIB, Musixmatch, YouTube Music) ONLY when no time-sync exists
     */
    suspend fun getLyrics(
        title: String,
        artist: String,
        durationSec: Long? = null,
        videoId: String? = null
    ): LyricsData? = withContext(Dispatchers.IO) {
        val cleanTitle = TitleCleaner.cleanTitle(title)
        var cleanArtist = cleanArtistName(artist)

        if (title.contains(" - ") && isInvalidArtist(cleanArtist)) {
            val parts = title.split(" - ", limit = 2)
            if (parts.size == 2 && parts[0].isNotBlank()) {
                cleanArtist = cleanArtistName(parts[0].trim())
            }
        }

        val primaryArtist = cleanArtist
            .substringBefore(",")
            .substringBefore("&")
            .substringBefore(" feat. ")
            .substringBefore(" ft. ")
            .substringBefore(" and ")
            .trim()
        val hasValidArtist = !isInvalidArtist(primaryArtist) || !isInvalidArtist(cleanArtist)

        var candidatePlainLyrics: LyricsData? = null

        // ── PHASE 1: TRUE TIME-SYNCED SEARCH ──

        // 1. Try LRCLIB Exact Match without strict duration (High Precision)
        if (hasValidArtist) {
            val exactResult = fetchLrclibExact(cleanTitle, primaryArtist, null)
                ?: fetchLrclibExact(cleanTitle, cleanArtist, null)
                ?: (if (durationSec != null) fetchLrclibExact(cleanTitle, primaryArtist, durationSec) else null)
            if (exactResult != null) {
                if (exactResult.syncType != SyncType.PLAIN && exactResult.lines.isNotEmpty()) {
                    return@withContext exactResult
                }
                if (candidatePlainLyrics == null) {
                    candidatePlainLyrics = exactResult
                }
            }
        }

        // 2. Try Musixmatch / Spotify Lyrics Catalog (200M+ synced catalog for Hindi, Bhojpuri, Regional & Global)
        if (hasValidArtist) {
            val mxmLyrics = fetchMusixmatchLyrics(cleanTitle, primaryArtist, durationSec)
                ?: fetchMusixmatchLyrics(cleanTitle, cleanArtist, durationSec)
            if (mxmLyrics != null) {
                if (mxmLyrics.syncType != SyncType.PLAIN && mxmLyrics.lines.isNotEmpty()) {
                    return@withContext mxmLyrics
                }
                if (candidatePlainLyrics == null) {
                    candidatePlainLyrics = mxmLyrics
                }
            }
        }

        // 3. Try KuGou (Metrolist / ViMusic core synced lyrics provider)
        val kugouLyrics = fetchKugouLyrics(cleanTitle, primaryArtist, durationSec)
            ?: fetchKugouLyrics(cleanTitle, cleanArtist, durationSec)
        if (kugouLyrics != null && kugouLyrics.lines.isNotEmpty()) {
            return@withContext kugouLyrics
        }

        // 4. Try AMLL RichSync (Synced Only)
        if (hasValidArtist) {
            val amllLyrics = fetchAmllLyrics(cleanTitle, primaryArtist, durationSec)
                ?: fetchAmllLyrics(cleanTitle, cleanArtist, durationSec)
            if (amllLyrics != null && amllLyrics.lines.isNotEmpty()) {
                return@withContext amllLyrics
            }
        }

        // 5. Try LRCLIB Search with (Title + Artist) prioritizing Synced
        if (hasValidArtist) {
            val searchResult = fetchLrclibSearch("$cleanTitle $primaryArtist", cleanTitle, primaryArtist, durationSec, strictArtist = true)
                ?: fetchLrclibSearch("$cleanTitle $cleanArtist", cleanTitle, cleanArtist, durationSec, strictArtist = true)
            if (searchResult != null) {
                if (searchResult.syncType != SyncType.PLAIN && searchResult.lines.isNotEmpty()) {
                    return@withContext searchResult
                }
                if (candidatePlainLyrics == null) {
                    candidatePlainLyrics = searchResult
                }
            }
        }

        // 6. Try LRCLIB Search with (Title only) prioritizing Synced
        val titleOnlySearch = fetchLrclibSearch(cleanTitle, cleanTitle, null, durationSec, strictArtist = false)
        if (titleOnlySearch != null) {
            if (titleOnlySearch.syncType != SyncType.PLAIN && titleOnlySearch.lines.isNotEmpty()) {
                return@withContext titleOnlySearch
            }
            if (candidatePlainLyrics == null) {
                candidatePlainLyrics = titleOnlySearch
            }
        }

        // ── PHASE 2: PLAIN LYRICS FALLBACK (When no synchronized timestamps exist anywhere) ──

        if (candidatePlainLyrics != null) {
            return@withContext candidatePlainLyrics
        }

        // 7. Try YouTube Music Official Record Label Lyrics
        if (!videoId.isNullOrBlank() && !videoId.startsWith("sp_")) {
            val ytLyrics = innerTubeClient.getYouTubeMusicLyrics(videoId)
            if (ytLyrics != null && ytLyrics.lines.isNotEmpty()) {
                return@withContext ytLyrics
            }
        }

        null
    }

    /**
     * KuGou 2-Step Synced Lyrics Fetcher (Metrolist / ViMusic architecture)
     */
    private suspend fun fetchKugouLyrics(title: String, artist: String, durationSec: Long?): LyricsData? {
        try {
            val encQuery = URLEncoder.encode("$title $artist".trim(), "UTF-8")
            val searchUrl = "$KUGOU_SEARCH_URL?version=9108&highlight=1&keyword=$encQuery&plat=0&pagesize=5&area_code=1&page=1&with_res_tag=1"

            val req = Request.Builder()
                .url(searchUrl)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
                .build()

            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) return null

            var rawBody = resp.body?.string() ?: return null
            rawBody = rawBody.replace(Regex("<!--.*?-->"), "").trim()
            val json = JSONObject(rawBody)
            val info = json.optJSONObject("data")?.optJSONArray("info") ?: return null

            for (i in 0 until info.length()) {
                val item = info.optJSONObject(i) ?: continue
                val rawFileName = item.optString("filename")
                val candSinger = item.optString("singername")
                val candSongTitle = if (rawFileName.contains(" - ")) rawFileName.substringAfter(" - ") else rawFileName
                val candArtistName = if (rawFileName.contains(" - ")) rawFileName.substringBefore(" - ") else candSinger
                val candDur = item.optLong("duration", 0)

                val hash = item.optString("hash").ifBlank {
                    item.optString("320hash").ifBlank { item.optString("sqhash") }
                }
                if (hash.isBlank()) continue

                if (LyricsMatcher.isCandidateAcceptable(title, artist, candSongTitle, candArtistName)) {
                    if (durationSec == null || durationSec <= 0 || LyricsMatcher.isDurationMatching(durationSec, candDur, 15)) {
                        val durMs = if (candDur > 0) candDur * 1000 else (durationSec?.let { it * 1000 } ?: 0)
                        val krcUrl = "$KUGOU_KRCS_URL?ver=1&man=yes&client=mobi&keyword=&duration=$durMs&hash=$hash"

                        val krcReq = Request.Builder()
                            .url(krcUrl)
                            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
                            .build()

                        val krcResp = client.newCall(krcReq).execute()
                        if (krcResp.isSuccessful) {
                            val krcBody = (krcResp.body?.string() ?: "").replace(Regex("<!--.*?-->"), "").trim()
                            val krcJson = JSONObject(krcBody)
                            val candidates = krcJson.optJSONArray("candidates")

                            if (candidates != null && candidates.length() > 0) {
                                val cand = candidates.optJSONObject(0)
                                val candId = cand?.optString("id")
                                val accessKey = cand?.optString("accesskey")

                                if (!candId.isNullOrBlank() && !accessKey.isNullOrBlank()) {
                                    val dlUrl = "$KUGOU_DOWNLOAD_URL?ver=1&client=pc&id=$candId&accesskey=$accessKey&fmt=lrc&charset=utf8"
                                    val dlReq = Request.Builder().url(dlUrl).build()
                                    val dlResp = client.newCall(dlReq).execute()

                                    if (dlResp.isSuccessful) {
                                        val dlJson = JSONObject(dlResp.body?.string() ?: "")
                                        val b64Content = dlJson.optString("content")
                                        if (b64Content.isNotBlank()) {
                                            val lrcText = String(Base64.decode(b64Content, Base64.DEFAULT), Charsets.UTF_8)
                                            val parsed = LrcParser.parse(lrcText, LyricsProvider.KUGOU)
                                            if (parsed.syncType != SyncType.PLAIN && parsed.lines.isNotEmpty()) {
                                                return parsed.copy(
                                                    trackName = candSongTitle,
                                                    artistName = candArtistName
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return null
    }

    private suspend fun fetchMusixmatchLyrics(title: String, artist: String, durationSec: Long?): LyricsData? {
        try {
            if (musixmatchToken.isNullOrBlank()) {
                val tokReq = Request.Builder()
                    .url("https://apic-desktop.musixmatch.com/ws/1.1/token.get?app_id=web-desktop-app-v1.0")
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36")
                    .header("Cookie", "AWSELBCORS=0; AWSELB=0")
                    .build()
                val tokResp = client.newCall(tokReq).execute()
                if (tokResp.isSuccessful) {
                    val tokJson = JSONObject(tokResp.body?.string() ?: "")
                    musixmatchToken = tokJson.optJSONObject("message")?.optJSONObject("body")?.optString("user_token")
                }
            }

            val token = musixmatchToken ?: return null
            val encTrack = URLEncoder.encode(title, "UTF-8")
            val encArtist = URLEncoder.encode(artist, "UTF-8")
            val searchUrl = "https://apic-desktop.musixmatch.com/ws/1.1/track.search?app_id=web-desktop-app-v1.0&usertoken=$token&q_track=$encTrack&q_artist=$encArtist&page_size=5&s_track_rating=desc"

            val searchReq = Request.Builder()
                .url(searchUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .header("Cookie", "AWSELBCORS=0; AWSELB=0")
                .build()

            val searchResp = client.newCall(searchReq).execute()
            if (!searchResp.isSuccessful) return null

            val searchJson = JSONObject(searchResp.body?.string() ?: "")
            val trackList = searchJson.optJSONObject("message")?.optJSONObject("body")?.optJSONArray("track_list") ?: return null

            for (i in 0 until trackList.length()) {
                val trackObj = trackList.optJSONObject(i)?.optJSONObject("track") ?: continue
                val trackId = trackObj.optLong("track_id", 0)
                if (trackId == 0L) continue
                val hasSubtitles = trackObj.optInt("has_subtitles", 0) == 1
                val hasLyrics = trackObj.optInt("has_lyrics", 0) == 1

                // 1. Try to fetch Synced Subtitles (.lrc format)
                if (hasSubtitles) {
                    val subUrl = "https://apic-desktop.musixmatch.com/ws/1.1/track.subtitle.get?app_id=web-desktop-app-v1.0&usertoken=$token&track_id=$trackId&subtitle_format=lrc"
                    val subReq = Request.Builder().url(subUrl).header("User-Agent", "Mozilla/5.0").build()
                    val subResp = client.newCall(subReq).execute()
                    if (subResp.isSuccessful) {
                        val subJson = JSONObject(subResp.body?.string() ?: "")
                        val lrcBody = subJson.optJSONObject("message")?.optJSONObject("body")?.optJSONObject("subtitle")?.optString("subtitle_body")
                        if (!lrcBody.isNullOrBlank()) {
                            val parsed = LrcParser.parse(lrcBody, LyricsProvider.LRCLIB)
                            if (parsed.lines.isNotEmpty()) {
                                return parsed.copy(
                                    trackName = title,
                                    artistName = artist
                                )
                            }
                        }
                    }
                }

                // 2. Try to fetch Plain Lyrics if no subtitles available
                if (hasLyrics) {
                    val lrcUrl = "https://apic-desktop.musixmatch.com/ws/1.1/track.lyrics.get?app_id=web-desktop-app-v1.0&usertoken=$token&track_id=$trackId"
                    val lrcReq = Request.Builder().url(lrcUrl).header("User-Agent", "Mozilla/5.0").build()
                    val lrcResp = client.newCall(lrcReq).execute()
                    if (lrcResp.isSuccessful) {
                        val lrcJson = JSONObject(lrcResp.body?.string() ?: "")
                        val rawBody = lrcJson.optJSONObject("message")?.optJSONObject("body")?.optJSONObject("lyrics")?.optString("lyrics_body")
                        if (!rawBody.isNullOrBlank()) {
                            val cleanLyrics = rawBody.substringBefore("******* This Lyrics is NOT for Commercial use *******").trim()
                            val lines = cleanLyrics.lines()
                                .map { it.trim() }
                                .filter { it.isNotBlank() }
                                .map { LyricLine(time = 0L, text = it) }
                            return LyricsData(
                                provider = LyricsProvider.LRCLIB,
                                syncType = SyncType.PLAIN,
                                lines = lines,
                                plainLyrics = cleanLyrics,
                                trackName = title,
                                artistName = artist
                            )
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return null
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
                .header("User-Agent", CLIENT_HEADER)
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return null

            val body = response.body?.string() ?: return null
            val json = JSONObject(body)

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
        } catch (_: Exception) {}
        return null
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
                .header("User-Agent", CLIENT_HEADER)
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return null

            val body = response.body?.string() ?: return null
            val array = JSONArray(body)

            var fallbackPlainLyrics: LyricsData? = null

            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val candTitle = item.optString("trackName")
                val candArtist = item.optString("artistName")
                val candDuration = item.optLong("duration", 0)

                val isMatch = if (strictArtist && targetArtist != null) {
                    LyricsMatcher.isCandidateAcceptable(targetTitle, targetArtist, candTitle, candArtist)
                } else {
                    LyricsMatcher.diceCoefficient(targetTitle, candTitle) >= 0.5
                }

                if (isMatch) {
                    if (durationSec == null || durationSec <= 0 || LyricsMatcher.isDurationMatching(durationSec, candDuration, 15)) {
                        val syncedLyrics = item.optString("syncedLyrics")
                        val plainLyrics = item.optString("plainLyrics")

                        if (syncedLyrics.isNotBlank()) {
                            return LrcParser.parse(syncedLyrics, LyricsProvider.LRCLIB).copy(
                                trackName = candTitle,
                                artistName = candArtist,
                                plainLyrics = plainLyrics.ifBlank { null }
                            )
                        } else if (plainLyrics.isNotBlank() && fallbackPlainLyrics == null) {
                            val lines = plainLyrics.lines()
                                .map { it.trim() }
                                .filter { it.isNotBlank() }
                                .map { LyricLine(time = 0L, text = it) }
                            fallbackPlainLyrics = LyricsData(
                                provider = LyricsProvider.LRCLIB,
                                syncType = SyncType.PLAIN,
                                lines = lines,
                                plainLyrics = plainLyrics,
                                trackName = candTitle,
                                artistName = candArtist
                            )
                        }
                    }
                }
            }
            return fallbackPlainLyrics
        } catch (_: Exception) {}
        return null
    }
}
