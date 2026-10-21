package com.auralis.music.windows.home

import com.auralis.music.desktop.library.DesktopListeningStats.Listen
import com.auralis.music.domain.model.Track
import kotlin.test.Test
import kotlin.test.assertEquals

class TasteReleasesTest {
    private val now = 1_800_000_000_000L
    private val day = 24L * 60 * 60 * 1000
    private fun listen(artist: String, minutes: Long, daysAgo: Long) =
        Listen("$artist$daysAgo$minutes", Track(id = "$artist-song", title = "Song", artist = artist), now - daysAgo * day, minutes * 60_000)

    @Test fun recentListeningOutweighsOldListening() {
        val top = TasteReleases.topArtists(listOf(
            listen("Old Favourite", 60, 28),   // an hour, four weeks ago: 1/16 weight
            listen("Tame Impala, Someone", 20, 0),
            listen("Ravyn Lenae", 10, 1),
        ), now = now)
        assertEquals(listOf("Tame Impala", "Ravyn Lenae", "Old Favourite"), top)
    }

    @Test fun localAlbumsExtractsAlbumsAndSinglesForArtist() {
        val listens = listOf(
            Listen("1", Track(id = "s1", title = "The Less I Know The Better", artist = "Tame Impala", album = "Currents", albumId = "MPREb_1"), now, 180_000),
            Listen("2", Track(id = "s2", title = "Let It Happen", artist = "Tame Impala", album = "Currents", albumId = "MPREb_1"), now, 180_000),
            Listen("3", Track(id = "s3", title = "Borderline", artist = "Tame Impala", album = "The Slow Rush"), now, 180_000),
            Listen("4", Track(id = "s4", title = "Patience", artist = "Tame Impala"), now, 180_000)
        )
        val albums = TasteReleases.localAlbumsForArtist(listens, "Tame Impala")
        val titles = albums.map { it.title }
        kotlin.test.assertTrue("Currents" in titles)
        kotlin.test.assertTrue("The Slow Rush" in titles)
        kotlin.test.assertTrue("Patience" in titles)
        val currents = albums.first { it.title == "Currents" }
        assertEquals("MPREb_1", currents.id)
        val patience = albums.first { it.title == "Patience" }
        assertEquals("album:s4", patience.id)
    }

    @Test fun albumUsesARealIdEvenWhenTheFirstListenHasNone() {
        val listens = listOf(
            Listen("1", Track("first", "Song one", "TV Girl", album = "Who Really Cares"), now, 180_000),
            Listen("2", Track("second", "Song two", "TV Girl", album = "Who Really Cares", albumId = "MPRE-real"), now, 180_000))
        assertEquals("MPRE-real", TasteReleases.localAlbumsForArtist(listens, "TV Girl").single().id)
    }
}

