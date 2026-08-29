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

open class InnerTubeClient(
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
    open suspend fun search(query: String, params: String? = null): SearchResults = withContext(Dispatchers.IO) {
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
    open suspend fun getHome(params: String? = null, continuation: String? = null): Pair<List<HomeChip>, List<HomeSection>> = withContext(Dispatchers.IO) {
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
    open suspend fun getNextAndRelatedEndpoint(videoId: String): Pair<String?, String?> = withContext(Dispatchers.IO) {
        try {
            val requestBody = JSONObject().apply {
                put("videoId", videoId)
                put("enablePersistentPlaylistPanel", true)
                put("isAudioOnly", true)
                put("context", createClientContext())
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

            extractRelatedEndpointFromNextJson(json)
        } catch (e: Exception) {
            Pair(null, null)
        }
    }

    /**
     * Fetches smart radio / autoplay tracks for a given seed track.
     * Generates a curated, genre-matching radio queue with:
     * - Top hits from the same artist (3-4 tracks)
     * - Top hits from other artists in the exact same genre & vibe (10-15 tracks)
     * - Collaborations and related releases
     */
    open suspend fun getRadioTracks(
        videoId: String,
        artist: String? = null,
        title: String? = null
    ): List<Track> = withContext(Dispatchers.IO) {
        val tracks = mutableListOf<Track>()

        val primaryArtist = artist?.split("&", ",", "feat.", "ft.", "Feat.", "Ft.", "with")?.firstOrNull()?.trim()
            ?.ifBlank { null } ?: artist?.trim()?.ifBlank { null }
        val cleanTitle = if (!title.isNullOrBlank()) TitleCleaner.cleanTitle(title) else null

        // ── STRATEGY 1: Official YouTube Music Next / Radio Playlist Panel ──
        try {
            val requestBody = JSONObject().apply {
                put("videoId", videoId)
                put("playlistId", "RDAMVM$videoId")
                put("enablePersistentPlaylistPanel", true)
                put("isAudioOnly", true)
                put("context", createClientContext())
            }

            val request = Request.Builder()
                .url("$YT_MUSIC_API/next?prettyPrint=false")
                .post(requestBody.toString().toRequestBody(JSON_MEDIA_TYPE))
                .header("Referer", "https://music.youtube.com/")
                .header("Origin", "https://music.youtube.com")
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string()
                if (!body.isNullOrBlank()) {
                    val json = JSONObject(body)
                    val parsed = parseRadioFromNextResponse(json, videoId)
                    tracks.addAll(parsed)
                }
            }
        } catch (_: Exception) {}

        // ── STRATEGY 2: Smart Multi-Tier Genre & Artist Recommendations ──
        if (tracks.size < 10 && (!primaryArtist.isNullOrBlank() || !cleanTitle.isNullOrBlank())) {
            try {
                val seenIds = mutableSetOf(videoId)
                seenIds.addAll(tracks.map { it.id })

                // 1. Same Artist Top Hits
                val sameArtistHits = if (!primaryArtist.isNullOrBlank()) {
                    search(primaryArtist, FILTER_SONGS).songs.filter { it.id !in seenIds }
                } else emptyList()

                // Take 2-3 top tracks from the same artist
                val selectedSameArtist = sameArtistHits.take(3)
                selectedSameArtist.forEach { seenIds.add(it.id) }

                // 2. Song + Artist Specific Radio & Collaborators (e.g. "Winning Speech Karan Aujla")
                val songSpecificHits = if (!cleanTitle.isNullOrBlank() && !primaryArtist.isNullOrBlank()) {
                    search("$cleanTitle $primaryArtist", FILTER_SONGS).songs.filter { it.id !in seenIds }
                } else if (!cleanTitle.isNullOrBlank()) {
                    search(cleanTitle, FILTER_SONGS).songs.filter { it.id !in seenIds }
                } else emptyList()
                songSpecificHits.forEach { seenIds.add(it.id) }

                // 3. Same Genre & Vibe Mix from Other Artists (e.g. "Karan Aujla mix radio" -> Diljit, Sidhu Moose Wala, AP Dhillon, etc.)
                val genreMixHits = if (!primaryArtist.isNullOrBlank()) {
                    search("$primaryArtist mix radio", FILTER_SONGS).songs.filter { it.id !in seenIds }
                } else emptyList()
                genreMixHits.forEach { seenIds.add(it.id) }

                // Blend together:
                val resultList = mutableListOf<Track>()
                resultList.addAll(tracks.take(3))
                resultList.addAll(selectedSameArtist)
                val otherPool = (songSpecificHits + genreMixHits + sameArtistHits.drop(3)).distinctBy { it.id }
                resultList.addAll(otherPool)

                val finalTracks = resultList.filter { it.id != videoId }.distinctBy { it.id }.take(25)
                if (finalTracks.isNotEmpty()) {
                    return@withContext finalTracks
                }
            } catch (_: Exception) {}
        }

        // Fallback: If still empty, attempt without RDAMVM or fetch related
        if (tracks.isEmpty()) {
            try {
                val requestBody = JSONObject().apply {
                    put("videoId", videoId)
                    put("enablePersistentPlaylistPanel", true)
                    put("isAudioOnly", true)
                    put("context", createClientContext())
                }

                val request = Request.Builder()
                    .url("$YT_MUSIC_API/next?prettyPrint=false")
                    .post(requestBody.toString().toRequestBody(JSON_MEDIA_TYPE))
                    .header("Referer", "https://music.youtube.com/")
                    .header("Origin", "https://music.youtube.com")
                    .build()

                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val body = response.body?.string()
                    if (!body.isNullOrBlank()) {
                        val json = JSONObject(body)
                        tracks.addAll(parseRadioFromNextResponse(json, videoId))
                    }
                }
            } catch (_: Exception) {}
        }

        tracks.filter { it.id != videoId }.distinctBy { it.id }
    }

    fun parseRadioFromNextResponse(root: JSONObject, seedVideoId: String? = null): List<Track> {
        val tracks = mutableListOf<Track>()
        try {
            val singleCol = root.optJSONObject("contents")
                ?.optJSONObject("singleColumnMusicWatchNextResultsRenderer")

            val playlistContents = singleCol?.optJSONObject("playlist")
                ?.optJSONObject("playlistPanelRenderer")
                ?.optJSONArray("contents")

            if (playlistContents != null) {
                for (i in 0 until playlistContents.length()) {
                    val item = playlistContents.optJSONObject(i) ?: continue
                    val videoRenderer = item.optJSONObject("playlistPanelVideoRenderer") ?: continue
                    val track = parsePlaylistPanelVideo(videoRenderer)
                    if (track != null && (seedVideoId == null || track.id != seedVideoId)) {
                        tracks.add(track)
                    }
                }
            }
        } catch (_: Exception) {}
        return tracks.distinctBy { it.id }
    }

    private fun extractRelatedEndpointFromNextJson(json: JSONObject): Pair<String?, String?> {
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
                    return Pair(browseId.ifBlank { null }, params.ifBlank { null })
                }
            }
        }
        return Pair(null, null)
    }

    fun parsePlaylistPanelVideo(renderer: JSONObject): Track? {
        val videoId = renderer.optString("videoId").ifBlank {
            renderer.optJSONObject("navigationEndpoint")?.optJSONObject("watchEndpoint")?.optString("videoId") ?: ""
        }
        if (videoId.isBlank()) return null

        val titleRuns = renderer.optJSONObject("title")?.optJSONArray("runs")
        val title = if (titleRuns != null && titleRuns.length() > 0) {
            titleRuns.optJSONObject(0)?.optString("text") ?: ""
        } else {
            renderer.optJSONObject("title")?.optString("simpleText") ?: ""
        }
        if (title.isBlank()) return null

        var artistName = "Unknown Artist"
        var durationSec = 0L
        val bylineRuns = renderer.optJSONObject("longBylineText")?.optJSONArray("runs")
            ?: renderer.optJSONObject("shortBylineText")?.optJSONArray("runs")

        if (bylineRuns != null) {
            for (r in 0 until bylineRuns.length()) {
                val runObj = bylineRuns.optJSONObject(r) ?: continue
                val text = runObj.optString("text").trim()
                if (text.matches(Regex("""\d+:\d+(:\d+)?"""))) {
                    durationSec = parseDurationToSeconds(text)
                } else if (r == 0 && text != "•") {
                    artistName = text
                }
            }
        }

        val lengthRuns = renderer.optJSONObject("lengthText")?.optJSONArray("runs")
        val lengthText = if (lengthRuns != null && lengthRuns.length() > 0) {
            lengthRuns.optJSONObject(0)?.optString("text") ?: ""
        } else {
            renderer.optJSONObject("lengthText")?.optString("simpleText") ?: ""
        }
        if (lengthText.isNotBlank() && durationSec == 0L) {
            durationSec = parseDurationToSeconds(lengthText)
        }

        val thumbnails = renderer.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
        val thumbUrl = getBestThumbnailUrl(thumbnails, videoId)

        return Track(
            id = videoId,
            title = TitleCleaner.cleanTitle(title),
            artist = artistName,
            duration = durationSec,
            thumbnail = thumbUrl,
            source = TrackSource.YOUTUBE
        )
    }


    /**
     * Fetches official record-label lyrics from YouTube Music for a videoId.
     */
    suspend fun getYouTubeMusicLyrics(videoId: String): LyricsData? = withContext(Dispatchers.IO) {
        try {
            val requestBody = JSONObject().apply {
                put("videoId", videoId)
                put("enablePersistentPlaylistPanel", true)
                put("isAudioOnly", true)
                put("context", createClientContext())
            }

            val request = Request.Builder()
                .url("$YT_MUSIC_API/next?prettyPrint=false")
                .post(requestBody.toString().toRequestBody(JSON_MEDIA_TYPE))
                .header("Referer", "https://music.youtube.com/")
                .header("Origin", "https://music.youtube.com")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return@withContext null

            val body = response.body?.string() ?: return@withContext null
            val json = JSONObject(body)

            val tabs = json.optJSONObject("contents")
                ?.optJSONObject("singleColumnMusicWatchNextResultsRenderer")
                ?.optJSONObject("tabbedRenderer")
                ?.optJSONObject("watchNextTabbedResultsRenderer")
                ?.optJSONArray("tabs") ?: JSONArray()

            var lyricsBrowseId: String? = null
            for (i in 0 until tabs.length()) {
                val tabRenderer = tabs.optJSONObject(i)?.optJSONObject("tabRenderer")
                val title = tabRenderer?.optString("title", "")?.lowercase() ?: ""
                val endpoint = tabRenderer?.optJSONObject("endpoint")?.optJSONObject("browseEndpoint")
                val bId = endpoint?.optString("browseId")
                if (bId != null && (bId.startsWith("MPLY") || title.contains("lyric") || bId.contains("lyrics"))) {
                    lyricsBrowseId = bId
                    break
                }
            }

            if (lyricsBrowseId != null) {
                val browseBody = createBrowseContext(lyricsBrowseId)
                val browseReq = Request.Builder()
                    .url("$YT_MUSIC_API/browse?prettyPrint=false")
                    .post(browseBody.toString().toRequestBody(JSON_MEDIA_TYPE))
                    .header("Referer", "https://music.youtube.com/")
                    .header("Origin", "https://music.youtube.com")
                    .build()

                val browseResp = client.newCall(browseReq).execute()
                if (browseResp.isSuccessful) {
                    val bHtml = browseResp.body?.string() ?: ""
                    val bJson = JSONObject(bHtml)
                    val runs = bJson.optJSONObject("contents")
                        ?.optJSONObject("sectionListRenderer")
                        ?.optJSONArray("contents")
                        ?.optJSONObject(0)
                        ?.optJSONObject("musicDescriptionShelfRenderer")
                        ?.optJSONObject("description")
                        ?.optJSONArray("runs")

                    if (runs != null && runs.length() > 0) {
                        val fullText = StringBuilder()
                        for (r in 0 until runs.length()) {
                            fullText.append(runs.optJSONObject(r)?.optString("text") ?: "")
                        }
                        val text = fullText.toString().trim()
                        if (text.isNotBlank()) {
                            val lines = text.lines()
                                .map { it.trim() }
                                .filter { it.isNotBlank() }
                                .mapIndexed { idx: Int, line: String ->
                                    LyricLine(
                                        time = idx * 3500L,
                                        text = line
                                    )
                                }
                            return@withContext LyricsData(
                                provider = LyricsProvider.YOUTUBE,
                                syncType = SyncType.PLAIN,
                                lines = lines,
                                plainLyrics = text
                            )
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        null
    }

    /**
     * Fetches related items using a browseId and optional params.
     */
    open suspend fun getRelated(browseId: String?, params: String?): List<Track> = withContext(Dispatchers.IO) {
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
        val page = getArtistPage(Artist(id = channelId, name = "Artist"))
        page?.topSongs ?: emptyList()
    }

    /**
     * Fetches full YouTube Music Artist Page including header portrait, subscriber count,
     * bio description, top songs, albums, singles, and similar artists.
     */
    suspend fun getArtistPage(artist: Artist): ArtistPage? = withContext(Dispatchers.IO) {
        try {
            var effectiveChannelId = artist.id

            // If channelId is not a real YouTube channel ID, search to resolve it
            if (!effectiveChannelId.startsWith("UC")) {
                val searchArtists = search(artist.name, FILTER_ARTISTS)
                val match = searchArtists.artists.firstOrNull { it.id.startsWith("UC") }
                    ?: search(artist.name).artists.firstOrNull { it.id.startsWith("UC") }
                if (match != null) {
                    effectiveChannelId = match.id
                }
            }

            val requestBody = createBrowseContext(effectiveChannelId)
            val request = Request.Builder()
                .url("$YT_MUSIC_API/browse?prettyPrint=false")
                .post(requestBody.toString().toRequestBody(JSON_MEDIA_TYPE))
                .header("Referer", "https://music.youtube.com/")
                .header("Origin", "https://music.youtube.com")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                // Fallback to search if browse fails
                val searchHits = search("${artist.name} top songs")
                return@withContext if (searchHits.songs.isNotEmpty()) {
                    ArtistPage(
                        artist = artist,
                        bannerUrl = artist.thumbnail,
                        topSongs = searchHits.songs
                    )
                } else null
            }

            val body = response.body?.string() ?: return@withContext null
            val json = JSONObject(body)

            val header = json.optJSONObject("header")?.optJSONObject("musicImmersiveHeaderRenderer")
                ?: json.optJSONObject("header")?.optJSONObject("musicVisualHeaderRenderer")
                ?: json.optJSONObject("header")?.optJSONObject("musicHeaderRenderer")

            val artistName = header?.optJSONObject("title")?.optJSONArray("runs")?.optJSONObject(0)?.optString("text")
                ?.ifBlank { artist.name } ?: artist.name

            // Parse description runs
            val descRuns = header?.optJSONObject("description")?.optJSONArray("runs")
            var description: String? = null
            if (descRuns != null && descRuns.length() > 0) {
                val sb = StringBuilder()
                for (d in 0 until descRuns.length()) {
                    sb.append(descRuns.optJSONObject(d)?.optString("text") ?: "")
                }
                description = sb.toString().trim().ifBlank { null }
            }

            // Parse subscriber count
            val subButton = header?.optJSONObject("subscriptionButton")?.optJSONObject("subscribeButtonRenderer")
            val subscribers = subButton?.optJSONObject("longSubscriberCountText")?.optJSONArray("runs")?.optJSONObject(0)?.optString("text")
                ?: subButton?.optJSONObject("subscriberCountText")?.optJSONArray("runs")?.optJSONObject(0)?.optString("text")
                ?: subButton?.optJSONObject("shortSubscriberCountText")?.optJSONArray("runs")?.optJSONObject(0)?.optString("text")

            // Parse banner portrait
            val bannerThumbs = header?.optJSONObject("thumbnail")
                ?.optJSONObject("musicThumbnailRenderer")
                ?.optJSONObject("thumbnail")
                ?.optJSONArray("thumbnails")
                ?: header?.optJSONObject("foregroundThumbnail")
                    ?.optJSONObject("musicThumbnailRenderer")
                    ?.optJSONObject("thumbnail")
                    ?.optJSONArray("thumbnails")

            val bannerUrl = getBestThumbnailUrl(bannerThumbs, null).ifBlank { artist.thumbnail }

            // Radio Endpoint
            val radioEndpoint = header?.optJSONObject("startRadioButton")
                ?.optJSONObject("buttonRenderer")
                ?.optJSONObject("navigationEndpoint")
                ?.optJSONObject("watchEndpoint")
            val radioPlaylistId = radioEndpoint?.optString("playlistId")

            val topSongs = mutableListOf<Track>()
            val albums = mutableListOf<PlaylistResult>()
            val singles = mutableListOf<PlaylistResult>()
            val similarArtists = mutableListOf<Artist>()

            val sectionList = json.optJSONObject("contents")
                ?.optJSONObject("singleColumnBrowseResultsRenderer")
                ?.optJSONArray("tabs")
                ?.optJSONObject(0)
                ?.optJSONObject("tabRenderer")
                ?.optJSONObject("content")
                ?.optJSONObject("sectionListRenderer")
                ?.optJSONArray("contents") ?: JSONArray()

            for (i in 0 until sectionList.length()) {
                val secObj = sectionList.optJSONObject(i) ?: continue
                val shelf = secObj.optJSONObject("musicShelfRenderer")
                    ?: secObj.optJSONObject("musicCarouselShelfRenderer") ?: continue

                val shelfHeader = shelf.optJSONObject("header")?.optJSONObject("musicShelfBasicHeaderRenderer")
                    ?: shelf.optJSONObject("header")?.optJSONObject("musicCarouselShelfBasicHeaderRenderer")
                val shelfTitle = shelfHeader?.optJSONObject("title")?.optJSONArray("runs")?.optJSONObject(0)?.optString("text")?.lowercase()
                    ?: "songs"

                val contents = shelf.optJSONArray("contents") ?: JSONArray()

                for (j in 0 until contents.length()) {
                    val cObj = contents.optJSONObject(j) ?: continue
                    val itemResp = cObj.optJSONObject("musicResponsiveListItemRenderer")
                    if (itemResp != null) {
                        val parsedSongs = mutableListOf<Track>()
                        val parsedArtists = mutableListOf<Artist>()
                        val parsedPlaylists = mutableListOf<PlaylistResult>()
                        parseMusicListItem(itemResp, parsedSongs, parsedArtists, parsedPlaylists)

                        if (shelfTitle.contains("song") || (i == 0 && !shelfTitle.contains("album") && !shelfTitle.contains("single"))) {
                            topSongs.addAll(parsedSongs)
                        } else if (shelfTitle.contains("album")) {
                            albums.addAll(parsedPlaylists)
                        } else if (shelfTitle.contains("single") || shelfTitle.contains("ep")) {
                            singles.addAll(parsedPlaylists)
                        } else if (shelfTitle.contains("fan") || shelfTitle.contains("like") || shelfTitle.contains("similar")) {
                            similarArtists.addAll(parsedArtists)
                        }
                    }

                    val twoRow = cObj.optJSONObject("musicTwoRowItemRenderer")
                    if (twoRow != null) {
                        val parsedTrack = parseMusicTwoRowItem(twoRow)
                        if (shelfTitle.contains("album")) {
                            val aTitle = twoRow.optJSONObject("title")?.optJSONArray("runs")?.optJSONObject(0)?.optString("text") ?: "Album"
                            val aNav = twoRow.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")?.optString("browseId")
                            val aThumb = getBestThumbnailUrl(twoRow.optJSONObject("thumbnailRenderer")?.optJSONObject("musicThumbnailRenderer")?.optJSONObject("thumbnail")?.optJSONArray("thumbnails"), null)
                            albums.add(PlaylistResult(id = aNav ?: "pl:$aTitle", title = aTitle, thumbnail = aThumb.ifBlank { null }, author = artistName))
                        } else if (shelfTitle.contains("single") || shelfTitle.contains("ep")) {
                            val sTitle = twoRow.optJSONObject("title")?.optJSONArray("runs")?.optJSONObject(0)?.optString("text") ?: "Single"
                            val sNav = twoRow.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")?.optString("browseId")
                            val sThumb = getBestThumbnailUrl(twoRow.optJSONObject("thumbnailRenderer")?.optJSONObject("musicThumbnailRenderer")?.optJSONObject("thumbnail")?.optJSONArray("thumbnails"), null)
                            singles.add(PlaylistResult(id = sNav ?: "pl:$sTitle", title = sTitle, thumbnail = sThumb.ifBlank { null }, author = artistName))
                        } else if (shelfTitle.contains("fan") || shelfTitle.contains("like") || shelfTitle.contains("similar")) {
                            val artName = twoRow.optJSONObject("title")?.optJSONArray("runs")?.optJSONObject(0)?.optString("text") ?: "Artist"
                            val artNav = twoRow.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")?.optString("browseId")
                            val artThumb = getBestThumbnailUrl(twoRow.optJSONObject("thumbnailRenderer")?.optJSONObject("musicThumbnailRenderer")?.optJSONObject("thumbnail")?.optJSONArray("thumbnails"), null)
                            similarArtists.add(Artist(id = artNav ?: "yt:$artName", name = artName, thumbnail = artThumb.ifBlank { null }))
                        } else if (parsedTrack != null && shelfTitle.contains("song")) {
                            topSongs.add(parsedTrack)
                        }
                    }
                }
            }

            // Strictly filter top tracks so only official songs by this artist are included
            val targetName = artistName.lowercase().trim()
            val filteredOfficial = topSongs.filter { trk ->
                val trkArtist = trk.artist.lowercase()
                val isMatchingArtist = trkArtist.contains(targetName) || targetName.contains(trkArtist) || trk.artist.contains("YouTube Artist", ignoreCase = true)
                val isCoverOrFanEdit = trk.title.contains("cover", ignoreCase = true) ||
                        trk.title.contains("remake", ignoreCase = true) ||
                        trk.title.contains("karaoke", ignoreCase = true) ||
                        trk.title.contains("instrumental", ignoreCase = true) ||
                        trk.title.contains("reaction", ignoreCase = true) ||
                        trk.title.contains("tutorial", ignoreCase = true) ||
                        trk.title.contains("parody", ignoreCase = true)
                isMatchingArtist && !isCoverOrFanEdit
            }.distinctBy { it.id }

            val resolvedTopSongs = if (filteredOfficial.isNotEmpty()) {
                filteredOfficial
            } else {
                val searchHits = search(artistName, FILTER_SONGS)
                searchHits.songs.filter { trk ->
                    val trkArtist = trk.artist.lowercase()
                    (trkArtist.contains(targetName) || targetName.contains(trkArtist)) &&
                    !trk.title.contains("cover", ignoreCase = true) &&
                    !trk.title.contains("karaoke", ignoreCase = true)
                }.ifEmpty { searchHits.songs }
            }

            val finalArtist = artist.copy(
                id = effectiveChannelId,
                name = artistName,
                thumbnail = bannerUrl ?: artist.thumbnail,
                subscribers = subscribers ?: artist.subscribers
            )

            ArtistPage(
                artist = finalArtist,
                bannerUrl = bannerUrl,
                description = description,
                subscribers = subscribers,
                topSongs = resolvedTopSongs.distinctBy { it.id },
                albums = albums.distinctBy { it.id },
                singles = singles.distinctBy { it.id },
                similarArtists = similarArtists.distinctBy { it.id },
                radioPlaylistId = radioPlaylistId
            )
        } catch (e: Exception) {
            val searchHits = search("${artist.name} top songs")
            if (searchHits.songs.isNotEmpty()) {
                ArtistPage(
                    artist = artist,
                    bannerUrl = artist.thumbnail,
                    topSongs = searchHits.songs
                )
            } else null
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
            put("context", createClientContext())
        }
    }

    private fun createContinuationContext(continuation: String): JSONObject {
        return JSONObject().apply {
            put("continuation", continuation)
            put("context", createClientContext())
        }
    }

    private fun createWebRemixContext(query: String, params: String? = null): JSONObject {
        return JSONObject().apply {
            put("query", query)
            if (!params.isNullOrBlank()) put("params", params)
            put("context", createClientContext())
        }
    }

    private fun createClientContext(): JSONObject {
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
