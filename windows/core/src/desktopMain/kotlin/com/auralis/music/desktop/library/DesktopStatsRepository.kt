package com.auralis.music.desktop.library

import com.auralis.music.domain.model.*
import com.auralis.music.domain.repository.StatsRepository
import com.auralis.music.domain.stats.ArtistCredits
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/** Adapter for shared Library's Top, weekly and monthly shelves, backed by measured sessions. */
class DesktopStatsRepository(private val store: DesktopListeningStats) : StatsRepository {
    private fun selected(from: Long, to: Long) = store.listens.map { all ->
        val selected = all.filter { it.startedAt in from..to }
        val ids = com.auralis.music.data.network.YouTubeShortsFilter.filter(selected.map { it.track }.distinctBy { it.id }).map { it.id }.toSet()
        selected.filter { it.track.id in ids }
    }
    // Recounted when a label upload's real artists arrive.
    private fun selectedForArtists(from: Long, to: Long) =
        combine(selected(from, to), com.auralis.music.data.network.OfficialArtistResolver.revision) { list, _ -> list }
    override suspend fun logPlaybackEvent(track: Track, playTimeMs: Long) {
        store.record(java.util.UUID.randomUUID().toString(), track, System.currentTimeMillis(), playTimeMs)
        com.auralis.music.data.network.OfficialArtistResolver.request(track)
    }
    override fun observeTopSongs(fromTimestamp: Long, toTimestamp: Long, limit: Int): Flow<List<SongStat>> = selected(fromTimestamp, toTimestamp).map { all ->
        all.groupBy { DesktopListeningStats.songKey(it.track) }.values.map { sessions ->
            SongStat(sessions.maxBy { it.timeMs }.track, sessions.count { it.countsAsPlay }, sessions.sumOf { it.timeMs })
        }.sortedByDescending { it.timeListenedMs }.take(limit)
    }
    override fun observeTopArtists(fromTimestamp: Long, toTimestamp: Long, limit: Int): Flow<List<ArtistStat>> = selectedForArtists(fromTimestamp, toTimestamp).map { all ->
        // A collab counts in full for each artist on it, not as one artist named after the credit line.
        DesktopListeningStats.artistGroups(all, store.artistLists).take(limit).map { (name, sessions) ->
            ArtistStat(name, null, sessions.map { DesktopListeningStats.songKey(it.track) }.distinct().size, sessions.sumOf { it.timeMs })
        }
    }
    override fun observeTopAlbums(fromTimestamp: Long, toTimestamp: Long, limit: Int): Flow<List<AlbumStat>> = selected(fromTimestamp, toTimestamp).map { all ->
        all.filter { !it.track.album.isNullOrBlank() }.groupBy { it.track.artist to it.track.album }.map { (album, sessions) ->
            AlbumStat(album.second.orEmpty(), album.first, sessions.first().track.thumbnail, sessions.sumOf { it.timeMs })
        }.sortedByDescending { it.timeListenedMs }.take(limit)
    }
    override fun observeStatsOverview(fromTimestamp: Long, toTimestamp: Long): Flow<StatsOverview> = selectedForArtists(fromTimestamp, toTimestamp).map { all ->
        StatsOverview(all.sumOf { it.timeMs }, all.map { DesktopListeningStats.songKey(it.track) }.distinct().size,
            store.artistLists.let { known -> all.flatMap { DesktopListeningStats.credits(it.track, known) } }.mapNotNull(ArtistCredits::key).distinct().size, all.filter { !it.track.album.isNullOrBlank() }.map { it.track.artist to it.track.album }.distinct().size)
    }
    override fun observeFirstEventTimestamp(): Flow<Long?> = store.listens.map { it.minOfOrNull { event -> event.startedAt } }
    override suspend fun removeEstimatedListens() {} // Desktop never writes estimated/chunked listens.
    override suspend fun mergeChunkedListens() {}
    override suspend fun clearListeningStats() = store.clear()
}
