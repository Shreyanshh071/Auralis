package com.auralis.music.domain.repository

import com.auralis.music.domain.model.ArtistStat
import com.auralis.music.domain.model.SongStat
import com.auralis.music.domain.model.StatsOverview
import com.auralis.music.domain.model.Track
import kotlinx.coroutines.flow.Flow

interface StatsRepository {
    suspend fun logPlaybackEvent(track: Track, playTimeMs: Long)
    fun observeStatsOverview(fromTimestamp: Long, toTimestamp: Long): Flow<StatsOverview>
    fun observeTopSongs(fromTimestamp: Long, toTimestamp: Long, limit: Int = 20): Flow<List<SongStat>>
    fun observeTopArtists(fromTimestamp: Long, toTimestamp: Long, limit: Int = 10): Flow<List<ArtistStat>>
    fun observeFirstEventTimestamp(): Flow<Long?>
    suspend fun seedFromHistoryIfNeeded()
}
