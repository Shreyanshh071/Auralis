package com.auralis.music.windows.downloads

import com.auralis.music.desktop.library.DesktopDownloads
import com.auralis.music.domain.model.Track
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.*
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlin.test.*

class DesktopDownloadsTest {
    @Test fun savesFullAudioGroupsPersistsAndRejectsIncompleteDownloads() = runBlocking {
        val bytes = ByteArray(4096) { (it % 255).toByte() }.apply { "ftyp".toByteArray().copyInto(this, 4) }
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var hits = 0
        server.createContext("/audio") { e ->
            hits++
            e.responseHeaders.add("Content-Type", "audio/mp4")
            e.sendResponseHeaders(200, bytes.size.toLong())
            e.responseBody.use { it.write(bytes) }; e.close()
        }
        server.createContext("/bad") { e ->
            e.responseHeaders.add("Content-Type", "text/html")
            e.sendResponseHeaders(200, 2048); e.responseBody.use { it.write(ByteArray(2048)) }; e.close()
        }
        server.createContext("/short") { e ->
            e.responseHeaders.add("Content-Type", "audio/mp4")
            e.sendResponseHeaders(200, 4096)
            runCatching { e.responseBody.use { it.write(bytes, 0, 2048) } }; e.close()
        }
        server.start()
        val root = Files.createTempDirectory("auralis-download-test").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val manager = DesktopDownloads(root, scope, readTimeoutMs = 1000L) { t -> "http://127.0.0.1:${server.address.port}/${if (t.id == "bad") "bad" else if (t.id == "short") "short" else "audio"}" }
        suspend fun settled(count: Int) = withTimeout(10000) {
            while (manager.entries.value.size != count || manager.entries.value.any { it.status in setOf("QUEUED", "DOWNLOADING") }) delay(20)
        }
        try {
            val track = Track("sample", "A/B: song", "Test artist")
            manager.enqueue(listOf(track)); settled(1)
            assertContentEquals(bytes, assertNotNull(manager.localFile(track.id)).readBytes())
            assertEquals("Individual songs", manager.entries.value.first().folder)
            manager.enqueue(listOf(track), "playlist-one", "My Playlist", "https://example.com/playlist-cover.png"); settled(2)
            manager.enqueue(listOf(track), "playlist-two", "My Playlist"); settled(3)
            assertEquals(3, hits)
            assertEquals(3, manager.entries.value.map { it.folder }.distinct().size, "Playlist folders must not collide")
            assertTrue(manager.entries.value.all { !it.file!!.contains("A/B") })
            val scene = androidx.compose.ui.ImageComposeScene(960, 560, coroutineContext = Dispatchers.Unconfined) {
                com.auralis.music.ui.theme.AuralisDesktopTheme(appearanceSettings = com.auralis.music.domain.model.AppearanceSettings(appTheme = "Dark Mode", colorPalette = "White"), platformActions = com.auralis.music.ui.components.DesktopPlatformActions {}) {
                    androidx.compose.material3.Surface(color = androidx.compose.material3.MaterialTheme.colorScheme.surface) {
                        com.auralis.music.windows.library.DesktopDownloadsPage(null, {}, manager)
                    }
                }
            }
            try {
                repeat(5) { scene.render(it * 16_666_667L).close() }
                val image = scene.render(100_000_000L)
                java.io.File("build/screens").mkdirs()
                java.io.File("build/screens/downloads-groups.png").writeBytes(image.encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)!!.bytes)
                image.close()
            } finally { scene.close() }
            val reloaded = DesktopDownloads(root, scope) { error("Offline files must not need network") }
            assertEquals(3, reloaded.entries.value.count(reloaded::available))
            assertEquals("https://example.com/playlist-cover.png", reloaded.entries.value.first { it.groupId == "playlist-one" }.coverUrl)
            assertContentEquals(bytes, reloaded.localFile(track.id)!!.readBytes())
            manager.enqueue(listOf(Track("bad", "Bad", "Test"))); settled(4)
            assertEquals("FAILED", manager.entries.value.first { it.track.id == "bad" }.status)
            assertNull(manager.localFile("bad"))
            manager.enqueue(listOf(Track("short", "Short response", "Test"))); settled(5)
            assertEquals("FAILED", manager.entries.value.first { it.track.id == "short" }.status)
            assertNull(manager.localFile("short"))
            assertFalse(root.walkTopDown().any { it.name.endsWith(".partial") })
            manager.removeGroup("playlist-one")
            assertNotNull(manager.localFile(track.id), "Deleting a playlist must preserve the individual copy")
            assertFalse(manager.entries.value.any { it.groupId == "playlist-one" })
            manager.clear()
            assertNull(manager.localFile(track.id))
        } finally { scope.cancel(); server.stop(0); root.deleteRecursively() }
    }
    @Test fun liveSongDownloadsCompletelyAndPlaysFromDiskWithoutResolver() = runBlocking {
        org.junit.Assume.assumeTrue(System.getenv("AURALIS_VERIFY_DOWNLOADS") == "1")
        val root = java.io.File("build/download-live").apply { mkdirs() }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val manager = DesktopDownloads(root, scope)
        val song = Track("5U5Ru0nTiUM", "Tere Liye", "Live verification")
        var playback: com.auralis.music.desktop.playback.DesktopPlaybackController? = null
        try {
            manager.enqueue(listOf(song))
            withTimeout(180000) {
                while (manager.entries.value.isEmpty() || manager.entries.value.any { it.status in setOf("QUEUED", "DOWNLOADING") }) delay(100)
            }
            val entry = manager.entries.value.first()
            assertEquals("COMPLETED", entry.status, entry.error)
            val file = assertNotNull(manager.localFile(song.id))
            assertEquals(entry.total, file.length())
            println("Complete audio file: ${file.length()} bytes, ${file.absolutePath}")
            val player = assertNotNull(com.auralis.music.desktop.playback.VlcAudioPlayer.createOrNull(java.io.File("C:/Program Files/VideoLAN/VLC")))
            player.setVolume(0f)
            playback = com.auralis.music.desktop.playback.DesktopPlaybackController(player, scope,
                resolveStream = { error("Offline playback must not use stream resolver") }, offlineFile = manager::localFile)
            playback.setVolume(0f)
            playback.playFrom(song, listOf(song))
            withTimeout(15000) { while (player.positionMs.value < 1500L) delay(100) }
            assertTrue(player.lengthMs.value > 1000)
            println("Offline playback advanced to ${player.positionMs.value} ms; duration ${player.lengthMs.value} ms")
        } finally { playback?.release(); scope.cancel() }
    }

    @Test fun downloadRetriesTransientErrorAndSucceeds() {
        runBlocking {
            val bytes = ByteArray(4096) { (it % 255).toByte() }.apply { "ftyp".toByteArray().copyInto(this, 4) }
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            var callCount = 0
            server.createContext("/flaky") { e ->
                callCount++
                if (callCount == 1) {
                    e.sendResponseHeaders(500, -1)
                    e.close()
                } else {
                    e.responseHeaders.add("Content-Type", "audio/mp4")
                    e.sendResponseHeaders(200, bytes.size.toLong())
                    e.responseBody.use { it.write(bytes) }
                    e.close()
                }
            }
            server.start()
            val root = Files.createTempDirectory("auralis-retry-test").toFile()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val manager = DesktopDownloads(root, scope, readTimeoutMs = 3000L) { "http://127.0.0.1:${server.address.port}/flaky" }
            try {
                val song = Track("flaky-song", "Flaky Test", "Artist")
                manager.enqueue(listOf(song))
                withTimeout(10000) {
                    while (manager.entries.value.isEmpty() || manager.entries.value.any { it.status in setOf("QUEUED", "DOWNLOADING") }) delay(50)
                }
                val entry = manager.entries.value.first()
                assertEquals("COMPLETED", entry.status)
                assertTrue(callCount >= 2, "Expected at least 2 attempts for flaky download")
                assertNotNull(manager.localFile(song.id))
            } finally {
                scope.cancel(); server.stop(0); root.deleteRecursively()
            }
        }
    }

    @Test fun folderNamesCannotEscapeRootOrUseWindowsDevices() {
        assertEquals("_CON", DesktopDownloads.sanitize("CON"))
        assertFalse(DesktopDownloads.sanitize("../../outside").contains('/'))
        assertFalse(DesktopDownloads.sanitize("../../outside").contains(".."))
    }
}
