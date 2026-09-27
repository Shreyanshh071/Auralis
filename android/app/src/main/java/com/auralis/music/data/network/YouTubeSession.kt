package com.auralis.music.data.network

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.MessageDigest

/**
 * The user's own YouTube sign-in, used only to unlock age-restricted songs.
 *
 * Auralis sign-in (email or Google through Firebase) never gives YouTube a session: age-restricted
 * videos answer "Sign in to confirm your age" to every signed-out client, so the only way to play
 * or download them is a real YouTube login. The user signs in to music.youtube.com inside the app
 * ([com.auralis.music.ui.screens.YouTubeSignInScreen]) and its cookies are kept here.
 *
 * The cookie stays on this phone: app-private storage, app backup disabled, never uploaded to
 * Auralis's servers. It is sent only to YouTube, and only for songs YouTube says need an age check.
 */
object YouTubeSession {
    private const val PREFS = "auralis_youtube_session"
    private const val KEY_COOKIE = "cookie"
    private const val KEY_VISITOR_DATA = "visitor_data"
    private const val KEY_AUTH_USER = "auth_user"
    private const val KEY_ACCOUNT_LABEL = "account_label"
    private const val KEY_DATA_SYNC_ID = "data_sync_id"

    private var prefs: SharedPreferences? = null

    @Volatile private var cookie: String = ""
    @Volatile var visitorData: String = ""
        private set
    @Volatile private var authUser: String = "0"
    /** The account's session ID; YouTube binds a signed-in download's PO token to it. */
    @Volatile private var dataSyncId: String = ""

    private val _signedIn = MutableStateFlow(false)
    val signedIn: StateFlow<Boolean> = _signedIn.asStateFlow()

    private val _accountLabel = MutableStateFlow("")
    /** Shown in Profile so the user can tell which YouTube account is linked. */
    val accountLabel: StateFlow<String> = _accountLabel.asStateFlow()

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        cookie = p.getString(KEY_COOKIE, "").orEmpty()
        visitorData = p.getString(KEY_VISITOR_DATA, "").orEmpty()
        authUser = p.getString(KEY_AUTH_USER, "0").orEmpty().ifBlank { "0" }
        dataSyncId = p.getString(KEY_DATA_SYNC_ID, "").orEmpty()
        _accountLabel.value = p.getString(KEY_ACCOUNT_LABEL, "").orEmpty()
        _signedIn.value = hasAuthCookies(cookie)
    }

    val isSignedIn: Boolean get() = _signedIn.value

    fun save(cookie: String, visitorData: String, authUser: String, accountLabel: String, dataSyncId: String = "") {
        this.cookie = cookie
        this.visitorData = visitorData
        this.authUser = authUser.filter(Char::isDigit).ifBlank { "0" }
        this.dataSyncId = normalizeDataSyncId(dataSyncId)
        prefs?.edit()
            ?.putString(KEY_COOKIE, cookie)
            ?.putString(KEY_VISITOR_DATA, visitorData)
            ?.putString(KEY_AUTH_USER, this.authUser)
            ?.putString(KEY_ACCOUNT_LABEL, accountLabel)
            ?.putString(KEY_DATA_SYNC_ID, this.dataSyncId)
            ?.apply()
        _accountLabel.value = accountLabel
        _signedIn.value = hasAuthCookies(cookie)
    }

    fun signOut() {
        cookie = ""
        visitorData = ""
        authUser = "0"
        dataSyncId = ""
        prefs?.edit()?.clear()?.apply()
        _accountLabel.value = ""
        _signedIn.value = false
    }

    /** "12345||" or "12345||67890" -> "12345": the part YouTube binds tokens to. */
    fun normalizeDataSyncId(raw: String): String = raw.substringBefore("||").trim()

    /**
     * The account's Data Sync ID. Sessions saved before it was recorded fetch it once from
     * music.youtube.com (its page config carries DATASYNC_ID for the signed-in account).
     */
    fun dataSyncId(): String {
        if (dataSyncId.isNotBlank() || !isSignedIn) return dataSyncId
        val origin = "https://music.youtube.com"
        val fetched = try {
            val request = okhttp3.Request.Builder()
                .url("$origin/")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36")
                .header("Accept-Language", "en-US,en;q=0.9")
                .header("Cookie", cookie)
                .build()
            okhttp3.OkHttpClient().newCall(request).execute().use { resp ->
                val html = resp.body?.string().orEmpty()
                Regex(""""DATASYNC_ID"\s*:\s*"([^"]+)"""").find(html)?.groupValues?.get(1).orEmpty()
            }
        } catch (_: Exception) {
            ""
        }
        val id = normalizeDataSyncId(fetched)
        if (id.isNotBlank()) {
            dataSyncId = id
            prefs?.edit()?.putString(KEY_DATA_SYNC_ID, id)?.apply()
        }
        return id
    }

    /** Signed in means YouTube set its login cookies, not just any cookie from the login page. */
    fun hasAuthCookies(cookie: String): Boolean {
        val names = parseCookies(cookie).keys
        return "LOGIN_INFO" in names &&
            ("SAPISID" in names || "__Secure-3PAPISID" in names || "__Secure-1PAPISID" in names)
    }

    fun parseCookies(cookie: String): Map<String, String> =
        cookie.split(";")
            .mapNotNull { part ->
                val i = part.indexOf('=')
                if (i <= 0) null else part.substring(0, i).trim() to part.substring(i + 1).trim()
            }
            .toMap()

    /**
     * Headers that make an InnerTube request count as this signed-in account: the cookie plus
     * YouTube's SAPISIDHASH authorization (SHA-1 of "timestamp SAPISID origin"), the same scheme
     * youtube.com's own pages use. Empty when signed out.
     */
    fun authHeaders(origin: String): Map<String, String> {
        val current = cookie
        if (!hasAuthCookies(current)) return emptyMap()
        val cookies = parseCookies(current)
        val timestamp = (System.currentTimeMillis() / 1000L).toString()
        val authorization = listOf(
            "SAPISIDHASH" to (cookies["SAPISID"] ?: cookies["__Secure-3PAPISID"]),
            "SAPISID1PHASH" to cookies["__Secure-1PAPISID"],
            "SAPISID3PHASH" to cookies["__Secure-3PAPISID"]
        ).mapNotNull { (scheme, sid) ->
            sid?.takeIf { it.isNotBlank() }?.let { "$scheme ${timestamp}_${sha1Hex("$timestamp $it $origin")}" }
        }.joinToString(" ")

        return buildMap {
            put("Cookie", current)
            put("Authorization", authorization)
            put("X-Origin", origin)
            put("X-Goog-AuthUser", authUser)
            put("X-Youtube-Bootstrap-Logged-In", "true")
            if (visitorData.isNotBlank()) put("X-Goog-Visitor-Id", visitorData)
        }
    }

    private fun sha1Hex(text: String): String =
        MessageDigest.getInstance("SHA-1").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
