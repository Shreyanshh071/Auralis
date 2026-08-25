package com.auralis.music.data.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * High-Speed Direct Audio Stream Resolver.
 * Resolves direct audio streams with ultra-fast failover for pure native background playback in ExoPlayer.
 */
object AudioStreamResolver {

    private const val TAG = "AuralisPlayback"
    private val client = OkHttpClient.Builder()
        .connectTimeout(3000, TimeUnit.MILLISECONDS)
        .readTimeout(3000, TimeUnit.MILLISECONDS)
        .followRedirects(true)
        .build()

    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    // Host blacklisting with timestamp (clears after 5 minutes)
    private val blacklistedHosts = ConcurrentHashMap<String, Long>()
    private const val BLACKLIST_DURATION_MS = 5 * 60 * 1000L

    fun blacklistHost(host: String) {
        blacklistedHosts[host] = System.currentTimeMillis()
        try {
            Log.w(TAG, "[Resolver Blacklist] Host blacklisted for 5 min: $host")
        } catch (_: Throwable) {}
    }

    private fun isHostBlacklisted(url: String): Boolean {
        return try {
            val uri = java.net.URI(url)
            val host = uri.host ?: return false
            val time = blacklistedHosts[host] ?: return false
            if (System.currentTimeMillis() - time > BLACKLIST_DURATION_MS) {
                blacklistedHosts.remove(host)
                false
            } else {
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    suspend fun resolveAudioStream(videoId: String, title: String, artist: String): String? = withContext(Dispatchers.IO) {
        // 1. First priority: Direct YouTube InnerTube iOS stream (pure native, exact YouTube audio)
        try {
            val ytStream = resolveYouTubePlayerStream(videoId)
            if (!ytStream.isNullOrBlank()) {
                try {
                    Log.d(TAG, "[Resolver] Resolved direct YouTube audio stream for $videoId")
                } catch (_: Throwable) {}
                return@withContext ytStream
            }
        } catch (_: Exception) {}

        // 2. Second priority: JioSaavn 320kbps lossless master stream
        try {
            val cleanTitle = title.replace(Regex("(?i)\\[.*?\\]|\\(.*?\\)|official.*|video.*"), "").trim()
            val cleanArtist = if (artist.lowercase() !in listOf("shreyanshh", "shreyansh", "unknown", "artist", "youtube music")) artist else ""
            val query = if (cleanArtist.isNotBlank()) "$cleanTitle $cleanArtist" else cleanTitle
            val encQuery = URLEncoder.encode(query, "UTF-8")
            val searchUrl = "https://www.jiosaavn.com/api.php?__call=autocomplete.get&_format=json&_marker=0&cc=in&includeMetaTags=1&query=$encQuery"

            val searchReq = Request.Builder()
                .url(searchUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .build()

            val searchRes = client.newCall(searchReq).execute()
            if (searchRes.isSuccessful) {
                val body = searchRes.body?.string() ?: ""
                val json = JSONObject(body)
                val songs = json.optJSONObject("songs")?.optJSONArray("data")
                if (songs != null && songs.length() > 0) {
                    val firstSong = songs.getJSONObject(0)
                    val pid = firstSong.optString("id")
                    if (pid.isNotBlank()) {
                        val detailUrl = "https://www.jiosaavn.com/api.php?__call=song.getDetails&cc=in&_marker=0%3F_marker%3D0&_format=json&pids=$pid"
                        val detailReq = Request.Builder()
                            .url(detailUrl)
                            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                            .build()
                        val detailRes = client.newCall(detailReq).execute()
                        if (detailRes.isSuccessful) {
                            val detailBody = detailRes.body?.string() ?: ""
                            val detailJson = JSONObject(detailBody)
                            val songObj = detailJson.optJSONObject(pid)
                            val encUrl = songObj?.optString("encrypted_media_url")

                            if (!encUrl.isNullOrBlank()) {
                                val decryptedUrl = decryptSaavnMediaUrl(encUrl)
                                if (!decryptedUrl.isNullOrBlank() && !isHostBlacklisted(decryptedUrl)) {
                                    val candidate = decryptedUrl.replace("_96.mp4", "_320.mp4").replace("_160.mp4", "_320.mp4")
                                    return@withContext candidate
                                }
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        null
    }

    private fun resolveYouTubePlayerStream(videoId: String): String? {
        try {
            val iosPayload = JSONObject().apply {
                put("videoId", videoId)
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", "IOS")
                        put("clientVersion", "19.29.1")
                        put("deviceModel", "iPhone14,3")
                        put("hl", "en")
                        put("gl", "US")
                    })
                })
            }

            val req = Request.Builder()
                .url("https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
                .post(iosPayload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .header("User-Agent", "com.google.ios.youtube/19.29.1 (iPhone14,3; U; CPU iOS 17_5_1 like Mac OS X; en_US)")
                .header("Origin", "https://www.youtube.com")
                .build()

            val res = client.newCall(req).execute()
            if (res.isSuccessful) {
                val body = res.body?.string() ?: ""
                val json = JSONObject(body)
                val streamingData = json.optJSONObject("streamingData")
                val adaptiveFormats = streamingData?.optJSONArray("adaptiveFormats")
                if (adaptiveFormats != null) {
                    var bestAudioUrl: String? = null
                    var bestBitrate = 0
                    for (i in 0 until adaptiveFormats.length()) {
                        val fmt = adaptiveFormats.getJSONObject(i)
                        val mime = fmt.optString("mimeType", "")
                        if (mime.startsWith("audio/")) {
                            val directUrl = fmt.optString("url")
                            val bitrate = fmt.optInt("bitrate", 0)
                            if (directUrl.isNotBlank() && bitrate > bestBitrate) {
                                bestBitrate = bitrate
                                bestAudioUrl = directUrl
                            }
                        }
                    }
                    if (!bestAudioUrl.isNullOrBlank()) {
                        return bestAudioUrl
                    }
                }
            }
        } catch (_: Exception) {}

        return null
    }

    internal fun decryptSaavnMediaUrl(encryptedUrl: String): String? {
        if (encryptedUrl.isBlank()) return null
        return try {
            val key = "38346564".toByteArray(Charsets.UTF_8)
            val secretKey = SecretKeySpec(key, "DES")
            val cipher = Cipher.getInstance("DES/ECB/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey)
            val decoded = try {
                java.util.Base64.getDecoder().decode(encryptedUrl.trim())
            } catch (_: Throwable) {
                android.util.Base64.decode(encryptedUrl.trim(), android.util.Base64.DEFAULT)
            }
            val decrypted = cipher.doFinal(decoded)
            val url = String(decrypted, Charsets.UTF_8)
            if (url.startsWith("http://") || url.startsWith("https://")) {
                url
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }
}
