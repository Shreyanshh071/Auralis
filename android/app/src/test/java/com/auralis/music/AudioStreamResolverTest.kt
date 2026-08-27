package com.auralis.music

import com.auralis.music.data.network.AudioStreamResolver
import kotlinx.coroutines.runBlocking
import org.junit.Test

class AudioStreamResolverTest {

    @Test
    fun testDirectStreamResolution() = runBlocking {
        val tracks = listOf(
            Triple("1lyu1KKwC74", "Bitter Sweet Symphony", "The Verve"),
            Triple("4NRXx6U8ABQ", "Blinding Lights", "The Weeknd"),
            Triple("fJ9rUzIMcZQ", "Bohemian Rhapsody", "Queen"),
            Triple("JGwWNGJdvx8", "Shape of You", "Ed Sheeran"),
            Triple("34Na4j8AVgA", "Starboy", "The Weeknd"),
            Triple("opwZ_PJ-F_E", "Choo Lo", "The Local Train"),
            Triple("EwLgGHAxTa8", "Raanjhanaa", "A.R. Rahman"),
            Triple("H5v3kku4y6Q", "As It Was", "Harry Styles")
        )

        for ((id, title, artist) in tracks) {
            AudioStreamResolver.clearCache()
            println("==================================================")
            println("PHASE 1 COLD RESOLUTION TEST: '$title' by '$artist' ($id)")
            val t0 = System.currentTimeMillis()
            val stream = AudioStreamResolver.resolveAudioStream(id, title, artist)
            val coldMs = System.currentTimeMillis() - t0
            println("COLD RESULT: resolved in ${coldMs}ms")
            println("URL: ${stream?.take(80)}...")
            assert(!stream.isNullOrBlank())

            // Warm / Prefetched Test (from cache)
            val tWarm0 = System.currentTimeMillis()
            val warmStream = AudioStreamResolver.resolveAudioStream(id, title, artist)
            val warmMs = System.currentTimeMillis() - tWarm0
            println("WARM / PREFETCHED RESULT: resolved in ${warmMs}ms")
            assert(!warmStream.isNullOrBlank())
            println("==================================================")
        }
    }
}
