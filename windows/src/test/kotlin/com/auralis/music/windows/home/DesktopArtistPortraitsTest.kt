package com.auralis.music.windows.home

import androidx.compose.foundation.layout.fillMaxSize

import org.json.JSONObject
import kotlin.test.*

class DesktopArtistPortraitsTest {
    @Test fun savedWindowsFileUriActuallyRendersInArtistCard() {
        val folder = java.nio.file.Files.createTempDirectory("artist portrait space").toFile()
        val file = java.io.File(folder, "portrait.png")
        val image = java.awt.image.BufferedImage(32, 32, java.awt.image.BufferedImage.TYPE_INT_RGB)
        image.createGraphics().apply { color = java.awt.Color.MAGENTA; fillRect(0, 0, 32, 32); dispose() }
        javax.imageio.ImageIO.write(image, "png", file)
        val scene = androidx.compose.ui.ImageComposeScene(32, 32, coroutineContext = kotlinx.coroutines.Dispatchers.Unconfined) {
            FamiliarArtistPhoto(file.toURI().toString(), "Artist", androidx.compose.ui.Modifier.fillMaxSize())
        }
        try {
            var rendered = false
            for (frame in 0..100) {
                val bytes = scene.render(frame * 16_666_667L).use { it.encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)!!.use { it.bytes } }
                val pixel = javax.imageio.ImageIO.read(bytes.inputStream()).getRGB(16, 16)
                if ((pixel and 0xFFFFFF) == 0xFF00FF) { rendered = true; break }
                Thread.sleep(10)
            }
            assertTrue(rendered, "The cached Windows file URI must display the portrait")
        } finally { scene.close(); folder.deleteRecursively() }
    }

    @Test fun portraitsAreDecodedAndSavedLocallyAndBrokenImagesAreRejected() = kotlinx.coroutines.runBlocking {
        val directory = java.nio.file.Files.createTempDirectory("verified-portraits").toFile()
        val bitmap = java.awt.image.BufferedImage(120, 120, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val encoded = java.io.ByteArrayOutputStream().also { javax.imageio.ImageIO.write(bitmap, "png", it) }.toByteArray()
        var calls = 0
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/portrait") { exchange ->
            calls++
            exchange.sendResponseHeaders(200, encoded.size.toLong())
            exchange.responseBody.use { it.write(encoded) }
        }
        server.createContext("/broken") { exchange ->
            val error = "not an image".toByteArray()
            exchange.sendResponseHeaders(200, error.size.toLong())
            exchange.responseBody.use { it.write(error) }
        }
        server.start()
        try {
            val cache = DesktopArtistPortraits(directory)
            val url = "http://127.0.0.1:${server.address.port}/portrait"
            val local = assertNotNull(cache.localPhoto(url))
            assertTrue(java.io.File(java.net.URI(local)).isFile)
            assertEquals(local, cache.localPhoto(url))
            assertEquals(1, calls)
            assertNull(cache.localPhoto("http://127.0.0.1:${server.address.port}/broken"))
        } finally { server.stop(0); directory.deleteRecursively() }
    }

    private fun page(title: String, description: String) = JSONObject().put("title", title).put("description", description)
    @Test fun portraitsRequireTheExactMusicEntity() {
        assertTrue(portraitPageMatches("The Police", page("The Police", "English rock band")))
        assertTrue(portraitPageMatches("Pawan Singh", page("Pawan Singh (singer)", "Indian singer and actor")))
        assertFalse(portraitPageMatches("The Police", page("Police", "Law enforcement organisation")))
        assertFalse(portraitPageMatches("Radiohead", page("Thom Yorke", "English musician")))
        assertFalse(portraitPageMatches("TV Girl", page("TV Girl", "Wikimedia disambiguation page")
            .put("pageprops", JSONObject().put("disambiguation", ""))))
    }
}
