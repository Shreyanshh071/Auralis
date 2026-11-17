package com.auralis.music.windows.profile

import java.io.File
import java.nio.file.Files
import kotlin.test.*

class BundledSignInBrowserTest {
    @Test fun safariOnlyInstallationCanFindThePrivateBrowserInResources() {
        val root = Files.createTempDirectory("auralis-browser-layout-").toFile()
        try {
            assertNull(YouTubeBrowserSignIn.bundledMacBrowser(root, development = false))
            val executable = File(root, "signin-browser/Chromium.app/Contents/MacOS/Chromium")
            executable.parentFile.mkdirs()
            executable.writeText("fixture")
            executable.setExecutable(true, false)
            val browser = assertNotNull(YouTubeBrowserSignIn.bundledMacBrowser(root, development = false))
            assertEquals(executable, browser.exe)
            assertTrue(browser.chromium)
            assertTrue(browser.bundled)
        } finally { root.deleteRecursively() }
    }
}
