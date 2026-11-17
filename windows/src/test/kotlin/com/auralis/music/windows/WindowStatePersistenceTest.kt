package com.auralis.music.windows

import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import com.auralis.music.util.PropertiesFileKeyValueStore
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WindowStatePersistenceTest {

    @Test
    fun codecRoundTripsPlacementAndPortraitOrLandscapeBounds() {
        val dir = Files.createTempDirectory("auralis-window-state-test").toFile()
        try {
            val file = dir.resolve("desktop-window.properties")
            val store = PropertiesFileKeyValueStore(file, synchronous = true)

            val portraitFullscreen = PersistedWindowState(
                placement = WindowPlacement.Fullscreen,
                width = 920f,
                height = 1140f,
                x = 180f,
                y = 64f
            )
            store.clear()
            store.putStrings(WindowStateCodec.encode(portraitFullscreen))

            val reloadedStore = PropertiesFileKeyValueStore(file, synchronous = true)
            assertEquals(portraitFullscreen, WindowStateCodec.decode(reloadedStore))

            val maximized = PersistedWindowState(
                placement = WindowPlacement.Maximized,
                width = 1600f,
                height = 900f,
                x = 40f,
                y = 25f
            )
            store.clear()
            store.putStrings(WindowStateCodec.encode(maximized))
            assertEquals(maximized, WindowStateCodec.decode(PropertiesFileKeyValueStore(file, synchronous = true)))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun codecFallsBackSafelyWhenEmptyOrCorrupt() {
        val dir = Files.createTempDirectory("auralis-window-state-corrupt").toFile()
        try {
            val store = PropertiesFileKeyValueStore(dir.resolve("desktop-window.properties"), synchronous = true)
            assertEquals(PersistedWindowState(), WindowStateCodec.decode(store))

            store.putStrings(
                mapOf(
                    "placement" to "unknown",
                    "width" to "-100",
                    "height" to "not-a-number",
                    "x" to "NaN",
                    "y" to "120"
                )
            )
            val decoded = WindowStateCodec.decode(store)
            assertEquals(WindowPlacement.Floating, decoded.placement)
            assertEquals(PersistedWindowState.DEFAULT_WINDOW_WIDTH, decoded.width)
            assertEquals(PersistedWindowState.DEFAULT_WINDOW_HEIGHT, decoded.height)
            assertNull(decoded.x)
            assertNull(decoded.y)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun resolveRestoresFullscreenPortraitSecondMonitorAndDisconnectFallback() {
        val primary = ScreenBounds(x = 0f, y = 25f, width = 1440f, height = 875f)
        val portraitExternal = ScreenBounds(x = 1440f, y = 0f, width = 1080f, height = 1920f)

        // Portrait custom shape on secondary portrait monitor stays on that monitor.
        val restoredPortrait = resolveRestoredWindowState(
            saved = PersistedWindowState(
                placement = WindowPlacement.Floating,
                width = 960f,
                height = 1480f,
                x = 1500f,
                y = 120f
            ),
            screens = listOf(primary, portraitExternal)
        )
        assertEquals(WindowPlacement.Floating, restoredPortrait.placement)
        assertEquals(DpSize(960.dp, 1480.dp), restoredPortrait.size)
        assertEquals(WindowPosition.Absolute(1500.dp, 120.dp), restoredPortrait.position)

        // Fullscreen closed on an external display that is later unplugged keeps Fullscreen and
        // clamps floating dimensions to the current screen, centering when off-screen.
        val restoredAfterUnplug = resolveRestoredWindowState(
            saved = PersistedWindowState(
                placement = WindowPlacement.Fullscreen,
                width = 1800f,
                height = 1100f,
                x = 2000f,
                y = 100f
            ),
            screens = listOf(primary)
        )
        assertEquals(WindowPlacement.Fullscreen, restoredAfterUnplug.placement)
        assertEquals(DpSize(1440.dp, 875.dp), restoredAfterUnplug.size)
        assertEquals(WindowPosition.Aligned(Alignment.Center), restoredAfterUnplug.position)

        // Slightly overlapping edge of single screen is clamped inside usable bounds.
        val clamped = resolveRestoredWindowState(
            saved = PersistedWindowState(
                placement = WindowPlacement.Floating,
                width = 1000f,
                height = 700f,
                x = 900f,
                y = 400f
            ),
            screens = listOf(primary)
        )
        assertEquals(WindowPosition.Absolute(440.dp, 200.dp), clamped.position)
    }

    @Test
    fun nextStatePreservesFloatingBoundsAcrossFullscreenMaximizeMinimizeHideAndPlayerModes() {
        val base = PersistedWindowState(
            placement = WindowPlacement.Floating,
            width = 1020f,
            height = 980f,
            x = 140f,
            y = 70f
        )

        // Entering Fullscreen preserves previous custom floating size/position.
        val inFullscreen = nextPersistedWindowState(
            current = base,
            placement = WindowPlacement.Fullscreen,
            size = DpSize(1920.dp, 1080.dp),
            position = WindowPosition.Absolute(0.dp, 0.dp),
            isMinimized = false,
            visible = true,
            playerFullscreen = false,
            embeddedImmersive = false,
            isMac = true
        )
        assertEquals(base.copy(placement = WindowPlacement.Fullscreen), inFullscreen)

        // During macOS startup fullscreen animation (Floating frame before toggleFullScreen settles),
        // restoringInitialFullscreen keeps Fullscreen + floating bounds intact.
        val midRestore = nextPersistedWindowState(
            current = inFullscreen,
            placement = WindowPlacement.Floating,
            size = DpSize(1280.dp, 820.dp),
            position = WindowPosition.Absolute(0.dp, 0.dp),
            isMinimized = false,
            visible = true,
            playerFullscreen = false,
            embeddedImmersive = false,
            isMac = true,
            restoringInitialFullscreen = true
        )
        assertEquals(inFullscreen, midRestore)

        // Transient minimize (e.g. Windows -32000,-32000) or hidden-to-tray/dock never overwrites state.
        val whileMinimized = nextPersistedWindowState(
            current = inFullscreen,
            placement = WindowPlacement.Floating,
            size = DpSize(160.dp, 28.dp),
            position = WindowPosition.Absolute((-32000).dp, (-32000).dp),
            isMinimized = true,
            visible = true,
            playerFullscreen = false,
            embeddedImmersive = false,
            isMac = false
        )
        assertEquals(inFullscreen, whileMinimized)

        val whileHidden = nextPersistedWindowState(
            current = inFullscreen,
            placement = WindowPlacement.Floating,
            size = DpSize(900.dp, 600.dp),
            position = WindowPosition.Absolute(0.dp, 0.dp),
            isMinimized = false,
            visible = false,
            playerFullscreen = false,
            embeddedImmersive = false,
            isMac = true
        )
        assertEquals(inFullscreen, whileHidden)

        // Windows Now Playing borderless fullscreen leaves prior Floating placement & bounds intact.
        val winDuringPlayer = nextPersistedWindowState(
            current = base,
            placement = WindowPlacement.Floating,
            size = DpSize(2560.dp, 1440.dp),
            position = WindowPosition.Absolute(0.dp, 0.dp),
            isMinimized = false,
            visible = true,
            playerFullscreen = true,
            embeddedImmersive = false,
            isMac = false
        )
        assertEquals(base, winDuringPlayer)

        // macOS Now Playing automatic Maximized keeps pre-player placement,
        // unless user explicitly enters native macOS Fullscreen while in Now Playing.
        val macAutoMaxPlayer = nextPersistedWindowState(
            current = base,
            placement = WindowPlacement.Maximized,
            size = DpSize(1728.dp, 1117.dp),
            position = WindowPosition.Absolute(0.dp, 25.dp),
            isMinimized = false,
            visible = true,
            playerFullscreen = true,
            embeddedImmersive = false,
            isMac = true,
            macPrePlayerPlacement = WindowPlacement.Floating
        )
        assertEquals(base, macAutoMaxPlayer)

        val macExplicitFullscreenInPlayer = nextPersistedWindowState(
            current = base,
            placement = WindowPlacement.Fullscreen,
            size = DpSize(1728.dp, 1117.dp),
            position = WindowPosition.Absolute(0.dp, 0.dp),
            isMinimized = false,
            visible = true,
            playerFullscreen = true,
            embeddedImmersive = false,
            isMac = true,
            macPrePlayerPlacement = WindowPlacement.Floating
        )
        assertEquals(base.copy(placement = WindowPlacement.Fullscreen), macExplicitFullscreenInPlayer)

        // Transition frame returning to Floating (commitFloatingBounds = false) updates placement
        // without clobbering prior floating bounds, then settled frame commits new size/position.
        val unmaximizedTransition = nextPersistedWindowState(
            current = inFullscreen,
            placement = WindowPlacement.Floating,
            size = DpSize(1600.dp, 1000.dp),
            position = WindowPosition.Absolute(0.dp, 0.dp),
            isMinimized = false,
            visible = true,
            playerFullscreen = false,
            embeddedImmersive = false,
            isMac = true,
            commitFloatingBounds = false
        )
        assertEquals(base, unmaximizedTransition)

        val settledCustomShape = nextPersistedWindowState(
            current = unmaximizedTransition,
            placement = WindowPlacement.Floating,
            size = DpSize(940.dp, 1060.dp),
            position = WindowPosition.Absolute(210.dp, 90.dp),
            isMinimized = false,
            visible = true,
            playerFullscreen = false,
            embeddedImmersive = false,
            isMac = true,
            commitFloatingBounds = true
        )
        assertEquals(
            PersistedWindowState(
                placement = WindowPlacement.Floating,
                width = 940f,
                height = 1060f,
                x = 210f,
                y = 90f
            ),
            settledCustomShape
        )
    }

    @Test
    fun macOsAllowsHalfAndQuarterTiledSizesInPersistenceAndRestoration() {
        assertEquals(600f, minWindowWidth(isMac = true))
        assertEquals(380f, minWindowHeight(isMac = true))
        assertEquals(900f, minWindowWidth(isMac = false))
        assertEquals(600f, minWindowHeight(isMac = false))

        val initial = PersistedWindowState(
            placement = WindowPlacement.Floating,
            width = 1200f,
            height = 800f,
            x = 120f,
            y = 80f
        )

        val macHalfLeft = nextPersistedWindowState(
            current = initial,
            placement = WindowPlacement.Floating,
            size = DpSize(756.dp, 940.dp),
            position = WindowPosition.Absolute(0.dp, 38.dp),
            isMinimized = false,
            visible = true,
            playerFullscreen = false,
            embeddedImmersive = false,
            isMac = true
        )
        assertEquals(
            PersistedWindowState(
                placement = WindowPlacement.Floating,
                width = 756f,
                height = 940f,
                x = 0f,
                y = 38f
            ),
            macHalfLeft
        )

        val macQuarter = nextPersistedWindowState(
            current = initial,
            placement = WindowPlacement.Floating,
            size = DpSize(640.dp, 390.dp),
            position = WindowPosition.Absolute(640.dp, 25.dp),
            isMinimized = false,
            visible = true,
            playerFullscreen = false,
            embeddedImmersive = false,
            isMac = true
        )
        assertEquals(640f, macQuarter.width)
        assertEquals(390f, macQuarter.height)

        val macbookScreen = listOf(ScreenBounds(x = 0f, y = 25f, width = 1280f, height = 775f))
        val restoredQuarter = resolveRestoredWindowState(
            saved = macQuarter,
            screens = macbookScreen,
            minWidth = minWindowWidth(isMac = true),
            minHeight = minWindowHeight(isMac = true)
        )
        assertEquals(DpSize(640.dp, 390.dp), restoredQuarter.size)
        assertEquals(WindowPosition.Absolute(640.dp, 25.dp), restoredQuarter.position)

        // Windows ignores floating bounds below 900x600 during persistence and clamps restored sizes to 900x600.
        val windowsIgnored = nextPersistedWindowState(
            current = initial,
            placement = WindowPlacement.Floating,
            size = DpSize(756.dp, 470.dp),
            position = WindowPosition.Absolute(0.dp, 0.dp),
            isMinimized = false,
            visible = true,
            playerFullscreen = false,
            embeddedImmersive = false,
            isMac = false
        )
        assertEquals(initial, windowsIgnored)

        val windowsRestored = resolveRestoredWindowState(
            saved = PersistedWindowState(WindowPlacement.Floating, width = 756f, height = 470f, x = 0f, y = 25f),
            screens = macbookScreen,
            minWidth = minWindowWidth(isMac = false),
            minHeight = minWindowHeight(isMac = false)
        )
        assertEquals(DpSize(900.dp, 600.dp), windowsRestored.size)
    }
}
