package com.auralis.music.desktop.playback

import com.auralis.music.data.network.ContentLocale
import com.auralis.music.data.network.YouTubeSession
import com.auralis.music.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Age-restricted videos answer "Sign in to confirm your age" to every signed-out client, so the
 * anonymous extraction ([DesktopStreamResolver]) can't open them. With the user's own YouTube
 * sign-in ([YouTubeSession]) the web clients serve them, as on the phone
 * (android InnerTubePlayerResolver.resolveSignedInStream): the stream plays once its signature and
 * `n` parameter are deciphered with YouTube's own player script and it carries PO tokens. Both come
 * from the vendored zemer-cipher, which on desktop runs those scripts in an unseen Chromium.
 *
 * The cookie is sent only to YouTube, and only for a video YouTube said needs a sign-in.
 */
object DesktopSignedInStream {
    private const val TAG = "SignedInStream"
    private val JSON = "application/json; charset=utf-8".toMediaType()
    private const val WEB_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

    /** A YouTube web client that accepts a login (the phone's list: whole-song streams with PO tokens). */
    internal class Client(val name: String, val id: Int, val version: String, val origin: String)

    internal val CLIENTS = listOf(
        Client("WEB_REMIX", 67, "1.20260213.01.00", "https://music.youtube.com"),
        Client("WEB_CREATOR", 62, "1.20260213.00.00", "https://www.youtube.com")
    )

    /**
     * The signed-in account a request speaks for, as YouTube Music's own page reports it: which
     * account in the cookie (authUser), and for a channel/brand account its page and account IDs.
     * Without these YouTube treats the request as signed out (logged_in=0).
     */
    data class Account(val cookie: String, val authUser: String, val pageId: String?, val dataSyncId: String?, val visitorData: String?)

    /** Supplies the account (set by the app from its YouTube Music client); the default uses the stored session as is. */
    @Volatile var account: suspend () -> Account? = {
        YouTubeSession.authHeaders("https://music.youtube.com")["Cookie"]?.let {
            Account(it, YouTubeSession.authHeaders("https://music.youtube.com")["X-Goog-AuthUser"] ?: "0", null, null, YouTubeSession.visitorData.ifBlank { null })
        }
    }

    private val http by lazy {
        com.auralis.music.data.network.NetworkClientProvider.okHttpClient.newBuilder()
            .connectTimeout(5, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).build()
    }
    private val poTokens by lazy { com.zemer.cipher.potoken.PoTokenGenerator() }
    /** One signed-in lookup at a time: the cipher and token tabs serve one call each. */
    private val lock = Mutex()

    /** Whether a signed-in lookup can run at all (signed in, cipher set up, a Chromium to run it). */
    val usable: Boolean
        get() = YouTubeSession.isSignedIn && com.zemer.cipher.ZemerCipher.isInitialized &&
            com.zemer.cipher.HeadlessChromium.available && com.zemer.cipher.ZemerCipher.poTokenApiKey.isNotBlank()

    /** A playable audio URL for [videoId] through the user's sign-in, with its format; null when none. */
    suspend fun resolve(videoId: String): Stream? = withContext(Dispatchers.IO) {
        if (!usable) return@withContext null
        com.zemer.cipher.ZemerCipher.onFirstUse()
        lock.withLock {
            val started = System.currentTimeMillis()
            val account = runCatching { account() }.onFailure { Log.w(TAG, "YouTube account unavailable: ${it.message?.take(160)}") }.getOrNull()
                ?: return@withLock null
            val visitor = account.visitorData ?: YouTubeSession.visitorData.ifBlank { null }
            if (visitor == null) {
                Log.w(TAG, "No visitor ID with the YouTube sign-in; can't mint PO tokens")
                return@withLock null
            }
            val sts = com.zemer.cipher.CipherDeobfuscator.signatureTimestamp()
            if (sts == null) {
                Log.w(TAG, "Couldn't load YouTube's player script")
                return@withLock null
            }
            val tokens = poTokens.getWebClientPoToken(videoId, visitor)
            if (tokens == null) {
                Log.w(TAG, "No PO tokens for $videoId")
                return@withLock null
            }
            for (client in CLIENTS) {
                val stream = request(videoId, client, sts, tokens.playerRequestPoToken, account, visitor) ?: continue
                val url = stream.url + "&pot=" + java.net.URLEncoder.encode(tokens.streamingDataPoToken, Charsets.UTF_8)
                Log.i(TAG, "Signed-in stream for $videoId via ${client.name} in ${System.currentTimeMillis() - started}ms")
                return@withLock stream.copy(url = url, client = client.name)
            }
            null
        }
    }

    /** After the CDN refused a signed-in stream: a stale player config only shows up this way. */
    suspend fun onRejected() {
        runCatching { com.zemer.cipher.CipherDeobfuscator.onStreamRejected() }
    }

    data class Stream(val url: String, val itag: Int?, val mimeType: String?, val client: String)

