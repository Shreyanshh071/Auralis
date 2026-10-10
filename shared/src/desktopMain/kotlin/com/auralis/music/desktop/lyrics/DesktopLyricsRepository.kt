package com.auralis.music.desktop.lyrics

import com.auralis.music.data.network.LyricsClient
import com.auralis.music.domain.lyrics.LyricsAlignmentEngine
import com.auralis.music.domain.model.LyricsData
import com.auralis.music.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * Lyrics for the desktop app: the phone's provider race (LyricsClient) and its playback
 * alignment, with an in-memory cache. The phone's repository adds a Room database on top;
 * desktop has no persistent lyrics cache yet.
 */
class DesktopLyricsRepository(private val client: LyricsClient = LyricsClient()) {
    private val memoryCache = ConcurrentHashMap<String, LyricsData>()

    fun cached(videoId: String): LyricsData? = memoryCache[videoId.lowercase()]

    /**
     * Same request the phone makes. [onInterim] gets displayable synced lyrics while the race
     * still waits for a better source; the return value is the final answer.
     */
    suspend fun getLyrics(
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
        memoryCache[key]?.let { return it }
        val started = System.currentTimeMillis()
        val result = client.getLyrics(
            title = title,
            artist = artist,
            durationSec = durationSec,
            videoId = videoId,
            album = album,
            channelTitle = channelTitle,
            durationMs = durationMs,
            audioLeadingSilenceMs = null,
            onInterim = { interim -> onInterim(align(interim, durationMs)) }
        ) ?: run {
            Log.w(TAG, "No lyrics for '$title' by '$artist' (${System.currentTimeMillis() - started}ms)")
            return null
        }
        val aligned = align(result, durationMs)
        Log.i(TAG, "Lyrics for '$title': ${aligned.provider} ${aligned.syncType}, ${aligned.lines.size} lines in ${System.currentTimeMillis() - started}ms")
        if (aligned.lines.isNotEmpty()) memoryCache[key] = aligned
        return aligned
    }

    private fun align(lyrics: LyricsData, durationMs: Long): LyricsData =
        if (durationMs > 0L) LyricsAlignmentEngine.alignToPlayback(lyrics, durationMs, null) else lyrics

    private companion object {
        const val TAG = "DesktopLyrics"
    }
}
