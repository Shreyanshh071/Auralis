package com.auralis.music.windows.home

import com.auralis.music.domain.model.Track
import kotlin.test.*

class GuestHomeFeedTest {
    @Test fun emptyFirstLaunchHasPlayableShelvesWithoutInventingHistory() {
        val shelves = guestHomeShelves(emptyList(), emptyList(), emptyList())
        assertTrue(shelves.size >= 2)
        assertTrue(shelves.sumOf { it.tracks.size } >= 18)
        assertTrue(shelves.all { it.tracks.all { track -> track.id.isNotBlank() && track.thumbnail.isNotBlank() } })
        assertFalse(shelves.any { it.title == "Recents" || it.title == "Keep listening" })
    }

    @Test fun fullPublicFeedReplacesStarters() {
        val public = DesktopHomeShelf("public", "Trending", tracks = (1..24).map { Track("$it", "Song $it", "Artist") })
        assertEquals(listOf(public), guestHomeShelves(emptyList(), listOf(public), emptyList()))
    }

    @Test fun listeningRecommendationsLeadAndRetireStarterSongs() {
        val heard = Track("heard", "Heard", "Artist")
        val related = DesktopHomeShelf("related", "Because you listened to Heard", tracks = listOf(heard.copy(id = "next")))
        val public = DesktopHomeShelf("public", "Explore", tracks = listOf(heard.copy(id = "explore")))
        val shelves = guestHomeShelves(emptyList(), listOf(public), listOf(heard), listOf(related))
        assertEquals(listOf(related, public), shelves)
        assertFalse(shelves.any { it.id.startsWith("guest-starter") })
    }
}
