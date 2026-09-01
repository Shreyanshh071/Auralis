package com.auralis.music.data.network

import com.auralis.music.domain.model.*
import com.auralis.music.domain.recommendations.TrackDeduplicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
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

        const val FILTER_SONGS = "EgWKAQIIAWoSEAUQCRAKEAMQDhAEEBAQFRAR"
        const val FILTER_ARTISTS = "EgWKAQIgAWoSEAUQCRAKEAMQDhAEEBAQFRAR"
        const val FILTER_PLAYLISTS = "EgeKAQQoADgBahIQBRAJEAoQAxAOEAQQEBAVEBE%3D"
        const val FILTER_ALBUMS = "EgWKAQIYAWoSEAUQCRAKEAMQDhAEEBAQFRAR"
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
     * Curates a fully diverse, genre-matched random radio queue:
     * - Zero duplicate song titles or live/alternate takes (via TrackDeduplicator)
     * - Maximum 2 songs from the seed artist
     * - Maximum 1-2 songs from any other artist
     * - Shuffles and interleaves artists so no two consecutive songs are by the same artist
     */
    fun curateDiverseGenreQueue(
        candidates: List<Track>,
        seedVideoId: String,
        seedArtist: String? = null,
        seedTitle: String? = null
    ): List<Track> {
        if (candidates.isEmpty()) return emptyList()

        val seedBaseTitle = if (!seedTitle.isNullOrBlank()) TrackDeduplicator.extractBaseSongTitle(seedTitle) else ""

        // 1. Filter out seed track itself, invalid artist names, and all remixes/versions/edits of the seed track
        val validCandidates = candidates.filter { track ->
            if (track.id == seedVideoId || TrackDeduplicator.isInvalidArtistName(track.artist)) {
                return@filter false
            }
            if (TrackDeduplicator.isVideoOrBloatedTrack(track) && track.duration > 330) {
                return@filter false
            }
            if (seedBaseTitle.isNotBlank()) {
                val candidateBaseTitle = TrackDeduplicator.extractBaseSongTitle(track.title)
                if (candidateBaseTitle.isNotBlank() && (
                    candidateBaseTitle == seedBaseTitle ||
                    (candidateBaseTitle.length >= 10 && seedBaseTitle.length >= 10 && candidateBaseTitle == seedBaseTitle)
                )) {
                    return@filter false
                }
            }
            true
        }

        // 2. Comprehensive Deduplication: Ensure only ONE version of ANY song title exists in the queue,
        // prioritizing authentic studio audio tracks over music video uploads.
        val deduplicated = TrackDeduplicator.deduplicateTracks(validCandidates)

        val cleanSeedArtist = seedArtist?.split("&", ",", "feat.", "ft.", "Feat.", "Ft.", "with")
            ?.firstOrNull()?.trim()?.lowercase() ?: seedArtist?.trim()?.lowercase() ?: ""

        // 3. Group by Normalized Primary Artist
        val artistGroups = mutableMapOf<String, MutableList<Track>>()
        for (track in deduplicated) {
            val primaryArtistName = track.artist.split("&", ",", "feat.", "ft.", "Feat.", "Ft.", "with")
                .firstOrNull()?.trim()?.lowercase() ?: track.artist.trim().lowercase()
            val list = artistGroups.getOrPut(primaryArtistName) { mutableListOf() }
            list.add(track)
        }

        // 4. Cap Artist Tracks: Seed artist gets max 2, all other artists get max 1 (or max 2 if pool is small)
        val maxOtherPerArtist = if (artistGroups.size >= 8) 1 else 2
        val cappedArtistQueues = mutableListOf<MutableList<Track>>()

        for ((artistKey, trackList) in artistGroups) {
            val isSeed = cleanSeedArtist.isNotBlank() && (artistKey == cleanSeedArtist || artistKey.contains(cleanSeedArtist) || cleanSeedArtist.contains(artistKey))
            val limit = if (isSeed) 2 else maxOtherPerArtist
            val sampled = trackList.shuffled().take(limit).toMutableList()
            if (sampled.isNotEmpty()) {
                cappedArtistQueues.add(sampled)
            }
        }

        // 5. Interleave & Shuffle Artists (Round-Robin with non-repeating artist constraints)
        val result = mutableListOf<Track>()
        val activeQueues = cappedArtistQueues.shuffled().toMutableList()
        var lastArtist = ""

        while (activeQueues.isNotEmpty() && result.size < 35) {
            // Find a queue whose next track is not by the last artist
            val nextQueueIndex = activeQueues.indexOfFirst { queue ->
                val nextTrack = queue.firstOrNull() ?: return@indexOfFirst false
                val nextArtist = nextTrack.artist.lowercase()
                lastArtist.isBlank() || (nextArtist != lastArtist && !nextArtist.contains(lastArtist) && !lastArtist.contains(nextArtist))
            }

            val chosenIndex = if (nextQueueIndex != -1) nextQueueIndex else 0
            val chosenQueue = activeQueues[chosenIndex]
            val track = chosenQueue.removeAt(0)
            result.add(track)
            lastArtist = track.artist.lowercase()

            if (chosenQueue.isEmpty()) {
                activeQueues.removeAt(chosenIndex)
            }
        }

        return result
    }

    /**
     * Fetches smart radio / autoplay tracks for a given seed track.
     * Generates a curated, genre-matching radio queue with:
     * - Top hits from other artists in the exact same genre & vibe
     * - Strict maximum of 2 songs from the seed artist
     * - Strict maximum of 1 song per other artist (or 2 if pool is very small)
     * - Zero duplicate song titles or live/alternate versions
     * - Shuffled & interleaved so no two consecutive songs share the same artist
     */
    open suspend fun getRadioTracks(
        videoId: String,
        artist: String? = null,
        title: String? = null
    ): List<Track> = withContext(Dispatchers.IO) {
        val candidatesPool = java.util.Collections.synchronizedList(mutableListOf<Track>())

        val primaryArtist = artist?.split("&", ",", "feat.", "ft.", "Feat.", "Ft.", "with")?.firstOrNull()?.trim()
            ?.ifBlank { null } ?: artist?.trim()?.ifBlank { null }
        val cleanTitle = if (!title.isNullOrBlank()) TitleCleaner.cleanTitle(title) else null

        val isSpotifyId = videoId.startsWith("sp_") || videoId.startsWith("spotify:")
        val effectiveVideoId = if (isSpotifyId) {
            AudioStreamResolver.getMatchedVideoId(videoId) ?: ""
        } else videoId

        val hasValidYouTubeId = effectiveVideoId.isNotBlank() && !effectiveVideoId.startsWith("sp_") && !effectiveVideoId.startsWith("spotify:")

        coroutineScope {
            // ── SOURCE 1: Official YouTube Music Next / Radio Playlist Panel ──
            if (hasValidYouTubeId) {
                launch(Dispatchers.IO) {
                    try {
                        val requestBody = JSONObject().apply {
                            put("videoId", effectiveVideoId)
                            put("playlistId", "RDAMVM$effectiveVideoId")
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
                                val parsed = parseRadioFromNextResponse(json, effectiveVideoId)
                                candidatesPool.addAll(parsed)
                            }
                        }
                    } catch (_: Exception) {}
                }

                // ── SOURCE 2: Related browse endpoint (Deep YouTube genre recommendations) ──
                launch(Dispatchers.IO) {
                    try {
                        val (browseId, params) = getNextAndRelatedEndpoint(effectiveVideoId)
                        if (!browseId.isNullOrBlank() || !params.isNullOrBlank()) {
                            val related = getRelated(browseId, params)
                            candidatesPool.addAll(related)
                        }
                    } catch (_: Exception) {}
                }
            }

            // ── SOURCE 3: Artist Top Tracks & Similar Artists Pool ──
            if (!primaryArtist.isNullOrBlank()) {
                launch(Dispatchers.IO) {
                    try {
                        val artistSongs = search("$primaryArtist songs", FILTER_SONGS).songs
                        candidatesPool.addAll(artistSongs)
                    } catch (_: Exception) {}
                }
            }
        }

        // Fallback: If still empty, attempt basic next request
        if (candidatesPool.isEmpty()) {
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
                        candidatesPool.addAll(parseRadioFromNextResponse(json, videoId))
                    }
                }
            } catch (_: Exception) {}
        }

        // Curate into a perfectly diverse, genre-matched, randomized queue without duplicate song versions
        curateDiverseGenreQueue(candidatesPool.toList(), videoId, primaryArtist, cleanTitle)
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
                                .map { line ->
                                    LyricLine(
                                        time = 0L,
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

            val bannerThumbs = header?.optJSONObject("thumbnail")
                ?.optJSONObject("musicThumbnailRenderer")
                ?.optJSONObject("thumbnail")
                ?.optJSONArray("thumbnails")
                ?: header?.optJSONObject("foregroundThumbnail")
                    ?.optJSONObject("musicThumbnailRenderer")
                    ?.optJSONObject("thumbnail")
                    ?.optJSONArray("thumbnails")
            var bannerUrl = getBestThumbnailUrl(bannerThumbs, null).ifBlank { artist.thumbnail }

            // If banner is missing or the known all-black Donda square, resolve HD portrait via Wikipedia
            if (bannerUrl.isNullOrBlank() ||
                bannerUrl.contains("IFlc3sf6sHV3TAZ_5vhyHQiKb9D4AdSlDkiTSgsRiicnzLASXwVr1n22EEg6Vtd2XBlyJslm8xlYiA") ||
                artistName.equals("Kanye West", ignoreCase = true) ||
                artistName.equals("Ye", ignoreCase = true)
            ) {
                val wikiPortrait = fetchWikipediaArtistPortrait(artistName)
                if (!wikiPortrait.isNullOrBlank()) {
                    bannerUrl = wikiPortrait
                }
            }

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
                    val lowerText = text.lowercase()
                    val isUnwantedChip = lowerText.contains("sleep") || lowerText.contains("therapy") ||
                        lowerText.contains("rain") || lowerText.contains("white noise") ||
                        lowerText.contains("ambient sound")
                    if (!isUnwantedChip) {
                        chips.add(HomeChip(title = text, endpointBrowseId = browseId, params = params))
                    }
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

                val lowerTitle = title.lowercase()
                val lowerSubtitle = (subtitle ?: "").lowercase()
                val isUnwantedSection = lowerTitle.contains("rain therapy") || lowerTitle.contains("rain sound") ||
                    lowerTitle.contains("sleep therapy") || lowerTitle.contains("white noise") ||
                    lowerTitle.contains("nature sound") || lowerTitle.contains("binaural") ||
                    lowerTitle.contains("sleep sound") || lowerTitle.contains("deep sleep") ||
                    lowerSubtitle.contains("rain therapy") || lowerSubtitle.contains("sleep therapy") ||
                    lowerSubtitle.contains("white noise")

                if (isUnwantedSection) continue

                val shelfItems = shelfObj.optJSONArray("contents") ?: JSONArray()
                val tracks = mutableListOf<Track>()
                val albums = mutableListOf<PlaylistResult>()

                for (j in 0 until shelfItems.length()) {
                    val itemContainer = shelfItems.optJSONObject(j) ?: continue
                    val twoRow = itemContainer.optJSONObject("musicTwoRowItemRenderer")
                    if (twoRow != null) {
                        val parsedTrack = parseMusicTwoRowItem(twoRow)
                        if (parsedTrack != null) {
                            if (!com.auralis.music.domain.recommendations.TrackDeduplicator.isJunkOrNoiseTrack(parsedTrack)) {
                                tracks.add(parsedTrack)
                            }
                        } else {
                            val aTitle = twoRow.optJSONObject("title")?.optJSONArray("runs")?.optJSONObject(0)?.optString("text")
                            val aNav = twoRow.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")?.optString("browseId")
                            val aThumb = getBestThumbnailUrl(twoRow.optJSONObject("thumbnailRenderer")?.optJSONObject("musicThumbnailRenderer")?.optJSONObject("thumbnail")?.optJSONArray("thumbnails"), null)
                            val subRuns = twoRow.optJSONObject("subtitle")?.optJSONArray("runs")
                            var aAuthor: String? = null
                            if (subRuns != null && subRuns.length() > 0) {
                                aAuthor = subRuns.optJSONObject(0)?.optString("text")
                            }
                            if (!aTitle.isNullOrBlank() && !aNav.isNullOrBlank()) {
                                val aTitleLower = aTitle.lowercase()
                                val aAuthorLower = (aAuthor ?: "").lowercase()
                                if (!aTitleLower.contains("rain therapy") && !aTitleLower.contains("rain sound") &&
                                    !aTitleLower.contains("sleep therapy") && !aTitleLower.contains("white noise") &&
                                    !aAuthorLower.contains("rain therapy") && !aAuthorLower.contains("rain sound") &&
                                    !aAuthorLower.contains("sleep therapy") && !aAuthorLower.contains("white noise")) {
                                    albums.add(PlaylistResult(id = aNav, title = aTitle, thumbnail = aThumb.ifBlank { null }, author = aAuthor))
                                }
                            }
                        }
                    }
                    val responsive = itemContainer.optJSONObject("musicResponsiveListItemRenderer")
                    if (responsive != null) {
                        val dummyArtists = mutableListOf<Artist>()
                        val parsedAlbums = mutableListOf<PlaylistResult>()
                        val parsedPlaylists = mutableListOf<PlaylistResult>()
                        parseMusicListItem(responsive, tracks, dummyArtists, parsedAlbums, parsedPlaylists)
                        albums.addAll(parsedAlbums.filter {
                            val aTitle = it.title.lowercase()
                            !aTitle.contains("rain therapy") && !aTitle.contains("rain sound") && !aTitle.contains("sleep therapy")
                        })
                        albums.addAll(parsedPlaylists.filter {
                            val aTitle = it.title.lowercase()
                            !aTitle.contains("rain therapy") && !aTitle.contains("rain sound") && !aTitle.contains("sleep therapy")
                        })
                    }
                }

                if (tracks.isNotEmpty() || albums.isNotEmpty()) {
                    sections.add(
                        HomeSection(
                            id = "section_$i",
                            title = title,
                            subtitle = subtitle,
                            thumbnail = tracks.firstOrNull()?.thumbnail ?: albums.firstOrNull()?.thumbnail,
                            items = tracks,
                            albums = albums
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
        var topResult: SearchTopResult? = null
        val songs = mutableListOf<Track>()
        val albums = mutableListOf<PlaylistResult>()
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
                    val cardTop = parseMusicCardShelf(cardShelf, songs, albums, artists, playlists)
                    if (topResult == null && cardTop != null) {
                        topResult = cardTop
                    }
                }

                val shelf = section?.optJSONObject("musicShelfRenderer")
                if (shelf != null) {
                    val shelfContents = shelf.optJSONArray("contents") ?: JSONArray()
                    for (j in 0 until shelfContents.length()) {
                        val item = shelfContents.optJSONObject(j)?.optJSONObject("musicResponsiveListItemRenderer")
                        if (item != null) {
                            parseMusicListItem(item, songs, artists, albums, playlists)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // Ignored
        }

        return SearchResults(
            topResult = topResult,
            songs = songs,
            albums = albums,
            artists = artists,
            playlists = playlists
        )
    }

    private fun parseMusicCardShelf(
        card: JSONObject,
        songs: MutableList<Track>,
        albums: MutableList<PlaylistResult>,
        artists: MutableList<Artist>,
        playlists: MutableList<PlaylistResult>
    ): SearchTopResult? {
        val title = card.optJSONObject("title")?.optJSONArray("runs")?.optJSONObject(0)?.optString("text") ?: return null
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
            val artist = Artist(id = browseId ?: "yt:$title", name = title, thumbnail = thumbUrl.ifBlank { null }, query = "$title top songs")
            if (artists.none { it.id == browseId || it.name.equals(title, ignoreCase = true) }) {
                artists.add(0, artist)
            }
            return SearchTopResult.ArtistResult(artist)
        } else if (cardType.contains("album") || cardType.contains("ep") || cardType.contains("single") || (browseId != null && browseId.startsWith("MPRE") && videoId.isNullOrBlank())) {
            val author = if (subParts.size > 1) subParts[1] else null
            val album = PlaylistResult(id = browseId ?: "pl:$title", title = title, thumbnail = thumbUrl.ifBlank { null }, author = author)
            if (albums.none { it.id == browseId || it.title.equals(title, ignoreCase = true) }) {
                albums.add(0, album)
            }
            return SearchTopResult.AlbumResult(album)
        } else if (cardType.contains("playlist") || (browseId != null && (browseId.startsWith("VL") || browseId.startsWith("PL")) && videoId.isNullOrBlank())) {
            val author = if (subParts.size > 1) subParts[1] else null
            val pl = PlaylistResult(id = browseId ?: "pl:$title", title = title, thumbnail = thumbUrl.ifBlank { null }, author = author)
            if (playlists.none { it.id == browseId || it.title.equals(title, ignoreCase = true) }) {
                playlists.add(0, pl)
            }
            return SearchTopResult.AlbumResult(pl)
        } else if (!videoId.isNullOrBlank()) {
            val artist = if (subParts.size > 1) subParts[1] else "YouTube Artist"
            var duration = 200L
            val durStr = subParts.find { it.matches(Regex("""\d+:\d+(:\d+)?""")) }
            if (durStr != null) {
                duration = parseDurationToSeconds(durStr)
            }
            val track = Track(
                id = videoId,
                title = TitleCleaner.cleanTitle(title),
                artist = artist,
                duration = duration,
                thumbnail = thumbUrl.ifBlank { "https://i.ytimg.com/vi/$videoId/hqdefault.jpg" },
                source = TrackSource.YOUTUBE
            )
            if (songs.none { it.id == videoId }) {
                songs.add(0, track)
            }
            return SearchTopResult.SongResult(track)
        }
        return null
    }

    fun parseMusicListItem(
        item: JSONObject,
        songs: MutableList<Track>,
        artists: MutableList<Artist>,
        albums: MutableList<PlaylistResult> = mutableListOf(),
        playlists: MutableList<PlaylistResult> = mutableListOf()
    ) {
        val flexColumns = item.optJSONArray("flexColumns") ?: return
        val col0Runs = flexColumns.optJSONObject(0)
            ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
            ?.optJSONObject("text")
            ?.optJSONArray("runs") ?: return

        val title = col0Runs.optJSONObject(0)?.optString("text") ?: return
        val itemNav = item.optJSONObject("navigationEndpoint")
        val navEndpoint = col0Runs.optJSONObject(0)?.optJSONObject("navigationEndpoint") ?: itemNav
        var browseId = navEndpoint?.optJSONObject("browseEndpoint")?.optString("browseId")
            ?: itemNav?.optJSONObject("browseEndpoint")?.optString("browseId")

        // Also check menu endpoints for album playlistId (e.g. OLAK5uy_...)
        if (browseId.isNullOrBlank()) {
            val menuItems = item.optJSONObject("menu")
                ?.optJSONObject("menuRenderer")
                ?.optJSONArray("items")
            if (menuItems != null) {
                for (m in 0 until menuItems.length()) {
                    val mItem = menuItems.optJSONObject(m)
                    val queueTarget = mItem?.optJSONObject("menuServiceItemRenderer")
                        ?.optJSONObject("serviceEndpoint")
                        ?.optJSONObject("queueAddEndpoint")
                        ?.optJSONObject("queueTarget")
                    val plId = queueTarget?.optString("playlistId")
                    if (!plId.isNullOrBlank() && (plId.startsWith("OLAK") || plId.startsWith("MPRE") || plId.startsWith("VL"))) {
                        browseId = plId
                        break
                    }
                }
            }
        }

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
        var viewsStr: String? = null
        var itemType = ""

        val typeKeywords = setOf("song", "video", "artist", "album", "single", "ep", "playlist")

        if (col1Runs != null) {
            for (r in 0 until col1Runs.length()) {
                val runObj = col1Runs.optJSONObject(r) ?: continue
                val text = runObj.optString("text").trim()
                if (text.isBlank() || text == "•") continue

                val runBrowseId = runObj.optJSONObject("navigationEndpoint")
                    ?.optJSONObject("browseEndpoint")
                    ?.optString("browseId")

                val lowerText = text.lowercase()

                if (runBrowseId != null && runBrowseId.startsWith("UC")) {
                    artistName = text
                } else if (runBrowseId != null && (runBrowseId.startsWith("MPRE") || runBrowseId.startsWith("FEmusic"))) {
                    albumName = text
                } else if (text.matches(Regex("""\d+:\d+(:\d+)?"""))) {
                    durationSec = parseDurationToSeconds(text)
                } else if (lowerText.contains("play") || lowerText.contains("view") || lowerText.contains("listener") || lowerText.contains("subscriber")) {
                    viewsStr = text
                } else if (typeKeywords.contains(lowerText)) {
                    itemType = lowerText
                } else {
                    if (artistName == "Unknown Artist") {
                        artistName = text
                    } else if (albumName == null) {
                        albumName = text
                    }
                }
            }
        }

        val col2Runs = if (flexColumns.length() > 2) {
            flexColumns.optJSONObject(2)
                ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                ?.optJSONObject("text")
                ?.optJSONArray("runs")
        } else null

        if (col2Runs != null) {
            for (r in 0 until col2Runs.length()) {
                val runObj = col2Runs.optJSONObject(r) ?: continue
                val text = runObj.optString("text").trim()
                if (text.isBlank() || text == "•") continue
                val lowerText = text.lowercase()

                if (text.matches(Regex("""\d+:\d+(:\d+)?"""))) {
                    if (durationSec == 0L) durationSec = parseDurationToSeconds(text)
                } else if (lowerText.contains("play") || lowerText.contains("view") || lowerText.contains("listener")) {
                    if (viewsStr == null) viewsStr = text
                } else if (!typeKeywords.contains(lowerText) && albumName == null) {
                    albumName = text
                }
            }
        }

        val thumbnails = item.optJSONObject("thumbnail")
            ?.optJSONObject("musicThumbnailRenderer")
            ?.optJSONObject("thumbnail")
            ?.optJSONArray("thumbnails")

        val thumbUrl = getBestThumbnailUrl(thumbnails, videoId)

        val isAlbum = itemType.contains("album") || itemType.contains("ep") || itemType.contains("single") || (browseId != null && browseId.startsWith("MPRE"))
        val isPlaylist = itemType.contains("playlist") || (browseId != null && (browseId.startsWith("VL") || browseId.startsWith("PL")))

        val cleanArtist = if (artistName.equals("Song", ignoreCase = true) || artistName.equals("Video", ignoreCase = true) || artistName.equals("Unknown Artist", ignoreCase = true)) {
            if (!albumName.isNullOrBlank() && !typeKeywords.contains(albumName.lowercase())) {
                val temp = albumName
                albumName = null
                temp
            } else "YouTube Artist"
        } else artistName

        val cleanAlbum = if (albumName?.contains("play", ignoreCase = true) == true || albumName?.contains("view", ignoreCase = true) == true) null else albumName

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
        } else if (isAlbum && videoId.isNullOrBlank()) {
            if (albums.none { it.id == browseId || it.title.equals(title, ignoreCase = true) }) {
                albums.add(
                    PlaylistResult(
                        id = browseId ?: "pl:$title",
                        title = title,
                        thumbnail = thumbUrl.ifBlank { null },
                        author = cleanArtist
                    )
                )
            }
        } else if (isPlaylist && videoId.isNullOrBlank()) {
            if (playlists.none { it.id == browseId || it.title.equals(title, ignoreCase = true) }) {
                playlists.add(
                    PlaylistResult(
                        id = browseId ?: "pl:$title",
                        title = title,
                        thumbnail = thumbUrl.ifBlank { null },
                        author = cleanArtist
                    )
                )
            }
        } else if (!videoId.isNullOrBlank()) {
            if (songs.none { it.id == videoId }) {
                songs.add(
                    Track(
                        id = videoId,
                        title = TitleCleaner.cleanTitle(title),
                        artist = cleanArtist,
                        album = cleanAlbum,
                        duration = durationSec,
                        thumbnail = thumbUrl,
                        views = viewsStr,
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
                } else if (url.contains("i.ytimg.com") || url.contains("img.youtube.com")) {
                    val noQuery = url.substringBefore('?')
                    url = noQuery.replace("default.jpg", "hqdefault.jpg")
                        .replace("mqdefault.jpg", "hqdefault.jpg")
                        .replace("hq720.jpg", "hqdefault.jpg")
                }
                return url
            }
        }
        return if (!videoId.isNullOrBlank()) "https://i.ytimg.com/vi/$videoId/hqdefault.jpg" else ""
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

    suspend fun fetchWikipediaArtistPortrait(artistName: String): String? = withContext(Dispatchers.IO) {
        if (artistName.isBlank()) return@withContext null
        try {
            val cleanName = when (artistName.trim().lowercase()) {
                "ye" -> "Kanye_West"
                else -> artistName.trim().replace(" ", "_")
            }
            val encoded = java.net.URLEncoder.encode(cleanName, "UTF-8")
            val url = "https://en.wikipedia.org/w/api.php?action=query&titles=$encoded&prop=pageimages&format=json&pithumbsize=1280"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "AuralisMusicApp/1.0 (contact@auralis.app)")
                .build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return@withContext null
            val body = response.body?.string() ?: return@withContext null
            val json = JSONObject(body)
            val pages = json.optJSONObject("query")?.optJSONObject("pages") ?: return@withContext null
            val firstKey = pages.keys().asSequence().firstOrNull() ?: return@withContext null
            val pageObj = pages.optJSONObject(firstKey)
            val thumb = pageObj?.optJSONObject("thumbnail")?.optString("source")
            if (!thumb.isNullOrBlank()) {
                return@withContext thumb
            }

            // Fallback search query on Wikipedia
            val searchEncoded = java.net.URLEncoder.encode("${artistName.trim()} musician", "UTF-8")
            val searchUrl = "https://en.wikipedia.org/w/api.php?action=query&generator=search&gsrsearch=$searchEncoded&gsrlimit=1&prop=pageimages&pithumbsize=1280&format=json"
            val searchReq = Request.Builder().url(searchUrl).header("User-Agent", "AuralisMusicApp/1.0 (contact@auralis.app)").build()
            val searchResp = client.newCall(searchReq).execute()
            if (searchResp.isSuccessful) {
                val sBody = searchResp.body?.string() ?: return@withContext null
                val sJson = JSONObject(sBody)
                val sPages = sJson.optJSONObject("query")?.optJSONObject("pages") ?: return@withContext null
                val sKey = sPages.keys().asSequence().firstOrNull() ?: return@withContext null
                return@withContext sPages.optJSONObject(sKey)?.optJSONObject("thumbnail")?.optString("source")?.ifBlank { null }
            }
            null
        } catch (_: Exception) {
            null
        }
    }
}
