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

    /** One YouTube client to try with the user's sign-in (versions and agents from yt-dlp 2026.07). */
    private class SignedInClient(
        val name: String,
        val id: Int,
        val version: String,
        val origin: String,
        val userAgent: String
    ) {
        /** Web clients' streams need PO tokens; the TV clients' don't. */
        val needsPoToken: Boolean get() = !name.startsWith("TVHTML5")
    }

    // In order. On device (2026-09-27) the TV clients answered "The page needs to be reloaded"
    // and WEB_REMIX handed over streams that googlevideo refused (403) without PO tokens.
    private val SIGNED_IN_CLIENTS = listOf(
        SignedInClient(
            "WEB_REMIX", 67, "1.20260114.03.00", "https://music.youtube.com",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
        ),
        SignedInClient(
            "MWEB", 2, "2.20260115.01.00", "https://m.youtube.com",
            "Mozilla/5.0 (iPad; CPU OS 16_7_10 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1,gzip(gfe)"
        ),
        SignedInClient(
            "WEB", 1, "2.20260114.08.00", "https://www.youtube.com",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/15.5 Safari/605.1.15,gzip(gfe)"
        ),
        SignedInClient("TVHTML5", 7, "5.20260114", "https://www.youtube.com", "Mozilla/5.0 (ChromiumStylePlatform) Cobalt/Version"),
        SignedInClient(
            "TVHTML5", 7, "7.20260114.12.00", "https://www.youtube.com",
            "Mozilla/5.0 (ChromiumStylePlatform) Cobalt/25.lts.30.1034943-gold (unlike Gecko), Unknown_TV_Unknown_0/Unknown (Unknown, Unknown)"
        )
    )

    /**
     * Age-restricted videos answer "Sign in to confirm your age" to every signed-out client. With
     * the user's own YouTube sign-in ([YouTubeSession]) YouTube serves them, and a web client's
     * stream downloads once it carries proof-of-origin tokens
     * ([com.auralis.music.data.network.potoken.PoTokenGenerator]): the player token in the request,
     * the streaming token as `pot=` on the URL. The streaming token is bound to the account's Data
     * Sync ID; YouTube sometimes wants it bound to the video instead, which
     * [bindStreamingTokenToVideo] tries (used on the retry after a 403).
     * Null when signed out or when no client returns a stream.
     */
    suspend fun resolveSignedInStream(videoId: String, bindStreamingTokenToVideo: Boolean = false): String? =
        withContext(Dispatchers.IO) {
            if (!YouTubeSession.isSignedIn) return@withContext null
            AudioStreamResolver.ensureNewPipeInitialized()
            val sts = runCatching {
                org.schabi.newpipe.extractor.services.youtube.YoutubeJavaScriptPlayerManager.getSignatureTimestamp(videoId)
            }.getOrNull() ?: PlayerJsCache.getSignatureTimestamp() ?: 20689

            val dataSyncId = YouTubeSession.dataSyncId()
            val session = dataSyncId.ifBlank { YouTubeSession.visitorData }
            val tokens = if (session.isNotBlank()) {
                com.auralis.music.data.network.potoken.PoTokenGenerator.getTokens(videoId, session)
            } else null
            Log.i(
                TAG,
                "Signed-in resolve $videoId: poTokens=${tokens != null}, " +
                    "boundTo=${if (dataSyncId.isNotBlank()) "account" else "visitor"}, bindToVideo=$bindStreamingTokenToVideo"
            )

            for (client in SIGNED_IN_CLIENTS) {
                // Without tokens a web client's stream is refused anyway; go to the TV clients.
                if (client.needsPoToken && tokens == null) continue
                val playerPot = if (client.needsPoToken) tokens?.playerRequestPoToken else null
                val url = requestSignedInStream(videoId, client, sts, playerPot) ?: continue
                val finalUrl = if (client.needsPoToken && tokens != null) {
                    val pot = if (bindStreamingTokenToVideo) tokens.playerRequestPoToken else tokens.streamingDataPoToken
                    url + "&pot=" + java.net.URLEncoder.encode(pot, "UTF-8")
                } else url
                Log.i(TAG, "Signed-in stream for $videoId via ${client.name} ${client.version}")
                return@withContext finalUrl
            }
            null
        }

    /** The best audio stream URL (signature and n parameter decoded) from one signed-in client. */
    private fun requestSignedInStream(videoId: String, client: SignedInClient, sts: Int, playerPoToken: String?): String? {
        try {
            val payload = JSONObject().apply {
                put("videoId", videoId)
                put("contentCheckOk", true)
                put("racyCheckOk", true)
                put(
                    "playbackContext",
                    JSONObject().put(
                        "contentPlaybackContext",
                        JSONObject().put("signatureTimestamp", sts).put("html5Preference", "HTML5_PREF_WANTS")
                    )
                )
                put("context", JSONObject().put("client", JSONObject().apply {
                    put("clientName", client.name)
                    put("clientVersion", client.version)
                    put("hl", "en")
                    put("gl", "US")
                    if (YouTubeSession.visitorData.isNotBlank()) put("visitorData", YouTubeSession.visitorData)
                }))
                if (!playerPoToken.isNullOrBlank()) {
                    put("serviceIntegrityDimensions", JSONObject().put("poToken", playerPoToken))
                }
            }
            val request = Request.Builder()
                .url(client.origin + "/youtubei/v1/player?prettyPrint=false")
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .header("User-Agent", client.userAgent)
                .header("Origin", client.origin)
                .header("Referer", client.origin + "/")
                .header("X-YouTube-Client-Name", client.id.toString())
                .header("X-YouTube-Client-Version", client.version)
                .apply { YouTubeSession.authHeaders(client.origin).forEach { (k, v) -> header(k, v) } }
                .build()

            val json = execute(request) ?: return null
            val streamingData = json.optJSONObject("streamingData")
            if (streamingData == null) {
                val playability = json.optJSONObject("playabilityStatus")
                Log.w(TAG, "Signed-in ${client.name} ${client.version}: ${playability?.optString("status")} ${playability?.optString("reason")}")
                return null
            }
            val formats = streamingData.optJSONArray("adaptiveFormats") ?: return null
            var best: JSONObject? = null
            for (i in 0 until formats.length()) {
                val fmt = formats.getJSONObject(i)
                if (!fmt.optString("mimeType").startsWith("audio/")) continue
                if (best == null || fmt.optInt("bitrate") > best.optInt("bitrate")) best = fmt
            }
            if (best == null) {
                Log.w(TAG, "Signed-in ${client.name}: no audio formats (SABR-only=${streamingData.has("serverAbrStreamingUrl")})")
                return null
            }

            val direct = best.optString("url")
            val url = if (direct.isNotBlank()) direct else {
                val cipher = best.optString("signatureCipher", best.optString("cipher"))
                if (cipher.isBlank()) {
                    Log.w(TAG, "Signed-in ${client.name}: audio format has neither url nor signatureCipher")
                    return null
                }
                val params = cipher.split("&").associate {
                    val parts = it.split("=", limit = 2)
                    parts[0] to (if (parts.size > 1) URLDecoder.decode(parts[1], "UTF-8") else "")
                }
                val rawUrl = params["url"] ?: return null
                val sig = params["s"]
                if (sig.isNullOrBlank()) rawUrl else {
                    val decoded = org.schabi.newpipe.extractor.services.youtube.YoutubeJavaScriptPlayerManager
                        .deobfuscateSignature(videoId, sig)
                    rawUrl + "&" + (params["sp"] ?: "sig") + "=" + java.net.URLEncoder.encode(decoded, "UTF-8")
                }
            }
            return org.schabi.newpipe.extractor.services.youtube.YoutubeJavaScriptPlayerManager
                .getUrlWithThrottlingParameterDeobfuscated(videoId, url)
        } catch (e: Exception) {
            Log.w(TAG, "Signed-in ${client.name} ${client.version} failed: ${e.javaClass.simpleName} ${e.message}")
            return null
        }
    }

    private fun execute(request: Request): JSONObject? =
        client.newCall(request).execute().use { res ->
            if (!res.isSuccessful) {
                Log.w(TAG, "Signed-in player request got HTTP ${res.code}")
                return null
            }
            JSONObject(res.body?.string() ?: return null)
        }

    private suspend fun requestPlayerStream(
        videoId: String,
        sts: Int,
        clientName: String,
        clientVersion: String,
        origin: String,
        userAgent: String = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36",
        extraHeaders: Map<String, String> = emptyMap(),
        visitorData: String = "",
        apiHost: String = "https://www.youtube.com"
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
                .url("$apiHost/youtubei/v1/player?prettyPrint=false")
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .header("User-Agent", userAgent)
                .header("Origin", origin)
                .header("Referer", "$origin/")
                .apply { extraHeaders.forEach { (name, value) -> header(name, value) } }
                .build()

            val res = client.newCall(req).execute()
            if (!res.isSuccessful) {
                if (extraHeaders.isNotEmpty()) Log.w(TAG, "Signed-in player request for $videoId ($clientName) got HTTP ${res.code}")
                return null
            }

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
