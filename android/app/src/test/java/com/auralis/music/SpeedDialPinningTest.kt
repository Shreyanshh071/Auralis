package com.auralis.music

import com.auralis.music.domain.model.PlaylistResult
import com.auralis.music.domain.model.SpeedDialItem
import com.auralis.music.domain.model.SpeedDialType
import com.auralis.music.domain.model.Track
import com.auralis.music.domain.repository.HistoryRepository
import com.auralis.music.domain.repository.SearchRepository
import com.auralis.music.ui.viewmodel.HomeViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SpeedDialPinningTest {

    private val testDispatcher = StandardTestDispatcher()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testSpeedDialAlbumItemSerialization() {
        val album = PlaylistResult(
            id = "MPREb_12345",
            title = "25 (Deluxe Edition)",
            author = "Adele",
            thumbnail = "https://example.com/adele25.jpg",
            trackCount = 14
        )
        val speedDialItem = SpeedDialItem(
            id = "album-MPREb_12345",
            name = "25 (Deluxe Edition)",
            type = SpeedDialType.ALBUM,
            image = "https://example.com/adele25.jpg",
            album = album,
            isPinned = true
        )

        val encoded = json.encodeToString(speedDialItem)
        val decoded = json.decodeFromString<SpeedDialItem>(encoded)

        assertEquals("album-MPREb_12345", decoded.id)
        assertEquals("25 (Deluxe Edition)", decoded.name)
        assertEquals(SpeedDialType.ALBUM, decoded.type)
        assertTrue(decoded.isPinned)
        assertNotNull(decoded.album)
        assertEquals("MPREb_12345", decoded.album?.id)
        assertEquals("Adele", decoded.album?.author)
        assertEquals(14, decoded.album?.trackCount)
    }

    @Test
    fun testBuildSpeedDialPagesWithPinnedAlbum() {
        val topTracks = (1..10).map {
            Track(id = "top_$it", title = "Top Song $it", artist = "Artist $it")
        }
        val historyTracks = (11..20).map {
            Track(id = "hist_$it", title = "History Song $it", artist = "Artist $it")
        }

        val pinnedAlbum = SpeedDialItem(
            id = "album-alb_999",
            name = "Pinned Album 999",
            type = SpeedDialType.ALBUM,
            image = "https://example.com/art.jpg",
            album = PlaylistResult(id = "alb_999", title = "Pinned Album 999", author = "Great Artist"),
            isPinned = true
        )

        val dummyRepo = object : HistoryRepository {
            override fun getHistory() = flowOf(emptyList<com.auralis.music.domain.model.HistoryEntry>())
            override suspend fun addToHistory(track: Track) {}
            override suspend fun removeFromHistory(trackId: String) {}
            override suspend fun clearHistory() {}
            override fun getTopPlayedTracks() = flowOf(emptyList<com.auralis.music.domain.model.PlayCountEntry>())
            override suspend fun recordPlay(track: Track) {}
            override suspend fun getPlayCounts() = emptyList<com.auralis.music.domain.model.PlayCountEntry>()
            override suspend fun getForgottenFavorites(cutoffTimestamp: Long) = emptyList<Track>()
            override suspend fun getRecentHeavyRotation(fromTimestamp: Long) = emptyList<Track>()
            override suspend fun getLikedSeeds(limit: Int) = emptyList<Track>()
        }
        val dummySearchRepo = object : SearchRepository {
            override suspend fun search(query: String) = com.auralis.music.domain.model.SearchResults()
            override suspend fun searchSongs(query: String) = emptyList<Track>()
            override suspend fun searchAlbums(query: String) = emptyList<PlaylistResult>()
            override suspend fun searchArtists(query: String) = emptyList<com.auralis.music.domain.model.Artist>()
            override suspend fun searchPlaylists(query: String) = emptyList<PlaylistResult>()
            override suspend fun getSuggestions(query: String) = emptyList<String>()
            override suspend fun getArtistPage(artist: com.auralis.music.domain.model.Artist) = null
            override suspend fun getAlbumTracks(album: PlaylistResult) = emptyList<Track>()
            override fun getRecentSearchQueries() = flowOf(emptyList<String>())
            override suspend fun recordSearchQuery(query: String) {}
            override suspend fun removeSearchQuery(query: String) {}
            override suspend fun clearSearchHistory() {}
        }

        val homeVm = HomeViewModel(
            historyRepository = dummyRepo,
            searchRepository = dummySearchRepo,
            context = null
        )

        val pages = homeVm.buildSpeedDialPages(
            topTracks = topTracks,
            historyTracks = historyTracks,
            pinnedItems = listOf(pinnedAlbum)
        )

        assertTrue(pages.isNotEmpty())
        val page0 = pages[0]
        // Page 0 must have exactly 9 slots
        assertEquals(9, page0.size)
        // First slot must be our pinned album!
        assertEquals("album-alb_999", page0[0].id)
        assertEquals(SpeedDialType.ALBUM, page0[0].type)
        assertTrue(page0[0].isPinned)
        assertEquals("Pinned Album 999", page0[0].name)

        // Slot 8 (index 8) must be the Surprise Me dice tile
        assertEquals(SpeedDialType.SURPRISE, page0[8].type)
    }
}
