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
        private const val PATHFINDER_URL = "https://api-partner.spotify.com/pathfinder/v1/query"
        private const val PLAYLIST_HASH = "a65e12194ed5fc443a1cdebed5fabe33ca5b07b987185d63c72483867ad13cb4"
        private const val ALBUM_HASH = "b9bfabef66ed756e5e13f68a942deb60bd4125ec1f1be8cc42769dc0259b4b10"

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

        /**
         * Extracts anonymous bearer token from Spotify Embed HTML __NEXT_DATA__ payload.
         */
        fun extractSessionTokenFromEmbed(html: String): String? {
            if (html.isBlank()) return null
            try {
                // Check regex for "accessToken":"..."
                val tokenMatch = Regex(""""accessToken"\s*:\s*"([^"]+)"""").find(html)
                if (tokenMatch != null) {
                    val token = tokenMatch.groupValues[1].trim()
                    if (token.isNotBlank() && token.length > 20) {
                        return token
                    }
                }

                // Check __NEXT_DATA__ JSON object
                val nextDataPattern = Pattern.compile("<script id=\"__NEXT_DATA__\" type=\"application/json\">(.*?)</script>", Pattern.DOTALL)
                val matcher = nextDataPattern.matcher(html)
                if (matcher.find()) {
                    val json = JSONObject(matcher.group(1) ?: "")
                    val pageProps = json.optJSONObject("props")?.optJSONObject("pageProps")
                    val state = pageProps?.optJSONObject("state")?.optJSONObject("data")
                    val session = state?.optJSONObject("settings")?.optJSONObject("session")
                        ?: pageProps?.optJSONObject("settings")?.optJSONObject("session")
                    val token = session?.optString("accessToken")
                    if (!token.isNullOrBlank()) {
                        return token
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error extracting session token from embed: ${e.message}")
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
        // ── TIER 0: OFFICIAL SPOTIFY WEB API (IF DEVELOPER KEYS PRESENT) ──────
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
                    Log.i(TAG, "Spotify Web API fetched ${apiPlaylist.tracks.size} tracks for '${apiPlaylist.title}'")
                    return@withContext apiPlaylist
                }
            } catch (e: Exception) {
                Log.w(TAG, "Spotify Web API Tier failed: ${e.message}. Falling back to Pathfinder GraphQL / Embed tiers...")
            }
        }

        // ══════════════════════════════════════════════════════════════════════
        // ── TIER 1: ANONYMOUS PATHFINDER GRAPHQL ENGINE (UNLIMITED TRACKS) ────
        // ══════════════════════════════════════════════════════════════════════
        val endpointType = when (type) {
            SpotifyItemType.ALBUM -> "album"
            SpotifyItemType.TRACK -> "track"
            else -> "playlist"
        }

        val embedUrl = "https://open.spotify.com/embed/$endpointType/$id"
        var embedHtml: String? = null
        var was404OrPrivate = false

        try {
            Log.d(TAG, "Fetching Embed HTML for token & metadata: $embedUrl")
            val embedReq = Request.Builder()
                .url(embedUrl)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.9")
                .build()

            val embedResp = client.newCall(embedReq).execute()
            if (embedResp.code == 404) was404OrPrivate = true
            if (embedResp.isSuccessful) {
                embedHtml = embedResp.body?.string() ?: ""
                if (embedHtml.contains("Page not found") || embedHtml.contains("\"status\":404")) {
                    was404OrPrivate = true
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error fetching embed HTML: ${e.message}")
        }

        // Extract anonymous token from embed page
        val anonymousToken = embedHtml?.let { extractSessionTokenFromEmbed(it) }
        if (!anonymousToken.isNullOrBlank()) {
            try {
                Log.i(TAG, "Acquired anonymous session token from embed HTML. Starting Pathfinder GraphQL multi-page import...")
                val pathfinderPlaylist = when (type) {
                    SpotifyItemType.PLAYLIST -> fetchPlaylistViaPathfinder(id, anonymousToken, onProgress)
                    SpotifyItemType.ALBUM -> fetchAlbumViaPathfinder(id, anonymousToken, onProgress)
                    SpotifyItemType.TRACK -> fetchTrackFromApi(id, anonymousToken)
                    else -> fetchPlaylistViaPathfinder(id, anonymousToken, onProgress)
                }

                if (pathfinderPlaylist != null && pathfinderPlaylist.tracks.isNotEmpty()) {
                    Log.i(TAG, "Pathfinder GraphQL successfully fetched ${pathfinderPlaylist.tracks.size} tracks for '${pathfinderPlaylist.title}' in record time.")
                    return@withContext pathfinderPlaylist
                }
            } catch (e: Exception) {
                Log.w(TAG, "Pathfinder GraphQL Tier failed: ${e.message}. Falling back to Embed HTML scraper...")
            }
        }

        // ══════════════════════════════════════════════════════════════════════
        // ── TIER 2: SPOTIFY EMBED HTML SCRAPING (FALLBACK) ──────────────────
        // ══════════════════════════════════════════════════════════════════════
        if (!embedHtml.isNullOrBlank()) {
            val parsed = parseEmbedHtml(embedHtml, id, type)
            if (parsed != null && parsed.tracks.isNotEmpty()) {
                Log.i(TAG, "Parsed ${parsed.tracks.size} tracks from Embed HTML")
                return@withContext parsed
            }
        }

        val urlsToTry = listOf(
            "https://open.spotify.com/embed/$endpointType/$id?utm_source=oembed",
            "https://open.spotify.com/$endpointType/$id"
        )

        for (url in urlsToTry) {
            try {
                Log.d(TAG, "Fetching Spotify Embed fallback URL: $url")
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .build()

                val response = client.newCall(request).execute()
                if (response.code == 404) was404OrPrivate = true
                if (response.isSuccessful) {
                    val html = response.body?.string() ?: ""
                    if (html.contains("Page not found") || html.contains("\"status\":404")) {
                        was404OrPrivate = true
                    }
                    val parsed = parseEmbedHtml(html, id, type)
                    if (parsed != null && parsed.tracks.isNotEmpty()) {
                        Log.i(TAG, "Successfully parsed ${parsed.tracks.size} tracks from fallback HTML")
                        return@withContext parsed
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
                                return@withContext parsed
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
     * Fetches a full playlist from Spotify's internal Pathfinder GraphQL API with multi-page pagination.
     * Works anonymously without requiring Developer API credentials or Spotify Premium.
     */
    suspend fun fetchPlaylistViaPathfinder(
        playlistId: String,
        token: String,
        onProgress: ((String) -> Unit)? = null
    ): Playlist? = withContext(Dispatchers.IO) {
        var title = "Spotify Playlist"
        var description = "Imported from Spotify"
        var coverUrl: String? = null
        val allTracks = mutableListOf<Track>()
        var offset = 0
        val limit = 100
        var totalTracks = Int.MAX_VALUE
        var page = 1

        Log.d(TAG, "Starting Pathfinder GraphQL pagination for playlist $playlistId")

        while (offset < totalTracks && page <= MAX_PAGE_LIMIT) {
            val variables = JSONObject().apply {
                put("uri", "spotify:playlist:$playlistId")
                put("offset", offset)
                put("limit", limit)
                put("enableWatchFeedEntrypoint", false)
            }
            val extensions = JSONObject().apply {
                put("persistedQuery", JSONObject().apply {
                    put("version", 1)
                    put("sha256Hash", PLAYLIST_HASH)
                })
            }

            val queryUrl = "$PATHFINDER_URL?operationName=fetchPlaylist&variables=${URLEncoder.encode(variables.toString(), "UTF-8")}&extensions=${URLEncoder.encode(extensions.toString(), "UTF-8")}"

            val req = Request.Builder()
                .url(queryUrl)
                .header("Authorization", "Bearer $token")
                .header("app-platform", "WebPlayer")
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .build()

            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) {
                Log.w(TAG, "Pathfinder fetchPlaylist page $page failed with code ${resp.code}")
                break
            }

            val body = resp.body?.string() ?: break
            val json = JSONObject(body)
            val data = json.optJSONObject("data") ?: break
            val playlistV2 = data.optJSONObject("playlistV2") ?: break

            if (page == 1) {
                title = playlistV2.optString("name", title)
                val descObj = playlistV2.optJSONObject("description")
                description = descObj?.optString("text", description) ?: description
                val coverSources = playlistV2.optJSONObject("images")?.optJSONArray("items")?.optJSONObject(0)?.optJSONArray("sources")
                if (coverSources != null && coverSources.length() > 0) {
                    coverUrl = coverSources.optJSONObject(0)?.optString("url")
                }
            }

            val content = playlistV2.optJSONObject("content") ?: break
            totalTracks = content.optInt("totalCount", totalTracks)
            val items = content.optJSONArray("items") ?: break
            if (items.length() == 0) break

            parsePathfinderPlaylistItems(items, title, allTracks, coverUrl ?: "")

            onProgress?.invoke("Fetching tracks from Spotify (${allTracks.size}/${if (totalTracks < Int.MAX_VALUE) totalTracks else allTracks.size})...")

            offset += items.length()
            page++
        }

        if (allTracks.isEmpty()) return@withContext null

        Log.i(TAG, "Pathfinder GraphQL fetched ${allTracks.size} tracks for playlist '$title'")

        Playlist(
            id = "sp_$playlistId",
            title = title,
            description = description,
            coverUrl = coverUrl,
            tracks = allTracks
        )
    }

    /**
     * Fetches a full album from Spotify's internal Pathfinder GraphQL API with multi-page pagination.
     */
    suspend fun fetchAlbumViaPathfinder(
        albumId: String,
        token: String,
        onProgress: ((String) -> Unit)? = null
    ): Playlist? = withContext(Dispatchers.IO) {
        var title = "Spotify Album"
        var artist = "Spotify Artist"
        var coverUrl: String? = null
        val allTracks = mutableListOf<Track>()
        var offset = 0
        val limit = 50
        var totalTracks = Int.MAX_VALUE
        var page = 1

        Log.d(TAG, "Starting Pathfinder GraphQL pagination for album $albumId")

        while (offset < totalTracks && page <= MAX_PAGE_LIMIT) {
            val variables = JSONObject().apply {
                put("uri", "spotify:album:$albumId")
                put("locale", "")
                put("offset", offset)
                put("limit", limit)
            }
            val extensions = JSONObject().apply {
                put("persistedQuery", JSONObject().apply {
                    put("version", 1)
                    put("sha256Hash", ALBUM_HASH)
                })
            }

            val queryUrl = "$PATHFINDER_URL?operationName=getAlbum&variables=${URLEncoder.encode(variables.toString(), "UTF-8")}&extensions=${URLEncoder.encode(extensions.toString(), "UTF-8")}"

            val req = Request.Builder()
                .url(queryUrl)
                .header("Authorization", "Bearer $token")
                .header("app-platform", "WebPlayer")
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .build()

            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) {
                Log.w(TAG, "Pathfinder getAlbum page $page failed with code ${resp.code}")
                break
            }

            val body = resp.body?.string() ?: break
            val json = JSONObject(body)
            val data = json.optJSONObject("data") ?: break
            val albumUnion = data.optJSONObject("albumUnion") ?: data.optJSONObject("album") ?: break

            if (page == 1) {
                title = albumUnion.optString("name", title)
                val artistsArr = albumUnion.optJSONObject("artists")?.optJSONArray("items")
                if (artistsArr != null && artistsArr.length() > 0) {
                    val names = mutableListOf<String>()
                    for (a in 0 until artistsArr.length()) {
                        val aName = artistsArr.optJSONObject(a)?.optJSONObject("profile")?.optString("name")
                            ?: artistsArr.optJSONObject(a)?.optString("name")
                        if (!aName.isNullOrBlank()) names.add(aName)
                    }
                    if (names.isNotEmpty()) artist = names.joinToString(", ")
                }
                val coverSources = albumUnion.optJSONObject("coverArt")?.optJSONArray("sources")
                if (coverSources != null && coverSources.length() > 0) {
                    coverUrl = coverSources.optJSONObject(0)?.optString("url")
                }
            }

            val tracksObj = albumUnion.optJSONObject("tracksV2") ?: albumUnion.optJSONObject("tracks") ?: break
            totalTracks = tracksObj.optInt("totalCount", totalTracks)
            val items = tracksObj.optJSONArray("items") ?: break
            if (items.length() == 0) break

            parsePathfinderAlbumItems(items, title, artist, coverUrl, allTracks)

            onProgress?.invoke("Fetching album tracks from Spotify (${allTracks.size}/${if (totalTracks < Int.MAX_VALUE) totalTracks else allTracks.size})...")

            offset += items.length()
            page++
        }

        if (allTracks.isEmpty()) return@withContext null

        Playlist(
            id = "sp_$albumId",
            title = title,
            description = "Album by $artist",
            coverUrl = coverUrl,
            tracks = allTracks
        )
    }

    /**
     * Helper to extract Track objects from Pathfinder GraphQL playlist items array.
     */
    fun parsePathfinderPlaylistItems(
        items: JSONArray,
        defaultAlbum: String,
        outList: MutableList<Track>,
        defaultCoverUrl: String = ""
    ) {
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val itemV2 = item.optJSONObject("itemV2")
            val trackData = itemV2?.optJSONObject("data") 
                ?: item.optJSONObject("track") 
                ?: item.optJSONObject("item") 
                ?: item

            var name = trackData.optString("name").ifBlank { trackData.optString("title") }
            if (name.isBlank()) {
                name = trackData.optJSONObject("track")?.optString("name")
                    ?: trackData.optJSONObject("episode")?.optString("name")
                    ?: trackData.optJSONObject("data")?.optString("name")
                    ?: ""
            }

            val uri = trackData.optString("uri")
            var id = if (uri.startsWith("spotify:track:")) {
                uri.substringAfter("spotify:track:")
            } else {
                trackData.optString("id")
            }
            if (id.isBlank()) {
                id = item.optString("uid").ifBlank {
                    if (uri.isNotBlank()) uri.replace(":", "_") else "sp_track_${outList.size}_$i"
                }
            }

            if (name.isBlank()) {
                name = if (id.isNotBlank()) "Unavailable Track ($id)" else "Unavailable Track"
            }

            val artistsArr = trackData.optJSONObject("artists")?.optJSONArray("items")
                ?: trackData.optJSONArray("artists")
            val artistsList = mutableListOf<String>()
            if (artistsArr != null) {
                for (a in 0 until artistsArr.length()) {
                    val aObj = artistsArr.optJSONObject(a)
                    val aName = aObj?.optJSONObject("profile")?.optString("name")
                        ?: aObj?.optString("name")
                    if (!aName.isNullOrBlank()) artistsList.add(aName)
                }
            }
            val artistStr = if (artistsList.isNotEmpty()) {
                artistsList.joinToString(", ")
            } else {
                val subtitle = trackData.optString("subtitle")
                if (subtitle.isNotBlank()) subtitle else "Spotify Artist"
            }

            val albumObj = trackData.optJSONObject("albumOfTrack") ?: trackData.optJSONObject("album")
            val albumName = albumObj?.optString("name", defaultAlbum)?.ifBlank { defaultAlbum } ?: defaultAlbum

            val coverSources = albumObj?.optJSONObject("coverArt")?.optJSONArray("sources")
                ?: albumObj?.optJSONArray("images")
                ?: trackData.optJSONObject("coverArt")?.optJSONArray("sources")
                ?: trackData.optJSONArray("images")
                ?: trackData.optJSONObject("visuals")?.optJSONObject("avatarImage")?.optJSONArray("sources")
            var trackArtwork = coverSources?.optJSONObject(0)?.optString("url") ?: ""
            if (trackArtwork.isBlank() && defaultCoverUrl.isNotBlank()) {
                trackArtwork = defaultCoverUrl
            }

            val durationObj = trackData.optJSONObject("trackDuration")
            val durationMs = durationObj?.optLong("totalMilliseconds")
                ?: trackData.optLong("duration", trackData.optLong("duration_ms", 210000L))

            outList.add(
                Track(
                    id = "sp_$id",
                    title = name.trim(),
                    artist = artistStr,
                    album = albumName,
                    thumbnail = trackArtwork,
                    duration = if (durationMs > 1000L) durationMs / 1000L else durationMs,
                    source = TrackSource.YOUTUBE
                )
            )
        }
    }

    /**
     * Helper to extract Track objects from Pathfinder GraphQL album items array.
     */
    fun parsePathfinderAlbumItems(
        items: JSONArray,
        albumName: String,
        albumArtist: String,
        albumCover: String?,
        outList: MutableList<Track>
    ) {
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val trackData = item.optJSONObject("track") ?: item.optJSONObject("item") ?: item

            var name = trackData.optString("name").ifBlank { trackData.optString("title") }
            val uri = trackData.optString("uri")
            var id = if (uri.startsWith("spotify:track:")) uri.substringAfter("spotify:track:") else trackData.optString("id")
            if (id.isBlank()) {
                id = item.optString("uid").ifBlank {
                    if (uri.isNotBlank()) uri.replace(":", "_") else "sp_album_${outList.size}_$i"
                }
            }

            if (name.isBlank()) {
                name = if (id.isNotBlank()) "Unavailable Track ($id)" else "Unavailable Track"
            }

            val artistsArr = trackData.optJSONObject("artists")?.optJSONArray("items")
                ?: trackData.optJSONArray("artists")
            val artistsList = mutableListOf<String>()
            if (artistsArr != null) {
                for (a in 0 until artistsArr.length()) {
                    val aObj = artistsArr.optJSONObject(a)
                    val aName = aObj?.optJSONObject("profile")?.optString("name")
                        ?: aObj?.optString("name")
                    if (!aName.isNullOrBlank()) artistsList.add(aName)
                }
            }
            val artistStr = if (artistsList.isNotEmpty()) artistsList.joinToString(", ") else albumArtist

            val durationObj = trackData.optJSONObject("trackDuration")
            val durationMs = durationObj?.optLong("totalMilliseconds")
                ?: trackData.optLong("duration", trackData.optLong("duration_ms", 210000L))

            outList.add(
                Track(
                    id = "sp_$id",
                    title = name.trim(),
                    artist = artistStr,
                    album = albumName,
                    thumbnail = albumCover ?: "",
                    duration = if (durationMs > 1000L) durationMs / 1000L else durationMs,
                    source = TrackSource.YOUTUBE
                )
            )
        }
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

        Log.i(TAG, "Fetched ${allTracks.size} total tracks via Spotify Web API for playlist '$title'")

        Playlist(
            id = "sp_$playlistId",
            title = title,
            description = description,
            coverUrl = coverUrl,
            tracks = allTracks
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
            tracks = allTracks
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
            title = title.trim(),
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
            val trackObj = item.optJSONObject("track") 
                ?: item.optJSONObject("item") 
                ?: item.optJSONObject("episode") 
                ?: item
            val uri = trackObj.optString("uri")
            var id = trackObj.optString("id")
            if (id.isBlank() && uri.startsWith("spotify:track:")) {
                id = uri.substringAfter("spotify:track:")
            }
            if (id.isBlank()) {
                id = item.optString("uid").ifBlank {
                    if (uri.isNotBlank()) uri.replace(":", "_") else "sp_local_${outList.size}_$i"
                }
            }
            var name = trackObj.optString("name").ifBlank { trackObj.optString("title") }
            if (name.isBlank()) {
                name = if (id.isNotBlank()) "Unavailable Track ($id)" else "Unavailable Track"
            }

            val artistsArr = trackObj.optJSONArray("artists")
            val artistsList = mutableListOf<String>()
            if (artistsArr != null) {
                for (a in 0 until artistsArr.length()) {
                    val aName = artistsArr.optJSONObject(a)?.optString("name")
                    if (!aName.isNullOrBlank()) artistsList.add(aName)
                }
            }
            val artistStr = if (artistsList.isNotEmpty()) {
                artistsList.joinToString(", ")
            } else {
                trackObj.optString("subtitle").ifBlank { "Spotify Artist" }
            }

            val albumObj = trackObj.optJSONObject("album")
            val albumName = albumObj?.optString("name", defaultAlbum)?.ifBlank { defaultAlbum } ?: defaultAlbum
            val trackArtwork = albumObj?.optJSONArray("images")?.optJSONObject(0)?.optString("url") ?: ""

            val durationMs = trackObj.optLong("duration_ms", 210000L)

            outList.add(
                Track(
                    id = "sp_$id",
                    title = name.trim(),
                    artist = artistStr,
                    album = albumName,
                    thumbnail = trackArtwork,
                    duration = if (durationMs > 1000L) durationMs / 1000L else durationMs,
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
            val uri = trackObj.optString("uri")
            var id = trackObj.optString("id")
            if (id.isBlank() && uri.startsWith("spotify:track:")) {
                id = uri.substringAfter("spotify:track:")
            }
            if (id.isBlank()) {
                id = if (uri.isNotBlank()) uri.replace(":", "_") else "sp_album_${outList.size}_$i"
            }
            var name = trackObj.optString("name").ifBlank { trackObj.optString("title") }
            if (name.isBlank()) {
                name = if (id.isNotBlank()) "Unavailable Track ($id)" else "Unavailable Track"
            }

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
                    title = name.trim(),
                    artist = artistStr,
                    album = albumName,
                    thumbnail = albumCover ?: "",
                    duration = if (durationMs > 1000L) durationMs / 1000L else durationMs,
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

        val semaphore = Semaphore(16)
        val completedCounter = AtomicInteger(0)

        coroutineScope {
            tracks.map { track ->
                async {
                    semaphore.withPermit {
                        try {
                            // Yield briefly only if user is actively resolving current live playback
                            while (AudioStreamResolver.isPlaybackResolving) {
                                kotlinx.coroutines.delay(100)
                            }

                            val cleanArtist = if (track.artist == "Spotify Artist" || track.artist.isBlank()) "" else track.artist
                            val primaryArtist = if (cleanArtist.isNotBlank()) {
                                cleanArtist.split(Regex("[,&/]|\\b(feat|ft|with)\\b", RegexOption.IGNORE_CASE)).firstOrNull()?.trim() ?: cleanArtist
                            } else ""
                            val cleanedTitle = TitleCleaner.cleanTitle(track.title)
                            val cleanTitle = cleanedTitle.replace(Regex("\\(.*\\)|\\[.*\\]|(?i)- (from|original|remix|audio).*"), "").trim().ifBlank { track.title }
                            val primaryQuery = if (primaryArtist.isNotBlank()) "${cleanTitle} $primaryArtist".trim() else cleanTitle

                            // 1. Ultra-fast single-pass search: YouTube Music Songs filter with primary artist
                            val songsResult = innerTubeClient.search(primaryQuery, InnerTubeClient.FILTER_SONGS).songs
                            
                            // Check if candidate #0 is the top official match from YouTube Music
                            var topMatch: Track? = null
                            if (songsResult.isNotEmpty()) {
                                val cand0 = songsResult[0]
                                val normCand0Title = TitleCleaner.cleanTitle(cand0.title).lowercase()
                                val normCleanTarget = cleanTitle.lowercase()
                                val isDerivative = listOf("remix", "lofi", "slowed", "dj", "cover", "status", "ringtone", "mashup").any { normCand0Title.contains(it) && !normCleanTarget.contains(it) }
                                if (!isDerivative && (normCand0Title.contains(normCleanTarget) || normCleanTarget.contains(normCand0Title) || normCand0Title.split(" ").any { it.length > 2 && normCleanTarget.contains(it) })) {
                                    topMatch = cand0
                                }
                            }

                            if (topMatch == null) {
                                topMatch = com.auralis.music.domain.search.SearchQueryMatcher.findBestCandidateForTrack(track, songsResult)
                            }

                            // 2. Fallback: Search with full composite artist if multi-artist query
                            if (topMatch == null && cleanArtist != primaryArtist && cleanArtist.isNotBlank()) {
                                val fullArtistQuery = "${cleanTitle} $cleanArtist".trim()
                                val fullArtistSongs = innerTubeClient.search(fullArtistQuery, InnerTubeClient.FILTER_SONGS).songs
                                topMatch = com.auralis.music.domain.search.SearchQueryMatcher.findBestCandidateForTrack(track, fullArtistSongs)
                            }

                            // 3. Fallback: Search with cleanTitle alone
                            if (topMatch == null && cleanTitle.isNotBlank()) {
                                val titleSongsResult = innerTubeClient.search(cleanTitle, InnerTubeClient.FILTER_SONGS).songs
                                topMatch = com.auralis.music.domain.search.SearchQueryMatcher.findBestCandidateForTrack(track, titleSongsResult)
                            }

                            // 4. Fallback: Search general results if Songs filter didn't produce a high-confidence match
                            if (topMatch == null) {
                                val generalResult = innerTubeClient.search(primaryQuery).songs
                                topMatch = com.auralis.music.domain.search.SearchQueryMatcher.findBestCandidateForTrack(track, generalResult)
                            }

                            val count = completedCounter.incrementAndGet()
                            if (count % 5 == 0 || count == total) {
                                onProgress?.invoke("Matching tracks to official audio ($count/$total)...")
                            }

                            if (topMatch != null) {
                                Log.i(TAG, "Matched Spotify track '${track.title}' -> '${topMatch.id}' (${topMatch.title})")
                                track.copy(
                                    id = topMatch.id,
                                    thumbnail = topMatch.thumbnail.ifBlank {
                                        track.thumbnail.ifBlank { "https://i.ytimg.com/vi/${topMatch.id}/hqdefault.jpg" }
                                    },
                                    duration = if (track.duration > 0) track.duration else topMatch.duration
                                )
                            } else {
                                Log.w(TAG, "No confident match for Spotify track '${track.title}' by '${track.artist}' (${track.duration}s). Preserving Spotify identity.")
                                if (track.thumbnail.contains("mosaic.scdn.co") || track.thumbnail.contains("image-cdn")) {
                                    track.copy(thumbnail = "")
                                } else {
                                    track
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error enriching Spotify track '${track.title}': ${e.message}")
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
                            title = trTitle.trim(),
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
                    tracks = tracks
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
                val trackTitle = item.optString("title").ifBlank { item.optString("name") }
                if (trackTitle.isBlank()) continue

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
                    item.optString("id").ifBlank { "track_${tracks.size}_$i" }
                }

                var trackArtwork: String? = null
                val itemArt = item.optJSONObject("album")?.optJSONObject("coverArt")?.optJSONArray("sources")?.optJSONObject(0)?.optString("url")
                    ?: item.optJSONObject("coverArt")?.optJSONArray("sources")?.optJSONObject(0)?.optString("url")
                if (!itemArt.isNullOrBlank()) {
                    trackArtwork = itemArt
                }

                tracks.add(
                    Track(
                        id = "sp_$trackId",
                        title = trackTitle.trim(),
                        artist = if (trackSubtitle.isBlank() || trackSubtitle == "Artist") "Spotify Artist" else trackSubtitle,
                        album = title,
                        thumbnail = trackArtwork ?: "",
                        duration = durationSec,
                        source = TrackSource.YOUTUBE
                    )
                )
            }
        }

        return if (tracks.isNotEmpty() || title.isNotBlank()) {
            Playlist(
                id = "sp_$resourceId",
                title = title,
                description = description,
                coverUrl = coverUrl,
                tracks = tracks
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
                            title = trackTitle.trim(),
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
