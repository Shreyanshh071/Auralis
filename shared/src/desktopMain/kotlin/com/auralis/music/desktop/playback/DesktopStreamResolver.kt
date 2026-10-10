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
    private const val TAG = "DesktopStreamResolver"
    private const val EXTRACTION_BUDGET_MS = 8000L

    private data class Cached(val url: String, val expiresAtMs: Long)
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
        try {
            ensureInitialized()
            val url = NewPipeDownloader.withRequestBudget(EXTRACTION_BUDGET_MS) {
                val extractor = ServiceList.YouTube.getStreamExtractor("https://www.youtube.com/watch?v=$target")
                extractor.fetchPage()
                selectStream(extractor.audioStreams ?: emptyList())?.also {
                    Log.d(TAG, "Picked itag ${it.itag} ${it.format?.mimeType} ${kbps(it)}kbps for $target")
                }?.content
            }
            Log.d(TAG, "Resolved $target in ${System.currentTimeMillis() - started}ms: ${url != null}")
            if (!url.isNullOrBlank()) cache[target] = Cached(url, expiryOf(url))
            url
        } catch (e: Throwable) {
            // Throwable, not Exception: a missing class (NoClassDefFoundError) must not kill playback.
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.w(TAG, "Couldn't resolve $target: ${e.javaClass.simpleName} ${e.message}")
            null
        }
    }

    fun invalidate(videoId: String) {
        cache.remove(RecordingMatches.getMatchedVideoId(videoId) ?: videoId)
    }

    private fun selectStream(streams: List<AudioStream>): AudioStream? {
        val playable = streams.filter { !it.content.isNullOrBlank() }
        // Auto-dubbed and audio-description tracks reuse the original's itags; keep the original.
        val original = playable.filter { it.audioTrackType == null || it.audioTrackType == AudioTrackType.ORIGINAL }
        return original.ifEmpty { playable }.maxByOrNull { kbps(it) }
    }

    private fun kbps(stream: AudioStream): Int = stream.averageBitrate.takeIf { it > 0 } ?: (stream.bitrate / 1000)

    private fun expiryOf(url: String): Long =
        Regex("expire=([0-9]+)").find(url)?.groupValues?.get(1)?.toLongOrNull()?.times(1000L)
            ?: (System.currentTimeMillis() + 4 * 3600 * 1000L)
}
