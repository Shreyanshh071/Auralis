package com.auralis.music

import com.auralis.music.data.network.AudioStreamResolver
import com.auralis.music.data.network.InnerTubePlayerResolver
import com.auralis.music.data.network.PlayerJsCache
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InnerTubePlayerResolverTest {

    @Test
    fun testColdTracksResolutionTimings() = runBlocking {
        // Preload player JS cache once (just as the app does on startup)
        val tInit0 = System.currentTimeMillis()
        PlayerJsCache.ensurePlayerJsLoaded()
        val initDuration = System.currentTimeMillis() - tInit0
        println("PlayerJsCache initial load/warmup: ${initDuration}ms (sts=${PlayerJsCache.getSignatureTimestamp()})")

        val testTracks = listOf(
            Triple("opwZ_PJ-F_E", "Choo Lo", "The Local Train"), // Previously failed / 25.4s
            Triple("EwLgGHAxTa8", "Raanjhanaa", "A.R. Rahman"),
            Triple("1lyu1KKwC74", "Bitter Sweet Symphony", "The Verve"),
            Triple("34Na4j8AVgA", "Starboy", "The Weeknd"),
            Triple("H5v3kku4y6Q", "As It Was", "Harry Styles")
        )

        println("==========================================================================")
        println("STEP 3 BENCHMARK: DIRECT INNERTUBE /PLAYER STREAM RESOLUTION TIMINGS")
        println("==========================================================================")

        for ((id, title, artist) in testTracks) {
            val t0 = System.currentTimeMillis()
            val streamUrl = InnerTubePlayerResolver.resolveStream(id)
            val durationMs = System.currentTimeMillis() - t0

            println("Track: '$title' ($id)")
            println("Resolution Time: ${durationMs}ms")
            println("Resolved URL: ${streamUrl?.take(80)}...")
            println("--------------------------------------------------------------------------")

            assertNotNull("Stream URL must not be null for $title", streamUrl)
            assertTrue("Stream URL must be valid HTTP URL", streamUrl?.startsWith("http") == true)
            assertTrue("Direct resolution must be under 1500ms once cached", durationMs < 1500)
        }
    }

    @Test
    fun testAudioStreamResolverLayeredFallback() = runBlocking {
        val id = "opwZ_PJ-F_E"
        val title = "Choo Lo"
        val artist = "The Local Train"

        // 1. Cold resolution
        val t0Cold = System.currentTimeMillis()
        val coldUrl = AudioStreamResolver.resolveAudioStream(id, title, artist)
        val tColdMs = System.currentTimeMillis() - t0Cold
        println("AudioStreamResolver COLD '$title': ${tColdMs}ms")
        assertNotNull(coldUrl)

        // 2. Warm resolution (Memory Cache HIT)
        val t0Warm = System.currentTimeMillis()
        val warmUrl = AudioStreamResolver.resolveAudioStream(id, title, artist)
        val tWarmMs = System.currentTimeMillis() - t0Warm
        println("AudioStreamResolver WARM '$title': ${tWarmMs}ms")
        assertNotNull(warmUrl)
        assertTrue("Warm cache hit must be instantaneous (<50ms)", tWarmMs < 50)
    }
}
