package com.auralis.music.data.download

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "playlist_download_jobs")
data class PlaylistDownloadJobEntity(
    @PrimaryKey val jobId: String,
    val playlistId: String,
    val playlistName: String,
    val folderName: String,
    val backend: String,
    val trackSnapshotJson: String,
    val resultsJson: String,
    val status: String,
    val completedCount: Int,
    val failedCount: Int,
    val skippedCount: Int,
    val currentTrackId: String?,
    val cancelRequested: Boolean,
    val offlineEnabled: Boolean,
    val createdAt: Long,
    val updatedAt: Long
)

@Dao
interface PlaylistDownloadJobDao {
    @Query("SELECT * FROM playlist_download_jobs ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<PlaylistDownloadJobEntity>>

    @Query("SELECT * FROM playlist_download_jobs ORDER BY updatedAt DESC")
    suspend fun getAll(): List<PlaylistDownloadJobEntity>

    @Query("SELECT * FROM playlist_download_jobs WHERE jobId = :jobId")
    suspend fun get(jobId: String): PlaylistDownloadJobEntity?

    @Query("SELECT * FROM playlist_download_jobs WHERE playlistId = :playlistId LIMIT 1")
    suspend fun getForPlaylist(playlistId: String): PlaylistDownloadJobEntity?

    @Query("SELECT * FROM playlist_download_jobs WHERE folderName = :folderName LIMIT 1")
    suspend fun getForFolder(folderName: String): PlaylistDownloadJobEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(job: PlaylistDownloadJobEntity)

    @Update
    suspend fun update(job: PlaylistDownloadJobEntity): Int

    @Query("DELETE FROM playlist_download_jobs WHERE jobId = :jobId")
    suspend fun delete(jobId: String)

    @Query("UPDATE playlist_download_jobs SET cancelRequested = 1, status = 'CANCELLED', currentTrackId = NULL, offlineEnabled = 0, updatedAt = :now WHERE jobId = :jobId")
    suspend fun requestCancel(jobId: String, now: Long)

    @Query("UPDATE playlist_download_jobs SET offlineEnabled = :enabled, updatedAt = :now WHERE playlistId = :playlistId")
    suspend fun setOfflineEnabled(playlistId: String, enabled: Boolean, now: Long)
}

@Database(entities = [PlaylistDownloadJobEntity::class], version = 1, exportSchema = false)
abstract class PlaylistDownloadDatabase : RoomDatabase() {
    abstract fun jobs(): PlaylistDownloadJobDao

    companion object {
        @Volatile private var instance: PlaylistDownloadDatabase? = null
        fun get(context: Context): PlaylistDownloadDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                PlaylistDownloadDatabase::class.java,
                "auralis_playlist_downloads.db"
            ).build().also { instance = it }
        }
    }
}
