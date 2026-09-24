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

    val KNOWN_STUDIO_REPLACEMENTS = mapOf(
        "sBzrzS1Ag_g" to "PvM79DJ2PmM", // The Less I Know The Better (Official Video -> Studio Audio)
        "2g5xkLqIElU" to "rymYToIEL9o", // Borderline (Official Video -> Studio Audio)
        "pFptt7Cargc" to "NMRhx71bGo4", // Let It Happen (Official Video -> Studio Audio)
        "ila-hAUXR5U" to "cxKs2b5lRsA", // Flashing Lights (Official Video -> Studio Audio)
        "Co0tTeuUVhU" to "s40BTpfAELs", // Heartless (Official Video -> Studio Audio)
        "PsO6Zn4V07g" to "12hLNbXKCs4", // Stronger (Official Video -> Studio Audio)
        "LK7-_dgAVQE" to "N6_EvGT0ZfM", // Tauba Tauba (Official Video -> Studio Audio)
        "cWMxFX7QCbw" to "U4qD41gPQMU", // Softly (Official Video -> Studio Audio)
        "vX2cDW8up2g" to "0DS5jYQeiw0", // Winning Speech (Official Video -> Studio Audio)
        "XFkzRNyygfk" to "9RfVp-GhKfs", // Creep (Official Video -> Studio Audio)
        "1uYWYWPc9HU" to "nbCOAPR33ME", // Karma Police (Official Video -> Studio Audio)
        "u5CVsCnxyXg" to "7374CZQoS2Y", // No Surprises (Official Video -> Studio Audio)
        "n5h0qHwNrHk" to "6gDhsUWCHrg", // Fake Plastic Trees (Official Video -> Studio Audio)
        "QjQ_rG_c43A" to "6Zv9mSiZGBU", // No Cap (Official Video -> Studio Audio)
        "yS3vYw4oXG8" to "brXz6f3EPFM", // Prarthana (Official Video -> Studio Audio)
        "z6bEwQjU_Qc" to "mLaQwQHpP6A", // I Guess (Official Video -> Studio Audio)
        "BddP6PYo2gs" to "NJAv_7lHUIU", // Kesariya (Official Video -> Studio Audio)
        "IJq0yyWug1k" to "fsiPzT50ZiM", // Tum Hi Ho (Official Video -> Studio Audio)
        "ElZfdU54Cp8" to "YALvuUpY_b0", // Apna Bana Le (Official Video -> Studio Audio)
        "5i_Wc3uE6G0" to "zv-tbc4F818", // Zara Sa (Official Video -> Studio Audio)
        "2wVf4nUu8s8" to "XPu9ZE4Onzc", // Kya Mujhe Pyar Hai (Official Video -> Studio Audio)
        "M4-Ecx6h0tU" to "12pMB_mCBOo", // Labon Ko (Official Video -> Studio Audio)
        "z3UHfi9mpsg" to "1If9aw74Tj4", // Sunn Raha Hai (Official Video -> Studio Audio)
        "d8ITb6mZbi4" to "MEjnFgMh3qE", // Manwa Laage (Official Video -> Studio Audio)
        "h6lHUn20J5g" to "eSu6HHRn1UE", // Deewani Mastani (Official Video -> Studio Audio)
        "a18py61EcP4" to "qmBW9-fUvag", // Tajdar-e-Haram (Official Video -> Studio Audio)
        "vpO8sZdxOGI" to "3M3o3Ak1qBY", // Jeene Laga Hoon (Official Video -> Studio Audio)
        "BadBAMnPXSc" to "swcCuuQKGJ4", // Pehli Nazar Mein (Official Video -> Studio Audio)
        "cswfR85D7jM" to "HfpR4tAmI7E", // Love Me Not (Official Video -> Studio Audio)
        "tvTRZJ-4EyI" to "18_J_7v0i4k", // HUMBLE. (Official Video -> Studio Audio)
        "xFYQQPAOz7Y" to "4wOLVrGHiIU", // Lose Yourself (Official Video -> Studio Audio)
        "10z6-vQm23w" to "3Mr0pDNVms0"  // Heaven Knows I'm Miserable Now (Unofficial Cut -> 2008 Remaster Studio Audio)
    )

    val KNOWN_STUDIO_DURATIONS = mapOf(
        "4wOLVrGHiIU" to 322L, // Lose Yourself (Official Studio Audio)
        "3Mr0pDNVms0" to 216L, // Heaven Knows I'm Miserable Now (2008 Remaster)
        "HfpR4tAmI7E" to 213L, // Love Me Not (Studio Audio)
        "18_J_7v0i4k" to 177L, // HUMBLE. (Studio Audio)
        "6gDhsUWCHrg" to 290L, // Fake Plastic Trees (Studio Audio)
        "9RfVp-GhKfs" to 239L, // Creep (Studio Audio)
        "nbCOAPR33ME" to 261L, // Karma Police (Studio Audio)
        "7374CZQoS2Y" to 229L, // No Surprises (Studio Audio)
        "PvM79DJ2PmM" to 216L, // The Less I Know The Better (Studio Audio)
        "rymYToIEL9o" to 238L, // Borderline (Studio Audio)
        "NMRhx71bGo4" to 467L, // Let It Happen (Studio Audio)
        "cxKs2b5lRsA" to 238L, // Flashing Lights (Studio Audio)
        "s40BTpfAELs" to 211L, // Heartless (Studio Audio)
        "12hLNbXKCs4" to 312L  // Stronger (Studio Audio)
    )

    private val matchedVideoIdCache = ConcurrentHashMap<String, String>()

    fun getMatchedVideoId(id: String): String? = matchedVideoIdCache[id] ?: KNOWN_STUDIO_REPLACEMENTS[id]

    fun getEffectiveDurationSec(videoId: String, originalDurationSec: Long): Long {
        val matchedId = getMatchedVideoId(videoId) ?: videoId
        return KNOWN_STUDIO_DURATIONS[matchedId] ?: originalDurationSec
    }

    @Volatile
    var isPlaybackResolving: Boolean = false
        private set

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
            val mappedCachedUrl = getCachedStream(mappedId)
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
                try {
                    val tNpStart = System.currentTimeMillis()
                    ensureNewPipeInitialized()
                    val nativeStream = withTimeoutOrNull(6000L) {
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
                        diagLog("[Diag-Resolver] Native Extractor returned no stream for $actualTargetId ('$title') in ${npMs}ms; returning null for YouTubeEngine fallback")
                        return@withContext null
                    }
                } catch (e: Exception) {
                    diagLog("[Diag-Resolver] Native Extractor exception for $actualTargetId ('$title'): ${e.javaClass.simpleName} - ${e.message}; returning null for YouTubeEngine fallback")
                    return@withContext null
                }
            } else {
                diagLog("[Diag-Resolver] Spotify Track ID detected ($effectiveTargetId) - resolving official YouTube release")
                val altStream = withTimeoutOrNull(9500L) {
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
            val songs = searchClient.search(primaryQuery, InnerTubeClient.FILTER_SONGS).songs
            val allCandidates = songs.distinctBy { it.id }

            val dummyTarget = com.auralis.music.domain.model.Track(
                id = originalVideoId,
                title = title,
                artist = if (artist.equals("Spotify Artist", ignoreCase = true)) "" else artist,
                duration = duration
            )

            val scoredCandidates = allCandidates
                .filter { it.id != originalVideoId }
                .mapNotNull { cand ->
                    val score = com.auralis.music.domain.search.SearchQueryMatcher.scoreTrackCandidate(dummyTarget, cand)
                    if (score >= 40.0) cand to score else null
                }
                .sortedByDescending { it.second }
                .map { it.first }

            val bestCandidate = scoredCandidates.firstOrNull() ?: allCandidates.firstOrNull { it.id != originalVideoId }

            if (bestCandidate == null) {
                diagLog("[Diag-Resolver] No match found for track '$title' by '$artist'")
                return null
            }

            // CRITICAL: Immediately register matched YouTube ID so that YouTube Web Engine fallback
            // can play the track even if ExoPlayer extraction times out or fails!
            matchedVideoIdCache[originalVideoId] = bestCandidate.id
            diagLog("[Diag-Resolver] Registered match: Spotify '$title' ($originalVideoId) -> YouTube '${bestCandidate.title}' (${bestCandidate.id})")

            ensureNewPipeInitialized()
            val candidatesToTry = (listOf(bestCandidate) + scoredCandidates.filter { it.id != bestCandidate.id }).take(2)
            for (candidate in candidatesToTry) {
                try {
                    val streamUrl = withTimeoutOrNull(4500L) {
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
