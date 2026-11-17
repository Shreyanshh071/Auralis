package com.auralis.music.windows

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import com.auralis.music.domain.model.Artist
import com.auralis.music.domain.model.ArtistPage
import com.auralis.music.domain.model.PlaylistResult
import com.auralis.music.domain.model.SearchResults
import com.auralis.music.domain.model.Track
import com.auralis.music.domain.repository.SearchRepository
import com.auralis.music.windows.search.DesktopSearchState
import com.auralis.music.windows.search.FindMusicDropdown
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(androidx.compose.ui.InternalComposeUiApi::class)
class WindowTitleBarSearchSelectionTest {

    private class FakeSearchRepository : SearchRepository {
        override suspend fun search(query: String): SearchResults = SearchResults()
        override suspend fun search(query: String, onPartial: (SearchResults) -> Unit): SearchResults = SearchResults()
        override fun cachedSearch(query: String): SearchResults = SearchResults()
        override suspend fun searchSongs(query: String): List<Track> = emptyList()
        override suspend fun searchLiveSongs(query: String, onUpdate: (List<Track>) -> Unit) = Unit
        override suspend fun searchAlbums(query: String): List<PlaylistResult> = emptyList()
        override suspend fun searchArtists(query: String): List<Artist> = emptyList()
        override suspend fun searchPlaylists(query: String): List<PlaylistResult> = emptyList()
        override suspend fun getSuggestions(query: String): List<String> = emptyList()
        override suspend fun getArtistPage(artist: Artist): ArtistPage = throw UnsupportedOperationException()
        override suspend fun getArtistPage(
            artist: Artist,
            onPartial: suspend (ArtistPage) -> Unit
        ): ArtistPage = throw UnsupportedOperationException()
        override suspend fun getAlbumTracks(album: PlaylistResult): List<Track> = emptyList()
        override fun getRecentSearchQueries(): Flow<List<String>> = emptyFlow()
        override suspend fun recordSearchQuery(query: String) = Unit
        override suspend fun removeSearchQuery(query: String) = Unit
        override suspend fun clearSearchHistory() = Unit
    }

    @Test
    fun draggingInTitleBarSearchSelectsTextWithoutDraggingWindowAndCmdOrCtrlADeletesAll() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val searchState = DesktopSearchState(FakeSearchRepository(), scope)
        var dragAreaTouches by mutableStateOf(0)
        var searchRect by mutableStateOf(Rect.Zero)
        var captionRect by mutableStateOf(Rect.Zero)
        var brandRect by mutableStateOf(Rect.Zero)
        var backClicks = 0

        val scene = ImageComposeScene(1280, 120, coroutineContext = Dispatchers.Unconfined) {
            MaterialTheme {
                Box(Modifier.fillMaxSize()) {
                    WindowTitleBar(
                        actions = TitleBarActions(
                            canGoBack = true,
                            back = { backClicks++ },
                            openAccount = {},
                            openSettings = {},
                            search = {
                                FindMusicDropdown(
                                    state = searchState,
                                    currentTrackId = null,
                                    isPlaying = false,
                                    onPlay = { _, _ -> },
                                    onSearch = {},
                                    onPlayNext = {},
                                    onAddToQueue = {},
                                    onMenuClick = {},
                                    onTypingChange = {},
                                    bar = true
                                )
                            }
                        ),
                        title = "Auralis",
                        icon = null,
                        maximized = false,
                        minimize = {},
                        maximize = {},
                        close = {},
                        captionBounds = { captionRect = it },
                        brandBounds = { brandRect = it },
                        searchBounds = { searchRect = it },
                        // Mirrors Compose Desktop's WindowDraggableArea (requireUnconsumed = false)
                        dragArea = { modifier, content ->
                            Box(
                                modifier.pointerInput(Unit) {
                                    awaitEachGesture {
                                        awaitFirstDown(requireUnconsumed = false)
                                        dragAreaTouches++
                                    }
                                }
                            ) { content() }
                        }
                    )
                }
            }
        }

