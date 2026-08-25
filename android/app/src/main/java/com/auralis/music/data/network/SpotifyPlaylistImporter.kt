package com.auralis.music.data.network

import android.util.Log
import com.auralis.music.domain.model.Playlist
import com.auralis.music.domain.model.Track
import com.auralis.music.domain.model.TrackSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

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
     * Enriches imported Spotify tracks with real YouTube Music official artwork and video IDs.
     */
    suspend fun enrichTracksWithYouTubeData(tracks: List<Track>): List<Track> = withContext(Dispatchers.IO) {
        coroutineScope {
            tracks.map { track ->
                async {
                    try {
                        val cleanArtist = if (track.artist == "Spotify Artist" || track.artist.isBlank()) "" else track.artist
                        val query = "${track.title} $cleanArtist".trim()
                        val songsResult = innerTubeClient.search(query, InnerTubeClient.FILTER_SONGS).songs
                        val match = songsResult.firstOrNull() ?: innerTubeClient.search(query).songs.firstOrNull()
                        if (match != null) {
                            track.copy(
                                id = match.id,
                                thumbnail = match.thumbnail.ifBlank { "https://i.ytimg.com/vi/${match.id}/hq720.jpg" },
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
            }.awaitAll()
        }
    }

    /**
     * Imports a Spotify playlist or album by URL or ID and converts it into an Auralis Playlist domain object.
     */
    suspend fun importPlaylist(urlOrId: String): Playlist? = withContext(Dispatchers.IO) {
        Log.i(TAG, "Starting import for input: '$urlOrId'")
        val resource = resolveResource(urlOrId)
        if (resource == null) {
            Log.e(TAG, "Could not resolve resource from input: '$urlOrId'")
            return@withContext null
        }
        val (id, type) = resource
        val endpointType = when (type) {
            SpotifyItemType.ALBUM -> "album"
            SpotifyItemType.TRACK -> "track"
            else -> "playlist"
        }
        Log.i(TAG, "Resolved Spotify resource: id=$id, type=$type, endpoint=$endpointType")

        // ── TIER 1: SPOTIFY EMBED HTML ──
        val urlsToTry = listOf(
            "https://open.spotify.com/embed/$endpointType/$id",
            "https://open.spotify.com/embed/$endpointType/$id?utm_source=oembed",
            "https://open.spotify.com/$endpointType/$id"
        )

        var was404OrPrivate = false

        for (url in urlsToTry) {
            try {
                Log.d(TAG, "Fetching Spotify URL: $url")
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .build()

                val response = client.newCall(request).execute()
                Log.d(TAG, "Response code for $url: ${response.code}")
                if (response.code == 404) {
                    was404OrPrivate = true
                }
                if (response.isSuccessful) {
                    val html = response.body?.string() ?: ""
                    Log.d(TAG, "Fetched HTML length: ${html.length}")
                    if (html.contains("Page not found") || html.contains("\"status\":404")) {
                        was404OrPrivate = true
                    }
                    val parsed = parseEmbedHtml(html, id, type)
                    if (parsed != null && parsed.tracks.isNotEmpty()) {
                        Log.i(TAG, "Successfully parsed ${parsed.tracks.size} tracks from $url (Title: '${parsed.title}'). Enriching with official artwork...")
                        val enrichedTracks = enrichTracksWithYouTubeData(parsed.tracks)
                        return@withContext parsed.copy(tracks = enrichedTracks)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error fetching $url: ${e.message}")
            }
        }

        // ── TIER 2: SPOTIFY OEMBED METADATA ──
        try {
            val encodedTarget = URLEncoder.encode("https://open.spotify.com/$endpointType/$id", "UTF-8")
            val oembedUrl = "https://open.spotify.com/oembed?url=$encodedTarget"
            Log.d(TAG, "Fetching oEmbed URL: $oembedUrl")
            val request = Request.Builder()
                .url(oembedUrl)
                .header("User-Agent", USER_AGENT)
                .build()

            val response = client.newCall(request).execute()
            Log.d(TAG, "oEmbed response code: ${response.code}")
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

                // If iframeUrl is present, fetch that iframe HTML
                if (iframeUrl.isNotBlank()) {
                    try {
                        Log.d(TAG, "Fetching oEmbed iframe_url: $iframeUrl")
                        val ifReq = Request.Builder()
                            .url(iframeUrl)
                            .header("User-Agent", USER_AGENT)
                            .build()
                        val ifResp = client.newCall(ifReq).execute()
                        if (ifResp.isSuccessful) {
                            val ifHtml = ifResp.body?.string() ?: ""
                            val parsed = parseEmbedHtml(ifHtml, id, type)
                            if (parsed != null && parsed.tracks.isNotEmpty()) {
                                Log.i(TAG, "Successfully parsed ${parsed.tracks.size} tracks from oEmbed iframe. Enriching...")
                                val enrichedTracks = enrichTracksWithYouTubeData(parsed.tracks)
                                return@withContext parsed.copy(tracks = enrichedTracks)
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Error fetching iframe_url: ${e.message}")
                    }
                }

                if (title.isNotBlank()) {
                    Log.i(TAG, "Returning shell playlist from oEmbed: '$title'")
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

        // Extract playlist cover (pick highest resolution)
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
                    // Extract from artists array if present
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

                // Track cover artwork
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
