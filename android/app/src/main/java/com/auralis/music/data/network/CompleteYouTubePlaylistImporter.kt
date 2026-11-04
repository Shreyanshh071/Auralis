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
class CompleteYouTubePlaylistImporter(private val client: OkHttpClient = YouTubeMusicLibrary.client) {
    /** A YouTube front end that can serve a playlist; each lays its page out differently. */
    private class Site(val origin: String, val clientName: String, val clientId: String, val version: String)

    private val music = Site("https://music.youtube.com", "WEB_REMIX", "67", "1.20260213.01.00")
    private val web = Site("https://www.youtube.com", "WEB", "1", "2.20260213.01.00")

    suspend fun importPlaylist(input: String): Playlist? = importPlaylistById(
        YouTubePlaylistImporter.extractPlaylistId(input) ?: throw IllegalArgumentException("Invalid YouTube playlist link"))

    /** A private playlist only answers the signed-in user, so every signed-in route goes before the public ones. */
    suspend fun importPlaylistById(id: String): Playlist = withContext(Dispatchers.IO) {
        val cleanId = id.removePrefix("VL")
        val sites = if (cleanId.startsWith("MPRE")) listOf(music) else listOf(music, web)
        val signedIn = sites.filter { YouTubeSession.isSignedIn }.map { it to YouTubeMusicLibrary.signedInHeaders() }
            .filter { (_, headers) -> headers.isNotEmpty() }
        var failure: Exception? = null
        for ((site, headers) in signedIn + sites.map { it to emptyMap() }) {
            try {
                read(cleanId, site, headers)?.let { return@withContext it }
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                android.util.Log.w(TAG, "$cleanId via ${site.clientName}, signed in=${headers.isNotEmpty()}: ${e.message}")
                if (failure == null) failure = e
            }
        }
        throw failure ?: IncompletePlaylistImport("YouTube did not return this playlist. Nothing was replaced. Sign in to YouTube again if it is private.")
    }

    /** The whole playlist from one route, or null when this route doesn't show it. */
    private suspend fun read(cleanId: String, site: Site, headers: Map<String, String>): Playlist? {
        val context = JSONObject().put("client", JSONObject().put("clientName", site.clientName)
            .put("clientVersion", site.version).put("hl", "en").put("gl", "US")
            .apply { if (YouTubeSession.visitorData.isNotBlank()) put("visitorData", YouTubeSession.visitorData) })
        val initial = JSONObject().put("context", context).put("browseId", if (cleanId.startsWith("MPRE")) cleanId else "VL$cleanId")
        var root = fetch(initial, headers, site)
        val header = findObject(root, setOf("musicResponsiveHeaderRenderer", "musicDetailHeaderRenderer", "playlistHeaderRenderer", "pageHeaderRenderer"))
        val title = text(header?.optJSONObject("title"))
            .ifBlank { root.optJSONObject("metadata")?.optJSONObject("playlistMetadataRenderer")?.optString("title").orEmpty() }
            .ifBlank { "YouTube playlist" }
        val expected = header?.toString()?.let { Regex("([0-9][0-9,\\s]*)\\s+(?:tracks|songs|videos)", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1)?.filter(Char::isDigit)?.toIntOrNull() }
        val cover = YouTubePlaylistImporter.extractPlaylistThumbnail(root)
        if (page(root) == null) {
            if (expected == 0) return Playlist(cleanId, title, "Imported from YouTube Music", cover, emptyList())
            android.util.Log.w(TAG, "$cleanId via ${site.clientName}, signed in=${headers.isNotEmpty()}: no song list. ${describe(root)}")
            return null
        }
        val tracks = mutableListOf<Track>()
        val seenTokens = mutableSetOf<String>()
        var pages = 0
        while (true) {
            val page = page(root) ?: throw IncompletePlaylistImport("YouTube playlist page could not be read. Nothing was replaced.")
            tracks += parseRows(page.rows, cleanId, tracks.size)
            if (expected != null && tracks.size >= expected) break
            val token = page.continuation ?: break
            if (!seenTokens.add(token) || ++pages > 500) throw IncompletePlaylistImport("YouTube playlist pagination stopped before completion. Nothing was replaced.")
            root = fetch(JSONObject().put("context", context).put("continuation", token), headers, site)
        }
        // No pages left: YouTube's count still includes songs it hides from the list.
        PlaylistImportIntegrity.requireComplete("YouTube", tracks.size, expected, allowMissing = true)
        return Playlist(cleanId, title, "Imported from YouTube Music", cover, tracks)
    }

    /** What YouTube sent instead of a song list: whether it saw the account, and the page's parts. */
    private fun describe(root: JSONObject): String {
        val loggedIn = Regex("\"logged_in\",\"value\":\"(\\d)\"").find(root.optJSONObject("responseContext").toString())?.groupValues?.get(1)
        val renderers = linkedSetOf<String>()
        fun walk(node: Any?) {
            if (renderers.size >= 20) return
            if (node is JSONObject) for (key in node.keys()) {
                if (key.endsWith("Renderer")) renderers += key
                walk(node.opt(key))
            } else if (node is JSONArray) for (i in 0 until node.length()) walk(node.opt(i))
        }
        walk(root)
        return "logged_in=$loggedIn keys=${root.keys().asSequence().toList()} renderers=$renderers"
    }

