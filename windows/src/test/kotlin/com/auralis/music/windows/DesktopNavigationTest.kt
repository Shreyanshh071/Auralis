package com.auralis.music.windows

import com.auralis.music.desktop.browse.BrowsePage
import com.auralis.music.domain.model.PlaylistResult
import kotlin.test.*

class DesktopNavigationTest {
    @Test fun backAndForwardRestorePagesAndNewNavigationClearsForwardHistory() {
        val history = DesktopNavigation()
        val home = DesktopRoute(DesktopDestination.HOME, emptyList())
        val search = DesktopRoute(DesktopDestination.SEARCH, emptyList())
        val album = DesktopRoute(DesktopDestination.SEARCH, listOf(BrowsePage.Album(PlaylistResult("album", "Album"))))
        history.record(home); history.record(search); history.record(album)
        assertEquals(search, history.back())
        history.record(search) // Composition observes the restored route.
        assertEquals(home, history.back())
        assertEquals(search, history.forward())
        assertEquals(album, history.forward())
        history.back()
        history.record(DesktopRoute(DesktopDestination.LIBRARY, emptyList()))
        assertFalse(history.canGoForward)
        assertEquals(search, history.back())
    }

    @Test fun albumLoadingDoesNotCreateAnotherBackStep() {
        val history = DesktopNavigation()
        val home = DesktopRoute(DesktopDestination.HOME, emptyList())
        val album = BrowsePage.Album(PlaylistResult("album", "Album"))
        history.record(home)
        history.record(DesktopRoute(DesktopDestination.HOME, listOf(album)))
        history.record(DesktopRoute(DesktopDestination.HOME, listOf(album.copy(isLoading = false))))
        assertEquals(home, history.back())
        assertFalse((history.forward()!!.pages.single() as BrowsePage.Album).isLoading)
    }
}
