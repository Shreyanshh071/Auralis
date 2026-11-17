package com.auralis.music.windows.history

import com.auralis.music.desktop.library.DesktopListeningStats
import com.auralis.music.desktop.library.DesktopListeningStats.Listen
import com.auralis.music.domain.model.Track
import com.auralis.music.domain.model.TrackArtist
import kotlin.test.*

class ArtistListBorrowTest {
    // A collab whose credit line can't be split correctly ("Tyler, The Creator" isn't on any list).
    private val credit = "Zyler, The Kreator & Kali Uchis"
    private val old = Track("song1", "See You Again", credit)
    private val fresh = old.copy(artists = listOf(TrackArtist("Zyler, The Kreator", "UC1"), TrackArtist("Kali Uchis", "UC2")))

    @Test fun anOldListenBorrowsTheListFromANewerListenOfTheSameSong() {
        val history = listOf(Listen("1", old, 1_000L, 200_000), Listen("2", fresh, 9_000L, 200_000))
        // Only the old listen is in the period being shown; the list comes from the whole history.
        val groups = DesktopListeningStats.artistGroups(history.take(1), DesktopListeningStats.knownArtists(history))
        assertEquals(setOf("Zyler, The Kreator", "Kali Uchis"), groups.map { it.first }.toSet())
    }

    @Test fun withoutAnyListFromYouTubeTheCreditLineIsSplit() {
        val groups = DesktopListeningStats.artistGroups(listOf(Listen("1", old, 1_000L, 200_000)))
        assertEquals(setOf("Zyler", "The Kreator", "Kali Uchis"), groups.map { it.first }.toSet())
    }

    @Test fun theNewestListOfASongWins() {
        val newer = old.copy(artists = listOf(TrackArtist("Zyler, The Kreator", "UC1")))
        val known = DesktopListeningStats.knownArtists(listOf(Listen("1", fresh, 1_000L, 1), Listen("2", newer, 5_000L, 1)))
        assertEquals(listOf("Zyler, The Kreator"), known["song1"]?.map { it.name })
    }
}
