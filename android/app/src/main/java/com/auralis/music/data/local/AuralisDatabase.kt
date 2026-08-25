package com.auralis.music.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.auralis.music.data.local.converter.AuralisConverters
import com.auralis.music.data.local.dao.*
import com.auralis.music.data.local.entity.*

@Database(
    entities = [
        TrackEntity::class,
        PlaylistEntity::class,
        PlaylistTrackCrossRef::class,
        SavedArtistEntity::class,
        SavedAlbumEntity::class,
        HistoryEntity::class,
        PlayCountEntity::class,
        SearchHistoryEntity::class,
        LyricsEntity::class
    ],
    version = 3,
    exportSchema = false
)
@TypeConverters(AuralisConverters::class)
abstract class AuralisDatabase : RoomDatabase() {
    abstract fun trackDao(): TrackDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun libraryDao(): LibraryDao
    abstract fun historyDao(): HistoryDao
    abstract fun playCountDao(): PlayCountDao
    abstract fun searchHistoryDao(): SearchHistoryDao
    abstract fun lyricsDao(): LyricsDao

    companion object {
        private const val DATABASE_NAME = "auralis_music.db"

        @Volatile
        private var instance: AuralisDatabase? = null

        fun getInstance(context: Context): AuralisDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AuralisDatabase::class.java,
                    DATABASE_NAME
                )
                .fallbackToDestructiveMigration()
                .build()
                .also { instance = it }
            }
        }
    }
}