    private suspend fun fetch(payload: JSONObject, headers: Map<String, String>, site: Site): JSONObject {
        var failure: Exception? = null
        repeat(3) { attempt ->
            try {
                val request = Request.Builder().url("${site.origin}/youtubei/v1/browse?prettyPrint=false")
                    .header("User-Agent", SpotifySession.USER_AGENT).header("Origin", site.origin)
                    .header("Referer", "${site.origin}/").header("X-YouTube-Client-Name", site.clientId)
                    .header("X-YouTube-Client-Version", site.version)
                    .apply { (if (site === music) headers else YouTubeMusicLibrary.signedInHeaders(site.origin).takeIf { headers.isNotEmpty() }.orEmpty()).forEach { (name, value) -> header(name, value) } }
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
        // Lockup rows hold menus with tokens of their own, so only their closing marker counts.
        fun rowsToken(rows: JSONArray): String? {
            val last = rows.optJSONObject(rows.length() - 1)
            val lockups = (0 until rows.length()).any { rows.optJSONObject(it)?.has("lockupViewModel") == true }
            return if (!lockups) token(rows) else last?.optJSONObject("continuationItemViewModel")?.let(::token)
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
                    // Regular YouTube keeps the next-page marker beside the song list, not inside it.
                    if (contents != null) for (i in 0 until contents.length()) {
                        val rows = contents.optJSONObject(i)?.optJSONObject("itemSectionRenderer")?.optJSONArray("contents") ?: continue
                        if (holdsSongs(rows)) return Page(rows, token(findObject(contents, setOf("continuationItemViewModel"))))
                    }
                }
                node.optJSONObject("appendContinuationItemsAction")?.optJSONArray("continuationItems")?.let { return Page(it, rowsToken(it)) }
                // YouTube renames its shelves; any list holding song rows is the playlist.
                node.optJSONArray("contents")?.takeIf(::holdsSongs)?.let { rows ->
                    return Page(rows, token(node.optJSONArray("continuations")) ?: rowsToken(rows))
                }
                for (key in node.keys()) locate(node.opt(key))?.let { return it }
            } else if (node is JSONArray) for (i in 0 until node.length()) locate(node.opt(i))?.let { return it }
            return null
        }
        // Later pages also carry the playlist's stale page; the appended rows are the real answer.
        return locate(root.opt("onResponseReceivedActions")) ?: locate(root)
    }

    private fun holdsSongs(rows: JSONArray): Boolean = (0 until rows.length()).any { i ->
        val row = rows.optJSONObject(i)
        row?.has("musicResponsiveListItemRenderer") == true || row?.has("playlistVideoRenderer") == true ||
            row?.has("lockupViewModel") == true
    }

    /** Regular YouTube's song row. */
    private fun lockupTrack(lockup: JSONObject): Track? {
        val id = lockup.optString("contentId").takeIf(String::isNotBlank) ?: return null
        val meta = lockup.optJSONObject("metadata")?.optJSONObject("lockupMetadataViewModel")
        val rows = findObject(meta, setOf("contentMetadataViewModel"))?.optJSONArray("metadataRows")
        val artist = rows?.optJSONObject(0)?.optJSONArray("metadataParts")?.optJSONObject(0)
            ?.optJSONObject("text")?.optString("content").orEmpty().removeSuffix(" - Topic")
        val image = lockup.optJSONObject("contentImage")
        val duration = findObject(image, setOf("thumbnailBadgeViewModel"))?.optString("text").orEmpty()
            .split(':').fold(0L) { total, part -> total * 60 + (part.toLongOrNull() ?: 0L) }
        val thumbnail = YouTubePlaylistImporter.extractBestThumbnailUrl(findObject(image, setOf("image"))?.optJSONArray("sources")).orEmpty()
        return Track(id, meta?.optJSONObject("title")?.optString("content").orEmpty().ifBlank { "Unavailable YouTube video" },
            artist, duration = duration, thumbnail = thumbnail)
    }

    internal fun parseRows(rows: JSONArray, playlistId: String, start: Int): List<Track> = buildList {
        for (i in 0 until rows.length()) {
            val wrapper = rows.optJSONObject(i) ?: throw IncompletePlaylistImport("Unreadable YouTube playlist entry")
            if (wrapper.has("continuationItemRenderer") || wrapper.has("continuationItemViewModel")) continue
            val lockup = wrapper.optJSONObject("lockupViewModel")
            if (lockup != null) {
                lockupTrack(lockup)?.let(::add)
                continue
            }
            val item = wrapper.optJSONObject("musicResponsiveListItemRenderer") ?: wrapper.optJSONObject("playlistVideoRenderer")
            if (item == null) {
                android.util.Log.w("YouTubeImport", "Skipped entry ${start + size + 1} of $playlistId: ${wrapper.keys().asSequence().toList()}")
                continue
            }
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

    private companion object { const val TAG = "YouTubeImport" }
}
