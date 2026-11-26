package com.auralis.music.data.repository

import com.auralis.music.data.local.dao.HistoryDao
import com.auralis.music.data.local.dao.PlayCountDao
import com.auralis.music.data.local.dao.PlaybackEventDao
import com.auralis.music.data.local.dao.PlaybackEventWithTrackTuple
import com.auralis.music.data.local.dao.TrackDao
import com.auralis.music.data.local.entity.PlaybackEventEntity
import com.auralis.music.data.local.mapper.toDomain
import com.auralis.music.data.local.mapper.toEntity
import com.auralis.music.domain.model.AlbumStat
import com.auralis.music.domain.model.ArtistStat
import com.auralis.music.domain.model.SongStat
import com.auralis.music.domain.model.StatsOverview
import com.auralis.music.domain.model.Track
import com.auralis.music.domain.repository.StatsRepository
import com.auralis.music.data.network.OfficialArtistResolver
import com.auralis.music.domain.stats.ArtistCredits
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val LOOKUPS_PER_PASS = 25

class StatsRepositoryImpl(
    private val trackDao: TrackDao,
    private val playbackEventDao: PlaybackEventDao,
    private val historyDao: HistoryDao,
    private val playCountDao: PlayCountDao
) : StatsRepository {
    private val statsMutationMutex = Mutex()

    override suspend fun logPlaybackEvent(track: Track, playTimeMs: Long) = withContext(Dispatchers.IO) {
        if (track.id.isBlank()) return@withContext
        trackDao.upsertTrackPreservingFavorite(track.toEntity())
        // A label upload ("T-Series") gets its real artists from the official song, in the background.
        OfficialArtistResolver.request(track)
        // Only measured time counts; never assume a full-length listen.
        if (playTimeMs <= 0) return@withContext
        val effectivePlayTimeMs = playTimeMs
        playbackEventDao.insertEvent(
            PlaybackEventEntity(
                trackId = track.id,
                timestamp = System.currentTimeMillis(),
                playTimeMs = effectivePlayTimeMs
            )
        )
    }

    override fun observeStatsOverview(fromTimestamp: Long, toTimestamp: Long): Flow<StatsOverview> {
        // Counted from the same grouped events as the lists below, so "75 Songs" means 75 distinct
        // songs (not 75 YouTube IDs, where one song can have an audio, video and Topic upload).
        return withArtistLookups(playbackEventDao.getEventsInRangeFlow(fromTimestamp, toTimestamp)).map { list ->
            StatsOverview(
                totalPlayTimeMs = list.sumOf { it.event.playTimeMs },
                songsCount = list.map { facts(it).songKey }.distinct().size,
                artistsCount = list.flatMap { credits(it) }.map { it.first }.distinct().size,
                albumsCount = list.mapNotNull { e ->
                    e.track.album?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
                }.distinct().size
            )
        }.flowOn(Dispatchers.Default)
        // The ViewModel collects via stateIn(viewModelScope, ...), which defaults to the main
        // thread; without flowOn this grouping ran there on every pill switch, stuttering the UI.
    }

    override fun observeTopSongs(
        fromTimestamp: Long,
        toTimestamp: Long,
        limit: Int
    ): Flow<List<SongStat>> {
        return playbackEventDao.getEventsInRangeFlow(fromTimestamp, toTimestamp).map { list ->
            // Group by the song itself (cleaned title + artist), not the YouTube ID: the same song
            // played from its official audio and its music video used to show up twice.
            list.groupBy { facts(it).songKey }
                .map { (_, events) ->
                    // Show the version that was played most.
                    val representative = events.groupBy { it.track.id }
                        .maxByOrNull { (_, e) -> e.size }!!.value.first().track
                    SongStat(
                        track = factsOf(representative).track,
                        playCount = events.count { countsAsPlay(it.event.playTimeMs, it.track.duration) },
                        timeListenedMs = events.sumOf { it.event.playTimeMs }
                    )
                }
                .filter { it.timeListenedMs > 0 }
                .sortedByDescending { it.timeListenedMs }
                .take(limit)
        }.flowOn(Dispatchers.Default)
    }

    override fun observeTopArtists(
        fromTimestamp: Long,
        toTimestamp: Long,
        limit: Int
    ): Flow<List<ArtistStat>> {
        return withArtistLookups(playbackEventDao.getEventsInRangeFlow(fromTimestamp, toTimestamp)).map { list ->
            // A collab ("Karan Aujla & Ikky") counts in full for each artist on it, like Spotify,
            // instead of becoming its own artist named after the whole credit line.
            list.flatMap { e -> credits(e).map { (key, name) -> Triple(key, name, e) } }
                .groupBy { it.first }
                .map { (_, credits) ->
                    val events = credits.map { it.third }
                    // Most common spelling, without YouTube's " - Topic" channel suffix.
                    val artistName = credits.groupingBy { it.second }.eachCount()
                        .maxByOrNull { it.value }!!.key
                    val photo = com.auralis.music.data.network.ArtistPhotoProvider.getCachedPhoto(artistName)
                    ArtistStat(
                        name = artistName,
                        thumbnailUrl = photo,
                        songsPlayedCount = events.map { facts(it).songKey }.distinct().size,
                        timeListenedMs = events.sumOf { it.event.playTimeMs }
                    )
                }
                .sortedByDescending { it.timeListenedMs }
                .take(limit)
        }.flowOn(Dispatchers.Default)
    }

    override fun observeTopAlbums(
        fromTimestamp: Long,
        toTimestamp: Long,
        limit: Int
    ): Flow<List<AlbumStat>> {
        return playbackEventDao.getEventsInRangeFlow(fromTimestamp, toTimestamp).map { list ->
            list.mapNotNull { e ->
                val album = e.track.album?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                album.lowercase() to e
            }
                .groupBy({ it.first }, { it.second })
                .map { (_, events) ->
                    // Artwork and spelling from the album's most played track.
                    val representative = events.groupBy { it.track.id }
                        .maxByOrNull { (_, e) -> e.sumOf { it.event.playTimeMs } }!!.value.first().track
                    AlbumStat(
                        title = representative.album!!.trim(),
                        artist = displayArtist(representative.artist),
                        thumbnailUrl = representative.thumbnail.takeIf { it.isNotBlank() },
                        timeListenedMs = events.sumOf { it.event.playTimeMs }
                    )
                }
                .filter { it.timeListenedMs > 0 }
                .sortedByDescending { it.timeListenedMs }
                .take(limit)
        }.flowOn(Dispatchers.Default)
    }

    private fun songKey(track: Track): String {
        val fp = com.auralis.music.domain.recommendations.TrackDeduplicator.getSongFingerprint(track)
        val title = fp.normalizedCoreTitle.ifBlank { fp.normalizedTitle }.ifBlank { track.id }
        return "${fp.normalizedArtist}|$title"
    }

    private fun displayArtist(raw: String): String =
        raw.trim().replace(Regex("""(?i)\s*-\s*topic$"""), "").replace(Regex("""(?i)vevo$"""), "").trim()

    /** Each artist [track] credits: the official song's for a label upload, YouTube's list, or the split credit. */
    private fun creditedArtists(track: Track): List<String> = OfficialArtistResolver.credits(track)

    /**
     * [events], recounted whenever a label upload's real artists arrive. Label uploads in the
     * range that haven't been looked up are queued (a few at a time, in the background).
     */
    private fun withArtistLookups(events: Flow<List<PlaybackEventWithTrackTuple>>): Flow<List<PlaybackEventWithTrackTuple>> =
        combine(events, OfficialArtistResolver.revision) { list, _ ->
            var queued = 0
            for (e in list) { // Newest first, as the query orders them.
                if (queued >= LOOKUPS_PER_PASS) break
                val f = facts(e)
                if (f.uploaderCredit && OfficialArtistResolver.needsLookup(f.track)) { OfficialArtistResolver.request(f.track); queued++ }
            }
            list
        }

    private fun artistKey(raw: String): String? = ArtistCredits.key(raw)

    /**
     * What a song row works out to, computed once per row instead of once per listen on every
     * period switch: switching 1 week / 1 month / 3 months re-ran the title cleaning and artist
     * splitting for every listen in the range (twice for the artists), which is what made the
     * numbers wait. A row that changes (a refreshed song) is a new key.
     */
    private class TrackFacts(val track: Track, val songKey: String, val uploaderCredit: Boolean)
    private val trackFacts = java.util.concurrent.ConcurrentHashMap<com.auralis.music.data.local.entity.TrackEntity, TrackFacts>()
    private fun factsOf(row: com.auralis.music.data.local.entity.TrackEntity): TrackFacts = trackFacts.getOrPut(row) {
        val track = row.toDomain()
        TrackFacts(track, songKey(track), ArtistCredits.isUploaderCredit(track))
    }
    private fun facts(e: PlaybackEventWithTrackTuple): TrackFacts = factsOf(e.track)

    /** Each credited artist as (key, name); recomputed when a label upload's real artists arrive. */
    private val trackCredits = java.util.concurrent.ConcurrentHashMap<com.auralis.music.data.local.entity.TrackEntity, List<Pair<String, String>>>()
    @Volatile private var creditsRevision = Int.MIN_VALUE
    private fun credits(e: PlaybackEventWithTrackTuple): List<Pair<String, String>> {
        val revision = OfficialArtistResolver.revision.value
        if (revision != creditsRevision) { trackCredits.clear(); creditsRevision = revision }
        return trackCredits.getOrPut(e.track) {
            creditedArtists(facts(e).track).mapNotNull { name -> artistKey(name)?.let { it to name } }
        }
    }

    /**
     * A listen counts as a "play" once it reaches 30s (or half the song, for songs under a minute).
     * Time listened always counts in full; this only stops skips from inflating play counts.
     */
    private fun countsAsPlay(playTimeMs: Long, durationSec: Long): Boolean {
        val threshold = if (durationSec in 1..59) durationSec * 500L else 30_000L
        return playTimeMs >= threshold
    }

    override fun observeFirstEventTimestamp(): Flow<Long?> {
        return playbackEventDao.getFirstEventTimestamp()
    }

    override suspend fun removeEstimatedListens() = withContext(Dispatchers.IO) {
        statsMutationMutex.withLock {
            // Stats shows only measured listening time. This used to rebuild events from play
            // counts as plays x full length (wiping real events to do it), so a song started five
            // times showed five complete listens.
            playbackEventDao.deleteEstimatedEvents()
            Unit
        }
    }

    override suspend fun mergeChunkedListens() = withContext(Dispatchers.IO) {
        statsMutationMutex.withLock {
            val events = playbackEventDao.getAllEventsByTrack()
            if (events.size < 2) return@withLock
            val durations = events.map { it.trackId }.distinct()
                .associateWith { (trackDao.getTrackById(it)?.duration ?: 0L) * 1000L }
            val groups = com.auralis.music.domain.stats.ListenChunkMerger.findChunkedListens(
                events.map { com.auralis.music.domain.stats.ListenChunkMerger.Event(it.id, it.trackId, it.timestamp, it.playTimeMs) }
            ) { trackId -> durations[trackId] ?: 0L }
            for (group in groups) {
                playbackEventDao.setPlayTime(group.first().id, group.sumOf { it.playTimeMs })
                playbackEventDao.deleteEvents(group.drop(1).map { it.id })
            }
        }
    }

    override suspend fun clearListeningStats() = withContext(Dispatchers.IO) {
        statsMutationMutex.withLock {
            // The startup seed reads both of these legacy tables. Clear them before events
            // so opening Stats again cannot restore the old listening data.
            historyDao.clearHistory()
            playCountDao.clearPlayCounts()
            playbackEventDao.clearAllEvents()
        }
        com.auralis.music.data.sync.StatsCloudSync.clearAccountStats()
    }
}
