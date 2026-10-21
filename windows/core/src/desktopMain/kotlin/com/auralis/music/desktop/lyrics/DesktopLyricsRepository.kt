package com.auralis.music.desktop.lyrics

import com.auralis.music.data.network.LyricsClient
import com.auralis.music.domain.lyrics.LyricsAlignmentEngine
import com.auralis.music.domain.model.LyricsData
import com.auralis.music.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * Lyrics for the desktop app: the phone's provider race (LyricsClient) and its playback
 * alignment, cached in memory and on disk ([LyricsDiskCache]; the phone uses Room). Like the
 * phone's cache it keeps the provider's answer and aligns it to the stream on every read, and a
 * word-synced entry is never replaced by a lower tier.
 */
class DesktopLyricsRepository(
    private val disk: LyricsDiskCache? = null,
    private val client: LyricsClient = LyricsClient()
) {
    companion object {
        @Volatile var preferredProviders: List<com.auralis.music.domain.model.LyricsProvider> = emptyList()
    }
    private val memory = ConcurrentHashMap<String, LyricsData>()

    /** Cached lyrics for [videoId], aligned to [durationMs]; null when there are none. */
    fun cached(videoId: String, durationMs: Long): LyricsData? {
        val key = videoId.lowercase()
        val raw = memory[key] ?: disk?.read(key)?.also { memory[key] = it } ?: return null
        return align(raw, durationMs)
    }

    /**
     * Same request the phone makes. [onInterim] gets displayable synced lyrics while the race
     * still waits for a better source; the return value is the final answer (or the cached copy
     * when the network has nothing better).
     */
    suspend fun fetch(
        videoId: String,
        title: String,
        artist: String,
        album: String?,
        channelTitle: String?,
        durationSec: Long,
        durationMs: Long,
        onInterim: (LyricsData) -> Unit
    ): LyricsData? {
        val key = videoId.lowercase()
        val started = System.currentTimeMillis()
        var preferred: LyricsData? = null
        val deadline = System.currentTimeMillis() + 15_000
        for (provider in preferredProviders.filter { LyricsClient.isProviderEnabled(it) }) {
            if (System.currentTimeMillis() >= deadline) break
            preferred = client.searchProvider(provider,title,artist,durationSec,videoId,album,channelTitle,durationMs)
            if (preferred != null && preferred.lines.isNotEmpty()) break
        }
        val result = preferred ?: client.getLyrics(
            title = title,
            artist = artist,
            durationSec = durationSec,
            videoId = videoId,
            album = album,
            channelTitle = channelTitle,
            durationMs = durationMs,
            audioLeadingSilenceMs = null,
            onInterim = { interim -> onInterim(align(interim, durationMs)) }
        )
        val existing = memory[key] ?: disk?.read(key)
        if (result == null || result.lines.isEmpty()) {
            Log.w(TAG, "No lyrics from the network for '$title' by '$artist' (${System.currentTimeMillis() - started}ms)")
            return existing?.let { align(it, durationMs) }
        }
        if (existing != null && LyricsClient.tierOf(existing) > LyricsClient.tierOf(result)) {
            Log.i(TAG, "Keeping cached ${existing.provider} ${existing.syncType} for '$title' over ${result.provider} ${result.syncType}")
            return align(existing, durationMs)
        }
        memory[key] = result
        disk?.write(key, result)
        val aligned = align(result, durationMs)
        Log.i(TAG, "Lyrics for '$title': ${aligned.provider} ${aligned.syncType}, ${aligned.lines.size} lines in ${System.currentTimeMillis() - started}ms")
        return aligned
    }

    private fun align(lyrics: LyricsData, durationMs: Long): LyricsData =
        if (durationMs > 0L) LyricsAlignmentEngine.alignToPlayback(lyrics, durationMs, null) else lyrics

    private val TAG = "DesktopLyrics"
}
