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
    private val streamCache = ConcurrentHashMap<String, CachedStream>()
    private val fingerprintCache = ConcurrentHashMap<String, CachedStream>()

    fun getSongFingerprintKey(title: String, artist: String): String {
        val normTitle = TitleCleaner.cleanTitle(title).lowercase().replace(Regex("""[^\p{L}\p{M}0-9]"""), "")
        val normArtist = TitleCleaner.cleanArtist(artist).lowercase().replace(Regex("""[^\p{L}\p{M}0-9]"""), "")
        return if (normTitle.isNotBlank()) "$normTitle|$normArtist" else ""
    }

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
        if (fingerprintKey.isBlank()) return null
        val cached = fingerprintCache[fingerprintKey] ?: return null
        if (System.currentTimeMillis() >= (cached.expiresAtMs - 60_000L)) {
            fingerprintCache.remove(fingerprintKey)
            return null
        }
        return cached.url
    }

    fun clearCache() {
        streamCache.clear()
        fingerprintCache.clear()
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
        val fpKey = getSongFingerprintKey(title, artist)
        if (fpKey.isNotBlank()) {
            fingerprintCache[fpKey] = entry
        }
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

    private val matchedVideoIdCache = ConcurrentHashMap<String, String>()

    fun getMatchedVideoId(id: String): String? = matchedVideoIdCache[id]

    @Volatile
    var isPlaybackResolving: Boolean = false
        private set

    suspend fun resolveAudioStream(videoId: String, title: String, artist: String): String? = withContext(Dispatchers.IO) {
        val t0Resolve = System.currentTimeMillis()
        // 0. Check in-memory stream cache by videoId (instant 0ms resolution)
        val cachedUrl = getCachedStream(videoId)
        if (!cachedUrl.isNullOrBlank()) {
            diagLog("[Diag-Resolver] Memory Cache HIT for $videoId ('$title') - 0ms")
            return@withContext cachedUrl
        }

        // Check fingerprint cache by title & artist (instant 0ms cross-session/cross-ID resolution)
        val fpKey = getSongFingerprintKey(title, artist)
        if (fpKey.isNotBlank()) {
            val fpCached = getCachedStreamByFingerprint(fpKey)
            if (!fpCached.isNullOrBlank()) {
                diagLog("[Diag-Resolver] Memory Fingerprint HIT for '$title' by '$artist' ($videoId) - 0ms")
                cacheStream(videoId, fpCached, title, artist)
                return@withContext fpCached
            }
        }

        val mappedId = matchedVideoIdCache[videoId]
        if (!mappedId.isNullOrBlank()) {
            val mappedCachedUrl = getCachedStream(mappedId)
            if (!mappedCachedUrl.isNullOrBlank()) {
                diagLog("[Diag-Resolver] Memory Cache HIT via mapped ID $mappedId for $videoId ('$title') - 0ms")
                cacheStream(videoId, mappedCachedUrl, title, artist)
                return@withContext mappedCachedUrl
            }
        }

        isPlaybackResolving = true
        try {
            diagLog("[Diag-Resolver] Starting stream resolution for '$title' ($videoId)")

            val isSpotifyId = videoId.startsWith("sp_") || videoId.startsWith("spotify:")

            // 1. Tier 1: Native Stream Extractor for YouTube IDs
            if (!isSpotifyId) {
                try {
                    val tNpStart = System.currentTimeMillis()
                    ensureNewPipeInitialized()
                    val nativeStream = withTimeoutOrNull(5500L) {
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
                        cacheStream(videoId, nativeStream, title, artist)
                        return@withContext nativeStream
                    } else {
                        diagLog("[Diag-Resolver] Native Extractor timed out or returned no stream in ${npMs}ms; proceeding to Tier 2 (Alternative Matcher)")
                    }
                } catch (e: Exception) {
                    diagLog("[Diag-Resolver] Native Extractor notice for $videoId ('$title'): ${e.javaClass.simpleName} - ${e.message}")
                }
            } else {
                diagLog("[Diag-Resolver] Spotify Track ID detected ($videoId) - skipping YouTube ID extractor and resolving official release")
            }

            // 2. Tier 2: Search official release / non-restricted alternative
            val tAltStart = System.currentTimeMillis()
            val altTimeoutMs = if (isSpotifyId) 5500L else 4000L
            diagLog("[Diag-Resolver] Attempting alternative search at T+${tAltStart - t0Resolve}ms (timeout=${altTimeoutMs}ms)")
            val altStream = withTimeoutOrNull(altTimeoutMs) {
                resolveNonRestrictedAlternative(title, artist, videoId)
            }
            if (!altStream.isNullOrBlank()) {
                val totalMs = System.currentTimeMillis() - t0Resolve
                diagLog("[Diag-Resolver] WINNER: Alternative Track for $videoId ('$title') in ${totalMs}ms (alt search took ${System.currentTimeMillis() - tAltStart}ms)")
                cacheStream(videoId, altStream, title, artist)
                return@withContext altStream
            }

            diagLog("[Diag-Resolver] FAILED all native stream resolution attempts for $videoId ('$title') in ${System.currentTimeMillis() - t0Resolve}ms; routing to WebView Engine")
            null
        } finally {
            isPlaybackResolving = false
        }
    }

    private suspend fun resolveNonRestrictedAlternative(title: String, artist: String, originalVideoId: String): String? {
        if (title.isBlank()) return null
        return try {
            val query = if (artist.isNotBlank() && !artist.equals("Spotify Artist", ignoreCase = true) && !title.contains(artist, ignoreCase = true)) {
                "$title $artist"
            } else {
                title
            }
            val searchClient = InnerTubeClient()
            var songs = searchClient.search(query, InnerTubeClient.FILTER_SONGS).songs
            if (songs.isEmpty() && artist.isNotBlank() && !artist.equals("Spotify Artist", ignoreCase = true)) {
                songs = searchClient.search("$title $artist").songs
            }
            if (songs.isEmpty()) {
                songs = searchClient.search(title).songs
            }

            val isSpotifyId = originalVideoId.startsWith("sp_") || originalVideoId.startsWith("spotify:")
            val filteredSongs = if (isSpotifyId) songs else songs.filter { it.id != originalVideoId }
            if (filteredSongs.isEmpty()) return null

            val dummyTarget = com.auralis.music.domain.model.Track(
                id = originalVideoId,
                title = title,
                artist = if (artist.equals("Spotify Artist", ignoreCase = true)) "" else artist
            )

            val matchResult = SpotifyTrackMatcher.findBestMatch(dummyTarget, filteredSongs, minConfidence = 60)
            val candidate = matchResult?.candidate ?: run {
                val targetLower = title.lowercase().trim()
                val artistLower = artist.lowercase().trim()
                filteredSongs.firstOrNull { s ->
                    val candTitleLower = s.title.lowercase().trim()
                    val candArtistLower = s.artist.lowercase().trim()
                    val titleMatches = candTitleLower == targetLower || candTitleLower.contains(targetLower) || targetLower.contains(candTitleLower)
                    val artistMatches = artistLower.isNotBlank() && (candArtistLower.contains(artistLower) || artistLower.contains(candArtistLower))
                    titleMatches && (artistMatches || artistLower.isBlank() || artistLower == "spotify artist")
                }
            }

            if (candidate != null) {
                matchedVideoIdCache[originalVideoId] = candidate.id
                try {
                    ensureNewPipeInitialized()
                    val streamExtractor = org.schabi.newpipe.extractor.ServiceList.YouTube.getStreamExtractor("https://www.youtube.com/watch?v=${candidate.id}")
                    streamExtractor.fetchPage()
                    val audioStreams = streamExtractor.audioStreams
                    val bestAudio = audioStreams
                        ?.filter { !it.content.isNullOrBlank() && !isHostBlacklisted(it.content) }
                        ?.maxByOrNull { it.averageBitrate }
                    val streamUrl = bestAudio?.content
                    if (!streamUrl.isNullOrBlank()) {
                        diagLog("[Diag-Resolver] Resolved alternative via NewPipe for '$title' by '$artist' -> ${candidate.id} ('${candidate.title}' by '${candidate.artist}')")
                        cacheStream(candidate.id, streamUrl, candidate.title, candidate.artist)
                        cacheStream(originalVideoId, streamUrl, title, artist)
                        return streamUrl
                    }
                } catch (e: Exception) {
                    diagLog("[Diag-Resolver] Alternative NewPipe extraction failed for ${candidate.id}: ${e.message}")
                }
            }
            null
        } catch (e: Exception) {
            diagLog("[Diag-Resolver] Alternative search failed: ${e.message}")
            null
        }
    }
}
