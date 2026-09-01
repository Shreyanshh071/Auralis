package com.auralis.music.data.network.provider

import com.auralis.music.data.network.NetworkClientProvider
import com.auralis.music.data.network.TitleCleaner
import com.auralis.music.data.parser.LrcParser
import com.auralis.music.data.parser.LyricsMatcher
import com.auralis.music.data.parser.YrcParser
import com.auralis.music.domain.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder

/**
 * NetEase Cloud Music Provider: High-coverage open synchronized (.lrc and .yrc) database
 * for global pop, K-pop, J-pop, EDM, Anime, Latin, and international catalog.
 */
class NetEaseLyricsSource(
    private val client: OkHttpClient = NetworkClientProvider.lyricsHttpClient
) : LyricsSource {

    override val provider: LyricsProvider = LyricsProvider.NETEASE
    override val supportedSyncTypes: Set<SyncType> = setOf(SyncType.RICHSYNC, SyncType.LINE_SYNC, SyncType.PLAIN)

    companion object {
        private const val SEARCH_URL = "https://music.163.com/api/search/get/web"
        private const val LYRIC_URL = "https://music.163.com/api/song/lyric"
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
    }

    override suspend fun search(query: LyricsSearchQuery): LyricsCandidate? = withContext(Dispatchers.IO) {
        val cleanTitle = TitleCleaner.cleanCoreSongTitle(query.title)
        val cleanArtist = TitleCleaner.cleanArtist(query.artist)
        val primaryArtist = cleanArtist
            .split(Regex("""[,&/|]|(?:\s+feat\.?\s+)|\s+ft\.?\s+|\s+and\s+|\s+with\s+""", RegexOption.IGNORE_CASE))
            .firstOrNull()?.trim() ?: cleanArtist

        if (primaryArtist.isNotBlank() && primaryArtist != cleanArtist) {
            searchNetEase("$cleanTitle $primaryArtist", query)?.let { return@withContext it }
        }

        searchNetEase("$cleanTitle $cleanArtist", query)
            ?: searchNetEase(cleanTitle, query)
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
                    val rawLyrics = fetchLyricsBySongId(songId, songName, artistName)
                    if (rawLyrics != null && rawLyrics.lines.isNotEmpty()) {
                        val lyricsData = LyricsMatcher.autoAlignLyrics(rawLyrics, query.durationSec, durationSec)
                        bestConfidence = confidence
                        bestCandidate = LyricsCandidate(
                            lyricsData = lyricsData,
                            confidence = confidence,
                            syncType = lyricsData.syncType,
                            provider = LyricsProvider.NETEASE
                        )
                        if (lyricsData.syncType == SyncType.RICHSYNC && confidence >= 65) {
                            return bestCandidate
                        }
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
            val url = "$LYRIC_URL?os=pc&id=$songId&lv=-1&kv=-1&tv=-1&yv=-1&rv=-1"
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

            // 1. Try YRC (Word-by-word / Syllable-level RichSync)
            val rawYrc = json.optJSONObject("yrc")?.optString("lyric") ?: ""
            if (rawYrc.isNotBlank() && !rawYrc.contains("纯音乐")) {
                val parsedYrc = YrcParser.parse(rawYrc, LyricsProvider.NETEASE, trackName, artistName)
                if (parsedYrc != null && parsedYrc.lines.isNotEmpty() && parsedYrc.syncType == SyncType.RICHSYNC) {
                    return parsedYrc
                }
            }

            // 1.5 Try AMLL TTML DB repository (over 20,000 community curated studio TTML tracks)
            try {
                val ttmlUrl = "https://raw.githubusercontent.com/amll-dev/amll-ttml-db/main/ncm-lyrics/$songId.ttml"
                val ttmlReq = Request.Builder().url(ttmlUrl).header("User-Agent", USER_AGENT).build()
                val ttmlResp = client.newCall(ttmlReq).execute()
                if (ttmlResp.isSuccessful) {
                    val ttmlContent = ttmlResp.body?.string() ?: ""
                    if (ttmlContent.isNotBlank()) {
                        val parsedTtml = com.auralis.music.data.parser.TtmlParser.parse(ttmlContent, LyricsProvider.NETEASE)
                        if (parsedTtml.lines.isNotEmpty() && parsedTtml.syncType == SyncType.RICHSYNC) {
                            return parsedTtml.copy(trackName = trackName, artistName = artistName)
                        }
                    }
                }
            } catch (_: Exception) {}

            // 2. Try Standard LRC
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

