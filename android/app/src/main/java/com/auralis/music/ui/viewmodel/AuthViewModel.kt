package com.auralis.music.ui.viewmodel

import com.auralis.music.R
import com.auralis.music.ui.i18n.str

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
    val showPlaylistSelectDialog: Boolean = false,
    val isSendingPasswordReset: Boolean = false,
    val passwordResetMessage: String? = null,
    val isDeletingAccount: Boolean = false,
    val deleteAccountError: String? = null
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

    fun signInWithGoogle(activity: android.app.Activity, onSuccess: (() -> Unit)? = null) {
        viewModelScope.launch {
            _uiState.update { it.copy(isSyncing = true, syncMessage = str(R.string.signing_in_with_google)) }
            try {
                val helper = GoogleSignInHelper(activity)
                val account = helper.signIn(activity)
                if (account != null) {
                    syncManager.connectGoogleAccountWithIdToken(account)
                    onSuccess?.invoke()
                } else {
                    _uiState.update { it.copy(isSyncing = false, syncMessage = str(R.string.google_sign_in_cancelled)) }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isSyncing = false, syncMessage = str(R.string.sign_in_error_x, e.localizedMessage ?: e.message)) }
            }
        }
    }

    fun signUpWithEmail(email: String, password: String, displayName: String, onSuccess: () -> Unit) {
        viewModelScope.launch {
            try {
                syncManager.signUpWithEmail(email, password, displayName)
                onSuccess()
            } catch (e: Exception) {
                _uiState.update { it.copy(isSyncing = false, syncMessage = e.localizedMessage ?: str(R.string.failed_to_create_account)) }
            }
        }
    }

    fun signInWithEmail(email: String, password: String, onSuccess: () -> Unit) {
        viewModelScope.launch {
            try {
                syncManager.signInWithEmail(email, password)
                onSuccess()
            } catch (e: Exception) {
                _uiState.update { it.copy(isSyncing = false, syncMessage = e.localizedMessage ?: str(R.string.failed_to_sign_in)) }
            }
        }
    }

    fun sendPasswordResetEmail(email: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isSendingPasswordReset = true, passwordResetMessage = null) }
            try {
                syncManager.sendPasswordResetEmail(email)
                _uiState.update {
                    it.copy(
                        isSendingPasswordReset = false,
                        passwordResetMessage = str(R.string.if_an_account_exists_for_that_email_a_re)
                    )
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isSendingPasswordReset = false,
                        passwordResetMessage = e.localizedMessage ?: str(R.string.couldn_t_send_the_reset_email_try_again)
                    )
                }
            }
        }
    }

    fun clearPasswordResetMessage() {
        _uiState.update { it.copy(passwordResetMessage = null) }
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
            syncManager.restoreLibraryFromCloud()
        }
    }

    fun restoreCloudLibrary() {
        viewModelScope.launch {
            syncManager.restoreLibraryFromCloud()
        }
    }

    fun backupCloudLibrary() {
        viewModelScope.launch {
            syncManager.backupLibraryToCloud()
        }
    }

    fun disconnectAccount() {
        viewModelScope.launch {
            // Anything not yet backed up would stay on this phone only, owned by a signed-out account.
            kotlinx.coroutines.withTimeoutOrNull(8_000L) { syncManager.flushToCloud() }
            syncManager.disconnectAccount()
        }
    }

    /** Email + password accounts confirm deletion with their password; Google accounts sign in again. */
    fun deleteAccountNeedsPassword(): Boolean = syncManager.isEmailPasswordAccount()

    /**
     * Deletes the account and everything backed up to it, after confirming it's really the owner:
     * [password] for email accounts, a fresh Google sign-in otherwise.
     */
    fun deleteAccount(activity: android.app.Activity, password: String?, onDeleted: () -> Unit) {
        viewModelScope.launch {
            _uiState.update { it.copy(isDeletingAccount = true, deleteAccountError = null) }
            val credential = try {
                if (syncManager.isEmailPasswordAccount()) {
                    val email = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.email
                    if (email.isNullOrBlank() || password.isNullOrBlank()) null
                    else com.google.firebase.auth.EmailAuthProvider.getCredential(email, password)
                } else {
                    GoogleSignInHelper(activity).signIn(activity)?.let {
                        com.google.firebase.auth.GoogleAuthProvider.getCredential(it.idToken, null)
                    }
                }
            } catch (e: Exception) {
                null
            }
            if (credential == null) {
                _uiState.update { it.copy(isDeletingAccount = false, deleteAccountError = str(R.string.couldn_t_confirm_it_s_you_nothing_was_de)) }
                return@launch
            }
            val result = syncManager.deleteAccount(credential)
            if (result.isSuccess) {
                _uiState.update { it.copy(isDeletingAccount = false) }
                onDeleted()
            } else {
                val e = result.exceptionOrNull()
                val message = when (e) {
                    is com.google.firebase.auth.FirebaseAuthInvalidCredentialsException -> str(R.string.wrong_password_nothing_was_deleted)
                    else -> str(R.string.couldn_t_delete_your_account_x, e?.localizedMessage ?: "unknown error")
                }
                _uiState.update { it.copy(isDeletingAccount = false, deleteAccountError = message) }
            }
        }
    }

    fun clearDeleteAccountError() {
        _uiState.update { it.copy(deleteAccountError = null) }
    }

    fun toggleAutoSync(enabled: Boolean) {
        syncManager.toggleAutoSync(enabled)
    }

    fun toggleSyncLiked(enabled: Boolean) {
        syncManager.toggleSyncLiked(enabled)
    }
}
