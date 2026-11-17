package com.auralis.music.data.sync

import com.auralis.music.domain.model.TrackArtist
import com.auralis.music.domain.model.TrackArtists
import kotlin.test.*

class ListenArtistMetadataTest {
    @Test fun artistCreditsSurviveHistoryAndFirestoreBridgeEncoding() {
        val artists = listOf(TrackArtist("Tyler, The Creator", "UC-one"), TrackArtist("Guest", "UC-two"))
        val event = ListenEvent("song", 1234, 30000, artist = "Tyler, The Creator & Guest",
            artistsJson = TrackArtists.encode(artists))
        val restored = assertNotNull(ListenEvent.fromJson(event.toJson()))
        assertEquals(artists, TrackArtists.decode(restored.artistsJson))
        assertEquals(event.key, restored.key)
        assertEquals(event.listenedMs, restored.listenedMs)
    }

    @Test fun legacyHistoryWithoutArtistCreditsStillDecodes() {
        val restored = assertNotNull(ListenEvent.fromJson(ListenEvent("song", 1234, 30000).toJson()))
        assertNull(restored.artistsJson)
        assertTrue(TrackArtists.decode(restored.artistsJson).isEmpty())
    }
}
