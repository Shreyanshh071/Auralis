package com.auralis.music.desktop.playback

import com.auralis.music.data.network.NewPipeDownloader
import com.auralis.music.data.network.RecordingMatches
import com.auralis.music.util.Log
import kotlinx.coroutines.Dispatchers
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
    @Volatile var quality = com.auralis.music.domain.model.AudioQuality.HIGH
        set(value) { if (field != value) { field = value; clearCache() } }
    private const val TAG = "DesktopStreamResolver"
    private const val EXTRACTION_BUDGET_MS = 8000L
    private const val MAX_ATTEMPTS = 3
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

    /** Starts NewPipe (its first extraction is slow) without waiting for it. */
    fun warmUp() {
        Thread({ runCatching { ensureInitialized() } }, "newpipe-warmup").apply { isDaemon = true }.start()
    }

    suspend fun resolve(videoId: String): String? = withContext(Dispatchers.IO) {
        val target = RecordingMatches.getMatchedVideoId(videoId) ?: videoId
        cache[target]?.let { if (System.currentTimeMillis() < it.expiresAtMs - 60_000L) return@withContext it.url }
        val started = System.currentTimeMillis()
        // YouTube now and then refuses a freshly issued stream URL (HTTP 403) that a new lookup
        // replaces with a working one. Check each URL with a two-byte request before libVLC gets
        // it, and look again (up to MAX_ATTEMPTS) when it's refused.
        for (attempt in 1..MAX_ATTEMPTS) {
            val url = extract(target) ?: break
            when (val status = probe(url)) {
                403 -> Log.w(TAG, "Stream for $target refused (HTTP 403) on attempt $attempt; looking again")
                else -> {
                    Log.d(TAG, "Resolved $target in ${System.currentTimeMillis() - started}ms (attempt $attempt, check $status)")
                    cache[target] = Cached(url, expiryOf(url))
                    return@withContext url
                }
            }
        }
        Log.w(TAG, "No playable stream for $target after ${System.currentTimeMillis() - started}ms")
        null
    }

    private fun extract(target: String): String? = try {
        ensureInitialized()
        NewPipeDownloader.withRequestBudget(EXTRACTION_BUDGET_MS) {
            val extractor = ServiceList.YouTube.getStreamExtractor("https://www.youtube.com/watch?v=$target")
            extractor.fetchPage()
            selectStream(extractor.audioStreams ?: emptyList())?.also {
                val content = it.content.orEmpty()
                val client = Regex("[?&]c=([^&]+)").find(content)?.groupValues?.get(1)
                Log.d(TAG, "Picked itag ${it.itag} ${it.format?.mimeType} ${kbps(it)}kbps for $target from ${hostOf(content)} (client $client)")
            }?.content
        }
    } catch (e: Throwable) {
        // Throwable, not Exception: a missing class (NoClassDefFoundError) must not kill playback.
        if (e is kotlinx.coroutines.CancellationException) throw e
        Log.w(TAG, "Couldn't resolve $target: ${e.javaClass.simpleName} ${e.message}")
        null
    }

    /** HTTP status for the first two bytes of [url]; -1 when the check itself couldn't run. */
    private fun probe(url: String): Int = try {
        val request = okhttp3.Request.Builder()
            .url(url)
            .header("User-Agent", PROBE_USER_AGENT)
            .header("Range", "bytes=0-1")
            .build()
        probeClient.newCall(request).execute().use { it.code }
    } catch (e: Exception) {
        -1
    }

    private val probeClient by lazy {
        com.auralis.music.data.network.NetworkClientProvider.okHttpClient.newBuilder()
            .callTimeout(4, java.util.concurrent.TimeUnit.SECONDS)
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

    private fun selectStream(streams: List<AudioStream>): AudioStream? {
        val usable = streams.filter { !it.content.isNullOrBlank() }
        // Prefer servers that haven't failed lately; a video often has only one, so fall back to it.
        val playable = usable.filter { !hostBlocked(it.content) }.ifEmpty { usable }
        // Auto-dubbed and audio-description tracks reuse the original's itags; keep the original.
        val original = playable.filter { it.audioTrackType == null || it.audioTrackType == AudioTrackType.ORIGINAL }
        val candidates = original.ifEmpty { playable }
        return if (quality == com.auralis.music.domain.model.AudioQuality.AUTO) candidates.maxByOrNull { kbps(it) }
        else candidates.minByOrNull { kotlin.math.abs(kbps(it) - quality.targetBitrate / 1000) }
    }

    private fun kbps(stream: AudioStream): Int = stream.averageBitrate.takeIf { it > 0 } ?: (stream.bitrate / 1000)

    private fun expiryOf(url: String): Long =
        Regex("expire=([0-9]+)").find(url)?.groupValues?.get(1)?.toLongOrNull()?.times(1000L)
            ?: (System.currentTimeMillis() + 4 * 3600 * 1000L)
}
