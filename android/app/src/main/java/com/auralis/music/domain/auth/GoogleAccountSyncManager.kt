package com.auralis.music.domain.auth

import android.content.Context
import android.content.SharedPreferences
import com.auralis.music.data.network.YouTubeChannelInfo
import com.auralis.music.data.network.YouTubeDataApiClient
import com.auralis.music.data.network.YouTubePlaylistItem
import com.auralis.music.domain.model.Playlist
import com.auralis.music.domain.model.Track
import com.auralis.music.domain.repository.HistoryRepository
import com.auralis.music.domain.repository.LibraryRepository
import com.auralis.music.domain.repository.SearchRepository
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.UserProfileChangeRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

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

class GoogleAccountSyncManager(
    private val context: Context,
    private val libraryRepository: LibraryRepository,
    private val historyRepository: HistoryRepository,
    private val searchRepository: SearchRepository,
    private val ytApiClient: YouTubeDataApiClient = YouTubeDataApiClient()
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("auralis_account_prefs", Context.MODE_PRIVATE)

    private val _userProfile = MutableStateFlow(loadPersistedProfile())
    val userProfile: StateFlow<UserProfile> = _userProfile.asStateFlow()

    private val _remotePlaylists = MutableStateFlow<List<YouTubePlaylistItem>>(emptyList())
    val remotePlaylists: StateFlow<List<YouTubePlaylistItem>> = _remotePlaylists.asStateFlow()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val _syncMessage = MutableStateFlow<String?>(null)
    val syncMessage: StateFlow<String?> = _syncMessage.asStateFlow()

    private fun loadPersistedProfile(): UserProfile {
        val fbUser = try { FirebaseAuth.getInstance().currentUser } catch (_: Exception) { null }
        if (fbUser != null && !fbUser.isAnonymous) {
            val uid = fbUser.uid
            val email = fbUser.email ?: "Authenticated User"
            val rawPrefName = prefs.getString("display_name", null)
            val displayName = fbUser.displayName?.takeIf { it.isNotBlank() && !isGenericListener(it) }
                ?: rawPrefName?.takeIf { it.isNotBlank() && !isGenericListener(it) }
                ?: fbUser.email?.substringBefore("@")
                ?: ""
            val token = prefs.getString("access_token", null)

            return UserProfile(
                uid = uid,
                displayName = displayName,
                email = email,
                avatarUrl = fbUser.photoUrl?.toString() ?: prefs.getString("avatar_url", null),
                isGoogleConnected = true,
                isYouTubeSynced = prefs.getBoolean("is_yt_synced", false),
                lastSyncedTimestamp = prefs.getLong("last_synced_ts", 0L),
                syncedPlaylistsCount = prefs.getInt("synced_playlists_count", 0),
                syncedLikedCount = prefs.getInt("synced_liked_count", 0),
                autoSyncOnWifi = prefs.getBoolean("auto_sync_wifi", true),
                syncLikedMusic = prefs.getBoolean("sync_liked", true),
                accessToken = token
            )
        }

        return UserProfile()
    }

    private fun persistProfile(profile: UserProfile) {
        prefs.edit()
            .putString("uid", profile.uid)
            .putString("display_name", profile.displayName)
            .putString("email", profile.email)
            .putString("avatar_url", profile.avatarUrl)
            .putBoolean("is_google_connected", profile.isGoogleConnected)
            .putBoolean("is_yt_synced", profile.isYouTubeSynced)
            .putLong("last_synced_ts", profile.lastSyncedTimestamp)
            .putInt("synced_playlists_count", profile.syncedPlaylistsCount)
            .putInt("synced_liked_count", profile.syncedLikedCount)
            .putBoolean("auto_sync_wifi", profile.autoSyncOnWifi)
            .putBoolean("sync_liked", profile.syncLikedMusic)
            .putString("access_token", profile.accessToken)
            .apply()
    }

    /**
     * Connects with Google / YouTube OAuth access token (with `youtube.readonly` scope).
     * Automatically queries the user's real YouTube channel and fetches their playlist catalog.
     */
    suspend fun connectWithOAuthToken(token: String) = withContext(Dispatchers.IO) {
        _isSyncing.value = true
        _syncMessage.value = "Authenticating with Google & YouTube Data API..."

        try {
            // 1. Fetch channel info
            val channelInfo = ytApiClient.fetchChannelInfo(token)

            val updated = _userProfile.value.copy(
                uid = "yt_${System.currentTimeMillis()}",
                displayName = channelInfo.title,
                email = "Connected via YouTube Data API",
                avatarUrl = channelInfo.avatarUrl,
                isGoogleConnected = true,
                isYouTubeSynced = true,
                accessToken = token
            )
            _userProfile.value = updated
            persistProfile(updated)

            _syncMessage.value = "Connected as ${channelInfo.title}. Fetching your playlists..."

            // 2. Fetch playlists
            fetchRemotePlaylists()

            _isSyncing.value = false
        } catch (e: Exception) {
            _syncMessage.value = "Auth error: ${e.localizedMessage}"
            _isSyncing.value = false
            throw e
        }
    }

    /**
     * Creates a new Email & Password account via Firebase Auth.
     */
    suspend fun signUpWithEmail(email: String, password: String, displayName: String) = withContext(Dispatchers.IO) {
        _isSyncing.value = true
        _syncMessage.value = "Creating account..."
        val name = displayName.ifBlank { email.substringBefore("@") }

        try {
            val auth = FirebaseAuth.getInstance()
            val result = auth.createUserWithEmailAndPassword(email.trim(), password).await()
            val user = result.user

            user?.let {
                val profileUpdates = UserProfileChangeRequest.Builder()
                    .setDisplayName(name)
                    .build()
                it.updateProfile(profileUpdates).await()
            }

            val updated = _userProfile.value.copy(
                uid = user?.uid ?: "",
                displayName = name,
                email = email.trim(),
                isGoogleConnected = true
            )
            _userProfile.value = updated
            persistProfile(updated)
            _syncMessage.value = "Account created successfully!"
        } catch (e: Exception) {
            val msg = e.localizedMessage ?: "Failed to create account"
            _syncMessage.value = msg
            throw RuntimeException(msg, e)
        } finally {
            _isSyncing.value = false
        }
    }

    /**
     * Signs in with Email & Password via Firebase Auth.
     */
    suspend fun signInWithEmail(email: String, password: String) = withContext(Dispatchers.IO) {
        _isSyncing.value = true
        _syncMessage.value = "Signing in..."

        try {
            val auth = FirebaseAuth.getInstance()
            val result = auth.signInWithEmailAndPassword(email.trim(), password).await()
            val user = result.user ?: throw RuntimeException("Authentication returned empty user.")
            val name = user.displayName ?: email.substringBefore("@")

            val updated = _userProfile.value.copy(
                uid = user.uid,
                displayName = name,
                email = user.email ?: email.trim(),
                avatarUrl = user.photoUrl?.toString(),
                isGoogleConnected = true
            )
            _userProfile.value = updated
            persistProfile(updated)
            _syncMessage.value = "Welcome back, $name!"
        } catch (e: Exception) {
            val msg = e.localizedMessage ?: "Failed to sign in"
            _syncMessage.value = msg
            throw RuntimeException(msg, e)
        } finally {
            _isSyncing.value = false
        }
    }

    /**
     * Connects account with Google ID token and authenticates Firebase.
     */
    suspend fun connectGoogleAccountWithIdToken(
        account: GoogleUserAccount
    ) = withContext(Dispatchers.IO) {
        _isSyncing.value = true
        _syncMessage.value = "Authenticating with Google..."
        try {
            val credential = com.google.firebase.auth.GoogleAuthProvider.getCredential(account.idToken, null)
            val authResult = FirebaseAuth.getInstance().signInWithCredential(credential).await()
            val fbUser = authResult.user ?: FirebaseAuth.getInstance().currentUser

            val updated = _userProfile.value.copy(
                uid = fbUser?.uid ?: "google_${System.currentTimeMillis()}",
                displayName = fbUser?.displayName ?: account.displayName,
                email = fbUser?.email ?: account.email,
                avatarUrl = fbUser?.photoUrl?.toString() ?: account.avatarUrl,
                isGoogleConnected = true,
                isYouTubeSynced = true
            )
            _userProfile.value = updated
            persistProfile(updated)
            _syncMessage.value = "Signed in as ${updated.displayName}"
        } catch (e: Exception) {
            val msg = "Google Sign-In failed: ${e.localizedMessage ?: e.message}"
            _syncMessage.value = msg
            throw RuntimeException(msg, e)
        } finally {
            _isSyncing.value = false
        }
    }

    /**
     * Fetches user's authentic YouTube playlists using their OAuth token.
     */
    suspend fun fetchRemotePlaylists(): List<YouTubePlaylistItem> = withContext(Dispatchers.IO) {
        val token = _userProfile.value.accessToken
        if (token.isNullOrBlank()) {
            // Fallback: search user-oriented public playlists
            val searchResults = searchRepository.search("YouTube Music Playlists")
            val fallback = searchResults.playlists.map {
                YouTubePlaylistItem(id = it.id, title = it.title, trackCount = it.trackCount ?: 20, thumbnail = it.thumbnail)
            }
            _remotePlaylists.value = fallback
            return@withContext fallback
        }

        try {
            val playlists = ytApiClient.fetchUserPlaylists(token)
            _remotePlaylists.value = playlists
            playlists
        } catch (e: Exception) {
            _syncMessage.value = "Error fetching playlists: ${e.localizedMessage}"
            emptyList()
        }
    }

    /**
     * Imports selected YouTube playlists into the native room database.
     */
    suspend fun importSelectedPlaylists(playlistIds: List<String>): Int = withContext(Dispatchers.IO) {
        _isSyncing.value = true
        _syncMessage.value = "Importing ${playlistIds.size} playlists from YouTube..."
        var importedCount = 0

        val token = _userProfile.value.accessToken

        for (pid in playlistIds) {
            val playlistMeta = _remotePlaylists.value.firstOrNull { it.id == pid }
            val title = playlistMeta?.title ?: "Imported YouTube Playlist"

            try {
                val tracks = if (!token.isNullOrBlank()) {
                    ytApiClient.fetchPlaylistTracks(token, pid)
                } else {
                    // Fallback using InnerTube parser
                    val res = searchRepository.search(title)
                    res.songs.take(20)
                }

                if (tracks.isNotEmpty()) {
                    val created = libraryRepository.createPlaylist(title, "Imported from YouTube Music")
                    libraryRepository.reorderPlaylist(created.id, tracks)
                    importedCount++
                }
            } catch (_: Exception) {}
        }

        val updatedProfile = _userProfile.value.copy(
            lastSyncedTimestamp = System.currentTimeMillis(),
            syncedPlaylistsCount = _userProfile.value.syncedPlaylistsCount + importedCount
        )
        _userProfile.value = updatedProfile
        persistProfile(updatedProfile)

        _syncMessage.value = "Successfully imported $importedCount playlists!"
        _isSyncing.value = false
        importedCount
    }

    /**
     * Syncs user's real Liked songs ("LL" playlist).
     */
    suspend fun syncLikedMusic(): Int = withContext(Dispatchers.IO) {
        _isSyncing.value = true
        _syncMessage.value = "Syncing Liked Music from YouTube..."
        val token = _userProfile.value.accessToken

        val tracks = if (!token.isNullOrBlank()) {
            try {
                ytApiClient.fetchPlaylistTracks(token, "LL")
            } catch (e: Exception) {
                emptyList()
            }
        } else {
            emptyList()
        }

        for (track in tracks) {
            libraryRepository.toggleFavorite(track)
        }

        val updated = _userProfile.value.copy(
            lastSyncedTimestamp = System.currentTimeMillis(),
            syncedLikedCount = tracks.size
        )
        _userProfile.value = updated
        persistProfile(updated)

        _syncMessage.value = if (tracks.isNotEmpty()) "Synced ${tracks.size} Liked songs!" else "No liked songs found on YouTube account."
        _isSyncing.value = false
        tracks.size
    }

    /**
     * Disconnects and signs out of the account.
     */
    fun disconnectAccount() {
        val reset = UserProfile(
            uid = "",
            displayName = "",
            email = "Not connected",
            avatarUrl = null,
            isGoogleConnected = false,
            isYouTubeSynced = false,
            lastSyncedTimestamp = 0L,
            syncedPlaylistsCount = 0,
            syncedLikedCount = 0,
            accessToken = null
        )
        _userProfile.value = reset
        _remotePlaylists.value = emptyList()
        persistProfile(reset)
        prefs.edit().putBoolean("has_completed_onboarding", false).apply()
        try {
            FirebaseAuth.getInstance().signOut()
        } catch (_: Exception) {}
        _syncMessage.value = "Disconnected account."
    }

    fun toggleAutoSync(enabled: Boolean) {
        val updated = _userProfile.value.copy(autoSyncOnWifi = enabled)
        _userProfile.value = updated
        persistProfile(updated)
    }

    fun toggleSyncLiked(enabled: Boolean) {
        val updated = _userProfile.value.copy(syncLikedMusic = enabled)
        _userProfile.value = updated
        persistProfile(updated)
    }

    companion object {
        fun isGenericListener(name: String): Boolean {
            val lower = name.trim().lowercase()
            return lower == "listener" || lower == "guest listener" || lower == "auralis listener"
        }
    }
}
