package com.auralis.music.windows.profile

import com.auralis.music.domain.auth.UserProfile
import com.auralis.music.windows.together.FirestoreRest
import com.sun.jna.platform.win32.Crypt32Util
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.AtomicMoveNotSupportedException

/** Passwords are never saved. The refresh token is protected by the signed-in Windows user. */
class DesktopAccount(private val sessionFile: File, private val scope: CoroutineScope,
    private val api: FirestoreRest = FirestoreRest.fromResource(), private val restoreRetryMs: Long = 5_000L,
    /** Blank while the Cloudflare library rollout is off: the library then syncs through Firestore only. */
    private val libraryUrl: String = System.getProperty("auralis.librarySyncUrl").orEmpty(),
    /** Blank while account sync (settings, later listening history) is off: those stay on Firestore. */
    private val accountSyncUrl: String = System.getProperty("auralis.accountSyncUrl").orEmpty()) {
    data class State(val profile: UserProfile? = null, val busy: Boolean = false, val message: String? = null, val browser: Boolean = false, val syncMessage: String? = null)
    private val mutable = MutableStateFlow(State(busy = sessionFile.isFile))
    val state = mutable.asStateFlow()
    private var restoreJob: Job? = null
    init {
        if (sessionFile.isFile) restoreJob = scope.launch(Dispatchers.IO) {
            var savedToken: String? = null
            try {
                val isWindows = System.getProperty("os.name").orEmpty().lowercase().contains("win")
                val rawBytes = sessionFile.readBytes()
                val saved = if (isWindows) {
                    runCatching { String(Crypt32Util.cryptUnprotectData(rawBytes), Charsets.UTF_8) }.getOrElse { String(rawBytes, Charsets.UTF_8) }
                } else {
                    String(rawBytes, Charsets.UTF_8)
                }
                // Read the old token-only format as well as the protected profile snapshot.
                if (saved.startsWith("{")) {
                    val snapshot = org.json.JSONObject(saved)
                    savedToken = snapshot.getString("refreshToken")
                    snapshot.optJSONObject("profile")?.let { p ->
                        mutable.value = State(profile = UserProfile(p.getString("uid"), p.getString("displayName"),
                            p.getString("email"), p.optString("avatarUrl").takeIf(String::isNotBlank), p.optBoolean("google")))
                    }
                } else savedToken = saved
            } catch (_: Exception) { mutable.value = State(message = "Couldn't restore your sign-in. Please sign in again.") }
            savedToken?.let { initialToken ->
                var token = initialToken
                var attempt = 0
                while (isActive) {
                    try {
                        api.restoreAccount(token)
                        ensureActive()
                        token = api.accountRefreshToken()
                        save()
                        refreshProfile(); save()
                        break
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) {
                        val expired = e is FirestoreRest.ServiceException && e.status == 400 &&
                            listOf("INVALID_REFRESH_TOKEN", "TOKEN_EXPIRED", "USER_DISABLED", "USER_NOT_FOUND").any { e.message.orEmpty().contains(it) }
                        mutable.value = if (expired) State(message = "Your saved sign-in has expired. Please sign in again.")
                        else mutable.value.copy(busy = false, message = "Couldn't reconnect to your account. Retrying automatically.")
                        if (expired) break
                        delay((restoreRetryMs * (1L shl attempt.coerceAtMost(4))).coerceAtMost(60_000L))
                        attempt++
                    }
                }
            }
        }
    }
    fun signIn(email: String, password: String, create: Boolean) {
        if (mutable.value.busy) return
        restoreJob?.cancel()
        mutable.value = mutable.value.copy(busy = true, message = null)
        scope.launch(Dispatchers.IO) {
            try {
                api.signInEmail(email, password, create)
                save()
                refreshProfile()
                val isWindows = System.getProperty("os.name").orEmpty().lowercase().contains("win")
                try { save() } catch (_: Exception) {
                    val platform = if (isWindows) "Windows" else "Auralis"
                    mutable.value = mutable.value.copy(message = "Signed in for this session. $platform couldn't save the protected sign-in.")
                }
            } catch (e: Exception) { mutable.value = mutable.value.copy(busy = false, browser = false, message = errorMessage(e)) }
        }
    }
    val googleAvailable: Boolean get() = DesktopGoogleLogin.available
    private var googleJob: Job? = null
    fun signInGoogle() {
        if (mutable.value.busy) return
        restoreJob?.cancel()
        mutable.value = mutable.value.copy(busy = true, browser = true, message = "Complete sign-in in your browser")
        googleJob = scope.launch(Dispatchers.IO) {
            try {
                val idToken = DesktopGoogleLogin.idToken()
                val idTokenAvatar = runCatching {
                    val parts = idToken.split('.')
                    if (parts.size >= 2) {
                        val payload = String(java.util.Base64.getUrlDecoder().decode(parts[1]), Charsets.UTF_8)
                        org.json.JSONObject(payload).optString("picture").takeIf { it.isNotBlank() }
                    } else null
                }.getOrNull()
                api.signInGoogle(idToken)
                if (idTokenAvatar != null) {
                    mutable.value = mutable.value.copy(
                        profile = (mutable.value.profile ?: UserProfile()).copy(avatarUrl = idTokenAvatar)
                    )
                }
                save()
                refreshProfile()
                val isWindows = System.getProperty("os.name").orEmpty().lowercase().contains("win")
                try { save() } catch (_: Exception) {
                    val platform = if (isWindows) "Windows" else "Auralis"
                    mutable.value = mutable.value.copy(message = "Signed in for this session. $platform couldn't save the protected sign-in.")
                }
            } catch (_: CancellationException) { mutable.value = mutable.value.copy(busy = false, browser = false, message = "Google sign-in cancelled") }
            catch (e: Exception) { mutable.value = mutable.value.copy(busy = false, browser = false, message = errorMessage(e)) }
        }
    }
    fun cancelGoogle() { googleJob?.cancel() }
    private suspend fun refreshProfile() {
        val profile = api.accountProfile()
        currentCoroutineContext().ensureActive()
        val email = profile.optString("email")
        val providers = profile.optJSONArray("providerUserInfo")
        val isGoogle = (0 until (providers?.length() ?: 0)).any {
            providers?.optJSONObject(it)?.optString("providerId") == "google.com"
        } || mutable.value.browser
        val resolvedAvatar = profile.optString("photoUrl").takeIf { it.isNotBlank() }
            ?: (0 until (providers?.length() ?: 0)).firstNotNullOfOrNull {
                providers?.optJSONObject(it)?.optString("photoUrl")?.takeIf { u -> u.isNotBlank() }
            }
            ?: mutable.value.profile?.avatarUrl
        val resolvedName = profile.optString("displayName").ifBlank {
            (0 until (providers?.length() ?: 0)).firstNotNullOfOrNull {
                providers?.optJSONObject(it)?.optString("displayName")?.takeIf { n -> n.isNotBlank() }
            } ?: email.substringBefore('@')
        }
        mutable.value = State(
            profile = UserProfile(
                uid = api.uid,
                displayName = resolvedName,
                email = email.ifBlank {
                    (0 until (providers?.length() ?: 0)).firstNotNullOfOrNull {
                        providers?.optJSONObject(it)?.optString("email")?.takeIf { e -> e.isNotBlank() }
                    }.orEmpty()
                },
                avatarUrl = resolvedAvatar,
                isGoogleConnected = isGoogle
            ),
            busy = false,
            browser = false,
            message = "Signed in as $resolvedName."
        )
    }
    private fun save() {
        val snapshot = org.json.JSONObject().put("refreshToken", api.accountRefreshToken())
        mutable.value.profile?.takeIf { it.uid == api.uid }?.let { p -> snapshot.put("profile", org.json.JSONObject().put("uid", p.uid)
            .put("displayName", p.displayName).put("email", p.email).put("avatarUrl", p.avatarUrl.orEmpty())
            .put("google", p.isGoogleConnected)) }
        val isWindows = System.getProperty("os.name").orEmpty().lowercase().contains("win")
        val dataBytes = snapshot.toString().toByteArray(Charsets.UTF_8)
        val protected = if (isWindows) runCatching { Crypt32Util.cryptProtectData(dataBytes) }.getOrDefault(dataBytes) else dataBytes
        sessionFile.parentFile?.mkdirs()
        val temporary = File(sessionFile.parentFile, sessionFile.name + ".tmp")
        temporary.outputStream().use { it.write(protected); it.fd.sync() }
        try {
            Files.move(temporary.toPath(), sessionFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), sessionFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
    /** Dismisses the account's status line (e.g. "Signed in as …"). */
    fun clearMessage() { mutable.value = mutable.value.copy(message = null) }

    /** The account's Firestore access (Auralis Connect); only usable while signed in. */
    internal val firestore: FirestoreRest get() = api

    /** Runs while still signed in, just before signing out (Connect leaves the account's session). */
    @Volatile var beforeSignOut: (suspend () -> Unit)? = null

    fun signOut() {
        if (mutable.value.busy) return
        restoreJob?.cancel()
        mutable.value = mutable.value.copy(busy = true)
        scope.launch(Dispatchers.IO) {
            runCatching { withTimeoutOrNull(5_000L) { beforeSignOut?.invoke() } }
            libraryCloud.stopAll()
            try { Files.deleteIfExists(sessionFile.toPath()); api.forgetAccount(); mutable.value = State() }
            catch (_: Exception) { mutable.value = mutable.value.copy(busy = false, message = "Couldn't remove the saved sign-in. Try again.") }
        }
    }
    fun reset(email: String) {
        if (mutable.value.busy) return
        mutable.value = mutable.value.copy(busy = true)
        scope.launch(Dispatchers.IO) {
            try { api.resetPassword(email); mutable.value = mutable.value.copy(busy = false, message = "If this address has an account, check its inbox for a password reset link.") }
            catch (e: Exception) { mutable.value = mutable.value.copy(busy = false, browser = false, message = errorMessage(e)) }
        }
    }
    /**
     * Brings the account's phone backup onto this PC: playlists (with their songs), liked songs,
     * saved albums and artists from `users/{uid}`, and listening stats from `users/{uid}/listening`
     * (the phone's StatsCloudSync layout). Read only: nothing on the account is changed. Returns a
     * one-line summary, or null when signed out or the account has no backup.
     */
    suspend fun pullCloudBackup(
        library: com.auralis.music.domain.repository.LibraryRepository,
        stats: com.auralis.music.desktop.library.DesktopListeningStats,
        history: com.auralis.music.domain.repository.HistoryRepository? = null
    ): String? = withContext(Dispatchers.IO) { supervisorScope {
        val uid = mutable.value.profile?.uid?.takeIf { it.isNotBlank() } ?: return@supervisorScope null
        val pullStarted = System.currentTimeMillis()
        // Home must not queue behind the library document or wait for every historical page.
        val listening = async {
            // On the account sync service, history comes from there (syncListensCloud), not Firestore.
            if (listensOnCloud()) return@async emptyList<Pair<String, org.json.JSONObject>>() to 0
            var recentAdded = 0
            if (stats.listens.value.isEmpty()) {
                val recent = try { api.listRecentDays("users/$uid/listening") }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { emptyList() }
                ensureActive()
                if (mutable.value.profile?.uid != uid) return@async emptyList<Pair<String, org.json.JSONObject>>() to 0
                recentAdded = stats.importListens(cloudListens(recent))
                history?.importHistory(cloudListens(recent).map { com.auralis.music.domain.model.HistoryEntry(it.track, it.startedAt) }, uid)
            }
            val days = api.list("users/$uid/listening")
            ensureActive()
            if (mutable.value.profile?.uid != uid) return@async emptyList<Pair<String, org.json.JSONObject>>() to 0
            val added = stats.importListens(cloudListens(days))
            history?.importHistory(cloudListens(days).map { com.auralis.music.domain.model.HistoryEntry(it.track, it.startedAt) }, uid)
            days to (recentAdded + added)
        }
        fun track(o: org.json.JSONObject) = com.auralis.music.domain.model.Track(
            id = o.optString("id"), title = o.optString("title").ifBlank { "Unknown Track" },
            artist = o.optString("artist").ifBlank { "Unknown Artist" }, album = o.optString("album").takeIf { it.isNotBlank() },
            thumbnail = o.optString("thumbnail"), duration = o.optLong("duration"))
        fun objects(a: org.json.JSONArray?) = (0 until (a?.length() ?: 0)).mapNotNull { a!!.optJSONObject(it) }

        var playlists = 0; var liked = 0
        // Once the account's library lives elsewhere (or is being moved), the Firestore document is a
        // frozen copy: applying it here would bring back what other devices have since changed.
        val doc = if (libraryCloud.route(uid, check = true) != com.auralis.music.data.sync.LibraryRoute.Firestore) null
        else try { api.get("users/$uid") } catch (e: Exception) {
            if (e is CancellationException) throw e
            // Still let independently fetched listening history populate Home.
            try { listening.await() } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) {}
            throw e
        }
        ensureActive()
        if (mutable.value.profile?.uid != uid) return@supervisorScope null
        if (doc != null) {
            // What this PC already had: only what the pull adds is the account's, and goes on sign-out.
            val localPlaylists = library.getPlaylists().first()
            val localFavoriteIds = library.getFavoriteTracks().first().map { it.id }.toSet()
            val addedPlaylists = mutableSetOf<String>()
            val addedFavorites = mutableSetOf<String>()
            val catchUpDone = syncMarks.getString("account_items_v1_$uid", "") == "1"
            val serviceLinks = serviceLinkedPlaylists()
            val cloudSync = cloudLibrary(doc)
            val agreed = com.auralis.music.data.sync.SyncBaseline.decode(syncMarks.getString(baselineKey(uid), ""))
            val localFavorites = library.getFavoriteTracks().first().map { it.id }.toSet()
            val localAlbums = library.getSavedAlbums().first().map { it.id }.toSet()
            val localArtists = library.getSavedArtists().first().map { it.id }.toSet()
            for (p in objects(doc.optJSONArray("playlists"))) {
                val title = p.optString("title").ifBlank { "Restored Playlist" }
                val description = p.optString("description").takeIf { it.isNotBlank() }
                val cover = p.optString("coverUrl").takeIf { it.isNotBlank() }
                val id = p.optString("id").takeIf { it.isNotBlank() }
                val tracks = objects(p.optJSONArray("tracks")).map(::track).filter { it.id.isNotBlank() }
                // Deleted on a device, or removed here and not uploaded yet: pushLibrary settles it.
                if (id != null && (id in cloudSync.deleted ||
                    (agreed?.playlists?.containsKey(id) == true && localPlaylists.none { it.id == id }))) continue
                // The same playlist under another id (same name AND the very same songs) is not added
                // twice. A same-named playlist with different songs (99 vs 100) is its own playlist.
                if (localPlaylists.any { it.id != id && sameNameAndSongs(it, title, tracks) }) continue
                val existedHere = id != null && localPlaylists.any { it.id == id }
                // Service sync owns this playlist's baseline and pending edits.
                // A stale phone/cloud backup must not erase an unuploaded song.
                if (existedHere && id in serviceLinks) { playlists++; continue }
                val target = if (id != null) library.restorePlaylist(id, title, description, cover) else library.createPlaylist(title, description, cover)
                if (tracks.isNotEmpty()) library.replacePlaylistTracks(target.id, tracks)
                if (!existedHere) addedPlaylists += target.id
                // Catch-up for accounts pulled before this record existed: a playlist made on the
                // phone (not an import, which this PC may have made itself) is the account's.
                else if (!catchUpDone && !target.id.startsWith("imported:")) addedPlaylists += target.id
                playlists++
            }
            for (f in objects(doc.optJSONArray("favorites"))) {
                val t = track(f)
                if (t.id.isNotBlank() && (agreed == null || t.id in localFavorites || t.id !in agreed.favorites)) {
                    library.setFavorite(t, true); liked++
                    if (t.id !in localFavoriteIds) addedFavorites += t.id
                }
            }
            rememberAccountItems(uid, addedPlaylists, addedFavorites)
            syncMarks.putStrings(mapOf("account_items_v1_$uid" to "1"))
            for (a in objects(doc.optJSONArray("savedAlbums"))) {
                val id = a.optString("id"); val title = a.optString("title")
                if (id.isNotBlank() && title.isNotBlank() && (agreed == null || id in localAlbums || id !in agreed.albums)) library.saveAlbum(com.auralis.music.domain.model.SavedAlbum(id, title,
                    a.optString("artist").takeIf { it.isNotBlank() }, a.optString("thumbnail").takeIf { it.isNotBlank() },
                    a.optInt("trackCount").takeIf { it > 0 }, a.optLong("savedAt").takeIf { it > 0 } ?: System.currentTimeMillis()))
            }
            for (a in objects(doc.optJSONArray("savedArtists"))) {
                val id = a.optString("id"); val name = a.optString("name")
                if (id.isNotBlank() && name.isNotBlank() && (agreed == null || id in localArtists || id !in agreed.artists)) library.saveArtist(com.auralis.music.domain.model.SavedArtist(id, name,
                    a.optString("thumbnail").takeIf { it.isNotBlank() }, a.optString("subscribers").takeIf { it.isNotBlank() }))
            }
        }
        // Listening stats: one document per UTC day, events keyed "<startMs>_<trackId>".
        val (days, newListens) = listening.await()
        ensureActive()
        if (mutable.value.profile?.uid != uid) return@supervisorScope null
        syncMarks.putStrings(mapOf("pulled_at_$uid" to pullStarted.toString()))
        pulledUid = uid
        if (doc == null && days.isEmpty()) return@supervisorScope null
        "Synced from your account: $playlists playlists, $liked liked songs, $newListens new listens"
    } }

    private fun marks(key: String): Set<String> =
        syncMarks.getString(key, "").orEmpty().split('\n').filter { it.isNotBlank() }.toSet()

    /** Adds to the record of what the account brought onto this PC (kept across sessions). */
    private fun rememberAccountItems(uid: String, playlists: Set<String>, favorites: Set<String>) {
        if (playlists.isEmpty() && favorites.isEmpty()) return
        syncMarks.putStrings(mapOf(
            "account_playlists_$uid" to (marks("account_playlists_$uid") + playlists).joinToString("\n"),
            "account_favorites_$uid" to (marks("account_favorites_$uid") + favorites).joinToString("\n")))
    }

    /**
     * After signing out of [uid]: takes off this PC the playlists and liked songs that came from that
     * account. What was here before, or was imported here (by link, Spotify, or picking YouTube Music
     * playlists), stays. Returns how many playlists were removed.
     */
    suspend fun removeAccountItems(uid: String, library: com.auralis.music.domain.repository.LibraryRepository): Int = withContext(Dispatchers.IO) {
        val playlists = marks("account_playlists_$uid")
        val favorites = marks("account_favorites_$uid")
        val present = library.getPlaylists().first().map { it.id }.toSet()
        playlists.filter { it in present }.forEach { library.deletePlaylist(it) }
        library.getFavoriteTracks().first().filter { it.id in favorites }.forEach { library.setFavorite(it, false) }
        syncMarks.putStrings(mapOf("account_playlists_$uid" to "", "account_favorites_$uid" to "",
            "pulled_at_$uid" to "0", "account_items_v1_$uid" to "", baselineKey(uid) to ""))
        playlists.count { it in present }
    }

    private val librarySyncLock = Mutex()

    /** Retry failed restores, then merge remote edits even when this device has been idle. */
    internal suspend fun syncCloudLibrary(
        library: com.auralis.music.domain.repository.LibraryRepository,
        stats: com.auralis.music.desktop.library.DesktopListeningStats,
        history: com.auralis.music.domain.repository.HistoryRepository? = null
    ): Boolean = librarySyncLock.withLock {
        val uid = mutable.value.profile?.uid?.takeIf { it.isNotBlank() } ?: return@withLock false
        try {
            if (pulledUid != uid) pullCloudBackup(library, stats, history)
            val saved = pushLibrary(library)
            if (saved && mutable.value.profile?.uid == uid) mutable.value = mutable.value.copy(syncMessage = null)
            saved
        } catch (e: CancellationException) { throw e }
        catch (e: com.auralis.music.data.sync.LibraryAuthorityChanged) {
            // The account moved while this device was syncing: the next sync asks where it is now.
            if (mutable.value.profile?.uid == uid) mutable.value = mutable.value.copy(syncMessage = WAITING_MOVE)
            false
        }
        catch (e: Exception) {
            if (mutable.value.profile?.uid == uid) reportSyncFailure(e)
            false
        }
    }

    private fun reportSyncFailure(e: Exception) {
        mutable.value = mutable.value.copy(syncMessage =
            if (e is FirestoreRest.ServiceException && e.status == 429)
                "Cloud sync is paused: Firebase quota exceeded. Your local library is safe. Retrying automatically."
            else if (e is com.auralis.music.data.sync.LibraryUpdateRequired)
                "Update Auralis to keep syncing your library. Your local changes are saved."
            else "Couldn't sync your cloud library. Your local changes are saved. Retrying automatically.")
        com.auralis.music.util.Log.w("AccountSync", "Library sync failed: ${e.message}")
    }

    /** Which account's backup has been read this session; uploads wait for it (see [pushLibrary]). */
    @Volatile private var pulledUid: String? = null
    private val syncMarks by lazy {
        com.auralis.music.util.PropertiesFileKeyValueStore(File(sessionFile.parentFile, "account-sync.properties"))
    }

    private fun baselineKey(uid: String) = "library_baseline_$uid"

    /**
     * The account sync service client: one client and one socket for the library and every other
     * topic. With both endpoints blank (rollout off) it is never contacted.
     */
    private val libraryCloud by lazy {
        val device = syncMarks.getString("library_device", "")?.takeIf { it.isNotBlank() }
            ?: java.util.UUID.randomUUID().toString().also { syncMarks.putStrings(mapOf("library_device" to it)) }
        com.auralis.music.data.sync.LibraryCloudClient(libraryUrl.ifBlank { accountSyncUrl }, device,
            com.auralis.music.data.sync.FileLibraryCloudStore(File(sessionFile.parentFile, "library-cloud")), okhttp3.OkHttpClient(), scope,
            log = { com.auralis.music.util.Log.i("AccountSync", it) }, libraryEnabled = libraryUrl.isNotBlank(),
            token = { uid, refresh ->
                // Only the signed-in account's token, and only for that account's library.
                withContext(Dispatchers.IO) { runCatching { api.accountIdTokenFor(uid, refresh) }.getOrNull() }
            })
    }

    // ---- Listening history on the account sync service (account-sync-proposal.md) -------------------

    private val cloudStore by lazy { com.auralis.music.data.sync.FileLibraryCloudStore(File(sessionFile.parentFile, "library-cloud")) }
    private val listensCloud by lazy { com.auralis.music.data.sync.ListensCloudSync(cloudStore) { uid, frame -> libraryCloud.call(uid, frame) } }
    private val recentHistoryBatcher by lazy { com.auralis.music.data.sync.RecentHistoryBatcher(cloudStore) }
    private val listenBatcher by lazy { com.auralis.music.data.sync.ListenBatcher(cloudStore) }
    private val listenDevice by lazy {
        syncMarks.getString("library_device", "")?.takeIf { it.isNotBlank() }
            ?: java.util.UUID.randomUUID().toString().also { syncMarks.putStrings(mapOf("library_device" to it)) }
    }
    @Volatile private var listensStartedFor: String? = null

    private fun events(days: List<Pair<String, org.json.JSONObject>>): List<com.auralis.music.data.sync.ListenEvent> = days.flatMap { (_, fields) ->
        val map = fields.optJSONObject("events") ?: return@flatMap emptyList()
        map.keys().asSequence().mapNotNull { map.optJSONObject(it)?.let(com.auralis.music.data.sync.ListenEvent::fromJson) }.toList()
    }

    private suspend fun applyListens(pulled: com.auralis.music.data.sync.ListensPulled,
        stats: com.auralis.music.desktop.library.DesktopListeningStats, history: com.auralis.music.domain.repository.HistoryRepository?, uid: String) {
        if (pulled.clearedBefore > 0) stats.removeBefore(pulled.clearedBefore)
        val listens = pulled.events.filter { it.device != listenDevice && it.startedAt >= pulled.clearedBefore }.map { e ->
            com.auralis.music.desktop.library.DesktopListeningStats.Listen("cloud_${e.startedAt}_${e.trackId}",
                com.auralis.music.domain.model.Track(id = e.trackId, title = e.title ?: "Unknown Track", artist = e.artist ?: "Unknown Artist",
                    album = e.album, thumbnail = e.thumbnail.orEmpty(), duration = e.duration,
                        artists = com.auralis.music.domain.model.TrackArtists.decode(e.artistsJson)), e.startedAt, e.listenedMs)
        }
        if (listens.isEmpty()) return
        stats.importListens(listens)
        history?.importHistory(listens.map { com.auralis.music.domain.model.HistoryEntry(it.track, it.startedAt) }, uid)
        recentHistoryBatcher.markSent(uid, pulled.events)
    }

    /**
     * Whether listening history uses the account sync service for this account (the build names it
     * and the service serves the topic). False means today's Firestore path, unchanged.
     */
    suspend fun listensOnCloud(): Boolean {
        val uid = mutable.value.profile?.uid?.takeIf { it.isNotBlank() } ?: return false
        if (!accountSyncOn) return false
        return runCatching { listensCloud.status(uid) != null }.getOrDefault(false)
    }

    /**
     * Listening history through the service:
     * - once per session: the one-time copy of the Firestore history (if the account has none yet),
     *   or, while old app versions may exist, a start-time check of Firestore for their listens;
     * - other devices' changes since this device's cursor;
     * - this device's finished listens, each once, mirrored to Firestore while the bridge is on.
     * Returns how many listens were sent.
     */
    suspend fun syncListensCloud(stats: com.auralis.music.desktop.library.DesktopListeningStats,
        history: com.auralis.music.domain.repository.HistoryRepository? = null,
        includePlaying: Boolean = false): Int = withContext(Dispatchers.IO) {
        val uid = mutable.value.profile?.uid?.takeIf { it.isNotBlank() } ?: return@withContext 0
        if (!accountSyncOn) return@withContext 0
        watchListens(stats, history)
        val status = listensCloud.status(uid) ?: return@withContext 0
        val bridge = status.optBoolean("bridge", true)
        if (listensStartedFor != uid) {
            try {
                if (!status.optBoolean("imported")) {
                    listensCloud.push(uid, events(api.list("users/$uid/listening")), import = true)
                    listensCloud.markImported(uid)
                } else if (bridge) {
                    val since = syncMarks.getString("listens_bridge_$uid", "0")?.toLongOrNull() ?: 0L
                    val started = System.currentTimeMillis()
                    val fromOldApps = events(api.queryGreaterThan("users/$uid", "listening", "updatedAt", since - 10 * 60_000L)).filter { it.device == null }
                    if (fromOldApps.isNotEmpty()) listensCloud.push(uid, fromOldApps, legacy = true)
                    syncMarks.putStrings(mapOf("listens_bridge_$uid" to started.toString()))
                }
                listensStartedFor = uid
            } catch (e: CancellationException) { throw e }
            catch (e: com.auralis.music.data.sync.ListensImportLater) { com.auralis.music.util.Log.i("AccountSync", "History copy continues tomorrow") }
            catch (e: Exception) { com.auralis.music.util.Log.w("AccountSync", "Listening history bridge will retry: ${e.javaClass.simpleName}") }
        }
        // After a bridge restore: copy to Firestore what updated apps recorded while it was retired.
        if (bridge && status.has("backfillFrom")) {
            val from = status.optLong("backfillFrom")
            runCatching {
                listensCloud.daysSince(uid, from) { _, list -> if (list.isNotEmpty()) mirrorListens(uid, list) }
                listensCloud.markBackfilled(uid, from)
            }.onFailure { com.auralis.music.util.Log.w("AccountSync", "Listening backfill to Firestore will retry: ${it.javaClass.simpleName}") }
        }
        if (mutable.value.profile?.uid != uid) return@withContext 0
        listensCloud.pull(uid) { applyListens(it, stats, history, uid) }
        val recent = recentHistoryBatcher.ready(uid, history?.getHistory()?.first().orEmpty(), listenDevice)
        if (recent.isNotEmpty()) {
            listensCloud.push(uid, recent)
            recentHistoryBatcher.markSent(uid, recent)
            com.auralis.music.util.Log.i("AccountSync", "Sent ${recent.size} recent history entries to the account")
        }
        val now = System.currentTimeMillis()
        val mine = stats.listens.value.filter { !it.id.startsWith("cloud_") && it.timeMs > 0 }.map { l ->
            com.auralis.music.data.sync.ListenEvent(l.track.id, l.startedAt, l.timeMs, l.track.title, l.track.artist, l.track.album,
                l.track.thumbnail, l.track.duration, listenDevice,
                com.auralis.music.domain.model.TrackArtists.encode(
                    com.auralis.music.data.network.OfficialArtistResolver.cached(l.track.id) ?: l.track.artists))
        }
        val ready = listenBatcher.ready(uid, mine, now, includePlaying = includePlaying)
        if (ready.isEmpty()) return@withContext recent.size
        listensCloud.push(uid, ready)
        listenBatcher.markSent(uid, ready, now)
        if (bridge) runCatching { mirrorListens(uid, ready) }
            .onFailure { com.auralis.music.util.Log.w("AccountSync", "Listening mirror to Firestore failed: ${it.javaClass.simpleName}") }
        ready.size + recent.size
    }

    private val listensSyncLock = Mutex()

    /**
     * [syncListensCloud] on the app's own scope. Run from a screen's effect it was cancelled whenever
     * that effect restarted (four times in the first 30 s of a launch), and the first run, which copies
     * the whole Firestore history, never finished, so nothing went up or came down. One run at a time:
     * a request while one is running is covered by it or by the next.
     */
    fun syncListensCloudInBackground(stats: com.auralis.music.desktop.library.DesktopListeningStats,
        history: com.auralis.music.domain.repository.HistoryRepository? = null): Job = scope.launch {
        if (!listensSyncLock.tryLock()) return@launch
        try {
            val sent = syncListensCloud(stats, history, includePlaying = true)
            if (sent > 0) com.auralis.music.util.Log.i("AccountSync", "Sent $sent measured listens to the account")
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { com.auralis.music.util.Log.w("AccountSync", "Listening sync failed: ${e.javaClass.simpleName}: ${e.message}") }
        finally { listensSyncLock.unlock() }
    }

    /** Register before discovery or uploads: an idle receiver must not depend on a screen's startup lookup. */
    internal fun watchListens(stats: com.auralis.music.desktop.library.DesktopListeningStats,
        history: com.auralis.music.domain.repository.HistoryRepository? = null) {
        if (!accountSyncOn) return
        val uid = mutable.value.profile?.uid?.takeIf { it.isNotBlank() } ?: return
        libraryCloud.listen(uid, "listens") { frame ->
            if (mutable.value.profile?.uid != uid) return@listen
            try {
                listensChanged(frame, stats, history)
                if (frame != null) com.auralis.music.util.Log.i("AccountSync", "Applied listening history notification")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                com.auralis.music.util.Log.w("AccountSync", "Listening notification failed: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    /** A change another device made: applied from the frame when it follows on, otherwise pulled. */
    suspend fun listensChanged(frame: org.json.JSONObject?, stats: com.auralis.music.desktop.library.DesktopListeningStats,
        history: com.auralis.music.domain.repository.HistoryRepository? = null) = withContext(Dispatchers.IO) {
        val uid = mutable.value.profile?.uid?.takeIf { it.isNotBlank() } ?: return@withContext
        val apply: suspend (com.auralis.music.data.sync.ListensPulled) -> Unit = { applyListens(it, stats, history, uid) }
        if (frame == null || !listensCloud.accept(uid, frame, apply)) listensCloud.pull(uid, apply)
    }

    /** "Clear listening stats" here clears the whole account's history from now back. */
    suspend fun clearListensCloud(before: Long = System.currentTimeMillis()) = withContext(Dispatchers.IO) {
        val uid = mutable.value.profile?.uid?.takeIf { it.isNotBlank() }
            ?: error("Sign in before clearing account history.")
        check(accountSyncOn) { "Account history sync is not enabled." }
        listensCloud.clear(uid, before)
        Unit
    }

    /** The finished batch, written per listen key to Firestore day documents for old app versions (writes only). */
    private fun mirrorListens(uid: String, events: List<com.auralis.music.data.sync.ListenEvent>) {
        val day = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(java.time.ZoneOffset.UTC)
        for ((date, list) in events.groupBy { day.format(java.time.Instant.ofEpochMilli(it.startedAt)) }) {
            val map = org.json.JSONObject()
            val mask = mutableListOf<String>()
            for (e in list) {
                map.put(e.key, e.toJson())
                mask += "events.`" + e.key.replace("\\", "\\\\").replace("`", "\\`") + "`"
            }
            api.write("users/$uid/listening/$date", org.json.JSONObject().put("events", map).put("updatedAt", System.currentTimeMillis()),
                mask = mask + "updatedAt")
        }
    }

    /** Whether settings (and later listening history) sync through the account sync service. */
    internal val accountSyncOn: Boolean get() = accountSyncUrl.isNotBlank()

    /** The account's sync service client, shared with settings sync. */
    internal val accountSync: com.auralis.music.data.sync.LibraryCloudClient get() = libraryCloud

    /** Called when another device changed the library, so the app can sync without polling. */
    @Volatile var onLibraryChanged: (suspend () -> Unit)? = null

    /** Whether this account's library is on Cloudflare: remote edits then arrive by notification. */
    val libraryNotifies: Boolean get() = mutable.value.profile?.uid?.let {
        libraryCloud.enabled && libraryCloud.cached(it)?.state == com.auralis.music.data.sync.LibraryAuthority.CLOUDFLARE
    } == true

    private fun serviceLinkedPlaylists() = com.auralis.music.data.playlistsync.PlaylistSyncStore(
        com.auralis.music.util.PropertiesFileKeyValueStore(File(sessionFile.parentFile, "playlist-sync.properties"), synchronous = true))
        .links.value.filter { it.enabled }.map { it.localId }.toSet()

    /** The account document as the shape every device merges. */
    private fun cloudLibrary(doc: org.json.JSONObject?): com.auralis.music.data.sync.SyncedLibrary {
        fun objects(a: org.json.JSONArray?) = (0 until (a?.length() ?: 0)).mapNotNull { a!!.optJSONObject(it) }
        fun text(o: org.json.JSONObject, key: String) = o.optString(key).takeIf { it.isNotBlank() && it != "null" }
        fun track(o: org.json.JSONObject) = com.auralis.music.domain.model.Track(
            id = o.optString("id"), title = o.optString("title").ifBlank { "Unknown Track" },
            artist = o.optString("artist").ifBlank { "Unknown Artist" }, album = text(o, "album"),
            thumbnail = o.optString("thumbnail"), duration = o.optLong("duration"))
        val sync = doc?.optJSONObject("librarySync")
        val pins = sync?.optJSONArray("pins")
        return com.auralis.music.data.sync.SyncedLibrary(
            playlists = objects(doc?.optJSONArray("playlists")).mapNotNull { p ->
                val id = text(p, "id") ?: return@mapNotNull null
                com.auralis.music.domain.model.Playlist(id, p.optString("title").ifBlank { "Restored Playlist" },
                    text(p, "description"), text(p, "coverUrl"),
                    objects(p.optJSONArray("tracks")).map(::track).filter { it.id.isNotBlank() },
                    createdAt = p.optLong("createdAt"), isCustom = p.optBoolean("isCustom", true))
            },
            favorites = objects(doc?.optJSONArray("favorites")).map(::track).filter { it.id.isNotBlank() },
            albums = objects(doc?.optJSONArray("savedAlbums")).mapNotNull { a ->
                val id = text(a, "id") ?: return@mapNotNull null
                val title = text(a, "title") ?: return@mapNotNull null
                com.auralis.music.domain.model.SavedAlbum(id, title, text(a, "artist"), text(a, "thumbnail"),
                    a.optInt("trackCount").takeIf { it > 0 }, a.optLong("savedAt").takeIf { it > 0 } ?: System.currentTimeMillis())
            },
            artists = objects(doc?.optJSONArray("savedArtists")).mapNotNull { a ->
                val id = text(a, "id") ?: return@mapNotNull null
                val name = text(a, "name") ?: return@mapNotNull null
                com.auralis.music.domain.model.SavedArtist(id, name, text(a, "thumbnail"), text(a, "subscribers"))
            },
            pins = (0 until (pins?.length() ?: 0)).mapNotNull { pins?.optString(it)?.takeIf(String::isNotBlank) },
            pinsAt = sync?.optLong("pinsAt") ?: 0L,
            deleted = objects(sync?.optJSONArray("deleted")).mapNotNull { d ->
                (text(d, "id") ?: return@mapNotNull null) to d.optLong("at")
            }.toMap()
        )
    }

    /**
     * Merges this PC's library with the account's (see AccountLibraryMerge) and applies what other
     * devices changed. Only after this session read the account's backup ([pullCloudBackup]). The
     * merge never drops what another device added, and pins set on phones stay in the account.
     */
    suspend fun pushLibrary(library: com.auralis.music.domain.repository.LibraryRepository): Boolean = withContext(Dispatchers.IO) {
        val uid = mutable.value.profile?.uid?.takeIf { it.isNotBlank() } ?: return@withContext false
        if (pulledUid != uid) return@withContext false
        suspend fun local(): com.auralis.music.data.sync.SyncedLibrary = com.auralis.music.data.sync.SyncedLibrary(
            library.getPlaylists().first(), library.getFavoriteTracks().first(), library.getSavedAlbums().first(), library.getSavedArtists().first())
        fun duplicates(mine: com.auralis.music.data.sync.SyncedLibrary): (com.auralis.music.domain.model.Playlist) -> Boolean =
            { cloud -> mine.playlists.any { it.id != cloud.id && sameNameAndSongs(it, cloud.title, cloud.tracks) } }
        val baseline = com.auralis.music.data.sync.SyncBaseline.decode(syncMarks.getString(baselineKey(uid), ""))
        val plan: com.auralis.music.data.sync.SyncPlan
        var accountSaved = true
        when (val route = libraryCloud.route(uid)) {
            is com.auralis.music.data.sync.LibraryRoute.Wait -> {
                // Nothing is uploaded anywhere: edits stay in the local library until the account's
                // library has a home again. Not knowing is never treated as "use Firestore".
                if (mutable.value.profile?.uid == uid) mutable.value = mutable.value.copy(syncMessage = if (route.migrating) WAITING_MOVE else WAITING_CHECK)
                return@withContext false
            }
            is com.auralis.music.data.sync.LibraryRoute.Cloudflare -> {
                val result = libraryCloud.sync(uid, route.epoch, baseline, serviceLinkedPlaylists(), ::local, ::duplicates)
                plan = result.plan
                accountSaved = result.saved
                if (result.refused > 0) com.auralis.music.util.Log.w("AccountSync", "The library server refused ${result.refused} changes")
                libraryCloud.watch(uid) { onLibraryChanged?.invoke() }
            }
            com.auralis.music.data.sync.LibraryRoute.Firestore -> {
                libraryCloud.stopWatching()
                val mine = local()
                val now = System.currentTimeMillis()
                plan = com.auralis.music.data.sync.AccountLibraryMerge.merge(mine, cloudLibrary(api.get("users/$uid")), baseline, now,
                    serviceLinkedPlaylists(), duplicates(mine))
                if (mutable.value.profile?.uid != uid) return@withContext false
                if (plan.accountChanged) accountSaved = writeFirestoreLibrary(uid, plan.account, now)
            }
        }
        if (mutable.value.profile?.uid != uid) return@withContext false
        plan.deletePlaylists.forEach { library.deletePlaylist(it) }
        for (p in plan.writePlaylists) {
            library.restorePlaylist(p.id, p.title, p.description, p.coverUrl)
            library.replacePlaylistTracks(p.id, p.tracks)
            library.setPlaylistCreatedAt(p.id, p.createdAt)
        }
        plan.addFavorites.forEach { library.setFavorite(it, true) }
        library.getFavoriteTracks().first().filter { it.id in plan.removeFavorites }.forEach { library.setFavorite(it, false) }
        plan.addAlbums.forEach { library.saveAlbum(it) }
        plan.removeAlbums.forEach { library.removeAlbum(it) }
        plan.addArtists.forEach { library.saveArtist(it) }
        plan.removeArtists.forEach { library.removeArtist(it) }
        rememberAccountItems(uid, plan.addedPlaylistIds, plan.addFavorites.mapTo(HashSet()) { it.id })
        if (accountSaved) syncMarks.putStrings(mapOf(baselineKey(uid) to plan.baseline.encode()))
        accountSaved
    }

    /** The Firestore write old and new clients share. False when quota refused it (the merge still applies locally). */
    private fun writeFirestoreLibrary(uid: String, merged: com.auralis.music.data.sync.SyncedLibrary, now: Long): Boolean {
        fun trackJson(t: com.auralis.music.domain.model.Track) = org.json.JSONObject()
            .put("id", t.id).put("title", t.title).put("artist", t.artist).put("album", t.album ?: org.json.JSONObject.NULL)
            .put("thumbnail", t.thumbnail).put("duration", t.duration)
        try { api.write("users/$uid", org.json.JSONObject()
            .put("playlists", org.json.JSONArray(merged.playlists.map { p ->
                org.json.JSONObject().put("id", p.id).put("title", p.title)
                    .put("description", p.description ?: org.json.JSONObject.NULL).put("coverUrl", p.coverUrl ?: org.json.JSONObject.NULL)
                    .put("createdAt", p.createdAt).put("isCustom", p.isCustom)
                    .put("tracks", org.json.JSONArray(p.tracks.map(::trackJson)))
            }))
            .put("favorites", org.json.JSONArray(merged.favorites.map(::trackJson)))
            .put("savedAlbums", org.json.JSONArray(merged.albums.map { a ->
                org.json.JSONObject().put("id", a.id).put("title", a.title).put("artist", a.artist ?: org.json.JSONObject.NULL)
                    .put("thumbnail", a.thumbnail ?: org.json.JSONObject.NULL).put("trackCount", a.trackCount ?: org.json.JSONObject.NULL)
                    .put("savedAt", a.savedAt)
            }))
            .put("savedArtists", org.json.JSONArray(merged.artists.map { a ->
                org.json.JSONObject().put("id", a.id).put("name", a.name).put("thumbnail", a.thumbnail ?: org.json.JSONObject.NULL)
                    .put("subscribers", a.subscribers ?: org.json.JSONObject.NULL)
            }))
            .put("librarySync", org.json.JSONObject().put("pins", org.json.JSONArray(merged.pins)).put("pinsAt", merged.pinsAt)
                .put("deleted", org.json.JSONArray(merged.deleted.map { (id, at) -> org.json.JSONObject().put("id", id).put("at", at) })))
            .put("updatedAt", now).put("playlistsUpdatedAt", now).put("favoritesUpdatedAt", now).put("schemaVersion", 1))
        } catch (e: FirestoreRest.ServiceException) {
            // Refused by the rules: the account's library may have moved. Ask discovery before the next try.
            if (e.status == 403) libraryCloud.authorityChanged(uid)
            if (e.status != 429) throw e
            // Apply the merge we already read, retaining the last acknowledged baseline so
            // this device's pending additions are retried rather than treated as deletions.
            reportSyncFailure(e)
            return false
        }
        return true
    }

    /**
     * Uploads listens measured on this PC to `users/{uid}/listening/{UTC day}`, in the phone's
     * StatsCloudSync shape. Each listen is written as its own map entry ("events.`<start>_<id>`"),
     * so the phone's listens on the same day are left exactly as they are. Listens that came from
     * the account are not sent back. Returns how many were sent.
     */
    suspend fun pushListens(stats: com.auralis.music.desktop.library.DesktopListeningStats): Int = withContext(Dispatchers.IO) {
        val uid = mutable.value.profile?.uid?.takeIf { it.isNotBlank() } ?: return@withContext 0
        val markKey = "listens_watermark_$uid"
        val watermark = syncMarks.getString(markKey, "0")?.toLongOrNull() ?: 0L
        // Re-send the last half hour: a listen may still have been growing at the last upload.
        val pending = stats.listens.value.filter { !it.id.startsWith("cloud_") && it.timeMs > 0 && it.startedAt >= watermark - 30 * 60_000L }
        if (pending.isEmpty()) return@withContext 0
        val day = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(java.time.ZoneOffset.UTC)
        for ((date, listens) in pending.groupBy { day.format(java.time.Instant.ofEpochMilli(it.startedAt)) }) {
            val events = org.json.JSONObject()
            val mask = mutableListOf<String>()
            for (l in listens) {
                val key = "${l.startedAt}_${l.track.id}"
                events.put(key, org.json.JSONObject().put("t", l.track.id).put("ts", l.startedAt).put("ms", l.timeMs)
                    .put("ti", l.track.title).put("ar", l.track.artist).put("al", l.track.album ?: org.json.JSONObject.NULL)
                    .put("th", l.track.thumbnail).put("du", l.track.duration)
                    .put("as", com.auralis.music.domain.model.TrackArtists.encode(
                        com.auralis.music.data.network.OfficialArtistResolver.cached(l.track.id) ?: l.track.artists) ?: org.json.JSONObject.NULL))
                mask += "events.`" + key.replace("\\", "\\\\").replace("`", "\\`") + "`"
            }
            api.write("users/$uid/listening/$date", org.json.JSONObject().put("events", events).put("updatedAt", System.currentTimeMillis()),
                mask = mask + "updatedAt")
        }
        syncMarks.putStrings(mapOf(markKey to pending.maxOf { it.startedAt }.toString()))
        pending.size
    }

    /** The phone's StatsCloudSync day documents as listens (ids "cloud_<start>_<track>"). */
    private fun cloudListens(days: List<Pair<String, org.json.JSONObject>>): List<com.auralis.music.desktop.library.DesktopListeningStats.Listen> =
        days.flatMap { (_, fields) ->
            val events = fields.optJSONObject("events") ?: return@flatMap emptyList()
            events.keys().asSequence().mapNotNull { key ->
                val e = events.optJSONObject(key) ?: return@mapNotNull null
                val id = e.optString("t"); val ts = e.optLong("ts"); val ms = e.optLong("ms")
                if (id.isBlank() || ts <= 0 || ms <= 0) return@mapNotNull null
                com.auralis.music.desktop.library.DesktopListeningStats.Listen("cloud_${ts}_$id",
                    com.auralis.music.domain.model.Track(id = id, title = e.optString("ti").ifBlank { "Unknown Track" },
                        artist = e.optString("ar").ifBlank { "Unknown Artist" }, album = e.optString("al").takeIf { it.isNotBlank() && it != "null" },
                        thumbnail = e.optString("th").takeIf { it != "null" }.orEmpty(), duration = e.optLong("du"),
                        artists = com.auralis.music.domain.model.TrackArtists.decode(e.optString("as").takeIf { it.isNotBlank() && it != "null" })), ts, ms)
            }.toList()
        }

    /**
     * The quick check run every minute: only the listening days updated since the last pull (by
     * the phone, or by this PC's own uploads, which are skipped as duplicates). One read when
     * nothing changed. Returns how many new listens arrived.
     */
    suspend fun pullNewListens(stats: com.auralis.music.desktop.library.DesktopListeningStats,
        history: com.auralis.music.domain.repository.HistoryRepository? = null): Int = withContext(Dispatchers.IO) {
        val uid = mutable.value.profile?.uid?.takeIf { it.isNotBlank() } ?: return@withContext 0
        if (pulledUid != uid) return@withContext 0
        val since = syncMarks.getString("pulled_at_$uid", "0")?.toLongOrNull() ?: 0L
        val started = System.currentTimeMillis()
        // Ten minutes of margin for clock differences between the phone and this PC.
        val days = api.queryGreaterThan("users/$uid", "listening", "updatedAt", since - 10 * 60_000L)
        if (mutable.value.profile?.uid != uid) return@withContext 0
        val added = stats.importListens(cloudListens(days))
        history?.importHistory(cloudListens(days).map { com.auralis.music.domain.model.HistoryEntry(it.track, it.startedAt) }, uid)
        syncMarks.putStrings(mapOf("pulled_at_$uid" to started.toString()))
        added
    }

    private companion object {
        const val WAITING_MOVE = "Your library is being moved to a new sync service. Changes are saved on this device and will sync when it finishes."
        const val WAITING_CHECK = "Couldn't check where your library syncs. Your local changes are saved. Retrying automatically."
    }

    private fun errorMessage(e: Exception): String = when {
        e.message?.contains("INVALID_LOGIN_CREDENTIALS") == true || e.message?.contains("INVALID_PASSWORD") == true -> "Email or password is incorrect."
        e.message?.contains("EMAIL_EXISTS") == true -> "This email already has an account. Choose Sign in."
        e.message?.contains("OPERATION_NOT_ALLOWED") == true -> "Email sign-in is disabled in this Firebase project. Enable Email/Password in Firebase Authentication."
        e.message?.contains("TOO_MANY_ATTEMPTS") == true -> "Too many attempts. Please try again later."
        else -> "Couldn't sign in: ${e.message?.take(160) ?: "check your connection"}"
    }
}

/** Same playlist: the same name (ignoring case and spaces) and exactly the same songs in it. */
internal fun sameNameAndSongs(existing: com.auralis.music.domain.model.Playlist, title: String,
    tracks: List<com.auralis.music.domain.model.Track>): Boolean =
    existing.title.trim().equals(title.trim(), ignoreCase = true) && tracks.isNotEmpty() &&
        existing.tracks.map { it.id }.toSet() == tracks.map { it.id }.toSet() && existing.tracks.size == tracks.size
