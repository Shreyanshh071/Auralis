package com.auralis.music.windows.settings

import com.sun.jna.platform.win32.Advapi32Util.*
import com.sun.jna.platform.win32.WinReg.HKEY_CURRENT_USER
import java.util.UUID
import kotlin.test.*

class WindowsStartupRegistrationTest {
    @Test fun realWindowsRegistrationRoundTripsAndRepairsDisabledOrStaleEntries() {
        // Isolated keys never participate in Windows login or modify the user's Auralis entry.
        val root = "Software\\Auralis\\IntegrationTests\\${UUID.randomUUID()}"
        val run = "$root\\Run"
        val approval = "$root\\Approval"
        val exe = WindowsAutostart.executable()
        val registration = WindowsStartupRegistration(exe, "Test", run, approval)
        try {
            assertFalse(registration.enabled())
            registration.set(true)
            assertTrue(registration.enabled())
            assertEquals("\"${exe.absolutePath}\" --autostart", registryGetStringValue(HKEY_CURRENT_USER, run, "Test"))
            registryCreateKey(HKEY_CURRENT_USER, approval)
            registrySetBinaryValue(HKEY_CURRENT_USER, approval, "Test", byteArrayOf(3,0,0,0,0,0,0,0,0,0,0,0))
            assertFalse(registration.enabled(), "Windows-disabled entries must not appear enabled")
            registration.set(true)
            assertTrue(registration.enabled(), "Explicit enable must repair the disabled registration")
            registrySetStringValue(HKEY_CURRENT_USER, run, "Test", "C:\\old install\\Auralis.exe")
            assertFalse(registration.enabled(), "A stale or unquoted path isn't a valid registration")
            registration.set(true)
            assertTrue(registration.enabled())
            registration.set(false)
            assertFalse(registryValueExists(HKEY_CURRENT_USER, run, "Test"))
        } finally {
            if (registryKeyExists(HKEY_CURRENT_USER, run)) registryDeleteKey(HKEY_CURRENT_USER, run)
            if (registryKeyExists(HKEY_CURRENT_USER, approval)) registryDeleteKey(HKEY_CURRENT_USER, approval)
            if (registryKeyExists(HKEY_CURRENT_USER, root)) registryDeleteKey(HKEY_CURRENT_USER, root)
        }
    }
}
