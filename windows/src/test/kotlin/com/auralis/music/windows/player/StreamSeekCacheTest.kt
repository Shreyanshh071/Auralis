package com.auralis.music.windows.player

import com.auralis.music.desktop.playback.LocalStreamRelay
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class StreamSeekCacheTest {
    @Test fun nativePlayerResumesAfterRandomSeeks() {
        if (System.getenv("AURALIS_VERIFY_SEEK_AUDIO") != "1") return
        val file = java.io.File("build/seek-fixture.webm")
        check(file.isFile)
        val bytes = file.readBytes()
        val executor = Executors.newCachedThreadPool()
        val upstream = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        upstream.executor = executor
        upstream.createContext("/audio") { exchange ->
            try {
                val range = exchange.requestHeaders.getFirst("Range").removePrefix("bytes=").split('-')
                val start = range[0].toInt(); val end = minOf(range[1].toInt(), bytes.lastIndex)
                exchange.responseHeaders.add("Content-Range", "bytes $start-$end/${bytes.size}")
                exchange.responseHeaders.add("Content-Type", "audio/webm")
                exchange.sendResponseHeaders(206, (end - start + 1).toLong())
                var offset = start
                while (offset <= end) {
                    val count = minOf(4096, end - offset + 1)
                    Thread.sleep(25) // 160 KiB/s origin, enough to expose whole-chunk waits.
                    exchange.responseBody.write(bytes, offset, count); exchange.responseBody.flush()
                    offset += count
                }
            } catch (_: java.io.IOException) {} finally { exchange.close() }
        }
        upstream.start()
        val vlcDir = java.io.File(System.getenv("LOCALAPPDATA"), "Auralis/app/resources/vlc")
        val audio = checkNotNull(com.auralis.music.desktop.playback.VlcAudioPlayer.createOrNull(vlcDir))
        try {
            audio.setVolume(0f)
            audio.play(LocalStreamRelay.register("http://127.0.0.1:${upstream.address.port}/audio?clen=${bytes.size}"))
            fun waitFor(timeoutMs: Long, condition: () -> Boolean) {
                val deadline = System.nanoTime() + timeoutMs * 1_000_000
                while (!condition() && System.nanoTime() < deadline) Thread.sleep(10)
                assertTrue(condition(), "Playback did not advance after seeking")
            }
            waitFor(15_000) { audio.positionMs.value > 500 }
            for (target in listOf(30_000L, 140_000L, 60_000L, 20_000L)) {
                val start = System.nanoTime()
                audio.seekTo(target)
                waitFor(5000) { audio.positionMs.value >= target + 250 }
                val elapsed = (System.nanoTime() - start) / 1_000_000
                println("Native muted seek to ${target / 1000}s advanced audio in ${elapsed}ms")
                assertTrue(elapsed < 2500, "Seek should resume promptly on a throttled source")
            }
        } finally { audio.release(); upstream.stop(0); executor.shutdownNow() }
    }

    @Test fun seeksUseSmallRequestsAndRepeatedOverlappingRangesUseCachedBytes() {
        val bytes = ByteArray(512 * 1024) { (it % 251).toByte() }
        val requests = CopyOnWriteArrayList<Pair<Int, Int>>()
        val executor = Executors.newCachedThreadPool()
        val upstream = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        upstream.executor = executor
        upstream.createContext("/audio") { exchange ->
            try {
                val range = exchange.requestHeaders.getFirst("Range").removePrefix("bytes=").split('-')
                val start = range[0].toInt()
                val end = minOf(range[1].toInt(), bytes.lastIndex)
                requests += start to end
                exchange.responseHeaders.add("Content-Range", "bytes $start-$end/${bytes.size}")
                exchange.responseHeaders.add("Content-Type", "audio/webm")
                exchange.sendResponseHeaders(206, (end - start + 1).toLong())
                // Throttled upstream: a large whole-chunk wait would delay a random seek.
                Thread.sleep(100)
                exchange.responseBody.write(bytes, start, end - start + 1)
            } finally { exchange.close() }
        }
        upstream.start()
        try {
            val local = LocalStreamRelay.register("http://127.0.0.1:${upstream.address.port}/audio?clen=${bytes.size}")
            fun request(start: Int, end: Int): ByteArray {
                val connection = URI(local).toURL().openConnection() as HttpURLConnection
                connection.setRequestProperty("Range", "bytes=$start-$end")
                connection.connectTimeout = 3000; connection.readTimeout = 3000
                try {
                    assertEquals(206, connection.responseCode)
                    assertEquals("bytes $start-$end/${bytes.size}", connection.getHeaderField("Content-Range"))
                    return connection.inputStream.use { it.readBytes() }
                } finally { connection.disconnect() }
            }
            val start = 170_000
            assertContentEquals(bytes.copyOfRange(start, start + 2048), request(start, start + 2047))
            val blockStart = start / 32768 * 32768
            val fetched = requests.count { it.first == blockStart }
            assertEquals(1, fetched)
            val cachedStart = System.nanoTime()
            assertContentEquals(bytes.copyOfRange(start + 100, start + 1100), request(start + 100, start + 1099))
            println("Cached overlapping seek: ${(System.nanoTime() - cachedStart) / 1_000_000}ms")
            assertEquals(fetched, requests.count { it.first == blockStart }, "Seeking within downloaded audio must not fetch it again")
            assertTrue(requests.all { it.second - it.first + 1 <= 32768 }, "Fresh seeks should not wait for 512 KiB chunks")
            val cross = 196_600
            assertContentEquals(bytes.copyOfRange(cross, cross + 4000), request(cross, cross + 3999))
            assertContentEquals(bytes.copyOfRange(bytes.size - 10, bytes.size), request(bytes.size - 10, bytes.size - 1))
        } finally { upstream.stop(0); executor.shutdownNow() }
    }
}
