package com.auralis.music.desktop.playback

import com.sun.net.httpserver.HttpServer
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URL
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The relay against a fake stream server: "/limited" refuses every byte past 1 MiB (as YouTube
 * does for some URLs) and "/full" serves everything.
 */
class LocalStreamRelayTest {
    private val size = 2_600_000
    private val data = ByteArray(size) { (it % 251).toByte() }
    private lateinit var upstream: HttpServer
    private val base get() = "http://127.0.0.1:${upstream.address.port}"

    @BeforeTest
    fun startUpstream() {
        upstream = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        upstream.createContext("/") { ex ->
            val limited = ex.requestURI.path == "/limited"
            val range = ex.requestHeaders.getFirst("Range")!!.removePrefix("bytes=")
            val start = range.substringBefore('-').toInt()
            val end = range.substringAfter('-').toIntOrNull() ?: (size - 1)
            if (limited && end >= 1_048_576) {
                ex.sendResponseHeaders(403, -1)
            } else {
                ex.responseHeaders.add("Content-Range", "bytes $start-$end/$size")
                ex.responseHeaders.add("Content-Type", "audio/webm")
                ex.sendResponseHeaders(206, (end - start + 1).toLong())
                ex.responseBody.write(data, start, end - start + 1)
            }
            ex.close()
        }
        upstream.start()
    }

    @AfterTest
    fun stopUpstream() = upstream.stop(0)

    private fun read(url: String, range: String?): Pair<Int, ByteArray> {
        val connection = URL(url).openConnection() as HttpURLConnection
        range?.let { connection.setRequestProperty("Range", it) }
        val code = connection.responseCode
        val body = runCatching { connection.inputStream.use { it.readBytes() } }.getOrElse { ByteArray(0) }
        return code to body
    }

    @Test
    fun servesAWholeStreamThatYouTubeOnlyServesInPieces() {
        val local = LocalStreamRelay.register("$base/full?clen=$size")
        val (code, body) = read(local, "bytes=0-")
        assertEquals(206, code)
        assertContentEquals(data, body)
    }

    @Test
    fun switchesToAFreshUrlWhenBytesPastTheFirstMegabyteAreRefused() {
        val local = LocalStreamRelay.register("$base/limited?clen=$size", refresh = { "$base/full?clen=$size" })
        val (_, body) = read(local, "bytes=0-")
        assertContentEquals(data, body)
    }

    @Test
    fun seeksPastTheLimitThroughAFreshUrl() {
        val local = LocalStreamRelay.register("$base/limited?clen=$size", refresh = { "$base/full?clen=$size" })
        val (code, body) = read(local, "bytes=2000000-")
        assertEquals(206, code)
        assertContentEquals(data.copyOfRange(2_000_000, size), body)
    }

    @Test
    fun neverSplicesInADifferentFile() {
        val local = LocalStreamRelay.register("$base/limited?clen=$size", refresh = { "$base/full?clen=999" })
        val (_, body) = read(local, "bytes=0-")
        assertTrue(body.size < size, "a refresh to another file must end the stream, not continue it")
        assertContentEquals(data.copyOfRange(0, body.size), body)
    }
}
