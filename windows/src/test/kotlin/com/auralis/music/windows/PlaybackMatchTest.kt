package com.auralis.music.windows

import com.auralis.music.desktop.playback.DesktopPlaybackMatch
import com.auralis.music.data.network.RecordingMatches
import com.auralis.music.domain.model.Track
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class PlaybackMatchTest {
    @Test fun youtubeIdsNeverNeedSearch() = runBlocking {
        assertEquals("HfpR4tAmI7E", DesktopPlaybackMatch.videoId(Track(id = "HfpR4tAmI7E")) { error("Unexpected search") })
    }
    @Test fun importedTrackMatchesAndCachesRecordingBeforeExtraction() = runBlocking {
        val source = Track(id = "sp_test_match", title = "Love Me Not", artist = "Ravyn Lenae", duration = 213)
        val recording = source.copy(id = "HfpR4tAmI7E")
        try {
            assertEquals(recording.id, DesktopPlaybackMatch.videoId(source) { listOf(recording) })
            assertEquals(recording.id, DesktopPlaybackMatch.videoId(source) { error("Must reuse match") })
            assertEquals(213L, RecordingMatches.matchedDurations[source.id])
        } finally {
            RecordingMatches.matchedVideoIds.remove(source.id)
            RecordingMatches.matchedDurations.remove(source.id)
        }
    }
    @Test fun unmatchedOrDifferentRecordingIsNeverSentToYouTubePlayer() = runBlocking {
        val source = Track(id = "sp_test_unmatched", title = "Love Me Not", artist = "Ravyn Lenae", duration = 213)
        assertNull(DesktopPlaybackMatch.videoId(source) { emptyList() })
        assertNull(DesktopPlaybackMatch.videoId(source) { listOf(Track(id = "HfpR4tAmI7E", title = "Another Song", artist = "Other Artist", duration = 80)) })
        assertFalse(DesktopPlaybackMatch.isVideoId(source.id))
    }

    @Test fun importedTrackWithFeaturedArtistMatchesOfficialAlbumTrack() = runBlocking {
        val source = Track(id = "sp_like_him", title = "Like Him (feat. Lola Young)", artist = "Tyler, The Creator, Lola Young", album = "CHROMAKOPIA", duration = 278)
        val official = Track(id = "N3oCS85HDBY", title = "Like Him", artist = "Tyler, The Creator", album = "CHROMAKOPIA", duration = 278)
        try {
            assertEquals("N3oCS85HDBY", DesktopPlaybackMatch.videoId(source) { listOf(official) })
        } finally {
            RecordingMatches.matchedVideoIds.remove(source.id)
            RecordingMatches.matchedDurations.remove(source.id)
        }
    }

    @Test fun importedTrackWithFeaturedArtistMatchesOfficialTrackWithoutAlbumMetadata() = runBlocking {
        val source = Track(id = "sp_the_way_life_goes", title = "The Way Life Goes (feat. Oh Wonder)", artist = "Lil Uzi Vert, Oh Wonder", album = "Luv Is Rage 2", duration = 0)
        val official = Track(id = "2eAZfqOm4En", title = "The Way Life Goes", artist = "Lil Uzi Vert", album = null, duration = 205)
        try {
            assertEquals("2eAZfqOm4En", DesktopPlaybackMatch.videoId(source) { listOf(official) })
        } finally {
            RecordingMatches.matchedVideoIds.remove(source.id)
            RecordingMatches.matchedDurations.remove(source.id)
        }
    }
}
