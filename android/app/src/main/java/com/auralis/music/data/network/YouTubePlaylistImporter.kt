package com.auralis.music.data.network

import com.auralis.music.domain.model.Playlist
import com.auralis.music.domain.model.Track
import com.auralis.music.domain.model.TrackSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.TimeUnit

class YouTubePlaylistImporter(
    private val client: OkHttpClient = OkHttpClient.Builder().proxyAuthenticator(com.auralis.music.data.network.ContentProxy.authenticator)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
) {
    companion object {
        fun isYouTubeMusicUrl(input: String): Boolean {
            val trimmed = input.trim()
            if (trimmed.isBlank()) return false
            val normalized = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "https://$trimmed"
            return try {
                val uri = URI(normalized)
                val host = uri.host?.lowercase() ?: ""
                host == "music.youtube.com"
            } catch (_: Exception) {
                false
            }
        }

        fun isStandardYouTubeUrl(input: String): Boolean {
            val trimmed = input.trim()
            if (trimmed.isBlank()) return false
            val normalized = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "https://$trimmed"
            return try {
                val uri = URI(normalized)
                val host = uri.host?.lowercase() ?: ""
                host == "youtube.com" || host == "www.youtube.com" || host == "m.youtube.com" || host == "youtu.be"
            } catch (_: Exception) {
                false
            }
        }

        fun extractPlaylistId(input: String): String? {
            val trimmed = input.trim()
            if (trimmed.isBlank()) return null

            // Direct ID support
            if (trimmed.startsWith("OLAK5uy_") || trimmed.startsWith("RDCLAK5uy_") || trimmed.startsWith("PL") || trimmed.startsWith("VLPL") || trimmed.startsWith("UU") || trimmed.startsWith("FL") || trimmed.startsWith("LM")) {
                return if (trimmed.startsWith("VL")) trimmed.substring(2) else trimmed
            }

            try {
                val normalized = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "https://$trimmed"
                val uri = URI(normalized)
                val query = uri.query
                if (!query.isNullOrBlank()) {
                    val params = query.split("&").associate {
                        val parts = it.split("=")
                        if (parts.size == 2) parts[0] to parts[1] else parts[0] to ""
                    }
                    val listId = params["list"]
                    if (!listId.isNullOrBlank()) {
                        return if (listId.startsWith("VL")) listId.substring(2) else listId
                    }
                }

                val path = uri.path ?: ""
                val match = Regex("/playlist/([A-Za-z0-9_-]+)").find(path)
                if (match != null) {
                    val id = match.groupValues[1]
                    return if (id.startsWith("VL")) id.substring(2) else id
                }
                val browseMatch = Regex("/browse/([A-Za-z0-9_-]+)").find(path)
                if (browseMatch != null) {
                    val id = browseMatch.groupValues[1]
                    return if (id.startsWith("VL")) id.substring(2) else id
                }
            } catch (_: Exception) {}

            return null
        }

        // Moved to shared PlaylistThumbnails (search uses it too); kept here for existing callers.
        fun upgradeCoverUrl(rawUrl: String): String = PlaylistThumbnails.upgradeCoverUrl(rawUrl)

        fun extractBestThumbnailUrl(thumbnails: JSONArray?): String? = PlaylistThumbnails.extractBestThumbnailUrl(thumbnails)

        internal fun extractPlaylistThumbnail(json: JSONObject): String? = PlaylistThumbnails.extractPlaylistThumbnail(json)

        private fun extractPlaylistAuthor(json: JSONObject): String? {
            fun extractAuthorFromHeader(header: JSONObject?): String? {
                if (header == null) return null
                val effective = header.optJSONObject("musicEditablePlaylistDetailHeaderRenderer")
                    ?.optJSONObject("header")?.optJSONObject("musicResponsiveHeaderRenderer")
                    ?: header.optJSONObject("musicEditablePlaylistDetailHeaderRenderer")
                        ?.optJSONObject("header")?.optJSONObject("musicDetailHeaderRenderer")
                    ?: header.optJSONObject("header")?.optJSONObject("musicResponsiveHeaderRenderer")
                    ?: header.optJSONObject("header")?.optJSONObject("musicDetailHeaderRenderer")
                    ?: header

                val strapline = effective.optJSONObject("straplineTextOne")
                    ?.optJSONArray("runs")?.optJSONObject(0)?.optString("text")
                    ?: effective.optJSONObject("subtitle")
                        ?.optJSONArray("runs")?.optJSONObject(0)?.optString("text")
                return strapline?.trim()?.ifBlank { null }
            }

            val contents = json.optJSONObject("contents")
            val tabs = contents?.optJSONObject("twoColumnBrowseResultsRenderer")?.optJSONArray("tabs")
                ?: contents?.optJSONObject("singleColumnBrowseResultsRenderer")?.optJSONArray("tabs")

            if (tabs != null && tabs.length() > 0) {
                val tab0 = tabs.optJSONObject(0)?.optJSONObject("tabRenderer")?.optJSONObject("content")
                val sectionList = tab0?.optJSONObject("sectionListRenderer")
                val sectionContents = sectionList?.optJSONArray("contents")
                if (sectionContents != null) {
                    for (i in 0 until sectionContents.length()) {
                        val sec = sectionContents.optJSONObject(i) ?: continue
                        for (k in listOf("musicResponsiveHeaderRenderer", "musicDetailHeaderRenderer", "musicEditablePlaylistDetailHeaderRenderer")) {
                            sec.optJSONObject(k)?.let { extractAuthorFromHeader(it)?.let { return it } }
                        }
                        extractAuthorFromHeader(sec)?.let { return it }
                    }
                }
                extractAuthorFromHeader(sectionList?.optJSONObject("header"))?.let { return it }
            }

            extractAuthorFromHeader(json.optJSONObject("header"))?.let { return it }
            return null
        }
    }

    suspend fun importPlaylist(urlOrId: String): Playlist? = withContext(Dispatchers.IO) {
        val trimmed = urlOrId.trim()
        val playlistId = extractPlaylistId(trimmed)
            ?: throw IllegalArgumentException("Please enter a valid YouTube or YouTube Music playlist link (music.youtube.com/playlist?list=... or youtube.com/playlist?list=...)")

        importPlaylistById(playlistId)
    }

    suspend fun importPlaylistById(playlistId: String): Playlist? = withContext(Dispatchers.IO) {
        val cleanId = if (playlistId.startsWith("VL")) playlistId.substring(2) else playlistId
        val browseId = if (cleanId.startsWith("MPRE") || cleanId.startsWith("FEmusic_") || cleanId.startsWith("UC")) cleanId else "VL$cleanId"

        try {
            val signedInHeaders = YouTubeMusicLibrary.signedInHeaders()
            val clientContext = JSONObject().apply {
                put("client", JSONObject().apply {
                    put("clientName", "WEB_REMIX")
                    put("clientVersion", YouTubeMusicLibrary.CLIENT_VERSION)
                    put("hl", "en")
                    put("gl", "US")
                    if (YouTubeSession.visitorData.isNotBlank()) put("visitorData", YouTubeSession.visitorData)
                })
            }

            // 1. Fetch initial batch via YouTube Music InnerTube Browse
            val initialPayload = JSONObject().apply {
                put("context", clientContext)
                put("browseId", browseId)
            }

            var useSignedIn = signedInHeaders.isNotEmpty()
            var json = fetchBrowsePage(initialPayload, null, if (useSignedIn) signedInHeaders else emptyMap())
            var playlistTitle = json?.let(::extractPlaylistTitle) ?: "Imported Playlist"
            var playlistAuthor = json?.let(::extractPlaylistAuthor)
            var playlistCover = json?.let(::extractPlaylistThumbnail)
            val allTracks = mutableListOf<Track>()
            if (json != null) allTracks.addAll(extractTracksFromJson(json, playlistTitle, playlistAuthor, playlistCover))
            // A stale account cookie can hide an otherwise public or unlisted playlist.
            // Retry without account headers before deciding the link is unavailable.
            if (allTracks.isEmpty()) {
                val publicPage = fetchBrowsePage(initialPayload, null, emptyMap())
                if (publicPage != null) {
                    json = publicPage
                    useSignedIn = false
                    playlistTitle = extractPlaylistTitle(publicPage) ?: "Imported Playlist"
                    playlistAuthor = extractPlaylistAuthor(publicPage)
                    playlistCover = extractPlaylistThumbnail(publicPage)
                    allTracks.addAll(extractTracksFromJson(publicPage, playlistTitle, playlistAuthor, playlistCover))
                }
            }
            if (json == null || allTracks.isEmpty()) return@withContext null

            // 2. Fetch continuations to import all remaining songs (up to 5,000 tracks)
            var continuationToken = extractPlaylistContinuationToken(json)
            var page = 0
            val maxPages = 50

            while (!continuationToken.isNullOrBlank() && page < maxPages) {
                page++
                try {
                    val contPayload = JSONObject().apply {
                        put("context", clientContext)
                        put("continuation", continuationToken)
                    }

                    val contJson = fetchBrowsePage(contPayload, continuationToken, if (useSignedIn) signedInHeaders else emptyMap())
                        ?: fetchBrowsePage(contPayload, continuationToken, emptyMap()) ?: break

                    val newTracks = extractTracksFromJson(contJson, playlistTitle, playlistAuthor, playlistCover)
                    allTracks.addAll(newTracks)

                    val nextToken = extractPlaylistContinuationToken(contJson)
                    if (nextToken == continuationToken) break
                    continuationToken = nextToken
                } catch (_: Exception) {
                    break
                }
            }

            val validTracks = YouTubeShortsFilter.filter(allTracks.filter { it.id.isNotBlank() })
            if (validTracks.isNotEmpty()) {
                val finalCover = playlistCover?.ifBlank { null }
                return@withContext Playlist(
                    id = cleanId,
                    title = playlistTitle,
                    description = playlistAuthor ?: "Imported from YouTube Music",
                    coverUrl = finalCover,
                    tracks = validTracks
                )
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("YouTubePlaylistImporter", "Could not import playlist $cleanId: ${e.message}")
            if (e is IllegalArgumentException) throw e
        }

        null
    }

    private fun fetchBrowsePage(payload: JSONObject, continuation: String?, headers: Map<String, String>): JSONObject? {
        val url = if (continuation == null) "https://music.youtube.com/youtubei/v1/browse?prettyPrint=false"
        else "https://music.youtube.com/youtubei/v1/browse?continuation=$continuation&ctoken=$continuation&type=next&prettyPrint=false"
        val request = Request.Builder()
            .url(url)
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36")
            .header("Referer", "https://music.youtube.com/")
            .header("Origin", "https://music.youtube.com")
            .header("X-YouTube-Client-Name", "67")
            .header("X-YouTube-Client-Version", YouTubeMusicLibrary.CLIENT_VERSION)
            .apply { headers.forEach { (key, value) -> header(key, value) } }
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    android.util.Log.w("YouTubePlaylistImporter", "Browse returned HTTP ${response.code}")
                    null
                } else response.body?.string()?.let(::JSONObject)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("YouTubePlaylistImporter", "Browse request failed: ${e.message}")
            null
        }
    }

    private fun extractPlaylistTitle(json: JSONObject): String? {
        // 1. Direct check in microformatDataRenderer (top-level metadata in YouTube Music InnerTube)
        val microformatTitle = json.optJSONObject("microformat")
            ?.optJSONObject("microformatDataRenderer")
            ?.optString("title")
        if (!microformatTitle.isNullOrBlank()) {
            return microformatTitle.trim()
        }

        // 2. Direct check in twoColumnBrowseResultsRenderer tabs
        val responsiveHeaderTitle = json.optJSONObject("contents")
            ?.optJSONObject("twoColumnBrowseResultsRenderer")
            ?.optJSONArray("tabs")?.optJSONObject(0)
            ?.optJSONObject("tabRenderer")?.optJSONObject("content")
            ?.optJSONObject("sectionListRenderer")?.optJSONArray("contents")
            ?.optJSONObject(0)?.optJSONObject("musicResponsiveHeaderRenderer")
            ?.optJSONObject("title")?.optJSONArray("runs")?.optJSONObject(0)?.optString("text")
        if (!responsiveHeaderTitle.isNullOrBlank()) {
            return responsiveHeaderTitle.trim()
        }

        // 3. Direct check in root header
        val rootHeader = json.optJSONObject("header")
        val rootTitle = rootHeader?.optJSONObject("musicDetailHeaderRenderer")
            ?.optJSONObject("title")?.optJSONArray("runs")?.optJSONObject(0)?.optString("text")
            ?: rootHeader?.optJSONObject("musicResponsiveHeaderRenderer")
                ?.optJSONObject("title")?.optJSONArray("runs")?.optJSONObject(0)?.optString("text")
            ?: rootHeader?.optJSONObject("musicEditablePlaylistDetailHeaderRenderer")
                ?.optJSONObject("header")?.optJSONObject("musicDetailHeaderRenderer")
                ?.optJSONObject("title")?.optJSONArray("runs")?.optJSONObject(0)?.optString("text")
        if (!rootTitle.isNullOrBlank()) {
            return rootTitle.trim()
        }

        // 4. Recursive search across JSON for header title
        fun findHeader(obj: Any?): String? {
            if (obj is JSONObject) {
                if (obj.has("musicResponsiveHeaderRenderer")) {
                    val title = obj.optJSONObject("musicResponsiveHeaderRenderer")
                        ?.optJSONObject("title")?.optJSONArray("runs")?.optJSONObject(0)?.optString("text")
                    if (!title.isNullOrBlank()) return title.trim()
                }
                if (obj.has("musicDetailHeaderRenderer")) {
                    val title = obj.optJSONObject("musicDetailHeaderRenderer")
                        ?.optJSONObject("title")?.optJSONArray("runs")?.optJSONObject(0)?.optString("text")
                    if (!title.isNullOrBlank()) return title.trim()
                }
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    if (key != "continuations" && key != "musicPlaylistShelfRenderer") {
                        val title = findHeader(obj.get(key))
                        if (title != null) return title
                    }
                }
            } else if (obj is JSONArray) {
                for (i in 0 until obj.length()) {
                    val title = findHeader(obj.get(i))
                    if (title != null) return title
                }
            }
            return null
        }
        return findHeader(json)
    }

    private fun extractPlaylistContinuationToken(json: JSONObject): String? {
        // 1. Check onResponseReceivedActions (modern InnerTube continuation response pages)
        val actions = json.optJSONArray("onResponseReceivedActions")
        if (actions != null && actions.length() > 0) {
            for (i in 0 until actions.length()) {
                val action = actions.optJSONObject(i) ?: continue
                val appendAction = action.optJSONObject("appendContinuationItemsAction") ?: continue
                val contItems = appendAction.optJSONArray("continuationItems") ?: continue
                if (contItems.length() > 0) {
                    val lastItem = contItems.optJSONObject(contItems.length() - 1)
                    val token = lastItem?.optJSONObject("continuationItemRenderer")
                        ?.optJSONObject("continuationEndpoint")
                        ?.optJSONObject("continuationCommand")
                        ?.optString("token")
                    if (!token.isNullOrBlank()) return token
                }
            }
        }

        // 2. Check musicPlaylistShelfRenderer in initial page
        fun findPlaylistShelf(obj: Any?): JSONObject? {
            if (obj is JSONObject) {
                if (obj.has("musicPlaylistShelfRenderer")) {
                    return obj.optJSONObject("musicPlaylistShelfRenderer")
                }
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    // Exclude outer continuations to avoid picking up Related Playlists recommendation tokens
                    if (key != "continuations" && key != "header") {
                        val res = findPlaylistShelf(obj.get(key))
                        if (res != null) return res
                    }
                }
            } else if (obj is JSONArray) {
                for (i in 0 until obj.length()) {
                    val res = findPlaylistShelf(obj.get(i))
                    if (res != null) return res
                }
            }
            return null
        }

        val shelf = findPlaylistShelf(json)
        if (shelf != null) {
            val shelfContents = shelf.optJSONArray("contents")
            if (shelfContents != null && shelfContents.length() > 0) {
                val lastItem = shelfContents.optJSONObject(shelfContents.length() - 1)
                val token = lastItem?.optJSONObject("continuationItemRenderer")
                    ?.optJSONObject("continuationEndpoint")
                    ?.optJSONObject("continuationCommand")
                    ?.optString("token")
                if (!token.isNullOrBlank()) return token
            }
        }

        // 3. Fallback: check musicPlaylistShelfContinuation (if served via legacy format)
        val contContents = json.optJSONObject("continuationContents")?.optJSONObject("musicPlaylistShelfContinuation")
        if (contContents != null) {
            val items = contContents.optJSONArray("contents")
            if (items != null && items.length() > 0) {
                val lastItem = items.optJSONObject(items.length() - 1)
                val token = lastItem?.optJSONObject("continuationItemRenderer")
                    ?.optJSONObject("continuationEndpoint")
                    ?.optJSONObject("continuationCommand")
                    ?.optString("token")
                if (!token.isNullOrBlank()) return token
            }
            val contArray = contContents.optJSONArray("continuations")
            if (contArray != null && contArray.length() > 0) {
                val next = contArray.optJSONObject(0)?.optJSONObject("nextContinuationData")?.optString("continuation")
                if (!next.isNullOrBlank()) return next
            }
        }

        return null
    }



    private fun extractTracksFromJson(
        json: JSONObject,
        fallbackAlbum: String,
        fallbackAuthor: String? = null,
        fallbackCover: String? = null
    ): List<Track> {
        val tracks = mutableListOf<Track>()

        fun extractItems(obj: Any?) {
            if (obj is JSONObject) {
                if (obj.has("musicResponsiveListItemRenderer")) {
                    val item = obj.getJSONObject("musicResponsiveListItemRenderer")
                    val flexCols = item.optJSONArray("flexColumns")
                    val col0 = flexCols?.optJSONObject(0)?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                    val col1 = flexCols?.optJSONObject(1)?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")

                    val col0Runs = col0?.optJSONObject("text")?.optJSONArray("runs")
                    var title = if (col0Runs != null && col0Runs.length() > 0) {
                        (0 until col0Runs.length()).mapNotNull { col0Runs.optJSONObject(it)?.optString("text") }.joinToString("").trim()
                    } else {
                        item.optJSONObject("title")?.optJSONArray("runs")?.optJSONObject(0)?.optString("text")?.trim() ?: "Track"
                    }

                    val col1Runs = col1?.optJSONObject("text")?.optJSONArray("runs")
                    var artist = fallbackAuthor ?: "Artist"
                    var album = fallbackAlbum
                    var durationSec = 210L

                    // Check fixedColumns for duration (standard in YouTube Music album items)
                    val fixedCols = item.optJSONArray("fixedColumns")
                    val fixed0 = fixedCols?.optJSONObject(0)?.optJSONObject("musicResponsiveListItemFixedColumnRenderer")
                    val fixed0Txt = fixed0?.optJSONObject("text")?.optJSONArray("runs")?.optJSONObject(0)?.optString("text")
                    if (!fixed0Txt.isNullOrBlank() && fixed0Txt.matches(Regex("^\\d+:\\d+(:\\d+)?$"))) {
                        durationSec = parseDurationToSeconds(fixed0Txt)
                    }

                    if (col1Runs != null && col1Runs.length() > 0) {
                        val artistRuns = mutableListOf<String>()
                        var foundDot = false
                        for (r in 0 until col1Runs.length()) {
                            val rObj = col1Runs.optJSONObject(r) ?: continue
                            val txt = rObj.optString("text")
                            if (txt.trim() == "•") {
                                foundDot = true
                                continue
                            }
                            if (!foundDot) {
                                if (!txt.contains("plays", ignoreCase = true)) {
                                    artistRuns.add(txt)
                                }
                            } else {
                                if (txt.matches(Regex("^\\d+:\\d+(:\\d+)?$"))) {
                                    durationSec = parseDurationToSeconds(txt)
                                } else if (txt.isNotBlank()) {
                                    album = txt
                                }
                            }
                        }
                        if (artistRuns.isNotEmpty()) {
                            artist = artistRuns.joinToString("").trim().replace(Regex("(?i) - Topic$"), "").trim()
                        }
                    }

                    // Clean title and split if title has "Artist - Title" format
                    if (title.contains(" - ")) {
                        val parts = title.split(" - ", limit = 2)
                        if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
                            val p1Lower = parts[1].trim().lowercase()
                            val isPart1MovieOrSubtitle = p1Lower.startsWith("from ") ||
                                p1Lower.startsWith("from \"") ||
                                p1Lower.startsWith("from '") ||
                                p1Lower.startsWith("ost") ||
                                TitleCleaner.extractVersion(parts[1]) != null

                            if (!isPart1MovieOrSubtitle && (artist == "Artist" || artist == "YouTube Music" || artist.isBlank())) {
                                artist = parts[0].trim().replace(Regex("(?i) - Topic$"), "").trim()
                                title = parts[1].trim()
                            }
                        }
                    }

                    var videoId = item.optJSONObject("playlistItemData")?.optString("videoId")
                    if (videoId.isNullOrBlank()) {
                        videoId = item.optJSONObject("overlay")
                            ?.optJSONObject("musicItemThumbnailOverlayRenderer")
                            ?.optJSONObject("content")
                            ?.optJSONObject("musicPlayButtonRenderer")
                            ?.optJSONObject("playNavigationEndpoint")
                            ?.optJSONObject("watchEndpoint")
                            ?.optString("videoId")
                    }
                    if (videoId.isNullOrBlank()) {
                        videoId = col0?.optJSONObject("navigationEndpoint")
                            ?.optJSONObject("watchEndpoint")
                            ?.optString("videoId")
                    }
                    if (videoId.isNullOrBlank()) {
                        videoId = col0Runs?.optJSONObject(0)
                            ?.optJSONObject("navigationEndpoint")?.optJSONObject("watchEndpoint")?.optString("videoId")
                    }
                    if (videoId.isNullOrBlank()) {
                        videoId = item.optJSONObject("navigationEndpoint")
                            ?.optJSONObject("watchEndpoint")?.optString("videoId")
                    }

                    val isVideoUnavailable = (title.equals("Private video", ignoreCase = true) || title.equals("Deleted video", ignoreCase = true)) && videoId.isNullOrBlank()

                    val itemThumbnails = item.optJSONObject("thumbnail")
                        ?.optJSONObject("musicThumbnailRenderer")
                        ?.optJSONObject("thumbnail")
                        ?.optJSONArray("thumbnails")
                        ?: item.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
                    val itemThumbUrl = if (itemThumbnails != null && itemThumbnails.length() > 0) {
                        itemThumbnails.optJSONObject(itemThumbnails.length() - 1)?.optString("url")
                    } else null

                    val finalThumbnail = when {
                        !itemThumbUrl.isNullOrBlank() -> itemThumbUrl
                        !videoId.isNullOrBlank() -> "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
                        else -> ""
                    }

                    if (!videoId.isNullOrBlank() && title.isNotBlank() && !isVideoUnavailable &&
                        !YouTubeShortsFilter.hasShortsMarker(item) &&
                        !YouTubeShortsFilter.isUnlinkedShortUgc(item, durationSec)
                    ) {
                        tracks.add(
                            Track(
                                id = videoId,
                                title = title,
                                artist = if (artist.isBlank() || artist == "Artist") (fallbackAuthor ?: "YouTube Music") else artist,
                                album = album,
                                thumbnail = finalThumbnail,
                                duration = durationSec,
                                source = TrackSource.YOUTUBE
                            )
                        )
                    }
                    return
                }

                val keys = obj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    if (key != "continuations" && key != "shelfDivider" && key != "chipCloudRenderer") {
                        extractItems(obj.get(key))
                    }
                }
            } else if (obj is JSONArray) {
                for (i in 0 until obj.length()) {
                    extractItems(obj.get(i))
                }
            }
        }

        extractItems(json)
        return tracks
    }

    private fun parseDurationToSeconds(timeStr: String): Long {
        if (timeStr.isBlank()) return 0L
        val parts = timeStr.trim().split(":")
        return try {
            when (parts.size) {
                1 -> parts[0].toLong()
                2 -> parts[0].toLong() * 60 + parts[1].toLong()
                3 -> parts[0].toLong() * 3600 + parts[1].toLong() * 60 + parts[2].toLong()
                else -> 0L
            }
        } catch (_: Exception) {
            0L
        }
    }
}
