package com.auralis.music.data.network

import com.auralis.music.domain.model.Playlist
import com.auralis.music.domain.model.Track
import com.auralis.music.domain.model.TrackSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class YouTubeChannelInfo(
    val title: String,
    val avatarUrl: String?
)

data class YouTubePlaylistItem(
    val id: String,
    val title: String,
    val trackCount: Int,
    val thumbnail: String?
)

/**
 * Real YouTube Data API v3 Client for importing user's authentic YouTube Music playlists,
 * Liked Music ("LL"), and channel info using OAuth tokens with `youtube.readonly` scope.
 */
class YouTubeDataApiClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
) {
    private val baseUrl = "https://www.googleapis.com/youtube/v3"

    /**
     * Fetches real YouTube channel info (`channels?part=snippet&mine=true`).
     */
    suspend fun fetchChannelInfo(accessToken: String): YouTubeChannelInfo = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$baseUrl/channels?part=snippet&mine=true")
            .header("Authorization", "Bearer $accessToken")
            .header("Accept", "application/json")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: ""
                val msg = try {
                    JSONObject(errorBody).optJSONObject("error")?.optString("message")
                } catch (_: Exception) { null }
                throw RuntimeException(msg ?: "YouTube API returned HTTP ${response.code}")
            }

            val json = JSONObject(response.body?.string() ?: "{}")
            val items = json.optJSONArray("items")
            val channel = items?.optJSONObject(0) ?: throw RuntimeException("No YouTube channel found for this account")
            val snippet = channel.optJSONObject("snippet")

            val title = snippet?.optString("title", "YouTube Music User") ?: "YouTube Music User"
            val thumbs = snippet?.optJSONObject("thumbnails")
            val avatar = thumbs?.optJSONObject("high")?.optString("url")
                ?: thumbs?.optJSONObject("medium")?.optString("url")
                ?: thumbs?.optJSONObject("default")?.optString("url")

            YouTubeChannelInfo(title = title, avatarUrl = avatar)
        }
    }

    /**
     * Fetches user's playlists (`playlists?part=snippet,contentDetails&mine=true&maxResults=50`).
     */
    suspend fun fetchUserPlaylists(accessToken: String): List<YouTubePlaylistItem> = withContext(Dispatchers.IO) {
        val playlists = mutableListOf<YouTubePlaylistItem>()
        var pageToken: String? = null

        do {
            val urlBuilder = StringBuilder("$baseUrl/playlists?part=snippet,contentDetails&mine=true&maxResults=50")
            if (pageToken != null) {
                urlBuilder.append("&pageToken=$pageToken")
            }

            val request = Request.Builder()
                .url(urlBuilder.toString())
                .header("Authorization", "Bearer $accessToken")
                .header("Accept", "application/json")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string() ?: ""
                    val msg = try {
                        JSONObject(errorBody).optJSONObject("error")?.optString("message")
                    } catch (_: Exception) { null }
                    throw RuntimeException(msg ?: "YouTube API returned HTTP ${response.code}")
                }

                val json = JSONObject(response.body?.string() ?: "{}")
                val items = json.optJSONArray("items")

                if (items != null) {
                    for (i in 0 until items.length()) {
                        val item = items.optJSONObject(i) ?: continue
                        val id = item.optString("id")
                        val snippet = item.optJSONObject("snippet")
                        val contentDetails = item.optJSONObject("contentDetails")

                        val title = snippet?.optString("title", "Untitled Playlist") ?: "Untitled Playlist"
                        val count = contentDetails?.optInt("itemCount", 0) ?: 0
                        val thumbs = snippet?.optJSONObject("thumbnails")
                        val thumb = thumbs?.optJSONObject("high")?.optString("url")
                            ?: thumbs?.optJSONObject("medium")?.optString("url")
                            ?: thumbs?.optJSONObject("default")?.optString("url")

                        playlists.add(
                            YouTubePlaylistItem(
                                id = id,
                                title = title,
                                trackCount = count,
                                thumbnail = thumb
                            )
                        )
                    }
                }

                pageToken = if (json.has("nextPageToken")) json.optString("nextPageToken") else null
            }
        } while (!pageToken.isNullOrBlank() && playlists.size < 100)

        playlists
    }

    /**
     * Fetches songs/videos from a YouTube playlist ID (including "LL" for Liked Music).
     * Accurately extracts the authentic artist from `videoOwnerChannelTitle` instead of the playlist owner's name.
     */
    suspend fun fetchPlaylistTracks(accessToken: String, playlistId: String): List<Track> = withContext(Dispatchers.IO) {
        val tracks = mutableListOf<Track>()
        var pageToken: String? = null

        do {
            val urlBuilder = StringBuilder("$baseUrl/playlistItems?part=snippet,contentDetails&playlistId=$playlistId&maxResults=50")
            if (pageToken != null) {
                urlBuilder.append("&pageToken=$pageToken")
            }

            val request = Request.Builder()
                .url(urlBuilder.toString())
                .header("Authorization", "Bearer $accessToken")
                .header("Accept", "application/json")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string() ?: ""
                    val msg = try {
                        JSONObject(errorBody).optJSONObject("error")?.optString("message")
                    } catch (_: Exception) { null }
                    throw RuntimeException(msg ?: "YouTube API returned HTTP ${response.code}")
                }

                val json = JSONObject(response.body?.string() ?: "{}")
                val items = json.optJSONArray("items")

                if (items != null) {
                    for (i in 0 until items.length()) {
                        val item = items.optJSONObject(i) ?: continue
                        val snippet = item.optJSONObject("snippet")
                        val contentDetails = item.optJSONObject("contentDetails")

                        val videoId = snippet?.optJSONObject("resourceId")?.optString("videoId")
                            ?: contentDetails?.optString("videoId") ?: continue

                        val rawTitle = snippet?.optString("title", "Untitled") ?: "Untitled"
                        if (rawTitle == "Deleted video" || rawTitle == "Private video") continue

                        // Extract actual music artist:
                        // snippet.videoOwnerChannelTitle = Uploader/Artist channel (e.g., "Tame Impala", "TV Girl")
                        // snippet.channelTitle = Playlist owner (e.g. user account name)
                        var artist = snippet?.optString("videoOwnerChannelTitle")
                        if (artist.isNullOrBlank() || artist.equals("YouTube", ignoreCase = true)) {
                            if (rawTitle.contains(" - ")) {
                                val parts = rawTitle.split(" - ", limit = 2)
                                artist = parts[0].trim()
                            } else {
                                artist = snippet?.optString("channelTitle", "YouTube Music") ?: "YouTube Music"
                            }
                        }

                        // Clean - Topic suffixes (e.g. "Tame Impala - Topic" -> "Tame Impala")
                        artist = artist.replace(Regex("(?i) - Topic$"), "").trim()

                        var title = rawTitle
                        if (rawTitle.contains(" - ")) {
                            val parts = rawTitle.split(" - ", limit = 2)
                            if (parts.size == 2) {
                                val possibleArtist = parts[0].trim().replace(Regex("(?i) - Topic$"), "").trim()
                                val possibleTitle = parts[1].trim()
                                val p1Lower = possibleTitle.lowercase()
                                val isPart1MovieOrSubtitle = p1Lower.startsWith("from ") ||
                                    p1Lower.startsWith("from \"") ||
                                    p1Lower.startsWith("from '") ||
                                    p1Lower.startsWith("ost") ||
                                    TitleCleaner.extractVersion(possibleTitle) != null

                                if (!isPart1MovieOrSubtitle && (artist.isBlank() || artist == "YouTube Music" || artist.equals(possibleArtist, ignoreCase = true))) {
                                    title = possibleTitle
                                    if (artist.isBlank() || artist == "YouTube Music") {
                                        artist = possibleArtist
                                    }
                                }
                            }
                        }

                        val thumbs = snippet?.optJSONObject("thumbnails")
                        val thumb = thumbs?.optJSONObject("high")?.optString("url")
                            ?: thumbs?.optJSONObject("medium")?.optString("url")
                            ?: "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"

                        tracks.add(
                            Track(
                                id = videoId,
                                title = title,
                                artist = artist,
                                album = "YouTube Music",
                                thumbnail = thumb,
                                duration = 210L,
                                source = TrackSource.YOUTUBE
                            )
                        )
                    }
                }

                pageToken = if (json.has("nextPageToken")) json.optString("nextPageToken") else null
            }
        } while (!pageToken.isNullOrBlank() && tracks.size < 300)

        tracks
    }
}
