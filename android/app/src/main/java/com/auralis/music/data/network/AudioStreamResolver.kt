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
        .connectTimeout(8000, TimeUnit.MILLISECONDS)
        .readTimeout(8000, TimeUnit.MILLISECONDS)
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
    private val streamCache = ConcurrentHashMap<String, CachedStream>()

    fun getSongFingerprintKey(title: String, artist: String): String {
        val cleanT = TitleCleaner.cleanTitle(title).lowercase().trim()
        val cleanA = TitleCleaner.cleanArtist(artist).lowercase().trim()
        return if (cleanT.isNotBlank() && cleanA.isNotBlank()) "fp:${cleanA}_${cleanT}" else ""
    }

    fun init(context: android.content.Context) {
        try {
            clearCache()
            NewPipeDownloader.init(context.cacheDir)
            PlayerJsCache.init(context)
            ensureNewPipeInitialized()
            // Asynchronous background warmup so first search/playback doesn't hit cold Rhino JS init
            kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                try {
                    ensureNewPipeInitialized()
                    PlayerJsCache.ensurePlayerJsLoaded()
                    try {
                        org.schabi.newpipe.extractor.ServiceList.YouTube.getStreamExtractor("https://www.youtube.com/watch?v=opwZ_PJ-F_E")
                    } catch (_: Throwable) {}
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

    fun getCachedStreamByFingerprint(fingerprintKey: String): String? {
        return null
    }

    fun clearCache() {
        streamCache.clear()
        matchedVideoIdCache.clear()
    }

    fun invalidateStream(videoId: String) {
        streamCache.remove(videoId)
        for (key in streamCache.keys()) {
            if (key.startsWith(videoId)) {
                streamCache.remove(key)
            }
        }
        val mapped = matchedVideoIdCache.remove(videoId)
        if (mapped != null) {
            streamCache.remove(mapped)
        }
    }

    fun cacheStream(videoId: String, url: String, title: String = "", artist: String = "") {
        val expireParam = Regex("expire=([0-9]+)").find(url)?.groupValues?.get(1)?.toLongOrNull()
        val expiresAtMs = if (expireParam != null) {
            expireParam * 1000L
        } else {
            System.currentTimeMillis() + (4 * 3600 * 1000L)
        }
        val entry = CachedStream(url, expiresAtMs)
        streamCache[videoId] = entry
    }

    fun ensureNewPipeInitialized() {
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

    private val matchedVideoIdCache = ConcurrentHashMap<String, String>()

    fun getMatchedVideoId(id: String): String? = matchedVideoIdCache[id]

    @Volatile
    var isPlaybackResolving: Boolean = false
        private set

    suspend fun resolveAudioStream(
        videoId: String,
        title: String = "",
        artist: String = "",
        quality: com.auralis.music.domain.model.AudioQuality = com.auralis.music.domain.model.AudioQuality.AUTO,
        context: android.content.Context? = null
    ): String? = withContext(Dispatchers.IO) {
        val t0Resolve = System.currentTimeMillis()
        val cacheKey = "${videoId}_${quality.name}"

        // 1. Memory Cache Check by exact video ID
        val memCached = getCachedStream(cacheKey) ?: getCachedStream(videoId)
        if (!memCached.isNullOrBlank()) {
            diagLog("[Diag-Resolver] Memory Cache HIT for $videoId ('$title') [$quality] - 0ms")
            return@withContext memCached
        }

        val mappedId = matchedVideoIdCache[videoId]
        if (!mappedId.isNullOrBlank()) {
            val mappedCachedUrl = getCachedStream(mappedId)
            if (!mappedCachedUrl.isNullOrBlank()) {
                diagLog("[Diag-Resolver] Memory Cache HIT via mapped ID $mappedId for $videoId ('$title') - 0ms")
                cacheStream(videoId, mappedCachedUrl)
                return@withContext mappedCachedUrl
            }
        }

        isPlaybackResolving = true
        try {
            diagLog("[Diag-Resolver] Starting stream resolution for '$title' ($videoId)")

            val isSpotifyId = videoId.startsWith("sp_") || videoId.startsWith("spotify:")

            // 1. Tier 1: Native Stream Extractor for exact YouTube ID
            if (!isSpotifyId) {
                try {
                    val tNpStart = System.currentTimeMillis()
                    ensureNewPipeInitialized()
                    val nativeStream = withTimeoutOrNull(6000L) {
                        val streamExtractor = org.schabi.newpipe.extractor.ServiceList.YouTube.getStreamExtractor("https://www.youtube.com/watch?v=$videoId")
                        streamExtractor.fetchPage()
                        val audioStreams = streamExtractor.audioStreams ?: emptyList()
                        val selectedAudio = selectStreamForQuality(audioStreams, quality, context)
                        selectedAudio?.content
                    }
                    val npMs = System.currentTimeMillis() - tNpStart
                    if (!nativeStream.isNullOrBlank()) {
                        val totalMs = System.currentTimeMillis() - t0Resolve
                        diagLog("[Diag-Resolver] WINNER: Native Stream Extractor for $videoId ('$title') in ${totalMs}ms [$quality, extraction took ${npMs}ms]")
                        cacheStream(cacheKey, nativeStream)
                        cacheStream(videoId, nativeStream)
                        return@withContext nativeStream
                    } else {
                        diagLog("[Diag-Resolver] Native Extractor returned no stream for $videoId ('$title') in ${npMs}ms; returning null for YouTubeEngine fallback (NEVER substituting alternative video)")
                        return@withContext null
                    }
                } catch (e: Exception) {
                    diagLog("[Diag-Resolver] Native Extractor exception for $videoId ('$title'): ${e.javaClass.simpleName} - ${e.message}; returning null for YouTubeEngine fallback (NEVER substituting alternative video)")
                    return@withContext null
                }
            } else {
                diagLog("[Diag-Resolver] Spotify Track ID detected ($videoId) - resolving official YouTube release")
                val tAltStart = System.currentTimeMillis()
                val altStream = withTimeoutOrNull(4000L) {
                    resolveNonRestrictedAlternative(title, artist, videoId, quality, context)
                }
                if (!altStream.isNullOrBlank()) {
                    val totalMs = System.currentTimeMillis() - t0Resolve
                    diagLog("[Diag-Resolver] WINNER: Alternative Track for Spotify $videoId ('$title') in ${totalMs}ms [$quality]")
                    cacheStream(cacheKey, altStream)
                    cacheStream(videoId, altStream)
                    return@withContext altStream
                }
                return@withContext null
            }
        } finally {
            isPlaybackResolving = false
        }
    }

    private suspend fun resolveNonRestrictedAlternative(
        title: String,
        artist: String,
        originalVideoId: String,
        quality: com.auralis.music.domain.model.AudioQuality = com.auralis.music.domain.model.AudioQuality.AUTO,
        context: android.content.Context? = null
    ): String? {
        if (title.isBlank()) return null
        return try {
            val cleanedTitle = TitleCleaner.cleanTitle(title)
            val cleanCoreTitle = cleanedTitle.replace(Regex("\\(.*\\)|\\[.*\\]|(?i)- (from|original|remix|audio).*"), "").trim()
            val cleanedArtist = TitleCleaner.cleanArtist(artist)
            val primaryArtist = if (cleanedArtist.isNotBlank() && !cleanedArtist.equals("Spotify Artist", ignoreCase = true)) {
                cleanedArtist.split(Regex("[,&/]|\\b(feat|ft|with)\\b", RegexOption.IGNORE_CASE)).firstOrNull()?.trim() ?: cleanedArtist
            } else ""
            val primaryQuery = if (primaryArtist.isNotBlank() && !cleanCoreTitle.contains(primaryArtist, ignoreCase = true)) {
                "$cleanCoreTitle $primaryArtist"
            } else {
                cleanCoreTitle
            }
            val searchClient = InnerTubeClient()
            val songs = searchClient.search(primaryQuery, InnerTubeClient.FILTER_SONGS).songs
            val allCandidates = songs.distinctBy { it.id }

            val dummyTarget = com.auralis.music.domain.model.Track(
                id = originalVideoId,
                title = title,
                artist = if (artist.equals("Spotify Artist", ignoreCase = true)) "" else artist
            )

            val scoredCandidates = allCandidates
                .filter { it.id != originalVideoId }
                .mapNotNull { cand ->
                    val score = com.auralis.music.domain.search.SearchQueryMatcher.scoreTrackCandidate(dummyTarget, cand)
                    if (score >= 30.0) cand to score else null
                }
                .sortedByDescending { it.second }
                .map { it.first }

            if (scoredCandidates.isEmpty()) {
                diagLog("[Diag-Resolver] No high-confidence match found for track '$title' by '$artist'")
                return null
            }

            ensureNewPipeInitialized()
            for (candidate in scoredCandidates.take(2)) {
                try {
                    val streamUrl = withTimeoutOrNull(1000L) {
                        val streamExtractor = org.schabi.newpipe.extractor.ServiceList.YouTube.getStreamExtractor("https://www.youtube.com/watch?v=${candidate.id}")
                        streamExtractor.fetchPage()
                        val audioStreams = streamExtractor.audioStreams ?: emptyList()
                        val selectedAudio = selectStreamForQuality(audioStreams, quality, context)
                        selectedAudio?.content
                    }
                    if (!streamUrl.isNullOrBlank()) {
                        diagLog("[Diag-Resolver] Resolved alternative via NewPipe for '$title' by '$artist' -> ${candidate.id} ('${candidate.title}' by '${candidate.artist}') [$quality]")
                        matchedVideoIdCache[originalVideoId] = candidate.id
                        val cacheKey = "${candidate.id}_${quality.name}"
                        cacheStream(cacheKey, streamUrl)
                        cacheStream(candidate.id, streamUrl)
                        cacheStream(originalVideoId, streamUrl)
                        return streamUrl
                    }
                } catch (e: Exception) {
                    diagLog("[Diag-Resolver] Candidate ${candidate.id} ('${candidate.title}') failed: ${e.message}; trying next candidate...")
                }
            }

            null
        } catch (e: Exception) {
            diagLog("[Diag-Resolver] Alternative search failed: ${e.message}")
            null
        }
    }

    /**
     * Selects optimal AudioStream according to the user's AudioQuality preference.
     */
    fun selectStreamForQuality(
        audioStreams: List<org.schabi.newpipe.extractor.stream.AudioStream>,
        quality: com.auralis.music.domain.model.AudioQuality,
        context: android.content.Context? = null
    ): org.schabi.newpipe.extractor.stream.AudioStream? {
        val validStreams = audioStreams.filter { !it.content.isNullOrBlank() && !isHostBlacklisted(it.content) }
        if (validStreams.isEmpty()) return null

        val isWifi = context?.let { ctx ->
            try {
                val cm = ctx.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
                val network = cm?.activeNetwork
                val caps = cm?.getNetworkCapabilities(network)
                caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true
            } catch (_: Exception) {
                true
            }
        } ?: true

        return when (quality) {
            com.auralis.music.domain.model.AudioQuality.LOW -> {
                // Minimum bitrate for mobile data saving (~48-64 kbps Opus/AAC)
                validStreams.minByOrNull { it.averageBitrate }
            }
            com.auralis.music.domain.model.AudioQuality.STANDARD -> {
                // Target ~128 kbps (AAC itag 140 or Opus itag 250)
                validStreams.minByOrNull { kotlin.math.abs(it.averageBitrate - 128_000) }
            }
            com.auralis.music.domain.model.AudioQuality.HIGH -> {
                // Highest bitrate available (~160 kbps Opus)
                validStreams.maxByOrNull { it.averageBitrate }
            }
            com.auralis.music.domain.model.AudioQuality.AUTO -> {
                if (isWifi) {
                    validStreams.maxByOrNull { it.averageBitrate }
                } else {
                    validStreams.minByOrNull { kotlin.math.abs(it.averageBitrate - 128_000) }
                }
            }
        } ?: validStreams.firstOrNull()
    }
}
