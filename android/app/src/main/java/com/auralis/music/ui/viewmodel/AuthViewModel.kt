package com.auralis.music.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.auralis.music.data.network.YouTubePlaylistItem
import com.auralis.music.domain.auth.GoogleAccountSyncManager
import com.auralis.music.domain.auth.GoogleSignInHelper
import com.auralis.music.domain.auth.UserProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AuthUiState(
    val profile: UserProfile = UserProfile(),
    val remotePlaylists: List<YouTubePlaylistItem> = emptyList(),
    val selectedPlaylistIds: Set<String> = emptySet(),
    val isSyncing: Boolean = false,
    val syncMessage: String? = null,
    val showPlaylistSelectDialog: Boolean = false
)

class AuthViewModel(
    private val syncManager: GoogleAccountSyncManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            syncManager.userProfile.collect { profile ->
                _uiState.update { it.copy(profile = profile) }
            }
        }
        viewModelScope.launch {
            syncManager.remotePlaylists.collect { list ->
                _uiState.update { it.copy(remotePlaylists = list) }
            }
        }
        viewModelScope.launch {
            syncManager.isSyncing.collect { syncing ->
                _uiState.update { it.copy(isSyncing = syncing) }
            }
        }
        viewModelScope.launch {
            syncManager.syncMessage.collect { msg ->
                _uiState.update { it.copy(syncMessage = msg) }
            }
        }
    }

    fun connectWithGoogleOAuth(activity: android.app.Activity, onSuccess: (() -> Unit)? = null) {
        viewModelScope.launch {
            _uiState.update { it.copy(isSyncing = true, syncMessage = "Connecting with Google YouTube OAuth...") }
            try {
                val helper = GoogleSignInHelper(activity)
                val token = helper.signInWithGoogleYouTubeOAuth(activity)

                if (!token.isNullOrBlank()) {
                    syncManager.connectWithOAuthToken(token)
                    onSuccess?.invoke()
                    openPlaylistSelectDialog()
                } else {
                    _uiState.update { it.copy(isSyncing = false, syncMessage = "Google OAuth cancelled.") }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isSyncing = false, syncMessage = "OAuth error: ${e.localizedMessage ?: e.message}") }
            }
        }
    }

    fun signInWithGoogle(activity: android.app.Activity, onSuccess: (() -> Unit)? = null) {
        viewModelScope.launch {
            _uiState.update { it.copy(isSyncing = true, syncMessage = "Signing in with Google...") }
            try {
                val helper = GoogleSignInHelper(activity)
                val account = helper.signIn(activity)
                if (account != null) {
                    syncManager.connectGoogleAccountWithIdToken(account)
                    onSuccess?.invoke()
                } else {
                    _uiState.update { it.copy(isSyncing = false, syncMessage = "Google Sign-In cancelled.") }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isSyncing = false, syncMessage = "Sign-in error: ${e.localizedMessage ?: e.message}") }
            }
        }
    }

    fun signUpWithEmail(email: String, password: String, displayName: String, onSuccess: () -> Unit) {
        viewModelScope.launch {
            try {
                syncManager.signUpWithEmail(email, password, displayName)
                onSuccess()
            } catch (e: Exception) {
                _uiState.update { it.copy(isSyncing = false, syncMessage = e.localizedMessage ?: "Failed to create account") }
            }
        }
    }

    fun signInWithEmail(email: String, password: String, onSuccess: () -> Unit) {
        viewModelScope.launch {
            try {
                syncManager.signInWithEmail(email, password)
                onSuccess()
            } catch (e: Exception) {
                _uiState.update { it.copy(isSyncing = false, syncMessage = e.localizedMessage ?: "Failed to sign in") }
            }
        }
    }

    fun openPlaylistSelectDialog() {
        viewModelScope.launch {
            syncManager.fetchRemotePlaylists()
            _uiState.update { it.copy(showPlaylistSelectDialog = true) }
        }
    }

    fun closePlaylistSelectDialog() {
        _uiState.update { it.copy(showPlaylistSelectDialog = false) }
    }

    fun togglePlaylistSelection(playlistId: String) {
        _uiState.update {
            val current = it.selectedPlaylistIds.toMutableSet()
            if (current.contains(playlistId)) {
                current.remove(playlistId)
            } else {
                current.add(playlistId)
            }
            it.copy(selectedPlaylistIds = current)
        }
    }

    fun selectAllPlaylists() {
        _uiState.update {
            it.copy(selectedPlaylistIds = it.remotePlaylists.map { p -> p.id }.toSet())
        }
    }

    fun deselectAllPlaylists() {
        _uiState.update {
            it.copy(selectedPlaylistIds = emptySet())
        }
    }

    fun connectWithOAuthToken(token: String) {
        viewModelScope.launch {
            try {
                syncManager.connectWithOAuthToken(token)
                openPlaylistSelectDialog()
            } catch (_: Exception) {}
        }
    }

    fun importSelectedPlaylists() {
        val selected = _uiState.value.selectedPlaylistIds.toList()
        if (selected.isEmpty()) return
        viewModelScope.launch {
            syncManager.importSelectedPlaylists(selected)
            _uiState.update { it.copy(showPlaylistSelectDialog = false, selectedPlaylistIds = emptySet()) }
        }
    }

    fun syncLikedMusic() {
        viewModelScope.launch {
            syncManager.syncLikedMusic()
        }
    }

    fun syncLibraryNow() {
        viewModelScope.launch {
            syncManager.syncLikedMusic()
            openPlaylistSelectDialog()
        }
    }

    fun disconnectAccount() {
        syncManager.disconnectAccount()
    }

    fun toggleAutoSync(enabled: Boolean) {
        syncManager.toggleAutoSync(enabled)
    }

    fun toggleSyncLiked(enabled: Boolean) {
        syncManager.toggleSyncLiked(enabled)
    }
}
