package com.auralis.music.data.local.dao

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import com.auralis.music.data.local.entity.PlaybackEventEntity
import com.auralis.music.data.local.entity.TrackEntity
import kotlinx.coroutines.flow.Flow

data class PlaybackEventWithTrackTuple(
    @Embedded
    val event: PlaybackEventEntity,
    @Relation(
        parentColumn = "trackId",
        entityColumn = "id"
    )
    val track: TrackEntity
)

@Dao
interface PlaybackEventDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEvent(event: PlaybackEventEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEvents(events: List<PlaybackEventEntity>)

    @Query("SELECT SUM(playTimeMs) FROM playback_events WHERE timestamp >= :fromTimestamp AND timestamp <= :toTimestamp")
    fun getTotalPlayTimeInRange(fromTimestamp: Long, toTimestamp: Long): Flow<Long?>

    @Query("SELECT COUNT(DISTINCT trackId) FROM playback_events WHERE timestamp >= :fromTimestamp AND timestamp <= :toTimestamp")
    fun getUniqueSongCountInRange(fromTimestamp: Long, toTimestamp: Long): Flow<Int>

    @Query("""
        SELECT COUNT(DISTINCT tracks.artist) 
        FROM playback_events 
        JOIN tracks ON playback_events.trackId = tracks.id 
        WHERE timestamp >= :fromTimestamp AND timestamp <= :toTimestamp
    """)
    fun getUniqueArtistCountInRange(fromTimestamp: Long, toTimestamp: Long): Flow<Int>

    @Query("""
        SELECT COUNT(DISTINCT tracks.album) 
        FROM playback_events 
        JOIN tracks ON playback_events.trackId = tracks.id 
        WHERE timestamp >= :fromTimestamp AND timestamp <= :toTimestamp AND tracks.album IS NOT NULL AND tracks.album != ''
    """)
    fun getUniqueAlbumCountInRange(fromTimestamp: Long, toTimestamp: Long): Flow<Int>

    @Transaction
    @Query("""
        SELECT * FROM playback_events 
        WHERE timestamp >= :fromTimestamp AND timestamp <= :toTimestamp 
        ORDER BY timestamp DESC
    """)
    fun getEventsInRangeFlow(fromTimestamp: Long, toTimestamp: Long): Flow<List<PlaybackEventWithTrackTuple>>

    @Transaction
    @Query("""
        SELECT * FROM playback_events 
        WHERE timestamp >= :fromTimestamp AND timestamp <= :toTimestamp 
        ORDER BY timestamp DESC
    """)
    suspend fun getEventsInRange(fromTimestamp: Long, toTimestamp: Long): List<PlaybackEventWithTrackTuple>

    @Query("SELECT MIN(timestamp) FROM playback_events")
    fun getFirstEventTimestamp(): Flow<Long?>

    @Query("SELECT COUNT(*) FROM playback_events")
    suspend fun getEventCount(): Int

    @Query("DELETE FROM playback_events")
    suspend fun clearAllEvents()
}
