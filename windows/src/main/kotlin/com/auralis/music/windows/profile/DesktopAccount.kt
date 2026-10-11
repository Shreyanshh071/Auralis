package com.auralis.music.windows.profile

import com.auralis.music.domain.auth.UserProfile
import com.auralis.music.windows.together.FirestoreRest
import com.sun.jna.platform.win32.Crypt32Util
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Passwords are never saved. The refresh token is protected by the signed-in Windows user. */
class DesktopAccount(private val sessionFile: File, private val scope: CoroutineScope) {
    data class State(val profile: UserProfile? = null, val busy: Boolean = false, val message: String? = null, val browser: Boolean = false)
    private val mutable = MutableStateFlow(State(busy = sessionFile.isFile))
    val state = mutable.asStateFlow()
    private val api = FirestoreRest.fromResource()
    init {
        if (sessionFile.isFile) scope.launch(Dispatchers.IO) {
            try {
                api.restoreAccount(String(Crypt32Util.cryptUnprotectData(sessionFile.readBytes()), Charsets.UTF_8))
                refreshProfile(); save()
            } catch (_: Exception) { mutable.value = State(message = "Couldn't restore your sign-in. Please sign in again.") }
        }
    }
    fun signIn(email: String, password: String, create: Boolean) {
        if (mutable.value.busy) return
        mutable.value = mutable.value.copy(busy = true, message = null)
        scope.launch(Dispatchers.IO) {
            try {
                api.signInEmail(email, password, create); refreshProfile()
                try { save() } catch (_: Exception) { mutable.value = mutable.value.copy(message = "Signed in for this session. Windows couldn't save the protected sign-in.") }
            } catch (e: Exception) { mutable.value = mutable.value.copy(busy = false, browser = false, message = errorMessage(e)) }
        }
    }
    val googleAvailable: Boolean get() = DesktopGoogleLogin.available
    private var googleJob: Job? = null
    fun signInGoogle() {
        if (mutable.value.busy) return
        mutable.value = mutable.value.copy(busy = true, browser = true, message = "Complete sign-in in your browser")
        googleJob = scope.launch(Dispatchers.IO) {
            try {
                api.signInGoogle(DesktopGoogleLogin.idToken()); refreshProfile()
                try { save() } catch (_: Exception) { mutable.value = mutable.value.copy(message = "Signed in for this session. Windows couldn't save the protected sign-in.") }
            } catch (_: CancellationException) { mutable.value = mutable.value.copy(busy = false, browser = false, message = "Google sign-in cancelled") }
            catch (e: Exception) { mutable.value = mutable.value.copy(busy = false, browser = false, message = errorMessage(e)) }
        }
    }
    fun cancelGoogle() { googleJob?.cancel() }
    private fun refreshProfile() {
        val profile = api.accountProfile()
        val email = profile.optString("email")
        mutable.value = State(profile = UserProfile(uid = api.uid, displayName = profile.optString("displayName").ifBlank { email.substringBefore('@') },
            email = email, avatarUrl = profile.optString("photoUrl").takeIf { it.isNotBlank() }, isGoogleConnected = false))
    }
    private fun save() {
        val protected = Crypt32Util.cryptProtectData(api.accountRefreshToken().toByteArray(Charsets.UTF_8))
        sessionFile.parentFile?.mkdirs()
        val temporary = File(sessionFile.parentFile, sessionFile.name + ".tmp")
        temporary.writeBytes(protected)
        Files.move(temporary.toPath(), sessionFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
    fun signOut() {
        if (mutable.value.busy) return
        mutable.value = mutable.value.copy(busy = true)
        scope.launch(Dispatchers.IO) {
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
        stats: com.auralis.music.desktop.library.DesktopListeningStats
    ): String? = withContext(Dispatchers.IO) {
        val uid = mutable.value.profile?.uid?.takeIf { it.isNotBlank() } ?: return@withContext null
        fun track(o: org.json.JSONObject) = com.auralis.music.domain.model.Track(
            id = o.optString("id"), title = o.optString("title").ifBlank { "Unknown Track" },
            artist = o.optString("artist").ifBlank { "Unknown Artist" }, album = o.optString("album").takeIf { it.isNotBlank() },
            thumbnail = o.optString("thumbnail"), duration = o.optLong("duration"))
        fun objects(a: org.json.JSONArray?) = (0 until (a?.length() ?: 0)).mapNotNull { a!!.optJSONObject(it) }

        var playlists = 0; var liked = 0
        val doc = api.get("users/$uid")
        if (doc != null) {
            for (p in objects(doc.optJSONArray("playlists"))) {
                val title = p.optString("title").ifBlank { "Restored Playlist" }
                val description = p.optString("description").takeIf { it.isNotBlank() }
                val cover = p.optString("coverUrl").takeIf { it.isNotBlank() }
                val id = p.optString("id").takeIf { it.isNotBlank() }
                val tracks = objects(p.optJSONArray("tracks")).map(::track).filter { it.id.isNotBlank() }
                val target = if (id != null) library.restorePlaylist(id, title, description, cover) else library.createPlaylist(title, description, cover)
                if (tracks.isNotEmpty()) library.replacePlaylistTracks(target.id, tracks)
                playlists++
            }
            for (f in objects(doc.optJSONArray("favorites"))) {
                val t = track(f)
                if (t.id.isNotBlank()) { library.setFavorite(t, true); liked++ }
            }
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
        val listens = runCatching { api.list("users/$uid/listening") }.getOrDefault(emptyList()).flatMap { (_, fields) ->
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
        val newListens = stats.importListens(listens)
        if (doc == null && listens.isEmpty()) return@withContext null
        "Synced from your account: $playlists playlists, $liked liked songs, $newListens new listens"
    }

    private fun errorMessage(e: Exception): String = when {
        e.message?.contains("INVALID_LOGIN_CREDENTIALS") == true || e.message?.contains("INVALID_PASSWORD") == true -> "Email or password is incorrect."
        e.message?.contains("EMAIL_EXISTS") == true -> "This email already has an account. Choose Sign in."
        e.message?.contains("OPERATION_NOT_ALLOWED") == true -> "Email sign-in is disabled in this Firebase project. Enable Email/Password in Firebase Authentication."
        e.message?.contains("TOO_MANY_ATTEMPTS") == true -> "Too many attempts. Please try again later."
        else -> "Couldn't sign in: ${e.message?.take(160) ?: "check your connection"}"
    }
}
