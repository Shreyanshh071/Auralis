package com.auralis.music.windows.player

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.*
import com.auralis.music.domain.model.Artist
import kotlin.test.*

class SidebarArtistLinksTest {
    @Test fun everyPerformerHasAnIndependentTargetEvenWithoutAChannelId() {
        val people = listOf(SongCredit("Gorillaz", listOf("Main Artist"), "UC-primary"),
            SongCredit("De La Soul", listOf("Main Artist")), SongCredit("David Jolicoeur", listOf("Writer")))
        assertEquals(listOf(Artist("UC-primary", "Gorillaz"), Artist("", "De La Soul")),
            sidebarArtists("Gorillaz, De La Soul", people))
        assertEquals(Artist("", "De La Soul"), people[1].profileArtist())
        assertNull(people[2].profileArtist())
        assertEquals(listOf("Gorillaz", "De La Soul"), sidebarArtists("Gorillaz, De La Soul", emptyList()).map { it.name })
    }

    @Test fun creditedBandPunctuationDoesNotCreateFakeArtistLinks() {
        val people = listOf(SongCredit("Earth, Wind & Fire", listOf("Main Artist"), "UC-band"),
            SongCredit("Lucky Daye", listOf("Main Artist")))
        assertEquals(listOf("Earth, Wind & Fire", "Lucky Daye"),
            sidebarArtists("Earth, Wind & Fire feat. Lucky Daye", people).map { it.name })
    }

    @Test fun clickingTheRenderedCreditLineOpensBothSeparateArtists() {
        val artists = sidebarArtists("Gorillaz, De La Soul", emptyList())
        val opened = mutableListOf<Artist>()
        val scene = ImageComposeScene(400, 60) {
            MaterialTheme { SidebarArtistNames(artists, { opened += it }) }
        }
        try {
            scene.render().close()
            for (x in 5..250 step 5) {
                scene.sendPointerEvent(PointerEventType.Press, Offset(x.toFloat(), 8f), type = PointerType.Mouse)
                scene.sendPointerEvent(PointerEventType.Release, Offset(x.toFloat(), 8f), type = PointerType.Mouse)
            }
            assertEquals(artists, opened.distinct())
        } finally { scene.close() }
    }
}
