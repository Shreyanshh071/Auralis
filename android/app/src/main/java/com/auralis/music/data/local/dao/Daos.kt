package com.auralis.music.data.local.dao

import androidx.room.*
import com.auralis.music.data.local.entity.*
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackDao {
    @Upsert
    suspend fun upsertTrack(track: TrackEntity)

    @Upsert
    suspend fun upsertTracks(tracks: List<TrackEntity>)

    @Query("SELECT * FROM tracks WHERE id = :id LIMIT 1")
    suspend fun getTrackById(id: String): TrackEntity?

    @Query("SELECT * FROM tracks WHERE isFavorite = 1 ORDER BY favoriteAddedAt DESC")
    fun getFavoriteTracksFlow(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE isFavorite = 1 ORDER BY favoriteAddedAt DESC LIMIT :limit")
    suspend fun getFavoriteTracksList(limit: Int = 20): List<TrackEntity>

    @Query("SELECT isFavorite FROM tracks WHERE id = :id LIMIT 1")
    fun isFavoriteFlow(id: String): Flow<Boolean?>

    @Query("UPDATE tracks SET isFavorite = :isFavorite, favoriteAddedAt = :addedAt WHERE id = :id")
    suspend fun setFavorite(id: String, isFavorite: Boolean, addedAt: Long? = if (isFavorite) System.currentTimeMillis() else null)

    @Query("DELETE FROM tracks WHERE id = :id")
    suspend fun deleteTrack(id: String)
}

data class PlaylistWithTracksTuple(
    @Embedded
    val playlist: PlaylistEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "id",
        associateBy = Junction(
            value = PlaylistTrackCrossRef::class,
            parentColumn = "playlistId",
            entityColumn = "trackId"
        )
    )
    val tracks: List<TrackEntity>
)

@Dao
interface PlaylistDao {
    @Upsert
    suspend fun upsertPlaylist(playlist: PlaylistEntity)

    @Query("UPDATE playlists SET title = :title, description = :description, coverUrl = :coverUrl WHERE id = :playlistId")
    suspend fun updatePlaylist(playlistId: String, title: String, description: String?, coverUrl: String?)

    @Query("SELECT * FROM playlists ORDER BY createdAt DESC")
    fun getAllPlaylistsFlow(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlists WHERE id = :playlistId LIMIT 1")
    fun getPlaylistEntityFlow(playlistId: String): Flow<PlaylistEntity?>

    @Query("""
        SELECT tracks.* FROM tracks 
        INNER JOIN playlist_tracks ON tracks.id = playlist_tracks.trackId 
        WHERE playlist_tracks.playlistId = :playlistId 
        ORDER BY playlist_tracks.position ASC
    """)
    fun getOrderedTracksForPlaylistFlow(playlistId: String): Flow<List<TrackEntity>>

    @Query("""
        SELECT tracks.* FROM tracks 
        INNER JOIN playlist_tracks ON tracks.id = playlist_tracks.trackId 
        WHERE playlist_tracks.playlistId = :playlistId 
        ORDER BY playlist_tracks.position ASC
    """)
    suspend fun getOrderedTracksForPlaylist(playlistId: String): List<TrackEntity>

    @Transaction
    @Query("SELECT * FROM playlists WHERE id = :playlistId LIMIT 1")
    fun getPlaylistWithTracksFlow(playlistId: String): Flow<PlaylistWithTracksTuple?>

    @Transaction
    @Query("SELECT * FROM playlists WHERE id = :playlistId LIMIT 1")
    suspend fun getPlaylistWithTracks(playlistId: String): PlaylistWithTracksTuple?

    @Upsert
    suspend fun insertCrossRef(ref: PlaylistTrackCrossRef)

    @Upsert
    suspend fun insertCrossRefs(refs: List<PlaylistTrackCrossRef>)

    @Query("DELETE FROM playlist_tracks WHERE playlistId = :playlistId AND trackId = :trackId")
    suspend fun removeTrackFromPlaylist(playlistId: String, trackId: String)

    @Query("DELETE FROM playlist_tracks WHERE playlistId = :playlistId")
    suspend fun clearPlaylistTracks(playlistId: String)

    @Query("DELETE FROM playlists WHERE id = :playlistId")
    suspend fun deletePlaylist(playlistId: String)
}

@Dao
interface LibraryDao {
    // Artists
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertArtist(artist: SavedArtistEntity)

    @Query("SELECT * FROM saved_artists ORDER BY savedAt DESC")
    fun getSavedArtistsFlow(): Flow<List<SavedArtistEntity>>

