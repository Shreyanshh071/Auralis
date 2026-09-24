package com.auralis.music.data.repository

import com.auralis.music.data.datastore.SettingsDataStore
import com.auralis.music.data.local.dao.*
import com.auralis.music.data.local.entity.HistoryEntity
import com.auralis.music.data.local.entity.PlayCountEntity
import com.auralis.music.data.local.entity.PlaylistTrackCrossRef
import com.auralis.music.data.local.mapper.*
import com.auralis.music.domain.model.*
import com.auralis.music.domain.recommendations.TrackDeduplicator
import com.auralis.music.domain.repository.HistoryRepository
import com.auralis.music.domain.repository.LibraryRepository
import com.auralis.music.domain.repository.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.UUID

class LibraryRepositoryImpl(
    private val trackDao: TrackDao,
    private val playlistDao: PlaylistDao,
    private val libraryDao: LibraryDao
) : LibraryRepository {

    override fun getFavoriteTracks(): Flow<List<Track>> {
        return trackDao.getFavoriteTracksFlow().map { list -> list.map { it.toDomain() } }
    }

    override fun isFavorite(trackId: String): Flow<Boolean> {
        return trackDao.isFavoriteFlow(trackId).map { it == true }
    }

    override suspend fun toggleFavorite(track: Track) {
        val existing = trackDao.getTrackById(track.id)
        val nextFav = !(existing?.isFavorite ?: false)
        trackDao.upsertTrack(track.toEntity(isFavorite = nextFav, favoriteAddedAt = if (nextFav) System.currentTimeMillis() else null))
    }

    override suspend fun setFavorite(track: Track, isFavorite: Boolean) {
        trackDao.upsertTrack(track.toEntity(isFavorite = isFavorite, favoriteAddedAt = if (isFavorite) System.currentTimeMillis() else null))
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override fun getPlaylists(): Flow<List<Playlist>> {
        return playlistDao.getAllPlaylistsWithTracksFlow().map { list ->
            list.map { tuple ->
                val orderedTracks = playlistDao.getOrderedTracksForPlaylist(tuple.playlist.id)
                Playlist(
                    id = tuple.playlist.id,
                    title = tuple.playlist.title,
                    description = tuple.playlist.description,
                    coverUrl = tuple.playlist.coverUrl,
                    tracks = orderedTracks.map { it.toDomain() }.filter { !it.title.startsWith("Track ") && it.title.isNotBlank() },
                    createdAt = tuple.playlist.createdAt,
                    isCustom = tuple.playlist.isCustom
                )
            }
        }.flowOn(kotlinx.coroutines.Dispatchers.Default)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override fun getPlaylist(playlistId: String): Flow<Playlist?> {
        return playlistDao.getPlaylistEntityFlow(playlistId).flatMapLatest { entity ->
            if (entity == null) flowOf(null)
            else {
                playlistDao.getOrderedTracksForPlaylistFlow(playlistId).map { trackEntities ->
                    Playlist(
                        id = entity.id,
                        title = entity.title,
                        description = entity.description,
                        coverUrl = entity.coverUrl,
                        tracks = trackEntities.map { it.toDomain() }.filter { !it.title.startsWith("Track ") && it.title.isNotBlank() },
                        createdAt = entity.createdAt,
                        isCustom = entity.isCustom
                    )
                }
            }
        }.flowOn(kotlinx.coroutines.Dispatchers.Default)
    }

    override suspend fun createPlaylist(title: String, description: String?, coverUrl: String?): Playlist {
        val playlist = Playlist(
            id = UUID.randomUUID().toString(),
            title = title,
            description = description,
            coverUrl = coverUrl,
            createdAt = System.currentTimeMillis()
        )
        playlistDao.upsertPlaylist(playlist.toEntity())
        return playlist
    }

    override suspend fun updatePlaylist(playlistId: String, title: String, description: String?, coverUrl: String?) {
        playlistDao.updatePlaylist(playlistId, title, description, coverUrl)
    }

    override suspend fun addTrackToPlaylist(playlistId: String, track: Track) {
        trackDao.upsertTrackPreservingFavorite(track.toEntity())
        val orderedTracks = playlistDao.getOrderedTracksForPlaylist(playlistId)
        val nextPos = orderedTracks.size
        playlistDao.insertCrossRef(PlaylistTrackCrossRef(playlistId, track.id, nextPos))
    }

    override suspend fun removeTrackFromPlaylist(playlistId: String, trackId: String) {
        playlistDao.removeTrackFromPlaylist(playlistId, trackId)
    }

    override suspend fun deletePlaylist(playlistId: String) {
        playlistDao.deletePlaylist(playlistId)
    }

    override suspend fun reorderPlaylist(playlistId: String, tracks: List<Track>) {
        trackDao.upsertTracksPreservingFavorite(tracks.map { it.toEntity() })
        val refs = tracks.mapIndexed { index, track ->
            PlaylistTrackCrossRef(playlistId, track.id, index)
        }
        playlistDao.replacePlaylistCrossRefs(playlistId, refs)
    }

    override suspend fun replacePlaylistTracks(playlistId: String, tracks: List<Track>) {
        trackDao.upsertTracksPreservingFavorite(tracks.map { it.toEntity() })
        val refs = tracks.mapIndexed { index, track ->
            PlaylistTrackCrossRef(playlistId, track.id, index)
        }
        playlistDao.replacePlaylistCrossRefs(playlistId, refs)
    }

    override fun getSavedArtists(): Flow<List<SavedArtist>> {
        return libraryDao.getSavedArtistsFlow().map { list -> list.map { it.toDomain() } }
    }

    override fun isArtistSaved(artistId: String): Flow<Boolean> {
        return libraryDao.isArtistSavedFlow(artistId)
    }

    override suspend fun saveArtist(artist: SavedArtist) {
        libraryDao.upsertArtist(artist.toEntity())
    }

    override suspend fun removeArtist(artistId: String) {
        libraryDao.deleteArtist(artistId)
    }

    override fun getSavedAlbums(): Flow<List<SavedAlbum>> {
        return libraryDao.getSavedAlbumsFlow().map { list -> list.map { it.toDomain() } }
    }

    override fun isAlbumSaved(albumId: String): Flow<Boolean> {
        return libraryDao.isAlbumSavedFlow(albumId)
    }

    override suspend fun saveAlbum(album: SavedAlbum) {
        libraryDao.upsertAlbum(album.toEntity())
    }

    override suspend fun removeAlbum(albumId: String) {
        libraryDao.deleteAlbum(albumId)
    }
}

class HistoryRepositoryImpl(
    private val trackDao: TrackDao,
    private val historyDao: HistoryDao,
    private val playCountDao: PlayCountDao,
    private val playbackEventDao: com.auralis.music.data.local.dao.PlaybackEventDao? = null
) : HistoryRepository {

    override fun getHistory(): Flow<List<HistoryEntry>> {
        return historyDao.getHistoryWithTracksFlow()
            .map { list ->
                val seenFps = mutableListOf<com.auralis.music.domain.recommendations.SongFingerprint>()
                val seenKeys = mutableSetOf<String>()
                val deduplicated = mutableListOf<HistoryEntry>()

                for (item in list) {
                    val entry = item.toDomain()
                    val fp = TrackDeduplicator.getSongFingerprint(entry.track)
                    val fastKey = if (fp.normalizedBaseTitle.isNotBlank() && fp.normalizedArtist.isNotBlank()) {
                        "${fp.normalizedArtist}::${fp.normalizedBaseTitle}"
                    } else null

                    val isDup = (fastKey != null && seenKeys.contains(fastKey)) ||
                            seenFps.any { existingFp ->
                                TrackDeduplicator.isDuplicateSong(existingFp, fp, matchAlternateVersions = true)
                            }

                    if (!isDup) {
                        if (fastKey != null) seenKeys.add(fastKey)
                        seenFps.add(fp)
                        deduplicated.add(entry)
                    }
                }
                deduplicated
            }
            .flowOn(Dispatchers.Default)
    }

    override suspend fun addToHistory(track: Track): Unit = withContext(Dispatchers.IO) {
        if (track.id.isBlank()) return@withContext
        trackDao.upsertTrackPreservingFavorite(track.toEntity())

        // Remove any existing duplicate track (e.g. alternate YouTube video ID or cut of the same song)
        val trackFp = TrackDeduplicator.getSongFingerprint(track)
        val existingEntries = historyDao.getHistoryWithTracks()
        val idsToRemove = mutableListOf<String>()
        for (entry in existingEntries) {
            val existingTrack = entry.track.toDomain()
            if (existingTrack.id != track.id) {
                val existingFp = TrackDeduplicator.getSongFingerprint(existingTrack)
                if (TrackDeduplicator.isDuplicateSong(existingFp, trackFp, matchAlternateVersions = true)) {
                    idsToRemove.add(existingTrack.id)
                }
            }
        }
        for (id in idsToRemove) {
            historyDao.removeFromHistory(id)
        }

        // Upsert current track at the latest timestamp so it appears at index 0 (the very top)
        historyDao.upsertHistory(HistoryEntity(trackId = track.id, playedAt = System.currentTimeMillis()))
        historyDao.pruneHistoryToCap()
    }

    override suspend fun removeFromHistory(trackId: String): Unit = withContext(Dispatchers.IO) {
        val existingEntries = historyDao.getHistoryWithTracks()
        val targetTrack = existingEntries.firstOrNull { it.track.id == trackId }?.track?.toDomain()
        historyDao.removeFromHistory(trackId)
        if (targetTrack != null) {
            val targetFp = TrackDeduplicator.getSongFingerprint(targetTrack)
            val idsToRemove = mutableListOf<String>()
            for (entry in existingEntries) {
                val existingTrack = entry.track.toDomain()
                if (existingTrack.id != trackId) {
                    val existingFp = TrackDeduplicator.getSongFingerprint(existingTrack)
                    if (TrackDeduplicator.isDuplicateSong(existingFp, targetFp, matchAlternateVersions = true)) {
                        idsToRemove.add(existingTrack.id)
                    }
                }
            }
            for (id in idsToRemove) {
                historyDao.removeFromHistory(id)
            }
        }
    }

    override suspend fun clearHistory(): Unit = withContext(Dispatchers.IO) {
        historyDao.clearHistory()
        playCountDao.clearPlayCounts()
    }

    override fun getTopPlayedTracks(): Flow<List<PlayCountEntry>> {
        return playCountDao.getTopPlayedTracksFlow().map { list -> list.map { it.toDomain() } }
    }

    override suspend fun recordPlay(track: Track): Unit = withContext(Dispatchers.IO) {
        if (track.id.isBlank()) return@withContext
        trackDao.upsertTrackPreservingFavorite(track.toEntity())
        val existing = playCountDao.getPlayCount(track.id)
        val count = (existing?.count ?: 0) + 1
        playCountDao.upsertPlayCount(
            PlayCountEntity(
                trackId = track.id,
                count = count,
                lastPlayed = System.currentTimeMillis()
            )
        )
        // Stats listening time is recorded by ListeningTimeTracker with the real played duration
        // when the song ends; logging the full song length here on every start made skips count
        // as complete listens.
    }

    override suspend fun getPlayCounts(): List<PlayCountEntry> {
        return playCountDao.getAllPlayCounts().map { it.toDomain() }
    }

    override suspend fun getForgottenFavorites(cutoffTimestamp: Long): List<Track> {
        return historyDao.getForgottenHistoryTracks(cutoffTimestamp).map { it.track.toDomain() }
    }

    override suspend fun getRecentHeavyRotation(fromTimestamp: Long): List<Track> {
        return historyDao.getRecentHistoryTracks(fromTimestamp).map { it.track.toDomain() }
    }

    override suspend fun getLikedSeeds(limit: Int): List<Track> {
        return trackDao.getFavoriteTracksList(limit).map { it.toDomain() }
    }
}

class SettingsRepositoryImpl(
    private val settingsDataStore: SettingsDataStore
) : SettingsRepository {

    override val settingsFlow: Flow<PlayerSettings> = settingsDataStore.settingsFlow

    override suspend fun updateSettings(settings: PlayerSettings) {
        settingsDataStore.updateSettings(settings)
    }

    override suspend fun setThemeMode(mode: ThemeMode) {
        settingsDataStore.setThemeMode(mode)
    }

    override suspend fun setAudioQuality(quality: AudioQuality) {
        settingsDataStore.setAudioQuality(quality)
    }

    override suspend fun setGaplessPlayback(enabled: Boolean) {
        settingsDataStore.setGaplessPlayback(enabled)
    }

    override suspend fun setSkipSilence(enabled: Boolean) {
        settingsDataStore.setSkipSilence(enabled)
    }

    override suspend fun setSpatialAudio(enabled: Boolean) {
        settingsDataStore.setSpatialAudio(enabled)
    }

    override suspend fun setVolume(volume: Float) {
        settingsDataStore.setVolume(volume)
    }

    override suspend fun setPlaybackRate(rate: Float) {
        settingsDataStore.setPlaybackRate(rate)
    }
}
