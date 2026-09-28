package com.auralis.music

import com.auralis.music.domain.model.Playlist
import com.auralis.music.domain.model.Track
import com.auralis.music.domain.recommendations.PlaylistListeningStats
import com.auralis.music.domain.recommendations.PlaylistSpeedDialRanking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaylistSpeedDialRankingTest {
    private fun playlist(id: String) = Playlist(
        id = id,
        title = "Same name",
        tracks = listOf(Track(id = "track-$id", title = "Song", artist = "Artist"))
    )

    @Test fun oneOrTwoSessionsNeverQualifyEvenWithLongListening() {
        val candidate = playlist("a")
        assertNull(PlaylistSpeedDialRanking.mostListened(listOf(candidate), mapOf(
            candidate.id to PlaylistListeningStats(2, 90 * 60 * 1000L)
        )))
    }

    @Test fun shortRepeatSessionsNeverQualify() {
        val candidate = playlist("a")
        assertNull(PlaylistSpeedDialRanking.mostListened(listOf(candidate), mapOf(
            candidate.id to PlaylistListeningStats(10, 2 * 60 * 1000L)
        )))
    }

    @Test fun mostListenedExistingPlaylistWinsByIdentityAndDuration() {
        val lower = playlist("a")
        val higher = playlist("b")
        val stats = mapOf(
            lower.id to PlaylistListeningStats(7, 24 * 60 * 1000L),
            higher.id to PlaylistListeningStats(3, 40 * 60 * 1000L),
            "deleted" to PlaylistListeningStats(20, 90 * 60 * 1000L)
        )
        assertEquals("b", PlaylistSpeedDialRanking.mostListened(listOf(lower, higher), stats)?.id)
        assertEquals("a", PlaylistSpeedDialRanking.mostListened(listOf(lower), stats)?.id)
    }
}
