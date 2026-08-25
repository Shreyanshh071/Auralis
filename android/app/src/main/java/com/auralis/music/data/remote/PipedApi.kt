package com.auralis.music.data.remote

import com.auralis.music.data.network.NetworkClientProvider
import com.auralis.music.domain.model.Track
import com.auralis.music.domain.model.TrackSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicInteger

/**
 * Audio stream format metadata resolved from Piped.
 */
data class PipedAudioStream(
    val url: String,
    val bitrate: Int,
    val mimeType: String,
    val codec: String? = null,
    val quality: String? = null
)

/**
 * Piped API client with multi-instance pooling, automatic failover on HTTP 429/5xx,
 * and audio stream resolution.
 */
class PipedApi(
    private val client: OkHttpClient = NetworkClientProvider.okHttpClient,
    private val instancePool: List<String> = DEFAULT_INSTANCES
) {
    companion object {
        val DEFAULT_INSTANCES = listOf(
            "https://pipedapi.kavin.rocks",
            "https://api.piped.yt",
            "https://piped-api.lunar.icu",
            "https://pipedapi.tokhmi.xyz",
            "https://pipedapi.r4fo.com",
            "https://api.piped.privacydev.net"
        )
    }

    private val currentInstanceIndex = AtomicInteger(0)

    /**
     * Resolves the best available audio stream URL for a given YouTube video ID.
     * Automatically fails over across the instance pool on HTTP errors (429, 5xx) or timeouts.
     */
    suspend fun getAudioStreamUrl(videoId: String): String? = withContext(Dispatchers.IO) {
        val streams = getAudioStreams(videoId)
        streams.maxByOrNull { it.bitrate }?.url ?: streams.firstOrNull()?.url
    }

    /**
     * Fetches all audio streams for a given video ID with multi-instance failover.
     */
    suspend fun getAudioStreams(videoId: String): List<PipedAudioStream> = withContext(Dispatchers.IO) {
        val poolSize = instancePool.size
        if (poolSize == 0) return@withContext emptyList()

        val startIndex = currentInstanceIndex.get() % poolSize

        for (attempt in 0 until poolSize) {
            val instanceIndex = (startIndex + attempt) % poolSize
            val baseUrl = instancePool[instanceIndex].trimEnd('/')

            try {
                val url = "$baseUrl/streams/$videoId"
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Auralis-Music/2.0.0 (Android)")
                    .header("Accept", "application/json")
                    .build()

                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    // Failover on rate limiting (429), server errors (5xx), or client errors (4xx)
                    continue
                }

                val body = response.body?.string() ?: continue
                val json = JSONObject(body)
                val audioStreamsArray = json.optJSONArray("audioStreams") ?: continue

                val resultList = mutableListOf<PipedAudioStream>()
                for (i in 0 until audioStreamsArray.length()) {
                    val streamObj = audioStreamsArray.optJSONObject(i) ?: continue
                    val streamUrl = streamObj.optString("url")
                    val bitrate = streamObj.optInt("bitrate", 0)
                    val mimeType = streamObj.optString("mimeType", "")
                    val codec = streamObj.optString("codec", "")
                    val quality = streamObj.optString("quality", "")

                    if (streamUrl.isNotBlank()) {
                        resultList.add(
                            PipedAudioStream(
                                url = streamUrl,
                                bitrate = bitrate,
                                mimeType = mimeType,
                                codec = codec,
                                quality = quality
                            )
                        )
                    }
                }

                if (resultList.isNotEmpty()) {
                    // Update preferred instance on success
                    currentInstanceIndex.set(instanceIndex)
                    return@withContext resultList
                }
            } catch (_: Exception) {
                // Connection failed or timed out; try next instance
                continue
            }
        }

        emptyList()
    }

    /**
     * Searches Piped for music tracks matching the given query with failover.
     */
    suspend fun searchSongs(query: String): List<Track> = withContext(Dispatchers.IO) {
        val poolSize = instancePool.size
        if (poolSize == 0 || query.isBlank()) return@withContext emptyList()

        val startIndex = currentInstanceIndex.get() % poolSize
        val encodedQuery = URLEncoder.encode(query, "UTF-8")

        for (attempt in 0 until poolSize) {
            val instanceIndex = (startIndex + attempt) % poolSize
            val baseUrl = instancePool[instanceIndex].trimEnd('/')

            try {
                val url = "$baseUrl/search?q=$encodedQuery&filter=music_songs"
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Auralis-Music/2.0.0 (Android)")
                    .header("Accept", "application/json")
                    .build()

                val response = client.newCall(request).execute()
                if (!response.isSuccessful) continue

                val body = response.body?.string() ?: continue
                val json = JSONObject(body)
                val items = json.optJSONArray("items") ?: continue

                val tracks = mutableListOf<Track>()
                for (i in 0 until items.length()) {
                    val item = items.optJSONObject(i) ?: continue
                    val rawUrl = item.optString("url", "")
                    val id = if (rawUrl.startsWith("/watch?v=")) rawUrl.removePrefix("/watch?v=") else rawUrl
                    val title = item.optString("title", "")
                    val uploader = item.optString("uploaderName", "")
                    val duration = item.optLong("duration", 0L)
                    val thumbnail = item.optString("thumbnail", "")

                    if (id.isNotBlank() && title.isNotBlank()) {
                        tracks.add(
                            Track(
                                id = id,
                                title = title,
                                artist = uploader,
                                duration = duration,
                                thumbnail = thumbnail,
                                source = TrackSource.YOUTUBE,
                                channelTitle = uploader
                            )
                        )
                    }
                }

                if (tracks.isNotEmpty()) {
                    currentInstanceIndex.set(instanceIndex)
                    return@withContext tracks
                }
            } catch (_: Exception) {
                continue
            }
        }

        emptyList()
    }
}
