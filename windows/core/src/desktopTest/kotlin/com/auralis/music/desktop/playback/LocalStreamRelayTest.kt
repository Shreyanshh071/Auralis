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
    fun concurrentRefusedPiecesShareOneRefresh() {
        val refreshes = java.util.concurrent.atomic.AtomicInteger()
        val local = LocalStreamRelay.register("$base/limited?clen=$size&n=coalesce", refresh = {
            refreshes.incrementAndGet()
            Thread.sleep(150) // the other reader's piece fails meanwhile
            "$base/full?clen=$size&n=coalesce"
        })
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val a = pool.submit<Pair<Int, ByteArray>> { read(local, "bytes=1100000-1200000") }
            val b = pool.submit<Pair<Int, ByteArray>> { read(local, "bytes=2100000-2200000") }
            assertContentEquals(data.copyOfRange(1_100_000, 1_200_001), a.get().second)
            assertContentEquals(data.copyOfRange(2_100_000, 2_200_001), b.get().second)
        } finally { pool.shutdown() }
        assertEquals(1, refreshes.get(), "pieces that failed together must share one fresh link")
    }

    @Test
    fun sameLengthButAnotherFormatIsNotSplicedIn() {
        val local = LocalStreamRelay.register("$base/limited?itag=251&clen=$size&n=itag", refresh = { "$base/full?itag=140&clen=$size&n=itag" })
        val (_, body) = read(local, "bytes=0-")
        assertTrue(body.size < size, "equal length alone must not let another format continue the stream")
        assertContentEquals(data.copyOfRange(0, body.size), body)
    }

    @Test
    fun aRefusalIsReportedWithItsCategory() {
        val local = LocalStreamRelay.register("$base/limited?clen=$size&n=report")
        read(local, "bytes=1100000-1100100")
        assertEquals(com.auralis.music.data.network.FailureCategory.MEDIA_FORBIDDEN, LocalStreamRelay.failureFor(local)?.category)
    }

    @Test
    fun neverSplicesInADifferentFile() {
        val local = LocalStreamRelay.register("$base/limited?clen=$size", refresh = { "$base/full?clen=999" })
        val (_, body) = read(local, "bytes=0-")
        assertTrue(body.size < size, "a refresh to another file must end the stream, not continue it")
        assertContentEquals(data.copyOfRange(0, body.size), body)
    }
}
