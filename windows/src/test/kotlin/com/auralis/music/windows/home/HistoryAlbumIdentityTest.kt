package com.auralis.music.windows.home

import com.auralis.music.domain.model.PlaylistResult
import kotlin.test.*

class HistoryAlbumIdentityTest {
    @Test fun historyPlaylistTitlesAreReplacedByTheOriginalSongsCatalogueAlbum() {
        val card = PlaylistResult("album:abcdefghijk", "My imported playlist", author = "Example Artist")
        val fixed = historyAlbumIdentity(card) { "Real Album" to "MPRE-official" }
        assertEquals("Real Album", fixed.title)
        assertEquals("MPRE-official", fixed.id)
        assertEquals(card.author, fixed.author)
    }
    @Test fun realAlbumAndPlaylistIdsAreNeverRewritten() {
        for (id in listOf("MPRE-selected", "VLPL-selected", "local:playlist")) {
            val album = PlaylistResult(id, "Selected Album")
            assertEquals(album, historyAlbumIdentity(album) { error("Must preserve selected identity") })
        }
    }
    @Test fun missingMetadataKeepsTheHistoryCardResolvable() {
        val album = PlaylistResult("album:abcdefghijk", "Selected Album")
        assertEquals(album, historyAlbumIdentity(album) { null })
    }
}
