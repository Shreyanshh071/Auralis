package com.auralis.music.windows.home

import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.Dispatchers
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import kotlin.test.*

@OptIn(coil3.annotation.DelicateCoilApi::class)
class HomeArtworkRecoveryTest {
    @Test fun keepsOriginalAndSignedCoversAndOnlyUsesValidExactVideoFallbacks() {
        val spotify = "https://i.scdn.co/image/ab67616d00001e02example"
        assertEquals(listOf("https://i.scdn.co/image/ab67616d0000b273example", spotify), homeArtworkSources(spotify, "sp_123"))
        val signed = "https://i.ytimg.com/pl_c/playlist/cover.jpg?sqp=signature&other=value"
        assertEquals(listOf(signed), homeArtworkSources(signed, null))
        assertTrue(homeArtworkSources(null, "sp_123456789").isEmpty())
        assertTrue(homeArtworkSources(null, "local:track").isEmpty())
        assertTrue(homeArtworkSources(null, "not-a-video-id").isEmpty())
        val exact = homeArtworkSources("https://i.ytimg.com/vi/HfpR4tAmI7E/hq720.jpg", "7avJyVkxVA0")
        assertEquals("https://i.ytimg.com/vi/HfpR4tAmI7E/hqdefault.jpg", exact[1])
        assertEquals("https://i.ytimg.com/vi/HfpR4tAmI7E/mqdefault.jpg", exact[2])
        assertEquals(exact.size, exact.distinct().size)
    }

    @Test fun retriesTransientFailureWithoutChangingTheCover() = verifyRecovery(transient = true)
    @Test fun fallsBackAfterPreferredImageFailsAndRendersTheActualCover() = verifyRecovery(transient = false)

    private fun verifyRecovery(transient: Boolean) {
        val requests = AtomicInteger()
        val alternateRequests = AtomicInteger()
        val sourceImage = BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until 16) for (x in 0 until 16) sourceImage.setRGB(x, y, 0xffff2020.toInt())
        val png = ByteArrayOutputStream().also { ImageIO.write(sourceImage, "png", it) }.toByteArray()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        fun respond(exchange: com.sun.net.httpserver.HttpExchange, success: Boolean) {
            val body = if (success) png else "Unavailable".toByteArray()
            exchange.responseHeaders.add("Cache-Control", "no-store")
            exchange.responseHeaders.add("Content-Type", if (success) "image/png" else "text/plain")
            exchange.sendResponseHeaders(if (success) 200 else 503, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
            exchange.close()
        }
        server.createContext("/preferred") { e ->
            val attempt = requests.incrementAndGet()
            respond(e, transient && attempt > 1)
        }
        server.createContext("/original") { e -> alternateRequests.incrementAndGet(); respond(e, true) }
        server.start()
        val previousLoader = coil3.SingletonImageLoader.get(coil3.PlatformContext.INSTANCE)
        val loader = coil3.ImageLoader.Builder(coil3.PlatformContext.INSTANCE).build()
        coil3.SingletonImageLoader.setUnsafe(loader)
        val base = "http://127.0.0.1:${server.address.port}"
        var loaded: String? = null
        val scene = ImageComposeScene(100, 100, coroutineContext = Dispatchers.Unconfined) {
            MaterialTheme {
                HomeArtworkImage(listOf("$base/preferred", "$base/original"), Modifier.size(100.dp)) { loaded = it }
            }
        }
        try {
            var frame = 0L
            val deadline = System.nanoTime() + 8_000_000_000L
            while (loaded == null && System.nanoTime() < deadline) {
                scene.render(frame++ * 16_666_667L).close()
                Thread.sleep(15)
            }
            assertEquals(if (transient) "$base/preferred" else "$base/original", loaded,
                "preferred requests=${requests.get()}, alternate requests=${alternateRequests.get()}")
            repeat(3) { scene.render(frame++ * 16_666_667L).close() }
            val bytes = scene.render(frame * 16_666_667L).use { image -> image.encodeToData()!!.use { it.bytes } }
            val output = File("build/screens/home-artwork-recovery").apply { mkdirs() }
            File(output, if (transient) "retried.png" else "fallback.png").writeBytes(bytes)
            val pixel = ImageIO.read(bytes.inputStream()).getRGB(50, 50)
            assertTrue((pixel ushr 16 and 255) > 240 && (pixel ushr 8 and 255) < 50,
                "Recovered artwork must replace the music-note placeholder")
            assertEquals(2, requests.get(), "Preferred source gets one bounded retry")
            assertEquals(if (transient) 0 else 1, alternateRequests.get())
        } finally {
            scene.close()
            coil3.SingletonImageLoader.setUnsafe(previousLoader)
            loader.shutdown()
            server.stop(0)
        }
    }
}
