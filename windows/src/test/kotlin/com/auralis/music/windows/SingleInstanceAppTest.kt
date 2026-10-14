package com.auralis.music.windows

import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.*

class SingleInstanceAppTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun firstInstanceAcquiresLockAndSecondaryInstanceFails() {
        val dataDir = tempFolder.newFolder("single_instance_test")
        val firstLock = SingleInstanceApp.tryAcquire(dataDir)
        assertNotNull(firstLock, "First instance should acquire lock")

        val secondLock = SingleInstanceApp.tryAcquire(dataDir)
        assertNull(secondLock, "Second instance should NOT acquire lock")

        firstLock.recordWindowHandle(123456L)
        val hwndFile = File(dataDir, "app.hwnd")
        assertTrue(hwndFile.isFile, "HWND file should exist")
        assertTrue(hwndFile.readText().contains("HWND=123456"), "HWND file should record HWND")

        firstLock.close()

        val thirdLock = SingleInstanceApp.tryAcquire(dataDir)
        assertNotNull(thirdLock, "After closing first lock, subsequent instance should acquire lock")
        thirdLock.close()
    }

    @Test
    fun focusExistingWindowHandlesNonExistentWindowGracefully() {
        val dataDir = tempFolder.newFolder("focus_test")
        val focused = SingleInstanceApp.focusExistingWindow(dataDir, "NonExistentWindowName_12345", maxWaitMs = 150L)
        assertFalse(focused, "Should return false when window does not exist")
    }

    @Test
    fun recoverZombieInstanceCleansStaleHwndFileGracefully() {
        val dataDir = tempFolder.newFolder("zombie_test")
        val hwndFile = File(dataDir, "app.hwnd")
        hwndFile.writeText("HWND=99999999\nPID=999999999\n")
        assertTrue(hwndFile.exists(), "Stale hwnd file should exist before recovery")

        val recovered = SingleInstanceApp.recoverZombieInstance(dataDir)
        assertTrue(recovered, "Zombie recovery should succeed")
        assertFalse(hwndFile.exists(), "Stale hwnd file should be removed by recovery")
    }
}
