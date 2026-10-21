package com.auralis.music.windows

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import com.auralis.music.desktop.browse.BrowsePage
import com.auralis.music.domain.model.PlaylistResult
import com.auralis.music.ui.components.PlatformBackHandler
import kotlinx.coroutines.Dispatchers
import kotlin.test.*

@OptIn(androidx.compose.ui.InternalComposeUiApi::class)
class DesktopEscapeNavigationTest {
    @Test fun escapeCollapsesPlayerThenReturnsSettingsLibraryHomeEvenWhileTyping() {
        val navigation = DesktopNavigation()
        val home = DesktopRoute(DesktopDestination.HOME, emptyList())
        val library = DesktopRoute(DesktopDestination.LIBRARY, emptyList())
        val settings = DesktopRoute(DesktopDestination.SETTINGS, emptyList())
        listOf(home, library, settings).forEach(navigation::record)
        val route = mutableStateOf(settings)
        val sectionOpen = mutableStateOf(true)
        var playerOpen = true
        val scene = ImageComposeScene(100, 100, coroutineContext = Dispatchers.Unconfined) {
            PlatformBackHandler(enabled = navigation.canGoBack) {
                route.value = navigation.back()!!
            }
            if (route.value.destination == DesktopDestination.SETTINGS) {
                PlatformBackHandler(enabled = sectionOpen.value) { sectionOpen.value = false }
            }
        }
        val actions = object : ShortcutActions {
            override fun closePlayer() = handleEscapeBack(playerOpen) { playerOpen = false }
            override fun togglePlayPause() = error("Escape must not affect playback")
            override fun next() = Unit
            override fun previous() = Unit
            override fun seekBy(deltaMs: Long) = Unit
            override fun volumeBy(delta: Float) = Unit
            override fun toggleMute() = Unit
            override fun focusSearch() = Unit
            override fun togglePlayer() = Unit
            override fun showLyrics() = Unit
            override fun showQueue() = Unit
        }
        var frame = 0L
        fun render() { scene.render(frame++ * 16_666_667L).close() }
        fun escape(released: Boolean = false): Boolean = handleShortcut(
            KeyEvent(Key.Escape, if (released) KeyEventType.KeyUp else KeyEventType.KeyDown),
            typing = true, actions = actions
        )
        try {
            render()
            assertTrue(escape())
            assertFalse(playerOpen)
            assertEquals(settings, route.value, "Collapsing the player must preserve the current page")
            assertTrue(sectionOpen.value)
            assertFalse(escape(released = true), "Key release must not perform a second back step")
            assertTrue(escape())
            assertFalse(sectionOpen.value, "A settings subpage closes before leaving Settings")
            assertEquals(settings, route.value)
            render()
            assertTrue(escape())
            assertEquals(library, route.value)
            render()
            assertTrue(escape())
            assertEquals(home, route.value)
            render()
            assertFalse(escape(), "At the start of history Escape must leave Home open")
            assertEquals(library, navigation.forward(), "Escape must preserve forward history")
            val album = library.copy(pages = listOf(BrowsePage.Album(PlaylistResult("album", "Album"))))
            navigation.record(album)
            route.value = album
            render()
            assertTrue(escape())
            assertEquals(library, route.value)
            render()
            assertTrue(escape())
            assertEquals(home, route.value, "Returning from an album must not add a new history entry that reopens it")
        } finally { scene.close() }
        assertFalse(handleEscapeBack(false) {}, "Disposed page handlers must not receive later Escape presses")
    }
}
