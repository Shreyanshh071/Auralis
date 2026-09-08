package com.auralis.music.data.repository

import com.auralis.music.data.local.dao.HistoryDao
import com.auralis.music.data.local.dao.PlayCountDao
import com.auralis.music.data.local.dao.PlaybackEventDao
import com.auralis.music.data.local.dao.TrackDao
import com.auralis.music.data.local.entity.PlaybackEventEntity
import com.auralis.music.data.local.mapper.toDomain
import com.auralis.music.data.local.mapper.toEntity
import com.auralis.music.domain.model.ArtistStat
import com.auralis.music.domain.model.SongStat
import com.auralis.music.domain.model.StatsOverview
import com.auralis.music.domain.model.Track
import com.auralis.music.domain.repository.StatsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class StatsRepositoryImpl(
    private val trackDao: TrackDao,
    private val playbackEventDao: PlaybackEventDao,
    private val historyDao: HistoryDao,
    private val playCountDao: PlayCountDao
) : StatsRepository {

    override suspend fun logPlaybackEvent(track: Track, playTimeMs: Long) = withContext(Dispatchers.IO) {
        if (track.id.isBlank()) return@withContext
        trackDao.upsertTrackPreservingFavorite(track.toEntity())
        val effectivePlayTimeMs = if (playTimeMs > 0) {
            playTimeMs
        } else {
            (track.duration.takeIf { it > 0 } ?: 198L) * 1000L
        }
        playbackEventDao.insertEvent(
            PlaybackEventEntity(
                trackId = track.id,
                timestamp = System.currentTimeMillis(),
                playTimeMs = effectivePlayTimeMs
            )
        )
    }

    override fun observeStatsOverview(fromTimestamp: Long, toTimestamp: Long): Flow<StatsOverview> {
        return combine(
            playbackEventDao.getTotalPlayTimeInRange(fromTimestamp, toTimestamp),
            playbackEventDao.getUniqueSongCountInRange(fromTimestamp, toTimestamp),
            playbackEventDao.getUniqueArtistCountInRange(fromTimestamp, toTimestamp),
            playbackEventDao.getUniqueAlbumCountInRange(fromTimestamp, toTimestamp)
        ) { totalTime, songs, artists, albums ->
            StatsOverview(
                totalPlayTimeMs = totalTime ?: 0L,
                songsCount = songs,
                artistsCount = artists,
                albumsCount = albums
            )
        }
    }

    override fun observeTopSongs(
        fromTimestamp: Long,
        toTimestamp: Long,
        limit: Int
    ): Flow<List<SongStat>> {
        return playbackEventDao.getEventsInRangeFlow(fromTimestamp, toTimestamp).map { list ->
            list.groupBy { it.track.id }
                .map { (_, events) ->
                    val first = events.first()
                    val totalDuration = events.sumOf { it.event.playTimeMs }
                    SongStat(
                        track = first.track.toDomain(),
                        playCount = events.size,
                        timeListenedMs = totalDuration
                    )
                }
                .sortedByDescending { it.timeListenedMs }
                .take(limit)
        }
    }

    override fun observeTopArtists(
        fromTimestamp: Long,
        toTimestamp: Long,
        limit: Int
    ): Flow<List<ArtistStat>> {
        return playbackEventDao.getEventsInRangeFlow(fromTimestamp, toTimestamp).map { list ->
            list.filter { it.track.artist.isNotBlank() }
                .groupBy { it.track.artist.trim() }
                .map { (artistName, events) ->
                    val distinctSongs = events.map { it.track.id }.distinct().size
                    val totalDuration = events.sumOf { it.event.playTimeMs }
                    val photo = com.auralis.music.data.network.ArtistPhotoProvider.getCachedPhoto(artistName)
                    ArtistStat(
                        name = artistName,
                        thumbnailUrl = photo,
                        songsPlayedCount = distinctSongs,
                        timeListenedMs = totalDuration
                    )
                }
                .sortedByDescending { it.timeListenedMs }
                .take(limit)
        }
    }

    override fun observeFirstEventTimestamp(): Flow<Long?> {
        return playbackEventDao.getFirstEventTimestamp()
    }

    override suspend fun seedFromHistoryIfNeeded() = withContext(Dispatchers.IO) {
        val count = playbackEventDao.getEventCount()
        val playCounts = playCountDao.getAllPlayCounts()

        // Check if we need to seed or re-seed (if existing events were legacy-squeezed into < 48 hours)
        var needsReseed = count == 0
        if (!needsReseed && playCounts.any { it.playCount.count >= 4 }) {
            val minTs = playbackEventDao.getFirstEventTimestamp().firstOrNull()
            if (minTs != null && (System.currentTimeMillis() - minTs) < 48L * 3600_000L) {
                needsReseed = true
            }
        }

        if (!needsReseed) return@withContext

        if (count > 0) {
            playbackEventDao.clearAllEvents()
        }

        val history = historyDao.getHistoryWithTracks()
        val historyMap = history.associate { it.track.id to it.history.playedAt }

        val seedEvents = mutableListOf<PlaybackEventEntity>()

        if (playCounts.isNotEmpty()) {
            playCounts.forEach { pc ->
                val track = pc.track
                val durMs = (track.duration.takeIf { it > 0 } ?: 198L) * 1000L
                val playCount = pc.playCount.count.coerceAtLeast(1)
                val anchorTime = historyMap[track.id] ?: pc.playCount.lastPlayed

                for (i in 0 until playCount) {
                    // Spread plays realistically across 1.5 to 2.5 days apart
                    // so plays naturally distribute across 1 week, 1 month, 3 months
                    val jitter = (Math.abs(track.id.hashCode().toLong()) % 12L) * 3600_000L
                    val spreadMs = if (i == 0) 0L else (i * 36L * 3600_000L) + jitter
                    val ts = anchorTime - spreadMs
                    seedEvents.add(
                        PlaybackEventEntity(
                            trackId = track.id,
                            timestamp = ts,
                            playTimeMs = durMs
                        )
                    )
                }
            }
        } else if (history.isNotEmpty()) {
            history.forEach { tuple ->
                val durMs = (tuple.track.duration.takeIf { it > 0 } ?: 198L) * 1000L
                seedEvents.add(
                    PlaybackEventEntity(
                        trackId = tuple.track.id,
                        timestamp = tuple.history.playedAt,
                        playTimeMs = durMs
                    )
                )
            }
        }

        if (seedEvents.isNotEmpty()) {
            playbackEventDao.insertEvents(seedEvents)
        }
    }
}
