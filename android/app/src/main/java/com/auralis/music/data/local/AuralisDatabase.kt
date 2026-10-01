package com.auralis.music.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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
        LyricsEntity::class,
        NegativeLyricsEntity::class,
        PlaybackEventEntity::class,
        ArtworkSelectionEntity::class
    ],
    version = 11,
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
    abstract fun negativeLyricsDao(): NegativeLyricsDao
    abstract fun playbackEventDao(): PlaybackEventDao

    companion object {
        private const val DATABASE_NAME = "auralis_music.db"

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE lyrics_cache ADD COLUMN durationMs INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE lyrics_cache ADD COLUMN leadingSilenceMs INTEGER DEFAULT NULL")
            }
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `playback_events` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `trackId` TEXT NOT NULL,
                        `timestamp` INTEGER NOT NULL,
                        `playTimeMs` INTEGER NOT NULL,
                        FOREIGN KEY(`trackId`) REFERENCES `tracks`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_playback_events_trackId` ON `playback_events` (`trackId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_playback_events_timestamp` ON `playback_events` (`timestamp`)")
            }
        }

        // Version 10 was an abandoned experimental artwork schema; this starts from clean v9.
        // No legacy artwork is promoted, repaired, or changed by this migration.
        val MIGRATION_9_11 = object : Migration(9, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `artwork_selections` (
                        `trackId` TEXT NOT NULL PRIMARY KEY,
                        `title` TEXT NOT NULL,
                        `artist` TEXT NOT NULL,
                        `duration` INTEGER NOT NULL,
                        `source` TEXT NOT NULL,
                        `thumbnail` TEXT NOT NULL,
                        `album` TEXT,
                        `provider` TEXT NOT NULL,
                        `providerItemId` TEXT NOT NULL,
                        `releaseId` TEXT,
                        `evidence` TEXT NOT NULL,
                        `basis` TEXT NOT NULL,
                        `revision` INTEGER NOT NULL,
                        FOREIGN KEY(`trackId`) REFERENCES `tracks`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                """.trimIndent())
            }
        }

        // Compatibility only: preserve the abandoned v10 records as opaque archives.
        // Never promote them to explicit selections or rewrite Track/library metadata.
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `artwork_selections` RENAME TO `artwork_selections_v10_archive`")
                db.execSQL("ALTER TABLE `artwork_candidates` RENAME TO `artwork_candidates_v10_archive`")
                MIGRATION_9_11.migrate(db)
            }
        }

        @Volatile
        private var instance: AuralisDatabase? = null

        fun getInstance(context: Context): AuralisDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AuralisDatabase::class.java,
                    DATABASE_NAME
                )
                .addMigrations(MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_11, MIGRATION_10_11)
                .build()
                .also { instance = it }
            }
        }
    }
}
