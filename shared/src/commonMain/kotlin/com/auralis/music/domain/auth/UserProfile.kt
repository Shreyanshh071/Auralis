package com.auralis.music.domain.auth

/** The signed-in Auralis account as Profile shows it (Google account, YouTube sync state). */
data class UserProfile(
    val uid: String = "",
    val displayName: String = "",
    val email: String = "Not connected",
    val avatarUrl: String? = null,
    val isGoogleConnected: Boolean = false,
    val isYouTubeSynced: Boolean = false,
    val lastSyncedTimestamp: Long = 0L,
    val syncedPlaylistsCount: Int = 0,
    val syncedLikedCount: Int = 0,
    val autoSyncOnWifi: Boolean = true,
    val syncLikedMusic: Boolean = true,
    val accessToken: String? = null
)
