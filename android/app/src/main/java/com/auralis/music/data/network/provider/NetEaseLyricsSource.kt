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
        private const val SEARCH_URL = "https://music.163.com/api/search/get"
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
            var bestCandidateTier = com.auralis.music.data.network.LyricsClient.TIER_NONE
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
                val durationMs = songObj.optLong("duration", 0L).takeIf { it > 0L }
                val durationSec = (durationMs ?: 0L) / 1000L

                val targetDurationMs = query.durationMs?.takeIf { it > 0L }
                    ?: (query.durationSec?.let { it * 1000L }?.takeIf { it > 0L })
                if (targetDurationMs != null && targetDurationMs > 0L && durationMs != null && durationMs > 0L) {
                    val deltaMs = kotlin.math.abs(targetDurationMs - durationMs)
                    if (deltaMs > com.auralis.music.domain.lyrics.LyricsAlignmentEngine.COMPATIBLE_OFFSET_MAX_DELTA_MS) {
                        continue // Reject master mismatch: duration delta > 3.5s (e.g. 321s vs 326s)
                    }
                }

                val confidence = LyricsMatcher.calculateConfidence(
                    queryTitle = query.title,
                    queryArtist = query.artist,
                    candidateTitle = songName,
                    candidateArtist = artistName,
                    queryDurationSec = query.durationSec,
                    candidateDurationSec = durationSec
                )

                if (confidence >= 50) {
                    val rawLyrics = fetchLyricsBySongId(songId, songName, artistName, durationMs)
                    if (rawLyrics != null && rawLyrics.lines.isNotEmpty()) {
                        val lyricsData = LyricsMatcher.autoAlignLyrics(rawLyrics, query.durationSec, durationSec)
                            .let { if (it.syncType == SyncType.RICHSYNC) it else it.copy(durationMs = durationMs ?: rawLyrics.durationMs) }
                        val tier = com.auralis.music.data.network.LyricsClient.tierOf(lyricsData)
                        val candidate = LyricsCandidate(
                            lyricsData = lyricsData,
                            confidence = confidence,
                            syncType = if (tier == com.auralis.music.data.network.LyricsClient.TIER_WORD) SyncType.RICHSYNC else lyricsData.syncType,
                            provider = LyricsProvider.NETEASE
                        )

                        // Immediately return valid genuine word-level karaoke sync without querying remaining candidates
                        if (tier == com.auralis.music.data.network.LyricsClient.TIER_WORD && confidence >= 60) {
                            return candidate
                        }

                        if (tier > bestCandidateTier || (tier == bestCandidateTier && confidence > bestConfidence)) {
                            bestCandidateTier = tier
                            bestConfidence = confidence
                            bestCandidate = candidate
                        }
                    }
                }
            }

            return bestCandidate
        } catch (_: Exception) {
            return null
        }
    }

    private fun fetchLyricsBySongId(
        songId: Long,
        trackName: String,
        artistName: String,
        candDurationMs: Long? = null
    ): LyricsData? {
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

            // 1. Try the AMLL TTML DB mirror first (20,000+ community-curated
            //    studio TTML tracks). Its spans carry real per-syllable begin and
            //    end times, whereas NetEase's own YRC has to be reconstructed and
            //    its word ends are frequently absent. Genuine timing beats derived
            //    timing, so the better format is asked for first.
            try {
                val ttmlUrl = "https://raw.githubusercontent.com/amll-dev/amll-ttml-db/main/ncm-lyrics/$songId.ttml"
                val ttmlReq = Request.Builder().url(ttmlUrl).header("User-Agent", USER_AGENT).build()
                val ttmlResp = client.newCall(ttmlReq).execute()
                if (ttmlResp.isSuccessful) {
                    val ttmlContent = ttmlResp.body?.string() ?: ""
                    if (ttmlContent.isNotBlank()) {
                        val parsedTtml = com.auralis.music.data.parser.TtmlParser.parse(ttmlContent, LyricsProvider.NETEASE)
                        if (parsedTtml.lines.isNotEmpty() && parsedTtml.syncType == SyncType.RICHSYNC) {
                            return parsedTtml.copy(
                                trackName = trackName,
                                artistName = artistName,
                                durationMs = candDurationMs ?: parsedTtml.durationMs
                            )
                        }
                    }
                }
            } catch (_: Exception) {}

            // 2. Fall back to YRC (word-by-word / syllable-level RichSync)
            val rawYrc = json.optJSONObject("yrc")?.optString("lyric") ?: ""
            if (rawYrc.isNotBlank() && !rawYrc.contains("纯音乐")) {
                val parsedYrc = YrcParser.parse(rawYrc, LyricsProvider.NETEASE, trackName, artistName)
                if (parsedYrc != null && parsedYrc.lines.isNotEmpty() && parsedYrc.syncType == SyncType.RICHSYNC) {
                    return parsedYrc.copy(
                        durationMs = candDurationMs ?: parsedYrc.durationMs,
                        trackName = trackName,
                        artistName = artistName
                    )
                }
            }

            // 3. Try Standard LRC
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
                        artistName = artistName,
                        durationMs = candDurationMs
                    )
                }
                return null
            }

            val parsed = LrcParser.parse(rawLrc, LyricsProvider.NETEASE)
            return parsed.copy(
                trackName = trackName,
                artistName = artistName,
                durationMs = candDurationMs ?: parsed.durationMs
            )
        } catch (_: Exception) {
            return null
        }
    }
}

