package com.auralis.music.data.network

import android.util.Log
import com.auralis.music.domain.model.Playlist
import com.auralis.music.domain.model.Track
import com.auralis.music.domain.model.TrackSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Handles fetching playlists and tracks directly from the signed-in user's Spotify account ([SpotifySession]).
 * Supports private playlists and Liked Songs.
 */
object SpotifyLibrary {
    private const val TAG = "SpotifyLibrary"
    const val LIKED_SONGS_ID = "liked_songs"

    internal fun hasMorePages(pageSize: Int, nextOffset: Int, totalCount: Int): Boolean =
        pageSize > 0 && (totalCount < 0 || nextOffset < totalCount)

    internal fun playlistCover(data: JSONObject): String? {
        fun firstUrl(sources: JSONArray?): String? {
            if (sources == null) return null
            for (index in 0 until sources.length()) {
                sources.optJSONObject(index)?.optString("url")?.takeIf { it.isNotBlank() }?.let { return it }
            }
            return null
        }

        val images = data.optJSONObject("images")
        val items = images?.optJSONArray("items")
        if (items != null) for (index in 0 until items.length()) {
            val item = items.optJSONObject(index) ?: continue
            firstUrl(item.optJSONArray("sources"))?.let { return it }
            item.optString("url").takeIf { it.isNotBlank() }?.let { return it }
        }
        return firstUrl(images?.optJSONArray("sources"))
            ?: firstUrl(data.optJSONArray("images"))
            ?: firstUrl(data.optJSONObject("coverArt")?.optJSONArray("sources"))
    }

