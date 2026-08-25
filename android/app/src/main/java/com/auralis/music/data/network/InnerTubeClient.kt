package com.auralis.music.data.network

import com.auralis.music.domain.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

class InnerTubeClient(
    private val client: OkHttpClient = NetworkClientProvider.okHttpClient
) {
    companion object {
        private const val YT_MUSIC_API = "https://music.youtube.com/youtubei/v1"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        const val FILTER_SONGS = "Eg-KAQwIARAAGAAgACgAMABqChAMEAUSAhACEAU%3D"
        const val FILTER_ARTISTS = "Eg-KAQwIAhABGAEgACgAMABqChAMEAUSAhACEAU%3D"
        const val FILTER_PLAYLISTS = "Eg-KAQwIABAAGAAgACgBMABqChAMEAUSAhACEAU%3D"
        const val FILTER_ALBUMS = "Eg-KAQwBAhABGAEgACgAMABqChAMEAUSAhACEAU%3D"
    }

    /**
     * Searches YouTube Music exclusively using the WEB_REMIX InnerTube endpoint.
     */
    suspend fun search(query: String, params: String? = null): SearchResults = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return@withContext SearchResults()

        try {
            val requestBody = createWebRemixContext(trimmed, params)
            val request = Request.Builder()
                .url("$YT_MUSIC_API/search?prettyPrint=false")
                .post(requestBody.toString().toRequestBody(JSON_MEDIA_TYPE))
                .header("Referer", "https://music.youtube.com/")
                .header("Origin", "https://music.youtube.com")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return@withContext SearchResults()

            val body = response.body?.string() ?: return@withContext SearchResults()
            val json = JSONObject(body)
            parseYtMusicSearchResults(json)
        } catch (e: Exception) {
            SearchResults()
        }
    }

    /**
     * Fetches the YouTube Music Home page (FEmusic_home) matching Metrolist.
     * Returns paired HomeChips (Moods & moments) and Carousel HomeSections.
     */
    suspend fun getHome(params: String? = null, continuation: String? = null): Pair<List<HomeChip>, List<HomeSection>> = withContext(Dispatchers.IO) {
        try {
            val requestBody = if (continuation != null) {
                createContinuationContext(continuation)
            } else {
                createBrowseContext("FEmusic_home", params)
            }

            val url = if (continuation != null) {
                "$YT_MUSIC_API/browse?continuation=$continuation&prettyPrint=false"
            } else {
                "$YT_MUSIC_API/browse?prettyPrint=false"
            }

            val request = Request.Builder()
                .url(url)
                .post(requestBody.toString().toRequestBody(JSON_MEDIA_TYPE))
                .header("Referer", "https://music.youtube.com/")
                .header("Origin", "https://music.youtube.com")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return@withContext Pair(emptyList(), emptyList())

            val body = response.body?.string() ?: return@withContext Pair(emptyList(), emptyList())
            val json = JSONObject(body)
            parseHomePage(json)
        } catch (e: Exception) {
            Pair(emptyList(), emptyList())
        }
    }

    /**
     * Calls YouTube Next API for a videoId to extract the Related browse endpoint.
     */
    suspend fun getNextAndRelatedEndpoint(videoId: String): Pair<String?, String?> = withContext(Dispatchers.IO) {
        try {
            val requestBody = JSONObject().apply {
                put("videoId", videoId)
                put("enablePersistentPlaylistPanel", true)
                put("isAudioOnly", true)
                put("context", createClientContextObject())
            }

            val request = Request.Builder()
                .url("$YT_MUSIC_API/next?prettyPrint=false")
                .post(requestBody.toString().toRequestBody(JSON_MEDIA_TYPE))
                .header("Referer", "https://music.youtube.com/")
                .header("Origin", "https://music.youtube.com")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return@withContext Pair(null, null)

            val body = response.body?.string() ?: return@withContext Pair(null, null)
            val json = JSONObject(body)

            val tabs = json.optJSONObject("contents")
                ?.optJSONObject("singleColumnMusicWatchNextResultsRenderer")
                ?.optJSONObject("tabbedRenderer")
                ?.optJSONObject("watchNextTabbedResultsRenderer")
                ?.optJSONArray("tabs") ?: JSONArray()

            for (i in 0 until tabs.length()) {
                val tabRenderer = tabs.optJSONObject(i)?.optJSONObject("tabRenderer")
                val endpoint = tabRenderer?.optJSONObject("endpoint")?.optJSONObject("browseEndpoint")
                if (endpoint != null) {
                    val browseId = endpoint.optString("browseId")
                    val params = endpoint.optString("params")
                    if (browseId.isNotBlank() || params.isNotBlank()) {
                        return@withContext Pair(browseId.ifBlank { null }, params.ifBlank { null })
                    }
                }
            }
            Pair(null, null)
        } catch (e: Exception) {
            Pair(null, null)
        }
    }

    /**
     * Fetches related items using a browseId and optional params.
     */
    suspend fun getRelated(browseId: String?, params: String?): List<Track> = withContext(Dispatchers.IO) {
        try {
            val effectiveBrowseId = browseId ?: "FEmusic_shelf_related"
            val requestBody = createBrowseContext(effectiveBrowseId, params)

            val request = Request.Builder()
                .url("$YT_MUSIC_API/browse?prettyPrint=false")
                .post(requestBody.toString().toRequestBody(JSON_MEDIA_TYPE))
                .header("Referer", "https://music.youtube.com/")
                .header("Origin", "https://music.youtube.com")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return@withContext emptyList()

            val body = response.body?.string() ?: return@withContext emptyList()
            val json = JSONObject(body)

            val tracks = mutableListOf<Track>()
            val dummyArtists = mutableListOf<Artist>()
            val dummyPlaylists = mutableListOf<PlaylistResult>()

            // Traverse section list contents
            val sectionList = json.optJSONObject("contents")
                ?.optJSONObject("sectionListRenderer")
                ?.optJSONArray("contents") ?: JSONArray()

            for (i in 0 until sectionList.length()) {
                val secObj = sectionList.optJSONObject(i)
                val shelf = secObj?.optJSONObject("musicShelfRenderer")
                    ?: secObj?.optJSONObject("musicCarouselShelfRenderer")
                val contents = shelf?.optJSONArray("contents") ?: JSONArray()

                for (j in 0 until contents.length()) {
                    val cObj = contents.optJSONObject(j)
                    val itemResp = cObj?.optJSONObject("musicResponsiveListItemRenderer")
                    if (itemResp != null) {
                        parseMusicListItem(itemResp, tracks, dummyArtists, dummyPlaylists)
                    }
                    val twoRow = cObj?.optJSONObject("musicTwoRowItemRenderer")
                    if (twoRow != null) {
                        parseMusicTwoRowItem(twoRow)?.let { tracks.add(it) }
                    }
                }
            }
            tracks.distinctBy { it.id }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Fetches Explore page (new releases, charts)
     */
    suspend fun getExplore(): List<HomeSection> = withContext(Dispatchers.IO) {
        try {
            val requestBody = createBrowseContext("FEmusic_explore")
            val request = Request.Builder()
                .url("$YT_MUSIC_API/browse?prettyPrint=false")
                .post(requestBody.toString().toRequestBody(JSON_MEDIA_TYPE))
                .header("Referer", "https://music.youtube.com/")
                .header("Origin", "https://music.youtube.com")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return@withContext emptyList()

            val body = response.body?.string() ?: return@withContext emptyList()
            val json = JSONObject(body)
            val (_, sections) = parseHomePage(json)
            sections
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Fetches artist top tracks and discography shelves.
     */
    suspend fun getArtistDetails(channelId: String): List<Track> = withContext(Dispatchers.IO) {
        try {
            val requestBody = createBrowseContext(channelId)
            val request = Request.Builder()
                .url("$YT_MUSIC_API/browse?prettyPrint=false")
                .post(requestBody.toString().toRequestBody(JSON_MEDIA_TYPE))
                .header("Referer", "https://music.youtube.com/")
                .header("Origin", "https://music.youtube.com")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return@withContext emptyList()

            val body = response.body?.string() ?: return@withContext emptyList()
            val json = JSONObject(body)

            val tracks = mutableListOf<Track>()
            val dummyArtists = mutableListOf<Artist>()
            val dummyPlaylists = mutableListOf<PlaylistResult>()

            val sectionList = json.optJSONObject("contents")
                ?.optJSONObject("singleColumnBrowseResultsRenderer")
                ?.optJSONArray("tabs")
                ?.optJSONObject(0)
                ?.optJSONObject("tabRenderer")
                ?.optJSONObject("content")
                ?.optJSONObject("sectionListRenderer")
                ?.optJSONArray("contents") ?: JSONArray()

            for (i in 0 until sectionList.length()) {
                val secObj = sectionList.optJSONObject(i)
                val shelf = secObj?.optJSONObject("musicShelfRenderer")
                    ?: secObj?.optJSONObject("musicCarouselShelfRenderer")
                val contents = shelf?.optJSONArray("contents") ?: JSONArray()

                for (j in 0 until contents.length()) {
                    val cObj = contents.optJSONObject(j)
                    val itemResp = cObj?.optJSONObject("musicResponsiveListItemRenderer")
                    if (itemResp != null) {
                        parseMusicListItem(itemResp, tracks, dummyArtists, dummyPlaylists)
                    }
                    val twoRow = cObj?.optJSONObject("musicTwoRowItemRenderer")
                    if (twoRow != null) {
                        parseMusicTwoRowItem(twoRow)?.let { tracks.add(it) }
                    }
                }
            }
            tracks.distinctBy { it.id }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun parseHomePage(root: JSONObject): Pair<List<HomeChip>, List<HomeSection>> {
        val chips = mutableListOf<HomeChip>()
        val sections = mutableListOf<HomeSection>()

        try {
            val sectionList = root.optJSONObject("contents")
                ?.optJSONObject("singleColumnBrowseResultsRenderer")
                ?.optJSONArray("tabs")
                ?.optJSONObject(0)
                ?.optJSONObject("tabRenderer")
                ?.optJSONObject("content")
                ?.optJSONObject("sectionListRenderer")
                ?: root.optJSONObject("continuationContents")
                    ?.optJSONObject("sectionListContinuation")

            // Parse Chips
            val chipCloud = sectionList?.optJSONObject("header")
                ?.optJSONObject("chipCloudRenderer")
                ?.optJSONArray("chips") ?: JSONArray()

            for (i in 0 until chipCloud.length()) {
                val chipObj = chipCloud.optJSONObject(i)?.optJSONObject("chipCloudChipRenderer")
                val text = chipObj?.optJSONObject("text")?.optJSONArray("runs")?.optJSONObject(0)?.optString("text")
                val nav = chipObj?.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")
                val browseId = nav?.optString("browseId")
                val params = nav?.optString("params")

                if (!text.isNullOrBlank()) {
                    chips.add(HomeChip(title = text, endpointBrowseId = browseId, params = params))
                }
            }

            // Parse Sections
            val contents = sectionList?.optJSONArray("contents") ?: JSONArray()
            for (i in 0 until contents.length()) {
                val shelfObj = contents.optJSONObject(i)?.optJSONObject("musicCarouselShelfRenderer") ?: continue
                val header = shelfObj.optJSONObject("header")?.optJSONObject("musicCarouselShelfBasicHeaderRenderer")
                val title = header?.optJSONObject("title")?.optJSONArray("runs")?.optJSONObject(0)?.optString("text")
                    ?: "Recommendations"
                val subtitle = header?.optJSONObject("strapline")?.optJSONArray("runs")?.optJSONObject(0)?.optString("text")

                val shelfItems = shelfObj.optJSONArray("contents") ?: JSONArray()
                val tracks = mutableListOf<Track>()

                for (j in 0 until shelfItems.length()) {
                    val itemContainer = shelfItems.optJSONObject(j) ?: continue
                    val twoRow = itemContainer.optJSONObject("musicTwoRowItemRenderer")
                    if (twoRow != null) {
                        parseMusicTwoRowItem(twoRow)?.let { tracks.add(it) }
                    }
                    val responsive = itemContainer.optJSONObject("musicResponsiveListItemRenderer")
                    if (responsive != null) {
                        val dummyArtists = mutableListOf<Artist>()
                        val dummyPlaylists = mutableListOf<PlaylistResult>()
                        parseMusicListItem(responsive, tracks, dummyArtists, dummyPlaylists)
                    }
                }

                if (tracks.isNotEmpty()) {
                    sections.add(
                        HomeSection(
                            id = "section_$i",
                            title = title,
                            subtitle = subtitle,
                            thumbnail = tracks.firstOrNull()?.thumbnail,
                            items = tracks
                        )
                    )
                }
            }
        } catch (e: Exception) {
            // Ignored
        }

        return Pair(chips, sections)
    }

    private fun parseMusicTwoRowItem(twoRow: JSONObject): Track? {
        val title = twoRow.optJSONObject("title")?.optJSONArray("runs")?.optJSONObject(0)?.optString("text") ?: return null
        val navEndpoint = twoRow.optJSONObject("navigationEndpoint")
        val watchEndpoint = navEndpoint?.optJSONObject("watchEndpoint")
        val videoId = watchEndpoint?.optString("videoId") ?: return null

        val subtitleRuns = twoRow.optJSONObject("subtitle")?.optJSONArray("runs")
        var artistName = "YouTube Music"
        var albumName: String? = null
        if (subtitleRuns != null) {
            val names = mutableListOf<String>()
            for (k in 0 until subtitleRuns.length()) {
                val rText = subtitleRuns.optJSONObject(k)?.optString("text")?.trim() ?: continue
                if (rText != "•" && rText.isNotBlank()) {
                    names.add(rText)
                }
            }
            if (names.isNotEmpty()) artistName = names[0]
            if (names.size > 1) albumName = names[1]
        }

        val thumbnails = twoRow.optJSONObject("thumbnailRenderer")
            ?.optJSONObject("musicThumbnailRenderer")
            ?.optJSONObject("thumbnail")
            ?.optJSONArray("thumbnails")

        val thumbUrl = getBestThumbnailUrl(thumbnails, videoId)

        return Track(
            id = videoId,
            title = TitleCleaner.cleanTitle(title),
            artist = artistName,
            album = albumName,
            duration = 0L,
            thumbnail = thumbUrl,
            source = TrackSource.YOUTUBE
        )
    }

    fun parseYtMusicSearchResults(root: JSONObject): SearchResults {
        val songs = mutableListOf<Track>()
        val artists = mutableListOf<Artist>()
        val playlists = mutableListOf<PlaylistResult>()

        try {
            val sectionList = root.optJSONObject("contents")
                ?.optJSONObject("tabbedSearchResultsRenderer")
                ?.optJSONArray("tabs")
                ?.optJSONObject(0)
                ?.optJSONObject("tabRenderer")
                ?.optJSONObject("content")
                ?.optJSONObject("sectionListRenderer")
                ?.optJSONArray("contents") ?: JSONArray()

            for (i in 0 until sectionList.length()) {
                val section = sectionList.optJSONObject(i)
                val cardShelf = section?.optJSONObject("musicCardShelfRenderer")
                if (cardShelf != null) {
                    parseMusicCardShelf(cardShelf, songs, artists, playlists)
                }

                val shelf = section?.optJSONObject("musicShelfRenderer")
                if (shelf != null) {
                    val shelfContents = shelf.optJSONArray("contents") ?: JSONArray()
                    for (j in 0 until shelfContents.length()) {
                        val item = shelfContents.optJSONObject(j)?.optJSONObject("musicResponsiveListItemRenderer")
                        if (item != null) {
                            parseMusicListItem(item, songs, artists, playlists)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // Ignored
        }

        return SearchResults(songs = songs, artists = artists, playlists = playlists)
    }

    private fun parseMusicCardShelf(
        card: JSONObject,
        songs: MutableList<Track>,
        artists: MutableList<Artist>,
        playlists: MutableList<PlaylistResult>
    ) {
        val title = card.optJSONObject("title")?.optJSONArray("runs")?.optJSONObject(0)?.optString("text") ?: return
        val subtitleRuns = card.optJSONObject("subtitle")?.optJSONArray("runs")
        val subText = buildString {
            if (subtitleRuns != null) {
                for (r in 0 until subtitleRuns.length()) {
                    append(subtitleRuns.optJSONObject(r)?.optString("text") ?: "")
                }
            }
        }
        val subParts = subText.split("•").map { it.trim() }
        val cardType = subParts.firstOrNull()?.lowercase() ?: ""

        val thumbnails = card.optJSONObject("thumbnail")
            ?.optJSONObject("musicThumbnailRenderer")
            ?.optJSONObject("thumbnail")
            ?.optJSONArray("thumbnails")
        val thumbUrl = getBestThumbnailUrl(thumbnails, null)

        val onTap = card.optJSONObject("onTap")
        val browseId = onTap?.optJSONObject("browseEndpoint")?.optString("browseId")
        val videoId = onTap?.optJSONObject("watchEndpoint")?.optString("videoId")
            ?: card.optJSONArray("buttons")?.optJSONObject(0)?.optJSONObject("buttonRenderer")?.optJSONObject("command")?.optJSONObject("watchEndpoint")?.optString("videoId")

        if (cardType.contains("artist") || (browseId != null && browseId.startsWith("UC") && videoId.isNullOrBlank())) {
            if (artists.none { it.id == browseId || it.name.equals(title, ignoreCase = true) }) {
                artists.add(0, Artist(id = browseId ?: "yt:$title", name = title, thumbnail = thumbUrl.ifBlank { null }, query = "$title top songs"))
            }
        } else if (cardType.contains("album") || cardType.contains("playlist") || (browseId != null && (browseId.startsWith("MPRE") || browseId.startsWith("VL") || browseId.startsWith("PL")) && videoId.isNullOrBlank())) {
            if (playlists.none { it.id == browseId || it.title.equals(title, ignoreCase = true) }) {
                val author = if (subParts.size > 1) subParts[1] else null
                playlists.add(0, PlaylistResult(id = browseId ?: "pl:$title", title = title, thumbnail = thumbUrl.ifBlank { null }, author = author))
            }
        } else if (!videoId.isNullOrBlank()) {
            if (songs.none { it.id == videoId }) {
                val artist = if (subParts.size > 1) subParts[1] else "YouTube Artist"
                var duration = 200L
                val durStr = subParts.find { it.matches(Regex("""\d+:\d+(:\d+)?""")) }
                if (durStr != null) {
                    duration = parseDurationToSeconds(durStr)
                }
                songs.add(0, Track(
                    id = videoId,
                    title = TitleCleaner.cleanTitle(title),
                    artist = artist,
                    duration = duration,
                    thumbnail = thumbUrl.ifBlank { "https://i.ytimg.com/vi/$videoId/hqdefault.jpg" },
                    source = TrackSource.YOUTUBE
                ))
            }
        }
    }

    private fun parseMusicListItem(
        item: JSONObject,
        songs: MutableList<Track>,
        artists: MutableList<Artist>,
        playlists: MutableList<PlaylistResult>
    ) {
        val flexColumns = item.optJSONArray("flexColumns") ?: return
        val col0Runs = flexColumns.optJSONObject(0)
            ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
            ?.optJSONObject("text")
            ?.optJSONArray("runs") ?: return

        val title = col0Runs.optJSONObject(0)?.optString("text") ?: return
        val navEndpoint = col0Runs.optJSONObject(0)?.optJSONObject("navigationEndpoint")
        val browseId = navEndpoint?.optJSONObject("browseEndpoint")?.optString("browseId")
        val videoId = navEndpoint?.optJSONObject("watchEndpoint")?.optString("videoId")
            ?: item.optJSONObject("playlistItemData")?.optString("videoId")
            ?: item.optJSONObject("overlay")
                ?.optJSONObject("musicItemThumbnailOverlayRenderer")
                ?.optJSONObject("content")
                ?.optJSONObject("musicPlayButtonRenderer")
                ?.optJSONObject("playNavigationEndpoint")
                ?.optJSONObject("watchEndpoint")
                ?.optString("videoId")

        val col1Runs = if (flexColumns.length() > 1) {
            flexColumns.optJSONObject(1)
                ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                ?.optJSONObject("text")
                ?.optJSONArray("runs")
        } else null

        var artistName = "Unknown Artist"
        var albumName: String? = null
        var durationSec: Long = 0
        var itemType = ""

        if (col1Runs != null) {
            for (r in 0 until col1Runs.length()) {
                val runObj = col1Runs.optJSONObject(r) ?: continue
                val text = runObj.optString("text").trim()
                val runBrowseId = runObj.optJSONObject("navigationEndpoint")
                    ?.optJSONObject("browseEndpoint")
                    ?.optString("browseId")

                if (runBrowseId != null && runBrowseId.startsWith("UC")) {
                    artistName = text
                } else if (text.matches(Regex("""\d+:\d+(:\d+)?"""))) {
                    durationSec = parseDurationToSeconds(text)
                } else if (r == 0 && text != "•") {
                    itemType = text.lowercase()
                    artistName = text
                } else if (r > 0 && text != "•" && !text.matches(Regex("""\d+.*"""))) {
                    albumName = text
                }
            }
        }

        val thumbnails = item.optJSONObject("thumbnail")
            ?.optJSONObject("musicThumbnailRenderer")
            ?.optJSONObject("thumbnail")
            ?.optJSONArray("thumbnails")

        val thumbUrl = getBestThumbnailUrl(thumbnails, videoId)

        if (itemType.contains("artist") || (browseId != null && browseId.startsWith("UC") && videoId.isNullOrBlank())) {
            if (artists.none { it.id == browseId || it.name.equals(title, ignoreCase = true) }) {
                artists.add(
                    Artist(
                        id = browseId ?: "yt:$title",
                        name = title,
                        thumbnail = thumbUrl.ifBlank { null },
                        query = "$title top songs"
                    )
                )
            }
        } else if (itemType.contains("album") || itemType.contains("playlist") || (browseId != null && (browseId.startsWith("MPRE") || browseId.startsWith("VL") || browseId.startsWith("PL")) && videoId.isNullOrBlank())) {
            if (playlists.none { it.id == browseId || it.title.equals(title, ignoreCase = true) }) {
                playlists.add(
                    PlaylistResult(
                        id = browseId ?: "pl:$title",
                        title = title,
                        thumbnail = thumbUrl.ifBlank { null },
                        author = artistName
                    )
                )
            }
        } else if (!videoId.isNullOrBlank()) {
            if (songs.none { it.id == videoId }) {
                songs.add(
                    Track(
                        id = videoId,
                        title = TitleCleaner.cleanTitle(title),
                        artist = artistName,
                        album = albumName,
                        duration = durationSec,
                        thumbnail = thumbUrl,
                        source = TrackSource.YOUTUBE
                    )
                )
            }
        }
    }

    private fun getBestThumbnailUrl(thumbnails: JSONArray?, videoId: String?): String {
        if (thumbnails != null && thumbnails.length() > 0) {
            val last = thumbnails.optJSONObject(thumbnails.length() - 1)
            var url = last?.optString("url")
            if (!url.isNullOrBlank()) {
                if (url.startsWith("//")) url = "https:$url"
                if (url.contains("googleusercontent.com") || url.contains("ggpht.com")) {
                    url = url.replace(Regex("""=w\d+-h\d+.*"""), "=w1200-h1200-l90-rj")
                        .replace(Regex("""=s\d+.*"""), "=s1200-c")
                }
                return url
            }
        }
        return if (!videoId.isNullOrBlank()) "https://i.ytimg.com/vi/$videoId/hq720.jpg" else ""
    }

    private fun parseDurationToSeconds(timeStr: String): Long {
        if (timeStr.isBlank()) return 0
        val parts = timeStr.trim().split(":")
        return try {
            when (parts.size) {
                1 -> parts[0].toLong()
                2 -> parts[0].toLong() * 60 + parts[1].toLong()
                3 -> parts[0].toLong() * 3600 + parts[1].toLong() * 60 + parts[2].toLong()
                else -> 0
            }
        } catch (e: Exception) {
            0
        }
    }

    private fun createBrowseContext(browseId: String, params: String? = null): JSONObject {
        return JSONObject().apply {
            put("browseId", browseId)
            if (!params.isNullOrBlank()) put("params", params)
            put("context", createClientContextObject())
        }
    }

    private fun createContinuationContext(continuation: String): JSONObject {
        return JSONObject().apply {
            put("continuation", continuation)
            put("context", createClientContextObject())
        }
    }

    private fun createWebRemixContext(query: String, params: String? = null): JSONObject {
        return JSONObject().apply {
            put("query", query)
            if (!params.isNullOrBlank()) put("params", params)
            put("context", createClientContextObject())
        }
    }

    private fun createClientContextObject(): JSONObject {
        return JSONObject().apply {
            put("client", JSONObject().apply {
                put("clientName", "WEB_REMIX")
                put("clientVersion", "1.20241201.01.00")
                put("hl", "en")
                put("gl", "US")
            })
        }
    }
}
