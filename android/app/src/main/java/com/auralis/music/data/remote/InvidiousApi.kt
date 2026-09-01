package com.auralis.music.data.remote

import com.auralis.music.data.network.NetworkClientProvider
import com.auralis.music.domain.model.Artist
import com.auralis.music.domain.model.PlaylistResult
import com.auralis.music.domain.model.SearchResults
import com.auralis.music.domain.model.Track
import com.auralis.music.domain.model.TrackSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicInteger

/**
 * Audio stream format metadata resolved from Invidious.
 */
data class InvidiousAudioStream(
    val url: String,
    val bitrate: Int,
    val container: String,
    val codec: String? = null,
    val quality: String? = null
)

/**
 * Invidious API client with multi-instance pooling, automatic failover on HTTP 429/5xx,
 * typed search, and audio stream resolution.
 */
class InvidiousApi(
    private val client: OkHttpClient = NetworkClientProvider.okHttpClient,
    private val instancePool: List<String> = DEFAULT_INSTANCES
) {
    companion object {
        val DEFAULT_INSTANCES = listOf(
            "https://inv.nadeko.net",
            "https://invidious.nerdvpn.de",
            "https://yt.artemislena.eu",
            "https://invidious.drgns.space",
            "https://invidious.jing.rocks"
        )
    }

    private val currentInstanceIndex = AtomicInteger(0)

    /**
     * Resolves the best available audio stream URL for a given YouTube video ID.
     */
    suspend fun getAudioStreamUrl(videoId: String): String? = withContext(Dispatchers.IO) {
        val streams = getAudioStreams(videoId)
        streams.maxByOrNull { it.bitrate }?.url ?: streams.firstOrNull()?.url
    }

    /**
     * Fetches audio streams for a given video ID with multi-instance failover.
     */
    suspend fun getAudioStreams(videoId: String): List<InvidiousAudioStream> = withContext(Dispatchers.IO) {
        val poolSize = instancePool.size
        if (poolSize == 0) return@withContext emptyList()

        val startIndex = currentInstanceIndex.get() % poolSize

        for (attempt in 0 until poolSize) {
            val instanceIndex = (startIndex + attempt) % poolSize
            val baseUrl = instancePool[instanceIndex].trimEnd('/')

            try {
                val url = "$baseUrl/api/v1/videos/$videoId"
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Auralis-Music/1.0.0 (Android)")
                    .header("Accept", "application/json")
                    .build()

                val response = client.newCall(request).execute()
                if (!response.isSuccessful) continue

                val body = response.body?.string() ?: continue
                val json = JSONObject(body)
                val adaptiveFormats = json.optJSONArray("adaptiveFormats") ?: continue

                val resultList = mutableListOf<InvidiousAudioStream>()
                for (i in 0 until adaptiveFormats.length()) {
                    val formatObj = adaptiveFormats.optJSONObject(i) ?: continue
                    val type = formatObj.optString("type", "")
                    val streamUrl = formatObj.optString("url", "")
                    val bitrate = formatObj.optInt("bitrate", 0)
                    val container = formatObj.optString("container", "")
                    val encoding = formatObj.optString("encoding", "")
                    val quality = formatObj.optString("qualityLabel", "")

                    if (streamUrl.isNotBlank() && type.startsWith("audio/")) {
                        resultList.add(
                            InvidiousAudioStream(
                                url = streamUrl,
                                bitrate = bitrate,
                                container = container,
                                codec = encoding,
                                quality = quality
                            )
                        )
                    }
                }

                if (resultList.isNotEmpty()) {
                    currentInstanceIndex.set(instanceIndex)
                    return@withContext resultList
                }
            } catch (_: Exception) {
                continue
            }
        }

        emptyList()
    }

    /**
     * Typed search across Invidious instances with failover.
     */
    suspend fun search(query: String, type: String = "all"): SearchResults = withContext(Dispatchers.IO) {
        val poolSize = instancePool.size
        if (poolSize == 0 || query.isBlank()) return@withContext SearchResults()

        val startIndex = currentInstanceIndex.get() % poolSize
        val encodedQuery = URLEncoder.encode(query, "UTF-8")

        for (attempt in 0 until poolSize) {
            val instanceIndex = (startIndex + attempt) % poolSize
            val baseUrl = instancePool[instanceIndex].trimEnd('/')

            try {
                val typeParam = if (type == "all") "" else "&type=$type"
                val url = "$baseUrl/api/v1/search?q=$encodedQuery$typeParam"
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Auralis-Music/1.0.0 (Android)")
                    .header("Accept", "application/json")
                    .build()

                val response = client.newCall(request).execute()
                if (!response.isSuccessful) continue

                val body = response.body?.string() ?: continue
                val array = JSONArray(body)

                val songs = mutableListOf<Track>()
                val artists = mutableListOf<Artist>()
                val playlists = mutableListOf<PlaylistResult>()

                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val itemType = item.optString("type", "")

                    when (itemType) {
                        "video" -> {
                            val videoId = item.optString("videoId")
                            val title = item.optString("title")
                            val author = item.optString("author")
                            val lengthSeconds = item.optLong("lengthSeconds", 0L)
                            val thumbnails = item.optJSONArray("videoThumbnails")
                            val thumbUrl = thumbnails?.optJSONObject(0)?.optString("url") ?: ""

                            if (videoId.isNotBlank() && title.isNotBlank()) {
                                songs.add(
                                    Track(
                                        id = videoId,
                                        title = title,
                                        artist = author,
                                        duration = lengthSeconds,
                                        thumbnail = thumbUrl,
                                        source = TrackSource.YOUTUBE,
                                        channelTitle = author
                                    )
                                )
                            }
                        }
                        "channel", "artist" -> {
                            val authorId = item.optString("authorId")
                            val author = item.optString("author")
                            val subCount = item.optString("subCount", "")
                            val thumbs = item.optJSONArray("authorThumbnails")
                            val thumbUrl = thumbs?.optJSONObject(0)?.optString("url") ?: ""

                            if (author.isNotBlank()) {
                                artists.add(
                                    Artist(
                                        id = authorId.ifBlank { author },
                                        name = author,
                                        thumbnail = thumbUrl,
                                        subscribers = subCount,
                                        query = author
                                    )
                                )
                            }
                        }
                        "playlist" -> {
                            val playlistId = item.optString("playlistId")
                            val title = item.optString("title")
                            val author = item.optString("author")
                            val videoCount = item.optInt("videoCount", 0)
                            val thumbUrl = item.optString("playlistThumbnail", "")

                            if (playlistId.isNotBlank() && title.isNotBlank()) {
                                playlists.add(
                                    PlaylistResult(
                                        id = playlistId,
                                        title = title,
                                        thumbnail = thumbUrl,
                                        author = author,
                                        trackCount = videoCount
                                    )
                                )
                            }
                        }
                    }
                }

                if (songs.isNotEmpty() || artists.isNotEmpty() || playlists.isNotEmpty()) {
                    currentInstanceIndex.set(instanceIndex)
                    return@withContext SearchResults(
                        songs = songs,
                        artists = artists,
                        playlists = playlists
                    )
                }
            } catch (_: Exception) {
                continue
            }
        }

        SearchResults()
    }
}