    private suspend fun request(videoId: String, client: Client, sts: Int, playerPoToken: String, account: Account, visitor: String): Stream? = try {
        val payload = playerPayload(videoId, client, sts, playerPoToken, visitor, account.dataSyncId)
        val request = Request.Builder()
            .url(client.origin + "/youtubei/v1/player?prettyPrint=false")
            .post(payload.toString().toRequestBody(JSON))
            .header("User-Agent", WEB_USER_AGENT)
            .header("Origin", client.origin)
            .header("Referer", client.origin + "/")
            .header("X-YouTube-Client-Name", client.id.toString())
            .header("X-YouTube-Client-Version", client.version)
            .apply { authHeaders(account, client.origin, visitor).forEach { (k, v) -> header(k, v) } }
            .build()
        val json = http.newCall(request).execute().use { res ->
            if (!res.isSuccessful) { Log.w(TAG, "${client.name} player request got HTTP ${res.code}"); null }
            else JSONObject(res.body?.string() ?: "{}")
        }
        json?.let { pickAudio(it, client) }?.let { best ->
            val direct = best.optString("url")
            val url = direct.ifBlank {
                val cipher = best.optString("signatureCipher", best.optString("cipher"))
                if (cipher.isBlank()) null
                else com.zemer.cipher.CipherDeobfuscator.deobfuscateStreamUrl(cipher, videoId)
                    ?: run { Log.w(TAG, "${client.name}: signature deciphering failed"); null }
            }
            url?.let {
                Stream(com.zemer.cipher.CipherDeobfuscator.transformNParamInUrl(it), best.optInt("itag").takeIf { i -> i > 0 },
                    best.optString("mimeType").substringBefore(';').ifBlank { null }, client.name)
            }
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "${client.name} failed: ${e.javaClass.simpleName} ${e.message?.take(160)}")
        null
    }

    /** The phone's signed-in /player request body. */
    internal fun playerPayload(videoId: String, client: Client, sts: Int, playerPoToken: String, visitorData: String,
        onBehalfOfUser: String? = null) = JSONObject().apply {
        put("videoId", videoId)
        put("contentCheckOk", true)
        put("racyCheckOk", true)
        put("playbackContext", JSONObject().put("contentPlaybackContext",
            JSONObject().put("signatureTimestamp", sts).put("html5Preference", "HTML5_PREF_WANTS")))
        put("context", JSONObject().put("client", JSONObject().apply {
            put("clientName", client.name)
            put("clientVersion", client.version)
            put("hl", "en")
            put("gl", ContentLocale.gl())
            if (visitorData.isNotBlank()) put("visitorData", visitorData)
        }).put("user", JSONObject().put("lockedSafetyMode", false).apply { onBehalfOfUser?.let { put("onBehalfOfUser", it) } }))
        put("serviceIntegrityDimensions", JSONObject().put("poToken", playerPoToken))
    }

    /** The cookie plus YouTube's SAPISIDHASH authorization for [origin], scoped to [account]. */
    internal fun authHeaders(account: Account, origin: String, visitor: String?): Map<String, String> {
        val cookies = YouTubeSession.parseCookies(account.cookie)
        val timestamp = (System.currentTimeMillis() / 1000L).toString()
        val authorization = listOf(
            "SAPISIDHASH" to (cookies["SAPISID"] ?: cookies["__Secure-3PAPISID"]),
            "SAPISID1PHASH" to cookies["__Secure-1PAPISID"],
            "SAPISID3PHASH" to cookies["__Secure-3PAPISID"]
        ).mapNotNull { (scheme, sid) ->
            sid?.takeIf { it.isNotBlank() }?.let {
                val digest = java.security.MessageDigest.getInstance("SHA-1").digest("$timestamp $it $origin".toByteArray())
                    .joinToString("") { b -> "%02x".format(b) }
                "$scheme ${timestamp}_$digest"
            }
        }.joinToString(" ")
        return buildMap {
            put("Cookie", account.cookie)
            if (authorization.isNotBlank()) put("Authorization", authorization)
            put("X-Origin", origin)
            put("X-Goog-AuthUser", account.authUser)
            put("X-Youtube-Bootstrap-Logged-In", "true")
            account.pageId?.let { put("X-Goog-PageId", it) }
            visitor?.let { put("X-Goog-Visitor-Id", it) }
        }
    }

    /** YouTube's own verdict on whether the request counted as signed in ("1"/"0"; "?" when absent). */
    internal fun loggedIn(json: JSONObject): String {
        val services = json.optJSONObject("responseContext")?.optJSONArray("serviceTrackingParams") ?: return "?"
        for (i in 0 until services.length()) {
            val params = services.optJSONObject(i)?.optJSONArray("params") ?: continue
            for (j in 0 until params.length()) {
                val param = params.optJSONObject(j) ?: continue
                if (param.optString("key") == "logged_in") return param.optString("value")
            }
        }
        return "?"
    }

    /** The highest-bitrate audio format, or null (logging why) when the response has none. */
    internal fun pickAudio(json: JSONObject, client: Client): JSONObject? {
        val streamingData = json.optJSONObject("streamingData")
        if (streamingData == null) {
            val playability = json.optJSONObject("playabilityStatus")
            Log.w(TAG, "Signed-in ${client.name}: ${playability?.optString("status")} ${playability?.optString("reason")} (logged_in=${loggedIn(json)})")
            return null
        }
        val formats = streamingData.optJSONArray("adaptiveFormats") ?: return null
        var best: JSONObject? = null
        for (i in 0 until formats.length()) {
            val format = formats.optJSONObject(i) ?: continue
            if (!format.optString("mimeType").startsWith("audio/")) continue
            if (best == null || format.optInt("bitrate") > best.optInt("bitrate")) best = format
        }
        if (best == null) Log.w(TAG, "Signed-in ${client.name}: no audio formats (SABR-only=${streamingData.has("serverAbrStreamingUrl")})")
        return best
    }
}
