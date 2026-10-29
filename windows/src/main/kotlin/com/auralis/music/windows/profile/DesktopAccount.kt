package com.auralis.music.windows.profile

import com.auralis.music.domain.auth.UserProfile
import com.auralis.music.windows.together.FirestoreRest
import com.sun.jna.platform.win32.Crypt32Util
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.AtomicMoveNotSupportedException

/** Passwords are never saved. The refresh token is protected by the signed-in Windows user. */
class DesktopAccount(private val sessionFile: File, private val scope: CoroutineScope,
    private val api: FirestoreRest = FirestoreRest.fromResource(), private val restoreRetryMs: Long = 5_000L) {
    data class State(val profile: UserProfile? = null, val busy: Boolean = false, val message: String? = null, val browser: Boolean = false)
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
                try { save() } catch (_: Exception) { mutable.value = mutable.value.copy(message = "Signed in for this session. Windows couldn't save the protected sign-in.") }
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
                api.signInGoogle(DesktopGoogleLogin.idToken())
                save()
                refreshProfile()
                try { save() } catch (_: Exception) { mutable.value = mutable.value.copy(message = "Signed in for this session. Windows couldn't save the protected sign-in.") }
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
        mutable.value = State(
            profile = UserProfile(
                uid = api.uid,
                displayName = profile.optString("displayName").ifBlank { email.substringBefore('@') },
                email = email,
                avatarUrl = profile.optString("photoUrl").takeIf { it.isNotBlank() },
                isGoogleConnected = isGoogle
            ),
            busy = false,
            browser = false,
            message = "Signed in as ${profile.optString("displayName").ifBlank { email }}."
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
        val doc = try { api.get("users/$uid") } catch (e: Exception) {
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
            val serviceLinks = com.auralis.music.data.playlistsync.PlaylistSyncStore(
                com.auralis.music.util.PropertiesFileKeyValueStore(File(sessionFile.parentFile, "playlist-sync.properties"), synchronous = true))
                .links.value.filter { it.enabled }.map { it.localId }.toSet()
            for (p in objects(doc.optJSONArray("playlists"))) {
                val title = p.optString("title").ifBlank { "Restored Playlist" }
                val description = p.optString("description").takeIf { it.isNotBlank() }
                val cover = p.optString("coverUrl").takeIf { it.isNotBlank() }
                val id = p.optString("id").takeIf { it.isNotBlank() }
                val tracks = objects(p.optJSONArray("tracks")).map(::track).filter { it.id.isNotBlank() }
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
                if (t.id.isNotBlank()) {
                    library.setFavorite(t, true); liked++
                    if (t.id !in localFavoriteIds) addedFavorites += t.id
                }
            }
            rememberAccountItems(uid, addedPlaylists, addedFavorites)
            syncMarks.putStrings(mapOf("account_items_v1_$uid" to "1"))
            for (a in objects(doc.optJSONArray("savedAlbums"))) {
                val id = a.optString("id"); val title = a.optString("title")
                if (id.isNotBlank() && title.isNotBlank()) library.saveAlbum(com.auralis.music.domain.model.SavedAlbum(id, title,
                    a.optString("artist").takeIf { it.isNotBlank() }, a.optString("thumbnail").takeIf { it.isNotBlank() },
                    a.optInt("trackCount").takeIf { it > 0 }, a.optLong("savedAt").takeIf { it > 0 } ?: System.currentTimeMillis()))
            }
            for (a in objects(doc.optJSONArray("savedArtists"))) {
                val id = a.optString("id"); val name = a.optString("name")
                if (id.isNotBlank() && name.isNotBlank()) library.saveArtist(com.auralis.music.domain.model.SavedArtist(id, name,
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
            "pulled_at_$uid" to "0", "account_items_v1_$uid" to ""))
        playlists.count { it in present }
    }

    /** Which account's backup has been read this session; uploads wait for it (see [pushLibrary]). */
    @Volatile private var pulledUid: String? = null
    private val syncMarks by lazy {
        com.auralis.music.util.PropertiesFileKeyValueStore(File(sessionFile.parentFile, "account-sync.properties"))
    }

    /**
     * Uploads this PC's library in the phone's backup shape (`users/{uid}`: playlists with their
     * songs, favorites, saved albums and artists). Only after this session read the account's
     * backup into the PC library ([pullCloudBackup]), so the upload is the union of both devices
     * and never drops a playlist that only the phone has. The phone restores it on its next start.
     */
    suspend fun pushLibrary(library: com.auralis.music.domain.repository.LibraryRepository): Boolean = withContext(Dispatchers.IO) {
        val uid = mutable.value.profile?.uid?.takeIf { it.isNotBlank() } ?: return@withContext false
        if (pulledUid != uid) return@withContext false
        fun trackJson(t: com.auralis.music.domain.model.Track) = org.json.JSONObject()
            .put("id", t.id).put("title", t.title).put("artist", t.artist).put("album", t.album ?: org.json.JSONObject.NULL)
            .put("thumbnail", t.thumbnail).put("duration", t.duration)
        val playlists = library.getPlaylists().first()
        val favorites = library.getFavoriteTracks().first()
        val albums = library.getSavedAlbums().first()
        val artists = library.getSavedArtists().first()
        val now = System.currentTimeMillis()
        val fields = org.json.JSONObject()
            .put("playlists", org.json.JSONArray(playlists.map { p ->
                org.json.JSONObject().put("id", p.id).put("title", p.title)
                    .put("description", p.description ?: org.json.JSONObject.NULL).put("coverUrl", p.coverUrl ?: org.json.JSONObject.NULL)
                    .put("createdAt", p.createdAt).put("isCustom", p.isCustom)
                    .put("tracks", org.json.JSONArray(p.tracks.map(::trackJson)))
            }))
            .put("favorites", org.json.JSONArray(favorites.map(::trackJson)))
            .put("savedAlbums", org.json.JSONArray(albums.map { a ->
                org.json.JSONObject().put("id", a.id).put("title", a.title).put("artist", a.artist ?: org.json.JSONObject.NULL)
                    .put("thumbnail", a.thumbnail ?: org.json.JSONObject.NULL).put("trackCount", a.trackCount ?: org.json.JSONObject.NULL)
                    .put("savedAt", a.savedAt)
            }))
            .put("savedArtists", org.json.JSONArray(artists.map { a ->
                org.json.JSONObject().put("id", a.id).put("name", a.name).put("thumbnail", a.thumbnail ?: org.json.JSONObject.NULL)
                    .put("subscribers", a.subscribers ?: org.json.JSONObject.NULL)
            }))
            .put("updatedAt", now).put("playlistsUpdatedAt", now).put("favoritesUpdatedAt", now).put("schemaVersion", 1)
        api.write("users/$uid", fields)
        true
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
                    .put("th", l.track.thumbnail).put("du", l.track.duration))
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
                        thumbnail = e.optString("th").takeIf { it != "null" }.orEmpty(), duration = e.optLong("du")), ts, ms)
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
