package com.auralis.music.windows.home

import com.auralis.music.domain.model.*
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class ShortsHomeShelfTest {
    @Test fun homeOrderingCannotKeepAHiddenShortAsAClickableEntry() = runBlocking {
        val song = Track("song", duration = 49)
        val short = Track("short", duration = 49)
        val shelf = DesktopHomeShelf("home", "Home", tracks = listOf(short, song),
            albums = listOf(PlaylistResult("album", "Album")), order = listOf("track:short", "album:album", "track:song"))
        val result = filterHomeShelves(listOf(shelf, DesktopHomeShelf("shorts", "Shorts", tracks = listOf(short)))) {
            it.filterNot { track -> track.id == short.id }
        }
        assertEquals(1, result.size)
        assertEquals(listOf(song), result.single().tracks)
        assertEquals(listOf("album:album", "track:song"), result.single().order)
    }
}
