package com.auralis.music.desktop.browse

import com.auralis.music.domain.model.*
import com.auralis.music.domain.repository.SearchRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class DesktopArtistPhotosTest {
    private val artist = Artist("UCexample", "Example Artist", thumbnail = "original")
    private val preview = ArtistPage(artist, bannerUrl = "original", topSongs = listOf(Track("song", "Example song", artist.name)))

    private class Repository(val load: suspend (suspend (ArtistPage) -> Unit) -> ArtistPage) : SearchRepository {
        override suspend fun getArtistPage(artist: Artist) = load { }
        override suspend fun getArtistPage(artist: Artist, onPreview: suspend (ArtistPage) -> Unit) = load(onPreview)
        override suspend fun search(query: String) = SearchResults()
        override suspend fun searchSongs(query: String) = emptyList<Track>()
        override suspend fun searchAlbums(query: String) = emptyList<PlaylistResult>()
        override suspend fun searchArtists(query: String) = emptyList<Artist>()
        override suspend fun searchPlaylists(query: String) = emptyList<PlaylistResult>()
        override suspend fun getSuggestions(query: String) = emptyList<String>()
        override suspend fun getAlbumTracks(album: PlaylistResult) = emptyList<Track>()
        override fun getRecentSearchQueries() = flowOf(emptyList<String>())
        override suspend fun recordSearchQuery(query: String) { }
        override suspend fun removeSearchQuery(query: String) { }
        override suspend fun clearSearchHistory() { }
    }

    @Test fun photoUpgradesDuringCatalogueLoadingAndSurvivesItsCompletionAndReopening() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val catalogue = CompletableDeferred<Unit>()
            val photo = CompletableDeferred<String?>()
            var requests = 0
            val state = DesktopBrowseState(Repository { publish ->
                publish(preview)
                catalogue.await()
                preview.copy(description = "Full catalogue")
            }, this) { requests++; photo.await() }
            state.openArtist(artist)
            runCurrent()
            assertEquals("original", (state.top as BrowsePage.Artist).page.bannerUrl)
            assertTrue((state.top as BrowsePage.Artist).isLoading)
            photo.complete("high-quality")
            runCurrent()
            assertEquals("high-quality", (state.top as BrowsePage.Artist).page.bannerUrl)
            assertTrue((state.top as BrowsePage.Artist).isLoading)
            catalogue.complete(Unit)
            runCurrent()
            assertEquals("high-quality", (state.top as BrowsePage.Artist).page.bannerUrl)
            assertFalse((state.top as BrowsePage.Artist).isLoading)
            assertEquals("Full catalogue", (state.top as BrowsePage.Artist).page.description)
            state.pop()
            state.openArtist(artist)
            runCurrent()
            assertEquals("high-quality", (state.top as BrowsePage.Artist).page.bannerUrl)
            assertEquals(1, requests)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun failedPhotoKeepsOriginalAndCanRetryWithoutCancellingPageLoad() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            var attempts = 0
            val state = DesktopBrowseState(Repository { preview }, this) {
                if (++attempts == 1) throw java.io.IOException("Offline")
                "high-quality"
            }
            state.openArtist(artist)
            runCurrent()
            assertEquals("original", (state.top as BrowsePage.Artist).page.bannerUrl)
            assertFalse((state.top as BrowsePage.Artist).isLoading)
            state.pop()
            state.openArtist(artist)
            runCurrent()
            assertEquals("high-quality", (state.top as BrowsePage.Artist).page.bannerUrl)
            assertEquals(2, attempts)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun sharedLookupCannotReopenDismissedPagesOrReplaceAnotherArtist() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val photo = CompletableDeferred<String?>()
            var requests = 0
            val state = DesktopBrowseState(Repository { preview }, this) { requests++; photo.await() }
            state.openArtist(artist)
            state.openArtist(artist)
            runCurrent()
            state.clear()
            val other = artist.copy(id = "UCother", name = "Other Artist")
            state.stack += BrowsePage.Artist(ArtistPage(other), isLoading = false)
            photo.complete("high-quality")
            runCurrent()
            assertEquals(1, requests)
            assertEquals(1, state.stack.size)
            assertEquals(other, (state.top as BrowsePage.Artist).page.artist)
        } finally { Dispatchers.resetMain() }
    }
}
