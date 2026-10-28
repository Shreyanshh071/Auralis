package com.auralis.music.desktop.playback

import com.sun.net.httpserver.HttpServer
import okhttp3.OkHttpClient
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class DesktopStreamProbeTest {
    private lateinit var server: HttpServer
    private val requests = AtomicInteger()
    private val data = ByteArray(32768) { (it % 251).toByte() }
    private val client = OkHttpClient.Builder().callTimeout(2, TimeUnit.SECONDS).build()
    private val base get() = "http://127.0.0.1:${server.address.port}"

    @BeforeTest fun startServer() {
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/") { exchange ->
            requests.incrementAndGet()
            val path = exchange.requestURI.path
            val status = path.substringAfterLast('/').toIntOrNull()
                ?: if (path == "/full" || path == "/html") 200 else 206
            val bytes = when (path) {
                "/tiny" -> data.copyOf(512)
                "/truncated" -> data.copyOf(10)
                "/full" -> ByteArray(1_000_000) { (it % 251).toByte() }
                else -> data
            }
            exchange.responseHeaders.add("Content-Type", if (path == "/html") "text/html" else "audio/webm")
            if (status == 206) exchange.responseHeaders.add("Content-Range",
                if (path == "/wrong-range") "bytes 100-32867/40000"
                else if (path == "/tiny") "bytes 0-511/512" else "bytes 0-32767/32768")
            try {
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.write(bytes)
            } catch (_: java.io.IOException) { } finally { exchange.close() }
        }
        server.start()
    }

    @AfterTest fun stopServer() { server.stop(0); client.dispatcher.executorService.shutdown() }

    @Test fun acceptsAndCachesOnlyTheRequestedStartingAudio() {
        var saved = ByteArray(0)
        val result = DesktopStreamProbe.check(client, "$base/valid?clen=32768", "test") { _, bytes -> saved = bytes }
        assertEquals(206, result)
        assertContentEquals(data, saved)
        assertEquals(200, DesktopStreamProbe.check(client, "$base/full", "test") { _, bytes -> saved = bytes })
        assertContentEquals(data, saved, "A whole-file response must only read the first block")
        assertEquals(206, DesktopStreamProbe.check(client, "$base/tiny?clen=512", "test") { _, bytes -> saved = bytes })
        assertEquals(512, saved.size)
    }

    @Test fun rejectsHttpFailuresAndInvalidOrTruncatedAudio() {
        for (status in listOf(403, 404, 429, 500, 503)) {
            val result = DesktopStreamProbe.check(client, "$base/status/$status", "test") { _, _ -> fail("Failed HTTP cached as audio") }
            assertEquals(status, result)
            assertFalse(DesktopStreamProbe.playable(result))
        }
        for (path in listOf("wrong-range", "truncated", "html")) {
            val result = DesktopStreamProbe.check(client, "$base/$path", "test") { _, _ -> fail("Invalid audio cached") }
            assertEquals(-1, result, path)
        }
        assertFalse(DesktopStreamProbe.playable(-1))
    }

    @Test fun playbackReusesTheProbeBytesWithoutAnotherUpstreamRequest() {
        val url = "$base/valid?clen=32768"
        assertEquals(206, DesktopStreamProbe.check(client, url, "test", LocalStreamRelay::offerFirstBlock))
        val local = LocalStreamRelay.register(url)
        val connection = URL(local).openConnection() as HttpURLConnection
        connection.setRequestProperty("Range", "bytes=0-32767")
        assertContentEquals(data, connection.inputStream.use { it.readBytes() })
        connection.disconnect()
        assertEquals(1, requests.get(), "Probe and playback fetched the same first block twice")
    }
}
