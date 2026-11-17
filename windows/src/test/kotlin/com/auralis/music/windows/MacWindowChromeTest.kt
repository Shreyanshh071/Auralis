package com.auralis.music.windows

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.emptyFlow
import com.auralis.music.domain.model.Artist
import com.auralis.music.domain.model.ArtistPage
import com.auralis.music.domain.model.PlaylistResult
import com.auralis.music.domain.model.SearchResults
import com.auralis.music.domain.model.Track
import com.auralis.music.domain.repository.SearchRepository
import com.auralis.music.windows.search.DesktopSearchState
import com.auralis.music.windows.search.FindMusicDropdown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import java.awt.Window
import javax.swing.JFrame
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MacWindowChromeTest {

    private interface ObjC : Library {
        fun objc_getClass(name: String): Pointer?
        fun sel_registerName(name: String): Pointer
        fun objc_msgSend(receiver: Pointer, selector: Pointer): Pointer?
        fun objc_msgSend(receiver: Pointer, selector: Pointer, arg: Long): Pointer?

        companion object {
            val INSTANCE: ObjC = Native.load("objc", ObjC::class.java)
        }
    }

    private interface MsgLong : Library {
        fun objc_msgSend(receiver: Pointer, sel: Pointer): Long

        companion object {
            val INSTANCE: MsgLong = Native.load("objc", MsgLong::class.java)
        }
    }

    @Test
    fun predicateOnlyDisablesNativeDragForFullContentTransparentTitlebarWindows() {
        val fullSizeBit = 1L shl 15
        assertTrue(shouldDisableNativeTitleBarDrag(0x000FL or fullSizeBit, titlebarAppearsTransparent = true))
        assertTrue(shouldDisableNativeTitleBarDrag(0x400FL or fullSizeBit, titlebarAppearsTransparent = true))
        assertFalse(shouldDisableNativeTitleBarDrag(0x000FL or fullSizeBit, titlebarAppearsTransparent = false))
        assertFalse(shouldDisableNativeTitleBarDrag(0x000FL, titlebarAppearsTransparent = true))
        assertFalse(shouldDisableNativeTitleBarDrag(0x000FL, titlebarAppearsTransparent = false))
    }

    @Test
    fun awtViewDisablesNativeDragOnlyForFullContentTransparentWindowOnMac() {
        val isMac = System.getProperty("os.name").orEmpty().lowercase().contains("mac")
        if (!isMac) return

        assertTrue(MacWindowChrome.installIfMac(), "First installIfMac() should succeed on macOS")
        assertTrue(MacWindowChrome.installIfMac(), "Repeated installIfMac() should remain idempotent")

        val customTitle = "MacWindowChromeCustom-${System.nanoTime()}"
        val standardTitle = "MacWindowChromeStd-${System.nanoTime()}"
        var customFrame: JFrame? = null
        var standardFrame: JFrame? = null
        var customCanMove: Int? = null
        var standardCanMove: Int? = null

        try {
            SwingUtilities.invokeAndWait {
                customFrame = JFrame(customTitle).apply {
                    rootPane.putClientProperty("apple.awt.fullWindowContent", true)
                    rootPane.putClientProperty("apple.awt.transparentTitleBar", true)
                    rootPane.putClientProperty("apple.awt.windowTitleVisible", false)
                    setSize(320, 180)
                    setLocation(120, 120)
                    isVisible = true
                }
                standardFrame = JFrame(standardTitle).apply {
                    setSize(320, 180)
                    setLocation(480, 120)
                    isVisible = true
                }
            }
            Thread.sleep(250)
            SwingUtilities.invokeAndWait {
                customCanMove = readContentViewMouseDownCanMoveWindow(customTitle)
                standardCanMove = readContentViewMouseDownCanMoveWindow(standardTitle)
            }
            assertEquals(0, customCanMove, "Full-content transparent-titlebar AWTView must not allow native window drag")
            assertEquals(1, standardCanMove, "Standard AWTView must preserve default AppKit mouseDownCanMoveWindow")
        } finally {
            SwingUtilities.invokeAndWait {
                disposeQuietly(customFrame)
                disposeQuietly(standardFrame)
            }
        }
    }

    @Test
    fun titleBarHitTestingExcludesSearchBodyDisabledAndModalOverlays() {
        val brand = Rect(78f, 0f, 220f, 44f)
        val caption = Rect(220f, 0f, 920f, 44f)
        val search = Rect(380f, 6f, 680f, 38f)
        val trailing = Rect(1188f, 0f, 1200f, 44f)

        assertTrue(isMacTitleBarDragPoint(120f, 20f, brand, caption, trailing, search, titleBarEnabled = true, modalOverlayActive = false))
        assertTrue(isMacTitleBarDragPoint(300f, 36f, brand, caption, trailing, search, titleBarEnabled = true, modalOverlayActive = false))
        assertTrue(isMacTitleBarDragPoint(1194f, 18f, brand, caption, trailing, search, titleBarEnabled = true, modalOverlayActive = false))

        assertFalse(isMacTitleBarDragPoint(500f, 20f, brand, caption, trailing, search, titleBarEnabled = true, modalOverlayActive = false))
        assertFalse(isMacTitleBarDragPoint(300f, 60f, brand, caption, trailing, search, titleBarEnabled = true, modalOverlayActive = false))
        assertFalse(isMacTitleBarDragPoint(300f, 20f, brand, caption, trailing, search, titleBarEnabled = false, modalOverlayActive = false))
        assertFalse(isMacTitleBarDragPoint(300f, 20f, brand, caption, trailing, search, titleBarEnabled = true, modalOverlayActive = true))
        assertFalse(isMacTitleBarDragPoint(300f, 20f, Rect.Zero, Rect.Zero, Rect.Zero, Rect.Zero, titleBarEnabled = true, modalOverlayActive = false))
    }

    @Test
    fun rectToLogicalPointsDividesRetinaDensitySafely() {
        val logical = rectToLogicalPoints(Rect(156f, 0f, 440f, 88f), density = 2f)
        assertEquals(Rect(78f, 0f, 220f, 44f), logical)

        val fallback = rectToLogicalPoints(Rect(10f, 20f, 30f, 40f), density = 0f)
        assertEquals(Rect(10f, 20f, 30f, 40f), fallback)
        assertEquals(Rect.Zero, rectToLogicalPoints(Rect.Zero, density = 2f))
    }

    @Test
    fun fullscreenMaskAndDoubleClickPreferenceParsing() {
        val fullScreenMask = 1L shl 14
        assertTrue(isMacWindowFullscreen(fullScreenMask))
        assertTrue(isMacWindowFullscreen(0x000FL or fullScreenMask))
        assertFalse(isMacWindowFullscreen(0x000FL or (1L shl 15)))

        assertEquals(MacTitleBarDoubleClickAction.MAXIMIZE, parseMacDoubleClickAction("Maximize"))
        assertEquals(MacTitleBarDoubleClickAction.MINIMIZE, parseMacDoubleClickAction("Minimize"))
        assertEquals(MacTitleBarDoubleClickAction.NONE, parseMacDoubleClickAction("None"))
    }

    @Test
    fun attachedWindowHandleTracksBoundsAndModalBlocksOnMac() {
        val isMac = System.getProperty("os.name").orEmpty().lowercase().contains("mac")
        if (!isMac) return

        assertTrue(MacWindowChrome.installIfMac())
        val title = "MacWindowChromeAttached-${System.nanoTime()}"
        var frame: JFrame? = null
        var handle: MacWindowChrome.WindowHandle? = null
        var canMoveWhenCleared: Int? = null

        try {
            SwingUtilities.invokeAndWait {
                frame = JFrame(title).apply {
                    rootPane.putClientProperty("apple.awt.fullWindowContent", true)
                    rootPane.putClientProperty("apple.awt.transparentTitleBar", true)
                    rootPane.putClientProperty("apple.awt.windowTitleVisible", false)
                    setSize(640, 400)
                    setLocation(100, 100)
                    isVisible = true
                }
            }
            Thread.sleep(200)
            SwingUtilities.invokeAndWait {
                val win = frame!!
                val h = MacWindowChrome.attachWindow(win)
                handle = h
                h.titleBarEnabled = true
                h.setBrandBounds(Rect(156f, 0f, 440f, 88f), density = 2f)
                h.setCaptionBounds(Rect(440f, 0f, 1200f, 88f), density = 2f)
                h.setTrailingBounds(Rect(1256f, 0f, 1280f, 88f), density = 2f)
                h.setSearchBounds(Rect(600f, 12f, 960f, 76f), density = 2f)
                MacWindowChrome.acquireModalBlock().use { }
                h.clearBounds()
                canMoveWhenCleared = readContentViewMouseDownCanMoveWindow(title)
            }
            assertEquals(0, canMoveWhenCleared)
        } finally {
            SwingUtilities.invokeAndWait {
                handle?.close()
                disposeQuietly(frame)
            }
        }
    }

    @Test
    fun attachedComposeWindowDisablesMouseDownCanMoveWindowAndAllowsSearchDragSelection() {
        val isMac = System.getProperty("os.name").orEmpty().lowercase().contains("mac")
        if (!isMac) return

        assertTrue(MacWindowChrome.installIfMac())
        val title = "LiveComposeChrome-${System.nanoTime()}"
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val fakeRepo = object : SearchRepository {
            override suspend fun search(query: String) = SearchResults()
            override suspend fun search(query: String, onPartial: (SearchResults) -> Unit) = SearchResults()
            override fun cachedSearch(query: String) = SearchResults()
            override suspend fun searchSongs(query: String) = emptyList<Track>()
            override suspend fun searchLiveSongs(query: String, onUpdate: (List<Track>) -> Unit) = Unit
            override suspend fun searchAlbums(query: String) = emptyList<PlaylistResult>()
            override suspend fun searchArtists(query: String) = emptyList<Artist>()
            override suspend fun searchPlaylists(query: String) = emptyList<PlaylistResult>()
            override suspend fun getSuggestions(query: String) = emptyList<String>()
            override suspend fun getArtistPage(artist: Artist): ArtistPage = throw UnsupportedOperationException()
            override suspend fun getArtistPage(artist: Artist, onPartial: suspend (ArtistPage) -> Unit): ArtistPage = throw UnsupportedOperationException()
            override suspend fun getAlbumTracks(album: PlaylistResult) = emptyList<Track>()
            override fun getRecentSearchQueries() = emptyFlow<List<String>>()
            override suspend fun recordSearchQuery(query: String) = Unit
            override suspend fun removeSearchQuery(query: String) = Unit
            override suspend fun clearSearchHistory() = Unit
        }
        val searchState = DesktopSearchState(fakeRepo, scope)
        var win: androidx.compose.ui.awt.ComposeWindow? = null
        var handle: MacWindowChrome.WindowHandle? = null

        try {
            SwingUtilities.invokeAndWait {
                val cw = androidx.compose.ui.awt.ComposeWindow().apply {
                    this.title = title
                    setSize(1000, 500)
                    setLocation(120, 120)
                    rootPane.putClientProperty("apple.awt.fullWindowContent", true)
                    rootPane.putClientProperty("apple.awt.transparentTitleBar", true)
                    rootPane.putClientProperty("apple.awt.windowTitleVisible", false)
                    val h = MacWindowChrome.attachWindow(this)
                    handle = h
                    setContent {
                        MaterialTheme {
                            val d = LocalDensity.current.density
                            WindowTitleBar(
                                actions = TitleBarActions(search = {
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
                                        liquidify = true,
                                        bar = true
                                    )
                                }),
                                title = "Auralis",
                                icon = null,
                                maximized = false,
                                minimize = {},
                                maximize = {},
                                close = {},
                                captionBounds = { h.setCaptionBounds(it, d) },
                                brandBounds = { h.setBrandBounds(it, d) },
                                trailingBounds = { h.setTrailingBounds(it, d) },
                                searchBounds = { h.setSearchBounds(it, d) },
                            )
                        }
                    }
                    isVisible = true
                }
                win = cw
            }
            Thread.sleep(600)

            var canMoveWithPopulatedBounds: Int? = null
            SwingUtilities.invokeAndWait {
                val cw = win!!
                cw.toFront()
                cw.requestFocus()
                searchState.onQueryChange("ambient chill")
                canMoveWithPopulatedBounds = readContentViewMouseDownCanMoveWindow(title)
            }
            assertEquals(0, canMoveWithPopulatedBounds)

            val h = handle!!
            val sb = h.searchBounds
            assertTrue(!sb.isEmpty)
            assertTrue(!h.isDraggablePoint(sb.center.x, sb.center.y))
            assertTrue(!h.isDraggablePoint(sb.center.x, sb.top + 3f))
            val emptyCaptionX = (h.captionBounds.left + sb.left) / 2f
            assertTrue(h.isDraggablePoint(emptyCaptionX, 18f))

            Thread.sleep(300)
            val robot = java.awt.Robot()
            fun smoothDrag(x1: Int, y1: Int, x2: Int, y2: Int) {
                robot.mouseMove(x1, y1)
                Thread.sleep(50)
                robot.mousePress(java.awt.event.InputEvent.BUTTON1_DOWN_MASK)
                Thread.sleep(40)
                val steps = 20
                for (i in 1..steps) {
                    val x = x1 + ((x2 - x1) * i) / steps
                    val y = y1 + ((y2 - y1) * i) / steps
                    robot.mouseMove(x, y)
                    Thread.sleep(15)
                }
                Thread.sleep(40)
                robot.mouseRelease(java.awt.event.InputEvent.BUTTON1_DOWN_MASK)
                Thread.sleep(150)
            }

            var startLoc = java.awt.Point()
            SwingUtilities.invokeAndWait { startLoc = win!!.locationOnScreen }

            // Click first so FindMusicDropdown opens Popup
            robot.mouseMove(startLoc.x + sb.left.toInt() + 120, startLoc.y + sb.center.y.toInt())
            Thread.sleep(50)
            robot.mousePress(java.awt.event.InputEvent.BUTTON1_DOWN_MASK)
            robot.mouseRelease(java.awt.event.InputEvent.BUTTON1_DOWN_MASK)
            Thread.sleep(300)

            // Drag across text in FindMusicDropdown with Popup open
            val dragFromX = startLoc.x + sb.left.toInt() + 140
            val dragToX = startLoc.x + sb.left.toInt() + 34
            val dragY = startLoc.y + sb.center.y.toInt()
            smoothDrag(dragFromX, dragY, dragToX, dragY)
            robot.keyPress(java.awt.event.KeyEvent.VK_BACK_SPACE)
            robot.keyRelease(java.awt.event.KeyEvent.VK_BACK_SPACE)
            Thread.sleep(200)
            var queryAfterCenterDrag = ""
            var afterSearchDragLoc = java.awt.Point()
            SwingUtilities.invokeAndWait {
                queryAfterCenterDrag = searchState.query
                afterSearchDragLoc = win!!.locationOnScreen
            }
            assertEquals("", queryAfterCenterDrag)
            assertEquals(startLoc, afterSearchDragLoc)

            // Repeat near top of search box (inside 32pt native titlebar band)
            SwingUtilities.invokeAndWait { searchState.onQueryChange("late night") }
            Thread.sleep(200)
            val topY = afterSearchDragLoc.y + sb.top.toInt() + 3
            smoothDrag(afterSearchDragLoc.x + sb.left.toInt() + 140, topY, afterSearchDragLoc.x + sb.left.toInt() + 34, topY)
            robot.keyPress(java.awt.event.KeyEvent.VK_BACK_SPACE)
            robot.keyRelease(java.awt.event.KeyEvent.VK_BACK_SPACE)
            Thread.sleep(200)
            var queryAfterTopDrag = ""
            SwingUtilities.invokeAndWait { queryAfterTopDrag = searchState.query }
            assertEquals("", queryAfterTopDrag)

            // Move through empty draggable caption region before dragging inside search box
            SwingUtilities.invokeAndWait { searchState.onQueryChange("deep focus") }
            Thread.sleep(200)
            robot.mouseMove(afterSearchDragLoc.x + emptyCaptionX.toInt(), afterSearchDragLoc.y + 18)
            Thread.sleep(80)
            smoothDrag(afterSearchDragLoc.x + sb.left.toInt() + 140, dragY, afterSearchDragLoc.x + sb.left.toInt() + 34, dragY)
            robot.keyPress(java.awt.event.KeyEvent.VK_BACK_SPACE)
            robot.keyRelease(java.awt.event.KeyEvent.VK_BACK_SPACE)
            Thread.sleep(200)
            var queryAfterCaptionHoverThenSearchDrag = ""
            SwingUtilities.invokeAndWait { queryAfterCaptionHoverThenSearchDrag = searchState.query }
            assertEquals("", queryAfterCaptionHoverThenSearchDrag)

            // Manually resize window (edge drag + programmatic resize across width/height changes)
            // and verify search bounds update and text-selection drag still works without moving window
            var sizeBeforeResize = java.awt.Dimension()
            SwingUtilities.invokeAndWait { sizeBeforeResize = win!!.size }
            // Drag bottom-right corner inward to shrink both width and height via Cocoa resize
            smoothDrag(
                afterSearchDragLoc.x + sizeBeforeResize.width - 1,
                afterSearchDragLoc.y + sizeBeforeResize.height - 1,
                afterSearchDragLoc.x + sizeBeforeResize.width - 140,
                afterSearchDragLoc.y + sizeBeforeResize.height - 60
            )
            Thread.sleep(400)
            var locAfterEdgeResize = java.awt.Point()
            var sizeAfterEdgeResize = java.awt.Dimension()
            var sbAfterEdgeResize = Rect.Zero
            var canMoveAfterEdgeResize: Int? = null
            SwingUtilities.invokeAndWait {
                locAfterEdgeResize = win!!.locationOnScreen
                sizeAfterEdgeResize = win!!.size
                sbAfterEdgeResize = h.searchBounds
                canMoveAfterEdgeResize = readContentViewMouseDownCanMoveWindow(title)
                searchState.onQueryChange("after edge")
            }
            assertEquals(0, canMoveAfterEdgeResize)
            assertTrue(!sbAfterEdgeResize.isEmpty)
            assertTrue(!h.isDraggablePoint(sbAfterEdgeResize.center.x, sbAfterEdgeResize.center.y))
            assertTrue(!h.isDraggablePoint(sbAfterEdgeResize.center.x, sbAfterEdgeResize.top + 3f))
            Thread.sleep(200)
            // Avoid trailing Close icon (~28dp at right edge of search field)
            val edgeDragFromX = locAfterEdgeResize.x + minOf(sbAfterEdgeResize.right.toInt() - 36, sbAfterEdgeResize.left.toInt() + 140)
            val edgeDragToX = locAfterEdgeResize.x + sbAfterEdgeResize.left.toInt() + 34
            val edgeDragY = locAfterEdgeResize.y + sbAfterEdgeResize.center.y.toInt()
            smoothDrag(edgeDragFromX, edgeDragY, edgeDragToX, edgeDragY)
            robot.keyPress(java.awt.event.KeyEvent.VK_BACK_SPACE)
            robot.keyRelease(java.awt.event.KeyEvent.VK_BACK_SPACE)
            Thread.sleep(200)
            var queryAfterEdgeResizeDrag = ""
            var locAfterEdgeSearchDrag = java.awt.Point()
            SwingUtilities.invokeAndWait {
                queryAfterEdgeResizeDrag = searchState.query
                locAfterEdgeSearchDrag = win!!.locationOnScreen
            }
            assertEquals("", queryAfterEdgeResizeDrag)
            assertEquals(locAfterEdgeResize, locAfterEdgeSearchDrag)

            // Resize wider again and verify searchBounds & text selection continue to track
            SwingUtilities.invokeAndWait {
                win!!.setSize(1080, 540)
                win!!.validate()
                searchState.onQueryChange("after widen")
            }
            Thread.sleep(400)
            var locAfterWiden = java.awt.Point()
            var sbAfterWiden = Rect.Zero
            var canMoveAfterWiden: Int? = null
            SwingUtilities.invokeAndWait {
                locAfterWiden = win!!.locationOnScreen
                sbAfterWiden = h.searchBounds
                canMoveAfterWiden = readContentViewMouseDownCanMoveWindow(title)
            }
            assertEquals(0, canMoveAfterWiden)
            assertTrue(sbAfterWiden.width > sbAfterEdgeResize.width)
            assertTrue(!h.isDraggablePoint(sbAfterWiden.center.x, sbAfterWiden.top + 3f))
            val widenDragFromX = locAfterWiden.x + minOf(sbAfterWiden.right.toInt() - 36, sbAfterWiden.left.toInt() + 140)
            val widenDragToX = locAfterWiden.x + sbAfterWiden.left.toInt() + 34
            val widenDragY = locAfterWiden.y + sbAfterWiden.top.toInt() + 3
            smoothDrag(widenDragFromX, widenDragY, widenDragToX, widenDragY)
            robot.keyPress(java.awt.event.KeyEvent.VK_BACK_SPACE)
            robot.keyRelease(java.awt.event.KeyEvent.VK_BACK_SPACE)
            Thread.sleep(200)
            var queryAfterWidenDrag = ""
            var locAfterWidenSearchDrag = java.awt.Point()
            SwingUtilities.invokeAndWait {
                queryAfterWidenDrag = searchState.query
                locAfterWidenSearchDrag = win!!.locationOnScreen
            }
            assertEquals("", queryAfterWidenDrag)
            assertEquals(locAfterWiden, locAfterWidenSearchDrag)
            assertTrue(sizeAfterEdgeResize.width <= sizeBeforeResize.width)
        } finally {
            scope.cancel()
            SwingUtilities.invokeAndWait {
                handle?.close()
                disposeQuietly(win)
            }
        }
    }

    private fun disposeQuietly(window: Window?) {
        window?.isVisible = false
        window?.dispose()
    }

    private fun readContentViewMouseDownCanMoveWindow(windowTitle: String): Int? {
        val objc = ObjC.INSTANCE
        val msgLong = MsgLong.INSTANCE
        val selSharedApp = objc.sel_registerName("sharedApplication")
        val selWindows = objc.sel_registerName("windows")
        val selCount = objc.sel_registerName("count")
        val selObjectAtIndex = objc.sel_registerName("objectAtIndex:")
        val selTitle = objc.sel_registerName("title")
        val selUtf8 = objc.sel_registerName("UTF8String")
        val selContentView = objc.sel_registerName("contentView")
        val selMouseDownCanMove = objc.sel_registerName("mouseDownCanMoveWindow")

        val nsAppClass = objc.objc_getClass("NSApplication") ?: return null
        val app = objc.objc_msgSend(nsAppClass, selSharedApp) ?: return null
        val windows = objc.objc_msgSend(app, selWindows) ?: return null
        val count = msgLong.objc_msgSend(windows, selCount)
        for (i in 0 until count) {
            val nsWin = objc.objc_msgSend(windows, selObjectAtIndex, i) ?: continue
            val titlePtr = objc.objc_msgSend(nsWin, selTitle)
            val title = titlePtr?.let { objc.objc_msgSend(it, selUtf8)?.getString(0) }
            if (title == windowTitle) {
                val contentView = objc.objc_msgSend(nsWin, selContentView) ?: return null
                return (msgLong.objc_msgSend(contentView, selMouseDownCanMove) and 0xFFL).toInt()
            }
        }
        return null
    }
}
