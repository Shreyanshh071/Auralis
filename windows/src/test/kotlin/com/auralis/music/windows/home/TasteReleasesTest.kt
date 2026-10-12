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
}
