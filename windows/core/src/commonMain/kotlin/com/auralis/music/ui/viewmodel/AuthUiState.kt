package com.auralis.music.ui.viewmodel

import com.auralis.music.data.network.YouTubePlaylistItem
import com.auralis.music.domain.auth.UserProfile

data class AuthUiState(
    val profile: UserProfile = UserProfile(),
    val remotePlaylists: List<YouTubePlaylistItem> = emptyList(),
    val selectedPlaylistIds: Set<String> = emptySet(),
    val isSyncing: Boolean = false,
    val syncMessage: String? = null,
    val showPlaylistSelectDialog: Boolean = false,
    val isSendingPasswordReset: Boolean = false,
    val passwordResetMessage: String? = null,
    val isDeletingAccount: Boolean = false,
    val deleteAccountError: String? = null
)
