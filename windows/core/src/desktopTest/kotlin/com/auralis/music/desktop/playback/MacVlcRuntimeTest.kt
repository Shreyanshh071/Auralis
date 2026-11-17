package com.auralis.music.desktop.playback

import java.io.File
import java.nio.file.Files
import kotlin.test.*

class MacVlcRuntimeTest {
    private fun complete(root: File, installed: Boolean = false) {
        val libraries = if (installed) File(root, "lib") else root
        libraries.mkdirs()
        File(libraries, "libvlc.dylib").writeText("vlc")
        File(libraries, "libvlccore.dylib").writeText("core")
        File(root, "plugins").mkdirs()
    }

    @Test fun bundledRuntimeWinsAndUsesItsOwnPlugins() {
        val root = Files.createTempDirectory("auralis-mac-vlc-").toFile()
        try {
            val bundle = File(root, "bundle"); complete(bundle)
            val installed = File(root, "installed"); complete(installed, true)
            val paths = assertNotNull(macVlcPaths(bundle, installed))
            assertTrue(paths.bundled)
            assertEquals(bundle.canonicalFile, paths.libraries)
            assertEquals(File(bundle, "plugins").canonicalFile, paths.plugins)
        } finally { root.deleteRecursively() }
    }

    @Test fun brokenBundleNeverFallsBackToTheBuildHostsVlc() {
        val root = Files.createTempDirectory("auralis-mac-vlc-").toFile()
        try {
            val installed = File(root, "installed"); complete(installed, true)
            val bundle = File(root, "missing")
            assertNull(macVlcPaths(bundle, installed))
            complete(bundle)
            File(bundle, "libvlccore.dylib").delete()
            assertNull(macVlcPaths(bundle, installed))
            assertFalse(assertNotNull(macVlcPaths(null, installed)).bundled)
        } finally { root.deleteRecursively() }
    }

    @Test fun macPayloadNeverRebuildsAWindowsPluginCacheInsideTheSignedApp() {
        val root = Files.createTempDirectory("auralis-mac-vlc-").toFile()
        try {
            complete(root)
            assertEquals(emptyList(), VlcAudioPlayer.pluginCacheArgs(root))
            assertFalse(File(root, "plugins/plugins.dat").exists())
        } finally { root.deleteRecursively() }
    }
}
