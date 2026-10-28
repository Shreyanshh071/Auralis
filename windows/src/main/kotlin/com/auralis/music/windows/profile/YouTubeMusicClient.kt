package com.auralis.music.windows.profile

import com.auralis.music.data.network.ContentLocale
import com.auralis.music.data.network.NetworkClientProvider
import com.auralis.music.data.network.YouTubeSession
import com.auralis.music.domain.model.PlaylistResult
import com.auralis.music.domain.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

internal data class YouTubeIdentity(val name: String, val subtitle: String, val avatar: String?) {
    val key: String get() = subtitle.ifBlank { name }
}
internal data class YouTubeScope(val version: String = "1.20250101.01.00", val authUser: String = "0",
    val pageId: String? = null, val dataSyncId: String? = null, val visitor: String? = null)

/** YouTube Music requests scoped to a snapshot of the selected browser session; writes use the selected account. */
internal class YouTubeMusicClient(
    cookie: String = YouTubeSession.authHeaders(ORIGIN)["Cookie"].orEmpty(),
    authUser: String = "0",
    private val transport: ((String, JSONObject?, Map<String, String>) -> String)? = null
) {
    @Volatile private var cookie: String = cookie
    val sessionCookie: String get() = cookie
    private fun send(url: String, body: JSONObject?, headers: Map<String, String>): String =
        transport?.invoke(url, body, headers) ?: youtubeHttp(url, body, headers) { updates ->
            synchronized(this) {
                val previous = cookie
                cookie = mergeYouTubeCookies(previous, updates)
                YouTubeSession.refreshCookies(previous, cookie)
            }
        }

    companion object {
        const val ORIGIN = "https://music.youtube.com"
        private val scopes = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, YouTubeScope>>()
        @Volatile private var publicScope: Pair<Long, YouTubeScope>? = null
    }
    var scope = YouTubeScope(authUser = authUser); private set

    suspend fun resolveScope() = withContext(Dispatchers.IO) {
        check(YouTubeSession.hasAuthCookies(cookie)) { "Sign in to YouTube Music to load your library." }
        val scopeKey = youtubeHomeAccountKey(cookie, scope.authUser)
        scopes[scopeKey]?.takeIf { transport == null && System.currentTimeMillis() - it.first in 0..300_000L }?.let {
            scope = it.second
            return@withContext
        }
        val loggedOut = Regex("\"LOGGED_IN\"\\s*:\\s*false")
        var html = send("$ORIGIN/?authuser=${scope.authUser}", null, headers())
        // Google rotated the sign-in cookie in the browser: renew from the profile the listener
        // connected (if any) and try once more before asking them to reconnect.
        if (loggedOut.containsMatchIn(html) && transport == null) {
            val stale = cookie
            YouTubeSessionSource.renew(stale)?.let { fresh ->
                synchronized(this) { cookie = fresh }
                YouTubeSession.refreshCookies(stale, fresh)
                html = send("$ORIGIN/?authuser=${scope.authUser}", null, headers())
            }
        }
        check(!loggedOut.containsMatchIn(html)) {
            "Your YouTube session has expired. Please reconnect your browser profile."
        }
        scope = parseYouTubeScope(html)
        // Login/library sync already resolved this selected account. Home can reuse that
        // context instead of fetching and parsing YouTube's full HTML again.
        if (scopes.size > 16) scopes.clear()
        if (transport == null) scopes[scopeKey] = System.currentTimeMillis() to scope
    }

    /** Public Home uses its own visitor context and never requires an account. */
    suspend fun resolvePublicScope() = withContext(Dispatchers.IO) {
        check(!YouTubeSession.hasAuthCookies(cookie))
        publicScope?.takeIf { transport == null && System.currentTimeMillis() - it.first in 0..300_000L }?.let {
            scope = it.second
            return@withContext
        }
        scope = parseYouTubeScope(send("$ORIGIN/", null, headers()))
            .copy(pageId = null, dataSyncId = null, authUser = "0")
        if (transport == null) publicScope = System.currentTimeMillis() to scope
    }

    private fun headers(): Map<String, String> {
        val cookies = YouTubeSession.parseCookies(cookie)
        val timestamp = (System.currentTimeMillis() / 1000).toString()
        val authorization = listOf("SAPISIDHASH" to (cookies["SAPISID"] ?: cookies["__Secure-3PAPISID"]),
            "SAPISID1PHASH" to cookies["__Secure-1PAPISID"], "SAPISID3PHASH" to cookies["__Secure-3PAPISID"])
            .mapNotNull { (scheme, value) -> value?.let {
                val digest = MessageDigest.getInstance("SHA-1").digest("$timestamp $it $ORIGIN".toByteArray())
                    .joinToString("") { byte -> "%02x".format(byte) }
                "$scheme ${timestamp}_$digest"
            } }.joinToString(" ")
        return buildMap {
            if (cookie.isNotBlank()) put("Cookie", cookie)
            if (authorization.isNotBlank()) put("Authorization", authorization)
            put("Origin", ORIGIN); put("X-Origin", ORIGIN); put("Referer", "$ORIGIN/")
            if (YouTubeSession.hasAuthCookies(cookie)) put("X-Goog-AuthUser", scope.authUser)
            put("X-Youtube-Bootstrap-Logged-In", YouTubeSession.hasAuthCookies(cookie).toString())
            put("X-YouTube-Client-Name", "67"); put("X-YouTube-Client-Version", scope.version)
            put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/141.0.0.0 Safari/537.36")
            scope.pageId?.let { put("X-Goog-PageId", it) }
            scope.visitor?.let { put("X-Goog-Visitor-Id", it) }
            val hl = ContentLocale.hl()
            put("Accept-Language", if (hl == "en") "en-US,en;q=0.9" else "$hl,en;q=0.5")
        }
    }

    internal suspend fun post(endpoint: String, extras: JSONObject = JSONObject()): JSONObject = withContext(Dispatchers.IO) {
        val hl = ContentLocale.hl()
        val gl = ContentLocale.gl()
        val client = JSONObject().put("clientName", "WEB_REMIX").put("clientVersion", scope.version)
            .put("hl", hl).put("gl", gl)
        scope.visitor?.let { client.put("visitorData", it) }
        val user = JSONObject().put("lockedSafetyMode", false)
        scope.dataSyncId?.let { user.put("onBehalfOfUser", it) }
        extras.put("context", JSONObject().put("client", client).put("user", user)
            .put("request", JSONObject().put("useSsl", true)))
        val result = JSONObject(send("$ORIGIN/youtubei/v1/$endpoint?prettyPrint=false", extras, headers()))
        check(!result.has("error")) { "YouTube couldn't load this account. Please reconnect or try again." }
        result
    }

    suspend fun identity(): YouTubeIdentity = parseYouTubeIdentity(post("account/account_menu"))
        ?: error("YouTube did not confirm the account. Please reconnect your browser profile.")

    suspend fun browseHome(browseId: String = "FEmusic_home", continuation: String? = null): JSONObject =
        post("browse", if (continuation == null) JSONObject().put("browseId", browseId) else JSONObject().put("continuation", continuation))

    /** Music's filtered artist results, scoped to the selected account when connected. */
    suspend fun searchArtists(query: String): List<com.auralis.music.domain.model.Artist> {
        val root = post("search", JSONObject().put("query", query)
            .put("params", com.auralis.music.data.network.InnerTubeClient.FILTER_ARTISTS))
        val artists = mutableListOf<com.auralis.music.domain.model.Artist>()
        val parser = com.auralis.music.data.network.InnerTubeClient()
        youtubeNodes(root, "musicResponsiveListItemRenderer").forEach {
            parser.parseMusicListItem(it, mutableListOf(), artists, mutableListOf(), mutableListOf())
        }
        return artists.distinctBy { it.id }
    }

    /**
     * The account's subscription to an artist, read off the artist page's subscribe button: the
     * channel the button acts on (often the official channel, not the browsed one) and its state.
     */
    suspend fun artistSubscription(browseId: String): Pair<String, Boolean>? {
        val button = youtubeNodes(post("browse", JSONObject().put("browseId", browseId)), "subscribeButtonRenderer").firstOrNull()
            ?: return null
        return button.optString("channelId").ifBlank { browseId } to button.optBoolean("subscribed")
    }

    /** Subscribes the signed-in account to a channel, or unsubscribes it (YouTube's own write). */
    suspend fun setSubscribed(channelId: String, subscribed: Boolean) {
        post(if (subscribed) "subscription/subscribe" else "subscription/unsubscribe",
            JSONObject().put("channelIds", JSONArray().put(channelId)))
    }

    /** Create in the connected YouTube Music account, private until the user changes visibility. */
    suspend fun createPlaylist(title: String): PlaylistResult {
        val name = title.trim()
        require(name.isNotBlank()) { "Enter a playlist name." }
        val result = post("playlist/create", JSONObject().put("title", name).put("privacyStatus", "PRIVATE"))
        val id = result.optString("playlistId").takeIf { it.isNotBlank() }
            ?: error("YouTube did not confirm creation. Check your library before trying again.")
        return PlaylistResult(if (id.startsWith("VL")) id else "VL$id", name)
    }

    suspend fun playlists(onPage: (List<PlaylistResult>) -> Unit): List<PlaylistResult> {
        val values = linkedMapOf<String, PlaylistResult>()
        values["VLLM"] = PlaylistResult("VLLM", "Liked Music")
        val seen = mutableSetOf<String>()
        var token: String? = null
        do {
            val body = if (token == null) JSONObject().put("browseId", "FEmusic_liked_playlists")
                else JSONObject().put("continuation", token)
            val page = post("browse", body)
            parseYouTubePlaylists(page).forEach { values[it.id] = it }
            onPage(values.values.toList())
            token = youtubeContinuation(page)?.takeIf { seen.add(it) }
            kotlin.coroutines.coroutineContext.ensureActive()
        } while (token != null)
        return values.values.toList()
    }

    suspend fun tracks(playlist: PlaylistResult, onPage: (List<Track>) -> Unit): List<Track> {
        val values = linkedMapOf<String, Track>()
        val seen = mutableSetOf<String>()
        var token: String? = null
        do {
            val body = if (token == null) JSONObject().put("browseId", if (playlist.id.startsWith("VL")) playlist.id else "VL${playlist.id}")
                else JSONObject().put("continuation", token)
            val page = post("browse", body)
            parseYouTubeTracks(page).forEach { values[it.id] = it }
            onPage(values.values.toList())
            token = youtubePlaylistContinuation(page)?.takeIf { seen.add(it) }
            kotlin.coroutines.coroutineContext.ensureActive()
        } while (token != null)
        return values.values.toList()
    }
}

