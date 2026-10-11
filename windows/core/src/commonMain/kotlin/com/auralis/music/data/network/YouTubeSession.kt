package com.auralis.music.data.network

import com.auralis.music.util.KeyValueStore
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
    /** Where Android keeps the session (SharedPreferences file name). */
    const val PREFS_NAME = "auralis_youtube_session"
    private const val KEY_COOKIE = "cookie"
    private const val KEY_VISITOR_DATA = "visitor_data"
    private const val KEY_AUTH_USER = "auth_user"
    private const val KEY_ACCOUNT_LABEL = "account_label"
    private const val KEY_DATA_SYNC_ID = "data_sync_id"

    private var prefs: KeyValueStore? = null

    @Volatile private var cookie: String = ""
    @Volatile var visitorData: String = ""
        private set
    @Volatile private var authUser: String = "0"
    /** The account's session ID, recorded at sign-in (YouTube's DATASYNC_ID, account part). */
    @Volatile private var dataSyncId: String = ""

    private val _signedIn = MutableStateFlow(false)
    val signedIn: StateFlow<Boolean> = _signedIn.asStateFlow()

    private val _accountLabel = MutableStateFlow("")
    /** Shown in Profile so the user can tell which YouTube account is linked. */
    val accountLabel: StateFlow<String> = _accountLabel.asStateFlow()

    fun init(store: KeyValueStore) {
        if (prefs != null) return
        val p = store
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
        prefs?.putStrings(linkedMapOf(
            KEY_COOKIE to cookie,
            KEY_VISITOR_DATA to visitorData,
            KEY_AUTH_USER to this.authUser,
            KEY_ACCOUNT_LABEL to accountLabel,
            KEY_DATA_SYNC_ID to this.dataSyncId
        ))
        _accountLabel.value = accountLabel
        _signedIn.value = hasAuthCookies(cookie)
    }

    fun signOut() {
        cookie = ""
        visitorData = ""
        authUser = "0"
        dataSyncId = ""
        prefs?.clear()
        _accountLabel.value = ""
        _signedIn.value = false
    }

    /** "12345||" or "12345||67890" -> "12345": the part YouTube binds tokens to. */
    fun normalizeDataSyncId(raw: String): String = raw.substringBefore("||").trim()

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
