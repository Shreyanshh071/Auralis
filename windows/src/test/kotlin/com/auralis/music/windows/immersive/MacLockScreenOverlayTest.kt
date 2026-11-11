package com.auralis.music.windows.immersive

import com.auralis.music.util.KeyValueStore
import com.auralis.music.windows.settings.DesktopPreferencesStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    private fun localStore() = DesktopPreferencesStore(object : KeyValueStore {
        private val values = mutableMapOf<String, String>()
        override fun getString(key: String, default: String) = values[key] ?: default
        override fun putStrings(values: Map<String, String>) {
            this.values += values
        }
        override fun clear() = values.clear()
    })
}
