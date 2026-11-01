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

    private val client = OkHttpClient.Builder().proxyAuthenticator(com.auralis.music.data.network.ContentProxy.authenticator)
        .connectTimeout(3000, TimeUnit.MILLISECONDS)
        .readTimeout(3000, TimeUnit.MILLISECONDS)
        .followRedirects(true)
        .build()

    suspend fun resolveStream(videoId: String): String? = withContext(Dispatchers.IO) {
        val sts = PlayerJsCache.getSignatureTimestamp() ?: 20689
        unavailableReports.remove(videoId)

        // 0. VISIONOS is the one signed-out client that serves a whole file. With a visitor session
        // it opened every song that got "confirm you're not a bot" elsewhere, read to the last byte.
        val visitor = anonymousVisitorData()
        if (!visitor.isNullOrBlank()) {
            val visionStream = requestPlayerStream(
                videoId, sts, "VISIONOS", "0.1", "https://www.youtube.com",
                userAgent = VISIONOS_USER_AGENT,
                extraHeaders = mapOf(
                    "X-YouTube-Client-Name" to "101",
                    "X-YouTube-Client-Version" to "0.1",
                    "X-Goog-Visitor-Id" to visitor
                ),
                visitorData = visitor,
                clientFields = VISIONOS_DEVICE,
                transformN = false
            )
            if (!visionStream.isNullOrBlank()) return@withContext visionStream
            anonymousVisitor = null
        }

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

    private const val VISIONOS_USER_AGENT =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Safari/605.1.15"
    private val VISIONOS_DEVICE = mapOf(
        "osName" to "visionOS",
        "osVersion" to "1.3.21O771",
        "deviceMake" to "Apple",
        "deviceModel" to "RealityDevice14,1"
    )

    @Volatile private var anonymousVisitor: String? = null

    private val UNAVAILABLE_REASONS = setOf("this video is not available", "video unavailable")
    private val unavailableReports = java.util.concurrent.ConcurrentHashMap<String, MutableSet<String>>()

    /** Two clients said the video itself isn't there, so it isn't a refusal another try could pass. */
    fun isUnavailable(videoId: String): Boolean = (unavailableReports[videoId]?.size ?: 0) >= 2

    /** A signed-out visitor session: without one, YouTube refuses every client as a bot. */
    private fun anonymousVisitorData(): String? {
        anonymousVisitor?.let { return it }
        return try {
            val request = Request.Builder()
                .url("https://www.youtube.com/sw.js_data")
                .header("User-Agent", VISIONOS_USER_AGENT)
                .build()
            val body = client.newCall(request).execute().use { if (it.isSuccessful) it.body?.string() else null }
            body?.let { parseVisitorData(it) }?.also { anonymousVisitor = it }
        } catch (e: Exception) {
            Log.w(TAG, "Visitor session request failed: ${e.message}")
            null
        }
    }

    internal fun parseVisitorData(body: String): String? = runCatching {
        org.json.JSONArray(body.removePrefix(")]}'").trim())
            .getJSONArray(0).getJSONArray(2).getJSONArray(0).getJSONArray(0).getString(13)
    }.getOrNull()?.takeIf { it.isNotBlank() }

    /** A YouTube web client that accepts the user's sign-in (versions from zemer-app, 2026-09). */
    private class SignedInClient(
        val name: String,
        val id: Int,
        val version: String,
        val origin: String
    )

    private const val WEB_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

    // zemer-app validates clients against the live CDN (whole-song HTTP 206). The two that accept
    // a login and stream the whole song with PO tokens; the TV clients (SABR-only / 403 wall),
    // plain WEB and MWEB are dead for this, and VISIONOS doesn't take a login.
    private val SIGNED_IN_CLIENTS = listOf(
        SignedInClient("WEB_REMIX", 67, "1.20260213.01.00", "https://music.youtube.com"),
        SignedInClient("WEB_CREATOR", 62, "1.20260213.00.00", "https://www.youtube.com")
    )

    private val poTokenGenerator by lazy { com.zemer.cipher.potoken.PoTokenGenerator() }

    /**
     * Age-restricted videos answer "Sign in to confirm your age" to every signed-out client. With
     * the user's own YouTube sign-in ([YouTubeSession]) the web clients serve them; the stream
     * downloads once its signature and `n` parameter are deciphered with YouTube's own player JS
     * and it carries PO tokens. Both come from zemer-cipher (vendored module, self-updating player
     * configs): the session-bound token goes in the player request, the video-bound one on the URL
     * as `pot=`, and the session is the visitor ID even when signed in (as zemer-app does).
     * Null when signed out or when no client returns a usable stream.
     */
    suspend fun resolveSignedInStream(videoId: String): String? = withContext(Dispatchers.IO) {
        if (!YouTubeSession.isSignedIn) return@withContext null
        val visitorData = YouTubeSession.visitorData
        val sts = com.zemer.cipher.CipherDeobfuscator.signatureTimestamp()
            ?: PlayerJsCache.getSignatureTimestamp() ?: 20689
        val tokens = if (visitorData.isNotBlank()) {
            runCatching { poTokenGenerator.getWebClientPoToken(videoId, visitorData) }.getOrNull()
        } else null
        Log.i(TAG, "Signed-in resolve $videoId: sts=$sts, poTokens=${tokens != null}")
        if (tokens == null) return@withContext null

        for (client in SIGNED_IN_CLIENTS) {
            val url = requestSignedInStream(videoId, client, sts, tokens.playerRequestPoToken) ?: continue
            val finalUrl = url + "&pot=" + java.net.URLEncoder.encode(tokens.streamingDataPoToken, "UTF-8")
            Log.i(TAG, "Signed-in stream for $videoId via ${client.name} ${client.version}")
            return@withContext finalUrl
        }
        null
    }

    /**
     * After the CDN refused a signed-in stream: a wrong-but-non-throwing signature from a stale
     * player config only shows up this way, so let zemer-cipher refetch its configs.
     */
    suspend fun onSignedInStreamRejected() {
        runCatching { com.zemer.cipher.CipherDeobfuscator.onStreamRejected() }
    }

    /** The best audio stream URL (signature and n parameter deciphered) from one signed-in client. */
    private suspend fun requestSignedInStream(videoId: String, client: SignedInClient, sts: Int, playerPoToken: String): String? {
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
                    put("gl", ContentLocale.gl())
                    if (YouTubeSession.visitorData.isNotBlank()) put("visitorData", YouTubeSession.visitorData)
                }))
                put("serviceIntegrityDimensions", JSONObject().put("poToken", playerPoToken))
            }
            val request = Request.Builder()
                .url(client.origin + "/youtubei/v1/player?prettyPrint=false")
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .header("User-Agent", WEB_USER_AGENT)
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
                Log.w(TAG, "Signed-in ${client.name}: ${playability?.optString("status")} ${playability?.optString("reason")}")
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
                com.zemer.cipher.CipherDeobfuscator.deobfuscateStreamUrl(cipher, videoId) ?: run {
                    Log.w(TAG, "Signed-in ${client.name}: signature deciphering failed")
                    return null
                }
            }
            return com.zemer.cipher.CipherDeobfuscator.transformNParamInUrl(url)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
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
        apiHost: String = "https://www.youtube.com",
        clientFields: Map<String, String> = emptyMap(),
        transformN: Boolean = true
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
                        put("gl", ContentLocale.gl())
                        if (visitorData.isNotBlank()) put("visitorData", visitorData)
                        clientFields.forEach { (name, value) -> put(name, value) }
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
                if (extraHeaders.isNotEmpty()) Log.w(TAG, "Player request for $videoId ($clientName) got HTTP ${res.code}")
                return null
            }

            val body = res.body?.string() ?: return null
            val json = JSONObject(body)
            val streamingData = json.optJSONObject("streamingData")
            if (streamingData == null) {
                val playability = json.optJSONObject("playabilityStatus")
                Log.w(TAG, "No streams for $videoId ($clientName): ${playability?.optString("status")} ${playability?.optString("reason")}")
                if (playability?.optString("reason").orEmpty().trim().lowercase() in UNAVAILABLE_REASONS) {
                    unavailableReports.getOrPut(videoId) { java.util.concurrent.ConcurrentHashMap.newKeySet() }.add(clientName)
                }
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
            return if (transformN) applyNParamTransformation(bestUrl) else bestUrl
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
