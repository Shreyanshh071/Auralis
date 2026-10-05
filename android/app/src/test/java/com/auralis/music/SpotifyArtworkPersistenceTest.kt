package com.auralis.music

import com.auralis.music.data.local.dao.LibraryDao
import com.auralis.music.data.local.dao.PlaylistDao
import com.auralis.music.data.local.dao.TrackDao
import com.auralis.music.data.local.entity.TrackEntity
import com.auralis.music.data.repository.LibraryRepositoryImpl
import com.auralis.music.data.repository.hasSpotifyReleaseArtwork
import com.auralis.music.data.repository.shouldRefreshImportedArtwork
import com.auralis.music.domain.model.Track
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotifyArtworkPersistenceTest {
    private val spotifyCover = "https://i.scdn.co/image/release-cover"
    private val youtubeFrame = "https://i.ytimg.com/vi/track/hqdefault.jpg"

    @Test
    fun spotifyAlbumCoverReplacesExistingVideoFrameDuringPlaylistImport() = runBlocking {
        val trackDao = mockk<TrackDao>(relaxed = true)
        val playlistDao = mockk<PlaylistDao>(relaxed = true)
        val repository = LibraryRepositoryImpl(trackDao, playlistDao, mockk<LibraryDao>())
        val incoming = track(spotifyCover)
        coEvery { trackDao.getTracksByIds(listOf(incoming.id)) } returns listOf(entity(youtubeFrame))

        repository.replacePlaylistTracks("imported:spotify:remote", listOf(incoming))

        coVerify(exactly = 1) {
            trackDao.updateVerifiedRelease(incoming.id, "Old Album", youtubeFrame,
                "Spotify Album", spotifyCover)
        }
    }

    @Test
    fun spotifyLikedSongUpdatesExistingVideoFrame() = runBlocking {
        val trackDao = mockk<TrackDao>(relaxed = true)
        val repository = LibraryRepositoryImpl(trackDao, mockk<PlaylistDao>(), mockk<LibraryDao>())
        val incoming = track(spotifyCover)
        coEvery { trackDao.getTrackById(incoming.id) } returns entity(youtubeFrame)

        repository.setFavorite(incoming, true)

        coVerify(exactly = 1) {
            trackDao.updateVerifiedRelease(incoming.id, "Old Album", youtubeFrame,
                "Spotify Album", spotifyCover)
        }
        coVerify(exactly = 1) { trackDao.setFavorite(incoming.id, true, any()) }
    }

    @Test
    fun nonReleaseArtworkIsNotTrustedToReplaceExistingCover() {
        assertTrue(hasSpotifyReleaseArtwork(track(spotifyCover)))
        assertFalse(hasSpotifyReleaseArtwork(track(youtubeFrame)))
        assertFalse(hasSpotifyReleaseArtwork(track("https://mosaic.scdn.co/640/abc")))
        assertFalse(hasSpotifyReleaseArtwork(track(spotifyCover).copy(album = null)))
    }

    @Test
    fun youtubeReimportRestoresTheExactVideoCoverButDoesNotReplaceSpotifyReleaseArt() {
        val youtubeArt = "https://i.ytimg.com/vi/youtube1234/hqdefault.jpg"
        val imported = track(youtubeArt).copy(id = "youtube1234", album = "YouTube Album")
        assertTrue(shouldRefreshImportedArtwork("youtube_music", imported,
            entity("https://is1-ssl.mzstatic.com/wrong-edition").copy(id = imported.id)))
        assertFalse(shouldRefreshImportedArtwork("youtube_music", imported,
            entity(spotifyCover).copy(id = imported.id)))
        assertFalse(shouldRefreshImportedArtwork("youtube_music", imported.copy(thumbnail =
            "https://i.ytimg.com/vi/differentId/hqdefault.jpg"),
            entity("https://is1-ssl.mzstatic.com/wrong-edition").copy(id = imported.id)))
    }

    @Test
    fun sourceCoverCannotOverwriteAnotherRecordingWithTheSameId() {
        assertFalse(shouldRefreshImportedArtwork("spotify", track(spotifyCover),
            entity(youtubeFrame).copy(title = "A Different Song")))
    }

    private fun track(thumbnail: String) = Track(
        id = "youtube-match", title = "A Song", artist = "Artist",
        album = "Spotify Album", thumbnail = thumbnail, duration = 210
    )

    private fun entity(thumbnail: String) = TrackEntity(
        id = "youtube-match", title = "A Song", artist = "Artist",
        album = "Old Album", duration = 210, thumbnail = thumbnail
    )
}
