package com.auralis.music.windows.profile

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

/** Read-only YouTube Music requests scoped to a snapshot of the selected browser session. */
internal class YouTubeMusicClient(
    private val cookie: String = YouTubeSession.authHeaders(ORIGIN)["Cookie"].orEmpty(),
    authUser: String = "0",
    private val transport: (String, JSONObject?, Map<String, String>) -> String = ::youtubeHttp
) {
    companion object { const val ORIGIN = "https://music.youtube.com" }
    var scope = YouTubeScope(authUser = authUser); private set

    suspend fun resolveScope() = withContext(Dispatchers.IO) {
        check(YouTubeSession.hasAuthCookies(cookie)) { "Sign in to YouTube Music to load your library." }
        val html = transport("$ORIGIN/?authuser=${scope.authUser}", null, headers())
        check(!Regex("\"LOGGED_IN\"\\s*:\\s*false").containsMatchIn(html)) {
            "Your YouTube session has expired. Please reconnect your browser profile."
        }
        scope = parseYouTubeScope(html)
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
            put("Cookie", cookie); put("Authorization", authorization)
            put("Origin", ORIGIN); put("X-Origin", ORIGIN); put("Referer", "$ORIGIN/")
            put("X-Goog-AuthUser", scope.authUser); put("X-Youtube-Bootstrap-Logged-In", "true")
            put("X-YouTube-Client-Name", "67"); put("X-YouTube-Client-Version", scope.version)
            put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/141.0.0.0 Safari/537.36")
            scope.pageId?.let { put("X-Goog-PageId", it) }
            scope.visitor?.let { put("X-Goog-Visitor-Id", it) }
        }
    }

    private suspend fun post(endpoint: String, extras: JSONObject = JSONObject()): JSONObject = withContext(Dispatchers.IO) {
        val client = JSONObject().put("clientName", "WEB_REMIX").put("clientVersion", scope.version)
            .put("hl", "en").put("gl", "IN")
        scope.visitor?.let { client.put("visitorData", it) }
        val user = JSONObject().put("lockedSafetyMode", false)
        scope.dataSyncId?.let { user.put("onBehalfOfUser", it) }
        extras.put("context", JSONObject().put("client", client).put("user", user)
            .put("request", JSONObject().put("useSsl", true)))
        val result = JSONObject(transport("$ORIGIN/youtubei/v1/$endpoint?prettyPrint=false", extras, headers()))
        check(!result.has("error")) { "YouTube couldn't load this account. Please reconnect or try again." }
        result
    }

    suspend fun identity(): YouTubeIdentity = parseYouTubeIdentity(post("account/account_menu"))
        ?: error("YouTube did not confirm the account. Please reconnect your browser profile.")

    suspend fun browseHome(browseId: String = "FEmusic_home", continuation: String? = null): JSONObject =
        post("browse", if (continuation == null) JSONObject().put("browseId", browseId) else JSONObject().put("continuation", continuation))

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

private val youtubeHttpClient by lazy { NetworkClientProvider.okHttpClient.newBuilder().callTimeout(25, TimeUnit.SECONDS).build() }
private fun youtubeHttp(url: String, body: JSONObject?, headers: Map<String, String>): String {
    val request = Request.Builder().url(url)
    headers.forEach { (key, value) -> request.header(key, value) }
    if (body != null) request.post(body.toString().toRequestBody("application/json".toMediaType()))
    return youtubeHttpClient.newCall(request.build()).execute().use { response ->
        check(response.isSuccessful) { if (response.code in listOf(401, 403))
            "Your YouTube session needs reconnecting. Select your browser profile again."
            else "YouTube couldn't load your library. Please try again." }
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
        youtubeNodes(container, "musicResponsiveListItemRenderer").flatMap { row ->
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
