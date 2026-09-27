package com.auralis.music.data.network.discord

import android.content.Context
import android.util.Log
import android.os.Handler
import android.os.Looper

/** Kotlin boundary for Discord's official Social SDK native client. */
object DiscordSocialClient {
    const val APPLICATION_ID = 1553114990909591732L

    data class UserProfile(val username: String, val avatarUrl: String)

    init {
        System.loadLibrary("auralis_discord_bridge")
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var authorizationListener: ((Boolean, String, String) -> Unit)? = null
    @Volatile var readyListener: (() -> Unit)? = null
    @Volatile var profileListener: ((UserProfile) -> Unit)? = null
    @Volatile var currentProfile: UserProfile? = null
        private set
    @Volatile private var tokenStore: DiscordOAuthTokenStore? = null

    fun initialize(context: Context): Boolean {
        if (!nativeInitialize(APPLICATION_ID)) return false
        val store = tokenStore ?: DiscordOAuthTokenStore(context.applicationContext).also { tokenStore = it }
        store.load()?.let { tokens ->
            nativeResumeAuthorization(tokens.accessToken, tokens.refreshToken, tokens.expiresAtMs)
        }
        return true
    }

    fun updatePresence(p: DiscordPresence) = nativeUpdatePresence(
        p.name, p.details, p.state, p.largeImage, p.largeText, p.smallImage, p.smallText,
        p.activityType, p.isPlaying, p.positionMs, p.durationMs, p.showWhenPaused
    )

    /** [status] is a discordpp::StatusType: 0 Online, 3 Idle, 4 Do Not Disturb, 5 Invisible. */
    fun setOnlineStatus(status: Int) = nativeSetOnlineStatus(status)

    fun authorize(onResult: (Boolean, String, String) -> Unit) {
        authorizationListener = onResult
        nativeAuthorize()
    }

    fun clearPresence() = nativeClearPresence()

    fun forgetAuthorization() {
        tokenStore?.clear()
    }

    fun pumpCallbacks() = nativePumpCallbacks()

    @JvmStatic
    fun onAuthorizationResult(success: Boolean, username: String, detail: String) {
        Log.d("AuralisDiscord", "Official authorization completed: success=$success")
        mainHandler.post {
            val listener = authorizationListener
            authorizationListener = null
            listener?.invoke(success, username, detail)
        }
    }

    @JvmStatic
    fun onSdkReady() {
        mainHandler.post { readyListener?.invoke() }
    }

    @JvmStatic
    fun onCurrentUserProfile(username: String, avatarUrl: String) {
        val profile = UserProfile(username, avatarUrl)
        currentProfile = profile
        mainHandler.post { profileListener?.invoke(profile) }
    }

    @JvmStatic
    fun onAuthorizationTokens(accessToken: String, refreshToken: String, expiresIn: Int) {
        mainHandler.post {
            tokenStore?.save(
                DiscordOAuthTokens(
                    accessToken,
                    refreshToken,
                    System.currentTimeMillis() + expiresIn.coerceAtLeast(0) * 1000L
                )
            )
        }
    }

    private external fun nativeInitialize(applicationId: Long): Boolean
    private external fun nativeUpdatePresence(
        name: String,
        details: String,
        state: String,
        largeImage: String,
        largeText: String,
        smallImage: String,
        smallText: String,
        activityType: String,
        isPlaying: Boolean,
        positionMs: Long,
        durationMs: Long,
        showWhenPaused: Boolean
    )
    private external fun nativeSetOnlineStatus(status: Int)
    private external fun nativeAuthorize()
    private external fun nativeResumeAuthorization(accessToken: String, refreshToken: String, expiresAtMs: Long)
    private external fun nativeClearPresence()
    private external fun nativePumpCallbacks()
}

/** One Rich Presence update with every field already chosen; an empty string leaves that field out. */
data class DiscordPresence(
    val name: String,
    val details: String,
    val state: String,
    val largeImage: String,
    val largeText: String,
    val smallImage: String,
    val smallText: String,
    val activityType: String,
    val isPlaying: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val showWhenPaused: Boolean
)
