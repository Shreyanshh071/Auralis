package com.auralis.music.data.network

import android.util.Log
import com.auralis.music.domain.model.Playlist
import com.auralis.music.domain.model.Track
import com.auralis.music.domain.model.TrackSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.regex.Pattern

enum class SpotifyItemType {
    PLAYLIST,
    ALBUM,
    TRACK,
    UNKNOWN
}

data class SpotifyResource(
    val id: String,
    val type: SpotifyItemType
)

data class SpotifyAccessToken(
    val token: String,
    val expiresAtEpochMs: Long
) {
    val isValid: Boolean
        get() = token.isNotBlank() && System.currentTimeMillis() < (expiresAtEpochMs - 60_000L)
}

class SpotifyPlaylistImporter(
    private val innerTubeClient: InnerTubeClient = InnerTubeClient(),
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
) {
    companion object {
        private const val TAG = "SpotifyImporter"
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
        private const val MAX_PAGE_LIMIT = 100 // Maximum 100 pages * 100 tracks = 10,000 tracks safety cap

        @Volatile
        private var cachedToken: SpotifyAccessToken? = null

        /**
         * Extracts Spotify Resource ID and Type (PLAYLIST, ALBUM, TRACK) from any Spotify link, URI, or raw ID.
         */
        fun extractResource(input: String): SpotifyResource? {
            val trimmed = input.trim()
                .replace("\u200B", "")
                .replace("\uFEFF", "")
                .replace("\"", "")
                .replace("'", "")
            if (trimmed.isBlank()) return null

            // 1. Generic match: playlist/xyz, album/xyz, track/xyz, or playlist:xyz
            val genericMatch = Regex("""(playlist|album|track)[/:]([a-zA-Z0-9_-]+)""", RegexOption.IGNORE_CASE).find(trimmed)
            if (genericMatch != null) {
                val typeStr = genericMatch.groupValues[1].lowercase()
                val id = genericMatch.groupValues[2].substringBefore("?").substringBefore("&").substringBefore("#")
                if (id.isNotBlank()) {
                    val type = when (typeStr) {
                        "playlist" -> SpotifyItemType.PLAYLIST
                        "album" -> SpotifyItemType.ALBUM
                        "track" -> SpotifyItemType.TRACK
                        else -> SpotifyItemType.PLAYLIST
                    }
                    Log.d(TAG, "extractResource generic match: type=$type, id=$id from input='$trimmed'")
                    return SpotifyResource(id, type)
                }
            }

            // 2. Full HTTP/HTTPS URL with optional locale or user segments
            val urlPattern = Regex("""(?:https?://)?(?:open\.)?spotify\.com/(?:intl-[a-zA-Z-]+/)?(?:user/[^/]+/)?(?:embed/)?(playlist|album|track)/([a-zA-Z0-9_-]+)""", RegexOption.IGNORE_CASE)
            val urlMatch = urlPattern.find(trimmed)
            if (urlMatch != null) {
                val typeStr = urlMatch.groupValues[1].lowercase()
                val id = urlMatch.groupValues[2].substringBefore("?").substringBefore("&").substringBefore("#")
                val type = when (typeStr) {
                    "playlist" -> SpotifyItemType.PLAYLIST
                    "album" -> SpotifyItemType.ALBUM
                    "track" -> SpotifyItemType.TRACK
                    else -> SpotifyItemType.PLAYLIST
                }
                Log.d(TAG, "extractResource URL match: type=$type, id=$id")
                return SpotifyResource(id, type)
            }

            // 3. Raw 10-35 character alphanumeric ID
            if (trimmed.matches(Regex("^[a-zA-Z0-9_-]{10,35}$"))) {
                Log.d(TAG, "extractResource raw ID match: id=$trimmed")
                return SpotifyResource(trimmed, SpotifyItemType.PLAYLIST)
            }

            return null
        }

        private fun base64Encode(input: String): String {
            return try {
                java.util.Base64.getEncoder().encodeToString(input.toByteArray(Charsets.UTF_8))
            } catch (_: Throwable) {
                try {
                    android.util.Base64.encodeToString(input.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
                } catch (_: Throwable) {
                    ""
                }
            }
        }
    }

    /**
     * Resolves short links (like spotify.link, spoti.fi) and extracts the SpotifyResource.
     */
    suspend fun resolveResource(input: String): SpotifyResource? = withContext(Dispatchers.IO) {
        extractResource(input)?.let { return@withContext it }

        val trimmed = input.trim()
        if (trimmed.contains("spotify.link") || trimmed.contains("spoti.fi") || trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            try {
                val fullUrl = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "https://$trimmed"
                Log.d(TAG, "Resolving redirect for URL: $fullUrl")
                val req = Request.Builder()
                    .url(fullUrl)
                    .header("User-Agent", USER_AGENT)
                    .build()
                val resp = client.newCall(req).execute()
                val finalUrl = resp.request.url.toString()
                Log.d(TAG, "Redirect resolved to: $finalUrl")
                extractResource(finalUrl)?.let { return@withContext it }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to resolve redirect: ${e.message}")
            }
        }
        null
    }

    /**
     * Retrieves or refreshes a valid Spotify Bearer Access Token.
     * Tries Developer Client Credentials first, then falls back to Web Player anonymous token.
     */
    suspend fun getAccessToken(clientId: String? = null, clientSecret: String? = null): String? = withContext(Dispatchers.IO) {
        cachedToken?.let {
            if (it.isValid) return@withContext it.token
        }

        // 1. Try Spotify Developer Client Credentials flow
        val effectiveClientId = clientId?.trim()?.ifBlank { null }
            ?: runCatching { com.auralis.music.BuildConfig.SPOTIFY_CLIENT_ID.trim().ifBlank { null } }.getOrNull()
        val effectiveClientSecret = clientSecret?.trim()?.ifBlank { null }
            ?: runCatching { com.auralis.music.BuildConfig.SPOTIFY_CLIENT_SECRET.trim().ifBlank { null } }.getOrNull()

        if (!effectiveClientId.isNullOrBlank() && !effectiveClientSecret.isNullOrBlank()) {
            try {
                Log.d(TAG, "Requesting token via Spotify Client Credentials flow")
                val basicAuth = base64Encode("$effectiveClientId:$effectiveClientSecret")
                val requestBody = FormBody.Builder()
                    .add("grant_type", "client_credentials")
                    .build()

                val request = Request.Builder()
                    .url("https://accounts.spotify.com/api/token")
                    .header("Authorization", "Basic $basicAuth")
                    .header("User-Agent", USER_AGENT)
                    .post(requestBody)
                    .build()

                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: ""
                    val json = JSONObject(body)
                    val token = json.optString("access_token")
                    val expiresIn = json.optLong("expires_in", 3600L)
                    if (token.isNotBlank()) {
                        val expiresAt = System.currentTimeMillis() + (expiresIn * 1000L)
                        cachedToken = SpotifyAccessToken(token, expiresAt)
                        Log.i(TAG, "Successfully acquired Spotify token via Client Credentials (expires in ${expiresIn}s)")
                        return@withContext token
                    }
                } else {
                    Log.w(TAG, "Client Credentials token request failed with HTTP ${response.code}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error acquiring token via Client Credentials: ${e.message}")
            }
        }

        // 2. Fallback: Spotify Web Player anonymous token generator
        try {
            Log.d(TAG, "Requesting token via Spotify Web Player anonymous endpoint")
            val request = Request.Builder()
                .url("https://open.spotify.com/get_access_token?reason=transport&productType=web_player")
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string() ?: ""
                val json = JSONObject(body)
                val token = json.optString("accessToken")
                val expiresAtEpochMs = json.optLong("accessTokenExpirationTimestampMs", 0L)
                if (token.isNotBlank()) {
                    val expiresAt = if (expiresAtEpochMs > System.currentTimeMillis()) expiresAtEpochMs else (System.currentTimeMillis() + 3600_000L)
                    cachedToken = SpotifyAccessToken(token, expiresAt)
                    Log.i(TAG, "Successfully acquired Spotify token via Web Player anonymous endpoint")
                    return@withContext token
                }
            } else {
                Log.w(TAG, "Web Player anonymous token request returned HTTP ${response.code}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error acquiring Web Player anonymous token: ${e.message}")
        }

        null
    }

    /**
     * Imports a Spotify playlist, album, or track with full pagination (no 100-song limit).
     */
    suspend fun importPlaylist(
        urlOrId: String,
        clientId: String? = null,
        clientSecret: String? = null,
        onProgress: ((String) -> Unit)? = null
    ): Playlist? = withContext(Dispatchers.IO) {
        Log.i(TAG, "Starting import for input: '$urlOrId'")
        val resource = resolveResource(urlOrId)
        if (resource == null) {
            Log.e(TAG, "Could not resolve resource from input: '$urlOrId'")
            return@withContext null
        }
        val (id, type) = resource
        Log.i(TAG, "Resolved Spotify resource: id=$id, type=$type")

        // ══════════════════════════════════════════════════════════════════════
        // ── TIER 1: OFFICIAL SPOTIFY WEB API WITH PAGINATION (NO SONG LIMIT) ──
        // ══════════════════════════════════════════════════════════════════════
        val token = getAccessToken(clientId, clientSecret)
        if (!token.isNullOrBlank()) {
            try {
                val apiPlaylist = when (type) {
                    SpotifyItemType.PLAYLIST -> fetchPlaylistFromApi(id, token, onProgress)
                    SpotifyItemType.ALBUM -> fetchAlbumFromApi(id, token, onProgress)
                    SpotifyItemType.TRACK -> fetchTrackFromApi(id, token)
                    else -> fetchPlaylistFromApi(id, token, onProgress)
                }

                if (apiPlaylist != null && (apiPlaylist.tracks.isNotEmpty() || apiPlaylist.title.isNotBlank())) {
                    Log.i(TAG, "Spotify Web API fetched ${apiPlaylist.tracks.size} tracks for '${apiPlaylist.title}'. Enriching with YouTube data...")
                    val enrichedTracks = enrichTracksWithYouTubeData(apiPlaylist.tracks, onProgress)
                    return@withContext apiPlaylist.copy(tracks = enrichedTracks)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Spotify Web API Tier failed: ${e.message}. Falling back to Embed/oEmbed tiers...")
            }
        }

        // ══════════════════════════════════════════════════════════════════════
        // ── TIER 2: SPOTIFY EMBED HTML SCRAPING (FALLBACK) ──────────────────
        // ══════════════════════════════════════════════════════════════════════
        val endpointType = when (type) {
            SpotifyItemType.ALBUM -> "album"
            SpotifyItemType.TRACK -> "track"
            else -> "playlist"
        }

        val urlsToTry = listOf(
            "https://open.spotify.com/embed/$endpointType/$id",
            "https://open.spotify.com/embed/$endpointType/$id?utm_source=oembed",
            "https://open.spotify.com/$endpointType/$id"
        )

        var was404OrPrivate = false

        for (url in urlsToTry) {
            try {
                Log.d(TAG, "Fetching Spotify Embed URL: $url")
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .build()

                val response = client.newCall(request).execute()
                if (response.code == 404) {
                    was404OrPrivate = true
                }
                if (response.isSuccessful) {
                    val html = response.body?.string() ?: ""
                    if (html.contains("Page not found") || html.contains("\"status\":404")) {
                        was404OrPrivate = true
                    }
                    val parsed = parseEmbedHtml(html, id, type)
                    if (parsed != null && parsed.tracks.isNotEmpty()) {
                        Log.i(TAG, "Successfully parsed ${parsed.tracks.size} tracks from Embed HTML. Enriching...")
                        val enrichedTracks = enrichTracksWithYouTubeData(parsed.tracks, onProgress)
                        return@withContext parsed.copy(tracks = enrichedTracks)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error fetching Embed HTML $url: ${e.message}")
            }
        }

        // ══════════════════════════════════════════════════════════════════════
        // ── TIER 3: SPOTIFY OEMBED METADATA (FALLBACK) ──────────────────────
        // ══════════════════════════════════════════════════════════════════════
        try {
            val encodedTarget = URLEncoder.encode("https://open.spotify.com/$endpointType/$id", "UTF-8")
            val oembedUrl = "https://open.spotify.com/oembed?url=$encodedTarget"
            val request = Request.Builder()
                .url(oembedUrl)
                .header("User-Agent", USER_AGENT)
                .build()

            val response = client.newCall(request).execute()
            if (response.code == 404) {
                was404OrPrivate = true
            }
            if (response.isSuccessful) {
                val body = response.body?.string() ?: ""
                val json = JSONObject(body)
                val title = json.optString("title", "Spotify Playlist")
                val coverUrl = json.optString("thumbnail_url").ifBlank { null }
                val author = json.optString("author_name", "Spotify")
                val iframeUrl = json.optString("iframe_url")

                if (iframeUrl.isNotBlank()) {
                    try {
                        val ifReq = Request.Builder()
                            .url(iframeUrl)
                            .header("User-Agent", USER_AGENT)
                            .build()
                        val ifResp = client.newCall(ifReq).execute()
                        if (ifResp.isSuccessful) {
                            val ifHtml = ifResp.body?.string() ?: ""
                            val parsed = parseEmbedHtml(ifHtml, id, type)
                            if (parsed != null && parsed.tracks.isNotEmpty()) {
                                val enrichedTracks = enrichTracksWithYouTubeData(parsed.tracks, onProgress)
                                return@withContext parsed.copy(tracks = enrichedTracks)
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Error fetching oEmbed iframe: ${e.message}")
                    }
                }

                if (title.isNotBlank()) {
                    return@withContext Playlist(
                        id = "sp_$id",
                        title = title,
                        description = "Imported from Spotify by $author",
                        coverUrl = coverUrl,
                        tracks = emptyList()
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error in oEmbed tier: ${e.message}")
        }

        if (was404OrPrivate) {
            throw IllegalStateException("This Spotify playlist is Private. In Spotify, tap (⋮) on the playlist -> 'Make Public', then copy the link and try again.")
        }

        Log.e(TAG, "Failed all tiers for Spotify import: id=$id")
        null
    }

    /**
     * Fetches a full playlist from Spotify Web API with multi-page pagination.
     */
    suspend fun fetchPlaylistFromApi(
        playlistId: String,
        token: String,
        onProgress: ((String) -> Unit)? = null
    ): Playlist? = withContext(Dispatchers.IO) {
        val initialUrl = "https://api.spotify.com/v1/playlists/$playlistId"
        Log.d(TAG, "Calling Spotify API for playlist: $initialUrl")

        val req = Request.Builder()
            .url(initialUrl)
            .header("Authorization", "Bearer $token")
            .header("User-Agent", USER_AGENT)
            .build()

        val resp = client.newCall(req).execute()
        if (!resp.isSuccessful) {
            Log.w(TAG, "Spotify API Playlist request failed with code ${resp.code}")
            return@withContext null
        }

        val jsonStr = resp.body?.string() ?: return@withContext null
        val root = JSONObject(jsonStr)
        val title = root.optString("name", "Spotify Playlist")
        val description = root.optString("description", "Imported from Spotify")

        var coverUrl: String? = null
        val images = root.optJSONArray("images")
        if (images != null && images.length() > 0) {
            coverUrl = images.optJSONObject(0)?.optString("url")
        }

        val allTracks = mutableListOf<Track>()
        val tracksObj = root.optJSONObject("tracks")
        var totalTracks = tracksObj?.optInt("total", 0) ?: 0
        var nextUrl = tracksObj?.optString("next").takeIf { !it.isNullOrBlank() }

        // Parse initial batch of tracks
        tracksObj?.optJSONArray("items")?.let { items ->
            parseApiTrackItems(items, title, allTracks)
        }

        onProgress?.invoke("Fetching tracks from Spotify (${allTracks.size}/${if (totalTracks > 0) totalTracks else allTracks.size})...")

        // ── PAGINATION LOOP: Fetch all remaining pages ──
        var pageCount = 1
        while (!nextUrl.isNullOrBlank() && pageCount < MAX_PAGE_LIMIT) {
            pageCount++
            try {
                Log.d(TAG, "Fetching Spotify playlist page $pageCount: $nextUrl")
                val pageReq = Request.Builder()
                    .url(nextUrl)
                    .header("Authorization", "Bearer $token")
                    .header("User-Agent", USER_AGENT)
                    .build()

                val pageResp = client.newCall(pageReq).execute()
                if (pageResp.isSuccessful) {
                    val pageBody = pageResp.body?.string() ?: ""
                    val pageJson = JSONObject(pageBody)
                    val items = pageJson.optJSONArray("items")
                    if (items != null) {
                        parseApiTrackItems(items, title, allTracks)
                    }
                    totalTracks = pageJson.optInt("total", totalTracks)
                    nextUrl = pageJson.optString("next").takeIf { !it.isNullOrBlank() }
                    onProgress?.invoke("Fetching tracks from Spotify (${allTracks.size}/${totalTracks})...")
                } else {
                    Log.w(TAG, "Page $pageCount returned HTTP ${pageResp.code}")
                    break
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error fetching page $pageCount: ${e.message}")
                break
            }
        }

        val uniqueTracks = allTracks.distinctBy { it.id }
        Log.i(TAG, "Fetched ${uniqueTracks.size} total tracks via Spotify Web API for playlist '$title'")

        Playlist(
            id = "sp_$playlistId",
            title = title,
            description = description,
            coverUrl = coverUrl,
            tracks = uniqueTracks
        )
    }

    /**
     * Fetches a full album from Spotify Web API with multi-page pagination.
     */
    suspend fun fetchAlbumFromApi(
        albumId: String,
        token: String,
        onProgress: ((String) -> Unit)? = null
    ): Playlist? = withContext(Dispatchers.IO) {
        val initialUrl = "https://api.spotify.com/v1/albums/$albumId"
        Log.d(TAG, "Calling Spotify API for album: $initialUrl")

        val req = Request.Builder()
            .url(initialUrl)
            .header("Authorization", "Bearer $token")
            .header("User-Agent", USER_AGENT)
            .build()

        val resp = client.newCall(req).execute()
        if (!resp.isSuccessful) {
            Log.w(TAG, "Spotify API Album request failed with code ${resp.code}")
            return@withContext null
        }

        val jsonStr = resp.body?.string() ?: return@withContext null
        val root = JSONObject(jsonStr)
        val title = root.optString("name", "Spotify Album")

        val artists = root.optJSONArray("artists")
        val artistNames = mutableListOf<String>()
        if (artists != null) {
            for (i in 0 until artists.length()) {
                val aName = artists.optJSONObject(i)?.optString("name")
                if (!aName.isNullOrBlank()) artistNames.add(aName)
            }
        }
        val albumArtist = if (artistNames.isNotEmpty()) artistNames.joinToString(", ") else "Spotify Artist"

        var coverUrl: String? = null
        val images = root.optJSONArray("images")
        if (images != null && images.length() > 0) {
            coverUrl = images.optJSONObject(0)?.optString("url")
        }

        val allTracks = mutableListOf<Track>()
        val tracksObj = root.optJSONObject("tracks")
        var nextUrl = tracksObj?.optString("next").takeIf { !it.isNullOrBlank() }

        // Parse album tracks
        tracksObj?.optJSONArray("items")?.let { items ->
            parseAlbumTrackItems(items, title, albumArtist, coverUrl, allTracks)
        }

        // Paginate album tracks if needed
        var pageCount = 1
        while (!nextUrl.isNullOrBlank() && pageCount < MAX_PAGE_LIMIT) {
            pageCount++
            try {
                val pageReq = Request.Builder()
                    .url(nextUrl)
                    .header("Authorization", "Bearer $token")
                    .header("User-Agent", USER_AGENT)
                    .build()
                val pageResp = client.newCall(pageReq).execute()
                if (pageResp.isSuccessful) {
                    val pageJson = JSONObject(pageResp.body?.string() ?: "")
                    val items = pageJson.optJSONArray("items")
                    if (items != null) {
                        parseAlbumTrackItems(items, title, albumArtist, coverUrl, allTracks)
                    }
                    nextUrl = pageJson.optString("next").takeIf { !it.isNullOrBlank() }
                } else break
            } catch (e: Exception) {
                break
            }
        }

        Playlist(
            id = "sp_$albumId",
            title = title,
            description = "Album by $albumArtist",
            coverUrl = coverUrl,
            tracks = allTracks.distinctBy { it.id }
        )
    }

    /**
     * Fetches a single track from Spotify Web API.
     */
    suspend fun fetchTrackFromApi(
        trackId: String,
        token: String
    ): Playlist? = withContext(Dispatchers.IO) {
        val url = "https://api.spotify.com/v1/tracks/$trackId"
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("User-Agent", USER_AGENT)
            .build()

        val resp = client.newCall(req).execute()
        if (!resp.isSuccessful) return@withContext null

        val root = JSONObject(resp.body?.string() ?: return@withContext null)
        val title = root.optString("name", "Spotify Track")
        val artists = root.optJSONArray("artists")
        val artistNames = mutableListOf<String>()
        if (artists != null) {
            for (i in 0 until artists.length()) {
                val aName = artists.optJSONObject(i)?.optString("name")
                if (!aName.isNullOrBlank()) artistNames.add(aName)
            }
        }
        val artist = if (artistNames.isNotEmpty()) artistNames.joinToString(", ") else "Spotify Artist"
        val albumObj = root.optJSONObject("album")
        val albumName = albumObj?.optString("name", "Single") ?: "Single"
        val coverUrl = albumObj?.optJSONArray("images")?.optJSONObject(0)?.optString("url")
        val durationMs = root.optLong("duration_ms", 210000L)

        val track = Track(
            id = "sp_$trackId",
            title = TitleCleaner.cleanTitle(title),
            artist = artist,
            album = albumName,
            thumbnail = coverUrl ?: "",
            duration = durationMs / 1000L,
            source = TrackSource.YOUTUBE
        )

        Playlist(
            id = "sp_$trackId",
            title = title,
            description = "Track by $artist",
            coverUrl = coverUrl,
            tracks = listOf(track)
        )
    }

    /**
     * Helper to extract Track objects from Spotify API items array.
     */
    fun parseApiTrackItems(items: JSONArray, defaultAlbum: String, outList: MutableList<Track>) {
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val trackObj = item.optJSONObject("track") ?: item
            val id = trackObj.optString("id")
            val name = trackObj.optString("name")
            val isLocal = trackObj.optBoolean("is_local", false)

            if (name.isBlank() || isLocal || id.isBlank()) continue

            val artistsArr = trackObj.optJSONArray("artists")
            val artistsList = mutableListOf<String>()
            if (artistsArr != null) {
                for (a in 0 until artistsArr.length()) {
                    val aName = artistsArr.optJSONObject(a)?.optString("name")
                    if (!aName.isNullOrBlank()) artistsList.add(aName)
                }
            }
            val artistStr = if (artistsList.isNotEmpty()) artistsList.joinToString(", ") else "Spotify Artist"

            val albumObj = trackObj.optJSONObject("album")
            val albumName = albumObj?.optString("name", defaultAlbum)?.ifBlank { defaultAlbum } ?: defaultAlbum
            val trackArtwork = albumObj?.optJSONArray("images")?.optJSONObject(0)?.optString("url") ?: ""

            val durationMs = trackObj.optLong("duration_ms", 210000L)

            outList.add(
                Track(
                    id = "sp_$id",
                    title = TitleCleaner.cleanTitle(name),
                    artist = artistStr,
                    album = albumName,
                    thumbnail = trackArtwork,
                    duration = durationMs / 1000L,
                    source = TrackSource.YOUTUBE
                )
            )
        }
    }

    private fun parseAlbumTrackItems(
        items: JSONArray,
        albumName: String,
        albumArtist: String,
        albumCover: String?,
        outList: MutableList<Track>
    ) {
        for (i in 0 until items.length()) {
            val trackObj = items.optJSONObject(i) ?: continue
            val id = trackObj.optString("id")
            val name = trackObj.optString("name")
            if (name.isBlank() || id.isBlank()) continue

            val artistsArr = trackObj.optJSONArray("artists")
            val artistsList = mutableListOf<String>()
            if (artistsArr != null) {
                for (a in 0 until artistsArr.length()) {
                    val aName = artistsArr.optJSONObject(a)?.optString("name")
                    if (!aName.isNullOrBlank()) artistsList.add(aName)
                }
            }
            val artistStr = if (artistsList.isNotEmpty()) artistsList.joinToString(", ") else albumArtist
            val durationMs = trackObj.optLong("duration_ms", 210000L)

            outList.add(
                Track(
                    id = "sp_$id",
                    title = TitleCleaner.cleanTitle(name),
                    artist = artistStr,
                    album = albumName,
                    thumbnail = albumCover ?: "",
                    duration = durationMs / 1000L,
                    source = TrackSource.YOUTUBE
                )
            )
        }
    }

    /**
     * Enriches imported Spotify tracks with real YouTube Music official artwork and video IDs.
     * Uses controlled concurrency (Semaphore) to prevent network congestion when importing large playlists.
     */
    suspend fun enrichTracksWithYouTubeData(
        tracks: List<Track>,
        onProgress: ((String) -> Unit)? = null
    ): List<Track> = withContext(Dispatchers.IO) {
        val total = tracks.size
        if (total == 0) return@withContext emptyList()

        val semaphore = Semaphore(8)
        val completedCounter = AtomicInteger(0)

        coroutineScope {
            tracks.map { track ->
                async {
                    semaphore.withPermit {
                        try {
                            val cleanArtist = if (track.artist == "Spotify Artist" || track.artist.isBlank()) "" else track.artist
                            val query = "${track.title} $cleanArtist".trim()
                            val songsResult = innerTubeClient.search(query, InnerTubeClient.FILTER_SONGS).songs
                            val match = songsResult.firstOrNull() ?: innerTubeClient.search(query).songs.firstOrNull()

                            val count = completedCounter.incrementAndGet()
                            if (count % 10 == 0 || count == total) {
                                onProgress?.invoke("Matching songs with YouTube Music ($count/$total)...")
                            }

                            if (match != null) {
                                track.copy(
                                    id = match.id,
                                    thumbnail = match.thumbnail.ifBlank {
                                        track.thumbnail.ifBlank { "https://i.ytimg.com/vi/${match.id}/hq720.jpg" }
                                    },
                                    duration = if (match.duration > 0) match.duration else track.duration
                                )
                            } else {
                                if (track.thumbnail.contains("mosaic.scdn.co") || track.thumbnail.contains("image-cdn")) {
                                    track.copy(thumbnail = "")
                                } else {
                                    track
                                }
                            }
                        } catch (e: Exception) {
                            track
                        }
                    }
                }
            }.awaitAll()
        }
    }

    /**
     * Parses the HTML body of open.spotify.com/embed/... to extract playlist metadata and tracks.
     */
    fun parseEmbedHtml(html: String, resourceId: String, type: SpotifyItemType): Playlist? {
        if (html.isBlank()) return null

        // Look for <script id="__NEXT_DATA__" type="application/json">(.*?)</script>
        val nextDataPattern = Pattern.compile("<script id=\"__NEXT_DATA__\" type=\"application/json\">(.*?)</script>", Pattern.DOTALL)
        val nextDataMatcher = nextDataPattern.matcher(html)
        if (nextDataMatcher.find()) {
            val jsonStr = nextDataMatcher.group(1)
            if (!jsonStr.isNullOrBlank()) {
                try {
                    val json = JSONObject(jsonStr)
                    val playlist = parseNextDataJson(json, resourceId, type)
                    if (playlist != null && playlist.tracks.isNotEmpty()) {
                        return playlist
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error parsing __NEXT_DATA__: ${e.message}")
                }
            }
        }

        // Look for <script id="initial-state">(.*?)</script> or <script id="session">(.*?)</script>
        val initialStatePattern = Pattern.compile("<script id=\"initial-state\"[^>]*>(.*?)</script>", Pattern.DOTALL)
        val initialStateMatcher = initialStatePattern.matcher(html)
        if (initialStateMatcher.find()) {
            val raw = initialStateMatcher.group(1)
            if (!raw.isNullOrBlank()) {
                try {
                    val decoded = if (raw.startsWith("{")) {
                        raw
                    } else {
                        try {
                            String(java.util.Base64.getDecoder().decode(raw.trim()))
                        } catch (_: Exception) {
                            raw
                        }
                    }
                    val json = JSONObject(decoded)
                    val playlist = parseInitialStateJson(json, resourceId, type)
                    if (playlist != null && playlist.tracks.isNotEmpty()) {
                        return playlist
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error parsing initial-state: ${e.message}")
                }
            }
        }

        // Look for raw regex matches for track JSON objects in the HTML
        try {
            val trackMatches = Regex(""""uri":"spotify:track:([a-zA-Z0-9]+)"[^}]*?"title":"([^"]+)"[^}]*?"subtitle":"([^"]+)"""").findAll(html)
            val tracks = mutableListOf<Track>()
            for (match in trackMatches) {
                val trId = match.groupValues[1]
                val trTitle = match.groupValues[2]
                val trArtist = match.groupValues[3]
                if (trTitle.isNotBlank()) {
                    tracks.add(
                        Track(
                            id = "sp_$trId",
                            title = TitleCleaner.cleanTitle(trTitle),
                            artist = if (trArtist.isBlank()) "Spotify Artist" else trArtist,
                            album = "Spotify Playlist",
                            thumbnail = "",
                            duration = 210L,
                            source = TrackSource.YOUTUBE
                        )
                    )
                }
            }
            if (tracks.isNotEmpty()) {
                val titleMatch = Regex("""<title[^>]*>([^<]+)</title>""").find(html)
                val cleanTitle = titleMatch?.groupValues?.get(1)?.replace("- playlist by Spotify | Spotify", "")?.replace("| Spotify", "")?.trim() ?: "Spotify Playlist"
                return Playlist(
                    id = "sp_$resourceId",
                    title = if (cleanTitle.isNotBlank()) cleanTitle else "Spotify Playlist",
                    description = "Imported from Spotify",
                    coverUrl = null,
                    tracks = tracks.distinctBy { it.id }
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error parsing regex tracks: ${e.message}")
        }

        return null
    }

    /**
     * Parses Next.js __NEXT_DATA__ JSON payload from Spotify embed page.
     */
    private fun parseNextDataJson(json: JSONObject, resourceId: String, itemType: SpotifyItemType): Playlist? {
        val pageProps = json.optJSONObject("props")?.optJSONObject("pageProps") ?: return null
        val state = pageProps.optJSONObject("state")?.optJSONObject("data")
        val entity = state?.optJSONObject("entity") ?: pageProps.optJSONObject("entity") ?: return null

        val title = entity.optString("title").ifBlank {
            entity.optString("name", if (itemType == SpotifyItemType.ALBUM) "Spotify Album" else "Spotify Playlist")
        }
        val description = entity.optString("subtitle").ifBlank {
            entity.optString("description", "Imported from Spotify")
        }

        var coverUrl: String? = null
        val visualIdentity = entity.optJSONObject("visualIdentity")
        if (visualIdentity != null) {
            val imageArray = visualIdentity.optJSONArray("image")
            if (imageArray != null && imageArray.length() > 0) {
                coverUrl = imageArray.optJSONObject(imageArray.length() - 1)?.optString("url")
                    ?: imageArray.optJSONObject(0)?.optString("url")
            }
        }
        if (coverUrl.isNullOrBlank()) {
            coverUrl = entity.optJSONObject("coverArt")?.optJSONArray("sources")?.optJSONObject(0)?.optString("url")
                ?: entity.optJSONObject("album")?.optJSONObject("coverArt")?.optJSONArray("sources")?.optJSONObject(0)?.optString("url")
        }

        val tracks = mutableListOf<Track>()
        val trackList = entity.optJSONArray("trackList") ?: entity.optJSONArray("tracks")

        if (trackList != null) {
            for (i in 0 until trackList.length()) {
                val item = trackList.optJSONObject(i) ?: continue
                val trackTitle = item.optString("title").ifBlank { item.optString("name", "Track $i") }
                val trackSubtitle = item.optString("subtitle").ifBlank {
                    val artistsArr = item.optJSONArray("artists")
                    if (artistsArr != null && artistsArr.length() > 0) {
                        val names = mutableListOf<String>()
                        for (a in 0 until artistsArr.length()) {
                            val name = artistsArr.optJSONObject(a)?.optString("name")
                            if (!name.isNullOrBlank()) names.add(name)
                        }
                        names.joinToString(", ")
                    } else "Spotify Artist"
                }

                val durationMs = item.optLong("duration", item.optLong("durationMs", 210000L))
                val durationSec = if (durationMs > 1000L) durationMs / 1000L else durationMs

                val uri = item.optString("uri", "")
                val trackId = if (uri.startsWith("spotify:track:")) {
                    uri.substringAfter("spotify:track:")
                } else {
                    item.optString("id", "track_$i")
                }

                var trackArtwork: String? = null
                val itemArt = item.optJSONObject("album")?.optJSONObject("coverArt")?.optJSONArray("sources")?.optJSONObject(0)?.optString("url")
                if (!itemArt.isNullOrBlank()) {
                    trackArtwork = itemArt
                }

                if (trackTitle.isNotBlank() && !trackTitle.startsWith("Track ")) {
                    tracks.add(
                        Track(
                            id = "sp_$trackId",
                            title = TitleCleaner.cleanTitle(trackTitle),
                            artist = if (trackSubtitle.isBlank() || trackSubtitle == "Artist") "Spotify Artist" else trackSubtitle,
                            album = title,
                            thumbnail = trackArtwork ?: "",
                            duration = durationSec,
                            source = TrackSource.YOUTUBE
                        )
                    )
                }
            }
        }

        val uniqueTracks = tracks.distinctBy { it.id }

        return if (uniqueTracks.isNotEmpty() || title.isNotBlank()) {
            Playlist(
                id = "sp_$resourceId",
                title = title,
                description = description,
                coverUrl = coverUrl,
                tracks = uniqueTracks
            )
        } else null
    }

    /**
     * Parses initial-state JSON payload if present.
     */
    private fun parseInitialStateJson(json: JSONObject, resourceId: String, itemType: SpotifyItemType): Playlist? {
        val rootData = json.optJSONObject("data") ?: json
        val entity = rootData.optJSONObject("entity") ?: return null

        val title = entity.optString("title", entity.optString("name", "Spotify Playlist"))
        val subtitle = entity.optString("subtitle", entity.optString("description", "Imported from Spotify"))
        val coverUrl = entity.optString("coverUrl", entity.optString("imageUrl", ""))

        val tracks = mutableListOf<Track>()
        val trackList = entity.optJSONArray("trackList") ?: entity.optJSONArray("tracks")

        if (trackList != null) {
            for (i in 0 until trackList.length()) {
                val item = trackList.optJSONObject(i) ?: continue
                val trackTitle = item.optString("title", item.optString("name", ""))
                val trackArtist = item.optString("subtitle", item.optString("artist", "Spotify Artist"))
                val durationMs = item.optLong("duration", 210000L)
                val trackId = item.optString("id", "track_$i")

                if (trackTitle.isNotBlank()) {
                    tracks.add(
                        Track(
                            id = "sp_$trackId",
                            title = TitleCleaner.cleanTitle(trackTitle),
                            artist = trackArtist,
                            album = title,
                            thumbnail = "",
                            duration = durationMs / 1000L,
                            source = TrackSource.YOUTUBE
                        )
                    )
                }
            }
        }

        return if (tracks.isNotEmpty()) {
            Playlist(
                id = "sp_$resourceId",
                title = title,
                description = subtitle,
                coverUrl = coverUrl.ifBlank { null },
                tracks = tracks
            )
        } else null
    }
}
