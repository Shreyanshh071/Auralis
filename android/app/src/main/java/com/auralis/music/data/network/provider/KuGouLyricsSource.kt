package com.auralis.music.data.network.provider

import android.util.Base64
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

class KuGouLyricsSource(
    private val client: OkHttpClient = NetworkClientProvider.lyricsHttpClient
) : LyricsSource {

    override val provider: LyricsProvider = LyricsProvider.KUGOU
    override val supportedSyncTypes: Set<SyncType> = setOf(SyncType.LINE_SYNC)

    companion object {
        private const val KUGOU_SEARCH_URL = "http://mobileservice.kugou.com/api/v3/lyric/search"
        private const val KUGOU_KRCS_URL = "http://krcs.kugou.com/search"
        private const val KUGOU_DOWNLOAD_URL = "http://lyrics.kugou.com/download"
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36"
    }

    override suspend fun search(query: LyricsSearchQuery): LyricsCandidate? = withContext(Dispatchers.IO) {
        val cleanTitle = TitleCleaner.cleanCoreSongTitle(query.title)
        val cleanArtist = TitleCleaner.cleanArtist(query.artist)
        val primaryArtist = cleanArtist
            .split(Regex("""[,&/|]|(?:\s+feat\.?\s+)|\s+ft\.?\s+|\s+and\s+|\s+with\s+""", RegexOption.IGNORE_CASE))
            .firstOrNull()?.trim() ?: cleanArtist

        if (primaryArtist.isNotBlank() && primaryArtist != cleanArtist) {
            searchKuGou("$cleanTitle $primaryArtist", query)?.let { return@withContext it }
        }

        searchKuGou("$cleanTitle $cleanArtist", query)
            ?: searchKuGou(cleanTitle, query)
    }

    private fun searchKuGou(searchTerm: String, query: LyricsSearchQuery): LyricsCandidate? {
        try {
            val encQuery = URLEncoder.encode(searchTerm.trim(), "UTF-8")
            val searchUrl = "$KUGOU_SEARCH_URL?version=9108&highlight=1&keyword=$encQuery&plat=0&pagesize=5&area_code=1&page=1&with_res_tag=1"

            val req = Request.Builder()
                .url(searchUrl)
                .header("User-Agent", USER_AGENT)
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
                val candDur = item.optLong("duration", 0L)

                val hash = item.optString("hash").ifBlank {
                    item.optString("320hash").ifBlank { item.optString("sqhash") }
                }
                if (hash.isBlank()) continue

                val confidence = LyricsMatcher.calculateConfidence(
                    queryTitle = query.title,
                    queryArtist = query.artist,
                    candidateTitle = candSongTitle,
                    candidateArtist = candArtistName,
                    queryDurationSec = query.durationSec,
                    candidateDurationSec = candDur
                )

                if (confidence >= 50) {
                    val durMs = if (candDur > 0) candDur * 1000 else (query.durationSec?.let { it * 1000 } ?: 0L)
                    val krcUrl = "$KUGOU_KRCS_URL?ver=1&man=yes&client=mobi&keyword=&duration=$durMs&hash=$hash"

                    val krcReq = Request.Builder()
                        .url(krcUrl)
                        .header("User-Agent", USER_AGENT)
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
                                            return LyricsCandidate(
                                                lyricsData = parsed.copy(
                                                    trackName = candSongTitle,
                                                    artistName = candArtistName
                                                ),
                                                confidence = confidence,
                                                syncType = SyncType.LINE_SYNC,
                                                provider = LyricsProvider.KUGOU
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            return null
        } catch (_: Exception) {
            return null
        }
    }
}
