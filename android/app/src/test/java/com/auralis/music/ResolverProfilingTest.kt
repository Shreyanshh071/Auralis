package com.auralis.music

import com.auralis.music.data.network.NetworkClientProvider
import com.auralis.music.data.network.NewPipeDownloader
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request as NPRequest
import org.schabi.newpipe.extractor.downloader.Response as NPResponse
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamExtractor
import java.util.concurrent.ConcurrentHashMap

class ResolverProfilingTest {

    class FastDownloader(private val delegate: Downloader) : Downloader() {
        private val cachedResponses = ConcurrentHashMap<String, Pair<Long, NPResponse>>()

        override fun execute(request: NPRequest): NPResponse {
            val url = request.url()
            // 1. Bypass heavy 'next' endpoint (comments/recommendations)
            if (url.contains("/youtubei/v1/next")) {
                return NPResponse(
                    200,
                    "OK",
                    emptyMap(),
                    "{\"responseContext\":{},\"contents\":{\"twoColumnWatchNextResults\":{\"results\":{\"results\":{\"contents\":[]}}}}}",
                    url
                )
            }

            // 2. Cache visitor_id responses for 10 minutes to eliminate 3x redundant round-trips
            if (url.contains("/youtubei/v1/visitor_id")) {
                val cached = cachedResponses[url]
                if (cached != null && System.currentTimeMillis() - cached.first < 600_000L) {
                    return cached.second
                }
                val fresh = delegate.execute(request)
                cachedResponses[url] = System.currentTimeMillis() to fresh
                return fresh
            }

            return delegate.execute(request)
        }
    }

    @Test
    fun testFastNextAndVisitorIdCache() = runBlocking {
        val fastDownloader = FastDownloader(NewPipeDownloader.instance)
        NewPipe.init(fastDownloader)

        val testTracks = listOf(
            "opwZ_PJ-F_E" to "Choo Lo",
            "EwLgGHAxTa8" to "Raanjhanaa",
            "1lyu1KKwC74" to "Bitter Sweet Symphony"
        )

        for ((id, title) in testTracks) {
            println("==========================================================================")
            println("OPTIMIZED RESOLUTION FOR: $title ($id)")
            println("==========================================================================")
            val t0 = System.currentTimeMillis()
            val extractor = ServiceList.YouTube.getStreamExtractor("https://www.youtube.com/watch?v=$id") as YoutubeStreamExtractor
            extractor.fetchPage()
            val fetchMs = System.currentTimeMillis() - t0

            val audioStreams = extractor.audioStreams
            val bestAudio = audioStreams?.maxByOrNull { it.averageBitrate }
            val streamUrl = bestAudio?.content
            val totalMs = System.currentTimeMillis() - t0

            println("  fetchPage() Time:      ${fetchMs}ms")
            println("  Total Duration:        ${totalMs}ms")
            println("  Audio Streams Count:   ${audioStreams.size}")
            println("  Best Audio Bitrate:    ${bestAudio?.averageBitrate} kbps (${bestAudio?.format})")

            assertNotNull("Stream URL must not be null", streamUrl)

            // Verify stream is 100% playable on CDN (206 Partial Content)
            val req = Request.Builder()
                .url(streamUrl!!)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .header("Range", "bytes=0-1024")
                .build()

            val res = NetworkClientProvider.okHttpClient.newCall(req).execute()
            println("  CDN HTTP Status:       ${res.code} (${res.message}), Content-Length: ${res.header("Content-Length")}")
            assertEquals("CDN must return 206 Partial Content", 206, res.code)
        }
    }
}
