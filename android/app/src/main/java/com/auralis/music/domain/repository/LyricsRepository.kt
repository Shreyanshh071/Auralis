package com.auralis.music.domain.repository

import com.auralis.music.domain.model.LyricsData

interface LyricsRepository {
    suspend fun getCachedLyrics(
        title: String,
        artist: String,
        durationSec: Long? = null,
        videoId: String? = null,
        album: String? = null,
        channelTitle: String? = null,
        durationMs: Long? = null,
        audioLeadingSilenceMs: Long? = null
    ): LyricsData? = null

    suspend fun getLyrics(
        title: String,
        artist: String,
        durationSec: Long? = null,
        videoId: String? = null,
        forceRefresh: Boolean = false,
        album: String? = null,
        channelTitle: String? = null,
        durationMs: Long? = null,
        audioLeadingSilenceMs: Long? = null
    ): LyricsData? = null

    /**
     * [getLyrics], but [onInterim] may be called (on any thread) with synced lyrics while
     * the search is still waiting for a better source. The returned value is the final answer.
     */
    suspend fun getLyricsWithInterim(
        title: String,
        artist: String,
        durationSec: Long?,
        videoId: String?,
        forceRefresh: Boolean,
        album: String?,
        channelTitle: String?,
        durationMs: Long?,
        audioLeadingSilenceMs: Long?,
        onInterim: (LyricsData) -> Unit
    ): LyricsData? = getLyrics(title, artist, durationSec, videoId, forceRefresh, album, channelTitle, durationMs, audioLeadingSilenceMs)

    suspend fun getCachedLyrics(
        title: String,
        artist: String,
        durationSec: Long?,
        videoId: String?
    ): LyricsData? = getCachedLyrics(
        title = title,
        artist = artist,
        durationSec = durationSec,
        videoId = videoId,
        album = null,
        channelTitle = null,
        durationMs = null
    )

    suspend fun getLyrics(
        title: String,
        artist: String,
        durationSec: Long?,
        videoId: String?,
        forceRefresh: Boolean
    ): LyricsData? = getLyrics(
        title = title,
        artist = artist,
        durationSec = durationSec,
        videoId = videoId,
        forceRefresh = forceRefresh,
        album = null,
        channelTitle = null,
        durationMs = null
    )

    /**
     * One background attempt to find the same track's lyrics *with* line-level speaker
     * metadata. Returns (and caches) a result only when it is word-synced, master-aligned
     * and names two or more vocalists; otherwise returns null and leaves every cache as is.
     */
    suspend fun enrichSpeakerMetadata(
        title: String,
        artist: String,
        durationSec: Long? = null,
        videoId: String? = null,
        album: String? = null,
        channelTitle: String? = null,
        durationMs: Long? = null,
        audioLeadingSilenceMs: Long? = null
    ): LyricsData? = null
}
