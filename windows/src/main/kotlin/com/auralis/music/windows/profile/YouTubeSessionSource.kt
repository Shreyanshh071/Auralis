package com.auralis.music.windows.profile

import com.auralis.music.data.network.YouTubeSession
import com.auralis.music.util.Log
import java.io.File

/**
 * The browser profile the listener picked to connect YouTube Music, remembered so the session can
 * be renewed from that same profile. Google rotates its sign-in cookies; once the browser takes a
 * new one, the copy Auralis holds stops working and YouTube answers "logged out". Re-reading the
 * profile the listener chose (never any other) picks up the current one, so they aren't asked to
 * reconnect while they're still signed in there. A pasted cookie has no profile to renew from.
 */
internal object YouTubeSessionSource {
    private const val TAG = "YouTubeSessionSource"
    private val store by lazy {
        com.auralis.music.util.PropertiesFileKeyValueStore(File(com.auralis.music.util.desktopAppDataDir(), "youtube-session-source.properties"))
    }

    /** The listener connected from [profile]. */
    fun remember(profile: BrowserYouTubeLogin.Profile) = runCatching {
        store.putStrings(mapOf("browser" to profile.browser, "name" to profile.name, "database" to profile.database.absolutePath,
            "firefox" to profile.firefox.toString(), "localState" to profile.localState?.absolutePath.orEmpty()))
    }

    /** The session came from somewhere else (a pasted cookie): nothing to renew from. */
    fun forget() = runCatching { store.putStrings(mapOf("database" to "")) }

    private fun profile(): BrowserYouTubeLogin.Profile? {
        val database = store.getString("database", "").orEmpty().takeIf { it.isNotBlank() }?.let(::File)?.takeIf { it.isFile } ?: return null
        return BrowserYouTubeLogin.Profile(store.getString("browser", "").orEmpty(), store.getString("name", "").orEmpty(), database,
            store.getString("firefox", "false") == "true",
            store.getString("localState", "").orEmpty().takeIf { it.isNotBlank() }?.let(::File))
    }

    /**
     * A current session from the remembered profile, when it differs from [stale] and is the same
     * account (same SAPISID). Null when there is no profile, it can't be read, or it holds nothing newer.
     */
    fun renew(stale: String): String? {
        val profile = profile() ?: return null
        val fresh = runCatching { BrowserYouTubeLogin.read(profile) }
            .onFailure { Log.w(TAG, "Couldn't renew from ${profile.label}: ${it.message}") }.getOrNull() ?: return null
        if (fresh == stale) return null
        val account = { cookie: String -> YouTubeSession.parseCookies(cookie).let { it["SAPISID"] ?: it["__Secure-3PAPISID"] } }
        if (account(fresh) != account(stale)) {
            Log.w(TAG, "${profile.label} is now signed in to a different YouTube account; not switching automatically")
            return null
        }
        Log.i(TAG, "Renewed the YouTube session from ${profile.label}")
        return fresh
    }
}
