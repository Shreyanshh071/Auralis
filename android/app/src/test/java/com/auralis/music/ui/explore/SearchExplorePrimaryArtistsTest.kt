package com.auralis.music.ui.explore

import com.auralis.music.domain.model.Track
import com.auralis.music.domain.recommendations.ArtistAffinity
import org.junit.Assert.assertEquals
import org.junit.Test

class SearchExplorePrimaryArtistsTest {
    private fun a(name: String, plays: Int, score: Float, song: String) =
        ArtistAffinity(name, plays, 0, score, Track(song, song, name))

    @Test fun songsCountTowardTheirFirstBilledArtistAndKeepTheMostPlayedSong() {
        val result = primaryArtists(listOf(
            a("Pritam, Arijit Singh", 10, 5f, "Tum Se Hi"),
            a("Pritam", 30, 9f, "Kesariya"),
            a("Arijit Singh", 4, 2f, "Channa Mereya"),
            a("Kendrick Lamar feat. SZA", 6, 3f, "All The Stars"),
            a("A.R. Rahman & Javed Ali", 2, 1f, "Kun Faya Kun")
        ))
        assertEquals(listOf("Pritam", "Kendrick Lamar", "Arijit Singh", "A.R. Rahman"), result.map { it.artistName })
        assertEquals(40, result.first().totalPlays)
        assertEquals("Kesariya", result.first().sampleTrack?.title)
    }
}
