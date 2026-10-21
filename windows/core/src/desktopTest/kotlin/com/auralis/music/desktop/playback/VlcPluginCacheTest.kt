package com.auralis.music.desktop.playback

import java.io.File
import java.nio.file.Files
import kotlin.test.*

class VlcPluginCacheTest {
    private fun vlcDir(): File = Files.createTempDirectory("auralis-vlc").toFile().apply {
        deleteOnExit()
        File(this, "libvlccore.dll").writeText("core")
        File(this, "plugins/codec").mkdirs()
        File(this, "plugins/codec/libopus_plugin.dll").writeText("opus")
    }

    @Test fun missingCacheIsRebuilt() {
        assertEquals(listOf("--reset-plugins-cache"), VlcAudioPlayer.pluginCacheArgs(vlcDir()))
    }

    @Test fun freshCacheIsUsed() {
        val dir = vlcDir()
        File(dir, "plugins/plugins.dat").apply { writeText("cache"); setLastModified(System.currentTimeMillis() + 5_000) }
        assertEquals(emptyList(), VlcAudioPlayer.pluginCacheArgs(dir))
    }

    @Test fun anUpdateMakesTheCacheStale() {
        val dir = vlcDir()
        val cache = File(dir, "plugins/plugins.dat").apply { writeText("cache") }
        cache.setLastModified(System.currentTimeMillis() - 60_000)
        // Installing a new build rewrites the plugin files with newer times.
        File(dir, "plugins/codec/libopus_plugin.dll").setLastModified(System.currentTimeMillis())
        assertEquals(listOf("--reset-plugins-cache"), VlcAudioPlayer.pluginCacheArgs(dir))
    }

    @Test fun aDirectoryWithoutPluginsGetsNoCacheOption() {
        val dir = Files.createTempDirectory("auralis-vlc-empty").toFile().apply { deleteOnExit() }
        assertEquals(emptyList(), VlcAudioPlayer.pluginCacheArgs(dir))
    }
}
