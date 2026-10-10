package com.auralis.music.desktop.playback

import com.auralis.music.util.Log
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * A loopback HTTP server between libVLC and YouTube's stream servers.
 *
 * libVLC asks for "everything from byte N" in one request. YouTube intermittently refuses such
 * open-ended or large requests (HTTP 403) on stream URLs it otherwise serves, while requests for
 * a few hundred kilobytes succeed. The relay answers libVLC's request itself and fetches the
 * stream in [CHUNK_BYTES] pieces, retrying a refused piece. Some URLs only ever serve their first
 * megabyte; when a piece stays refused the relay gets a fresh URL for the song (up to
 * [MAX_REFRESHES] times) and carries on from the same byte, so libVLC doesn't notice. Seeking still
 * works: libVLC sends a new ranged request and the relay starts from there. Only 127.0.0.1 can
 * reach it.
 */
object LocalStreamRelay {
    private const val TAG = "LocalStreamRelay"
    private const val CHUNK_BYTES = 512L * 1024L
    private const val CHUNK_ATTEMPTS = 2
    private const val MAX_REFRESHES = 3
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

    private class Stream(
        @Volatile var url: String,
        /** A fresh URL for the same song, or null when there is none. */
        val refresh: (() -> String?)?,
        @Volatile var length: Long = -1L,
        @Volatile var contentType: String? = null
    ) {
        @Volatile var refreshes = 0
    }

    private val streams = ConcurrentHashMap<String, Stream>()

    private val client: OkHttpClient by lazy {
        com.auralis.music.data.network.NetworkClientProvider.okHttpClient.newBuilder()
            .followRedirects(true)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    private val server: HttpServer by lazy {
        HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            executor = Executors.newCachedThreadPool { r -> Thread(r, "stream-relay").apply { isDaemon = true } }
            createContext("/s/") { exchange -> runCatching { serve(exchange) }; exchange.close() }
            start()
            Log.i(TAG, "Listening on 127.0.0.1:${address.port}")
        }
    }

    /** A local URL for libVLC that serves [upstreamUrl]; [refresh] fetches a new URL for the same song. */
    fun register(upstreamUrl: String, refresh: (() -> String?)? = null): String {
        val token = UUID.randomUUID().toString()
        // One song plays at a time; drop old entries so the map can't grow without bound.
        if (streams.size > 16) streams.clear()
        streams[token] = Stream(upstreamUrl, refresh, length = clenOf(upstreamUrl))
        return "http://127.0.0.1:${server.address.port}/s/$token"
    }

    private fun clenOf(url: String): Long =
        Regex("[?&]clen=([0-9]+)").find(url)?.groupValues?.get(1)?.toLongOrNull() ?: -1L

    private fun serve(exchange: HttpExchange) {
        val stream = streams[exchange.requestURI.path.substringAfter("/s/")]
        if (stream == null) {
            exchange.sendResponseHeaders(404, -1)
            return
        }
        if (stream.length < 0) stream.length = fetchLength(stream) ?: run {
            exchange.sendResponseHeaders(502, -1)
            return
        }
        val total = stream.length
        val rangeHeader = exchange.requestHeaders.getFirst("Range")
        val (start, end) = parseRange(rangeHeader, total) ?: run {
            exchange.responseHeaders.add("Content-Range", "bytes */$total")
            exchange.sendResponseHeaders(416, -1)
            return
        }
        exchange.responseHeaders.add("Accept-Ranges", "bytes")
        exchange.responseHeaders.add("Content-Type", stream.contentType ?: mimeOf(stream.url))
        if (rangeHeader != null) exchange.responseHeaders.add("Content-Range", "bytes $start-$end/$total")
        val bodyLength = end - start + 1
        if (exchange.requestMethod.equals("HEAD", ignoreCase = true)) {
            exchange.responseHeaders.add("Content-Length", bodyLength.toString())
            exchange.sendResponseHeaders(if (rangeHeader != null) 206 else 200, -1)
            return
        }
        exchange.sendResponseHeaders(if (rangeHeader != null) 206 else 200, bodyLength)

        val out = exchange.responseBody
        var position = start
        try {
            while (position <= end) {
                val chunkEnd = minOf(position + CHUNK_BYTES - 1, end)
                val bytes = fetchChunk(stream, position, chunkEnd)
                    ?: refreshAndFetch(stream, position, chunkEnd)
                    ?: throw IOException("YouTube kept refusing bytes $position-$chunkEnd")
                out.write(bytes)
                out.flush()
                position += bytes.size
            }
        } catch (e: IOException) {
            // libVLC closes the connection when it seeks or stops; that is not an error.
            if (e.message?.startsWith("YouTube") == true) Log.w(TAG, e.message!!)
        }
    }

