package com.auralis.music.data.network

import com.auralis.music.domain.model.Playlist
import com.auralis.music.domain.model.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/** Explicit imports preserve source order and repeats; discovery/Shorts filters do not apply. */
class CompleteYouTubePlaylistImporter(private val client: OkHttpClient = NetworkClientProvider.okHttpClient) {
    private val version = "1.20260213.01.00"
    suspend fun importPlaylist(input: String): Playlist? = importPlaylistById(
        YouTubePlaylistImporter.extractPlaylistId(input) ?: throw IllegalArgumentException("Invalid YouTube playlist link"))

    suspend fun importPlaylistById(id: String): Playlist = withContext(Dispatchers.IO) {
        val cleanId = id.removePrefix("VL")
        val context = JSONObject().put("client", JSONObject().put("clientName", "WEB_REMIX")
            .put("clientVersion", version).put("hl", "en").put("gl", "US")
            .apply { if (YouTubeSession.visitorData.isNotBlank()) put("visitorData", YouTubeSession.visitorData) })
        val initial = JSONObject().put("context", context).put("browseId", if (cleanId.startsWith("MPRE")) cleanId else "VL$cleanId")
        var headers = YouTubeMusicLibrary.signedInHeaders()
        var root = try { fetch(initial, headers) } catch (e: CancellationException) { throw e } catch (e: Exception) {
            if (headers.isEmpty()) throw e
            headers = emptyMap(); fetch(initial, headers)
        }
        if (page(root) == null && headers.isNotEmpty()) { headers = emptyMap(); root = fetch(initial, headers) }
        val header = findObject(root, setOf("musicResponsiveHeaderRenderer", "musicDetailHeaderRenderer", "playlistHeaderRenderer"))
        val title = text(header?.optJSONObject("title")).ifBlank { "YouTube playlist" }
        val expected = header?.toString()?.let { Regex("([0-9][0-9,\\s]*)\\s+(?:tracks|songs|videos)", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1)?.filter(Char::isDigit)?.toIntOrNull() }
        val cover = YouTubePlaylistImporter.extractPlaylistThumbnail(root)
        val tracks = mutableListOf<Track>()
        val seenTokens = mutableSetOf<String>()
        var pages = 0
        while (true) {
            val page = page(root) ?: throw IncompletePlaylistImport("YouTube playlist page could not be read. Nothing was replaced.")
            tracks += parseRows(page.rows, cleanId, tracks.size)
            if (expected != null && tracks.size >= expected) break
            val token = page.continuation ?: break
            if (!seenTokens.add(token) || ++pages > 500) throw IncompletePlaylistImport("YouTube playlist pagination stopped before completion. Nothing was replaced.")
            root = fetch(JSONObject().put("context", context).put("continuation", token), headers)
        }
        PlaylistImportIntegrity.requireComplete("YouTube", tracks.size, expected)
        Playlist(cleanId, title, "Imported from YouTube Music", cover, tracks)
    }

