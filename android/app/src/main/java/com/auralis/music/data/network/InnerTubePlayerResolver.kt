package com.auralis.music.data.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

/**
 * Direct InnerTube /player Resolver.
 * Performs direct JSON POST requests to YouTube Music / YouTube /player endpoints,
 * selects the highest bitrate audio stream (Opus 160kbps / AAC 128kbps), and deciphers
 * signatures and n-parameters using PlayerJsCache.
 */
object InnerTubePlayerResolver {

    private const val TAG = "InnerTubeResolver"
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(3000, TimeUnit.MILLISECONDS)
        .readTimeout(3000, TimeUnit.MILLISECONDS)
        .followRedirects(true)
        .build()

    suspend fun resolveStream(videoId: String): String? = withContext(Dispatchers.IO) {
        val sts = PlayerJsCache.getSignatureTimestamp() ?: 20689

        // 1. Try WEB_REMIX (YouTube Music Web)
        val webRemixStream = requestPlayerStream(videoId, sts, "WEB_REMIX", "1.20241201.01.00", "https://music.youtube.com")
        if (!webRemixStream.isNullOrBlank()) {
            return@withContext webRemixStream
        }

        // 2. Try ANDROID_VR (Direct unthrottled audio streams)
        val vrStream = requestPlayerStream(videoId, sts, "ANDROID_VR", "1.59.19", "https://www.youtube.com")
        if (!vrStream.isNullOrBlank()) {
            return@withContext vrStream
        }

        null
    }

    // yt-dlp's "tv_downgraded" client, its default for signed-in requests (yt-dlp 2026.07): a TV
    // client that accepts account cookies and needs no proof-of-origin token.
    private const val TV_CLIENT_VERSION = "5.20260114"
    private const val TV_USER_AGENT = "Mozilla/5.0 (ChromiumStylePlatform) Cobalt/Version"

    /**
     * Age-restricted videos answer "Sign in to confirm your age" to every signed-out client. With
     * the user's own YouTube sign-in ([YouTubeSession]) the TV client returns their streams.
     * Null when signed out, or when the account itself can't watch the video.
     */
    suspend fun resolveSignedInStream(videoId: String): String? = withContext(Dispatchers.IO) {
        if (!YouTubeSession.isSignedIn) return@withContext null
        val origin = "https://www.youtube.com"
        val sts = PlayerJsCache.getSignatureTimestamp() ?: 20689
        requestPlayerStream(
            videoId = videoId,
            sts = sts,
            clientName = "TVHTML5",
            clientVersion = TV_CLIENT_VERSION,
            origin = origin,
            userAgent = TV_USER_AGENT,
            extraHeaders = YouTubeSession.authHeaders(origin),
            visitorData = YouTubeSession.visitorData
        )
    }

    private suspend fun requestPlayerStream(
        videoId: String,
        sts: Int,
        clientName: String,
        clientVersion: String,
        origin: String,
        userAgent: String = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36",
        extraHeaders: Map<String, String> = emptyMap(),
        visitorData: String = ""
    ): String? {
        try {
            val payload = JSONObject().apply {
                put("videoId", videoId)
                put("playbackContext", JSONObject().apply {
                    put("contentPlaybackContext", JSONObject().apply {
                        put("signatureTimestamp", sts)
                        put("html5Preference", "HTML5_PREF_WANTS")
                    })
                })
                put("contentCheckOk", true)
                put("racyCheckOk", true)
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", clientName)
                        put("clientVersion", clientVersion)
                        put("hl", "en")
                        put("gl", "US")
                        if (visitorData.isNotBlank()) put("visitorData", visitorData)
                    })
                })
            }

            val req = Request.Builder()
                .url("https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .header("User-Agent", userAgent)
                .header("Origin", origin)
                .header("Referer", "$origin/")
                .apply { extraHeaders.forEach { (name, value) -> header(name, value) } }
                .build()

            val res = client.newCall(req).execute()
            if (!res.isSuccessful) return null

            val body = res.body?.string() ?: return null
            val json = JSONObject(body)
            val streamingData = json.optJSONObject("streamingData")
            if (streamingData == null) {
                val playability = json.optJSONObject("playabilityStatus")
                Log.w(TAG, "No streams for $videoId ($clientName): ${playability?.optString("status")} ${playability?.optString("reason")}")
                return null
            }
            val formats = streamingData.optJSONArray("adaptiveFormats") ?: return null

            var bestUrl: String? = null
            var bestBitrate = 0

            for (i in 0 until formats.length()) {
                val fmt = formats.getJSONObject(i)
                val mime = fmt.optString("mimeType", "")
                if (mime.startsWith("audio/")) {
                    val bitrate = fmt.optInt("bitrate", 0)
                    val directUrl = fmt.optString("url")
                    val cipher = fmt.optString("signatureCipher", fmt.optString("cipher"))

                    if (directUrl.isNotBlank() && bitrate > bestBitrate) {
                        bestBitrate = bitrate
                        bestUrl = directUrl
                    } else if (cipher.isNotBlank() && bitrate > bestBitrate) {
                        val parsedUrl = parseCipher(cipher)
                        if (!parsedUrl.isNullOrBlank()) {
                            bestBitrate = bitrate
                            bestUrl = parsedUrl
                        }
                    }
                }
            }

            if (bestUrl.isNullOrBlank()) {
                Log.w(TAG, "No usable audio URL for $videoId ($clientName): ${formats.length()} formats, SABR-only=${streamingData.has("serverAbrStreamingUrl")}")
                return null
            }

            // Transform n parameter if present in query string
            return applyNParamTransformation(bestUrl)
        } catch (e: Exception) {
            Log.w(TAG, "Direct player request failed for $videoId ($clientName): ${e.message}")
            return null
        }
    }

    private suspend fun parseCipher(cipher: String): String? {
        return try {
            val params = cipher.split("&").associate {
                val parts = it.split("=", limit = 2)
                parts[0] to (if (parts.size > 1) URLDecoder.decode(parts[1], "UTF-8") else "")
            }
            val rawUrl = params["url"] ?: return null
            val sig = params["s"]
            val sp = params["sp"] ?: "sig"
            if (!sig.isNullOrBlank()) {
                val deciphered = PlayerJsCache.decipherSignature(sig)
                "$rawUrl&$sp=$deciphered"
            } else {
                rawUrl
            }
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun applyNParamTransformation(url: String): String {
        return try {
            val uri = java.net.URI(url)
            val query = uri.rawQuery ?: return url
            val nParamMatch = Regex("""(?:^|&)n=([^&]+)""").find(query)
            if (nParamMatch != null) {
                val rawN = nParamMatch.groupValues[1]
                val transformedN = PlayerJsCache.transformNParam(rawN)
                if (transformedN.isNotBlank() && transformedN != rawN) {
                    return url.replace("n=$rawN", "n=$transformedN")
                }
            }
            url
        } catch (_: Exception) {
            url
        }
    }
}
