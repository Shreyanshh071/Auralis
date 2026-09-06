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
        durationMs: Long? = null
    ): LyricsData? = null

    suspend fun getLyrics(
        title: String,
        artist: String,
        durationSec: Long? = null,
        videoId: String? = null,
        forceRefresh: Boolean = false,
        album: String? = null,
        channelTitle: String? = null,
        durationMs: Long? = null
    ): LyricsData? = null

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
}
