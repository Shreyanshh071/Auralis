package com.auralis.music

import com.auralis.music.data.network.AudioStreamResolver
import kotlinx.coroutines.runBlocking
import org.junit.Test

class AudioStreamResolverTest {

    @Test
    fun testDirectStreamResolution() = runBlocking {
        // Fix key in AudioStreamResolver and test
        val stream = AudioStreamResolver.resolveAudioStream("4NRXx6U8ABQ", "Blinding Lights", "The Weeknd")
        println("Resolved direct 320kbps master stream: $stream")
        assert(stream != null && stream.contains(".mp4"))
    }
}
