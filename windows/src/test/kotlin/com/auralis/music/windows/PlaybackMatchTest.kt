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
}