    /** A fresh URL for the song, then the same piece from it; null when that fails too. */
    private fun refreshAndFetch(stream: Stream, from: Long, to: Long): ByteArray? {
        val refresh = stream.refresh ?: return null
        while (stream.refreshes < MAX_REFRESHES) {
            stream.refreshes++
            val fresh = refresh() ?: return null
            val freshLength = clenOf(fresh)
            // A different file (another format) would corrupt the stream mid-song.
            if (freshLength > 0 && stream.length > 0 && freshLength != stream.length) {
                Log.w(TAG, "Fresh URL is a different file ($freshLength vs ${stream.length} bytes); not switching")
                return null
            }
            Log.i(TAG, "Bytes $from-$to refused; switched to a fresh URL (${stream.refreshes}/$MAX_REFRESHES)")
            stream.url = fresh
            fetchChunk(stream, from, to)?.let { return it }
        }
        return null
    }

    /** Bytes [from]..[to] of the stream, retrying a refused or failed request. */
    private fun fetchChunk(stream: Stream, from: Long, to: Long): ByteArray? {
        repeat(CHUNK_ATTEMPTS) { attempt ->
            try {
                val request = Request.Builder()
                    .url(stream.url)
                    .header("User-Agent", USER_AGENT)
                    .header("Range", "bytes=$from-$to")
                    .build()
                client.newCall(request).execute().use { response ->
                    if (response.code == 206 || response.code == 200) {
                        response.header("Content-Type")?.let { stream.contentType = it }
                        val body = response.body?.bytes()
                        if (body != null && body.isNotEmpty()) return body
                    } else {
                        Log.w(TAG, "HTTP ${response.code} for bytes $from-$to (attempt ${attempt + 1})")
                    }
                }
            } catch (e: IOException) {
                Log.w(TAG, "Bytes $from-$to failed (attempt ${attempt + 1}): ${e.message}")
            }
            Thread.sleep(250L * (attempt + 1))
        }
        return null
    }

    /** The stream's total length from a two-byte request's Content-Range, when the URL has no clen. */
    private fun fetchLength(stream: Stream): Long? = try {
        val request = Request.Builder().url(stream.url).header("User-Agent", USER_AGENT).header("Range", "bytes=0-1").build()
        client.newCall(request).execute().use { response ->
            stream.contentType = response.header("Content-Type")
            response.header("Content-Range")?.substringAfter('/')?.toLongOrNull()
        }
    } catch (e: IOException) {
        null
    }

    /** "bytes=a-b", "bytes=a-" or "bytes=-n" against [total]; the whole stream without a header. */
    private fun parseRange(header: String?, total: Long): Pair<Long, Long>? {
        if (header == null) return 0L to total - 1
        val spec = header.substringAfter("bytes=", "").substringBefore(',').trim()
        val startText = spec.substringBefore('-')
        val endText = spec.substringAfter('-', "")
        val range = when {
            startText.isEmpty() -> {
                val suffix = endText.toLongOrNull() ?: return null
                (total - suffix).coerceAtLeast(0L) to total - 1
            }
            else -> {
                val start = startText.toLongOrNull() ?: return null
                val end = endText.toLongOrNull()?.coerceAtMost(total - 1) ?: (total - 1)
                start to end
            }
        }
        return range.takeIf { it.first in 0..it.second }
    }

    private fun mimeOf(url: String): String =
        Regex("[?&]mime=([^&]+)").find(url)?.groupValues?.get(1)?.let { java.net.URLDecoder.decode(it, "UTF-8") } ?: "application/octet-stream"
}
