package com.auralis.music.windows.immersive

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.auralis.music.util.KeyValueStore
import com.auralis.music.windows.settings.DesktopPreferencesStore
import java.awt.Rectangle
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MacLockScreenOverlayTest {
    @Test
    fun lockScreenPreferenceDefaultsToEnabledAndPersistsChanges() {
        val prefs = ImmersivePreferences(localStore())
        assertTrue(prefs.macLockScreenOverlay, "Lock Screen overlay should be enabled by default")
        prefs.macLockScreenOverlay = false
        assertFalse(prefs.macLockScreenOverlay)
        prefs.macLockScreenOverlay = true
        assertTrue(prefs.macLockScreenOverlay)
    }

    @Test
    fun osGuardOnlyMatchesMacOsHosts() {
        assertTrue(isMacLockScreenHost("Mac OS X"))
        assertTrue(isMacLockScreenHost("macOS"))
        assertFalse(isMacLockScreenHost("Windows 11"))
        assertFalse(isMacLockScreenHost("Linux"))
        assertFalse(isMacLockScreenHost(""))
        assertFalse(isMacLockScreenHost(null))
    }

    @Test
    fun nativeWindowConstantsMatchLockScreenSpaceRequirements() {
        assertEquals(400, LOCK_SCREEN_SPACE_LEVEL)
        assertEquals(337L, LOCK_SCREEN_COLLECTION_BEHAVIOR)
        assertEquals(Int.MAX_VALUE.toLong() - 2L, LOCK_SCREEN_WINDOW_LEVEL)
    }

    @Test
    fun overlayBoundsStayCenteredInMiddleBandClearOfClockAndPasswordArea() {
        listOf(
            Triple(0, 1512, 982),
            Triple(0, 1280, 800),
            Triple(0, 2560, 1440),
            Triple(-1920, 1920, 1080)
        ).forEach { (originX, width, height) ->
            val bounds = computeLockScreenOverlayBounds(
                screenX = originX,
                screenY = 0,
                screenWidth = width,
                screenHeight = height
            )
            assertEquals(originX + (width - bounds.width) / 2, bounds.x)
            assertTrue(bounds.width <= 580)
            assertTrue(bounds.height in 260..340)
            assertTrue(bounds.y >= (height * 0.30f).toInt(), "Must stay below lock clock on ${width}x$height")
            assertTrue(
                bounds.y + bounds.height <= (height * 0.77f).toInt(),
                "Must stay above avatar/password area on ${width}x$height"
            )
        }
    }

    @Test
    fun clearActiveWindowIsSafeWithoutPriorNativeInit() {
        MacLockScreenBridge.clearActiveLockScreenWindow()
        assertFalse(MacLockScreenBridge.isLocked.value)
    }

    @Test
    fun darwinFrameworksAndSkyLightSymbolsResolveOnMacOs() {
        if (isMacLockScreenHost()) {
            assertTrue(
                MacLockScreenBridge.verifyNativeSymbolsLoaded(),
                "Expected Darwin CF/ObjC/dispatch/SkyLight symbols to resolve on macOS"
            )
        } else {
            assertFalse(MacLockScreenBridge.verifyNativeSymbolsLoaded())
        }
    }

    @Test
    fun playerPillStaysStationaryWhetherLyricsAreLoadedOrNot() {
        for ((height, compact) in listOf(340 to false, 280 to true)) {
            var lyricsLoaded by mutableStateOf(false)
            var pillBounds by mutableStateOf<Rect?>(null)
            val scene = ImageComposeScene(
                width = 580,
                height = height,
                density = Density(1f),
                coroutineContext = Dispatchers.Unconfined
            ) {
                LockScreenOverlayLayout(
                    compactLyrics = compact,
                    lyricsSlot = { rowHeight ->
                        if (lyricsLoaded) {
                            Box(Modifier.fillMaxWidth().height(rowHeight * 3))
                        }
                    },
                    playerPill = {
                        Box(
                            Modifier
                                .size(width = 468.dp, height = 104.dp)
                                .onGloballyPositioned { pillBounds = it.boundsInRoot() }
                        )
                    }
                )
            }
            try {
                scene.render(0L).close()
                val withoutLyrics = assertNotNull(pillBounds)

                lyricsLoaded = true
                scene.render(16_666_667L).close()
                val withLyrics = assertNotNull(pillBounds)

                lyricsLoaded = false
                scene.render(33_333_334L).close()
                val afterUnloaded = assertNotNull(pillBounds)

                assertEquals(withLyrics.top, withoutLyrics.top, "Pill shifted vertically before lyrics loaded (compact=$compact)")
                assertEquals(withLyrics.bottom, withoutLyrics.bottom, "Pill bottom shifted before lyrics loaded (compact=$compact)")
                assertEquals(withLyrics.top, afterUnloaded.top, "Pill shifted vertically after lyrics unloaded (compact=$compact)")
                assertEquals(withLyrics.bottom, afterUnloaded.bottom, "Pill bottom shifted after lyrics unloaded (compact=$compact)")
            } finally {
                scene.close()
            }
        }
    }

    @Test
    fun windowBoundsSwitchBetweenFullScreenAndMiddleBand() {
        val full = computeLockScreenWindowBounds(
            displayX = 0,
            displayY = 0,
            displayWidth = 1512,
            displayHeight = 982,
            backdropCollapsed = false
        )
        assertEquals(LockScreenOverlayBounds(0, 0, 1512, 982), full)

        val collapsed = computeLockScreenWindowBounds(
            displayX = 0,
            displayY = 0,
            displayWidth = 1512,
            displayHeight = 982,
            backdropCollapsed = true
        )
        assertEquals(
            computeLockScreenOverlayBounds(0, 0, 1512, 982),
            collapsed
        )
    }

    @Test
    fun cocoaFrameConversionHandlesPrimaryAndSecondaryDisplays() {
        assertEquals(
            LockScreenCocoaFrame(0.0, 0.0, 1512.0, 982.0),
            awtBoundsToCocoaFrame(LockScreenOverlayBounds(0, 0, 1512, 982), primaryScreenHeight = 982)
        )
        assertEquals(
            LockScreenCocoaFrame(-1920.0, -98.0, 1920.0, 1080.0),
            awtBoundsToCocoaFrame(LockScreenOverlayBounds(-1920, 0, 1920, 1080), primaryScreenHeight = 982)
        )
        assertEquals(
            LockScreenCocoaFrame(0.0, 982.0, 1920.0, 1080.0),
            awtBoundsToCocoaFrame(LockScreenOverlayBounds(0, -1080, 1920, 1080), primaryScreenHeight = 982)
        )
    }

    @Test
    fun primaryDisplayAlwaysOrdersFirstForLockScreen() {
        val leftExternal = ImmersiveDisplay("-1920,0", Rectangle(-1920, 0, 1920, 1080))
        val macBook = ImmersiveDisplay("0,0", Rectangle(0, 0, 1512, 982))
        val rightExternal = ImmersiveDisplay("1512,0", Rectangle(1512, 0, 2560, 1440))

        val ordered = orderLockScreenDisplays(
            displays = listOf(rightExternal, leftExternal, macBook),
            primaryBounds = Rectangle(0, 0, 1512, 982)
        )
        assertEquals(listOf(macBook, leftExternal, rightExternal), ordered)
    }

    @Test
    fun fullScreenLockScreenKeepsPillStationaryAndRevealsPasswordOnlyViaButton() {
        var lyricsLoaded by mutableStateOf(false)
        var revealCount = 0
        var pillBounds by mutableStateOf<Rect?>(null)
        var clockBounds by mutableStateOf<Rect?>(null)
        var unlockButtonBounds by mutableStateOf<Rect?>(null)
        val scene = ImageComposeScene(
            width = 1280,
            height = 800,
            density = Density(1f),
            coroutineContext = Dispatchers.Unconfined
        ) {
            MaterialTheme(colorScheme = darkColorScheme()) {
                FullScreenLockScreenScaffold(
                    compactLyrics = false,
                    background = { _ -> Box(Modifier.fillMaxSize()) },
                    clock = { _, _ ->
                        Box(
                            Modifier
                                .size(240.dp, 110.dp)
                                .onGloballyPositioned { clockBounds = it.boundsInRoot() }
                        )
                    },
                    lyricsSlot = { rowHeight ->
                        if (lyricsLoaded) {
                            Box(Modifier.fillMaxWidth().height(rowHeight * 3))
                        }
                    },
                    playerPill = {
                        Box(
                            Modifier
                                .size(width = 468.dp, height = 104.dp)
                                .onGloballyPositioned { pillBounds = it.boundsInRoot() }
                        )
                    },
                    onRevealPassword = { revealCount++ },
                    touchIdActive = true,
                    unlockButtonModifier = Modifier.onGloballyPositioned { unlockButtonBounds = it.boundsInRoot() }
                )
            }
        }
        try {
            scene.render(0L).close()
            val initialPill = assertNotNull(pillBounds)
            val clock = assertNotNull(clockBounds)
            val button = assertNotNull(unlockButtonBounds)
            assertTrue(clock.bottom < initialPill.top, "Clock must remain above lyrics/player area")

            lyricsLoaded = true
            scene.render(16_666_667L).close()
            val loadedPill = assertNotNull(pillBounds)
            assertEquals(initialPill.top, loadedPill.top, "Full-screen pill shifted vertically when lyrics loaded")
            assertEquals(initialPill.bottom, loadedPill.bottom, "Full-screen pill bottom shifted when lyrics loaded")

            // Click inside the player pill: must NOT collapse/reveal password.
            scene.sendPointerEvent(PointerEventType.Press, loadedPill.center, type = PointerType.Mouse)
            scene.sendPointerEvent(PointerEventType.Release, loadedPill.center, type = PointerType.Mouse)
            assertEquals(0, revealCount, "Clicking inside the player pill must not dismiss the full-screen backdrop")

            // Click empty backdrop outside the pill and outside the button: must NOT reveal password.
            val backdropSpot = Offset(80f, 300f)
            scene.sendPointerEvent(PointerEventType.Press, backdropSpot, type = PointerType.Mouse)
            scene.sendPointerEvent(PointerEventType.Release, backdropSpot, type = PointerType.Mouse)
            assertEquals(0, revealCount, "Clicking backdrop outside the button must keep dynamic backdrop active")

            // Click the "Unlock with password" button: MUST reveal password.
            scene.sendPointerEvent(PointerEventType.Press, button.center, type = PointerType.Mouse)
            scene.sendPointerEvent(PointerEventType.Release, button.center, type = PointerType.Mouse)
            assertEquals(1, revealCount, "Clicking Unlock with password button must reveal password")
        } finally {
            scene.close()
        }
    }

    @Test
    fun secondaryLockScreenRevealsPasswordOnlyViaButton() {
        var revealCount = 0
        var unlockButtonBounds by mutableStateOf<Rect?>(null)
        val scene = ImageComposeScene(
            width = 1280,
            height = 800,
            density = Density(1f),
            coroutineContext = Dispatchers.Unconfined
        ) {
            MaterialTheme(colorScheme = darkColorScheme()) {
                SecondaryLockScreenScaffold(
                    background = { Box(Modifier.fillMaxSize()) },
                    clock = { _, _ -> Box(Modifier.size(200.dp, 90.dp)) },
                    touchIdActive = false,
                    onRevealPassword = { revealCount++ },
                    unlockButtonModifier = Modifier.onGloballyPositioned { unlockButtonBounds = it.boundsInRoot() }
                )
            }
        }
        try {
            scene.render(0L).close()
            val button = assertNotNull(unlockButtonBounds)

            scene.sendPointerEvent(PointerEventType.Press, Offset(120f, 120f), type = PointerType.Mouse)
            scene.sendPointerEvent(PointerEventType.Release, Offset(120f, 120f), type = PointerType.Mouse)
            assertEquals(0, revealCount, "Clicking secondary lock backdrop must not dismiss dynamic background")

            scene.sendPointerEvent(PointerEventType.Press, button.center, type = PointerType.Mouse)
            scene.sendPointerEvent(PointerEventType.Release, button.center, type = PointerType.Mouse)
            assertEquals(1, revealCount, "Clicking secondary Unlock with password button must reveal password")
        } finally {
            scene.close()
        }
    }

    @Test
    fun touchIdLabelAndBioutilParsingReflectRealAvailability() {
        assertEquals("Touch ID active", lockScreenTouchIdLabel(true))
        assertNull(lockScreenTouchIdLabel(false))

        val enabledOutput = """
            User Touch ID configuration:
            	Biometrics for unlock: 1
            	Biometrics for ApplePay: 1
            	Effective biometrics for unlock: 1
            	Effective biometrics for ApplePay: 1
            Operation performed successfully.
        """.trimIndent()
        assertEquals(true, parseBioutilUnlockEnabled(enabledOutput))

        val disabledOutput = """
            User Touch ID configuration:
            	Biometrics for unlock: 0
            	Effective biometrics for unlock: 0
        """.trimIndent()
        assertEquals(false, parseBioutilUnlockEnabled(disabledOutput))
        assertNull(parseBioutilUnlockEnabled("Operation failed"))

        val touchIdAvailable = MacLockScreenBridge.isTouchIdAvailableForUnlock()
        if (!isMacLockScreenHost()) {
            assertFalse(touchIdAvailable)
        }
    }

    @Test
    fun unlockActionHeightShowsSubtitleOnlyWhenTouchIdIsActive() {
        var withoutTouchId by mutableStateOf<Rect?>(null)
        var withTouchId by mutableStateOf<Rect?>(null)
        var active by mutableStateOf(false)
        val scene = ImageComposeScene(
            width = 400,
            height = 160,
            density = Density(1f),
            coroutineContext = Dispatchers.Unconfined
        ) {
            MaterialTheme(colorScheme = darkColorScheme()) {
                UnlockWithPasswordAction(
                    touchIdActive = active,
                    onClick = {},
                    modifier = Modifier.onGloballyPositioned {
                        if (active) withTouchId = it.boundsInRoot() else withoutTouchId = it.boundsInRoot()
                    }
                )
            }
        }
        try {
            scene.render(0L).close()
            val hInactive = assertNotNull(withoutTouchId).height
            active = true
            scene.render(16_666_667L).close()
            val hActive = assertNotNull(withTouchId).height
            assertTrue(hActive > hInactive, "Touch ID active subtitle should only render when Touch ID is active")
        } finally {
            scene.close()
        }
    }

    private fun localStore() = DesktopPreferencesStore(object : KeyValueStore {
        private val values = mutableMapOf<String, String>()
        override fun getString(key: String, default: String) = values[key] ?: default
        override fun putStrings(values: Map<String, String>) {
            this.values += values
        }
        override fun clear() = values.clear()
    })
}
