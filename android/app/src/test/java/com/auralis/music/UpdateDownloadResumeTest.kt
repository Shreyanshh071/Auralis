package com.auralis.music

import android.content.Context
import com.auralis.music.data.network.UpdateChecker
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.util.Collections
import kotlin.concurrent.thread
import kotlin.random.Random

/** Pause = cancelling the download; the next call must continue from the partial file. */
class UpdateDownloadResumeTest {

    private val payload = Random(7).nextBytes(600 * 1024)
    private val rangeHeaders = Collections.synchronizedList(mutableListOf<String?>())
    private lateinit var server: ServerSocket
    private lateinit var cacheDir: File
    private lateinit var context: Context

    @Before
    fun setUp() {
        cacheDir = kotlin.io.path.createTempDirectory("upd").toFile()
        context = mockk { every { cacheDir } returns this@UpdateDownloadResumeTest.cacheDir }
        server = ServerSocket(0)
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                thread(isDaemon = true) {
                    socket.use { s ->
                        val reader = s.getInputStream().bufferedReader()
                        var range: String? = null
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                            if (line.startsWith("Range:", ignoreCase = true)) range = line.substringAfter(':').trim()
                        }
                        rangeHeaders += range
                        val from = range?.removePrefix("bytes=")?.substringBefore('-')?.toIntOrNull() ?: 0
                        val out = s.getOutputStream()
                        val status = if (range != null) "206 Partial Content" else "200 OK"
                        out.write("HTTP/1.1 $status\r\nContent-Length: ${payload.size - from}\r\nConnection: close\r\n\r\n".toByteArray())
                        // Slow, so a pause lands mid-download.
                        var i = from
                        runCatching {
                            while (i < payload.size) {
                                val n = minOf(16 * 1024, payload.size - i)
                                out.write(payload, i, n); out.flush(); i += n
                                Thread.sleep(15)
                            }
                        }
                    }
                }
            }
        }
    }

    @After
    fun tearDown() {
        server.close()
        cacheDir.deleteRecursively()
    }

    @Test
    fun pausedDownloadResumesFromPartialFile() = runBlocking {
        val url = "http://127.0.0.1:${server.localPort}/a.apk"
        val reached = CompletableDeferred<Unit>()
        val first = async(Dispatchers.IO) {
            UpdateChecker.downloadApk(context, url, "9.9.9") { done, _ ->
                if (done >= 200 * 1024) reached.complete(Unit)
            }
        }
        withTimeout(10_000) { reached.await() }
        first.cancel() // Pause
        runCatching { first.await() }

        val part = File(cacheDir, "updates/Auralis-v9.9.9.apk.part")
        assertTrue("partial file kept", part.exists())
        val partial = part.length()
        assertTrue(partial in (200 * 1024L) until payload.size.toLong())

        var lastTotal = 0L
        val apk = withTimeout(20_000) {
            UpdateChecker.downloadApk(context, url, "9.9.9") { _, total -> lastTotal = total }
        }
        assertEquals("bytes=$partial-", rangeHeaders.last())
        assertEquals(payload.size.toLong(), lastTotal)
        assertArrayEquals(payload, apk.readBytes())
        assertFalse(part.exists())
    }
}
