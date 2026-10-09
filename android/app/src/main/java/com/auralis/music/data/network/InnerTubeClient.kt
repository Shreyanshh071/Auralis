package com.auralis.music.data.network

import com.auralis.music.domain.model.*
import com.auralis.music.domain.recommendations.TrackDeduplicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
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
    private data class CachedSearch(val timeMs: Long, val results: SearchResults)
    private val recentSearches = java.util.concurrent.ConcurrentHashMap<String, CachedSearch>()
    private fun cachedSearch(query: String, params: String?): SearchResults? =
        recentSearches[query.lowercase() + "|" + params.orEmpty()]?.takeIf {
            System.nanoTime() / 1_000_000 - it.timeMs < 60_000L
        }?.results
    private fun rememberSearch(query: String, params: String?, results: SearchResults): SearchResults {
        if (results.isNotEmpty()) {
            if (recentSearches.size >= 48) recentSearches.entries.minByOrNull { it.value.timeMs }?.let { recentSearches.remove(it.key, it.value) }
            recentSearches[query.lowercase() + "|" + params.orEmpty()] = CachedSearch(System.nanoTime() / 1_000_000, results)
        }
        return results
    }

    companion object {
        private val ALBUM_TRACK_PLAYS = Regex("\"text\":\"([0-9.,]+[KMB]?) plays\"")
        private const val YT_MUSIC_API = "https://music.youtube.com/youtubei/v1"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        const val FILTER_SONGS = "EgWKAQIIAWoKEAkQBRAKEAMQBA%3D%3D"
        const val FILTER_VIDEOS = "EgWKAQIQAWoKEAkQChAFEAMQBA%3D%3D"
        const val FILTER_ARTISTS = "EgWKAQIgAWoKEAkQChAFEAMQBA%3D%3D"
        const val FILTER_PLAYLISTS = "EgeKAQQoADgBahIQBRAJEAoQAxAOEAQQEBAVEBE%3D"
        const val FILTER_ALBUMS = "EgWKAQIYAWoKEAkQChAFEAMQBA%3D%3D"
        private val releaseTypeCache = java.util.concurrent.ConcurrentHashMap<String, String>()
    }

    /**
     * Searches YouTube Music exclusively using the WEB_REMIX InnerTube endpoint.
     */
    open suspend fun search(query: String, params: String? = null): SearchResults = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return@withContext SearchResults()

        cachedSearch(trimmed, params)?.let { return@withContext it }
        try {
            val requestBody = createWebRemixContext(trimmed, params)
            val request = Request.Builder()
                .url("$YT_MUSIC_API/search?prettyPrint=false")
                .post(requestBody.toString().toRequestBody(JSON_MEDIA_TYPE))
                .header("Referer", "https://music.youtube.com/")
                .header("Origin", "https://music.youtube.com")
                .build()

            val body = client.searchBody(request, 15_000L)
            rememberSearch(trimmed, params, parseYtMusicSearchResults(JSONObject(body)))
        } catch (e: Exception) {
            kotlin.coroutines.coroutineContext.ensureActive()
            SearchResults(requestFailed = true)
        }
    }

    /** Separate cancellable transport for requests replaced on every edit of the search field. */
    open suspend fun searchLive(query: String, params: String? = null): SearchResults = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return@withContext SearchResults()
        cachedSearch(trimmed, params)?.let { return@withContext it }
        try {
            val request = Request.Builder()
                .url("$YT_MUSIC_API/search?prettyPrint=false")
                .post(createWebRemixContext(trimmed, params).toString().toRequestBody(JSON_MEDIA_TYPE))
                .header("Referer", "https://music.youtube.com/")
                .header("Origin", "https://music.youtube.com")
                .build()
            rememberSearch(trimmed, params, parseYtMusicSearchResults(JSONObject(client.searchBody(request, 15_000L))))
        } catch (e: Exception) {
            // Query cancellation must propagate so abandoned requests cannot publish results.
            kotlin.coroutines.coroutineContext.ensureActive()
            SearchResults()
        }
    }

    /**
     * Fetches the YouTube Music Home page (FEmusic_home).
     * Returns paired HomeChips (Moods & moments) and Carousel HomeSections.
     */
    open suspend fun getHome(params: String? = null, continuation: String? = null): Pair<List<HomeChip>, List<HomeSection>> = withContext(Dispatchers.IO) {
        try {
            val requestBody = if (continuation != null) {
                createContinuationContext(continuation, localizedLabels = true)
            } else {
                createBrowseContext("FEmusic_home", params, localizedLabels = true)
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
     * Total plays of an album: the sum of the per-track "N plays" on its YouTube Music page
     * (for a release of four tracks or fewer, its biggest track only).
     * Search results carry no popularity for albums, so this is how an album named like the query
     * ("graduation" -> Kanye West's Graduation) is weighed against a same-named song.
     * Returns 0 when the page can't be read.
     */
    open suspend fun getAlbumTotalPlays(browseId: String): Long = getAlbumPlays(browseId).first

    /** Resolve an album by the browse ID attached to a song, without a fuzzy title search. */
    open suspend fun getAlbumById(browseId: String): PlaylistResult? = withContext(Dispatchers.IO) {
        if (!browseId.startsWith("MPRE")) return@withContext null
        try {
            val request = Request.Builder()
                .url("$YT_MUSIC_API/browse?prettyPrint=false")
                .post(createBrowseContext(browseId).toString().toRequestBody(JSON_MEDIA_TYPE))
                .header("Referer", "https://music.youtube.com/")
                .header("Origin", "https://music.youtube.com")
                .build()
            val body = client.newCall(request).execute().use {
                if (it.isSuccessful) it.body?.string() else null
            } ?: return@withContext null
            val json = JSONObject(body)
            val header = json.optJSONObject("header")
            val renderer = header?.optJSONObject("musicDetailHeaderRenderer")
                ?: header?.optJSONObject("musicResponsiveHeaderRenderer")
                ?: header?.optJSONObject("musicEditablePlaylistDetailHeaderRenderer")
                    ?.optJSONObject("header")?.optJSONObject("musicDetailHeaderRenderer")
                ?: json.optJSONObject("contents")?.optJSONObject("twoColumnBrowseResultsRenderer")
                    ?.optJSONArray("tabs")?.optJSONObject(0)?.optJSONObject("tabRenderer")
                    ?.optJSONObject("content")?.optJSONObject("sectionListRenderer")
                    ?.optJSONArray("contents")?.optJSONObject(0)
                    ?.optJSONObject("musicResponsiveHeaderRenderer")
            val title = renderer?.optJSONObject("title")?.optJSONArray("runs")
                ?.optJSONObject(0)?.optString("text").orEmpty()
            val art = PlaylistThumbnails.extractPlaylistThumbnail(json)
            if (title.isBlank() || art.isNullOrBlank()) return@withContext null
            PlaylistResult(id = browseId, title = title, thumbnail = art)
        } catch (_: Exception) { null }
    }

    /** (total plays, number of tracks with a play count) from the album's page; (0, 0) if unreadable. */
    open suspend fun getAlbumPlays(browseId: String): Pair<Long, Int> = withContext(Dispatchers.IO) {
        if (!browseId.startsWith("MPRE")) return@withContext 0L to 0
        try {
            val payload = JSONObject().apply {
                put("context", JSONObject().put("client", JSONObject().apply {
                    put("clientName", "WEB_REMIX")
                    put("clientVersion", "1.20241028.01.00")
                    put("hl", "en")
                    put("gl", "US")
                }))
                put("browseId", browseId)
            }
            val request = Request.Builder()
                .url("https://music.youtube.com/youtubei/v1/browse?prettyPrint=false")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36")
                .header("Referer", "https://music.youtube.com/")
                .header("Origin", "https://music.youtube.com")
                .build()
            val body = client.searchBody(request, 15_000L)
            val trackPlays = ALBUM_TRACK_PLAYS.findAll(body).map {
                com.auralis.music.domain.search.SearchQueryMatcher.parsePlayCount(it.groupValues[1] + " plays")
            }.toList()
            // A single or short EP is mostly versions of one song (remix, instrumental, sped up):
            // summing those would let a single outvote its own song, so it counts its biggest track.
            (if (trackPlays.size <= 4) trackPlays.maxOrNull() ?: 0L else trackPlays.sum()) to trackPlays.size
        } catch (_: Exception) {
            kotlin.coroutines.coroutineContext.ensureActive()
            0L to 0
        }
    }

    /**
     * "single", "ep" or "album": the label YouTube Music puts on a release page ("Single • KATSEYE
     * • 2025"), or null when it can't be read. Search results only say "album" for most uploads.
     */
    open suspend fun getReleaseType(browseId: String): String? = withContext(Dispatchers.IO) {
        if (!browseId.startsWith("MPRE")) return@withContext null
        releaseTypeCache[browseId]?.let { return@withContext it }
        try {
            val payload = JSONObject().apply {
                put("context", JSONObject().put("client", JSONObject().apply {
                    put("clientName", "WEB_REMIX")
                    put("clientVersion", "1.20241028.01.00")
                    put("hl", "en")
                    put("gl", "US")
                }))
                put("browseId", browseId)
            }
            val request = Request.Builder()
                .url("https://music.youtube.com/youtubei/v1/browse?prettyPrint=false")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .header("Referer", "https://music.youtube.com/")
                .header("Origin", "https://music.youtube.com")
                .build()
            // The page header's subtitle starts with the release type. Other releases' cards on the
            // page carry the same kind of subtitle, so only the header's counts.
            val body = client.searchBody(request, 6_000L)
            val headerStart = body.indexOf("\"musicResponsiveHeaderRenderer\"").takeIf { it >= 0 } ?: return@withContext null
            val type = Regex(""""subtitle":\{"runs":\[\{"text":"(Single|EP|Album)"""").find(body, headerStart)
                ?.groupValues?.get(1)?.lowercase()
            if (type != null) {
                if (releaseTypeCache.size > 500) releaseTypeCache.clear()
                releaseTypeCache[browseId] = type
            }
            type
        } catch (_: Exception) {
            kotlin.coroutines.coroutineContext.ensureActive()
            null
        }
    }

    /**
     * Calls YouTube Music get_queue endpoint
     * to fetch verified authentic track metadata including real album, artist, and duration.
     */
    open suspend fun getQueue(videoIds: List<String>): List<Track> = withContext(Dispatchers.IO) {
        if (videoIds.isEmpty()) return@withContext emptyList()
        try {
            val validIds = videoIds.filter { it.isNotBlank() }.take(50)
            if (validIds.isEmpty()) return@withContext emptyList()

            val requestBody = JSONObject().apply {
                put("context", createClientContext())
                put("videoIds", JSONArray().apply { validIds.forEach { put(it) } })
            }

            val request = Request.Builder()
                .url("$YT_MUSIC_API/music/get_queue?prettyPrint=false")
                .post(requestBody.toString().toRequestBody(JSON_MEDIA_TYPE))
                .header("Referer", "https://music.youtube.com/")
                .header("Origin", "https://music.youtube.com")
                .build()

            val body = client.searchBody(request, 15_000L)
            val json = JSONObject(body)
            val queueDatas = json.optJSONArray("queueDatas") ?: return@withContext emptyList()

            val tracks = mutableListOf<Track>()
            for (i in 0 until queueDatas.length()) {
                val qObj = queueDatas.optJSONObject(i) ?: continue
                val renderer = qObj.optJSONObject("content")?.optJSONObject("playlistPanelVideoRenderer") ?: continue

                val vid = renderer.optString("videoId")
                if (vid.isBlank()) continue

                val title = renderer.optJSONObject("title")?.optJSONArray("runs")?.optJSONObject(0)?.optString("text") ?: ""
                val longRuns = renderer.optJSONObject("longBylineText")?.optJSONArray("runs")

                var artistName = ""
                var albumName: String? = null
                var albumId: String? = null

                if (longRuns != null) {
                    for (r in 0 until longRuns.length()) {
                        val runObj = longRuns.optJSONObject(r) ?: continue
                        val text = runObj.optString("text").trim()
                        if (text.isBlank() || text == "•") continue

                        val nav = runObj.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")
                        val bId = nav?.optString("browseId")
                        val pageType = nav?.optJSONObject("browseEndpointContextSupportedConfigs")
                            ?.optJSONObject("browseEndpointContextMusicConfig")
                            ?.optString("pageType")

                        if (pageType == "MUSIC_PAGE_TYPE_ARTIST" || (bId != null && bId.startsWith("UC"))) {
                            if (artistName.isBlank()) artistName = text
                        } else if (pageType == "MUSIC_PAGE_TYPE_ALBUM" || (bId != null && (bId.startsWith("MPRE") || bId.startsWith("OLAK") || bId.startsWith("FEmusic")))) {
                            albumName = text
                            albumId = bId
                        }
                    }
                }

                var durationSec = 0L
                val lengthText = renderer.optJSONObject("lengthText")?.optJSONArray("runs")?.optJSONObject(0)?.optString("text")
                if (!lengthText.isNullOrBlank()) {
                    durationSec = parseDurationToSeconds(lengthText)
                }

                val thumbnails = renderer.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
                val thumbUrl = getBestThumbnailUrl(thumbnails, vid)

                tracks.add(
                    Track(
                        id = vid,
                        title = TitleCleaner.cleanTitle(title),
                        artist = artistName.ifBlank { "YouTube Artist" },
                        album = albumName,
                        albumId = albumId,
                        duration = durationSec,
                        thumbnail = thumbUrl.ifBlank { "https://i.ytimg.com/vi/$vid/hqdefault.jpg" },
                        source = TrackSource.YOUTUBE
                    )
                )
            }
            tracks
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Resolves authentic album and artist details for a single video ID via get_queue and AlbumMetadataResolver.
     */
    open suspend fun getSongDetails(videoId: String): Track? = withContext(Dispatchers.IO) {
        val qTrack = getQueue(listOf(videoId)).firstOrNull() ?: return@withContext null
        if (AlbumMetadataResolver.needsResolving(qTrack.album, qTrack.title)) {
            val resolved = AlbumMetadataResolver.resolveAlbum(qTrack.title, qTrack.artist)
            if (resolved != null && !resolved.isSingle && resolved.albumTitle.isNotBlank()) {
                return@withContext qTrack.copy(
                    album = resolved.albumTitle,
                    albumId = resolved.albumId ?: qTrack.albumId
                )
            }
        }
        qTrack
    }

    /**
     * Curates a fully diverse, genre-matched random radio queue:
     * - Zero duplicate song titles or live/alternate takes (via TrackDeduplicator)
     * - Maximum 2 songs from the seed artist
     * - Maximum 1-2 songs from any other artist
     * - Shuffles and interleaves artists so no two consecutive songs are by the same artist
     */
    private fun extractFirstSignificantWord(title: String): String {
        val clean = title.lowercase().replace(Regex("""[^a-z0-9\s]"""), " ")
        val words = clean.split(Regex("""\s+""")).filter { it.isNotBlank() }
        val stopWords = setOf("the", "a", "an", "in", "on", "at", "to", "for", "of", "and", "is", "it", "with")
        return words.firstOrNull { it !in stopWords } ?: ""
    }

    fun curateDiverseGenreQueue(
        candidates: List<Track>,
        seedVideoId: String,
        seedArtist: String? = null,
        seedTitle: String? = null
    ): List<Track> {
        if (candidates.isEmpty()) return emptyList()

        val seedBaseTitle = if (!seedTitle.isNullOrBlank()) TrackDeduplicator.extractBaseSongTitle(seedTitle) else ""
        val seedFirstWord = if (!seedTitle.isNullOrBlank()) extractFirstSignificantWord(seedTitle) else ""

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
            // Filter out songs sharing the exact same first significant word as the seed track (e.g. "Middle of the Ocean" vs "Middle of the Night")
            if (seedFirstWord.length >= 4) {
                val candidateFirstWord = extractFirstSignificantWord(track.title)
                if (candidateFirstWord.isNotBlank() && candidateFirstWord == seedFirstWord) {
                    return@filter false
                }
            }
            true
        }

        // 2. Comprehensive Deduplication: Ensure only ONE version of ANY song title exists in the queue,
        // prioritizing authentic studio audio tracks over music video uploads.
        val deduplicated = TrackDeduplicator.deduplicateTracks(validCandidates, matchAlternateVersions = true)

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

        // 5. Interleave & Shuffle Artists (Round-Robin with non-repeating artist & non-repeating first-word constraints)
        val result = mutableListOf<Track>()
        val seenBaseTitles = mutableSetOf<String>()
        val seenFirstWords = mutableSetOf<String>()
        if (seedBaseTitle.isNotBlank()) {
            seenBaseTitles.add(seedBaseTitle)
        }
        if (seedFirstWord.isNotBlank()) {
            seenFirstWords.add(seedFirstWord)
        }
        val activeQueues = cappedArtistQueues.shuffled().toMutableList()
        var lastArtist = ""

        while (activeQueues.isNotEmpty() && result.size < 35) {
            // Find a queue whose next track is:
            // 1. Not by the last artist
            // 2. Unseen base title
            // 3. Unseen first significant word (so no two songs start with the same word)
            val nextQueueIndex = activeQueues.indexOfFirst { queue ->
                val nextTrack = queue.firstOrNull() ?: return@indexOfFirst false
                val base = TrackDeduplicator.extractBaseSongTitle(nextTrack.title)
                if (base.isNotBlank() && base in seenBaseTitles) {
                    return@indexOfFirst false
                }
                val firstWord = extractFirstSignificantWord(nextTrack.title)
                if (firstWord.length >= 3 && firstWord in seenFirstWords) {
                    return@indexOfFirst false
                }
                val nextArtist = nextTrack.artist.lowercase()
                lastArtist.isBlank() || (nextArtist != lastArtist && !nextArtist.contains(lastArtist) && !lastArtist.contains(nextArtist))
            }

            val chosenIndex = if (nextQueueIndex != -1) {
                nextQueueIndex
            } else {
                // Secondary fallback: relaxing strict artist alternation but still enforcing unique first word & unseen title
                activeQueues.indexOfFirst { queue ->
                    val nextTrack = queue.firstOrNull() ?: return@indexOfFirst false
                    val base = TrackDeduplicator.extractBaseSongTitle(nextTrack.title)
                    if (base.isNotBlank() && base in seenBaseTitles) return@indexOfFirst false
                    val firstWord = extractFirstSignificantWord(nextTrack.title)
                    firstWord.length < 3 || firstWord !in seenFirstWords
                }.let { idx ->
                    if (idx != -1) idx else {
                        // Ultimate fallback: any queue with an unseen base title
                        activeQueues.indexOfFirst { queue ->
                            val nextTrack = queue.firstOrNull() ?: return@indexOfFirst false
                            val base = TrackDeduplicator.extractBaseSongTitle(nextTrack.title)
                            base.isBlank() || base !in seenBaseTitles
                        }
                    }
                }
            }

            if (chosenIndex == -1) {
                // All remaining candidate tracks across all queues are duplicates
                break
            }

            val chosenQueue = activeQueues[chosenIndex]
            val track = chosenQueue.removeAt(0)
            val base = TrackDeduplicator.extractBaseSongTitle(track.title)
            val firstWord = extractFirstSignificantWord(track.title)

            if (base.isBlank() || base !in seenBaseTitles) {
                if (base.isNotBlank()) seenBaseTitles.add(base)
                if (firstWord.isNotBlank()) seenFirstWords.add(firstWord)
                result.add(track)
                lastArtist = track.artist.lowercase()
            }

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
            RecordingMatches.getMatchedVideoId(videoId) ?: ""
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
        }

        // Fallback: If radio / related returned very few tracks, supplement with artist songs & basic next
        if (candidatesPool.size < 5 && !primaryArtist.isNullOrBlank()) {
            try {
                val artistSongs = search("$primaryArtist songs", FILTER_SONGS).songs
                candidatesPool.addAll(artistSongs)
            } catch (_: Exception) {}
        }

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

            // 1. Direct playlist panel (legacy / desktop)
            var playlistContents = singleCol?.optJSONObject("playlist")
                ?.optJSONObject("playlistPanelRenderer")
                ?.optJSONArray("contents")

            // 2. Modern tabbedRenderer (Tab 0 Up Next / MusicQueue)
            if (playlistContents == null || playlistContents.length() == 0) {
                val tabs = singleCol?.optJSONObject("tabbedRenderer")
                    ?.optJSONObject("watchNextTabbedResultsRenderer")
                    ?.optJSONArray("tabs")
                if (tabs != null && tabs.length() > 0) {
                    playlistContents = tabs.optJSONObject(0)
                        ?.optJSONObject("tabRenderer")
                        ?.optJSONObject("content")
                        ?.optJSONObject("musicQueueRenderer")
                        ?.optJSONObject("content")
                        ?.optJSONObject("playlistPanelRenderer")
                        ?.optJSONArray("contents")
                }
            }

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
            val title = tabRenderer?.optString("title", "")?.lowercase() ?: ""
            val endpoint = tabRenderer?.optJSONObject("endpoint")?.optJSONObject("browseEndpoint")
            if (endpoint != null) {
                val browseId = endpoint.optString("browseId")
                val params = endpoint.optString("params")
                if (browseId.startsWith("MPTR") || title.contains("related")) {
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
            val requestBody = createBrowseContext("FEmusic_explore", localizedLabels = true)
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
    private enum class ArtistShelfKind { SONGS, RELEASES, ARTISTS, OTHER }

    /** What an artist-page shelf holds, judged from its items' endpoints (language independent). */
    private fun artistShelfKind(shelf: JSONObject): ArtistShelfKind {
        val contents = shelf.optJSONArray("contents") ?: return ArtistShelfKind.OTHER
        val first = contents.optJSONObject(0) ?: return ArtistShelfKind.OTHER
        if (first.has("musicResponsiveListItemRenderer")) return ArtistShelfKind.SONGS
        val twoRow = first.optJSONObject("musicTwoRowItemRenderer") ?: return ArtistShelfKind.OTHER
        val pageType = twoRow.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")
            ?.optJSONObject("browseEndpointContextSupportedConfigs")
            ?.optJSONObject("browseEndpointContextMusicConfig")
            ?.optString("pageType")
        return when (pageType) {
            "MUSIC_PAGE_TYPE_ALBUM" -> ArtistShelfKind.RELEASES
            "MUSIC_PAGE_TYPE_ARTIST" -> ArtistShelfKind.ARTISTS
            else -> ArtistShelfKind.OTHER
        }
    }

    /** "33.6M monthly audience" / "66 subscribers" -> the number, for telling same-named channels apart. */
    private fun followerCount(text: String?): Long = com.auralis.music.domain.search.SearchQueryMatcher.parsePlayCount(
        text?.let { Regex("""[\d.,]+\s*[KkMmBb]?""").find(it)?.value?.replace(" ", "") })

    suspend fun getArtistPage(artist: Artist): ArtistPage? = withContext(Dispatchers.IO) {
        var resolvedArtist = artist
        try {
            var effectiveChannelId = artist.id

            // If channelId is not a real YouTube channel ID, search to resolve it
            if (!effectiveChannelId.startsWith("UC")) {
                val matcher = com.auralis.music.domain.search.SearchQueryMatcher
                // "Juice WRLD" also matches "BABY juice WRLD" (66 subscribers) by containment, and
                // the artist filter can list such an account first: a channel named exactly like the
                // artist wins, and among equal names the most subscribed one is the official artist.
                fun pick(candidates: List<Artist>): Artist? {
                    val channels = candidates.filter { it.id.startsWith("UC") }
                    val exact = channels.filter { matcher.normalize(it.name) == matcher.normalize(artist.name) }
                    return exact.maxByOrNull { followerCount(it.subscribers) }
                        ?: channels.filter { matcher.isAuthorMatch(it.name, artist.name) }
                            .maxByOrNull { followerCount(it.subscribers) }
                }
                val filtered = search(artist.name, FILTER_ARTISTS).artists
                val exactInFilter = filtered.any { it.id.startsWith("UC") && matcher.normalize(it.name) == matcher.normalize(artist.name) }
                val match = if (exactInFilter) pick(filtered) else pick(filtered + search(artist.name).artists)
                if (match != null) {
                    effectiveChannelId = match.id
                    resolvedArtist = match
                }
                if (!effectiveChannelId.startsWith("UC")) throw java.io.IOException("Artist identity not resolved")
            }

            val requestBody = createBrowseContext(effectiveChannelId)
            val request = Request.Builder()
                .url("$YT_MUSIC_API/browse?prettyPrint=false")
                .post(requestBody.toString().toRequestBody(JSON_MEDIA_TYPE))
                .header("Referer", "https://music.youtube.com/")
                .header("Origin", "https://music.youtube.com")
                .build()

            val body = client.searchBody(request, 6_000L)
            val json = JSONObject(body)

            val header = json.optJSONObject("header")?.optJSONObject("musicImmersiveHeaderRenderer")
                ?: json.optJSONObject("header")?.optJSONObject("musicVisualHeaderRenderer")
                ?: json.optJSONObject("header")?.optJSONObject("musicHeaderRenderer")
                ?: json.optJSONObject("header")?.optJSONObject("musicResponsiveHeaderRenderer")
                ?: artistSections(json).let { sections ->
                    (0 until sections.length()).firstNotNullOfOrNull { sections.optJSONObject(it)?.optJSONObject("musicResponsiveHeaderRenderer") }
                }

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
            // e.g. "25.1M monthly audience" (immersive header only).
            val monthlyAudience = header?.optJSONObject("monthlyListenerCount")?.optJSONArray("runs")
                ?.optJSONObject(0)?.optString("text")?.takeIf { it.isNotBlank() }
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
            var bannerUrl = getBestThumbnailUrl(bannerThumbs, null).ifBlank { resolvedArtist.thumbnail }

            // If banner is missing or the known all-black Donda square, resolve HD portrait via Wikipedia
            if (bannerUrl.isNullOrBlank() ||
                bannerUrl.contains("IFlc3sf6sHV3TAZ_5vhyHQiKb9D4AdSlDkiTSgsRiicnzLASXwVr1n22EEg6Vtd2XBlyJslm8xlYiA") ||
                artistName.equals("Kanye West", ignoreCase = true) ||
                artistName.equals("Ye", ignoreCase = true)
            ) {
                val wikiPortrait = kotlinx.coroutines.withTimeoutOrNull(800L) { fetchWikipediaArtistPortrait(artistName) }
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

            val sectionList = artistSections(json)

            var albumShelvesSeen = 0
            for (i in 0 until sectionList.length()) {
                val secObj = sectionList.optJSONObject(i) ?: continue
                val shelf = secObj.optJSONObject("musicShelfRenderer")
                    ?: secObj.optJSONObject("musicCarouselShelfRenderer") ?: continue

                val shelfHeader = shelf.optJSONObject("header")?.optJSONObject("musicShelfBasicHeaderRenderer")
                    ?: shelf.optJSONObject("header")?.optJSONObject("musicCarouselShelfBasicHeaderRenderer")
                val rawShelfTitle = shelfHeader?.optJSONObject("title")?.optJSONArray("runs")?.optJSONObject(0)?.optString("text")?.lowercase()
                    ?: "songs"
                // English titles name the shelf directly. Localized ones (content language) don't
                // match those words, so name it from what it holds instead; see artistShelfKind.
                val englishTitle = listOf("song", "album", "single", "ep", "fan", "like", "similar").any { rawShelfTitle.contains(it) }
                val shelfTitle = if (englishTitle) rawShelfTitle else when (artistShelfKind(shelf)) {
                    ArtistShelfKind.SONGS -> "songs"
                    ArtistShelfKind.ARTISTS -> "fans might also like"
                    ArtistShelfKind.RELEASES -> if (albumShelvesSeen++ == 0) "albums" else "singles"
                    ArtistShelfKind.OTHER -> rawShelfTitle
                }

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
                }
            }

            val finalArtist = resolvedArtist.copy(
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
                monthlyAudience = monthlyAudience,
                topSongs = resolvedTopSongs.distinctBy { it.id },
                albums = albums.distinctBy { it.id },
                singles = singles.distinctBy { it.id },
                similarArtists = similarArtists.distinctBy { it.id },
                radioPlaylistId = radioPlaylistId
            )
        } catch (e: Exception) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val songs = try { search(artist.name, FILTER_SONGS).songs.filter {
                com.auralis.music.domain.search.SearchQueryMatcher.isAuthorMatch(it.artist, artist.name)
            } } catch (_: Exception) { kotlinx.coroutines.currentCoroutineContext().ensureActive(); emptyList() }
            if (songs.isNotEmpty()) {
                ArtistPage(
                    artist = resolvedArtist,
                    bannerUrl = resolvedArtist.thumbnail,
                    topSongs = songs
                )
            } else null
        }
    }

    internal fun artistSections(root: JSONObject): JSONArray {
        val contents = root.optJSONObject("contents")
        val browse = contents?.optJSONObject("singleColumnBrowseResultsRenderer")
            ?: contents?.optJSONObject("twoColumnBrowseResultsRenderer")
        val primary = browse?.optJSONArray("tabs")?.optJSONObject(0)?.optJSONObject("tabRenderer")
            ?.optJSONObject("content")?.optJSONObject("sectionListRenderer")?.optJSONArray("contents")
        val secondary = browse?.optJSONObject("secondaryContents")?.optJSONObject("sectionListRenderer")
            ?.optJSONArray("contents")
        return JSONArray().apply {
            listOfNotNull(primary, secondary).forEach { sections ->
                for (i in 0 until sections.length()) put(sections.optJSONObject(i))
            }
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
            var linkedArtist: String? = null
            for (k in 0 until subtitleRuns.length()) {
                val run = subtitleRuns.optJSONObject(k) ?: continue
                val rText = run.optString("text").trim()
                if (rText != "•" && rText.isNotBlank()) {
                    names.add(rText)
                }
                val pageType = run.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")
                    ?.optJSONObject("browseEndpointContextSupportedConfigs")
                    ?.optJSONObject("browseEndpointContextMusicConfig")
                    ?.optString("pageType")
                if (linkedArtist == null && pageType == "MUSIC_PAGE_TYPE_ARTIST") linkedArtist = rText
            }
            // Prefer the run that links to an artist: the first plain run is often the item type
            // ("Song", "Video", or its translation when the home feed is localized).
            if (linkedArtist != null) {
                artistName = linkedArtist
                albumName = names.getOrNull(names.indexOf(linkedArtist) + 1)
            } else {
                if (names.isNotEmpty()) artistName = names[0]
                if (names.size > 1) albumName = names[1]
            }
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
                    val cardContents = cardShelf.optJSONArray("contents") ?: JSONArray()
                    for (j in 0 until cardContents.length()) {
                        cardContents.optJSONObject(j)?.optJSONObject("musicResponsiveListItemRenderer")?.let {
                            parseMusicListItem(it, songs, artists, albums, playlists)
                        }
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
                // Current general search wraps each result in itemSectionRenderer rather than
                // musicShelfRenderer. Skipping it leaves only the top music-video card.
                val items = section?.optJSONObject("itemSectionRenderer")?.optJSONArray("contents")
                if (items != null) {
                    for (j in 0 until items.length()) {
                        items.optJSONObject(j)?.optJSONObject("musicResponsiveListItemRenderer")?.let {
                            parseMusicListItem(it, songs, artists, albums, playlists)
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
        val onTapNav = onTap?.optJSONObject("browseEndpoint")
        val onTapBrowseId = onTapNav?.optString("browseId")
        val onTapPageType = onTapNav?.optJSONObject("browseEndpointContextSupportedConfigs")
            ?.optJSONObject("browseEndpointContextMusicConfig")
            ?.optString("pageType")

        val browseId = onTapBrowseId
        val videoId = onTap?.optJSONObject("watchEndpoint")?.optString("videoId")
            ?: card.optJSONArray("buttons")?.optJSONObject(0)?.optJSONObject("buttonRenderer")?.optJSONObject("command")?.optJSONObject("watchEndpoint")?.optString("videoId")

        val isRadio = title.startsWith("Radio •", ignoreCase = true) ||
            title.endsWith(" Radio", ignoreCase = true) ||
            (browseId != null && browseId.startsWith("VLRD"))

        if (isRadio) {
            return null
        }

        if (onTapPageType == "MUSIC_PAGE_TYPE_ARTIST" || cardType.contains("artist") || (browseId != null && browseId.startsWith("UC") && videoId.isNullOrBlank())) {
            val artist = Artist(id = browseId ?: "yt:$title", name = title, thumbnail = thumbUrl.ifBlank { null }, query = "$title top songs")
            if (artists.none { it.id == browseId || it.name.equals(title, ignoreCase = true) }) {
                artists.add(0, artist)
            }
            return SearchTopResult.ArtistResult(artist)
        } else if (onTapPageType == "MUSIC_PAGE_TYPE_ALBUM" || cardType.contains("album") || cardType.contains("ep") || cardType.contains("single") || (browseId != null && (browseId.startsWith("MPRE") || browseId.startsWith("OLAK")) && videoId.isNullOrBlank())) {
            val author = if (subParts.size > 1) subParts[1] else null
            val album = PlaylistResult(id = browseId ?: "pl:$title:${author.orEmpty()}", title = title, thumbnail = thumbUrl.ifBlank { null }, author = author,
                releaseType = cardType.takeIf { it == "album" || it == "single" || it == "ep" })
            if (albums.none { it.id == album.id }) {
                albums.add(0, album)
            }
            return SearchTopResult.AlbumResult(album)
        } else if (onTapPageType == "MUSIC_PAGE_TYPE_PLAYLIST" || cardType.contains("playlist") || (browseId != null && (browseId.startsWith("VL") || browseId.startsWith("PL")) && videoId.isNullOrBlank())) {
            val author = if (subParts.size > 1) subParts[1] else null
            val pl = PlaylistResult(id = browseId ?: "pl:$title", title = title, thumbnail = thumbUrl.ifBlank { null }, author = author)
            if (playlists.none { it.id == browseId || it.title.equals(title, ignoreCase = true) }) {
                playlists.add(0, pl)
            }
            // Do not return a playlist/radio as an AlbumResult
            return null
        } else if (!videoId.isNullOrBlank()) {
            val artist = if (subParts.size > 1) subParts[1] else "YouTube Artist"
            var duration = 200L
            val durStr = subParts.find { it.matches(Regex("""\d+:\d+(:\d+)?""")) }
            if (durStr != null) {
                duration = parseDurationToSeconds(durStr)
            }
            var cardAlbumName: String? = null
            var cardAlbumId: String? = null
            if (subtitleRuns != null) {
                for (r in 0 until subtitleRuns.length()) {
                    val runObj = subtitleRuns.optJSONObject(r)
                    val runNav = runObj?.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")
                    val runBrowseId = runNav?.optString("browseId")
                    val runPageType = runNav?.optJSONObject("browseEndpointContextSupportedConfigs")
                        ?.optJSONObject("browseEndpointContextMusicConfig")
                        ?.optString("pageType")
                    if (runPageType == "MUSIC_PAGE_TYPE_ALBUM" || (runBrowseId != null && (runBrowseId.startsWith("MPRE") || runBrowseId.startsWith("FEmusic") || runBrowseId.startsWith("OLAK")))) {
                        cardAlbumId = runBrowseId
                        cardAlbumName = runObj.optString("text")
                    }
                }
            }
            val viewsStr = subParts.find {
                val lower = it.lowercase()
                lower.contains("play") || lower.contains("view") || lower.contains("listener")
            }
            val track = Track(
                id = videoId,
                title = TitleCleaner.cleanTitle(title),
                artist = artist,
                album = cardAlbumName,
                albumId = cardAlbumId,
                duration = duration,
                thumbnail = thumbUrl.ifBlank { "https://i.ytimg.com/vi/$videoId/hqdefault.jpg" },
                views = viewsStr,
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
        val navBrowse = navEndpoint?.optJSONObject("browseEndpoint")
        var browseId = navBrowse?.optString("browseId")
            ?: itemNav?.optJSONObject("browseEndpoint")?.optString("browseId")
        val itemPageType = navBrowse?.optJSONObject("browseEndpointContextSupportedConfigs")
            ?.optJSONObject("browseEndpointContextMusicConfig")
            ?.optString("pageType")

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
        var albumIdStr: String? = null
        var durationSec: Long = 0
        var viewsStr: String? = null
        var itemType = ""

        val typeKeywords = setOf("song", "video", "artist", "album", "single", "ep", "playlist", "episode", "podcast")

        if (col1Runs != null) {
            // The subtitle is "•"-separated sections, e.g.
            //   [Song •] Lijo George-Dj Chetas, Darshan Raval & Asees Kaur • Loveyatri • 3:41 • 1.3B plays
            // where one section can hold several runs (artist links plus ", " / " & " glue).
            // Reading run-by-run kept only the first plain artist (or the last linked one) and
            // mistook the ", " / " & " glue for the album. Read whole sections instead.
            data class Section(val text: String, val artistLink: Boolean, val albumId: String?)
            val sections = mutableListOf<Section>()
            val buf = StringBuilder()
            var hasArtistLink = false
            var sectionAlbumId: String? = null
            fun endSection() {
                val t = buf.toString().trim()
                if (t.isNotBlank()) sections.add(Section(t, hasArtistLink, sectionAlbumId))
                buf.setLength(0)
                hasArtistLink = false
                sectionAlbumId = null
            }
            for (r in 0 until col1Runs.length()) {
                val runObj = col1Runs.optJSONObject(r) ?: continue
                val raw = runObj.optString("text")
                if (raw.trim() == "•") {
                    endSection()
                    continue
                }
                val nav = runObj.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")
                val runBrowseId = nav?.optString("browseId")
                val pageType = nav?.optJSONObject("browseEndpointContextSupportedConfigs")
                    ?.optJSONObject("browseEndpointContextMusicConfig")
                    ?.optString("pageType")
                if (pageType == "MUSIC_PAGE_TYPE_ARTIST" || (runBrowseId != null && runBrowseId.startsWith("UC"))) {
                    hasArtistLink = true
                } else if (pageType == "MUSIC_PAGE_TYPE_ALBUM" || (runBrowseId != null && (runBrowseId.startsWith("MPRE") || runBrowseId.startsWith("FEmusic") || runBrowseId.startsWith("OLAK")))) {
                    sectionAlbumId = runBrowseId
                }
                buf.append(raw)
            }
            endSection()

            // Language-independent fallbacks, for localized responses (Settings → Content →
            // content language) where "Song" / "1.3B plays" arrive as e.g. "गाना" / "1.3 अ॰ बार चलाया गया":
            //  - the item-type label is the leading unlinked section followed by an artist link;
            //  - the count is the last unlinked section with digits that isn't a duration or a year.
            val labelIndex = if (sections.size >= 2 && !sections[0].artistLink && sections[0].albumId == null &&
                sections[1].artistLink) 0 else -1
            val countIndex = sections.indices.lastOrNull { i ->
                val sec = sections[i]
                i != labelIndex && !sec.artistLink && sec.albumId == null && sec.text.any(Char::isDigit) &&
                    !sec.text.matches(Regex("""\d+:\d+(:\d+)?""")) && !sec.text.matches(Regex("""\d{4}"""))
            } ?: -1

            for ((index, section) in sections.withIndex()) {
                val text = section.text
                val lowerText = text.lowercase()
                when {
                    index == labelIndex -> itemType = if (typeKeywords.contains(lowerText)) lowerText else "label"
                    section.albumId != null -> {
                        albumName = text
                        albumIdStr = section.albumId
                    }
                    section.artistLink -> {
                        // First artist section wins; it already contains every credited artist.
                        if (artistName == "Unknown Artist") artistName = text
                    }
                    text.matches(Regex("""\d+:\d+(:\d+)?""")) -> durationSec = parseDurationToSeconds(text)
                    lowerText.contains("play") || lowerText.contains("view") || lowerText.contains("listener") || lowerText.contains("subscriber") || lowerText.contains("audience") -> viewsStr = text
                    typeKeywords.contains(lowerText) -> itemType = lowerText
                    index == countIndex && artistName != "Unknown Artist" -> viewsStr = text
                    artistName == "Unknown Artist" -> artistName = text
                    albumName == null -> albumName = text
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

                val nav = runObj.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")
                val runBrowseId = nav?.optString("browseId")
                val pageType = nav?.optJSONObject("browseEndpointContextSupportedConfigs")
                    ?.optJSONObject("browseEndpointContextMusicConfig")
                    ?.optString("pageType")

                if (pageType == "MUSIC_PAGE_TYPE_ALBUM" || (runBrowseId != null && (runBrowseId.startsWith("MPRE") || runBrowseId.startsWith("FEmusic") || runBrowseId.startsWith("OLAK")))) {
                    if (albumName == null) albumName = text
                    if (albumIdStr == null) albumIdStr = runBrowseId
                } else if (text.matches(Regex("""\d+:\d+(:\d+)?"""))) {
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

        val isAlbum = itemPageType == "MUSIC_PAGE_TYPE_ALBUM" || itemType.contains("album") || itemType.contains("ep") || itemType.contains("single") || (browseId != null && browseId.startsWith("MPRE"))
        val isPlaylist = itemPageType == "MUSIC_PAGE_TYPE_PLAYLIST" || itemType.contains("playlist") || (browseId != null && (browseId.startsWith("VL") || browseId.startsWith("PL")))

        val cleanArtist = if (artistName.equals("Song", ignoreCase = true) || artistName.equals("Video", ignoreCase = true) || artistName.equals("Unknown Artist", ignoreCase = true)) {
            if (!albumName.isNullOrBlank() && !typeKeywords.contains(albumName.lowercase())) {
                val temp = albumName
                albumName = null
                temp
            } else "YouTube Artist"
        } else artistName

        val cleanAlbum = if (
            albumName?.contains("play", ignoreCase = true) == true ||
            albumName?.contains("view", ignoreCase = true) == true ||
            albumName?.contains("listener", ignoreCase = true) == true ||
            albumName?.contains("subscriber", ignoreCase = true) == true ||
            albumName?.matches(Regex("""\d+:\d+(:\d+)?""")) == true
        ) null else albumName

        if (itemPageType == "MUSIC_PAGE_TYPE_ARTIST" || itemType.contains("artist") || (browseId != null && browseId.startsWith("UC") && videoId.isNullOrBlank())) {
            val candidate = Artist(
                id = browseId ?: "yt:$title",
                name = title,
                thumbnail = thumbUrl.ifBlank { null },
                subscribers = viewsStr,
                query = "$title top songs"
            )
            val sameName = artists.indexOfFirst { it.id == browseId || it.name.equals(title, ignoreCase = true) }
            if (sameName < 0) {
                artists.add(candidate)
            } else if (artists[sameName].id != browseId && followerCount(viewsStr) > followerCount(artists[sameName].subscribers)) {
                // Two channels with one name: keep the one more people follow (the official artist).
                artists[sameName] = candidate
            }
        } else if (isAlbum && videoId.isNullOrBlank()) {
            val albumId = browseId ?: "pl:$title:$cleanArtist"
            if (albums.none { it.id == albumId }) {
                albums.add(
                    PlaylistResult(
                        id = albumId,
                        title = title,
                        thumbnail = thumbUrl.ifBlank { null },
                        author = cleanArtist,
                        releaseType = itemType.takeIf { it == "album" || it == "single" || it == "ep" }
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
            // Podcast episodes ("Episode • Sep 17 • Show") are not songs.
            if (itemType == "episode" || itemType == "podcast") return
            if (songs.none { it.id == videoId }) {
                songs.add(
                    Track(
                        id = videoId,
                        title = TitleCleaner.cleanTitle(title),
                        artist = cleanArtist,
                        album = cleanAlbum,
                        albumId = albumIdStr,
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

    private suspend fun createBrowseContext(browseId: String, params: String? = null, localizedLabels: Boolean = false): JSONObject {
        return JSONObject().apply {
            put("browseId", browseId)
            if (!params.isNullOrBlank()) put("params", params)
            put("context", createClientContext(localizedLabels))
        }
    }

    private suspend fun createContinuationContext(continuation: String, localizedLabels: Boolean = false): JSONObject {
        return JSONObject().apply {
            put("continuation", continuation)
            put("context", createClientContext(localizedLabels))
        }
    }

    private suspend fun createWebRemixContext(query: String, params: String? = null): JSONObject {
        return JSONObject().apply {
            put("query", query)
            if (!params.isNullOrBlank()) put("params", params)
            put("context", createClientContext().apply {
                getJSONObject("client").put("hl", "en").put("gl", "US")
            })
        }
    }

    /**
     * Country (`gl`) always follows Settings → Content. The language (`hl`) does for home and
     * explore ([localizedLabels]) and for anything run inside [LocalizedContent] (search screen,
     * artist pages, radio); Auralis's own matching lookups stay English, see [LocalizedContent].
     */
    private suspend fun createClientContext(localizedLabels: Boolean = false): JSONObject {
        val hl = if (localizedLabels || LocalizedContent.isActive()) ContentLocale.hl() else "en"
        return JSONObject().apply {
            put("client", JSONObject().apply {
                put("clientName", "WEB_REMIX")
                put("clientVersion", "1.20241201.01.00")
                put("hl", hl)
                put("gl", ContentLocale.gl())
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
            val body = client.searchBody(request, 1_500L)
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
            run {
                val sBody = client.searchBody(searchReq, 1_500L)
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
