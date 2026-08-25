package com.auralis.music.domain.repository

import com.auralis.music.domain.model.LyricsData

interface LyricsRepository {
    suspend fun getLyrics(
        title: String,
        artist: String,
        durationSec: Long? = null,
        forceRefresh: Boolean = false
    ): LyricsData?
}
