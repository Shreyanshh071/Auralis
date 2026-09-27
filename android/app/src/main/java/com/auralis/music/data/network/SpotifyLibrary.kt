package com.auralis.music.data.network

import android.util.Log
import com.auralis.music.domain.model.Playlist
import com.auralis.music.domain.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Handles fetching playlists and tracks directly from the signed-in user's Spotify account ([SpotifySession]).
 * Supports private playlists and Liked Songs.
 */
object SpotifyLibrary {
    private const val TAG = "SpotifyLibrary"
    const val LIKED_SONGS_ID = "liked_songs"

    data class LibraryPlaylist(
        val id: String,
        val title: String,
        val subtitle: String,
        val thumbnail: String?,
        val trackCount: Int = 0
    ) {
        val isLikedSongs: Boolean get() = id == LIKED_SONGS_ID
    }

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Fetches all playlists belonging to or followed by the signed-in user, with Liked Songs first.
     * Returns null if signed out or the request fails.
     */
    suspend fun fetchPlaylists(): List<LibraryPlaylist>? = withContext(Dispatchers.IO) {
        if (!SpotifySession.isSignedIn) return@withContext null
        val token = SpotifySession.getValidAccessToken() ?: return@withContext null

        try {
            val list = mutableListOf<LibraryPlaylist>()

            // 1. Check user's Liked Songs count
            val likedCount = fetchLikedSongsCount(token)
            if (likedCount > 0) {
                list.add(
                    LibraryPlaylist(
                        id = LIKED_SONGS_ID,
                        title = "Liked Songs",
                        subtitle = "$likedCount tracks • Auto playlist",
                        thumbnail = null,
                        trackCount = likedCount
                    )
                )
            }

            // 2. Fetch user's playlists (paginated)
            var nextUrl: String? = "https://api.spotify.com/v1/me/playlists?limit=50"
            var pages = 0
            while (!nextUrl.isNullOrBlank() && pages++ < 20) {
                val pageReq = Request.Builder()
                    .url(nextUrl)
                    .header("Authorization", "Bearer $token")
                    .header("User-Agent", SpotifySession.USER_AGENT)
                    .build()

                val pageResp = client.newCall(pageReq).execute()
                if (!pageResp.isSuccessful) {
                    Log.w(TAG, "Spotify user playlists fetch failed with HTTP ${pageResp.code}")
                    break
                }

                val body = pageResp.body?.string() ?: break
                val json = JSONObject(body)
                val (pagePlaylists, next) = parsePlaylistsJson(json)
                list.addAll(pagePlaylists)
                nextUrl = next
            }

            list
        } catch (e: Exception) {
            Log.w(TAG, "Error fetching Spotify playlists: ${e.message}")
            null
        }
    }

    private fun fetchLikedSongsCount(token: String): Int {
        return try {
            val req = Request.Builder()
                .url("https://api.spotify.com/v1/me/tracks?limit=1")
                .header("Authorization", "Bearer $token")
                .header("User-Agent", SpotifySession.USER_AGENT)
                .build()
            val resp = client.newCall(req).execute()
            if (resp.isSuccessful) {
                val json = JSONObject(resp.body?.string() ?: "")
                json.optInt("total", 0)
            } else 0
        } catch (_: Exception) {
            0
        }
    }

    /**
     * Fetches all tracks from the user's Liked Songs and enriches them with YouTube audio matching.
     */
    suspend fun fetchLikedSongsTracks(
        importer: SpotifyPlaylistImporter,
        onProgress: ((String) -> Unit)? = null
    ): List<Track> = withContext(Dispatchers.IO) {
        val token = SpotifySession.getValidAccessToken() ?: return@withContext emptyList()
        val allRawTracks = mutableListOf<Track>()
        var offset = 0
        val limit = 50
        var totalTracks = Int.MAX_VALUE
        var nextUrl: String? = "https://api.spotify.com/v1/me/tracks?limit=$limit&offset=0"

        while (!nextUrl.isNullOrBlank() && offset < totalTracks) {
            val req = Request.Builder()
                .url(nextUrl)
                .header("Authorization", "Bearer $token")
                .header("User-Agent", SpotifySession.USER_AGENT)
                .build()

            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) break
            val body = resp.body?.string() ?: break
            val json = JSONObject(body)
            totalTracks = json.optInt("total", totalTracks)
            val items = json.optJSONArray("items") ?: break

            importer.parseApiTrackItems(items, "Liked Songs", allRawTracks)
            offset += items.length()
            onProgress?.invoke("Fetching Liked Songs (${allRawTracks.size}/${if (totalTracks < Int.MAX_VALUE) totalTracks else allRawTracks.size})...")
            nextUrl = json.optString("next").takeIf { !it.isNullOrBlank() }
        }

        if (allRawTracks.isEmpty()) return@withContext emptyList()

        onProgress?.invoke("Matching ${allRawTracks.size} Liked Songs to YouTube...")
        importer.enrichTracksWithYouTubeData(allRawTracks, onProgress)
    }

    /**
     * Fetches a full playlist (even if private) using the authenticated session token,
     * then matches tracks to official YouTube audio streams.
     */
    suspend fun fetchPlaylist(
        playlistId: String,
        importer: SpotifyPlaylistImporter,
        onProgress: ((String) -> Unit)? = null
    ): Playlist? = withContext(Dispatchers.IO) {
        val token = SpotifySession.getValidAccessToken() ?: return@withContext null
        val rawPlaylist = importer.fetchPlaylistFromApi(playlistId, token, onProgress)
            ?: return@withContext null

        if (rawPlaylist.tracks.isEmpty()) return@withContext rawPlaylist

        val enriched = importer.enrichTracksWithYouTubeData(rawPlaylist.tracks, onProgress)
        rawPlaylist.copy(tracks = enriched)
    }

    /**
     * Parses a page of Spotify playlists from Spotify Web API response.
     */
    internal fun parsePlaylistsJson(json: JSONObject): Pair<List<LibraryPlaylist>, String?> {
        val list = mutableListOf<LibraryPlaylist>()
        val items = json.optJSONArray("items") ?: return Pair(emptyList(), null)

        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val id = item.optString("id").takeIf { it.isNotBlank() } ?: continue
            val title = item.optString("name", "Untitled Playlist")
            val owner = item.optJSONObject("owner")?.optString("display_name") ?: "Spotify"
            val tracksObj = item.optJSONObject("tracks")
            val trackCount = tracksObj?.optInt("total", 0) ?: 0
            val images = item.optJSONArray("images")
            val thumbnail = images?.optJSONObject(0)?.optString("url")?.takeIf { it.isNotBlank() }

            val isPublic = item.optBoolean("public", false)
            val visibilityNote = if (isPublic) "" else " (Private)"
            val subtitle = "$owner • $trackCount tracks$visibilityNote"

            list.add(
                LibraryPlaylist(
                    id = id,
                    title = title,
                    subtitle = subtitle,
                    thumbnail = thumbnail,
                    trackCount = trackCount
                )
            )
        }

        val next = json.optString("next").takeIf { !it.isNullOrBlank() }
        return Pair(list, next)
    }
}