    @Query("DELETE FROM saved_artists WHERE id = :id")
    suspend fun deleteArtist(id: String)

    @Query("SELECT EXISTS(SELECT 1 FROM saved_artists WHERE id = :id)")
    fun isArtistSavedFlow(id: String): Flow<Boolean>

    // Albums
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAlbum(album: SavedAlbumEntity)

    @Query("SELECT * FROM saved_albums ORDER BY savedAt DESC")
    fun getSavedAlbumsFlow(): Flow<List<SavedAlbumEntity>>

    @Query("DELETE FROM saved_albums WHERE id = :id")
    suspend fun deleteAlbum(id: String)

    @Query("SELECT EXISTS(SELECT 1 FROM saved_albums WHERE id = :id)")
    fun isAlbumSavedFlow(id: String): Flow<Boolean>
}

data class HistoryWithTrackTuple(
    @Embedded
    val history: HistoryEntity,
    @Relation(
        parentColumn = "trackId",
        entityColumn = "id"
    )
    val track: TrackEntity
)

@Dao
interface HistoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertHistory(history: HistoryEntity)

    @Transaction
    @Query("SELECT * FROM history ORDER BY playedAt DESC LIMIT 100")
    fun getHistoryWithTracksFlow(): Flow<List<HistoryWithTrackTuple>>

    @Transaction
    @Query("SELECT * FROM history ORDER BY playedAt DESC LIMIT 100")
    suspend fun getHistoryWithTracks(): List<HistoryWithTrackTuple>

    @Transaction
    @Query("""
        SELECT * FROM history 
        WHERE playedAt < :cutoffTimestamp 
        ORDER BY playedAt ASC 
        LIMIT :limit
    """)
    suspend fun getForgottenHistoryTracks(cutoffTimestamp: Long, limit: Int = 20): List<HistoryWithTrackTuple>

    @Transaction
    @Query("""
        SELECT * FROM history 
        WHERE playedAt >= :fromTimestamp 
        ORDER BY playedAt DESC 
        LIMIT :limit
    """)
    suspend fun getRecentHistoryTracks(fromTimestamp: Long, limit: Int = 30): List<HistoryWithTrackTuple>

    @Query("DELETE FROM history WHERE trackId = :trackId")
    suspend fun removeFromHistory(trackId: String)

    @Query("DELETE FROM history")
    suspend fun clearHistory()

    @Query("DELETE FROM history WHERE trackId NOT IN (SELECT trackId FROM history ORDER BY playedAt DESC LIMIT 100)")
    suspend fun pruneHistoryToCap()
}

data class PlayCountWithTrackTuple(
    @Embedded
    val playCount: PlayCountEntity,
    @Relation(
        parentColumn = "trackId",
        entityColumn = "id"
    )
    val track: TrackEntity
)

@Dao
interface PlayCountDao {
    @Query("SELECT * FROM play_counts WHERE trackId = :trackId LIMIT 1")
    suspend fun getPlayCount(trackId: String): PlayCountEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPlayCount(entity: PlayCountEntity)

    @Transaction
    @Query("SELECT * FROM play_counts ORDER BY count DESC LIMIT 100")
    fun getTopPlayedTracksFlow(): Flow<List<PlayCountWithTrackTuple>>

    @Transaction
    @Query("SELECT * FROM play_counts")
    suspend fun getAllPlayCounts(): List<PlayCountWithTrackTuple>
}

@Dao
interface SearchHistoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSearchQuery(entity: SearchHistoryEntity)

    @Query("SELECT query FROM search_history ORDER BY timestamp DESC LIMIT 20")
    fun getRecentQueriesFlow(): Flow<List<String>>

    @Query("DELETE FROM search_history WHERE `query` = :query")
    suspend fun deleteSearchQuery(query: String)

    @Query("DELETE FROM search_history")
    suspend fun clearSearchHistory()
}

@Dao
interface LyricsDao {
    @Query("SELECT * FROM lyrics_cache WHERE trackId = :trackId LIMIT 1")
    suspend fun getLyrics(trackId: String): com.auralis.music.data.local.entity.LyricsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLyrics(entity: com.auralis.music.data.local.entity.LyricsEntity)

    @Query("DELETE FROM lyrics_cache WHERE trackId = :trackId")
    suspend fun deleteLyrics(trackId: String)

    @Query("DELETE FROM lyrics_cache")
    suspend fun clearAllLyrics()
}
