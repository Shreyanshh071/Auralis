package com.auralis.music.data.network

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import android.webkit.CookieManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.json.JSONArray
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Manages the user's Spotify Web Player session and tokens.
 *
 * Like [YouTubeSession], user credentials never leave the phone: cookies and tokens are stored
 * in private SharedPreferences and sent only to Spotify to read the user's playlists and Liked Songs.
 */
object SpotifySession {
    private const val TAG = "SpotifySession"
    private const val PREFS = "auralis_spotify_session"
    private const val KEY_COOKIE = "cookie"
    private const val KEY_ACCESS_TOKEN = "access_token"
    private const val KEY_EXPIRES_AT = "expires_at"
    private const val KEY_ACCOUNT_LABEL = "account_label"
    private const val KEY_USER_ID = "user_id"
    private const val KEY_AVATAR_URL = "avatar_url"

    const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

    private var prefs: SharedPreferences? = null

    @Volatile private var cookie: String = ""
    @Volatile private var accessToken: String = ""
    @Volatile private var expiresAtMs: Long = 0L
    @Volatile var userId: String = ""
        private set
    @Volatile var avatarUrl: String = ""
        private set

    private val _signedIn = MutableStateFlow(false)
    val signedIn: StateFlow<Boolean> = _signedIn.asStateFlow()

    private val _accountLabel = MutableStateFlow("")
    val accountLabel: StateFlow<String> = _accountLabel.asStateFlow()

    private val client by lazy {
        OkHttpClient.Builder().proxyAuthenticator(com.auralis.music.data.network.ContentProxy.authenticator)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        cookie = p.getString(KEY_COOKIE, "").orEmpty()
        accessToken = p.getString(KEY_ACCESS_TOKEN, "").orEmpty()
        expiresAtMs = p.getLong(KEY_EXPIRES_AT, 0L)
        userId = p.getString(KEY_USER_ID, "").orEmpty()
        avatarUrl = p.getString(KEY_AVATAR_URL, "").orEmpty()
        val label = p.getString(KEY_ACCOUNT_LABEL, "").orEmpty()
        _accountLabel.value = label
        _signedIn.value = hasAuth(cookie, accessToken)
    }

    val isSignedIn: Boolean get() = _signedIn.value

    fun save(
        cookie: String,
        accessToken: String,
        expiresAtMs: Long,
        accountLabel: String,
        userId: String = "",
        avatarUrl: String = ""
    ) {
        this.cookie = cookie
        this.accessToken = accessToken
        this.expiresAtMs = expiresAtMs
        this.userId = userId
        this.avatarUrl = avatarUrl

        prefs?.edit()
            ?.putString(KEY_COOKIE, cookie)
            ?.putString(KEY_ACCESS_TOKEN, accessToken)
            ?.putLong(KEY_EXPIRES_AT, expiresAtMs)
            ?.putString(KEY_ACCOUNT_LABEL, accountLabel)
            ?.putString(KEY_USER_ID, userId)
            ?.putString(KEY_AVATAR_URL, avatarUrl)
            ?.apply()

        _accountLabel.value = accountLabel
        _signedIn.value = hasAuth(cookie, accessToken)
        Log.i(TAG, "Saved Spotify session for: '$accountLabel' (signedIn=${_signedIn.value})")
    }

    fun signOut() {
        cookie = ""
        accessToken = ""
        expiresAtMs = 0L
        userId = ""
        avatarUrl = ""
        prefs?.edit()?.clear()?.apply()
        _accountLabel.value = ""
        _signedIn.value = false

        // Clear web cookies for Spotify
        try {
            val manager = CookieManager.getInstance()
            for (site in listOf("https://open.spotify.com", "https://accounts.spotify.com", "https://spotify.com")) {
                val host = Uri.parse(site).host.orEmpty()
                val baseDomain = host.substringAfter('.', host)
                manager.getCookie(site).orEmpty().split(";")
                    .mapNotNull { it.substringBefore('=').trim().takeIf(String::isNotBlank) }
                    .forEach { name ->
                        for (domain in listOf(host, ".$baseDomain")) {
                            manager.setCookie(site, "$name=; Domain=$domain; Path=/; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT")
                        }
                        manager.setCookie(site, "$name=; Path=/; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT")
                    }
            }
            manager.flush()
        } catch (e: Exception) {
            Log.w(TAG, "Error clearing Spotify cookies: ${e.message}")
        }
        Log.i(TAG, "Spotify session signed out.")
    }

