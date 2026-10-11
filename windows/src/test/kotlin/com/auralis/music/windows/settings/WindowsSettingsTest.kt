package com.auralis.music.windows.settings

import com.auralis.music.desktop.settings.DesktopDataStores
import com.auralis.music.domain.model.*
import com.auralis.music.data.network.ContentLocale
import com.auralis.music.data.network.ContentSettingsSource
import com.auralis.music.domain.repository.SearchRepository
import com.auralis.music.windows.search.DesktopSearchState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.awt.image.BufferedImage
import kotlin.test.*

class WindowsSettingsTest {
    @Test fun desktopPreferencesPersistAndContentLocaleUsesSelectedRegion() = runBlocking {
        val root = kotlin.io.path.createTempDirectory("auralis-settings-test").toFile()
        val stores = DesktopDataStores(File(root, "original"))
        stores.settings.setAudioQuality(AudioQuality.LOW)
        stores.settings.setPlaybackRate(1.5f)
        stores.privacy.setPauseListenHistory(true)
        stores.privacy.setPauseSearchHistory(true)
        stores.storage.setMaxImageCacheSizeMb(256)
        stores.content.update { it.copy(contentLanguage = "hi", contentCountry = "IN", romanizationEnabled = true) }
        stores.appearance.updateSettings(AppearanceSettings(appTheme = "Light Mode", displayDensity = "Large (115%)", hidePlayerThumbnail = true))
        // Read through flows before copying to ensure every write reached the file.
        assertEquals(1.5f, stores.settings.settingsFlow.first().playbackRate)
        val copy = File(root, "reloaded").apply { mkdirs() }
        File(root, "original").listFiles()!!.forEach { it.copyTo(File(copy, it.name)) }
        val reloaded = DesktopDataStores(copy)
        assertEquals(AudioQuality.LOW, reloaded.settings.settingsFlow.first().audioQuality)
        assertTrue(reloaded.privacy.settingsFlow.first().pauseSearchHistory)
        assertTrue(reloaded.privacy.settingsFlow.first().pauseListenHistory)
        assertEquals(256, reloaded.storage.settingsFlow.first().maxImageCacheSizeMb)
        assertEquals("Large (115%)", reloaded.appearance.settingsFlow.first().displayDensity)
        assertTrue(reloaded.appearance.settingsFlow.first().hidePlayerThumbnail)
        val oldSource = ContentSettingsSource.current
        try {
            val settings = reloaded.content.settingsFlow.first()
            ContentSettingsSource.current = { settings }
            assertEquals("hi", ContentLocale.hl()); assertEquals("IN", ContentLocale.gl())
            assertTrue(settings.romanizationEnabled)
        } finally { ContentSettingsSource.current = oldSource }
    }

    @Test fun pausingSearchHistoryDoesNotPreventSearchResults() = runBlocking {
        val repository = FakeSearch()
        var paused = true
        val state = DesktopSearchState(repository, this) { !paused }
        state.search("first")
        delay(50)
        assertEquals("first", state.results.songs.single().title)
        assertTrue(repository.recorded.isEmpty())
        paused = false
        state.search("second")
        delay(50)
        assertEquals(listOf("second"), repository.recorded)
        state.search("diagnostic", recordHistory = false)
        delay(50)
        assertEquals(listOf("second"), repository.recorded)
    }

    @Test fun clearingLyricCacheKeepsLibraryAccountAndUnrelatedFiles() {
        val root = kotlin.io.path.createTempDirectory("auralis-cache-test").toFile()
        val previous = System.getProperty("auralis.dataDir")
        try {
            System.setProperty("auralis.dataDir", root.absolutePath)
            File(root, "library.json").writeText("keep library")
            File(root, "account-session.bin").writeText("keep account")
            val lyrics = File(root, "lyrics").apply { mkdirs() }
            File(lyrics, "song.json").writeText("cached lyrics")
            File(lyrics, "notes.txt").writeText("keep notes")
            DesktopCaches.clearLyrics()
            assertFalse(File(lyrics, "song.json").exists())
            assertEquals("keep library", File(root, "library.json").readText())
            assertEquals("keep account", File(root, "account-session.bin").readText())
            assertEquals("keep notes", File(lyrics, "notes.txt").readText())
        } finally { if (previous == null) System.clearProperty("auralis.dataDir") else System.setProperty("auralis.dataDir", previous) }
    }

    @Test fun artworkColoursComeFromVisibleImagePixels() {
        val image = BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB)
        for (y in 0..63) for (x in 0..63) image.setRGB(x, y, if (x < 48) 0xFFFF0000.toInt() else 0xFF0000FF.toInt())
        val palette = extractArtworkPalette(image)
        assertTrue(palette.primary.red > 0.9f && palette.primary.blue < 0.1f)
    }

    private class FakeSearch : SearchRepository {
        val recorded = mutableListOf<String>()
        override suspend fun search(query: String) = SearchResults(songs = listOf(Track(id = query, title = query, artist = "Test")))
        override suspend fun searchSongs(query: String) = emptyList<Track>()
        override suspend fun searchAlbums(query: String) = emptyList<PlaylistResult>()
        override suspend fun searchArtists(query: String) = emptyList<Artist>()
        override suspend fun searchPlaylists(query: String) = emptyList<PlaylistResult>()
        override suspend fun getSuggestions(query: String) = emptyList<String>()
        override suspend fun getArtistPage(artist: Artist): ArtistPage? = null
        override suspend fun getAlbumTracks(album: PlaylistResult) = emptyList<Track>()
        override fun getRecentSearchQueries() = flowOf(recorded.toList())
        override suspend fun recordSearchQuery(query: String) { recorded += query }
        override suspend fun removeSearchQuery(query: String) { recorded.remove(query) }
        override suspend fun clearSearchHistory() { recorded.clear() }
    }
}
