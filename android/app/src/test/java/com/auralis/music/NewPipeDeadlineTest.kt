package com.auralis.music

import com.auralis.music.data.network.NewPipeDownloader
import okhttp3.OkHttpClient
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class NewPipeDeadlineTest {
    @Test fun `InnerTube route preserves the request on working YouTube host`() {
        var received: okhttp3.Request? = null
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            received = chain.request()
            okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1)
                .code(200).message("OK").body("{}".toResponseBody()).build()
        }.build()
        val downloader = NewPipeDownloader(client)
        downloader.post("https://youtubei.googleapis.com/youtubei/v1/player?prettyPrint=false",
            mapOf("Content-Type" to listOf("application/json")), "{\"videoId\":\"abcdefghijk\"}".toByteArray())
        assertEquals("www.youtube.com", received!!.url.host)
        assertEquals("/youtubei/v1/player", received!!.url.encodedPath)
        assertEquals("prettyPrint=false", received!!.url.encodedQuery)
        assertEquals("POST", received!!.method)
        assertEquals("application/json", received!!.header("Content-Type"))
        val body = okio.Buffer()
        received!!.body!!.writeTo(body)
        assertEquals("{\"videoId\":\"abcdefghijk\"}", body.readUtf8())
        downloader.get("https://example.com/audio")
        assertEquals("example.com", received!!.url.host)
    }

    private fun server(bodyStalls: Boolean, block: (String) -> Unit) {
        ServerSocket(0).use { server ->
            val worker = thread(isDaemon = true) {
                try {
                    server.accept().use { socket ->
                        val input = socket.getInputStream().bufferedReader()
                        while (!input.readLine().isNullOrEmpty()) Unit
                        if (bodyStalls) {
                            socket.getOutputStream().write(
                                "HTTP/1.1 200 OK\r\nContent-Length: 100\r\nConnection: close\r\n\r\nx".toByteArray())
                            socket.getOutputStream().flush()
                        }
                        Thread.sleep(2000)
                    }
                } catch (_: Exception) { }
            }
            try { block("http://127.0.0.1:${server.localPort}/audio") }
            finally { worker.interrupt(); worker.join(1000) }
        }
    }

    private fun assertBounded(bodyStalls: Boolean) = server(bodyStalls) { url ->
        val downloader = NewPipeDownloader(OkHttpClient.Builder()
            .readTimeout(10, TimeUnit.SECONDS).build())
        val started = System.nanoTime()
        var failed = false
        try {
            NewPipeDownloader.withRequestBudget(200) { downloader.get(url) }
        } catch (_: IOException) { failed = true }
        val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        assertTrue("Stalled request must fail", failed)
        assertTrue("Request escaped budget: ${elapsed}ms", elapsed < 1000)
    }

    @Test fun `deadline stops blocked response headers`() = assertBounded(false)
    @Test fun `deadline stops blocked response body`() = assertBounded(true)

    @Test fun `requests share a deadline and scope restores after failure`() {
        val downloader = NewPipeDownloader(OkHttpClient())
        val next = "https://www.youtube.com/youtubei/v1/next"
        var failed = false
        try {
            NewPipeDownloader.withRequestBudget(40) {
                assertEquals(200, downloader.get(next).responseCode())
                Thread.sleep(60)
                downloader.get(next)
            }
        } catch (_: IOException) { failed = true }
        assertTrue("Second request must use remaining extraction budget", failed)
        assertEquals(200, downloader.get(next).responseCode())
    }
}
