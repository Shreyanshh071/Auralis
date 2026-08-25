package com.auralis.music.data.repository

import com.auralis.music.data.network.LyricsClient
import com.auralis.music.domain.model.LyricsData
import com.auralis.music.domain.repository.LyricsRepository
import java.util.concurrent.ConcurrentHashMap

class LyricsRepositoryImpl(
    private val lyricsClient: LyricsClient
) : LyricsRepository {

    private val cache = ConcurrentHashMap<String, LyricsData>()

    override suspend fun getLyrics(
        title: String,
        artist: String,
        durationSec: Long?,
        forceRefresh: Boolean
    ): LyricsData? {
        val cacheKey = "$title::$artist::${durationSec ?: 0}".lowercase()

        if (!forceRefresh) {
            cache[cacheKey]?.let { return it }
        }

        val result = lyricsClient.getLyrics(title, artist, durationSec)
        if (result != null) {
            cache[cacheKey] = result
        }
        return result
    }
}
