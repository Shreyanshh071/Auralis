package com.auralis.music.windows.library

import com.auralis.music.desktop.library.DesktopLibraryRepository
import com.auralis.music.domain.model.*
import com.auralis.music.ui.library.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class LibraryFiltersTest {
    @Test fun legacyImportsFilterWithoutChangingTheirIdsOrMistakingLocalPlaylistsForImports() {
        val legacy = Playlist("old-random-uuid", "Spotify mix", description = "Imported from Spotify")
        val youtube = legacy.copy(id = "other-uuid", description = "Imported from YouTube Music")
        assertEquals(LibrarySourceFilter.SPOTIFY, desktopPlaylistSource(legacy))
        assertEquals(LibrarySourceFilter.YOUTUBE, desktopPlaylistSource(youtube))
        assertNull(desktopPlaylistSource(legacy.copy(description = "My Spotify favourites")))
        assertNull(desktopPlaylistSource(legacy.copy(description = null)))
        assertEquals("old-random-uuid", legacy.id)
    }

    @Test fun importedPlaylistsKeepTheirSourceAndReimportUpdatesRatherThanDuplicates() = runBlocking {
        val dir = kotlin.io.path.createTempDirectory("auralis-library-filters").toFile()
        val library = DesktopLibraryRepository(java.io.File(dir, "library.json"))
        val spotify = library.upsertImportedPlaylist("spotify", "same-remote-id", "Spotify mix")
        val youtube = library.upsertImportedPlaylist("youtube_music", "same-remote-id", "YouTube mix")
        val local = library.createPlaylist("My playlist")
        val song = Track(id = "track", title = "Song", artist = "Artist")
        library.addTrackToPlaylist(spotify.id, song)
        val updated = library.upsertImportedPlaylist("spotify", "same-remote-id", "Renamed mix")
        val saved = library.getPlaylists().first()
        assertEquals(3, saved.size)
        assertEquals(spotify.id, updated.id)
        assertEquals(listOf(song), updated.tracks)
        assertEquals(listOf(updated), saved.filter(::isSpotifyImport))
        assertEquals(listOf(youtube), saved.filter(::isYouTubeMusicImport))
        assertFalse(isSpotifyImport(local))
        assertFalse(isYouTubeMusicImport(local))
        assertTrue(isSpotifyImport(Playlist("sp_legacy", "Legacy")))
    }

    @Test fun savedAlbumsAppearInLibraryAndOpenWithTheirOriginalProviderId() {
        val album = SavedAlbum("MPREb_album", "Album", "Artist", "cover", 12)
        val imported = Playlist("imported:spotify:mix", "Album")
        val items = desktopLibraryItems(listOf(imported), listOf(album))
        val albums = items.filter { isAlbumPlaylist(it, listOf(album)) }
        assertEquals(listOf(savedAlbumEntryId(album)), albums.map { it.id })
        assertEquals("MPREb_album", album.asBrowseAlbum().id)
        assertEquals(12, album.asBrowseAlbum().trackCount)
        assertEquals(2, items.size)
        assertEquals(1, desktopLibraryItems(listOf(Playlist(album.id, album.title)), listOf(album)).size)
        assertTrue(desktopLibraryItems(emptyList(), emptyList()).isEmpty())
    }
    @Test fun onlyExplicitlyCreatedOrImportedYouTubePlaylistsAreRetained() = runBlocking {
        val dir = kotlin.io.path.createTempDirectory("auralis-explicit-playlists").toFile()
        try {
            val file = java.io.File(dir, "library.json")
            val library = DesktopLibraryRepository(file)
            val imported = library.upsertImportedPlaylist("youtube_music", "PLchosen", "Chosen import")
            val unrelated = PlaylistResult("VLPLunselected", "Unselected account playlist")
            val created = PlaylistResult("VLPLcreated", "aaaaa")
            val kept = retainCreatedYouTubePlaylist(library, created)
            assertEquals("imported:youtube_music:PLcreated", kept.id)
            assertEquals(LibrarySourceFilter.YOUTUBE, desktopPlaylistSource(kept))
            retainCreatedYouTubePlaylist(library, created)
            kotlinx.coroutines.withTimeout(5_000) {
                while (!file.isFile) kotlinx.coroutines.delay(25)
            }
            val reopened = DesktopLibraryRepository(file).getPlaylists().first()
            val items = desktopLibraryItems(reopened, emptyList())
            assertEquals(setOf(imported.id, kept.id), items.map { it.id }.toSet())
            assertEquals(2, items.size, "Creation must persist exactly one playlist without duplicates")
            assertFalse(items.any { it.title == unrelated.title })
        } finally { dir.deleteRecursively() }
    }

}
