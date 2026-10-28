package com.auralis.music.desktop.playback

import com.auralis.music.data.network.NewPipeDownloader
import com.auralis.music.data.network.RecordingMatches
import com.auralis.music.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import java.util.concurrent.ConcurrentHashMap

/**
 * Finds the audio stream URL for a YouTube video with NewPipeExtractor, the same extractor the
 * phone's AudioStreamResolver uses first. It picks the stream the phone picks on its default
 * (Auto) quality: the original-language track at the highest bitrate.
 */
object DesktopStreamResolver {
    @Volatile var enabledClients: Set<String> = setOf("VISIONOS")
        set(value) {
            if (value.isEmpty() || value == field) return
            field = value
            org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamExtractor.setFetchIosClient("IOS" in value)
            clearCache()
        }
    @Volatile var quality = com.auralis.music.domain.model.AudioQuality.HIGH
        set(value) { if (field != value) { field = value; clearCache() } }
    private const val TAG = "DesktopStreamResolver"
    private const val EXTRACTION_BUDGET_MS = 15000L
    private const val MAX_ATTEMPTS = 2
    /** How long one extraction may take before another starts beside it. */
    private const val HEDGE_MS = 1200L
    private const val MAX_PARALLEL = 3
    // libVLC sends this user agent too (VlcAudioPlayer).
    private const val PROBE_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

    private data class Cached(val url: String, val expiresAtMs: Long)

    /** Stream servers that refused us recently (host -> when), skipped for [HOST_BLOCK_MS] like the phone. */
    private val failedHosts = ConcurrentHashMap<String, Long>()
    private const val HOST_BLOCK_MS = 5 * 60 * 1000L

    /** The server behind [url] refused or broke the stream; avoid it for a while. */
    fun markHostFailed(url: String) {
        val host = hostOf(url) ?: return
        failedHosts[host] = System.currentTimeMillis()
        Log.w(TAG, "Avoiding $host for 5 min after a playback error")
    }

    private fun hostOf(url: String): String? = runCatching { java.net.URI(url).host }.getOrNull()

    private fun hostBlocked(url: String): Boolean {
        val host = hostOf(url) ?: return false
        val since = failedHosts[host] ?: return false
        if (System.currentTimeMillis() - since > HOST_BLOCK_MS) {
            failedHosts.remove(host)
            return false
        }
        return true
    }
    private val cache = ConcurrentHashMap<String, Cached>()
    /** Videos YouTube refuses to anonymous viewers (age-restricted), so they aren't retried. */
    private val ageRestricted = ConcurrentHashMap<String, Long>()

    @Volatile private var initialized = false

    private fun ensureInitialized() {
        if (initialized) return
        synchronized(this) {
            if (!initialized) {
                NewPipe.init(NewPipeDownloader.instance)
                initialized = true
            }
        }
    }

    internal fun initializeExtractor() = ensureInitialized()

    /** Silent companion video for the exact recording; audio resolution remains independent. */
    suspend fun resolveVideo(videoId: String): String? = withContext(Dispatchers.IO) {
        if (!DesktopPlaybackMatch.isVideoId(videoId)) return@withContext null
        runCatching {
            ensureInitialized()
            NewPipeDownloader.withRequestBudget(EXTRACTION_BUDGET_MS) {
                val extractor = ServiceList.YouTube.getStreamExtractor("https://www.youtube.com/watch?v=$videoId")
                extractor.fetchPage()
                // YouTube's auto-generated "Artist - Topic" uploads are one still picture of some
                // release's cover (often another album's): not a video, and it covered the real cover.
                if (runCatching { extractor.uploaderName }.getOrNull().orEmpty().endsWith(" - Topic")) return@withRequestBudget null
                extractor.videoStreams.filter { (it.resolution.substringBefore('p').toIntOrNull() ?: 9999) <= 720 }
                    .maxByOrNull { it.resolution.substringBefore('p').toIntOrNull() ?: 0 }?.content
            }
        }.getOrNull()
    }

    /** Starts NewPipe (its first extraction is slow) without waiting for it. */
    fun warmUp() {
        Thread({ runCatching { ensureInitialized() } }, "newpipe-warmup").apply { isDaemon = true }.start()
    }

