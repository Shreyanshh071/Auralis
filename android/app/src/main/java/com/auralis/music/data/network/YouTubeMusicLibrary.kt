package com.auralis.music.data.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * The signed-in user's own YouTube Music library ([YouTubeSession]): their playlists, private
 * ones included, and Liked Music, so they can pick what to import instead of pasting links.
 */
object YouTubeMusicLibrary {
    private const val TAG = "YouTubeMusicLibrary"
    private const val ORIGIN = "https://music.youtube.com"
    internal const val CLIENT_VERSION = "1.20260213.01.00"
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
    const val LIKED_MUSIC_ID = "LM"

    data class LibraryPlaylist(
        /** Playlist ID without the "VL" browse prefix; [LIKED_MUSIC_ID] for Liked Music. */
        val id: String,
        val title: String,
        val subtitle: String,
        val thumbnail: String?
    ) {
        val isLikedMusic: Boolean get() = id == LIKED_MUSIC_ID
    }

    private val client = OkHttpClient.Builder().proxyAuthenticator(com.auralis.music.data.network.ContentProxy.authenticator)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /** Headers that make a music.youtube.com request count as the signed-in user; empty when signed out. */
    fun signedInHeaders(): Map<String, String> = YouTubeSession.authHeaders(ORIGIN)

    /** The user's playlists (Liked Music first), or null when signed out or YouTube didn't answer. */
    suspend fun fetchPlaylists(): List<LibraryPlaylist>? = withContext(Dispatchers.IO) {
        if (!YouTubeSession.isSignedIn) return@withContext null
        try {
            val found = LinkedHashMap<String, LibraryPlaylist>()
            var json = browse(JSONObject().put("browseId", "FEmusic_liked_playlists"), continuation = null)
                ?: return@withContext null
            var pages = 0
            while (true) {
                collectPlaylists(json, found)
                val token = findContinuation(json) ?: break
                if (++pages > 20) break
                json = browse(JSONObject(), continuation = token) ?: break
            }
            // Liked Music is always there even if the grid didn't list it.
            if (LIKED_MUSIC_ID !in found) {
                found[LIKED_MUSIC_ID] = LibraryPlaylist(LIKED_MUSIC_ID, "Liked Music", "Auto playlist", null)
            }
            val liked = found.remove(LIKED_MUSIC_ID)!!
            listOf(liked) + found.values
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't load YouTube Music playlists: ${e.javaClass.simpleName} ${e.message}")
            null
        }
    }

    private fun browse(body: JSONObject, continuation: String?): JSONObject? {
        body.put("context", JSONObject().put("client", JSONObject().apply {
            put("clientName", "WEB_REMIX")
            put("clientVersion", CLIENT_VERSION)
            put("hl", "en")
            put("gl", "US")
            if (YouTubeSession.visitorData.isNotBlank()) put("visitorData", YouTubeSession.visitorData)
        }))
        val url = if (continuation == null) "$ORIGIN/youtubei/v1/browse?prettyPrint=false"
        else "$ORIGIN/youtubei/v1/browse?ctoken=$continuation&continuation=$continuation&type=next&prettyPrint=false"
        val request = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .header("User-Agent", USER_AGENT)
            .header("Origin", ORIGIN)
            .header("Referer", "$ORIGIN/")
            .header("X-YouTube-Client-Name", "67")
            .header("X-YouTube-Client-Version", CLIENT_VERSION)
            .apply { signedInHeaders().forEach { (k, v) -> header(k, v) } }
            .build()
        return client.newCall(request).execute().use { res ->
            if (!res.isSuccessful) {
                Log.w(TAG, "Library browse got HTTP ${res.code}")
                return null
            }
            JSONObject(res.body?.string() ?: return null)
        }
    }

    /** One browse page's playlist cards and its continuation token (visible for tests). */
    internal fun parsePage(json: JSONObject): Pair<List<LibraryPlaylist>, String?> {
        val found = LinkedHashMap<String, LibraryPlaylist>()
        collectPlaylists(json, found)
        return found.values.toList() to findContinuation(json)
    }

    /** Every playlist card (musicTwoRowItemRenderer pointing at a "VL…" playlist) in the response. */
    private fun collectPlaylists(node: Any?, out: MutableMap<String, LibraryPlaylist>) {
        when (node) {
            is JSONObject -> {
                node.optJSONObject("musicTwoRowItemRenderer")?.let { card ->
                    parseCard(card)?.let { out.putIfAbsent(it.id, it) }
                }
                for (key in node.keys()) collectPlaylists(node.opt(key), out)
            }
            is JSONArray -> for (i in 0 until node.length()) collectPlaylists(node.opt(i), out)
        }
    }

    private fun parseCard(card: JSONObject): LibraryPlaylist? {
        val browseId = card.optJSONObject("navigationEndpoint")
            ?.optJSONObject("browseEndpoint")?.optString("browseId").orEmpty()
        if (!browseId.startsWith("VL")) return null
        val id = browseId.removePrefix("VL")
        // "Episodes for later" is podcasts, not music.
        if (id == "SE") return null
        fun runs(obj: JSONObject?): String {
            val arr = obj?.optJSONArray("runs") ?: return ""
            return (0 until arr.length()).joinToString("") { arr.optJSONObject(it)?.optString("text").orEmpty() }
        }
        val title = runs(card.optJSONObject("title")).ifBlank { return null }
        val thumbs = card.optJSONObject("thumbnailRenderer")?.let { tr ->
            tr.optJSONObject("musicThumbnailRenderer")?.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
                ?: tr.optJSONObject("croppedSquareThumbnailRenderer")?.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
                ?: tr.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
                ?: tr.optJSONArray("thumbnails")
        } ?: card.optJSONObject("thumbnail")?.let { t ->
            t.optJSONObject("musicThumbnailRenderer")?.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
                ?: t.optJSONArray("thumbnails")
        }
        val thumbnail = YouTubePlaylistImporter.extractBestThumbnailUrl(thumbs)?.takeIf { it.isNotBlank() }
        return LibraryPlaylist(id, title, runs(card.optJSONObject("subtitle")), thumbnail)
    }

    private fun findContinuation(node: Any?): String? {
        when (node) {
            is JSONObject -> {
                for (key in listOf("nextContinuationData", "nextGridContinuationData")) {
                    node.optJSONObject(key)?.optString("continuation")?.takeIf { it.isNotBlank() }?.let { return it }
                }
                node.optJSONObject("continuationCommand")?.optString("token")?.takeIf { it.isNotBlank() }?.let { return it }
                for (key in node.keys()) findContinuation(node.opt(key))?.let { return it }
            }
            is JSONArray -> for (i in 0 until node.length()) findContinuation(node.opt(i))?.let { return it }
        }
        return null
    }
}