        try {
            var frame = 0L
            fun pumpFrames(count: Int = 3) {
                repeat(count) { scene.render(frame++ * 16_666_667L).close() }
            }

            fun drag(from: Offset, to: Offset) {
                val steps = 6
                val primaryDown = PointerButtons(isPrimaryPressed = true)
                scene.sendPointerEvent(PointerEventType.Enter, from, type = PointerType.Mouse)
                pumpFrames(1)
                scene.sendPointerEvent(
                    PointerEventType.Press,
                    from,
                    type = PointerType.Mouse,
                    buttons = primaryDown,
                    button = PointerButton.Primary
                )
                pumpFrames(1)
                for (i in 1..steps) {
                    val t = i / steps.toFloat()
                    val pos = Offset(
                        x = from.x + (to.x - from.x) * t,
                        y = from.y + (to.y - from.y) * t
                    )
                    scene.sendPointerEvent(
                        PointerEventType.Move,
                        pos,
                        type = PointerType.Mouse,
                        buttons = primaryDown
                    )
                    pumpFrames(1)
                }
                scene.sendPointerEvent(
                    PointerEventType.Release,
                    to,
                    type = PointerType.Mouse,
                    buttons = PointerButtons(isPrimaryPressed = false),
                    button = PointerButton.Primary
                )
                pumpFrames()
            }

            fun pressKey(key: Key, meta: Boolean = false, ctrl: Boolean = false) {
                scene.sendKeyEvent(
                    KeyEvent(
                        key = key,
                        type = KeyEventType.KeyDown,
                        codePoint = 0,
                        isCtrlPressed = ctrl,
                        isMetaPressed = meta,
                        isAltPressed = false,
                        isShiftPressed = false
                    )
                )
                scene.sendKeyEvent(
                    KeyEvent(
                        key = key,
                        type = KeyEventType.KeyUp,
                        codePoint = 0,
                        isCtrlPressed = ctrl,
                        isMetaPressed = meta,
                        isAltPressed = false,
                        isShiftPressed = false
                    )
                )
                pumpFrames()
            }

            pumpFrames(5)
            assertTrue(searchRect.width > 140f, "Expected search box to be laid out, got $searchRect")
            assertTrue(brandRect.width > 40f, "Expected brand area to be laid out, got $brandRect")
            assertTrue(captionRect.width > searchRect.width, "Expected caption area to be laid out, got $captionRect")

            // 1) Drag across query text inside search field: selects text without triggering window drag.
            searchState.onQueryChange("ambient chill")
            pumpFrames()
            drag(
                from = Offset(searchRect.left + 220f, searchRect.center.y),
                to = Offset(searchRect.left + 32f, searchRect.center.y)
            )
            assertEquals(0, dragAreaTouches, "Dragging across search text must not start window drag")
            pressKey(Key.Backspace)
            assertEquals("", searchState.query, "Dragging across query + Backspace must delete selected text")

            // 2) Dragging near the vertical top edge of the 34dp search box still selects rather than dragging window.
            searchState.onQueryChange("late night")
            pumpFrames()
            drag(
                from = Offset(searchRect.left + 200f, searchRect.top + 3f),
                to = Offset(searchRect.left + 32f, searchRect.top + 3f)
            )
            assertEquals(0, dragAreaTouches, "Dragging inside upper search field must not start window drag")
            pressKey(Key.Backspace)
            assertEquals("", searchState.query, "Upper-edge drag selection + Backspace must delete text")

            // 3) Cmd+A (macOS) / Ctrl+A (Windows/Linux) + Backspace selects all and deletes it.
            searchState.onQueryChange("midnight city")
            pumpFrames()
            val clickPos = Offset(searchRect.left + 90f, searchRect.center.y)
            scene.sendPointerEvent(
                PointerEventType.Press,
                clickPos,
                type = PointerType.Mouse,
                buttons = PointerButtons(isPrimaryPressed = true),
                button = PointerButton.Primary
            )
            scene.sendPointerEvent(
                PointerEventType.Release,
                clickPos,
                type = PointerType.Mouse,
                buttons = PointerButtons(isPrimaryPressed = false),
                button = PointerButton.Primary
            )
            pumpFrames()
            val isMac = System.getProperty("os.name").orEmpty().lowercase().contains("mac")
            pressKey(Key.A, meta = isMac, ctrl = !isMac)
            pressKey(Key.Backspace)
            assertEquals("", searchState.query, "Select All + Backspace must clear query")
            assertEquals(0, dragAreaTouches, "Clicking/selecting all inside search must not touch dragArea")

            // 4) Dragging on titlebar button does not trigger window drag.
            val backButtonCenter = Offset(brandRect.right + 17f, brandRect.center.y)
            drag(backButtonCenter, Offset(backButtonCenter.x + 12f, backButtonCenter.y))
            assertEquals(0, dragAreaTouches, "Titlebar navigation buttons must stay outside dragArea")
            assertEquals(1, backClicks)

            // 5) Dragging on brand and empty caption bar gap DOES trigger window drag.
            drag(brandRect.center, Offset(brandRect.center.x + 25f, brandRect.center.y))
            assertEquals(1, dragAreaTouches, "Dragging brand area must engage window drag")

            val emptyCaptionX = (captionRect.left + searchRect.left) / 2f
            drag(Offset(emptyCaptionX, captionRect.center.y), Offset(emptyCaptionX + 25f, captionRect.center.y))
            assertEquals(2, dragAreaTouches, "Dragging empty titlebar gap must engage window drag")
        } finally {
            scene.close()
            scope.cancel()
        }
    }
}
