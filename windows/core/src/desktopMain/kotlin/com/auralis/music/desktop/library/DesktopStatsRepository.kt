package com.auralis.music.desktop.library

import com.auralis.music.domain.model.*
import com.auralis.music.domain.repository.StatsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Adapter for shared Library's Top, weekly and monthly shelves, backed by measured sessions. */
class DesktopStatsRepository(private val store: DesktopListeningStats) : StatsRepository {
    private fun selected(from: Long, to: Long) = store.listens.map { all -> all.filter { it.startedAt in from..to } }
    override suspend fun logPlaybackEvent(track: Track, playTimeMs: Long) {
        store.record(java.util.UUID.randomUUID().toString(), track, System.currentTimeMillis(), playTimeMs)
    }
    override fun observeTopSongs(fromTimestamp: Long, toTimestamp: Long, limit: Int): Flow<List<SongStat>> = selected(fromTimestamp, toTimestamp).map { all ->
        all.groupBy { DesktopListeningStats.songKey(it.track) }.values.map { sessions ->
            SongStat(sessions.maxBy { it.timeMs }.track, sessions.count { it.countsAsPlay }, sessions.sumOf { it.timeMs })
        }.sortedByDescending { it.timeListenedMs }.take(limit)
    }
    override fun observeTopArtists(fromTimestamp: Long, toTimestamp: Long, limit: Int): Flow<List<ArtistStat>> = selected(fromTimestamp, toTimestamp).map { all ->
        all.groupBy { it.track.artist }.map { (artist, sessions) -> ArtistStat(artist, null,
            sessions.map { DesktopListeningStats.songKey(it.track) }.distinct().size, sessions.sumOf { it.timeMs }) }
            .sortedByDescending { it.timeListenedMs }.take(limit)
    }
    override fun observeTopAlbums(fromTimestamp: Long, toTimestamp: Long, limit: Int): Flow<List<AlbumStat>> = selected(fromTimestamp, toTimestamp).map { all ->
        all.filter { !it.track.album.isNullOrBlank() }.groupBy { it.track.artist to it.track.album }.map { (album, sessions) ->
            AlbumStat(album.second.orEmpty(), album.first, sessions.first().track.thumbnail, sessions.sumOf { it.timeMs })
        }.sortedByDescending { it.timeListenedMs }.take(limit)
    }
    override fun observeStatsOverview(fromTimestamp: Long, toTimestamp: Long): Flow<StatsOverview> = selected(fromTimestamp, toTimestamp).map { all ->
        StatsOverview(all.sumOf { it.timeMs }, all.map { DesktopListeningStats.songKey(it.track) }.distinct().size,
            all.map { it.track.artist }.distinct().size, all.filter { !it.track.album.isNullOrBlank() }.map { it.track.artist to it.track.album }.distinct().size)
    }
    override fun observeFirstEventTimestamp(): Flow<Long?> = store.listens.map { it.minOfOrNull { event -> event.startedAt } }
    override suspend fun removeEstimatedListens() {} // Desktop never writes estimated/chunked listens.
    override suspend fun mergeChunkedListens() {}
    override suspend fun clearListeningStats() = store.clear()
}