    /** Lookups under way, by video: playback, the next-song preload and the relay's refresh share one. */
    private val inFlight = ConcurrentHashMap<String, Deferred<String?>>()
    /** Lookups outlive the caller: a song skipped mid-lookup is ready when it's picked again. */
    private val lookups = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun resolve(videoId: String): String? {
        val target = RecordingMatches.getMatchedVideoId(videoId) ?: videoId
        if (!DesktopPlaybackMatch.isVideoId(target)) return null
        cache[target]?.let { if (System.currentTimeMillis() < it.expiresAtMs - 60_000L && !hostBlocked(it.url)) return it.url }
        val created = lookups.async(start = CoroutineStart.LAZY) { lookUp(target) }
        val shared = inFlight.putIfAbsent(target, created)
        if (shared != null) { created.cancel(); return shared.await() }
        created.invokeOnCompletion { inFlight.remove(target, created) }
        created.start()
        return created.await()
    }

    private suspend fun lookUp(target: String): String? = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        val testedUrls = mutableSetOf<String>()

        var extractMs = 0L
        var probeMs = 0L
        for (attempt in 1..MAX_ATTEMPTS) {
            if (ageRestricted.containsKey(target)) break
            val extractStarted = System.currentTimeMillis()
            val streams = extractHedged(target)
            extractMs += System.currentTimeMillis() - extractStarted
            if (streams.isNullOrEmpty()) {
                if (attempt < MAX_ATTEMPTS) {
                    kotlinx.coroutines.delay(400L * attempt)
                    continue
                }
                break
            }
            val candidates = rankStreams(streams, testedUrls)
            if (candidates.isEmpty()) {
                if (attempt < MAX_ATTEMPTS) {
                    kotlinx.coroutines.delay(400L * attempt)
                    continue
                }
                break
            }

            for (stream in candidates) {
                val url = stream.content ?: continue
                if (testedUrls.contains(url)) continue
                testedUrls.add(url)

                val probeStarted = System.currentTimeMillis()
                val status = probe(url)
                probeMs += System.currentTimeMillis() - probeStarted
                if (status == 403) {
                    val host = hostOf(url)
                    Log.w(TAG, "Stream for $target refused (HTTP 403) from $host on attempt $attempt; trying alternate stream")
                    markHostFailed(url)
                } else if (DesktopStreamProbe.playable(status)) {
                    val host = hostOf(url)
                    val client = Regex("[?&]c=([^&]+)").find(url)?.groupValues?.get(1)
                    Log.d(TAG, "Resolved $target in ${System.currentTimeMillis() - started}ms (extract ${extractMs}ms, check ${probeMs}ms; attempt $attempt, check $status, itag ${stream.itag} ${stream.format?.mimeType} from $host client $client)")
                    cache[target] = Cached(url, expiryOf(url))
                    return@withContext url
                } else {
                    Log.w(TAG, "Stream for $target failed its audio check ($status); trying alternate stream")
                }
            }
        }
        Log.w(TAG, "No playable stream for $target after ${System.currentTimeMillis() - started}ms")
        null
    }

    /**
     * The video's audio streams, without waiting out one slow extraction. Most finish in about a
     * second, but now and then one stalls for 10-15s (a slow YouTube response), which kept a song
     * buffering until it was picked again. So when one hasn't answered in [HEDGE_MS], another
     * starts beside it (up to [MAX_PARALLEL]), and the first good answer wins; a failed one is
     * replaced at once rather than after it ends the whole attempt.
     */
    private suspend fun extractHedged(target: String): List<AudioStream>? = hedgedStreamLookup(
        workers = lookups, hedgeMs = HEDGE_MS, budgetMs = EXTRACTION_BUDGET_MS, maxAttempts = MAX_PARALLEL,
        stop = { ageRestricted.containsKey(target) },
        onHedge = { Log.d(TAG, "Extraction for $target is slow; starting another") }
    ) { runInterruptible { extractStreams(target) }?.takeIf { it.isNotEmpty() } }

    private fun extractStreams(target: String): List<AudioStream>? = try {
        ensureInitialized()
        NewPipeDownloader.withRequestBudget(EXTRACTION_BUDGET_MS) {
            val extractor = ServiceList.YouTube.getStreamExtractor("https://www.youtube.com/watch?v=$target")
            extractor.fetchPage()
            extractor.audioStreams ?: emptyList()
        }
    } catch (e: Throwable) {
        // Throwable, not Exception: a missing class (NoClassDefFoundError) must not kill playback.
        if (e is kotlinx.coroutines.CancellationException) throw e
        // Age-restricted for anonymous viewers: retrying won't change that (playback finds another upload).
        if (e is org.schabi.newpipe.extractor.exceptions.AgeRestrictedContentException) ageRestricted[target] = System.currentTimeMillis()
        if (com.auralis.music.data.network.YouTubeBackoff.isBlockPage(e.message.orEmpty())) com.auralis.music.data.network.YouTubeBackoff.trip()
        Log.w(TAG, "Couldn't resolve $target: ${e.javaClass.simpleName} ${e.message}")
        null
    }

    /** HTTP status for the first two bytes of [url]; -1 when the check itself couldn't run. */
    private fun probe(url: String): Int = DesktopStreamProbe.check(
        probeClient, url, PROBE_USER_AGENT, LocalStreamRelay::offerFirstBlock)

    private val probeClient by lazy {
        com.auralis.music.data.network.NetworkClientProvider.okHttpClient.newBuilder()
            .connectTimeout(3000, java.util.concurrent.TimeUnit.MILLISECONDS)
            .readTimeout(3000, java.util.concurrent.TimeUnit.MILLISECONDS)
            .callTimeout(4000, java.util.concurrent.TimeUnit.MILLISECONDS)
            .build()
    }

    /** A new stream URL for [videoId], bypassing the cache (called from the relay's threads). */
    fun refreshBlocking(videoId: String): String? {
        invalidate(videoId)
        return kotlinx.coroutines.runBlocking { resolve(videoId) }
    }

    /** Forgets every looked-up stream (Settings' clear cache). */
    fun clearCache() = cache.clear()

    fun invalidate(videoId: String) {
        cache.remove(RecordingMatches.getMatchedVideoId(videoId) ?: videoId)
    }

    private fun rankStreams(streams: List<AudioStream>, excludedUrls: Set<String> = emptySet()): List<AudioStream> {
        val usable = streams.filter { stream ->
            val client = Regex("[?&]c=([^&]+)").find(stream.content.orEmpty())?.groupValues?.get(1)
            !stream.content.isNullOrBlank() && !excludedUrls.contains(stream.content) && (client !in setOf("VISIONOS","IOS") || client in enabledClients)
        }
        val unblocked = usable.filter { !hostBlocked(it.content) }
        val pool = unblocked.ifEmpty { usable }

        val original = pool.filter { it.audioTrackType == null || it.audioTrackType == AudioTrackType.ORIGINAL }
        val candidates = original.ifEmpty { pool }

        return candidates.sortedWith(
            compareByDescending<AudioStream> { !hostBlocked(it.content) }
                .thenByDescending { it.audioTrackType == null || it.audioTrackType == AudioTrackType.ORIGINAL }
                .thenByDescending {
                    if (quality == com.auralis.music.domain.model.AudioQuality.AUTO || quality == com.auralis.music.domain.model.AudioQuality.HIGH) {
                        kbps(it)
                    } else {
                        -kotlin.math.abs(kbps(it) - quality.targetBitrate / 1000)
                    }
                }
        )
    }

    private fun selectStream(streams: List<AudioStream>): AudioStream? = rankStreams(streams).firstOrNull()

    private fun kbps(stream: AudioStream): Int = stream.averageBitrate.takeIf { it > 0 } ?: (stream.bitrate / 1000)

    private fun expiryOf(url: String): Long =
        Regex("expire=([0-9]+)").find(url)?.groupValues?.get(1)?.toLongOrNull()?.times(1000L)
            ?: (System.currentTimeMillis() + 4 * 3600 * 1000L)
}