    data class LibraryPlaylist(
        val id: String,
        val title: String,
        val subtitle: String,
        val thumbnail: String?,
        val trackCount: Int = -1,
        val ownerName: String? = null
    ) {
        val isLikedSongs: Boolean get() = id == LIKED_SONGS_ID

        fun withTrackCount(count: Int): LibraryPlaylist = copy(
            trackCount = count,
            subtitle = "${ownerName ?: "Spotify"} • $count tracks"
        )
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

            // The web-player token is accepted by Spotify's own GraphQL API. The public
            // /v1/me endpoints require OAuth scopes that this cookie-based token lacks.
            val likedCount = runCatching {
                SpotifyWebApi.query(token, "fetchLibraryTracks", SpotifyWebApi.likedSongsVariables(0, 1))
                    .optJSONObject("data")?.optJSONObject("me")?.optJSONObject("library")
                    ?.optJSONObject("tracks")?.optInt("totalCount", 0) ?: 0
            }.onFailure { Log.w(TAG, "Could not read Liked Songs count: ${it.message}") }.getOrDefault(0)
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

            var offset = 0
            while (true) {
                val response = SpotifyWebApi.query(token, "libraryV3", SpotifyWebApi.libraryVariables(offset, 50))
                val library = response.optJSONObject("data")?.optJSONObject("me")?.optJSONObject("libraryV3")
                    ?: error("Spotify libraryV3 response has no library")
                val items = library.optJSONArray("items") ?: error("Spotify libraryV3 response has no items")
                list.addAll(parseGqlPlaylists(items))
                offset += items.length()
                val totalCount = library.optInt("totalCount", -1)
                if (!hasMorePages(items.length(), offset, totalCount)) break
            }
            list.distinctBy { it.id }
        } catch (e: Exception) {
            Log.w(TAG, "Error fetching Spotify playlists: ${e.message}")
            null
        }
    }

    /**
     * Fetches all tracks from the user's Liked Songs and enriches them with YouTube audio matching.
     */
    suspend fun fetchLikedSongsTracks(
        importer: SpotifyPlaylistImporter,
        onProgress: ((String) -> Unit)? = null,
        matchTracks: Boolean = true
    ): List<Track> = withContext(Dispatchers.IO) {
        val token = SpotifySession.getValidAccessToken() ?: return@withContext emptyList()
        val allRawTracks = mutableListOf<Track>()
        var offset = 0
        try {
            while (true) {
                val response = SpotifyWebApi.query(token, "fetchLibraryTracks", SpotifyWebApi.likedSongsVariables(offset, 100))
                val tracks = response.optJSONObject("data")?.optJSONObject("me")?.optJSONObject("library")
                    ?.optJSONObject("tracks") ?: error("Spotify Liked Songs response has no tracks")
                val items = tracks.optJSONArray("items") ?: error("Spotify Liked Songs response has no items")
                parseGqlTracks(items, likedSongs = true, allRawTracks)
                offset += items.length()
                onProgress?.invoke("Fetching Liked Songs (${allRawTracks.size}/${tracks.optInt("totalCount", allRawTracks.size)})...")
                val totalCount = tracks.optInt("totalCount", -1)
                if (!hasMorePages(items.length(), offset, totalCount)) break
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error fetching Liked Songs: ${e.message}")
            return@withContext emptyList()
        }

        if (allRawTracks.isEmpty()) return@withContext emptyList()
        if (!matchTracks) return@withContext allRawTracks

        onProgress?.invoke("Matching ${allRawTracks.size} Liked Songs to YouTube...")
        importer.enrichTracksWithYouTubeData(allRawTracks, onProgress)
    }

    /** Spotify's libraryV3 wraps each playlist in an item/data pair. */
    internal fun parseGqlPlaylists(items: JSONArray): List<LibraryPlaylist> = buildList {
        for (i in 0 until items.length()) {
            val wrapper = items.optJSONObject(i)?.optJSONObject("item") ?: continue
            if (!wrapper.optString("__typename").contains("Playlist", ignoreCase = true)) continue
            val data = wrapper.optJSONObject("data") ?: continue
            if (data.optString("__typename") != "Playlist") continue
            val uri = wrapper.optString("_uri")
            if (!uri.startsWith("spotify:playlist:")) continue
            val id = uri.substringAfterLast(':')
            val owner = data.optJSONObject("ownerV2")?.optJSONObject("data")?.optString("name")
                ?.takeIf { it.isNotBlank() } ?: "Spotify"
            val count = data.optJSONObject("content")?.optInt("totalCount", -1) ?: -1
            val thumbnail = playlistCover(data)
            add(LibraryPlaylist(id, data.optString("name").ifBlank { "Untitled Playlist" },
                "$owner • ${if (count >= 0) count else "…"} tracks", thumbnail, count, owner))
        }
    }

    /** libraryV3 omits playlist item totals, so fetchPlaylist supplies the actual count. */
    suspend fun fetchTrackCount(playlistId: String): Int? = withContext(Dispatchers.IO) {
        val token = SpotifySession.getValidAccessToken() ?: return@withContext null
        try {
            SpotifyWebApi.query(token, "fetchPlaylist", SpotifyWebApi.playlistVariables(playlistId, 0, 1))
                .optJSONObject("data")?.optJSONObject("playlistV2")?.optJSONObject("content")
                ?.optInt("totalCount", -1)?.takeIf { it >= 0 }
        } catch (e: Exception) {
            Log.w(TAG, "Could not fetch Spotify playlist track count for $playlistId: ${e.message}")
            null
        }
    }

    /** Converts a fetchPlaylist or fetchLibraryTracks page to the app's import track model. */
    internal fun parseGqlTracks(items: JSONArray, likedSongs: Boolean, out: MutableList<Track>) {
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val wrapper = item.optJSONObject(if (likedSongs) "track" else "itemV2") ?: continue
            val data = wrapper.optJSONObject("data") ?: continue
            // A playlist entry can retain its old URI after Spotify relinks the
            // playable release. The nested track URI identifies the metadata above.
            val uri = data.optString("uri").takeIf { it.startsWith("spotify:track:") }
                ?: wrapper.optString("uri").takeIf { it.startsWith("spotify:track:") }
                ?: wrapper.optString("_uri")
            if (!uri.startsWith("spotify:track:")) continue
            val name = data.optString("name").takeIf { it.isNotBlank() } ?: continue
            val artists = data.optJSONObject("artists")?.optJSONArray("items")
            val artistNames = buildList {
                if (artists != null) for (a in 0 until artists.length()) {
                    val artist = artists.optJSONObject(a)?.optJSONObject("profile")?.optString("name")
                    if (!artist.isNullOrBlank()) add(artist)
                }
            }
            val album = data.optJSONObject("albumOfTrack") ?: data.optJSONObject("album")
            val imageSources = album?.optJSONObject("coverArt")?.optJSONArray("sources")
                ?: album?.optJSONArray("images")
                ?: data.optJSONObject("coverArt")?.optJSONArray("sources")
                ?: data.optJSONArray("images")
                ?: data.optJSONObject("visuals")?.optJSONObject("avatarImage")?.optJSONArray("sources")
            val image = imageSources?.optJSONObject(0)?.optString("url").orEmpty()
            val durationMs = data.optJSONObject("duration")?.optLong("totalMilliseconds", 0L)
                ?.takeIf { it > 0L }
                ?: data.optJSONObject("trackDuration")?.optLong("totalMilliseconds", 0L)
                    ?.takeIf { it > 0L }
                ?: data.optLong("durationMs", data.optLong("duration_ms", 0L))
            out.add(Track(
                id = "sp_${uri.substringAfterLast(':')}",
                title = name,
                artist = artistNames.joinToString(", ").ifBlank { "Spotify Artist" },
                album = album?.optString("name")?.takeIf { it.isNotBlank() },
                thumbnail = image,
                duration = durationMs / 1000L,
                source = TrackSource.YOUTUBE
            ))
        }
    }

    /**
     * Fetches a full playlist (even if private) using the authenticated session token,
     * then matches tracks to official YouTube audio streams.
     */
    suspend fun fetchPlaylist(
        playlistId: String,
        importer: SpotifyPlaylistImporter,
        onProgress: ((String) -> Unit)? = null,
        matchTracks: Boolean = true
    ): Playlist? = withContext(Dispatchers.IO) {
        val token = SpotifySession.getValidAccessToken() ?: return@withContext null
        try {
            val rawTracks = mutableListOf<Track>()
            var title = "Spotify Playlist"
            var description = "Imported from Spotify"
            var coverUrl: String? = null
            var offset = 0
            while (true) {
                val response = SpotifyWebApi.query(token, "fetchPlaylist", SpotifyWebApi.playlistVariables(playlistId, offset, 100))
                val playlist = response.optJSONObject("data")?.optJSONObject("playlistV2")
                    ?: error("Spotify fetchPlaylist response has no playlist")
                if (offset == 0) {
                    title = playlist.optString("name").ifBlank { title }
                    description = playlist.optString("description").ifBlank { description }
                    coverUrl = playlistCover(playlist)
                }
                val content = playlist.optJSONObject("content") ?: error("Spotify playlist has no content")
                val items = content.optJSONArray("items") ?: error("Spotify playlist has no items")
                parseGqlTracks(items, likedSongs = false, rawTracks)
                offset += items.length()
                onProgress?.invoke("Fetching tracks from Spotify (${rawTracks.size}/${content.optInt("totalCount", rawTracks.size)})...")
                val totalCount = content.optInt("totalCount", -1)
                if (!hasMorePages(items.length(), offset, totalCount)) break
            }
            val raw = Playlist("sp_$playlistId", title, description, coverUrl, rawTracks)
            if (rawTracks.isEmpty() || !matchTracks) raw
            else raw.copy(tracks = importer.enrichTracksWithYouTubeData(rawTracks, onProgress))
        } catch (e: Exception) {
            Log.w(TAG, "Error fetching private Spotify playlist: ${e.message}")
            null
        }
    }

}
