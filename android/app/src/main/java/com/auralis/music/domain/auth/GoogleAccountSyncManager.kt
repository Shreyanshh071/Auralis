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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import androidx.room.withTransaction
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import com.auralis.music.domain.model.SavedArtist

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
            user?.uid?.let { com.auralis.music.data.datastore.OnboardingStore.onAuthenticated(it, newlyCreated = true) }

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
            user?.uid?.let { takeOverLocalLibrary(it) }
            backupLibraryToCloud()
        } catch (e: Exception) {
            val msg = e.localizedMessage ?: "Failed to create account"
            _syncMessage.value = msg
            throw RuntimeException(msg, e)
        } finally {
            _isSyncing.value = false
        }
    }

    /**
     * Sends a Firebase password reset email. Firebase does not report whether the address
     * has an account (to avoid leaking which emails are registered), so callers should show
     * the same confirmation regardless of the outcome, except for a malformed address.
     */
    suspend fun sendPasswordResetEmail(email: String) = withContext(Dispatchers.IO) {
        val trimmed = email.trim()
        if (trimmed.isBlank()) {
            throw IllegalArgumentException("Enter your email address first.")
        }
        try {
            FirebaseAuth.getInstance().sendPasswordResetEmail(trimmed).await()
        } catch (e: com.google.firebase.auth.FirebaseAuthInvalidCredentialsException) {
            throw IllegalArgumentException("That doesn't look like a valid email address.", e)
        }
        // FirebaseAuthInvalidUserException (no account for this email) and any other
        // failure are swallowed here on purpose: the UI shows one neutral message either way.
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
            com.auralis.music.data.datastore.OnboardingStore.onAuthenticated(user.uid, newlyCreated = false)
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
            restoreLibraryFromCloud()
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
            fbUser?.uid?.let { com.auralis.music.data.datastore.OnboardingStore.onAuthenticated(
                it, newlyCreated = authResult.additionalUserInfo?.isNewUser == true) }

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

            // Automatically restore cloud playlists and library
            restoreLibraryFromCloud()
        } catch (e: Exception) {
            val msg = "Google Sign-In failed: ${e.localizedMessage ?: e.message}"
            _syncMessage.value = msg
            throw RuntimeException(msg, e)
        } finally {
            _isSyncing.value = false
        }
    }

    /** One Room transaction over the library, so a restore lands in one step (see [AccountLibraryRestore]). */
    private val inTransaction: com.auralis.music.data.sync.InTransaction = { block ->
        com.auralis.music.data.local.AuralisDatabase.getInstance(context).withTransaction { block() }
    }

    /** True while a restore runs: a backup then would send the account a half-restored library. */
    @Volatile private var isRestoring = false

    /**
     * Restores the account onto this phone in one pass: playlists with all their songs, liked
     * songs, saved albums and artists (one transaction), and the listening stats (fetched at the
     * same time, also one transaction). Before, every row was its own write, so a new phone showed
     * playlists with 0 songs, likes arriving one by one, and stats counting up from 00:00, which
     * only started after the whole library was in.
     */
    suspend fun restoreLibraryFromCloud(): Boolean = withContext(Dispatchers.IO) {
        val fbUser = try { FirebaseAuth.getInstance().currentUser } catch (_: Exception) { null } ?: return@withContext false
        if (fbUser.isAnonymous) return@withContext false
        val uid = fbUser.uid
        _isSyncing.value = true
        _syncMessage.value = "Restoring library from your cloud account..."
        isRestoring = true
        var backupAfter = false

        try {
            val db = com.google.firebase.firestore.FirebaseFirestore.getInstance()
            val docSnap = db.collection("users").document(uid).get().await()

            com.auralis.music.data.datastore.OnboardingStore.restorePicks(
                uid, docSnap.data?.get("starterTaste") as? Map<*, *>)

            // Another account's library is on the phone: clear it here (it's in that account's
            // backup) before this account's lands, so accounts never mix.
            takeOverLocalLibrary(uid)

            coroutineScope {
                // Stats don't wait for the library: both are fetched and written side by side.
                val stats = async { com.auralis.music.data.sync.StatsCloudSync.restoreIfNeeded() }

                if (!docSnap.exists()) {
                    android.util.Log.d("CloudSync", "[CloudSync] No cloud backup found for user $uid. Backing up current local library to cloud...")
                    backupAfter = true
                } else {
                    val cloud = com.auralis.music.data.sync.CloudLibrary.parse(docSnap.data.orEmpty())
                    val restored = com.auralis.music.data.sync.AccountLibraryRestore.apply(libraryRepository, cloud, inTransaction)
                    rememberAccountItems(uid, restored.addedPlaylistIds, restored.addedFavoriteIds)
                    android.util.Log.d("CloudSync", "[CloudSync] Restored ${restored.playlists} playlists and ${restored.liked} favorites for user $uid in one pass")

                    val updated = _userProfile.value.copy(
                        lastSyncedTimestamp = System.currentTimeMillis(),
                        syncedPlaylistsCount = restored.playlists
                    )
                    _userProfile.value = updated
                    persistProfile(updated)
                }
                stats.await()
            }
            _syncMessage.value = "Your cloud playlists and library were successfully restored!"
            return@withContext true
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            android.util.Log.e("CloudSync", "[CloudSync Error] Restore failed: ${e.message}", e)
            _syncMessage.value = "Cloud restore notice: ${e.localizedMessage ?: e.message}"
            return@withContext false
        } finally {
            isRestoring = false
            _isSyncing.value = false
            if (backupAfter) backupLibraryToCloud()
            com.auralis.music.data.sync.StatsCloudSync.upload()
            com.auralis.music.data.sync.StatsCloudSync.startLiveUpdates()
        }
    }

    private fun accountItems(key: String): Set<String> = prefs.getStringSet(key, null)?.toSet().orEmpty()

    /** Adds to the record of what the account brought onto this phone (kept across restarts). */
    private fun rememberAccountItems(uid: String, playlists: Set<String>, favorites: Set<String>) {
        if (playlists.isEmpty() && favorites.isEmpty()) return
        prefs.edit()
            .putStringSet("account_playlists_$uid", HashSet(accountItems("account_playlists_$uid") + playlists))
            .putStringSet("account_favorites_$uid", HashSet(accountItems("account_favorites_$uid") + favorites))
            .commit()
    }

    private fun forgetAccountItems(uid: String) {
        prefs.edit().remove("account_playlists_$uid").remove("account_favorites_$uid").commit()
    }

    /**
     * After signing out of [uid]: takes off this phone the playlists and liked songs that account
     * brought here. What was here before signing in, or was made or imported here (by link,
     * Spotify, or picking YouTube Music playlists), stays. Listening stats stay. Runs only once
     * signed out, so the removal can never be backed up to the account.
     */
    suspend fun removeAccountItems(uid: String): Int = withContext(Dispatchers.IO) {
        val current = try { FirebaseAuth.getInstance().currentUser } catch (_: Exception) { null }
        if (current != null && !current.isAnonymous) return@withContext 0
        val removed = com.auralis.music.data.sync.AccountLibraryRestore.removeAccountItems(
            libraryRepository, accountItems("account_playlists_$uid"), accountItems("account_favorites_$uid"), inTransaction)
        forgetAccountItems(uid)
        android.util.Log.i("CloudSync", "[CloudSync] Signed out: removed $removed playlists the account brought here")
        removed
    }

    /**
     * Backs up local playlists, favorites, and saved artists to Firestore `/users/{uid}`.
     */
    suspend fun backupLibraryToCloud(): Boolean = withContext(Dispatchers.IO) {
        if (isDeletingAccount || isSwitchingAccount || isRestoring) return@withContext false
        val fbUser = try { FirebaseAuth.getInstance().currentUser } catch (_: Exception) { null } ?: return@withContext false
        if (fbUser.isAnonymous) return@withContext false
        val uid = fbUser.uid
        // The phone still holds another account's library (the switch hasn't cleared it yet).
        if (!com.auralis.music.data.sync.LocalDataOwner.belongsTo(uid)) return@withContext false

        try {
            val db = com.google.firebase.firestore.FirebaseFirestore.getInstance()
            val playlists = libraryRepository.getPlaylists().first()
            val favorites = libraryRepository.getFavoriteTracks().first()
            val savedArtists = libraryRepository.getSavedArtists().first()
            val savedAlbums = libraryRepository.getSavedAlbums().first()

            val playlistsData = playlists.map { p ->
                mapOf(
                    "id" to p.id,
                    "title" to p.title,
                    "description" to p.description,
                    "coverUrl" to p.coverUrl,
                    "createdAt" to p.createdAt,
                    "isCustom" to p.isCustom,
                    "tracks" to p.tracks.map { t ->
                        mapOf(
                            "id" to t.id,
                            "title" to t.title,
                            "artist" to t.artist,
                            "album" to t.album,
                            "thumbnail" to t.thumbnail,
                            "duration" to t.duration
                        )
                    }
                )
            }

            val favoritesData = favorites.map { t ->
                mapOf(
                    "id" to t.id,
                    "title" to t.title,
                    "artist" to t.artist,
                    "album" to t.album,
                    "thumbnail" to t.thumbnail,
                    "duration" to t.duration
                )
            }

            val savedArtistsData = savedArtists.map { a ->
                mapOf(
                    "id" to a.id,
                    "name" to a.name,
                    "thumbnail" to a.thumbnail,
                    "subscribers" to a.subscribers
                )
            }

            val savedAlbumsData = savedAlbums.map { a ->
                mapOf(
                    "id" to a.id,
                    "title" to a.title,
                    "artist" to a.artist,
                    "thumbnail" to a.thumbnail,
                    "trackCount" to a.trackCount,
                    "savedAt" to a.savedAt
                )
            }

            val now = System.currentTimeMillis()
            val docData = hashMapOf<String, Any?>(
                "playlists" to playlistsData,
                "favorites" to favoritesData,
                "savedArtists" to savedArtistsData,
                "savedAlbums" to savedAlbumsData,
                "updatedAt" to now,
                "playlistsUpdatedAt" to now,
                "favoritesUpdatedAt" to now,
                "schemaVersion" to 1
            )

            // Signed out (or restoring) while reading: never send that library to the account.
            val stillSignedIn = try { FirebaseAuth.getInstance().currentUser?.uid == uid } catch (_: Exception) { false }
            if (!stillSignedIn || isRestoring) return@withContext false
            db.collection("users").document(uid).set(docData, com.google.firebase.firestore.SetOptions.merge()).await()
            android.util.Log.d("CloudSync", "[CloudSync OK] Backed up ${playlists.size} playlists & ${favorites.size} favorites to cloud for user $uid")
            
            val updated = _userProfile.value.copy(
                lastSyncedTimestamp = now,
                syncedPlaylistsCount = playlists.size,
                syncedLikedCount = favorites.size
            )
            _userProfile.value = updated
            persistProfile(updated)

            return@withContext true
        } catch (e: Exception) {
            android.util.Log.e("CloudSync", "[CloudSync Error] Backup failed: ${e.message}", e)
            return@withContext false
        }
    }

    /**
     * Starts background observation of local library changes to keep cloud Firestore backup automatically up to date.
     */
    @OptIn(kotlinx.coroutines.FlowPreview::class)
    fun startContinuousCloudSync(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            val user = try { FirebaseAuth.getInstance().currentUser } catch (_: Exception) { null }
            if (user != null && !user.isAnonymous) {
                // Initial restore or backup on startup
                restoreLibraryFromCloud()
            }

            // Continuously observe library changes and debounced backup
            combine(
                libraryRepository.getPlaylists(),
                libraryRepository.getFavoriteTracks(),
                libraryRepository.getSavedArtists()
            ) { playlists, favorites, artists ->
                Triple(playlists, favorites, artists)
            }
            .debounce(3000L)
            .collect {
                val activeUser = try { FirebaseAuth.getInstance().currentUser } catch (_: Exception) { null }
                if (activeUser != null && !activeUser.isAnonymous) {
                    backupLibraryToCloud()
                }
            }
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
    /** True while [deleteAccount] runs, so no backup can re-create the data being deleted. */
    @Volatile var isDeletingAccount = false
        private set

    /** Whether the signed-in account uses email + password (vs Google), which decides how to re-verify. */
    fun isEmailPasswordAccount(): Boolean = try {
        FirebaseAuth.getInstance().currentUser?.providerData?.any { it.providerId == "password" } == true
    } catch (_: Exception) { false }

    /**
     * Deletes the signed-in account and everything backed up to it: listening stats
     * (users/{uid}/listening), the account document (playlists, liked songs, saved artists) and the
     * Firebase sign-in itself. Data on this phone is left alone.
     *
     * Firebase only deletes a sign-in that was used recently, so the caller re-verifies first with
     * [credential] (a fresh Google token or the account's password). Re-verifying before touching
     * any data means a failed check deletes nothing.
     */
    suspend fun deleteAccount(credential: com.google.firebase.auth.AuthCredential): Result<Unit> = withContext(Dispatchers.IO) {
        val user = try { FirebaseAuth.getInstance().currentUser } catch (_: Exception) { null }
        if (user == null || user.isAnonymous) return@withContext Result.failure(IllegalStateException("Not signed in"))
        val uid = user.uid
        isDeletingAccount = true
        com.auralis.music.data.sync.StatsCloudSync.paused = true
        try {
            user.reauthenticate(credential).await()

            val db = com.google.firebase.firestore.FirebaseFirestore.getInstance()
            val userDoc = db.collection("users").document(uid)
            // Batches are capped at 500 writes; a heavy listener has one stats document per day.
            val statsDocs = userDoc.collection("listening").get().await().documents
            for (chunk in statsDocs.chunked(400)) {
                val batch = db.batch()
                chunk.forEach { batch.delete(it.reference) }
                batch.commit().await()
            }
            userDoc.delete().await()
            user.delete().await()

            disconnectAccount()
            _syncMessage.value = "Your account and backed-up data were deleted."
            Result.success(Unit)
        } catch (e: Exception) {
            android.util.Log.e("CloudSync", "[DeleteAccount] Failed: ${e.message}", e)
            Result.failure(e)
        } finally {
            isDeletingAccount = false
            com.auralis.music.data.sync.StatsCloudSync.paused = false
        }
    }

    @Volatile private var isSwitchingAccount = false

    /**
     * Makes [uid] the owner of the library and listening data on this phone. If another account
     * owns it, that data is cleared from the phone first (it stays in that account's cloud backup
     * and returns when it signs in); data made while signed out is simply adopted. Downloads stay.
     */
    private suspend fun takeOverLocalLibrary(uid: String) {
        val owner = com.auralis.music.data.sync.LocalDataOwner
        if (owner.belongsTo(uid)) {
            owner.set(uid)
            return
        }
        android.util.Log.i("CloudSync", "[CloudSync] Account switch: clearing the previous account's library from this phone")
        isSwitchingAccount = true
        try {
            val playlists = libraryRepository.getPlaylists().first()
            val favorites = libraryRepository.getFavoriteTracks().first()
            val artists = libraryRepository.getSavedArtists().first()
            val albums = libraryRepository.getSavedAlbums().first()
            inTransaction {
                playlists.forEach { libraryRepository.deletePlaylist(it.id) }
                favorites.forEach { libraryRepository.setFavorite(it, false) }
                artists.forEach { libraryRepository.removeArtist(it.id) }
                albums.forEach { libraryRepository.removeAlbum(it.id) }
            }
            forgetAccountItems(owner.uid)
            com.auralis.music.data.sync.StatsCloudSync.clearLocalForAccountSwitch(uid)
            owner.set(uid)
        } finally {
            isSwitchingAccount = false
        }
    }

    /**
     * Uploads the latest library and listens before signing out, so nothing is left behind.
     * Returns whether the library reached the account.
     */
    suspend fun flushToCloud(): Boolean {
        val saved = backupLibraryToCloud()
        com.auralis.music.data.sync.StatsCloudSync.upload()
        return saved
    }

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
