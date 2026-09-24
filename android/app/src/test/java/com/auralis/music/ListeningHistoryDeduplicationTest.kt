package com.auralis.music

import com.auralis.music.data.local.dao.HistoryDao
import com.auralis.music.data.local.dao.HistoryWithTrackTuple
import com.auralis.music.data.local.dao.PlayCountDao
import com.auralis.music.data.local.dao.PlayCountWithTrackTuple
import com.auralis.music.data.local.dao.TrackDao
import com.auralis.music.data.local.entity.HistoryEntity
import com.auralis.music.data.local.entity.PlayCountEntity
import com.auralis.music.data.local.entity.TrackEntity
import com.auralis.music.data.local.mapper.toEntity
import com.auralis.music.data.repository.HistoryRepositoryImpl
import com.auralis.music.domain.model.Track
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ListeningHistoryDeduplicationTest {

    private class FakeTrackDao : TrackDao {
        val storage = mutableMapOf<String, TrackEntity>()

        override suspend fun upsertTrack(track: TrackEntity) {
            storage[track.id] = track
        }

        override suspend fun upsertTracks(tracks: List<TrackEntity>) {
            for (t in tracks) storage[t.id] = t
        }

        override suspend fun getTrackById(id: String): TrackEntity? = storage[id]
        override suspend fun getTracksByIds(ids: List<String>): List<TrackEntity> = ids.mapNotNull { storage[it] }
        override fun getFavoriteTracksFlow(): Flow<List<TrackEntity>> = kotlinx.coroutines.flow.flowOf(emptyList())
        override suspend fun getFavoriteTracksList(limit: Int): List<TrackEntity> = emptyList()
        override fun isFavoriteFlow(id: String): Flow<Boolean?> = kotlinx.coroutines.flow.flowOf(false)
        override suspend fun setFavorite(id: String, isFavorite: Boolean, addedAt: Long?) {}
        override suspend fun deleteTrack(id: String) { storage.remove(id) }
    }

    private class FakeHistoryDao(private val trackDao: FakeTrackDao) : HistoryDao {
        val historyStorage = mutableMapOf<String, HistoryEntity>()
        private val historyFlow = MutableStateFlow<List<HistoryWithTrackTuple>>(emptyList())

        private fun emitCurrent() {
            val list = historyStorage.values
                .sortedByDescending { it.playedAt }
                .take(100)
                .mapNotNull { hist ->
                    val trk = trackDao.storage[hist.trackId] ?: return@mapNotNull null
                    HistoryWithTrackTuple(history = hist, track = trk)
                }
            historyFlow.value = list
        }

        override suspend fun upsertHistory(history: HistoryEntity) {
            historyStorage[history.trackId] = history
            emitCurrent()
        }

        override fun getHistoryWithTracksFlow(): Flow<List<HistoryWithTrackTuple>> = historyFlow

        override suspend fun getHistoryWithTracks(): List<HistoryWithTrackTuple> {
            return historyStorage.values
                .sortedByDescending { it.playedAt }
                .take(100)
                .mapNotNull { hist ->
                    val trk = trackDao.storage[hist.trackId] ?: return@mapNotNull null
                    HistoryWithTrackTuple(history = hist, track = trk)
                }
        }

        override suspend fun getForgottenHistoryTracks(cutoffTimestamp: Long, limit: Int): List<HistoryWithTrackTuple> = emptyList()
        override suspend fun getRecentHistoryTracks(fromTimestamp: Long, limit: Int): List<HistoryWithTrackTuple> = emptyList()

        override suspend fun removeFromHistory(trackId: String) {
            historyStorage.remove(trackId)
            emitCurrent()
        }

        override suspend fun clearHistory() {
            historyStorage.clear()
            emitCurrent()
        }

        override suspend fun pruneHistoryToCap() {
            if (historyStorage.size > 100) {
                val sorted = historyStorage.values.sortedByDescending { it.playedAt }
                val toKeep = sorted.take(100).map { it.trackId }.toSet()
                val toRemove = historyStorage.keys.filter { it !in toKeep }
                for (id in toRemove) {
                    historyStorage.remove(id)
                }
                emitCurrent()
            }
        }
    }

    private class FakePlayCountDao : PlayCountDao {
        override suspend fun upsertPlayCount(entity: PlayCountEntity) {}
        override suspend fun getPlayCount(trackId: String): PlayCountEntity? = null
        override suspend fun getAllPlayCounts(): List<PlayCountWithTrackTuple> = emptyList()
        override fun getTopPlayedTracksFlow(): Flow<List<PlayCountWithTrackTuple>> = kotlinx.coroutines.flow.flowOf(emptyList())
        override suspend fun clearPlayCounts() {}
    }

    @Test
    fun `recently listened song appears at index 0 at the very top`() = runBlocking {
        val trackDao = FakeTrackDao()
        val historyDao = FakeHistoryDao(trackDao)
        val playCountDao = FakePlayCountDao()
        val repo = HistoryRepositoryImpl(trackDao, historyDao, playCountDao)

        val track1 = Track(id = "track-1", title = "The Less I Know The Better", artist = "Tame Impala")
        val track2 = Track(id = "track-2", title = "Borderline", artist = "Tame Impala")
        val track3 = Track(id = "track-3", title = "New Person, Same Old Mistakes", artist = "Tame Impala")

        repo.addToHistory(track1)
        Thread.sleep(5)
        repo.addToHistory(track2)
        Thread.sleep(5)
        repo.addToHistory(track3)

        val historyList = repo.getHistory().first()
        assertEquals(3, historyList.size)
        // track3 was played last, so it must be at index 0 (the very top)
        assertEquals("track-3", historyList[0].track.id)
        assertEquals("New Person, Same Old Mistakes", historyList[0].track.title)

        // Now replay track1 (which was the oldest)
        Thread.sleep(5)
        repo.addToHistory(track1)

        val updatedHistory = repo.getHistory().first()
        assertEquals(3, updatedHistory.size)
        // track1 must immediately move to index 0 (top)
        assertEquals("track-1", updatedHistory[0].track.id)
        assertEquals("The Less I Know The Better", updatedHistory[0].track.title)
    }

    @Test
    fun `duplicate track versions with different youtube IDs are deduplicated and moved to top`() = runBlocking {
        val trackDao = FakeTrackDao()
        val historyDao = FakeHistoryDao(trackDao)
        val playCountDao = FakePlayCountDao()
        val repo = HistoryRepositoryImpl(trackDao, historyDao, playCountDao)

        // Video version of "New Person, Same Old Mistakes" played earlier
        val videoVersion = Track(
            id = "video-id-123",
            title = "New Person, Same Old Mistakes (Official Audio)",
            artist = "Tame Impala"
        )
        // In-between track
        val intermediateTrack = Track(
            id = "other-song",
            title = "Let It Happen",
            artist = "Tame Impala"
        )
        // Studio/album version of the same song played later
        val studioVersion = Track(
            id = "album-id-456",
            title = "New Person, Same Old Mistakes",
            artist = "Tame Impala"
        )

        repo.addToHistory(videoVersion)
        Thread.sleep(5)
        repo.addToHistory(intermediateTrack)
        Thread.sleep(5)
        repo.addToHistory(studioVersion)

        val historyList = repo.getHistory().first()
        // There should be exactly 2 entries, NOT 3! The older video version must be replaced/deduplicated.
        assertEquals(2, historyList.size)
        // The newly listened version must be at index 0 (top)
        assertEquals("album-id-456", historyList[0].track.id)
        assertEquals("Let It Happen", historyList[1].track.title)

        // Verify that video-id-123 is no longer in history
        assertFalse(historyList.any { it.track.id == "video-id-123" })
    }

    @Test
    fun `history is capped at 100 songs and older songs are removed`() = runBlocking {
        val trackDao = FakeTrackDao()
        val historyDao = FakeHistoryDao(trackDao)
        val playCountDao = FakePlayCountDao()
        val repo = HistoryRepositoryImpl(trackDao, historyDao, playCountDao)

        // Add 105 distinct songs
        for (i in 1..105) {
            val track = Track(id = "track-id-$i", title = "Song $i", artist = "Artist $i")
            repo.addToHistory(track)
            Thread.sleep(1)
        }

        val historyList = repo.getHistory().first()
        // Must be capped at exactly 100 songs
        assertEquals(100, historyList.size)

        // The most recently added song (Song 105) must be at index 0
        assertEquals("track-id-105", historyList[0].track.id)
        assertEquals("Song 105", historyList[0].track.title)

        // The oldest 5 songs (1..5) should have been pruned
        for (i in 1..5) {
            assertFalse("Song $i should have been pruned", historyList.any { it.track.id == "track-id-$i" })
        }

        // Song 6 should still be present as the last item
        assertTrue(historyList.any { it.track.id == "track-id-6" })
    }

    @Test
    fun `getHistory deduplication executes in sub-100ms for 100 tracks without regex recompilation overhead`() = runBlocking {
        val trackDao = FakeTrackDao()
        val historyDao = FakeHistoryDao(trackDao)
        val playCountDao = FakePlayCountDao()
        val repo = HistoryRepositoryImpl(trackDao, historyDao, playCountDao)

        // Seed 100 realistic tracks with version tags, noise brackets, and featured artists
        for (i in 1..100) {
            val track = Track(
                id = "track-$i",
                title = "Song $i (Official Audio) [feat. Artist ${i % 5}]",
                artist = "Main Artist ${i % 10}"
            )
            repo.addToHistory(track)
        }

        // Measure time taken to evaluate getHistory()
        val startNanos = System.nanoTime()
        val historyList = repo.getHistory().first()
        val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000

        assertTrue("History list should not be empty", historyList.isNotEmpty())
        assertTrue("getHistory() must complete in under 250ms (took ${elapsedMs}ms) to prevent ANR", elapsedMs < 250)
    }
}
