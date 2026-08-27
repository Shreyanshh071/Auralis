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

class GooglevideoCdnTest {

    @Test
    fun testAllFiveTracksWithNewPipe() = runBlocking {
        NewPipe.init(NewPipeDownloader.instance)

        val testTracks = listOf(
            Triple("opwZ_PJ-F_E", "Choo Lo", "The Local Train"),
            Triple("EwLgGHAxTa8", "Raanjhanaa", "A.R. Rahman"),
            Triple("1lyu1KKwC74", "Bitter Sweet Symphony", "The Verve"),
            Triple("34Na4j8AVgA", "Starboy", "The Weeknd"),
            Triple("H5v3kku4y6Q", "As It Was", "Harry Styles")
        )

        for ((id, title, artist) in testTracks) {
            val t0 = System.currentTimeMillis()
            val extractor = ServiceList.YouTube.getStreamExtractor("https://www.youtube.com/watch?v=$id")
            extractor.fetchPage()
            val bestAudio = extractor.audioStreams?.maxByOrNull { it.averageBitrate }
            val streamUrl = bestAudio?.content
            val resolveMs = System.currentTimeMillis() - t0

            assertNotNull("Stream URL must not be null for $title", streamUrl)
            println("Track: '$title' ($id) resolved in ${resolveMs}ms")
            println("  URL: ${streamUrl?.take(80)}...")

            val req = Request.Builder()
                .url(streamUrl!!)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .header("Range", "bytes=0-1024")
                .build()

            val res = NetworkClientProvider.okHttpClient.newCall(req).execute()
            println("  CDN HTTP Status: ${res.code} (${res.message}), Content-Length: ${res.header("Content-Length")}")
            assertEquals("CDN must return 206 Partial Content", 206, res.code)
            println("--------------------------------------------------------------------------")
        }
    }
}
