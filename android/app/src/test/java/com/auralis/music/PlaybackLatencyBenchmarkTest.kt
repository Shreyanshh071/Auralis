package com.auralis.music

import com.auralis.music.data.network.AudioStreamResolver
import com.auralis.music.data.network.NetworkClientProvider
import com.auralis.music.data.network.NewPipeDownloader
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.schabi.newpipe.extractor.NewPipe

class PlaybackLatencyBenchmarkTest {

    @Before
    fun setup() {
        NewPipe.init(NewPipeDownloader.instance)
        AudioStreamResolver.clearCache()
    }

    @Test
    fun testSoftcoreAndAttentionResolutionAndCdnPlayback() = runBlocking {
        val testTracks = listOf(
            Triple("NIma5XOxBq0", "Softcore", "The Neighbourhood"),
            Triple("vxUBYHz_q1I", "Attention", "Charlie Puth")
        )

        for ((id, title, artist) in testTracks) {
            println("==========================================================================")
            println("TESTING COLD & WARM RESOLUTION: '$title' by '$artist' ($id)")
            println("==========================================================================")

            // 1. Cold resolution
            val t0Cold = System.currentTimeMillis()
            val coldStream = AudioStreamResolver.resolveAudioStream(id, title, artist)
            val tColdMs = System.currentTimeMillis() - t0Cold
            println("  Cold Resolution: ${tColdMs}ms")
            println("  Cold Stream URL: ${coldStream?.take(80)}...")

            assertNotNull("Stream URL must not be null for $title", coldStream)
            assertTrue("Stream URL must be a valid HTTP URL", coldStream?.startsWith("http") == true)

            // Verify CDN stream is 100% playable (HTTP 206 Partial Content)
            val req = Request.Builder()
                .url(coldStream!!)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .header("Range", "bytes=0-1024")
                .build()

            val res = NetworkClientProvider.okHttpClient.newCall(req).execute()
            println("  CDN HTTP Status: ${res.code} ${res.message}, Content-Length: ${res.header("Content-Length")}")
            assertEquals("CDN must return 206 Partial Content for $title", 206, res.code)

            // 2. Warm resolution (exact ID)
            val t0Warm = System.currentTimeMillis()
            val warmStream = AudioStreamResolver.resolveAudioStream(id, title, artist)
            val tWarmMs = System.currentTimeMillis() - t0Warm
            println("  Warm Resolution (exact ID): ${tWarmMs}ms")
            assertEquals("Warm stream must match cold stream", coldStream, warmStream)
            assertTrue("Warm resolution must be instant (< 50ms)", tWarmMs < 50)

            // 3. Fingerprint resolution (different ID, same title & artist)
            val dummyDifferentId = "alt_$id"
            val t0Fp = System.currentTimeMillis()
            val fpStream = AudioStreamResolver.resolveAudioStream(dummyDifferentId, title, artist)
            val tFpMs = System.currentTimeMillis() - t0Fp
            println("  Fingerprint Resolution (different ID): ${tFpMs}ms")
            assertEquals("Fingerprint stream must match cached stream", coldStream, fpStream)
            assertTrue("Fingerprint resolution must be instant (< 50ms)", tFpMs < 50)
        }
    }
}
