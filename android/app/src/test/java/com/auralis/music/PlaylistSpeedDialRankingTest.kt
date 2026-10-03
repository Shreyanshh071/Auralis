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

    @Test fun upToThreePlaylistsQualifyMostListenedFirst() {
        val lists = listOf("a", "b", "c", "d").map(::playlist)
        val stats = mapOf(
            "a" to PlaylistListeningStats(3, 25 * 60 * 1000L),
            "b" to PlaylistListeningStats(3, 60 * 60 * 1000L),
            "c" to PlaylistListeningStats(3, 40 * 60 * 1000L),
            "d" to PlaylistListeningStats(3, 30 * 60 * 1000L)
        )
        assertEquals(listOf("b", "c", "d"), PlaylistSpeedDialRanking.mostListened(lists, stats, 3).map { it.id })
    }

    @Test fun playlistsSitAmongSongsByListeningTime() {
        // Songs keep their order; a playlist goes ahead of the first song listened to less.
        val songs = listOf("s90" to 90L, "s50" to 50L, "s20" to 20L, "s5" to 5L)
        val playlists = listOf("p60" to 60L, "p30" to 30L, "p10" to 10L)
        val ranked = PlaylistSpeedDialRanking.interleave(songs, { it.second }, playlists, { it.second })
        assertEquals(listOf("s90", "p60", "s50", "p30", "s20", "p10", "s5"), ranked.map { it.first })
    }

    @Test fun playlistsListenedLessThanEverySongGoLast() {
        val ranked = PlaylistSpeedDialRanking.interleave(
            listOf("s" to 100L), { it.second }, listOf("p" to 1L), { it.second }
        )
        assertEquals(listOf("s", "p"), ranked.map { it.first })
    }
}