/** Stable across routine cookie rotation; includes the selected account and never exposes cookies. */
internal fun youtubeHomeAccountKey(cookie: String, authUser: String): String {
    val cookies = YouTubeSession.parseCookies(cookie)
    val identity = listOf(authUser, cookies["SAPISID"] ?: cookies["__Secure-3PAPISID"].orEmpty(),
        cookies["LOGIN_INFO"].orEmpty()).joinToString("|")
    return MessageDigest.getInstance("SHA-256").digest(identity.toByteArray())
        .joinToString("") { "%02x".format(it) }
}

/** Only cookies returned by YouTube itself are merged; browser profiles are not reread. */
internal fun mergeYouTubeCookies(current: String, updates: List<okhttp3.Cookie>): String {
    val merged = YouTubeSession.parseCookies(current).toMutableMap()
    updates.forEach { cookie ->
        if (cookie.domain == "youtube.com" || cookie.domain == "music.youtube.com") {
            if (cookie.expiresAt <= System.currentTimeMillis()) merged.remove(cookie.name)
            else merged[cookie.name] = cookie.value
        }
    }
    return merged.entries.joinToString("; ") { "${it.key}=${it.value}" }
}

private val youtubeHttpClient by lazy { NetworkClientProvider.okHttpClient.newBuilder().callTimeout(25, TimeUnit.SECONDS).build() }
private fun youtubeHttp(url: String, body: JSONObject?, headers: Map<String, String>, onCookies: (List<okhttp3.Cookie>) -> Unit): String {
    val request = Request.Builder().url(url)
    headers.forEach { (key, value) -> request.header(key, value) }
    if (body != null) request.post(body.toString().toRequestBody("application/json".toMediaType()))
    return youtubeHttpClient.newCall(request.build()).execute().use { response ->
        check(response.isSuccessful) { if (response.code in listOf(401, 403))
            "Your YouTube session needs reconnecting. Select your browser profile again."
            else "YouTube couldn't load your library. Please try again." }
        if (response.request.url.host == "music.youtube.com") {
            val updates = okhttp3.Cookie.parseAll(response.request.url, response.headers)
                .filter { it.matches(response.request.url) }
            if (updates.isNotEmpty()) onCookies(updates)
        }
        response.body?.string() ?: error("YouTube returned an empty response. Please try again.")
    }
}

