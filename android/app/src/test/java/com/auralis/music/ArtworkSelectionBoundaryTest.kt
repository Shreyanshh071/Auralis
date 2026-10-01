package com.auralis.music

import com.auralis.music.data.local.dao.TrackDao
import com.auralis.music.data.local.entity.*
import com.auralis.music.data.local.mapper.toDomain
import com.auralis.music.data.repository.ArtworkSelectionRepository
import com.auralis.music.domain.artwork.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ArtworkSelectionBoundaryTest {
    private class Store : TrackDao() {
        val tracks = mutableMapOf<String, TrackEntity>()
        val selections = mutableMapOf<String, ArtworkSelectionEntity>()
        override suspend fun writeTrack(track: TrackEntity) { tracks[track.id] = track }
        override suspend fun writeTracks(tracks: List<TrackEntity>) { tracks.forEach { writeTrack(it) } }
        override suspend fun getArtworkSelections(ids: List<String>) = ids.mapNotNull { selections[it] }
        override suspend fun writeArtworkSelection(selection: ArtworkSelectionEntity) { selections[selection.trackId] = selection }
        override suspend fun getArtworkSelection(trackId: String) = selections[trackId]
        override fun observeArtworkSelection(trackId: String) = flowOf(selections[trackId])
        override suspend fun getTrackById(id: String) = tracks[id]
        override suspend fun getTracksByIds(ids: List<String>) = ids.mapNotNull { tracks[it] }
        override fun getFavoriteTracksFlow(): Flow<List<TrackEntity>> = flowOf(tracks.values.filter { it.isFavorite })
        override suspend fun getFavoriteTracksList(limit: Int) = tracks.values.filter { it.isFavorite }.take(limit)
        override fun isFavoriteFlow(id: String) = flowOf(tracks[id]?.isFavorite)
        override suspend fun setFavorite(id: String, isFavorite: Boolean, addedAt: Long?) {
            tracks[id]?.let { tracks[id] = it.copy(isFavorite = isFavorite, favoriteAddedAt = addedAt) }
        }
        override suspend fun deleteTrack(id: String) { tracks.remove(id); selections.remove(id) }
    }
    private val original = TrackEntity("video-one", "Song", "Artist", "Release", 240,
        "https://example.test/original.jpg", isFavorite = true, favoriteAddedAt = 123)
    private fun decision(id: String = original.id) = ArtworkSelection(id,
        "https://example.test/selected.jpg", "Chosen release", "spotify", "provider-recording-1",
        "release-1", "Exact recording/release checked by caller", ArtworkSelectionBasis.VERIFIED_RELEASE)

    @Test fun `all complete write entry points preserve selection and favorite semantics`() = runBlocking {
        val writers: List<suspend (Store, TrackEntity) -> Unit> = listOf(
            { d, t -> d.upsertTrack(t) }, { d, t -> d.upsertTracks(listOf(t)) },
            { d, t -> d.upsertTrackPreservingFavorite(t) }, { d, t -> d.upsertTracksPreservingFavorite(listOf(t)) }
        )
        writers.forEachIndexed { index, writer ->
            val db = Store(); db.upsertTrack(original)
            assertTrue(db.selectArtwork(original, decision(), 0))
            writer(db, original.copy(album = "Collection", thumbnail = "wrong", isFavorite = false, favoriteAddedAt = null))
            val saved = db.tracks.getValue(original.id)
            assertEquals(decision().thumbnail, saved.thumbnail)
            assertEquals("Chosen release", saved.album)
            assertEquals(index >= 2, saved.isFavorite)
            assertEquals(if (index >= 2) 123L else null, saved.favoriteAddedAt)
        }
    }

    @Test fun `legacy URLs are not automatically selections and retain baseline writes`() = runBlocking {
        val db = Store(); db.upsertTrack(original)
        val imported = original.copy(album = "Spotify supplied release", thumbnail = "spotify-image")
        db.upsertTrack(imported)
        assertEquals(imported, db.tracks[original.id]); assertNull(db.getArtworkSelection(original.id))
    }

    @Test fun `repository selection preserves playback identity and records independent provenance`() = runBlocking {
        val db = Store(); db.upsertTrack(original)
        val repo = ArtworkSelectionRepository(db)
        assertTrue(repo.select(original.toDomain(), decision()))
        val result = repo.get(original.id)!!
        assertEquals(1L, result.revision); assertEquals("provider-recording-1", result.providerItemId)
        val stored = db.tracks.getValue(original.id)
        assertEquals(original.id, stored.id); assertEquals(original.source, stored.source)
        assertEquals(original.duration, stored.duration); assertEquals(original.title, stored.title)
        assertEquals(original.artist, stored.artist); assertTrue(stored.isFavorite)
    }

    @Test fun `a late resolver snapshot cannot replace a newer decision`() = runBlocking {
        val db = Store(); db.upsertTrack(original)
        assertTrue(db.selectArtwork(original, decision(), 0))
        assertFalse(db.selectArtwork(original, decision().copy(thumbnail = "late"), 0))
        assertFalse(db.selectArtwork(original, decision().copy(thumbnail = "late"), 1))
        assertEquals(decision().thumbnail, db.tracks[original.id]?.thumbnail)
    }

    @Test fun `explicit replacement requires current snapshot and revision`() = runBlocking {
        val db = Store(); db.upsertTrack(original)
        assertTrue(db.selectArtwork(original, decision(), 0))
        val current = db.tracks.getValue(original.id)
        assertFalse(db.selectArtwork(current, decision().copy(thumbnail = "new"), 0))
        assertTrue(db.selectArtwork(current, decision().copy(thumbnail = "new"), 1))
        assertEquals(2L, db.getArtworkSelection(original.id)?.revision)
    }

    @Test fun `same title on different IDs never shares a selection`() = runBlocking {
        val db = Store(); val other = original.copy(id = "video-two", artist = "Other artist", thumbnail = "other")
        db.upsertTracks(listOf(original, other))
        assertTrue(db.selectArtwork(original, decision(), 0))
        assertFalse(db.selectArtwork(other, decision(), 0))
        db.upsertTracks(listOf(original.copy(thumbnail = "stale"), other))
        assertEquals(other, db.tracks[other.id]); assertNull(db.getArtworkSelection(other.id))
    }

    @Test fun `reused playback ID cannot bind selected artwork to another recording`() = runBlocking {
        val db = Store(); db.upsertTrack(original)
        assertTrue(db.selectArtwork(original, decision(), 0))
        val incoming = original.copy(title = "Different song", artist = "Other", duration = 280, thumbnail = "wrong")
        db.upsertTrack(incoming)
        val stored = db.tracks.getValue(original.id)
        assertEquals(original.title, stored.title); assertEquals(original.artist, stored.artist)
        assertEquals(original.duration, stored.duration); assertEquals(decision().thumbnail, stored.thumbnail)
        assertFalse(db.selectArtwork(incoming, decision(), 1))
    }

    @Test fun `selection rejects missing identity evidence or unverified release ID`() = runBlocking {
        val db = Store(); db.upsertTrack(original)
        for (invalid in listOf(decision().copy(thumbnail = ""), decision().copy(providerItemId = ""),
            decision().copy(evidence = ""), decision().copy(releaseId = null))) {
            assertFalse(db.selectArtwork(original, invalid, 0))
        }
        assertEquals(original, db.tracks[original.id]); assertNull(db.getArtworkSelection(original.id))
    }

    @Test fun `selection fails if import updated release while verification was in flight`() = runBlocking {
        val db = Store(); db.upsertTrack(original)
        db.upsertTrack(original.copy(album = "New release", thumbnail = "new"))
        assertFalse(db.selectArtwork(original, decision(), 0))
    }

    @Test fun `manual decision needs no catalog release ID and can retain existing album`() = runBlocking {
        val db = Store(); db.upsertTrack(original)
        assertTrue(db.selectArtwork(original, decision().copy(basis = ArtworkSelectionBasis.USER_SELECTION,
            releaseId = null, album = null), 0))
        assertEquals(original.album, db.tracks[original.id]?.album)
    }

    @Test fun `deleting track removes selection and recreation has no inherited authority`() = runBlocking {
        val db = Store(); db.upsertTrack(original); assertTrue(db.selectArtwork(original, decision(), 0))
        db.deleteTrack(original.id); db.upsertTrack(original)
        assertNull(db.getArtworkSelection(original.id)); assertEquals(original, db.tracks[original.id])
    }

    @Test fun `mixed library preserves unselected rows and selections across batch history writes`() = runBlocking {
        val db = Store(); val tracks = (0 until 580).map { original.copy(id = "id-$it", thumbnail = "art-$it") }
        db.upsertTracks(tracks)
        val selected = tracks.filterIndexed { index, _ -> index % 5 == 0 }
        selected.forEach { assertTrue(db.selectArtwork(it, decision(it.id), 0)) }
        db.upsertTracksPreservingFavorite(tracks)
        tracks.forEach {
            assertEquals(if (it in selected) decision().thumbnail else it.thumbnail, db.tracks[it.id]?.thumbnail)
            assertTrue(db.tracks.getValue(it.id).isFavorite)
        }
        assertEquals(116, db.selections.size)
    }
}
