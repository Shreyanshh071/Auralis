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

/** Measured listening time for one track ID, summed over all its listens. */
data class TrackListenTime(val trackId: String, val totalMs: Long)

@Dao
interface PlaybackEventDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEvent(event: PlaybackEventEntity): Long

    /** Adds time to a listen still in progress. Returns 0 if the row is gone (stats cleared). */
    @Query("UPDATE playback_events SET playTimeMs = playTimeMs + :addMs WHERE id = :id")
    suspend fun addPlayTime(id: Long, addMs: Long): Int

    @Query("SELECT * FROM playback_events ORDER BY trackId, timestamp")
    suspend fun getAllEventsByTrack(): List<PlaybackEventEntity>

    @Query("SELECT * FROM playback_events WHERE timestamp >= :fromTimestamp ORDER BY timestamp")
    suspend fun getEventsSince(fromTimestamp: Long): List<PlaybackEventEntity>

    @Query("DELETE FROM playback_events WHERE id IN (:ids)")
    suspend fun deleteEvents(ids: List<Long>)

    @Query("UPDATE playback_events SET playTimeMs = :playTimeMs WHERE id = :id")
    suspend fun setPlayTime(id: Long, playTimeMs: Long)

    @Query("SELECT COUNT(*) FROM playback_events WHERE trackId = :trackId AND timestamp BETWEEN :from AND :to")
    suspend fun countEventsNear(trackId: String, from: Long, to: Long): Int

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

    @Query("SELECT trackId, SUM(playTimeMs) AS totalMs FROM playback_events GROUP BY trackId")
    fun getListenTimeByTrack(): Flow<List<TrackListenTime>>

    @Query("SELECT MIN(timestamp) FROM playback_events")
    fun getFirstEventTimestamp(): Flow<Long?>

    @Query("SELECT COUNT(*) FROM playback_events")
    suspend fun getEventCount(): Int

    @Query("DELETE FROM playback_events")
    suspend fun clearAllEvents()

    /**
     * Deletes estimated listens: events whose time is exactly the song's full length (or the old
     * 198s fallback). Older builds wrote these on every song start or fabricated them from play
     * counts; a measured listen from ListeningTimeTracker never lands on the exact millisecond.
     */
    @Query("""
        DELETE FROM playback_events
        WHERE playTimeMs = 198000
           OR playTimeMs = (SELECT tracks.duration * 1000 FROM tracks WHERE tracks.id = playback_events.trackId)
    """)
    suspend fun deleteEstimatedEvents(): Int
}