internal fun youtubeNodes(value: Any?, name: String): List<JSONObject> = buildList {
    fun visit(node: Any?) {
        when (node) {
            is JSONObject -> node.keys().forEach { key ->
                val child = node.opt(key)
                if (key == name && child is JSONObject) add(child) else visit(child)
            }
            is JSONArray -> for (i in 0 until node.length()) visit(node.opt(i))
        }
    }
    visit(value)
}
private fun text(value: JSONObject?): String = value?.optString("simpleText")?.takeIf { it.isNotBlank() }
    ?: value?.optJSONArray("runs")?.let { runs -> (0 until runs.length()).joinToString("") { runs.optJSONObject(it)?.optString("text").orEmpty() } }.orEmpty()
private fun photo(value: Any?): String? {
    // Thumbnail wrappers vary between account, grid and list renderers.
    fun find(node: Any?): String? = when (node) {
        is JSONObject -> node.optJSONArray("thumbnails")?.let { thumbnails ->
            (0 until thumbnails.length()).mapNotNull { thumbnails.optJSONObject(it) }
                .maxByOrNull { it.optInt("width") }?.optString("url")?.takeIf { it.startsWith("https://") }
        } ?: node.keys().asSequence().mapNotNull { find(node.opt(it)) }.firstOrNull()
        is JSONArray -> (0 until node.length()).firstNotNullOfOrNull { find(node.opt(it)) }
        else -> null
    }
    return find(value)
}
internal fun parseYouTubeScope(html: String): YouTubeScope {
    fun config(key: String) = Regex("\"$key\"\\s*:\\s*\"([^\"]+)\"").find(html)?.groupValues?.get(1)
    val signedIn = Regex("\"LOGGED_IN\"\\s*:\\s*true").containsMatchIn(html)
    val pageId = config("DELEGATED_SESSION_ID").takeIf { signedIn }
    return YouTubeScope(config("INNERTUBE_CLIENT_VERSION") ?: "1.20250101.01.00",
        Regex("\"SESSION_INDEX\"\\s*:\\s*\"?(\\d+)").find(html)?.groupValues?.get(1) ?: "0",
        pageId, if (signedIn) pageId ?: config("DATASYNC_ID")?.substringBefore("||") else null,
        config("VISITOR_DATA"))
}
internal fun parseYouTubeIdentity(root: JSONObject): YouTubeIdentity? {
    val account = youtubeNodes(root, "activeAccountHeaderRenderer").firstOrNull() ?: return null
    val name = text(account.optJSONObject("accountName")).takeIf { it.isNotBlank() } ?: return null
    return YouTubeIdentity(name, text(account.optJSONObject("email")).ifBlank { text(account.optJSONObject("channelHandle")) }, photo(account.opt("accountPhoto")))
}
internal fun youtubeContinuation(root: JSONObject): String? = youtubeNodes(root, "continuationItemRenderer")
    .firstNotNullOfOrNull { it.optJSONObject("continuationEndpoint")?.optJSONObject("continuationCommand")?.optString("token")?.takeIf(String::isNotBlank) }
    ?: youtubeNodes(root, "nextContinuationData").firstNotNullOfOrNull { it.optString("continuation").takeIf(String::isNotBlank) }

