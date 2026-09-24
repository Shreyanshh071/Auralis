package com.auralis.music.ui.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QueueSourceTitleTest {
    @Test
    fun singleSongUsesQueueEvenWhenStartedFromPlaylist() {
        assertNull(resolveQueueSourceTitle(1, "Favorites", null, false))
    }

    @Test
    fun playlistQueueUsesItsName() {
        assertEquals("Favorites", resolveQueueSourceTitle(3, " Favorites ", null, false))
    }

    @Test
    fun selectingAnotherQueueItemKeepsPlaylistName() {
        assertEquals("Favorites", resolveQueueSourceTitle(3, null, "Favorites", true))
    }

    @Test
    fun unrelatedQueueClearsPreviousPlaylistName() {
        assertNull(resolveQueueSourceTitle(3, null, "Favorites", false))
        assertNull(resolveQueueSourceTitle(3, "   ", "Favorites", false))
    }
}
