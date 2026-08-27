package com.auralis.music.data.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
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
    private fun diagLog(msg: String) {
        try {
            Log.d(TAG, msg)
        } catch (_: Throwable) {}
        println("[AudioStreamResolver] $msg")
    }

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
            PlayerJsCache.init(context)
            ensureNewPipeInitialized()
            // Asynchronous background warmup so first search/playback doesn't hit cold Rhino JS init
            kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                try {
                    ensureNewPipeInitialized()
                    PlayerJsCache.ensurePlayerJsLoaded()
                } catch (_: Exception) {}
            }
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

    fun clearCache() {
        streamCache.clear()
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

    @Volatile
    var isPlaybackResolving: Boolean = false
        private set

    suspend fun resolveAudioStream(videoId: String, title: String, artist: String): String? = withContext(Dispatchers.IO) {
        val t0Resolve = System.currentTimeMillis()
        // 0. Check in-memory stream cache (instant 0ms resolution)
        val cachedUrl = getCachedStream(videoId)
        if (!cachedUrl.isNullOrBlank()) {
            diagLog("[Diag-Resolver] Memory Cache HIT for $videoId ('$title') - 0ms")
            return@withContext cachedUrl
        }

        isPlaybackResolving = true
        try {
            diagLog("[Diag-Resolver] Starting stream resolution for '$title' ($videoId)")

            // 1. Tier 1: Native Stream Extractor (Verified 206 Partial Content without CDN 403 errors)
            try {
                val tNpStart = System.currentTimeMillis()
                ensureNewPipeInitialized()
                val nativeStream = withTimeoutOrNull(3500L) {
                    val streamExtractor = org.schabi.newpipe.extractor.ServiceList.YouTube.getStreamExtractor("https://www.youtube.com/watch?v=$videoId")
                    streamExtractor.fetchPage()
                    val audioStreams = streamExtractor.audioStreams
                    val bestAudio = audioStreams
                        ?.filter { !it.content.isNullOrBlank() && !isHostBlacklisted(it.content) }
                        ?.maxByOrNull { it.averageBitrate }
                    bestAudio?.content
                }
                val npMs = System.currentTimeMillis() - tNpStart
                if (!nativeStream.isNullOrBlank()) {
                    val totalMs = System.currentTimeMillis() - t0Resolve
                    diagLog("[Diag-Resolver] WINNER: Native Stream Extractor for $videoId ('$title') in ${totalMs}ms (extraction took ${npMs}ms)")
                    cacheStream(videoId, nativeStream)
                    return@withContext nativeStream
                } else {
                    diagLog("[Diag-Resolver] Native Extractor timed out or returned no stream in ${npMs}ms; proceeding to Tier 2 (Alternative Matcher)")
                }
            } catch (e: Exception) {
                diagLog("[Diag-Resolver] Native Extractor notice for $videoId ('$title'): ${e.message}")
            }

            // 3. Tier 3: Track is unavailable/restricted; auto-recover with official release (bounded 1800ms timeout)
            val tAltStart = System.currentTimeMillis()
            diagLog("[Diag-Resolver] Primary resolution tiers failed; attempting alternative search at T+${tAltStart - t0Resolve}ms")
            val altStream = withTimeoutOrNull(1800L) {
                resolveNonRestrictedAlternative(title, artist, videoId)
            }
            if (!altStream.isNullOrBlank()) {
                val totalMs = System.currentTimeMillis() - t0Resolve
                diagLog("[Diag-Resolver] WINNER: Alternative Track for $videoId ('$title') in ${totalMs}ms (alt search took ${System.currentTimeMillis() - tAltStart}ms)")
                cacheStream(videoId, altStream)
                return@withContext altStream
            }

            // 4. Tier 4: Total native resolution capped at ~4s max before surrender to WebView fallback
            diagLog("[Diag-Resolver] FAILED all native stream resolution attempts for $videoId ('$title') in ${System.currentTimeMillis() - t0Resolve}ms; routing to WebView Engine")
            null
        } finally {
            isPlaybackResolving = false
        }
    }

    private suspend fun resolveNonRestrictedAlternative(title: String, artist: String, originalVideoId: String): String? {
        if (title.isBlank()) return null
        return try {
            val query = if (artist.isNotBlank() && !title.contains(artist, ignoreCase = true)) "$title $artist" else title
            val searchClient = InnerTubeClient()
            val songs = searchClient.search(query, InnerTubeClient.FILTER_SONGS).songs
            val filteredSongs = songs.filter { it.id != originalVideoId }

            val dummyTarget = com.auralis.music.domain.model.Track(
                id = originalVideoId,
                title = title,
                artist = artist
            )

            val matchResult = SpotifyTrackMatcher.findBestMatch(dummyTarget, filteredSongs, minConfidence = 65)
            val candidate = matchResult?.candidate ?: filteredSongs.firstOrNull { s ->
                val (targetTokens, _) = SpotifyTrackMatcher.extractCoreTokensAndVersion(title)
                val (candTokens, _) = SpotifyTrackMatcher.extractCoreTokensAndVersion(s.title)
                targetTokens.isNotEmpty() && targetTokens.intersect(candTokens).isNotEmpty()
            }

            if (candidate != null) {
                try {
                    val directUrl = InnerTubePlayerResolver.resolveStream(candidate.id)
                    if (!directUrl.isNullOrBlank()) {
                        diagLog("[Diag-Resolver] Resolved non-restricted alternative via Direct InnerTube for '$title' -> ${candidate.id}")
                        return directUrl
                    }
                    val streamExtractor = org.schabi.newpipe.extractor.ServiceList.YouTube.getStreamExtractor("https://www.youtube.com/watch?v=${candidate.id}")
                    streamExtractor.fetchPage()
                    val audioStreams = streamExtractor.audioStreams
                    val bestAudio = audioStreams
                        ?.filter { !it.content.isNullOrBlank() && !isHostBlacklisted(it.content) }
                        ?.maxByOrNull { it.averageBitrate }
                    val streamUrl = bestAudio?.content
                    if (!streamUrl.isNullOrBlank()) {
                        diagLog("[Diag-Resolver] Resolved non-restricted alternative via NewPipe for '$title' -> ${candidate.id}")
                        return streamUrl
                    }
                } catch (_: Exception) {}
            }
            null
        } catch (e: Exception) {
            diagLog("[Diag-Resolver] Alternative search failed: ${e.message}")
            null
        }
    }
}
