package com.auralis.music.data.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * High-Speed Direct Audio Stream Resolver.
 * Resolves direct streams with ultra-fast failover (1-second timeouts) to prevent playback lag.
 */
object AudioStreamResolver {

    private const val TAG = "AuralisPlayback"
    private val client = OkHttpClient.Builder()
        .connectTimeout(1500, TimeUnit.MILLISECONDS)
        .readTimeout(1500, TimeUnit.MILLISECONDS)
        .followRedirects(true)
        .build()

    // Host blacklisting with timestamp (clears after 5 minutes)
    private val blacklistedHosts = ConcurrentHashMap<String, Long>()
    private const val BLACKLIST_DURATION_MS = 5 * 60 * 1000L

    fun blacklistHost(host: String) {
        blacklistedHosts[host] = System.currentTimeMillis()
        Log.w(TAG, "[Resolver Blacklist] Host blacklisted for 5 min: $host")
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

    private fun validateStreamUrl(url: String): Boolean {
        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .header("Range", "bytes=0-1024")
                .build()
            val response = client.newCall(request).execute()
            val code = response.code
            val contentType = (response.header("Content-Type") ?: "").lowercase()
            val isSuccess = (code == 200 || code == 206) &&
                (contentType.contains("audio") || contentType.contains("video") || contentType.contains("octet-stream") || contentType.contains("mp4") || contentType.contains("mpeg"))

            if (!isSuccess) {
                val host = java.net.URI(url).host
                if (!host.isNullOrBlank()) blacklistHost(host)
            }
            response.close()
            isSuccess
        } catch (e: Exception) {
            false
        }
    }

    suspend fun resolveAudioStream(videoId: String, title: String, artist: String): String? = withContext(Dispatchers.IO) {
        // Fast-check JioSaavn DES decrypted 320kbps streams
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
                                    if (validateStreamUrl(candidate)) {
                                        Log.d(TAG, "[Resolver] Validated direct 320kbps stream: ${candidate.substringBefore("?")}")
                                        return@withContext candidate
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        // Immediate return if no direct high-bitrate stream exists, so YouTube engine plays instantly
        null
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
            } catch (_: Exception) {
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
