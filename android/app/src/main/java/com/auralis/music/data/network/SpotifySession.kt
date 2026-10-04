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
import java.util.concurrent.TimeUnit

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
        val names = parseCookies(cookie).keys
        return "sp_dc" in names || "sp_key" in names
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
            return@withContext currentToken.takeIf { it.isNotBlank() }
        }

        try {
            Log.d(TAG, "Refreshing Spotify access token via web player endpoint...")
            val req = Request.Builder()
                .url("https://open.spotify.com/get_access_token?reason=transport&productType=web_player")
                .header("User-Agent", USER_AGENT)
                .header("Cookie", currentCookie)
                .header("Referer", "https://open.spotify.com/")
                .header("Origin", "https://open.spotify.com")
                .header("Accept", "application/json")
                .build()

            val resp = client.newCall(req).execute()
            if (resp.isSuccessful) {
                val body = resp.body?.string() ?: ""
                val json = JSONObject(body)
                val newToken = json.optString("accessToken")
                val newExpiresMs = json.optLong("accessTokenExpirationTimestampMs", 0L)
                val isAnon = json.optBoolean("isAnonymous", false)

                if (newToken.isNotBlank() && !isAnon) {
                    val finalExpiry = if (newExpiresMs > System.currentTimeMillis()) newExpiresMs else (System.currentTimeMillis() + 3600_000L)
                    accessToken = newToken
                    expiresAtMs = finalExpiry
                    prefs?.edit()
                        ?.putString(KEY_ACCESS_TOKEN, newToken)
                        ?.putLong(KEY_EXPIRES_AT, finalExpiry)
                        ?.apply()
                    Log.i(TAG, "Successfully refreshed Spotify access token.")
                    return@withContext newToken
                }
            } else {
                Log.w(TAG, "Spotify token refresh returned HTTP ${resp.code}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error refreshing Spotify token: ${e.message}")
        }

        // Return current token as best-effort fallback if refresh failed
        currentToken.takeIf { it.isNotBlank() }
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
            val req = Request.Builder()
                .url("https://open.spotify.com/get_access_token?reason=transport&productType=web_player")
                .header("User-Agent", USER_AGENT)
                .header("Cookie", cookieHeader)
                .header("Referer", "https://open.spotify.com/")
                .header("Origin", "https://open.spotify.com")
                .header("Accept", "application/json")
                .build()

            val resp = client.newCall(req).execute()
            if (resp.isSuccessful) {
                val body = resp.body?.string() ?: ""
                val json = JSONObject(body)
                val token = json.optString("accessToken")
                val isAnon = json.optBoolean("isAnonymous", false)
                if (token.isNotBlank() && !isAnon) {
                    return@withContext token
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error fetching web player token: ${e.message}")
        }
        null
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
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error fetching user profile: ${e.message}")
        }
        null
    }
}
