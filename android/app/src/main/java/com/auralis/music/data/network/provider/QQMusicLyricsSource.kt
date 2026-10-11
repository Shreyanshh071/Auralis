package com.auralis.music.data.network.provider

import com.auralis.music.data.network.NetworkClientProvider
import com.auralis.music.data.network.TitleCleaner
import com.auralis.music.data.parser.LyricsMatcher
import com.auralis.music.data.parser.QrcDecrypter
import com.auralis.music.data.parser.QrcParser
import com.auralis.music.domain.model.LyricsProvider
import com.auralis.music.domain.model.SyncType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class QQMusicLyricsSource(
    private val client: OkHttpClient = NetworkClientProvider.lyricsHttpClient
) : LyricsSource {

    override val provider: LyricsProvider = LyricsProvider.QQMUSIC
    override val supportedSyncTypes: Set<SyncType> = setOf(SyncType.RICHSYNC, SyncType.LINE_SYNC)

    companion object {
        private const val QQ_GATEWAY_URL = "https://u.y.qq.com/cgi-bin/musicu.fcg"
        private const val REFERER_URL = "https://y.qq.com/"
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    override suspend fun search(query: LyricsSearchQuery): LyricsCandidate? = withContext(Dispatchers.IO) {
        val cleanTitle = TitleCleaner.cleanCoreSongTitle(query.title)
        val cleanArtist = TitleCleaner.cleanArtist(query.artist)
        val primaryArtist = cleanArtist
            .split(Regex("""[,&/|]|(?:\s+feat\.?\s+)|\s+ft\.?\s+|\s+and\s+|\s+with\s+""", RegexOption.IGNORE_CASE))
            .firstOrNull()?.trim() ?: cleanArtist

        if (primaryArtist.isNotBlank() && primaryArtist != cleanArtist) {
            searchQQMusic("$cleanTitle $primaryArtist", query)?.let { return@withContext it }
        }

        searchQQMusic("$cleanTitle $cleanArtist", query)
            ?: searchQQMusic(cleanTitle, query)
    }

    private fun searchQQMusic(searchTerm: String, query: LyricsSearchQuery): LyricsCandidate? {
        return try {
            val searchPayload = JSONObject().apply {
                put("comm", JSONObject().apply {
                    put("ct", "19")
                    put("cv", "1859")
                    put("uin", "0")
                })
                put("req", JSONObject().apply {
                    put("module", "music.search.SearchCgiService")
                    put("method", "DoSearchForQQMusicMobile")
                    put("param", JSONObject().apply {
                        put("query", searchTerm.trim())
                        put("page_num", 1)
                        put("num_per_page", 5)
                        put("search_type", 0)
                    })
                })
            }

            val searchReq = Request.Builder()
                .url(QQ_GATEWAY_URL)
                .header("Referer", REFERER_URL)
                .header("User-Agent", USER_AGENT)
                .post(searchPayload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()

            val searchResp = client.newCall(searchReq).execute()
            if (!searchResp.isSuccessful) return null

            val respStr = searchResp.body?.string() ?: return null
            val searchJson = JSONObject(respStr)
            val bodyObj = searchJson.optJSONObject("req")?.optJSONObject("data")?.optJSONObject("body") ?: return null

            val songList = bodyObj.optJSONArray("item_song")
                ?: bodyObj.optJSONObject("song")?.optJSONArray("list")
                ?: return null

            for (i in 0 until songList.length()) {
                val song = songList.optJSONObject(i) ?: continue
                val mid = song.optString("mid")
                if (mid.isBlank()) continue

                val rawTitle = song.optString("title").ifBlank { song.optString("name") }
                val candTitle = rawTitle.replace(Regex("</?em>"), "").trim()

                val singers = song.optJSONArray("singer")
                val candArtist = if (singers != null && singers.length() > 0) {
                    val sName = singers.optJSONObject(0)?.optString("name") ?: ""
                    sName.replace(Regex("</?em>"), "").trim()
                } else ""

                val candDurSec = song.optLong("interval", 0L)

                val confidence = LyricsMatcher.calculateConfidence(
                    queryTitle = query.title,
                    queryArtist = query.artist,
                    candidateTitle = candTitle,
                    candidateArtist = candArtist,
                    queryDurationSec = query.durationSec,
                    candidateDurationSec = candDurSec
                )

                if (confidence >= 50) {
                    val cand = fetchLyricForMid(mid, candTitle, candArtist, candDurSec, confidence)
                    if (cand != null) return cand
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun fetchLyricForMid(
        mid: String,
        title: String,
        artist: String,
        durSec: Long,
        confidence: Int
    ): LyricsCandidate? {
        return try {
            val lyricPayload = JSONObject().apply {
                put("comm", JSONObject().apply {
                    put("ct", "19")
                    put("cv", "1859")
                    put("uin", "0")
                })
                put("req", JSONObject().apply {
                    put("module", "music.musichallSong.PlayLyricInfo")
                    put("method", "GetPlayLyricInfo")
                    put("param", JSONObject().apply {
                        put("songMID", mid)
                        put("qrc", 1)
                    })
                })
            }

            val lyricReq = Request.Builder()
                .url(QQ_GATEWAY_URL)
                .header("Referer", REFERER_URL)
                .header("User-Agent", USER_AGENT)
                .post(lyricPayload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()

            val lyricResp = client.newCall(lyricReq).execute()
            if (!lyricResp.isSuccessful) return null

            val lyricRespStr = lyricResp.body?.string() ?: return null
            val lyricJson = JSONObject(lyricRespStr)
            val dataObj = lyricJson.optJSONObject("req")?.optJSONObject("data") ?: return null

            val lyricHex = dataObj.optString("lyric").ifBlank { dataObj.optString("qrc") }
            if (lyricHex.isBlank()) return null

            val decrypted = QrcDecrypter.decryptQrcHex(lyricHex) ?: return null
            val parsed = QrcParser.parse(decrypted, LyricsProvider.QQMUSIC, title, artist) ?: return null

            if (parsed.lines.isNotEmpty()) {
                LyricsCandidate(
                    lyricsData = parsed.copy(
                        trackName = title,
                        artistName = artist,
                        durationMs = if (durSec > 0L) durSec * 1000L else null
                    ),
                    confidence = confidence,
                    syncType = parsed.syncType,
                    provider = LyricsProvider.QQMUSIC
                )
            } else null
        } catch (_: Exception) {
            null
        }
    }
}