    private suspend fun fetch(payload: JSONObject, headers: Map<String, String>): JSONObject {
        var failure: Exception? = null
        repeat(3) { attempt ->
            try {
                val request = Request.Builder().url("https://music.youtube.com/youtubei/v1/browse?prettyPrint=false")
                    .header("User-Agent", SpotifySession.USER_AGENT).header("Origin", "https://music.youtube.com")
                    .header("Referer", "https://music.youtube.com/").header("X-YouTube-Client-Name", "67")
                    .header("X-YouTube-Client-Version", version)
                    .apply { headers.forEach { (name, value) -> header(name, value) } }
                    .post(payload.toString().toRequestBody("application/json".toMediaType())).build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("YouTube returned HTTP ${response.code}")
                    val root = JSONObject(response.body?.string() ?: throw IOException("YouTube returned an empty page"))
                    if (root.has("error")) throw IOException("YouTube could not read this playlist")
                    return root
                }
            } catch (e: CancellationException) { throw e } catch (e: Exception) { failure = e }
            if (attempt < 2) delay(300L * (attempt + 1))
        }
        throw IncompletePlaylistImport("Could not finish reading the YouTube playlist (${failure?.message}). Nothing was replaced. Please retry.")
    }

    internal data class Page(val rows: JSONArray, val continuation: String?)
    internal fun page(root: JSONObject): Page? {
        fun token(node: Any?): String? = when (node) {
            is JSONObject -> node.optJSONObject("continuationCommand")?.optString("token")?.takeIf(String::isNotBlank)
                ?: node.optJSONObject("nextContinuationData")?.optString("continuation")?.takeIf(String::isNotBlank)
                ?: node.keys().asSequence().mapNotNull { token(node.opt(it)) }.firstOrNull()
            is JSONArray -> (0 until node.length()).firstNotNullOfOrNull { token(node.opt(it)) }
            else -> null
        }
        fun locate(node: Any?): Page? {
            if (node is JSONObject) {
                for (key in listOf("musicPlaylistShelfRenderer", "musicPlaylistShelfContinuation", "musicShelfContinuation", "playlistVideoListRenderer")) {
                    val shelf = node.optJSONObject(key) ?: continue
                    val rows = shelf.optJSONArray("contents") ?: continue
                    return Page(rows, token(shelf.optJSONArray("continuations")) ?: token(rows))
                }
                val section = node.optJSONObject("sectionListRenderer")
                if (section != null) {
                    val contents = section.optJSONArray("contents")
                    if (contents != null) for (i in 0 until contents.length()) {
                        val shelf = contents.optJSONObject(i)?.optJSONObject("musicPlaylistShelfRenderer") ?: continue
                        return Page(shelf.optJSONArray("contents") ?: JSONArray(), token(shelf) ?: token(section.optJSONArray("continuations")))
                    }
                }
                node.optJSONObject("appendContinuationItemsAction")?.optJSONArray("continuationItems")?.let { return Page(it, token(it)) }
                for (key in node.keys()) locate(node.opt(key))?.let { return it }
            } else if (node is JSONArray) for (i in 0 until node.length()) locate(node.opt(i))?.let { return it }
            return null
        }
        return locate(root)
    }

    internal fun parseRows(rows: JSONArray, playlistId: String, start: Int): List<Track> = buildList {
        for (i in 0 until rows.length()) {
            val wrapper = rows.optJSONObject(i) ?: throw IncompletePlaylistImport("Unreadable YouTube playlist entry")
            if (wrapper.has("continuationItemRenderer")) continue
            val item = wrapper.optJSONObject("musicResponsiveListItemRenderer") ?: wrapper.optJSONObject("playlistVideoRenderer")
                ?: throw IncompletePlaylistImport("Unreadable YouTube playlist entry at ${start + size + 1}. Nothing was replaced.")
            val columns = item.optJSONArray("flexColumns")
            fun column(index: Int) = columns?.optJSONObject(index)?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")?.optJSONObject("text")
            val name = text(column(0)).ifBlank { text(item.optJSONObject("title")) }.ifBlank { "Unavailable YouTube video" }
            val videoId = item.optJSONObject("playlistItemData")?.optString("videoId")?.takeIf(String::isNotBlank)
                ?: item.optString("videoId").takeIf(String::isNotBlank)
                ?: findObject(item, setOf("watchEndpoint"))?.optString("videoId")?.takeIf(String::isNotBlank)
            val fixed = item.optJSONArray("fixedColumns")?.optJSONObject(0)?.optJSONObject("musicResponsiveListItemFixedColumnRenderer")?.optJSONObject("text")
            val duration = text(fixed).ifBlank { text(item.optJSONObject("lengthText")) }.split(':').fold(0L) { total, part -> total * 60 + (part.toLongOrNull() ?: 0L) }
            val artist = text(column(1)).substringBefore(" • ").ifBlank { text(item.optJSONObject("shortBylineText")) }
            val thumbs = findObject(item.optJSONObject("thumbnail"), setOf("thumbnail"))?.optJSONArray("thumbnails")
                ?: item.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
            val thumbnail = YouTubePlaylistImporter.extractBestThumbnailUrl(thumbs).orEmpty()
            add(Track(videoId ?: "unavailable:youtube:$playlistId:${start + size}", name, artist,
                album = text(column(2)).takeIf(String::isNotBlank), duration = duration, thumbnail = thumbnail))
        }
    }

    private fun text(node: JSONObject?): String = node?.optString("simpleText")?.takeIf(String::isNotBlank)
        ?: node?.optJSONArray("runs")?.let { runs -> (0 until runs.length()).joinToString("") { runs.optJSONObject(it)?.optString("text").orEmpty() } }.orEmpty()
    private fun findObject(node: Any?, keys: Set<String>): JSONObject? {
        if (node is JSONObject) {
            keys.forEach { node.optJSONObject(it)?.let { value -> return value } }
            for (key in node.keys()) findObject(node.opt(key), keys)?.let { return it }
        } else if (node is JSONArray) for (i in 0 until node.length()) findObject(node.opt(i), keys)?.let { return it }
        return null
    }
}