internal fun parseYouTubePlaylists(root: JSONObject): List<PlaylistResult> {
    val rows = youtubeNodes(root, "musicTwoRowItemRenderer") + youtubeNodes(root, "musicResponsiveListItemRenderer")
    return rows.mapNotNull { row ->
        val titleNode = row.optJSONObject("title") ?: row.optJSONArray("flexColumns")?.optJSONObject(0)
            ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")?.optJSONObject("text")
        val id = (row.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")?.optString("browseId")
            ?.takeIf(String::isNotBlank) ?: youtubeNodes(titleNode, "browseEndpoint").firstOrNull()?.optString("browseId"))
            ?.takeIf { it.startsWith("VL") || it.startsWith("PL") || it.startsWith("OLAK") || it == "LM" } ?: return@mapNotNull null
        val title = text(titleNode).takeIf(String::isNotBlank) ?: return@mapNotNull null
        PlaylistResult(if (id.startsWith("VL")) id else "VL$id", title, photo(row), text(row.optJSONObject("subtitle")))
    }.distinctBy { it.id }
}
internal fun parseYouTubeTracks(root: JSONObject): List<Track> {
    // Scope to the actual playlist shelf, excluding recommendations underneath it.
    val containers = youtubeNodes(root, "musicPlaylistShelfRenderer") + youtubeNodes(root, "musicPlaylistShelfContinuation")
    val roots = containers.ifEmpty { listOf(root) }
    val parser = com.auralis.music.data.network.InnerTubeClient()
    return roots.flatMap { container ->
        // Music only: a liked-videos list also holds Shorts and plain YouTube uploads (tech reviews,
        // tutorials); those never show in Auralis.
        youtubeNodes(container, "musicResponsiveListItemRenderer")
            .filterNot { com.auralis.music.data.network.YouTubeShortsFilter.isNonMusicVideo(it) }.flatMap { row ->
            val tracks = mutableListOf<Track>()
            parser.parseMusicListItem(row, tracks, mutableListOf())
            tracks
        }
    }.distinctBy { it.id }
}

internal fun youtubePlaylistContinuation(root: JSONObject): String? {
    val shelves = youtubeNodes(root, "musicPlaylistShelfRenderer") + youtubeNodes(root, "musicPlaylistShelfContinuation")
    return youtubeContinuation(if (shelves.isEmpty()) root else JSONObject().put("shelves", JSONArray(shelves)))
}
