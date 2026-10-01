package com.auralis.music

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.auralis.music.data.local.AuralisDatabase
import com.auralis.music.data.local.entity.TrackEntity
import com.auralis.music.domain.artwork.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Uses only private test databases, never the Auralis application database. */
class ArtworkSelectionRoomTest {
    private val track = TrackEntity("recording", "Song", "Artist", "Album", 240, "original")
    private val decision = ArtworkSelection(track.id, "selected", "Chosen album", "spotify", "recording-id",
        "release-id", "Fixture recording evidence", ArtworkSelectionBasis.VERIFIED_RELEASE)

    @Test fun concurrentDecisionsAndStaleWritesAreAtomic() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AuralisDatabase::class.java).build()
        try {
            val dao = db.trackDao(); dao.upsertTrack(track)
            val results = (1..20).map { async(Dispatchers.IO) { dao.selectArtwork(track, decision.copy(thumbnail = "art-$it"), 0) } }.awaitAll()
            assertEquals(1, results.count { it })
            val selected = dao.getArtworkSelection(track.id)!!
            dao.upsertTracksPreservingFavorite(listOf(track.copy(thumbnail = "stale")))
            assertEquals(selected.thumbnail, dao.getTrackById(track.id)?.thumbnail)
            assertEquals(1L, selected.revision)
            dao.deleteTrack(track.id)
            assertNull(dao.getArtworkSelection(track.id))
        } finally { db.close() }
    }

    @Test fun migrationFromV9PreservesRowsAndDoesNotBackfillSelections() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "artwork-selection-migration-test.db"
        context.deleteDatabase(name)
        try {
            // Start with Room's exact generated schema, remove only the new table to model v9.
            Room.databaseBuilder(context, AuralisDatabase::class.java, name).build().let { db ->
                db.trackDao().upsertTrack(track)
                db.close()
            }
            context.openOrCreateDatabase(name, 0, null).use { sqlite ->
                sqlite.execSQL("DROP TABLE artwork_selections")
                sqlite.execSQL("DELETE FROM room_master_table")
                sqlite.version = 9
            }
            Room.databaseBuilder(context, AuralisDatabase::class.java, name)
                .addMigrations(AuralisDatabase.MIGRATION_9_11).build().let { db ->
                    try {
                        assertEquals(track, db.trackDao().getTrackById(track.id))
                        assertNull(db.trackDao().getArtworkSelection(track.id))
                        assertTrue(db.trackDao().selectArtwork(track, decision, 0))
                    } finally { db.close() }
                }
        } finally { context.deleteDatabase(name) }
    }
    @Test fun migrationFromExperimentalV10ArchivesUntrustedDecisions() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "artwork-selection-v10-compatibility-test.db"
        context.deleteDatabase(name)
        try {
            Room.databaseBuilder(context, AuralisDatabase::class.java, name).build().let { db ->
                db.trackDao().upsertTrack(track); db.close()
            }
            context.openOrCreateDatabase(name, 0, null).use { sqlite ->
                sqlite.execSQL("DROP TABLE artwork_selections")
                sqlite.execSQL("CREATE TABLE artwork_selections (trackId TEXT PRIMARY KEY NOT NULL, artworkUrl TEXT NOT NULL)")
                sqlite.execSQL("CREATE TABLE artwork_candidates (id TEXT PRIMARY KEY NOT NULL, trackId TEXT NOT NULL)")
                sqlite.execSQL("INSERT INTO artwork_selections VALUES ('recording','legacy-art')")
                sqlite.execSQL("INSERT INTO artwork_candidates VALUES ('candidate','recording')")
                sqlite.execSQL("DELETE FROM room_master_table")
                sqlite.version = 10
            }
            Room.databaseBuilder(context, AuralisDatabase::class.java, name)
                .addMigrations(AuralisDatabase.MIGRATION_10_11).build().let { db ->
                    try {
                        assertEquals(track, db.trackDao().getTrackById(track.id))
                        assertNull(db.trackDao().getArtworkSelection(track.id))
                        db.openHelper.readableDatabase.query("SELECT artworkUrl FROM artwork_selections_v10_archive").use {
                            assertTrue(it.moveToFirst()); assertEquals("legacy-art", it.getString(0))
                        }
                        db.openHelper.readableDatabase.query("SELECT count(*) FROM artwork_candidates_v10_archive").use {
                            assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0))
                        }
                    } finally { db.close() }
                }
        } finally { context.deleteDatabase(name) }
    }

}
