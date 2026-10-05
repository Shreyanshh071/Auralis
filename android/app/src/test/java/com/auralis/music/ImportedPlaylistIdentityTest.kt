package com.auralis.music

import com.auralis.music.data.local.dao.LibraryDao
import com.auralis.music.data.local.dao.PlaylistDao
import com.auralis.music.data.local.dao.TrackDao
import com.auralis.music.data.local.entity.PlaylistEntity
import com.auralis.music.data.repository.LibraryRepositoryImpl
import com.auralis.music.data.repository.importedPlaylistLocalId
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ImportedPlaylistIdentityTest {
    @Test
    fun sameTitleLocalPlaylistIsNotReusedForYouTubeImport() = runBlocking {
        val playlistDao = mockk<PlaylistDao>(relaxed = true)
        val repository = LibraryRepositoryImpl(mockk<TrackDao>(), playlistDao, mockk<LibraryDao>())
        val local = PlaylistEntity("local-uuid", "hi", null, null)
        val importedId = importedPlaylistLocalId("youtube_music", "PL-remote")
        coEvery { playlistDao.getPlaylistEntity(importedId) } returns null

        val imported = repository.upsertImportedPlaylist("youtube_music", "PL-remote", "hi")

        assertNotEquals(local.id, imported.id)
        assertEquals(importedId, imported.id)
        coVerify(exactly = 1) { playlistDao.upsertPlaylist(match { it.id == importedId && it.title == "hi" }) }
        coVerify(exactly = 0) { playlistDao.updatePlaylist(local.id, any(), any(), any()) }
    }

    @Test
    fun sameTitleLocalPlaylistIsNotReusedForSpotifyImport() = runBlocking {
        val playlistDao = mockk<PlaylistDao>(relaxed = true)
        val repository = LibraryRepositoryImpl(mockk<TrackDao>(), playlistDao, mockk<LibraryDao>())
        val local = PlaylistEntity("local-uuid", "hi", null, null)
        val importedId = importedPlaylistLocalId("spotify", "spotify-remote")
        coEvery { playlistDao.getPlaylistEntity(importedId) } returns null

        val imported = repository.upsertImportedPlaylist("spotify", "spotify-remote", "hi")

        assertNotEquals(local.id, imported.id)
        assertEquals(importedId, imported.id)
        coVerify(exactly = 1) { playlistDao.upsertPlaylist(match { it.id == importedId && it.title == "hi" }) }
        coVerify(exactly = 0) { playlistDao.updatePlaylist(local.id, any(), any(), any()) }
    }

    @Test
    fun sameRemotePlaylistRefreshesItsOwnRecordOnly() = runBlocking {
        val playlistDao = mockk<PlaylistDao>(relaxed = true)
        val repository = LibraryRepositoryImpl(mockk<TrackDao>(), playlistDao, mockk<LibraryDao>())
        val importedId = importedPlaylistLocalId("youtube_music", "PL-remote")
        coEvery { playlistDao.getPlaylistEntity(importedId) } returns
            PlaylistEntity(importedId, "old name", null, null, createdAt = 123L)

        val imported = repository.upsertImportedPlaylist("youtube_music", "PL-remote", "hi")

        assertEquals(importedId, imported.id)
        assertEquals(123L, imported.createdAt)
        coVerify(exactly = 1) { playlistDao.updatePlaylist(importedId, "hi", null, null) }
        coVerify(exactly = 0) { playlistDao.upsertPlaylist(any()) }
    }

    @Test
    fun remotePlaylistsWithSameTitleHaveDifferentLocalIds() {
        assertNotEquals(
            importedPlaylistLocalId("youtube_music", "PL-one"),
            importedPlaylistLocalId("youtube_music", "PL-two")
        )
        assertNotEquals(
            importedPlaylistLocalId("youtube_music", "PL-one"),
            importedPlaylistLocalId("spotify", "PL-one")
        )
    }

    @Test
    fun cloudRestoreUsesSavedIdInsteadOfMatchingTitle() = runBlocking {
        val playlistDao = mockk<PlaylistDao>(relaxed = true)
        val repository = LibraryRepositoryImpl(mockk<TrackDao>(), playlistDao, mockk<LibraryDao>())
        coEvery { playlistDao.getPlaylistEntity("cloud-original") } returns null

        val restored = repository.restorePlaylist("cloud-original", "hi")

        assertEquals("cloud-original", restored.id)
        coVerify(exactly = 1) { playlistDao.upsertPlaylist(match { it.id == "cloud-original" && it.title == "hi" }) }
    }
}
