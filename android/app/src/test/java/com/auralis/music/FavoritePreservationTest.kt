package com.auralis.music

import com.auralis.music.data.local.dao.TrackDao
import com.auralis.music.data.local.entity.TrackEntity
import com.auralis.music.data.local.mapper.toDomain
import com.auralis.music.data.local.mapper.toEntity
import com.auralis.music.domain.model.Track
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FavoritePreservationTest {

    private class FakeTrackDao : TrackDao {
        val storage = mutableMapOf<String, TrackEntity>()

        override suspend fun upsertTrack(track: TrackEntity) {
            storage[track.id] = track
        }

        override suspend fun upsertTracks(tracks: List<TrackEntity>) {
            for (t in tracks) storage[t.id] = t
        }

        override suspend fun getTrackById(id: String): TrackEntity? = storage[id]

        override suspend fun getTracksByIds(ids: List<String>): List<TrackEntity> =
            ids.mapNotNull { storage[it] }

        override fun getFavoriteTracksFlow(): Flow<List<TrackEntity>> =
            flowOf(storage.values.filter { it.isFavorite }.sortedByDescending { it.favoriteAddedAt })

        override suspend fun getFavoriteTracksList(limit: Int): List<TrackEntity> =
            storage.values.filter { it.isFavorite }.sortedByDescending { it.favoriteAddedAt }.take(limit)

        override fun isFavoriteFlow(id: String): Flow<Boolean?> =
            flowOf(storage[id]?.isFavorite)

        override suspend fun setFavorite(id: String, isFavorite: Boolean, addedAt: Long?) {
            val existing = storage[id]
            if (existing != null) {
                storage[id] = existing.copy(isFavorite = isFavorite, favoriteAddedAt = addedAt)
            }
        }

        override suspend fun deleteTrack(id: String) {
            storage.remove(id)
        }
    }

    @Test
    fun `adding liked track to playlist preserves its favorite status`() = runBlocking {
        val fakeDao = FakeTrackDao()
        val track = Track(
            id = "be-my-baby-123",
            title = "Be My Baby",
            artist = "The Ronettes",
            duration = 160,
            thumbnail = "https://example.com/cover.jpg"
        )

        // 1. User likes the song
        fakeDao.upsertTrack(track.toEntity(isFavorite = true, favoriteAddedAt = System.currentTimeMillis()))
        assertTrue("Track must be marked favorite", fakeDao.getTrackById(track.id)!!.isFavorite)

        // 2. User adds the track to a custom playlist (using preserving upsert)
        fakeDao.upsertTrackPreservingFavorite(track.toEntity())

        // 3. Verify favorite is NOT wiped
        val afterAddToPlaylist = fakeDao.getTrackById(track.id)
        assertTrue("Track MUST remain favorite after adding to playlist", afterAddToPlaylist!!.isFavorite)
        assertTrue("favoriteAddedAt must be preserved", afterAddToPlaylist.favoriteAddedAt != null)
    }

    @Test
    fun `batch adding or reordering tracks preserves favorite status of liked tracks`() = runBlocking {
        val fakeDao = FakeTrackDao()
        val track1 = Track(id = "1", title = "Liked Song", artist = "Artist 1", duration = 100, thumbnail = "")
        val track2 = Track(id = "2", title = "Unliked Song", artist = "Artist 2", duration = 100, thumbnail = "")

        fakeDao.upsertTrack(track1.toEntity(isFavorite = true, favoriteAddedAt = 1000L))
        fakeDao.upsertTrack(track2.toEntity(isFavorite = false, favoriteAddedAt = null))

        // Batch update
        fakeDao.upsertTracksPreservingFavorite(listOf(track1.toEntity(), track2.toEntity()))

        assertTrue("Track 1 must remain favorite", fakeDao.getTrackById("1")!!.isFavorite)
        assertFalse("Track 2 must remain unfavorite", fakeDao.getTrackById("2")!!.isFavorite)
    }
}
