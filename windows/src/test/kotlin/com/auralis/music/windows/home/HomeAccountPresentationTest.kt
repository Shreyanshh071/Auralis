package com.auralis.music.windows.home

import com.auralis.music.domain.model.Track
import kotlin.test.*

class HomeAccountPresentationTest {
    private val song = Track("heard", "A song", "Artist")
    private val shelf = DesktopHomeShelf("mix", "Your mixes", tracks = listOf(song))

    @Test fun eitherAccountUsesTheSamePersonalHomeFormat() {
        for ((a, y) in listOf(true to false, false to true, true to true)) {
            val feed = HomeAccountPresentation().present(a, y, listOf(song), listOf(shelf))
            assertTrue(feed.personal)
            assertEquals(listOf(song), feed.recents)
            assertEquals(listOf(shelf), feed.shelves)
        }
    }

    @Test fun disconnectingEitherAccountKeepsContentUntilBothAreDisconnected() {
        for ((a, y) in listOf(true to false, false to true)) {
            val model = HomeAccountPresentation()
            model.present(true, true, listOf(song), listOf(shelf))
            val remaining = model.present(a, y, emptyList(), emptyList())
            assertEquals(listOf(song), remaining.recents)
            assertEquals(listOf(shelf), remaining.shelves)
            val guest = model.present(false, false, listOf(song), listOf(shelf))
            assertFalse(guest.personal)
            assertTrue(guest.recents.isEmpty())
            assertTrue(guest.shelves.isEmpty())
            assertTrue(model.present(true, false, emptyList(), emptyList()).recents.isEmpty())
        }
    }
}
