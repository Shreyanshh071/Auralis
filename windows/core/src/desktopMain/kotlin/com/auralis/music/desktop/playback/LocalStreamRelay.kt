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
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong
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
    private const val CHUNK_BYTES = 32L * 1024L
    private const val CACHE_BLOCKS = 256 // 8 MiB per retained stream, at most four streams.
    private const val PREFETCH_BLOCKS = 64 // 2 MiB ahead; foreground seeks never queue behind this.
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
        val generation = AtomicLong()
        val blocks = object : LinkedHashMap<Long, ByteArray>(CACHE_BLOCKS, .75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>?) = size > CACHE_BLOCKS
        }
        val pending = ConcurrentHashMap<Long, CompletableFuture<ByteArray?>>()
        val refreshLock = Any()
    }

    private val streams = ConcurrentHashMap<String, Stream>()
    private val registrationOrder = ConcurrentLinkedQueue<String>()
    private val prefetcher = Executors.newFixedThreadPool(2) { r -> Thread(r, "stream-prefetch").apply { isDaemon = true } }

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
        streams[token] = Stream(upstreamUrl, refresh, length = clenOf(upstreamUrl))
        registrationOrder.add(token)
        while (streams.size > 4) registrationOrder.poll()?.let { streams.remove(it)?.generation?.incrementAndGet() }
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

        val generation = stream.generation.incrementAndGet()
        val out = exchange.responseBody
        var position = start
        var prefetchStarted = false
        try {
            while (position <= end) {
                val blockStart = position / CHUNK_BYTES * CHUNK_BYTES
                val blockEnd = minOf(blockStart + CHUNK_BYTES - 1, total - 1)
                val bytes = cachedBlock(stream, blockStart, blockEnd)
                    ?: refreshAndFetch(stream, blockStart, blockEnd)?.also { storeBlock(stream, blockStart, it) }
                    ?: throw IOException("YouTube kept refusing bytes $blockStart-$blockEnd")
                val offset = (position - blockStart).toInt()
                val count = minOf((bytes.size - offset).toLong(), end - position + 1).toInt()
                out.write(bytes, offset, count)
                out.flush()
                position += count
                if (!prefetchStarted) {
                    prefetchStarted = true
                    prefetch(stream, blockStart + CHUNK_BYTES, generation)
                }
            }
        } catch (e: IOException) {
            // libVLC closes the connection when it seeks or stops; that is not an error.
            if (e.message?.startsWith("YouTube") == true) Log.w(TAG, e.message!!)
        }
    }

    private fun storeBlock(stream: Stream, start: Long, bytes: ByteArray) {
        synchronized(stream.blocks) { stream.blocks[start] = bytes }
    }

    /** One fetch per aligned block, shared by playback, prefetch and overlapping seek requests. */
    private fun cachedBlock(stream: Stream, start: Long, end: Long): ByteArray? {
        synchronized(stream.blocks) { stream.blocks[start] }?.let { return it }
        val result = CompletableFuture<ByteArray?>()
        stream.pending.putIfAbsent(start, result)?.let { return it.get() }
        try {
            // A completed download may have arrived between the first lookup and claiming it.
            val bytes = synchronized(stream.blocks) { stream.blocks[start] } ?: fetchChunk(stream, start, end)
            if (bytes != null) storeBlock(stream, start, bytes)
            result.complete(bytes)
            return bytes
        } catch (e: Exception) {
            result.completeExceptionally(e)
            throw e
        } finally { stream.pending.remove(start, result) }
    }

    private fun prefetch(stream: Stream, from: Long, generation: Long) {
        prefetcher.execute {
            runCatching {
                var position = from
                repeat(PREFETCH_BLOCKS) {
                    if (stream.generation.get() != generation || position >= stream.length) return@runCatching
                    val end = minOf(position + CHUNK_BYTES - 1, stream.length - 1)
                    if (cachedBlock(stream, position, end) == null) return@runCatching
                    position += CHUNK_BYTES
                }
            }
        }
    }

    /** A fresh URL for the song, then the same piece from it; null when that fails too. */
    private fun refreshAndFetch(stream: Stream, from: Long, to: Long): ByteArray? {
        synchronized(stream.refreshLock) {
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
    }

    /** Bytes [from]..[to] of the stream, retrying a refused or failed request. */
    private fun fetchChunk(stream: Stream, from: Long, to: Long): ByteArray? {
        repeat(CHUNK_ATTEMPTS) { attempt ->
            try {
                val request = Request.Builder()
                    .url(stream.url)
                    .header("User-Agent", USER_AGENT)
                    .header("Range", "bytes=$from-$to")
                    .header("Accept-Encoding", "identity")
                    .build()
                client.newCall(request).execute().use { response ->
                    if (response.code == 206 || response.code == 200) {
                        response.header("Content-Type")?.let { stream.contentType = it }
                        // Never accept a whole-file 200 response as a nonzero range: that
                        // would feed the decoder bytes from the wrong timestamp.
                        val rangeStart = response.header("Content-Range")?.substringAfter("bytes ")?.substringBefore('-')?.toLongOrNull()
                        val validRange = if (response.code == 206) rangeStart == from else from == 0L
                        if (validRange) {
                            val expected = (to - from + 1).toInt()
                            val body = response.body?.byteStream()?.readNBytes(expected)
                            if (body?.size == expected) return body
                        }
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
