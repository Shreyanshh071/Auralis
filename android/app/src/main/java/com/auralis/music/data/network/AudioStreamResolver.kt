package com.auralis.music.data.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * High-Speed Direct Audio Stream Resolver.
 * Resolves direct audio streams with ultra-fast failover for pure native background playback in ExoPlayer.
 */
object AudioStreamResolver {

    private const val TAG = "AuralisPlayback"
    private val client = OkHttpClient.Builder()
        .connectTimeout(3000, TimeUnit.MILLISECONDS)
        .readTimeout(3000, TimeUnit.MILLISECONDS)
        .followRedirects(true)
        .build()

    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    // Host blacklisting with timestamp (clears after 5 minutes)
    private val blacklistedHosts = ConcurrentHashMap<String, Long>()
    private const val BLACKLIST_DURATION_MS = 5 * 60 * 1000L

    fun blacklistHost(host: String) {
        blacklistedHosts[host] = System.currentTimeMillis()
        try {
            Log.w(TAG, "[Resolver Blacklist] Host blacklisted for 5 min: $host")
        } catch (_: Throwable) {}
    }

    private fun isHostBlacklisted(url: String): Boolean {
        return try {
            val uri = java.net.URI(url)
            val host = uri.host ?: return false
            val time = blacklistedHosts[host] ?: return false
            if (System.currentTimeMillis() - time > BLACKLIST_DURATION_MS) {
                blacklistedHosts.remove(host)
                false
            } else {
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    private var isNewPipeInitialized = false

    private data class CachedStream(val url: String, val expiresAtMs: Long)
    private val streamCache = java.util.concurrent.ConcurrentHashMap<String, CachedStream>()

    fun init(context: android.content.Context) {
        try {
            NewPipeDownloader.init(context.cacheDir)
            ensureNewPipeInitialized()
        } catch (_: Exception) {}
    }

    fun getCachedStream(videoId: String): String? {
        val cached = streamCache[videoId] ?: return null
        if (System.currentTimeMillis() >= (cached.expiresAtMs - 60_000L)) {
            streamCache.remove(videoId)
            return null
        }
        return cached.url
    }

    fun cacheStream(videoId: String, url: String) {
        val expireParam = Regex("expire=([0-9]+)").find(url)?.groupValues?.get(1)?.toLongOrNull()
        val expiresAtMs = if (expireParam != null) {
            expireParam * 1000L
        } else {
            System.currentTimeMillis() + (4 * 3600 * 1000L)
        }
        streamCache[videoId] = CachedStream(url, expiresAtMs)
    }

    private fun ensureNewPipeInitialized() {
        if (!isNewPipeInitialized) {
            synchronized(this) {
                if (!isNewPipeInitialized) {
                    try {
                        org.schabi.newpipe.extractor.NewPipe.init(NewPipeDownloader.instance)
                        isNewPipeInitialized = true
                        Log.d(TAG, "[Resolver] NewPipeExtractor initialized successfully")
                    } catch (e: Exception) {
                        Log.e(TAG, "[Resolver] NewPipe init error: ${e.message}")
                    }
                }
            }
        }
    }

    suspend fun resolveAudioStream(videoId: String, title: String, artist: String): String? = withContext(Dispatchers.IO) {
        // 0. Check in-memory stream cache (instant 0ms resolution)
        val cachedUrl = getCachedStream(videoId)
        if (!cachedUrl.isNullOrBlank()) {
            Log.d(TAG, "[Resolver] Memory Cache HIT for $videoId ('$title') - 0ms")
            return@withContext cachedUrl
        }

        // 1. Parallel Resolution: Start fast direct check & NewPipeExtractor concurrently
        val directDeferred = async(Dispatchers.IO) {
            try {
                resolveYouTubePlayerStream(videoId)
            } catch (_: Exception) { null }
        }

        val newPipeDeferred = async(Dispatchers.IO) {
            try {
                ensureNewPipeInitialized()
                val streamExtractor = org.schabi.newpipe.extractor.ServiceList.YouTube.getStreamExtractor("https://www.youtube.com/watch?v=$videoId")
                streamExtractor.fetchPage()
                val audioStreams = streamExtractor.audioStreams
                val bestAudio = audioStreams
                    ?.filter { !it.content.isNullOrBlank() && !isHostBlacklisted(it.content) }
                    ?.maxByOrNull { it.averageBitrate }
                bestAudio?.content
            } catch (e: Exception) {
                Log.w(TAG, "[Resolver] NewPipeExtractor notice for $videoId: ${e.message}")
                null
            }
        }

        // Check if direct stream completes quickly (within 400ms)
        val fastDirect = withTimeoutOrNull(400L) { directDeferred.await() }
        if (!fastDirect.isNullOrBlank()) {
            newPipeDeferred.cancel()
            Log.d(TAG, "[Resolver] Resolved ultra-fast direct stream for $videoId ('$title')")
            cacheStream(videoId, fastDirect)
            return@withContext fastDirect
        }

        // Direct stream was not instant; await NewPipe (already calculating in parallel)
        val newPipeStream = newPipeDeferred.await()
        if (!newPipeStream.isNullOrBlank()) {
            Log.d(TAG, "[Resolver] Resolved via NewPipeExtractor for $videoId ('$title')")
            cacheStream(videoId, newPipeStream)
            return@withContext newPipeStream
        }

        // Fallback to direct stream if it finished late and was valid
        val lateDirect = directDeferred.await()
        if (!lateDirect.isNullOrBlank()) {
            Log.d(TAG, "[Resolver] Resolved via direct stream for $videoId ('$title')")
            cacheStream(videoId, lateDirect)
            return@withContext lateDirect
        }

        // 2. Track is unavailable/restricted; auto-recover with active official release
        val altStream = resolveNonRestrictedAlternative(title, artist, videoId)
        if (!altStream.isNullOrBlank()) {
            cacheStream(videoId, altStream)
            return@withContext altStream
        }

        null
    }

    private suspend fun resolveNonRestrictedAlternative(title: String, artist: String, originalVideoId: String): String? {
        if (title.isBlank()) return null
        return try {
            val query = if (artist.isNotBlank() && !title.contains(artist, ignoreCase = true)) "$title $artist" else title
            val searchClient = InnerTubeClient()
            val songs = searchClient.search(query, InnerTubeClient.FILTER_SONGS).songs

            val cleanTitle = title.lowercase().replace(Regex("[^a-z0-9 ]"), "").trim()
            val cleanArtist = artist.lowercase().replace(Regex("[^a-z0-9 ]"), "").trim()

            val candidates = songs.filter { s ->
                s.id != originalVideoId &&
                (cleanArtist.isBlank() || s.artist.lowercase().replace(Regex("[^a-z0-9 ]"), "").contains(cleanArtist)) &&
                s.title.lowercase().replace(Regex("[^a-z0-9 ]"), "").contains(cleanTitle.take(8))
            }

            for (candidate in candidates.take(3)) {
                try {
                    val direct = resolveYouTubePlayerStream(candidate.id)
                    if (!direct.isNullOrBlank()) {
                        Log.d(TAG, "[Resolver] Resolved non-restricted alternative direct stream for '$title' -> ${candidate.id}")
                        return direct
                    }
                    val streamExtractor = org.schabi.newpipe.extractor.ServiceList.YouTube.getStreamExtractor("https://www.youtube.com/watch?v=${candidate.id}")
                    streamExtractor.fetchPage()
                    val audioStreams = streamExtractor.audioStreams
                    val bestAudio = audioStreams
                        ?.filter { !it.content.isNullOrBlank() && !isHostBlacklisted(it.content) }
                        ?.maxByOrNull { it.averageBitrate }
                    val streamUrl = bestAudio?.content
                    if (!streamUrl.isNullOrBlank()) {
                        Log.d(TAG, "[Resolver] Resolved non-restricted alternative via NewPipe for '$title' -> ${candidate.id}")
                        return streamUrl
                    }
                } catch (_: Exception) {}
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "[Resolver] Alternative search failed: ${e.message}")
            null
        }
    }

    private fun resolveYouTubePlayerStream(videoId: String): String? {
        // 1. Try ANDROID_VR client (returns direct unencrypted googlevideo audio streams for exact videoId)
        try {
            val vrPayload = JSONObject().apply {
                put("videoId", videoId)
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", "ANDROID_VR")
                        put("clientVersion", "1.59.19")
                        put("deviceModel", "Quest 3")
                        put("hl", "en")
                        put("gl", "US")
                    })
                })
            }

            val req = Request.Builder()
                .url("https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
                .post(vrPayload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .header("Origin", "https://www.youtube.com")
                .build()

            val res = client.newCall(req).execute()
            if (res.isSuccessful) {
                val body = res.body?.string() ?: ""
                val json = JSONObject(body)
                val status = json.optJSONObject("playabilityStatus")?.optString("status")
                if (status == "OK") {
                    val streamingData = json.optJSONObject("streamingData")
                    val adaptiveFormats = streamingData?.optJSONArray("adaptiveFormats")
                    if (adaptiveFormats != null) {
                        var bestAudioUrl: String? = null
                        var bestBitrate = 0
                        for (i in 0 until adaptiveFormats.length()) {
                            val fmt = adaptiveFormats.getJSONObject(i)
                            val mime = fmt.optString("mimeType", "")
                            if (mime.startsWith("audio/")) {
                                val directUrl = fmt.optString("url")
                                val bitrate = fmt.optInt("bitrate", 0)
                                if (directUrl.isNotBlank() && bitrate > bestBitrate && !isHostBlacklisted(directUrl)) {
                                    bestBitrate = bitrate
                                    bestAudioUrl = directUrl
                                }
                            }
                        }
                        if (!bestAudioUrl.isNullOrBlank()) {
                            return bestAudioUrl
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        // 2. Fallback: Try IOS client
        try {
            val iosPayload = JSONObject().apply {
                put("videoId", videoId)
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", "IOS")
                        put("clientVersion", "19.29.1")
                        put("deviceModel", "iPhone14,3")
                        put("hl", "en")
                        put("gl", "US")
                    })
                })
            }

            val req = Request.Builder()
                .url("https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
                .post(iosPayload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .header("User-Agent", "com.google.ios.youtube/19.29.1 (iPhone14,3; U; CPU iOS 17_5_1 like Mac OS X; en_US)")
                .header("Origin", "https://www.youtube.com")
                .build()

            val res = client.newCall(req).execute()
            if (res.isSuccessful) {
                val body = res.body?.string() ?: ""
                val json = JSONObject(body)
                val status = json.optJSONObject("playabilityStatus")?.optString("status")
                if (status == "OK") {
                    val streamingData = json.optJSONObject("streamingData")
                    val adaptiveFormats = streamingData?.optJSONArray("adaptiveFormats")
                    if (adaptiveFormats != null) {
                        var bestAudioUrl: String? = null
                        var bestBitrate = 0
                        for (i in 0 until adaptiveFormats.length()) {
                            val fmt = adaptiveFormats.getJSONObject(i)
                            val mime = fmt.optString("mimeType", "")
                            if (mime.startsWith("audio/")) {
                                val directUrl = fmt.optString("url")
                                val bitrate = fmt.optInt("bitrate", 0)
                                if (directUrl.isNotBlank() && bitrate > bestBitrate && !isHostBlacklisted(directUrl)) {
                                    bestBitrate = bitrate
                                    bestAudioUrl = directUrl
                                }
                            }
                        }
                        if (!bestAudioUrl.isNullOrBlank()) {
                            return bestAudioUrl
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        return null
    }
}
