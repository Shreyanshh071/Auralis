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
import org.json.JSONObject
import java.net.URLEncoder

/**
 * NetEase Cloud Music Provider: High-coverage open synchronized (.lrc) database
 * for global pop, K-pop, J-pop, EDM, Anime, Latin, and international catalog.
 */
class NetEaseLyricsSource(
    private val client: OkHttpClient = NetworkClientProvider.okHttpClient
) : LyricsSource {

    override val provider: LyricsProvider = LyricsProvider.NETEASE
    override val supportedSyncTypes: Set<SyncType> = setOf(SyncType.LINE_SYNC, SyncType.PLAIN)

    companion object {
        private const val SEARCH_URL = "http://music.163.com/api/search/get/web"
        private const val LYRIC_URL = "http://music.163.com/api/song/lyric"
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
    }

    override suspend fun search(query: LyricsSearchQuery): LyricsCandidate? = withContext(Dispatchers.IO) {
        val cleanTitle = TitleCleaner.cleanTitle(query.title)
        val cleanArtist = TitleCleaner.cleanArtist(query.artist)

        searchNetEase("$cleanTitle $cleanArtist", query)
            ?: (if (cleanTitle != query.title) searchNetEase(cleanTitle, query) else null)
    }

    private fun searchNetEase(searchTerm: String, query: LyricsSearchQuery): LyricsCandidate? {
        try {
            val encQuery = URLEncoder.encode(searchTerm.trim(), "UTF-8")
            val url = "$SEARCH_URL?csrf_token=&type=1&offset=0&total=true&limit=5&s=$encQuery"

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

            var bestCandidate: LyricsCandidate? = null
            var bestConfidence = 0

            for (i in 0 until songs.length()) {
                val songObj = songs.optJSONObject(i) ?: continue
                val songId = songObj.optLong("id", 0L)
                if (songId == 0L) continue

                val songName = songObj.optString("name")
                val artists = songObj.optJSONArray("artists")
                val artistName = if (artists != null && artists.length() > 0) {
                    artists.optJSONObject(0)?.optString("name") ?: ""
                } else ""
                val durationSec = songObj.optLong("duration", 0L) / 1000L

                val confidence = LyricsMatcher.calculateConfidence(
                    queryTitle = query.title,
                    queryArtist = query.artist,
                    candidateTitle = songName,
                    candidateArtist = artistName,
                    queryDurationSec = query.durationSec,
                    candidateDurationSec = durationSec
                )

                if (confidence >= 50 && confidence > bestConfidence) {
                    val lyricsData = fetchLyricsBySongId(songId, songName, artistName)
                    if (lyricsData != null && lyricsData.lines.isNotEmpty()) {
                        bestConfidence = confidence
                        bestCandidate = LyricsCandidate(
                            lyricsData = lyricsData,
                            confidence = confidence,
                            syncType = lyricsData.syncType,
                            provider = LyricsProvider.NETEASE
                        )
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

    private fun fetchLyricsBySongId(songId: Long, trackName: String, artistName: String): LyricsData? {
        try {
            val url = "$LYRIC_URL?os=pc&id=$songId&lv=-1&kv=-1&tv=-1"
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

            val rawLrc = json.optJSONObject("lrc")?.optString("lyric") ?: ""
            if (rawLrc.isBlank() || rawLrc.contains("纯音乐，请欣赏") || rawLrc.contains("没有填词")) {
                // Check if track is flagged as purely instrumental
                if (rawLrc.contains("纯音乐")) {
                    return LyricsData(
                        syncType = SyncType.PLAIN,
                        lines = listOf(LyricLine(time = 0L, text = "[Instrumental]", isInstrumental = true)),
                        plainLyrics = "[Instrumental]",
                        provider = LyricsProvider.NETEASE,
                        trackName = trackName,
                        artistName = artistName
                    )
                }
                return null
            }

            val parsed = LrcParser.parse(rawLrc, LyricsProvider.NETEASE)
            return parsed.copy(
                trackName = trackName,
                artistName = artistName
            )
        } catch (_: Exception) {
            return null
        }
    }
}
