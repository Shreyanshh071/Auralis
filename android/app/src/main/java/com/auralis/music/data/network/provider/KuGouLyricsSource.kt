package com.auralis.music.data.network.provider

import android.util.Base64
import com.auralis.music.data.network.NetworkClientProvider
import com.auralis.music.data.network.TitleCleaner
import com.auralis.music.data.parser.KrcParser
import com.auralis.music.data.parser.LrcParser
import com.auralis.music.data.parser.LyricsMatcher
import com.auralis.music.domain.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.util.zip.Inflater

class KuGouLyricsSource(
    private val client: OkHttpClient = NetworkClientProvider.lyricsHttpClient
) : LyricsSource {

    override val provider: LyricsProvider = LyricsProvider.KUGOU
    override val supportedSyncTypes: Set<SyncType> = setOf(SyncType.RICHSYNC, SyncType.LINE_SYNC)

    companion object {
        private const val KUGOU_SEARCH_URL = "http://mobileservice.kugou.com/api/v3/lyric/search"
        private const val KUGOU_KRCS_URL = "http://krcs.kugou.com/search"
        private const val KUGOU_DOWNLOAD_URL = "http://lyrics.kugou.com/download"
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36"
        private val KRC_KEY = byteArrayOf(
            0x40.toByte(), 0x47.toByte(), 0x61.toByte(), 0x77.toByte(),
            0x5e.toByte(), 0x32.toByte(), 0x74.toByte(), 0x47.toByte(),
            0x51.toByte(), 0x36.toByte(), 0x31.toByte(), 0x2d.toByte(),
            0xce.toByte(), 0xd2.toByte(), 0x6e.toByte(), 0x69.toByte()
        )
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
                                // 1. Attempt word-level synced KRC
                                val krcCandidate = downloadKrc(candId, accessKey, candSongTitle, candArtistName, candDur, confidence)
                                if (krcCandidate != null) return krcCandidate

                                // 2. Fall back to line-synced LRC
                                val lrcCandidate = downloadLrc(candId, accessKey, candSongTitle, candArtistName, candDur, confidence)
                                if (lrcCandidate != null) return lrcCandidate
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

    private fun downloadKrc(
        candId: String,
        accessKey: String,
        songTitle: String,
        artistName: String,
        durationSec: Long,
        confidence: Int
    ): LyricsCandidate? {
        return try {
            val dlUrl = "$KUGOU_DOWNLOAD_URL?ver=1&client=mobi&id=$candId&accesskey=$accessKey&fmt=krc&charset=utf8"
            val dlReq = Request.Builder().url(dlUrl).build()
            val dlResp = client.newCall(dlReq).execute()
            if (!dlResp.isSuccessful) return null

            val dlJson = JSONObject(dlResp.body?.string() ?: "")
            val b64Content = dlJson.optString("content")
            if (b64Content.isBlank()) return null

            val rawBytes = Base64.decode(b64Content, Base64.DEFAULT)
            val decrypted = decryptKrc(rawBytes) ?: return null
            val parsed = KrcParser.parse(decrypted, LyricsProvider.KUGOU, songTitle, artistName) ?: return null

            if (parsed.lines.isNotEmpty()) {
                LyricsCandidate(
                    lyricsData = parsed.copy(
                        trackName = songTitle,
                        artistName = artistName,
                        durationMs = if (durationSec > 0L) durationSec * 1000L else null
                    ),
                    confidence = confidence,
                    syncType = parsed.syncType,
                    provider = LyricsProvider.KUGOU
                )
            } else null
        } catch (_: Exception) {
            null
        }
    }

    private fun downloadLrc(
        candId: String,
        accessKey: String,
        songTitle: String,
        artistName: String,
        durationSec: Long,
        confidence: Int
    ): LyricsCandidate? {
        return try {
            val dlUrl = "$KUGOU_DOWNLOAD_URL?ver=1&client=pc&id=$candId&accesskey=$accessKey&fmt=lrc&charset=utf8"
            val dlReq = Request.Builder().url(dlUrl).build()
            val dlResp = client.newCall(dlReq).execute()
            if (!dlResp.isSuccessful) return null

            val dlJson = JSONObject(dlResp.body?.string() ?: "")
            val b64Content = dlJson.optString("content")
            if (b64Content.isBlank()) return null

            val lrcText = String(Base64.decode(b64Content, Base64.DEFAULT), Charsets.UTF_8)
            val parsed = LrcParser.parse(lrcText, LyricsProvider.KUGOU)
            if (parsed.syncType != SyncType.PLAIN && parsed.lines.isNotEmpty()) {
                LyricsCandidate(
                    lyricsData = parsed.copy(
                        trackName = songTitle,
                        artistName = artistName,
                        durationMs = if (durationSec > 0L) durationSec * 1000L else null
                    ),
                    confidence = confidence,
                    syncType = SyncType.LINE_SYNC,
                    provider = LyricsProvider.KUGOU
                )
            } else null
        } catch (_: Exception) {
            null
        }
    }

    private fun decryptKrc(rawBytes: ByteArray): String? {
        if (rawBytes.size < 4) return null
        if (rawBytes[0] != 0x6b.toByte() || rawBytes[1] != 0x72.toByte() ||
            rawBytes[2] != 0x63.toByte() || rawBytes[3] != 0x31.toByte()
        ) {
            return null
        }
        val payload = rawBytes.copyOfRange(4, rawBytes.size)
        for (i in payload.indices) {
            payload[i] = (payload[i].toInt() xor KRC_KEY[i % 16].toInt()).toByte()
        }
        return try {
            val inflater = Inflater()
            inflater.setInput(payload)
            val buffer = ByteArray(4096)
            val outputStream = ByteArrayOutputStream()
            while (!inflater.finished()) {
                val count = inflater.inflate(buffer)
                if (count == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                outputStream.write(buffer, 0, count)
            }
            inflater.end()
            outputStream.toString("UTF-8")
        } catch (_: Exception) {
            null
        }
    }
}
