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

    private val client = OkHttpClient.Builder().proxyAuthenticator(com.auralis.music.data.network.ContentProxy.authenticator)
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
    private val failedNativeExtractions = ConcurrentHashMap<String, Long>()
    private const val NATIVE_FAILURE_RETRY_NS = 5L * 60L * 1_000_000_000L

    internal fun rememberNativeExtractionFailure(videoId: String) {
        failedNativeExtractions[videoId] = System.nanoTime()
    }

    private fun nativeExtractionRecentlyFailed(videoId: String): Boolean {
        val failedAt = failedNativeExtractions[videoId] ?: return false
        if (System.nanoTime() - failedAt < NATIVE_FAILURE_RETRY_NS) return true
        failedNativeExtractions.remove(videoId, failedAt)
        return false
    }

    fun getSongFingerprintKey(title: String, artist: String): String {
        val cleanT = TitleCleaner.cleanTitle(title).lowercase().trim()
        val cleanA = TitleCleaner.cleanArtist(artist).lowercase().trim()
        return if (cleanT.isNotBlank() && cleanA.isNotBlank()) "fp:${cleanA}_${cleanT}" else ""
    }

    fun init(context: android.content.Context) {
        try {
            clearCache()
            matchPreferences = context.getSharedPreferences("auralis_recording_matches_v3", android.content.Context.MODE_PRIVATE)
            matchPreferences?.all?.forEach { (id, value) ->
                if ((id.startsWith("sp_") || id.startsWith("spotify:")) && value is String &&
                    value.matches(Regex("[A-Za-z0-9_-]{11}"))) {
                    matchedVideoIdCache[id] = value
                    val seconds = matchPreferences?.getLong("${id}:duration", 0L) ?: 0L
                    if (seconds > 0L) matchedDurationCache[id] = seconds
                }
            }
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
        // Download retries and cache cleanup expire URLs, not recording matches.
    }

    fun invalidateStream(videoId: String) {
        streamCache.remove(videoId)
        for (key in streamCache.keys()) {
            if (key.startsWith(videoId)) {
                streamCache.remove(key)
            }
        }
        // An expired stream URL does not invalidate the verified recording identity.
        val mapped = matchedVideoIdCache[videoId]
        if (mapped != null) {
            streamCache.remove(mapped)
            for (key in streamCache.keys()) if (key.startsWith("${mapped}_")) streamCache.remove(key)
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

    // Owned by shared RecordingMatches (search and lyrics read them on every platform).
    val KNOWN_STUDIO_REPLACEMENTS get() = RecordingMatches.KNOWN_STUDIO_REPLACEMENTS
    val KNOWN_STUDIO_DURATIONS get() = RecordingMatches.KNOWN_STUDIO_DURATIONS

    private val matchedVideoIdCache get() = RecordingMatches.matchedVideoIds
    private val matchedDurationCache get() = RecordingMatches.matchedDurations
    private var matchPreferences: android.content.SharedPreferences? = null

    fun getMatchedVideoId(id: String): String? = RecordingMatches.getMatchedVideoId(id)

    fun rememberMatchedVideoId(spotifyId: String, youtubeId: String, durationSec: Long = 0L) {
        if ((spotifyId.startsWith("sp_") || spotifyId.startsWith("spotify:")) &&
            youtubeId.isNotBlank() && !youtubeId.startsWith("sp_") && !youtubeId.startsWith("spotify:")) {
            matchedVideoIdCache[spotifyId] = youtubeId
            if (durationSec > 0L) matchedDurationCache[spotifyId] = durationSec
            matchPreferences?.edit()?.putString(spotifyId, youtubeId)?.apply {
                if (durationSec > 0L) putLong("${spotifyId}:duration", durationSec)
            }?.apply()
        }
    }

    fun getEffectiveDurationSec(videoId: String, originalDurationSec: Long): Long =
        RecordingMatches.getEffectiveDurationSec(videoId, originalDurationSec)

    @Volatile
    var isPlaybackResolving: Boolean = false
        private set

    suspend fun resolvePlaybackVideoId(track: com.auralis.music.domain.model.Track): String? {
        getMatchedVideoId(track.id)?.let { return it }
        if (!track.id.startsWith("sp_") && !track.id.startsWith("spotify:")) return track.id
        return withContext(Dispatchers.IO) {
            isPlaybackResolving = true
            try {
                val recording = SpotifyPlaylistImporter().matchToYouTube(track) ?: return@withContext null
                rememberMatchedVideoId(track.id, recording.id, recording.duration)
                recording.id
            } finally { isPlaybackResolving = false }
        }
    }

    suspend fun resolveAudioStream(
        videoId: String,
        title: String = "",
        artist: String = "",
        quality: com.auralis.music.domain.model.AudioQuality = com.auralis.music.domain.model.AudioQuality.AUTO,
        context: android.content.Context? = null,
        duration: Long = 0L
    ): String? = withContext(Dispatchers.IO) {
        val effectiveTargetId = KNOWN_STUDIO_REPLACEMENTS[videoId] ?: videoId
        if (effectiveTargetId != videoId) {
            diagLog("[Diag-Resolver] Redirecting bloated video ID $videoId -> authentic studio audio ID $effectiveTargetId for '$title'")
            matchedVideoIdCache[videoId] = effectiveTargetId
        }
        val t0Resolve = System.currentTimeMillis()
        val cacheKey = "${effectiveTargetId}_${quality.name}"

        // 1. Memory Cache Check by exact video ID
        val memCached = getCachedStream(cacheKey) ?: getCachedStream(effectiveTargetId) ?: getCachedStream(videoId)
        if (!memCached.isNullOrBlank()) {
            diagLog("[Diag-Resolver] Memory Cache HIT for $effectiveTargetId ('$title') [$quality] - 0ms")
            return@withContext memCached
        }

        val mappedId = matchedVideoIdCache[effectiveTargetId] ?: matchedVideoIdCache[videoId]
        if (!mappedId.isNullOrBlank()) {
            val mappedCachedUrl = getCachedStream("${mappedId}_${quality.name}") ?: getCachedStream(mappedId)
            if (!mappedCachedUrl.isNullOrBlank()) {
                diagLog("[Diag-Resolver] Memory Cache HIT via mapped ID $mappedId for $effectiveTargetId ('$title') - 0ms")
                cacheStream(effectiveTargetId, mappedCachedUrl)
                cacheStream(videoId, mappedCachedUrl)
                return@withContext mappedCachedUrl
            }
        }

        isPlaybackResolving = true
        try {
            diagLog("[Diag-Resolver] Starting stream resolution for '$title' ($effectiveTargetId)")

            val actualTargetId = if (!mappedId.isNullOrBlank() && !mappedId.startsWith("sp_") && !mappedId.startsWith("spotify:")) mappedId else effectiveTargetId
            val isSpotifyId = actualTargetId.startsWith("sp_") || actualTargetId.startsWith("spotify:")

            // 1. Tier 1: Native Stream Extractor for exact YouTube ID
            if (!isSpotifyId) {
                if (nativeExtractionRecentlyFailed(actualTargetId)) {
                    diagLog("[Diag-Resolver] Recent native extraction failure for $actualTargetId; using YouTubeEngine without retry delay")
                    return@withContext null
                }
                try {
                    val tNpStart = System.currentTimeMillis()
                    ensureNewPipeInitialized()
                    val nativeStream = NewPipeDownloader.withRequestBudget(6000L) {
                        val streamExtractor = org.schabi.newpipe.extractor.ServiceList.YouTube.getStreamExtractor("https://www.youtube.com/watch?v=$actualTargetId")
                        streamExtractor.fetchPage()
                        val audioStreams = streamExtractor.audioStreams ?: emptyList()
                        val selectedAudio = selectStreamForQuality(audioStreams, quality, context)
                        selectedAudio?.content
                    }
                    val npMs = System.currentTimeMillis() - tNpStart
                    if (!nativeStream.isNullOrBlank()) {
                        val totalMs = System.currentTimeMillis() - t0Resolve
                        diagLog("[Diag-Resolver] WINNER: Native Stream Extractor for $actualTargetId ('$title') in ${totalMs}ms [$quality, extraction took ${npMs}ms]")
                        cacheStream(cacheKey, nativeStream)
                        cacheStream(actualTargetId, nativeStream)
                        cacheStream(effectiveTargetId, nativeStream)
                        cacheStream(videoId, nativeStream)
                        return@withContext nativeStream
                    } else {
                        rememberNativeExtractionFailure(actualTargetId)
                        diagLog("[Diag-Resolver] Native Extractor returned no stream for $actualTargetId ('$title') in ${npMs}ms; returning null for YouTubeEngine fallback")
                        return@withContext null
                    }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    rememberNativeExtractionFailure(actualTargetId)
                    diagLog("[Diag-Resolver] Native Extractor exception for $actualTargetId ('$title'): ${e.javaClass.simpleName} - ${e.message}; returning null for YouTubeEngine fallback")
                    return@withContext null
                }
            } else {
                diagLog("[Diag-Resolver] Spotify Track ID detected ($effectiveTargetId) - resolving official YouTube release")
                val altStream = withTimeoutOrNull(11500L) {
                    resolveNonRestrictedAlternative(title, artist, effectiveTargetId, quality, context, duration)
                }
                if (!altStream.isNullOrBlank()) {
                    val totalMs = System.currentTimeMillis() - t0Resolve
                    diagLog("[Diag-Resolver] WINNER: Alternative Track for Spotify $effectiveTargetId ('$title') in ${totalMs}ms [$quality]")
                    cacheStream(cacheKey, altStream)
                    cacheStream(effectiveTargetId, altStream)
                    cacheStream(videoId, altStream)
                    return@withContext altStream
                }
                return@withContext null
            }
        } finally {
            isPlaybackResolving = false
        }
    }

    suspend fun resolveNonRestrictedAlternative(
        title: String,
        artist: String,
        originalVideoId: String,
        quality: com.auralis.music.domain.model.AudioQuality = com.auralis.music.domain.model.AudioQuality.AUTO,
        context: android.content.Context? = null,
        duration: Long = 0L
    ): String? {
        if (title.isBlank()) return null
        return try {
            val cleanedTitle = TitleCleaner.cleanTitle(title)
            val cleanCoreTitle = cleanedTitle
                .replace(Regex("""(?i)\s*-\s*\d{4}\s*remaster(ed)?.*"""), "")
                .replace(Regex("""(?i)\s*-\s*remaster(ed)?.*"""), "")
                .replace(Regex("""(?i)\s*-\s*deluxe\s*(edition|version)?.*"""), "")
                .replace(Regex("""(?i)\s*\([^)]*remaster(ed)?[^)]*\)"""), "")
                .replace(Regex("""(?i)\s*\([^)]*deluxe[^)]*\)"""), "")
                .replace(Regex("""\(.*\)|\[.*\]|(?i)- (from|original|remix|audio).*"""), "")
                .trim()
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
            val isSpotifyImport = originalVideoId.startsWith("sp_") || originalVideoId.startsWith("spotify:")
            val songs = if (isSpotifyImport) emptyList() else
                searchClient.search(primaryQuery, InnerTubeClient.FILTER_SONGS).songs
            var allCandidates = songs.distinctBy { it.id }

            val dummyTarget = com.auralis.music.domain.model.Track(
                id = originalVideoId,
                title = title,
                artist = if (artist.equals("Spotify Artist", ignoreCase = true)) "" else artist,
                duration = duration
            )

            fun ranked(candidates: List<com.auralis.music.domain.model.Track>) = candidates
                .filter { it.id != originalVideoId }
                .mapNotNull { cand ->
                    val score = com.auralis.music.domain.search.SearchQueryMatcher.scoreTrackCandidate(dummyTarget, cand)
                    if (score >= 40.0) cand to score else null
                }
                .sortedByDescending { it.second }
                .map { it.first }
            var scoredCandidates = ranked(allCandidates)
            if (scoredCandidates.isEmpty() && !isSpotifyImport) {
                // Some releases are absent from YouTube Music's Songs filter but have
                // an exact recording in general search. Keep the same version gates.
                allCandidates = (allCandidates + searchClient.search(primaryQuery).songs).distinctBy { it.id }
                scoredCandidates = ranked(allCandidates)
            }

            // No fallback to an unscored candidate: playing some other version desyncs lyrics.
            // The one exception is the same song whose lengths differ only by rounding.
            val bestCandidate = if (isSpotifyImport) {
                // Use the same artist/title fallback queries and recording gates as import.
                // The previous foreground shortcut missed releases credited to co-singers.
                SpotifyPlaylistImporter(innerTubeClient = searchClient).matchToYouTube(dummyTarget)
            } else scoredCandidates.firstOrNull()
                ?: allCandidates.firstOrNull {
                    it.id != originalVideoId &&
                        com.auralis.music.domain.search.SearchQueryMatcher.isRoundingOnlyLengthGap(dummyTarget, it)
                }

            if (bestCandidate == null) {
                diagLog("[Diag-Resolver] No match found for track '$title' by '$artist'")
                return null
            }

            // Register the selected source so the web player can use it if extraction fails.
            matchedVideoIdCache[originalVideoId] = bestCandidate.id
            rememberMatchedVideoId(originalVideoId, bestCandidate.id, bestCandidate.duration)
            if (nativeExtractionRecentlyFailed(bestCandidate.id)) return null
            ensureNewPipeInitialized()
            val candidatesToTry = (listOf(bestCandidate) + scoredCandidates.filter { it.id != bestCandidate.id }).take(2)
            for ((index, candidate) in candidatesToTry.withIndex()) {
                try {
                    val streamUrl = NewPipeDownloader.withRequestBudget(if (index == 0) 4500L else 2200L) {
                        val streamExtractor = org.schabi.newpipe.extractor.ServiceList.YouTube.getStreamExtractor("https://www.youtube.com/watch?v=${candidate.id}")
                        streamExtractor.fetchPage()
                        val audioStreams = streamExtractor.audioStreams ?: emptyList()
                        val selectedAudio = selectStreamForQuality(audioStreams, quality, context)
                        selectedAudio?.content
                    }
                    if (!streamUrl.isNullOrBlank()) {
                        diagLog("[Diag-Resolver] Resolved alternative via NewPipe for '$title' by '$artist' -> ${candidate.id} ('${candidate.title}' by '${candidate.artist}') [$quality]")
                        matchedVideoIdCache[originalVideoId] = candidate.id
                        rememberMatchedVideoId(originalVideoId, candidate.id, candidate.duration)
                        val cacheKey = "${candidate.id}_${quality.name}"
                        cacheStream(cacheKey, streamUrl)
                        cacheStream(candidate.id, streamUrl)
                        cacheStream(originalVideoId, streamUrl)
                        return streamUrl
                    }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    rememberNativeExtractionFailure(candidate.id)
                    diagLog("[Diag-Resolver] Candidate ${candidate.id} ('${candidate.title}') failed: ${e.message}; trying next candidate...")
                }
            }

            null
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
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
        val playable = audioStreams.filter { !it.content.isNullOrBlank() && !isHostBlacklisted(it.content) }
        if (playable.isEmpty()) return null

        // Auto-dubbed and audio-description tracks share the itags of the original,
        // so max-bitrate alone can land on another language. Keep the original track
        // (or untyped, single-track videos) whenever one exists.
        val originalTrack = playable.filter {
            it.audioTrackType == null || it.audioTrackType == org.schabi.newpipe.extractor.stream.AudioTrackType.ORIGINAL
        }
        val validStreams = originalTrack.ifEmpty { playable }

        val selected = when (quality) {
            com.auralis.music.domain.model.AudioQuality.LOW -> {
                // Minimum bitrate for mobile data saving (~48-64 kbps Opus/AAC)
                validStreams.minByOrNull { streamKbps(it) }
            }
            com.auralis.music.domain.model.AudioQuality.STANDARD -> {
                // Target ~128 kbps (AAC itag 140)
                validStreams.minByOrNull { kotlin.math.abs(streamKbps(it) - 128) }
            }
            // AUTO takes the best free stream on any network: Opus 251 (~160 kbps)
            // when YouTube serves it, else AAC 140.
            com.auralis.music.domain.model.AudioQuality.HIGH,
            com.auralis.music.domain.model.AudioQuality.AUTO -> {
                validStreams.maxByOrNull { streamKbps(it) }
            }
        } ?: validStreams.firstOrNull()

        selected?.let {
            diagLog(
                "[Diag-Quality] [$quality] itag=${it.itag} codec=${it.codec} ${streamKbps(it)}kbps " +
                    "track=${it.audioTrackType ?: "default"} (of ${audioStreams.size} streams, " +
                    "itags=${validStreams.map { s -> s.itag }})"
            )
        }
        return selected
    }

    /** NewPipe's averageBitrate is kbps; fall back to the DASH bitrate (bps) when it's unknown. */
    private fun streamKbps(stream: org.schabi.newpipe.extractor.stream.AudioStream): Int =
        stream.averageBitrate.takeIf { it > 0 } ?: (stream.bitrate / 1000)
}
