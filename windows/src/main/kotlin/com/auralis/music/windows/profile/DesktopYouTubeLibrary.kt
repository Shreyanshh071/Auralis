package com.auralis.music.windows.profile

import com.auralis.music.data.network.YouTubeSession
import com.auralis.music.domain.model.PlaylistResult
import com.auralis.music.domain.model.Track
import com.auralis.music.util.KeyValueStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

internal data class YouTubeAccountSummary(val id: String, val identity: YouTubeIdentity)
internal data class YouTubeLibraryState(val identity: YouTubeIdentity? = null,
    val playlists: List<PlaylistResult> = emptyList(), val loading: Boolean = false,
    val error: String? = null, val accounts: List<YouTubeAccountSummary> = emptyList(), val activeId: String? = null)

/** The entire account registry (including cookies) lives under the store's DPAPI-protected key. */
internal class YouTubeAccountsStore(private val store: KeyValueStore) {
    private val entries = runCatching { JSONArray(store.getString("cookie", "[]")) }.getOrElse { JSONArray() }
    @Synchronized fun summaries(): List<YouTubeAccountSummary> = (0 until entries.length()).mapNotNull { index ->
        entries.optJSONObject(index)?.let { YouTubeAccountSummary(it.getString("id"),
            YouTubeIdentity(it.getString("name"), it.optString("subtitle"), it.optString("avatar").takeIf(String::isNotBlank))) }
    }
    @Synchronized fun findCookie(cookie: String, authUser: String = "0"): String? = (0 until entries.length()).firstNotNullOfOrNull {
        entries.optJSONObject(it)?.takeIf { item ->
            item.optString("authUser", "0") == authUser && sameYouTubeAccountSession(item.optString("session"), cookie)
        }?.optString("id")
    }
    @Synchronized fun session(id: String): JSONObject? = (0 until entries.length()).firstNotNullOfOrNull {
        entries.optJSONObject(it)?.takeIf { item -> item.optString("id") == id }?.let { item -> JSONObject(item.toString()) }
    }
    @Synchronized fun playlists(id: String?): List<PlaylistResult> = id?.let(::session)?.optJSONArray("playlists")?.let { array ->
        (0 until array.length()).mapNotNull { array.optJSONObject(it)?.let { p ->
            PlaylistResult(p.getString("id"), p.getString("title"), p.optString("image").takeIf(String::isNotBlank), p.optString("author")) } }
    }.orEmpty()
    @Synchronized fun save(identity: YouTubeIdentity, cookie: String, scope: YouTubeScope, playlists: List<PlaylistResult>): String {
        val id = MessageDigest.getInstance("SHA-256").digest("${identity.key}|${scope.authUser}|${scope.pageId.orEmpty()}".toByteArray())
            .joinToString("") { "%02x".format(it) }
        val value = JSONObject().put("id", id).put("name", identity.name).put("subtitle", identity.subtitle)
            .put("avatar", identity.avatar.orEmpty()).put("session", cookie).put("authUser", scope.authUser)
            .put("visitor", scope.visitor.orEmpty()).put("sync", scope.dataSyncId.orEmpty())
            .put("playlists", JSONArray(playlists.map { p -> JSONObject().put("id", p.id).put("title", p.title)
                .put("image", p.thumbnail.orEmpty()).put("author", p.author.orEmpty()) }))
        val index = (0 until entries.length()).firstOrNull { entries.optJSONObject(it)?.optString("id") == id }
        if (index == null) entries.put(value) else entries.put(index, value)
        store.putStrings(mapOf("cookie" to entries.toString()))
        return id
    }
    @Synchronized fun remove(id: String) {
        (0 until entries.length()).firstOrNull { entries.optJSONObject(it)?.optString("id") == id }?.let(entries::remove)
        store.putStrings(mapOf("cookie" to entries.toString()))
    }
    @Synchronized fun clear() {
        while (entries.length() > 0) entries.remove(0)
        store.putStrings(mapOf("cookie" to "[]"))
    }
}

