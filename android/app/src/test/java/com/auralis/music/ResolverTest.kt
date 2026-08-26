package com.auralis.music

import com.auralis.music.data.network.AudioStreamResolver
import kotlinx.coroutines.runBlocking
import org.junit.Test

class ResolverTest {

    @Test
    fun testCacheAndSpeed() = runBlocking {
        println("=== TESTING FIRST RESOLUTION ===")
        val start1 = System.currentTimeMillis()
        val stream1 = AudioStreamResolver.resolveAudioStream("OJ62RzJkYUo", "Where Is My Mind", "Pixies")
        val elapsed1 = System.currentTimeMillis() - start1
        println("FIRST RESOLUTION in ${elapsed1}ms: $stream1")

        println("=== TESTING CACHE HIT RESOLUTION ===")
        val start2 = System.currentTimeMillis()
        val stream2 = AudioStreamResolver.resolveAudioStream("OJ62RzJkYUo", "Where Is My Mind", "Pixies")
        val elapsed2 = System.currentTimeMillis() - start2
        println("CACHE HIT RESOLUTION in ${elapsed2}ms: $stream2")

        org.junit.Assert.assertEquals(stream1, stream2)
        org.junit.Assert.assertTrue("Cache hit must be < 20ms", elapsed2 < 20)
    }
}