    fun hasAuth(cookie: String, token: String): Boolean {
        if (token.isNotBlank()) return true
        return !parseCookies(cookie)["sp_dc"].isNullOrBlank()
    }

    private fun parseCookies(raw: String): Map<String, String> =
        raw.split(";")
            .mapNotNull { part ->
                val i = part.indexOf('=')
                if (i <= 0) null else part.substring(0, i).trim() to part.substring(i + 1).trim()
            }
            .toMap()

    /**
     * Gets a valid Bearer access token for Spotify Web API.
     * Refreshes automatically if expired and session cookies are present.
     */
    suspend fun getValidAccessToken(): String? = withContext(Dispatchers.IO) {
        val currentToken = accessToken
        val expiry = expiresAtMs

        // If current token is still valid (with 60-second buffer), use it
        if (currentToken.isNotBlank() && System.currentTimeMillis() < (expiry - 60_000L)) {
            return@withContext currentToken
        }

        // Token expired or missing; attempt refresh using stored session cookies
        val currentCookie = cookie.ifBlank {
            try {
                CookieManager.getInstance().getCookie("https://open.spotify.com").orEmpty()
            } catch (_: Exception) { "" }
        }

        if (currentCookie.isBlank()) {
            Log.w(TAG, "Cannot refresh Spotify access token: no session cookie available.")
            return@withContext currentToken.takeIf { it.isNotBlank() && System.currentTimeMillis() < expiry }
        }

        try {
            val refreshed = requestWebPlayerToken(currentCookie)
            if (refreshed != null) {
                accessToken = refreshed.first
                expiresAtMs = refreshed.second
                prefs?.edit()
                    ?.putString(KEY_ACCESS_TOKEN, refreshed.first)
                    ?.putLong(KEY_EXPIRES_AT, refreshed.second)
                    ?.apply()
                return@withContext refreshed.first
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error refreshing Spotify token: ${e.message}")
        }

        // Return current token as best-effort fallback if refresh failed
        currentToken.takeIf { it.isNotBlank() && System.currentTimeMillis() < expiry }
    }

    data class UserProfile(
        val id: String,
        val displayName: String,
        val avatarUrl: String
    )

    /**
     * Fetches a fresh Bearer access token using the provided session cookies.
     */
    suspend fun fetchWebPlayerToken(cookieHeader: String): String? = withContext(Dispatchers.IO) {
        try {
            requestWebPlayerToken(cookieHeader)?.first
        } catch (e: Exception) {
            Log.w(TAG, "Error fetching web player token: ${e.message}")
            null
        }
    }

    /** Spotify's current web-player token endpoint requires a time-based one-time password. */
    private fun requestWebPlayerToken(cookieHeader: String): Pair<String, Long>? {
        val gistRequest = Request.Builder()
            .url("https://api.github.com/gists/22ed9c6ba463899e933427f7de1f0eef")
            .header("Accept", "application/vnd.github+json")
            .build()
        val gistResponse = client.newCall(gistRequest).execute()
        if (!gistResponse.isSuccessful) {
            Log.w(TAG, "Spotify TOTP source returned HTTP ${gistResponse.code}")
            return null
        }
        val gist = JSONObject(gistResponse.body?.string().orEmpty())
        val files = gist.optJSONObject("files") ?: return null
        val firstFile = files.keys().asSequence().firstOrNull() ?: return null
        val nuances = JSONArray(files.getJSONObject(firstFile).optString("content"))
        val latest = (0 until nuances.length()).mapNotNull { nuances.optJSONObject(it) }
            .maxByOrNull { it.optInt("v") } ?: return null
        val secret = latest.optString("s")
        val version = latest.optInt("v")
        if (secret.isBlank()) return null

        val timeRequest = Request.Builder().url("https://open.spotify.com/api/server-time")
            .header("User-Agent", USER_AGENT).build()
        val timeResponse = client.newCall(timeRequest).execute()
        if (!timeResponse.isSuccessful) {
            Log.w(TAG, "Spotify server-time request returned HTTP ${timeResponse.code}")
            return null
        }
        val serverTime = JSONObject(timeResponse.body?.string().orEmpty()).optLong("serverTime")
        if (serverTime <= 0L) return null

        val totp = generateTotp(secret, serverTime)
        val tokenRequest = Request.Builder()
            .url("https://open.spotify.com/api/token?reason=transport&productType=web-player&totp=$totp&totpServer=$totp&totpVer=$version")
            .header("User-Agent", USER_AGENT)
            .header("Cookie", cookieHeader)
            .header("Referer", "https://open.spotify.com/")
            .header("Accept", "application/json")
            .build()
        val response = client.newCall(tokenRequest).execute()
        if (!response.isSuccessful) {
            Log.w(TAG, "Spotify token request returned HTTP ${response.code}")
            return null
        }
        val json = JSONObject(response.body?.string().orEmpty())
        val token = json.optString("accessToken")
        if (token.isBlank() || json.optBoolean("isAnonymous")) {
            Log.w(TAG, "Spotify returned an empty or anonymous web-player token")
            return null
        }
        val expiry = json.optLong("accessTokenExpirationTimestampMs")
            .takeIf { it > System.currentTimeMillis() } ?: System.currentTimeMillis() + 3_000_000L
        return token to expiry
    }

    private fun generateTotp(base32Secret: String, unixSeconds: Long): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        var buffer = 0
        var bits = 0
        val key = ArrayList<Byte>()
        for (char in base32Secret.uppercase()) {
            val digit = alphabet.indexOf(char)
            if (digit < 0) continue
            buffer = (buffer shl 5) or digit
            bits += 5
            if (bits >= 8) {
                bits -= 8
                key.add(((buffer shr bits) and 0xff).toByte())
            }
        }
        val step = unixSeconds / 30L
        val message = ByteArray(8) { index -> (step ushr ((7 - index) * 8)).toByte() }
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(key.toByteArray(), "HmacSHA1"))
        val hash = mac.doFinal(message)
        val offset = hash.last().toInt() and 0x0f
        val number = ((hash[offset].toInt() and 0x7f) shl 24) or
            ((hash[offset + 1].toInt() and 0xff) shl 16) or
            ((hash[offset + 2].toInt() and 0xff) shl 8) or
            (hash[offset + 3].toInt() and 0xff)
        return (number % 1_000_000).toString().padStart(6, '0')
    }

    /**
     * Fetches the user profile (id, display name, avatar) using a valid access token.
     */
    suspend fun fetchUserProfile(token: String): UserProfile? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url("https://api.spotify.com/v1/me")
                .header("Authorization", "Bearer $token")
                .header("User-Agent", USER_AGENT)
                .build()

            val resp = client.newCall(req).execute()
            if (resp.isSuccessful) {
                val body = resp.body?.string() ?: ""
                val json = JSONObject(body)
                val id = json.optString("id")
                val displayName = json.optString("display_name").ifBlank { id }
                val avatarUrl = json.optJSONArray("images")?.optJSONObject(0)?.optString("url").orEmpty()
                return@withContext UserProfile(id, displayName, avatarUrl)
            } else {
                Log.w(TAG, "Spotify profile request returned HTTP ${resp.code}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error fetching user profile: ${e.message}")
        }
        null
    }
}