internal class DesktopYouTubeLibrary(private val coroutineScope: CoroutineScope,
    private val accounts: YouTubeAccountsStore = YouTubeAccountsStore(ProtectedYouTubeStore(
        File(com.auralis.music.util.desktopAppDataDir(), "youtube-accounts.properties")))) {
    private val mutable = MutableStateFlow(YouTubeLibraryState(accounts = accounts.summaries()))
    val state = mutable.asStateFlow()
    private var job: Job? = null
    @Volatile private var generation = 0
    @Volatile private var currentCookie: String? = null
    @Volatile private var client: YouTubeMusicClient? = null

    fun refresh(force: Boolean = false) {
        val headers = YouTubeSession.authHeaders(YouTubeMusicClient.ORIGIN)
        val cookie = headers["Cookie"].orEmpty()
        val sessionKey = "${headers["X-Goog-AuthUser"].orEmpty()}:$cookie"
        if (!force && currentCookie == sessionKey) return
        currentCookie = sessionKey
        job?.cancel(); generation++
        val version = generation
        client = null
        if (cookie.isBlank()) {
            mutable.value = YouTubeLibraryState(accounts = accounts.summaries()); return
        }
        val cachedId = accounts.findCookie(cookie, headers["X-Goog-AuthUser"] ?: "0")
        val cachedIdentity = accounts.summaries().firstOrNull { it.id == cachedId }?.identity
        mutable.value = YouTubeLibraryState(cachedIdentity, accounts.playlists(cachedId), true,
            accounts = accounts.summaries(), activeId = cachedId)
        job = coroutineScope.launch(Dispatchers.IO) {
            try {
                val api = YouTubeMusicClient(cookie, headers["X-Goog-AuthUser"] ?: "0")
                api.resolveScope()
                val identity = api.identity()
                ensureActive()
                if (generation != version) return@launch
                val id = accounts.save(identity, api.sessionCookie, api.scope, accounts.playlists(cachedId))
                client = api
                YouTubeSession.save(api.sessionCookie, api.scope.visitor.orEmpty(), api.scope.authUser, identity.name, api.scope.dataSyncId.orEmpty())
                mutable.value = mutable.value.copy(identity = identity, accounts = accounts.summaries(), activeId = id)
                api.playlists { page ->
                    if (generation == version) {
                        accounts.save(identity, api.sessionCookie, api.scope, page)
                        mutable.value = mutable.value.copy(playlists = page)
                    }
                }
                if (generation == version) {
                    mutable.value = mutable.value.copy(loading = false)

                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (generation == version) mutable.value = mutable.value.copy(loading = false,
                    error = if (client == null) "Couldn't reach your YouTube account. Your saved connection is retained; try again."
                    else "Couldn't finish loading your YouTube playlists. Try again.")
            }
        }
    }

    fun switchAccount(id: String) {
        val value = accounts.session(id) ?: return
        YouTubeSession.save(value.getString("session"), value.optString("visitor"), value.optString("authUser", "0"),
            value.getString("name"), value.optString("sync"))
        refresh(force = true)
    }
    fun removeAccount(id: String) {
        accounts.remove(id)
        if (state.value.activeId == id) { YouTubeSession.signOut(); refresh(force = true) }
        else mutable.value = mutable.value.copy(accounts = accounts.summaries())
    }
    fun signOut() {
        state.value.activeId?.let(accounts::remove)
        accounts.clear()
        YouTubeSession.signOut()
        refresh(force = true)
    }
    suspend fun createPlaylist(title: String): PlaylistResult {
        val api = client ?: error("Connect YouTube Music and wait for your account to load.")
        val version = generation
        val created = api.createPlaylist(title)
        check(version == generation) { "Playlist created in the previous account. Switch back to view it." }
        val current = mutable.value
        val playlists = listOf(created) + current.playlists.filterNot { it.id == created.id }
        val identity = current.identity
        val cookie = current.activeId?.let(accounts::session)?.optString("session")
        if (identity != null && !cookie.isNullOrBlank()) accounts.save(identity, api.sessionCookie, api.scope, playlists)
        mutable.value = current.copy(playlists = playlists)
        return created
    }

    suspend fun tracks(playlist: PlaylistResult, onPage: (List<Track>) -> Unit): List<Track> {
        val api = client ?: error("Reconnect YouTube Music to open this playlist.")
        val version = generation
        return api.tracks(playlist) { page ->
            check(version == generation) { "The YouTube account changed. Open this playlist again." }
            onPage(page)
        }
    }
}

/** Google rotates non-identity cookies independently of the account registry. */
internal fun sameYouTubeAccountSession(saved: String, current: String): Boolean {
    if (!YouTubeSession.hasAuthCookies(saved) || !YouTubeSession.hasAuthCookies(current)) return false
    if (saved == current) return true
    val a = YouTubeSession.parseCookies(saved)
    val b = YouTubeSession.parseCookies(current)
    return listOf("SAPISID", "__Secure-1PAPISID", "__Secure-3PAPISID").any { name ->
        !a[name].isNullOrBlank() && a[name] == b[name]
    }
}
