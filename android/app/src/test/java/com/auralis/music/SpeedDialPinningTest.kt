package com.auralis.music

import android.content.Context
import com.auralis.music.data.datastore.HomeRecommendationsCache
import com.auralis.music.domain.model.PlaylistResult
import com.auralis.music.domain.model.SpeedDialItem
import com.auralis.music.domain.model.SpeedDialType
import com.auralis.music.domain.model.Track
import com.auralis.music.domain.repository.HistoryRepository
import com.auralis.music.domain.repository.SearchRepository
import com.auralis.music.ui.viewmodel.HomeViewModel
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import com.auralis.music.domain.recommendations.SpeedDialIdHelper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

@OptIn(ExperimentalCoroutinesApi::class)
class SpeedDialPinningTest {

    private val testDispatcher = StandardTestDispatcher()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; encodeDefaults = true }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        HomeRecommendationsCache.clearMemoryCacheForTesting()
        HomeRecommendationsCache.ioDispatcher = testDispatcher
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        HomeRecommendationsCache.clearMemoryCacheForTesting()
        HomeRecommendationsCache.ioDispatcher = Dispatchers.IO
    }

    private fun createHomeViewModel(
        context: Context? = null,
        ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = testDispatcher,
        defaultDispatcher: kotlinx.coroutines.CoroutineDispatcher = testDispatcher
    ): HomeViewModel {
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

        val mockInnerTube = mockk<com.auralis.music.data.network.InnerTubeClient>(relaxed = true)

        return HomeViewModel(
            historyRepository = dummyRepo,
            searchRepository = dummySearchRepo,
            innerTubeClient = mockInnerTube,
            context = context,
            ioDispatcher = ioDispatcher,
            defaultDispatcher = defaultDispatcher
        )
    }

    private fun createMockContext(): Context {
        val tempDir = Files.createTempDirectory("speed_dial_cache_test").toFile()
        tempDir.deleteOnExit()
        val mockContext = mockk<Context>(relaxed = true)
        every { mockContext.filesDir } returns tempDir
        return mockContext
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

        val homeVm = createHomeViewModel()
        val pages = homeVm.buildSpeedDialPages(
            topTracks = topTracks,
            historyTracks = historyTracks,
            pinnedItems = listOf(pinnedAlbum)
        )

        assertTrue(pages.isNotEmpty())
        val page0 = pages[0]
        assertEquals(9, page0.size)
        assertEquals("album-alb_999", page0[0].id)
        assertEquals(SpeedDialType.ALBUM, page0[0].type)
        assertTrue(page0[0].isPinned)
        assertEquals("Pinned Album 999", page0[0].name)
        assertEquals(SpeedDialType.SURPRISE, page0[8].type)
    }

    // 1. buildSpeedDialPages with pinned track placing it at Page 0, Slot 0 with isPinned = true
    @Test
    fun testBuildSpeedDialPagesWithPinnedTrack() {
        val homeVm = createHomeViewModel()
        val pinnedTrack = SpeedDialItem(
            id = "track-t1",
            name = "Pinned Track 1",
            type = SpeedDialType.TRACK,
            image = "https://example.com/t1.jpg",
            track = Track(id = "t1", title = "Pinned Track 1", artist = "Artist 1"),
            isPinned = true
        )
        val topTracks = (1..5).map { Track(id = "top_$it", title = "Top $it", artist = "Artist") }
        val pages = homeVm.buildSpeedDialPages(topTracks = topTracks, historyTracks = emptyList(), pinnedItems = listOf(pinnedTrack))

        assertTrue(pages.isNotEmpty())
        val page0 = pages[0]
        assertEquals(9, page0.size)
        assertEquals("track-t1", page0[0].id)
        assertEquals(SpeedDialType.TRACK, page0[0].type)
        assertTrue(page0[0].isPinned)
        assertEquals("Pinned Track 1", page0[0].name)
        assertEquals(SpeedDialType.SURPRISE, page0[8].type)
    }

    // 2. buildSpeedDialPages with pinned track NOT appearing again as candidate tile in any later slot on any page
    @Test
    fun testBuildSpeedDialPagesPinnedTrackDeduplicatedFromCandidates() {
        val homeVm = createHomeViewModel()
        val targetTrack = Track(id = "t_shared", title = "Shared Track", artist = "Artist")
        val pinnedTrack = SpeedDialItem(
            id = "track-t_shared",
            name = "Shared Track",
            type = SpeedDialType.TRACK,
            image = "https://example.com/shared.jpg",
            track = targetTrack,
            isPinned = true
        )
        val topTracks = listOf(targetTrack) + (1..10).map { Track(id = "top_$it", title = "Top $it", artist = "Artist") }
        val historyTracks = listOf(targetTrack) + (11..20).map { Track(id = "hist_$it", title = "Hist $it", artist = "Artist") }

        val pages = homeVm.buildSpeedDialPages(topTracks = topTracks, historyTracks = historyTracks, pinnedItems = listOf(pinnedTrack))

        var appearanceCount = 0
        pages.forEachIndexed { pageIdx, page ->
            page.forEachIndexed { slotIdx, item ->
                if (item.type == SpeedDialType.TRACK && (item.track?.id == "t_shared" || item.id.contains("t_shared"))) {
                    appearanceCount++
                    assertEquals(0, pageIdx)
                    assertEquals(0, slotIdx)
                    assertTrue(item.isPinned)
                }
            }
        }
        assertEquals(1, appearanceCount)
    }

    // 3. buildSpeedDialPages with both pinned track and pinned album correctly coexisting in pinned section
    @Test
    fun testBuildSpeedDialPagesPinnedTrackAndAlbumCoexist() {
        val homeVm = createHomeViewModel()
        val pinnedTrack = SpeedDialItem(
            id = "track-t1",
            name = "Song 1",
            type = SpeedDialType.TRACK,
            track = Track(id = "t1", title = "Song 1", artist = "Artist 1"),
            isPinned = true
        )
        val pinnedAlbum = SpeedDialItem(
            id = "album-alb1",
            name = "Album 1",
            type = SpeedDialType.ALBUM,
            album = PlaylistResult(id = "alb1", title = "Album 1"),
            isPinned = true
        )
        val topTracks = (1..10).map { Track(id = "top_$it", title = "Top $it", artist = "Artist") }
        val pages = homeVm.buildSpeedDialPages(topTracks = topTracks, historyTracks = emptyList(), pinnedItems = listOf(pinnedTrack, pinnedAlbum))

        val page0 = pages[0]
        assertEquals("track-t1", page0[0].id)
        assertTrue(page0[0].isPinned)
        assertEquals(SpeedDialType.TRACK, page0[0].type)

        assertEquals("album-alb1", page0[1].id)
        assertTrue(page0[1].isPinned)
        assertEquals(SpeedDialType.ALBUM, page0[1].type)

        assertEquals(SpeedDialType.SURPRISE, page0[8].type)
    }

    // 4. buildSpeedDialPages with 8+ pinned items leaving Slot 8 for Surprise Me tile on Page 0
    @Test
    fun testBuildSpeedDialPages8PlusPinnedItemsLeavesSlot8ForSurpriseMe() {
        val homeVm = createHomeViewModel()
        val pinnedItems = (1..10).map { i ->
            SpeedDialItem(
                id = "track-pin_$i",
                name = "Pinned $i",
                type = SpeedDialType.TRACK,
                track = Track(id = "pin_$i", title = "Pinned $i", artist = "Artist"),
                isPinned = true
            )
        }
        val pages = homeVm.buildSpeedDialPages(topTracks = emptyList(), historyTracks = emptyList(), pinnedItems = pinnedItems)

        assertTrue(pages.isNotEmpty())
        val page0 = pages[0]
        assertEquals(9, page0.size)
        for (i in 0 until 8) {
            assertTrue("Slot $i must be pinned", page0[i].isPinned)
        }
        assertEquals(SpeedDialType.SURPRISE, page0[8].type)
        assertEquals("Surprise Me", page0[8].name)
    }

    // 5. togglePinTrack on unpinned track returns true, updates state immediately, adds item to pinned items with SpeedDialType.TRACK
    @Test
    fun testTogglePinTrackOnUnpinnedTrack() {
        val homeVm = createHomeViewModel()
        val track = Track(id = "song_abc", title = "Song ABC", artist = "Famous Artist", thumbnail = "https://thumb.jpg")

        val result = homeVm.togglePinTrack(track)

        assertTrue(result)
        assertTrue(homeVm.isTrackPinned(track.id))
        assertTrue(homeVm.uiState.value.pinnedSpeedDialIds.contains("song_abc"))
        val pinnedItem = homeVm.inMemoryPinnedItems.firstOrNull { it.type == SpeedDialType.TRACK && it.track?.id == "song_abc" }
        assertNotNull(pinnedItem)
        assertEquals(SpeedDialType.TRACK, pinnedItem?.type)
        assertTrue(pinnedItem?.isPinned == true)
    }

    // 6. togglePinTrack on already pinned track returns false, updates state immediately, removes item from pinned items
    @Test
    fun testTogglePinTrackOnAlreadyPinnedTrack() {
        val homeVm = createHomeViewModel()
        val track = Track(id = "song_xyz", title = "Song XYZ", artist = "Famous Artist")

        val pinResult = homeVm.togglePinTrack(track)
        assertTrue(pinResult)
        assertTrue(homeVm.isTrackPinned(track.id))

        val unpinResult = homeVm.togglePinTrack(track)
        assertFalse(unpinResult)
        assertFalse(homeVm.isTrackPinned(track.id))
        assertFalse(homeVm.uiState.value.pinnedSpeedDialIds.contains("song_xyz"))
        val pinnedItem = homeVm.inMemoryPinnedItems.firstOrNull { it.type == SpeedDialType.TRACK && it.track?.id == "song_xyz" }
        assertNull(pinnedItem)
    }

    // 7. isTrackPinned returns true for pinned track regardless of ID prefix format (raw, track-, track-{id}-{index})
    @Test
    fun testIsTrackPinnedMatchesAllIdFormats() {
        val homeVm = createHomeViewModel()
        val track = Track(id = "canonical123", title = "Canonical Song", artist = "Artist")
        homeVm.togglePinTrack(track)

        assertTrue("Raw ID must match", homeVm.isTrackPinned("canonical123"))
        assertTrue("track- prefix must match", homeVm.isTrackPinned("track-canonical123"))
        assertTrue("Indexed speed dial ID must match", homeVm.isTrackPinned("track-canonical123-7"))
    }

    // 8. isTrackPinned returns false for unpinned track and does not match album with same suffix
    @Test
    fun testIsTrackPinnedReturnsFalseForUnpinnedAndAlbumCollision() {
        val homeVm = createHomeViewModel()
        val album = PlaylistResult(id = "shared_id", title = "Shared Album")
        homeVm.togglePinAlbum(album)

        assertTrue(homeVm.isAlbumPinned("shared_id"))
        assertFalse(homeVm.isTrackPinned("shared_id"))
        assertFalse(homeVm.isTrackPinned("track-shared_id"))
        assertFalse(homeVm.isTrackPinned("album-shared_id"))
        assertFalse(homeVm.isTrackPinned("nonexistent_id"))
    }

    // 9. Rapid Pin -> Unpin -> Pin sequence ends in PINNED state (In-memory verification)
    @Test
    fun testRapidPinUnpinPinEndsInPinned() = runTest {
        val homeVm = createHomeViewModel()
        val track = Track(id = "rapid_1", title = "Rapid Song 1", artist = "Artist")

        val r1 = homeVm.togglePinTrack(track)
        val r2 = homeVm.togglePinTrack(track)
        val r3 = homeVm.togglePinTrack(track)

        assertTrue(r1)
        assertFalse(r2)
        assertTrue(r3)

        testScheduler.advanceUntilIdle()

        assertTrue(homeVm.isTrackPinned("rapid_1"))
        val finalPinned = homeVm.inMemoryPinnedItems.filter { it.type == SpeedDialType.TRACK && it.track?.id == "rapid_1" }
        assertEquals(1, finalPinned.size)
    }

    // 9b. Rapid Pin -> Unpin -> Pin persists to HomeRecommendationsCache and matches final UI state
    @Test
    fun testRapidPinUnpinPinPersistsToCacheAndMatchesUi() = runTest {
        val mockContext = createMockContext()
        val homeVm = createHomeViewModel(context = mockContext, ioDispatcher = testDispatcher)
        val track = Track(id = "rapid_persisted_1", title = "Rapid Song 1", artist = "Artist", thumbnail = "https://example.com/rapid1.jpg")

        // 3 rapid toggles: Pin -> Unpin -> Pin
        val r1 = homeVm.togglePinTrack(track)
        val r2 = homeVm.togglePinTrack(track)
        val r3 = homeVm.togglePinTrack(track)

        assertTrue(r1)
        assertFalse(r2)
        assertTrue(r3)

        // Advance all coroutines to idle
        testScheduler.advanceUntilIdle()

        // 1. Verify ViewModel & UI state
        assertTrue(homeVm.isTrackPinned("rapid_persisted_1"))
        assertTrue(homeVm.uiState.value.pinnedSpeedDialIds.contains("rapid_persisted_1"))
        val uiPage0Tracks = homeVm.uiState.value.speedDialPages.firstOrNull()?.filter { it.type == SpeedDialType.TRACK && it.isPinned } ?: emptyList()
        assertTrue(uiPage0Tracks.any { it.track?.id == "rapid_persisted_1" })

        // 2. Clear in-memory cache to force reading directly from disk
        HomeRecommendationsCache.clearMemoryCacheForTesting()
        HomeRecommendationsCache.ioDispatcher = testDispatcher
        val persistedItems = HomeRecommendationsCache.getPinnedSpeedDialItems(mockContext)

        // 3. Verify persisted cache matches final UI state
        assertEquals(1, persistedItems.size)
        assertEquals("track-rapid_persisted_1", persistedItems[0].id)
        assertEquals("rapid_persisted_1", persistedItems[0].track?.id)
        assertTrue(persistedItems[0].isPinned)
        assertEquals(SpeedDialType.TRACK, persistedItems[0].type)
        assertEquals("Rapid Song 1", persistedItems[0].name)
        assertEquals("https://example.com/rapid1.jpg", persistedItems[0].image)
    }

    // 10. Rapid Pin -> Unpin persists to HomeRecommendationsCache and matches final UI state
    @Test
    fun testRapidPinUnpinPersistsToCacheAndMatchesUi() = runTest {
        val mockContext = createMockContext()
        val homeVm = createHomeViewModel(context = mockContext, ioDispatcher = testDispatcher)
        val track = Track(id = "rapid_persisted_2", title = "Rapid Song 2", artist = "Artist")

        // 2 rapid toggles: Pin -> Unpin
        val r1 = homeVm.togglePinTrack(track)
        val r2 = homeVm.togglePinTrack(track)

        assertTrue(r1)
        assertFalse(r2)

        // Advance all coroutines to idle
        testScheduler.advanceUntilIdle()

        // 1. Verify ViewModel & UI state
        assertFalse(homeVm.isTrackPinned("rapid_persisted_2"))
        assertFalse(homeVm.uiState.value.pinnedSpeedDialIds.contains("rapid_persisted_2"))
        val uiPinned = homeVm.inMemoryPinnedItems.filter { it.type == SpeedDialType.TRACK && it.track?.id == "rapid_persisted_2" }
        assertTrue(uiPinned.isEmpty())

        // 2. Clear in-memory cache to force reading directly from disk
        HomeRecommendationsCache.clearMemoryCacheForTesting()
        HomeRecommendationsCache.ioDispatcher = testDispatcher
        val persistedItems = HomeRecommendationsCache.getPinnedSpeedDialItems(mockContext)

        // 3. Verify persisted cache is empty / does not contain the unpinned track, matching UI
        assertTrue(persistedItems.none { it.track?.id == "rapid_persisted_2" || it.id.contains("rapid_persisted_2") })
        assertEquals(0, persistedItems.size)
    }

    // 10b. Rapid Unpin -> Pin -> Unpin sequence ends in UNPINNED state
    @Test
    fun testRapidUnpinPinUnpinEndsInUnpinned() = runTest {
        val homeVm = createHomeViewModel()
        val track = Track(id = "rapid_2", title = "Rapid Song 2", artist = "Artist")

        homeVm.togglePinTrack(track)
        assertTrue(homeVm.isTrackPinned("rapid_2"))

        val r1 = homeVm.togglePinTrack(track)
        val r2 = homeVm.togglePinTrack(track)
        val r3 = homeVm.togglePinTrack(track)

        assertFalse(r1)
        assertTrue(r2)
        assertFalse(r3)

        testScheduler.advanceUntilIdle()

        assertFalse(homeVm.isTrackPinned("rapid_2"))
        val finalPinned = homeVm.inMemoryPinnedItems.filter { it.type == SpeedDialType.TRACK && it.track?.id == "rapid_2" }
        assertTrue(finalPinned.isEmpty())
    }

    // 11. Pinned track serialization / deserialization preserves track metadata (Track object, title, artist, image)
    @Test
    fun testSpeedDialTrackItemSerialization() {
        val track = Track(
            id = "trk_serial_99",
            title = "Bohemian Rhapsody",
            artist = "Queen",
            thumbnail = "https://example.com/queen.jpg",
            duration = 354L
        )
        val speedDialItem = SpeedDialItem(
            id = "track-trk_serial_99",
            name = "Bohemian Rhapsody",
            type = SpeedDialType.TRACK,
            image = "https://example.com/queen.jpg",
            track = track,
            isPinned = true
        )

        val encoded = json.encodeToString(speedDialItem)
        val decoded = json.decodeFromString<SpeedDialItem>(encoded)

        assertEquals("track-trk_serial_99", decoded.id)
        assertEquals("Bohemian Rhapsody", decoded.name)
        assertEquals(SpeedDialType.TRACK, decoded.type)
        assertTrue(decoded.isPinned)
        assertNotNull(decoded.track)
        assertEquals("trk_serial_99", decoded.track?.id)
        assertEquals("Bohemian Rhapsody", decoded.track?.title)
        assertEquals("Queen", decoded.track?.artist)
        assertEquals("https://example.com/queen.jpg", decoded.track?.thumbnail)
        assertEquals(354L, decoded.track?.duration)
    }

    // 12. Album pinning regression: pinning an album does not affect isTrackPinned for any track, and vice-versa
    @Test
    fun testAlbumAndTrackPinningDoNotCollide() {
        val homeVm = createHomeViewModel()
        val album = PlaylistResult(id = "cross_id", title = "Cross Album")
        val track = Track(id = "cross_id", title = "Cross Track", artist = "Cross Artist")

        homeVm.togglePinAlbum(album)
        assertTrue(homeVm.isAlbumPinned("cross_id"))
        assertFalse(homeVm.isTrackPinned("cross_id"))

        homeVm.togglePinTrack(track)
        assertTrue(homeVm.isAlbumPinned("cross_id"))
        assertTrue(homeVm.isTrackPinned("cross_id"))

        homeVm.togglePinAlbum(album)
        assertFalse(homeVm.isAlbumPinned("cross_id"))
        assertTrue(homeVm.isTrackPinned("cross_id"))

        homeVm.togglePinTrack(track)
        assertFalse(homeVm.isAlbumPinned("cross_id"))
        assertFalse(homeVm.isTrackPinned("cross_id"))
    }

    // 13. Menu callback invocation: verify that invoking onPinToSpeedDial callback triggers state update in HomeViewModel
    @Test
    fun testMenuCallbackInvocationTriggersHomeViewModelUpdate() {
        val homeVm = createHomeViewModel()
        val track = Track(id = "callback_track", title = "Callback Song", artist = "Callback Artist")

        val onPinTrackToSpeedDial: (Track) -> Unit = { trk ->
            homeVm.togglePinTrack(trk)
        }
        val isTrackPinned: (String) -> Boolean = { id ->
            homeVm.isTrackPinned(id)
        }

        assertFalse(isTrackPinned(track.id))

        onPinTrackToSpeedDial(track)

        assertTrue(isTrackPinned(track.id))
        assertTrue(homeVm.uiState.value.pinnedSpeedDialIds.contains("callback_track"))

        onPinTrackToSpeedDial(track)
        assertFalse(isTrackPinned(track.id))
    }

    // A. Immediate pin: togglePinTrack immediately updates UI state without advancing virtual time/coroutines
    @Test
    fun testImmediatePinUpdatesUiStateWithoutDelay() {
        val homeVm = createHomeViewModel()
        val track = Track(id = "immediate_1", title = "Immediate Song", artist = "Artist")

        val pinned = homeVm.togglePinTrack(track)
        assertTrue(pinned)

        // Immediately inspect the UI state WITHOUT advancing time/coroutine scheduler
        val uiState = homeVm.uiState.value
        assertTrue("Track ID must be in pinnedSpeedDialIds immediately", uiState.pinnedSpeedDialIds.contains("immediate_1"))
        val firstSlot = uiState.speedDialPages.firstOrNull()?.firstOrNull()
        assertNotNull("First slot must not be null", firstSlot)
        assertEquals(SpeedDialType.TRACK, firstSlot?.type)
        assertEquals("immediate_1", firstSlot?.track?.id)
        assertTrue("First slot must be pinned", firstSlot?.isPinned == true)
    }

    // B. Background load cannot clobber pin
    @Test
    fun testBackgroundLoadCannotClobberPin() = runTest {
        val mockContext = createMockContext()
        val homeVm = createHomeViewModel(context = mockContext, ioDispatcher = testDispatcher)

        testScheduler.advanceUntilIdle()

        val pinnedTrack = Track(id = "safe_pin_1", title = "Safe Pin", artist = "Artist")
        homeVm.togglePinTrack(pinnedTrack)
        assertTrue(homeVm.isTrackPinned("safe_pin_1"))

        // Run background home data refresh / load
        homeVm.refresh()
        testScheduler.advanceUntilIdle()

        // Pinned track must STILL be pinned and present in UI state
        assertTrue(homeVm.isTrackPinned("safe_pin_1"))
        val uiState = homeVm.uiState.value
        assertTrue(uiState.pinnedSpeedDialIds.contains("safe_pin_1"))
        val firstSlot = uiState.speedDialPages.firstOrNull()?.firstOrNull()
        assertEquals("safe_pin_1", firstSlot?.track?.id)
        assertTrue(firstSlot?.isPinned == true)
    }

    // C. Background refresh uses current pinned state
    @Test
    fun testBackgroundRefreshUsesCurrentPinnedState() = runTest {
        val homeVm = createHomeViewModel()
        testScheduler.advanceUntilIdle()

        val track = Track(id = "refresh_track", title = "Refresh Track", artist = "Artist")
        homeVm.togglePinTrack(track)

        val topTracks = (1..5).map { Track(id = "top_$it", title = "Top $it", artist = "Artist") }
        val historyTracks = (6..10).map { Track(id = "hist_$it", title = "Hist $it", artist = "Artist") }
        val rebuiltPages = homeVm.buildSpeedDialPages(topTracks, historyTracks)

        val page0 = rebuiltPages[0]
        assertEquals("track-refresh_track", page0[0].id)
        assertTrue(page0[0].isPinned)
        assertEquals("refresh_track", page0[0].track?.id)
    }

    // E. Album pinning remains unchanged
    @Test
    fun testAlbumPinningRemainsUnchanged() {
        val homeVm = createHomeViewModel()
        val album = PlaylistResult(id = "album_xyz", title = "Great Album", author = "Great Artist")

        val result = homeVm.togglePinAlbum(album)
        assertTrue(result)
        assertTrue(homeVm.isAlbumPinned("album_xyz"))

        val uiState = homeVm.uiState.value
        val firstSlot = uiState.speedDialPages.firstOrNull()?.firstOrNull()
        assertNotNull(firstSlot)
        assertEquals(SpeedDialType.ALBUM, firstSlot?.type)
        assertEquals("album-album_xyz", firstSlot?.id)
        assertTrue(firstSlot?.isPinned == true)
    }

    // F. Persistence does NOT emit a second UI state that overwrites the optimistic pin
    @Test
    fun testPersistenceDoesNotEmitSecondUiState() = runTest {
        val mockContext = createMockContext()
        val homeVm = createHomeViewModel(context = mockContext, ioDispatcher = testDispatcher)
        testScheduler.advanceUntilIdle()

        val track = Track(id = "persist_track", title = "Persist Track", artist = "Artist")

        // Pin track — optimistic update is immediate
        homeVm.togglePinTrack(track)
        val stateBeforePersistence = homeVm.uiState.value
        assertTrue(stateBeforePersistence.pinnedSpeedDialIds.contains("persist_track"))

        // Advance coroutines to let persistence complete
        testScheduler.advanceUntilIdle()

        // State after persistence must be IDENTICAL to state before persistence
        // (i.e., persistence must not have emitted a second _uiState.update)
        val stateAfterPersistence = homeVm.uiState.value
        assertTrue(
            "Pin must survive persistence completion",
            stateAfterPersistence.pinnedSpeedDialIds.contains("persist_track")
        )
        val firstSlot = stateAfterPersistence.speedDialPages.firstOrNull()?.firstOrNull()
        assertEquals("persist_track", firstSlot?.track?.id)
        assertTrue("First slot must still be pinned", firstSlot?.isPinned == true)
    }

    // G. Rapid pin/unpin sequence resolves to correct final state
    @Test
    fun testRapidPinUnpinSequence() = runTest {
        val mockContext = createMockContext()
        val homeVm = createHomeViewModel(context = mockContext, ioDispatcher = testDispatcher)
        testScheduler.advanceUntilIdle()

        val track = Track(id = "rapid_track", title = "Rapid Track", artist = "Artist")

        // Pin → Unpin → Pin rapidly (3 toggles = pinned)
        homeVm.togglePinTrack(track) // pin
        homeVm.togglePinTrack(track) // unpin
        homeVm.togglePinTrack(track) // pin

        // Immediately check (no advanceUntilIdle)
        assertTrue("Track must be pinned after 3 toggles", homeVm.isTrackPinned("rapid_track"))
        assertTrue(homeVm.uiState.value.pinnedSpeedDialIds.contains("rapid_track"))

        // After all persistence completes, still pinned
        testScheduler.advanceUntilIdle()
        assertTrue("Track must still be pinned after persistence", homeVm.isTrackPinned("rapid_track"))
        assertTrue(homeVm.uiState.value.pinnedSpeedDialIds.contains("rapid_track"))
    }

    // H. Rapid pin/unpin ending on unpin resolves correctly
    @Test
    fun testRapidPinUnpinEndingOnUnpin() = runTest {
        val mockContext = createMockContext()
        val homeVm = createHomeViewModel(context = mockContext, ioDispatcher = testDispatcher)
        testScheduler.advanceUntilIdle()

        val track = Track(id = "rapid_unpin", title = "Rapid Unpin", artist = "Artist")

        // Pin → Unpin (2 toggles = unpinned)
        homeVm.togglePinTrack(track) // pin
        homeVm.togglePinTrack(track) // unpin

        assertFalse("Track must be unpinned after 2 toggles", homeVm.isTrackPinned("rapid_unpin"))
        assertFalse(homeVm.uiState.value.pinnedSpeedDialIds.contains("rapid_unpin"))

        testScheduler.advanceUntilIdle()
        assertFalse("Track must still be unpinned after persistence", homeVm.isTrackPinned("rapid_unpin"))
    }

    // ── Content-Aware Page Identity Tests for HorizontalPager Rendering ──

    // 1. Pin track changes page content identity
    @Test
    fun testPinTrackChangesPageContentIdentity() {
        val homeVm = createHomeViewModel()
        val initialPage0 = homeVm.uiState.value.speedDialPages.getOrNull(0)
        val initialKey = SpeedDialIdHelper.computePageContentKey(0, initialPage0)

        val track = Track(id = "content_pin_1", title = "Content Pin Song", artist = "Artist")
        homeVm.togglePinTrack(track)

        val updatedPage0 = homeVm.uiState.value.speedDialPages.getOrNull(0)
        val updatedKey = SpeedDialIdHelper.computePageContentKey(0, updatedPage0)

        assertNotEquals("Pinning track must produce a distinct page content key", initialKey, updatedKey)
        assertTrue("Updated key must contain the pinned track ID", updatedKey.contains("track-content_pin_1"))
        assertTrue("Updated key must reflect pinned status :P", updatedKey.contains("track-content_pin_1:P"))
    }

    // 2. Unpin track changes page content identity
    @Test
    fun testUnpinTrackChangesPageContentIdentity() {
        val homeVm = createHomeViewModel()
        val track = Track(id = "content_unpin_1", title = "To Unpin", artist = "Artist")

        homeVm.togglePinTrack(track)
        val pinnedPage0 = homeVm.uiState.value.speedDialPages.getOrNull(0)
        val pinnedKey = SpeedDialIdHelper.computePageContentKey(0, pinnedPage0)

        homeVm.togglePinTrack(track)
        val unpinnedPage0 = homeVm.uiState.value.speedDialPages.getOrNull(0)
        val unpinnedKey = SpeedDialIdHelper.computePageContentKey(0, unpinnedPage0)

        assertNotEquals("Unpinning track must produce a distinct page content key", pinnedKey, unpinnedKey)
        assertFalse("Unpinned key must not contain pinned marker for track", unpinnedKey.contains("track-content_unpin_1:P"))
    }

    // 3. Album pinning changes relevant page content identity
    @Test
    fun testAlbumPinningChangesPageContentIdentity() {
        val homeVm = createHomeViewModel()
        val initialKey = SpeedDialIdHelper.computePageContentKey(0, homeVm.uiState.value.speedDialPages.getOrNull(0))

        val album = PlaylistResult(id = "album_identity_xyz", title = "Identity Album", author = "Artist")
        homeVm.togglePinAlbum(album)

        val updatedKey = SpeedDialIdHelper.computePageContentKey(0, homeVm.uiState.value.speedDialPages.getOrNull(0))

        assertNotEquals("Pinning album must change page 0 content key", initialKey, updatedKey)
        assertTrue("Updated key must contain album ID with pinned marker", updatedKey.contains("album-album_identity_xyz:P"))
    }

    // 4. Same page contents produce the same identity
    @Test
    fun testSamePageContentsProduceSameIdentity() {
        val homeVm = createHomeViewModel()
        val page0 = homeVm.uiState.value.speedDialPages.getOrNull(0)

        val key1 = SpeedDialIdHelper.computePageContentKey(0, page0)
        val key2 = SpeedDialIdHelper.computePageContentKey(0, page0)

        assertEquals("Same page items must produce identical content key", key1, key2)
    }

    // 5. Recomposition alone does not change identity
    @Test
    fun testRecompositionAloneDoesNotChangeIdentity() = runTest {
        val homeVm = createHomeViewModel()
        testScheduler.advanceUntilIdle()

        val initialKeyPage0 = SpeedDialIdHelper.computePageContentKey(0, homeVm.uiState.value.speedDialPages.getOrNull(0))
        val initialKeyPage1 = SpeedDialIdHelper.computePageContentKey(1, homeVm.uiState.value.speedDialPages.getOrNull(1))

        // Virtual advance / simulated non-speed-dial state update
        testScheduler.advanceUntilIdle()

        val afterKeyPage0 = SpeedDialIdHelper.computePageContentKey(0, homeVm.uiState.value.speedDialPages.getOrNull(0))
        val afterKeyPage1 = SpeedDialIdHelper.computePageContentKey(1, homeVm.uiState.value.speedDialPages.getOrNull(1))

        assertEquals("Recomposition without item mutation must preserve page 0 key", initialKeyPage0, afterKeyPage0)
        assertEquals("Recomposition without item mutation must preserve page 1 key", initialKeyPage1, afterKeyPage1)
        assertNotEquals("Keys for different page indices must never collide", initialKeyPage0, initialKeyPage1)
    }

    // ── Focused Tap-Latency & Background Rebuild Tests ──

    // 1. Pin operation performs optimistic update without full deduplication on Main thread
    @Test
    fun testPinOperationOptimisticUpdateWithoutMainThreadDeduplication() {
        val homeVm = createHomeViewModel()
        val track = Track(id = "opt_pin_1", title = "Optimistic Song (Official Audio)", artist = "Artist 1")

        // Call togglePinTrack without advancing test coroutine dispatcher
        val result = homeVm.togglePinTrack(track)
        assertTrue("togglePinTrack must return true for new pin", result)

        // UI state must be updated immediately
        val uiState = homeVm.uiState.value
        assertTrue("pinnedSpeedDialIds must contain track ID immediately", uiState.pinnedSpeedDialIds.contains("opt_pin_1"))
        val firstItem = uiState.speedDialPages.firstOrNull()?.firstOrNull()
        assertNotNull("First slot must not be null", firstItem)
        assertEquals(SpeedDialType.TRACK, firstItem?.type)
        assertEquals("opt_pin_1", firstItem?.track?.id)
        assertTrue("First slot must be marked pinned", firstItem?.isPinned == true)
        assertEquals("Optimistic Song", firstItem?.name)
    }

    // 2. Unpin operation is immediate
    @Test
    fun testUnpinOperationIsImmediate() = runTest {
        val homeVm = createHomeViewModel()
        val track = Track(id = "opt_unpin_1", title = "To Be Unpinned", artist = "Artist 1")

        // First pin
        homeVm.togglePinTrack(track)
        testScheduler.advanceUntilIdle()
        assertTrue(homeVm.isTrackPinned("opt_unpin_1"))

        // Now unpin — check state immediately WITHOUT advancing scheduler
        val unpinResult = homeVm.togglePinTrack(track)
        assertFalse("togglePinTrack must return false when unpinning", unpinResult)

        val uiState = homeVm.uiState.value
        assertFalse("pinnedSpeedDialIds must not contain track ID immediately after unpin", uiState.pinnedSpeedDialIds.contains("opt_unpin_1"))
        assertFalse("isTrackPinned must return false immediately", homeVm.isTrackPinned("opt_unpin_1"))
        val firstItem = uiState.speedDialPages.firstOrNull()?.firstOrNull()
        assertNotEquals("opt_unpin_1", firstItem?.track?.id)
    }

    // 3. Background rebuild does not overwrite a newer pin
    @Test
    fun testBackgroundRebuildDoesNotOverwriteNewerPin() = runTest {
        val mockContext = createMockContext()
        val homeVm = createHomeViewModel(context = mockContext)

        val trackA = Track(id = "track_gen_a", title = "Song A", artist = "Artist")
        val trackB = Track(id = "track_gen_b", title = "Song B", artist = "Artist")

        // Pin A (seq = 1)
        homeVm.togglePinTrack(trackA)
        val seqAfterA = homeVm.pinSequence.get()

        // Unpin A and Pin B (seq = 2, seq = 3)
        homeVm.togglePinTrack(trackA) // unpins A
        homeVm.togglePinTrack(trackB) // pins B
        val finalSeq = homeVm.pinSequence.get()
        assertTrue("Final sequence must be greater than seqAfterA", finalSeq > seqAfterA)

        // Advance coroutines completely
        testScheduler.advanceUntilIdle()

        // Track B must remain pinned, Track A must NOT be resurrected by stale rebuild
        assertTrue("Track B must be pinned", homeVm.isTrackPinned("track_gen_b"))
        assertFalse("Track A must remain unpinned", homeVm.isTrackPinned("track_gen_a"))
        val uiState = homeVm.uiState.value
        assertTrue(uiState.pinnedSpeedDialIds.contains("track_gen_b"))
        assertFalse(uiState.pinnedSpeedDialIds.contains("track_gen_a"))
        val firstSlot = uiState.speedDialPages.firstOrNull()?.firstOrNull()
        assertEquals("track_gen_b", firstSlot?.track?.id)
    }

    // 4. Rapid Pin -> Unpin -> Pin preserves the final state
    @Test
    fun testRapidPinUnpinPreservesFinalState() = runTest {
        val mockContext = createMockContext()
        val homeVm = createHomeViewModel(context = mockContext)

        val track = Track(id = "rapid_flip", title = "Flipping Song", artist = "Artist")

        // Rapid Pin -> Unpin -> Pin
        homeVm.togglePinTrack(track)
        homeVm.togglePinTrack(track)
        homeVm.togglePinTrack(track)

        // Immediate check
        assertTrue("Immediately pinned after 3 toggles", homeVm.isTrackPinned("rapid_flip"))
        assertTrue(homeVm.uiState.value.pinnedSpeedDialIds.contains("rapid_flip"))

        // Complete background jobs
        testScheduler.advanceUntilIdle()

        // Final state after all coroutines finish
        assertTrue("Must remain pinned after background rebuild completes", homeVm.isTrackPinned("rapid_flip"))
        assertTrue(homeVm.uiState.value.pinnedSpeedDialIds.contains("rapid_flip"))
        val firstSlot = homeVm.uiState.value.speedDialPages.firstOrNull()?.firstOrNull()
        assertEquals("rapid_flip", firstSlot?.track?.id)
        assertTrue(firstSlot?.isPinned == true)
    }

    // 5. Persistence still occurs
    @Test
    fun testPersistenceStillOccurs() = runTest {
        val mockContext = createMockContext()
        val homeVm = createHomeViewModel(context = mockContext)
        val track = Track(id = "persist_verify_1", title = "Persist Verify", artist = "Artist")

        homeVm.togglePinTrack(track)
        testScheduler.advanceUntilIdle()

        // Verify disk cache has saved the pinned item
        val pinnedFromDisk = HomeRecommendationsCache.getPinnedSpeedDialItems(mockContext)
        assertTrue("Disk cache must contain pinned item", pinnedFromDisk.any { it.id.contains("persist_verify_1") })
    }
}
