package com.auralis.music

import com.auralis.music.data.network.AudioStreamResolver
import com.auralis.music.domain.model.AudioQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.stream.AudioStream

class AudioQualityStreamSelectionTest {

    private fun createStream(url: String, bitrate: Int, format: MediaFormat = MediaFormat.OPUS): AudioStream {
        return AudioStream.Builder()
            .setId("stream-$bitrate")
            .setContent(url, false)
            .setDeliveryMethod(org.schabi.newpipe.extractor.stream.DeliveryMethod.PROGRESSIVE_HTTP)
            .setMediaFormat(format)
            .setAverageBitrate(bitrate)
            .build()
    }

    @Test
    fun testQualitySelection_LowBitratePicksLowest() {
        val streams = listOf(
            createStream("https://googlevideo.com/stream_48k", 48_000),
            createStream("https://googlevideo.com/stream_128k", 128_000),
            createStream("https://googlevideo.com/stream_160k", 160_000)
        )

        val selected = AudioStreamResolver.selectStreamForQuality(streams, AudioQuality.LOW, null)
        assertNotNull(selected)
        assertEquals(48_000, selected?.averageBitrate)
        assertEquals("https://googlevideo.com/stream_48k", selected?.content)
    }

    @Test
    fun testQualitySelection_HighBitratePicksHighest() {
        val streams = listOf(
            createStream("https://googlevideo.com/stream_48k", 48_000),
            createStream("https://googlevideo.com/stream_128k", 128_000),
            createStream("https://googlevideo.com/stream_160k", 160_000)
        )

        val selected = AudioStreamResolver.selectStreamForQuality(streams, AudioQuality.HIGH, null)
        assertNotNull(selected)
        assertEquals(160_000, selected?.averageBitrate)
        assertEquals("https://googlevideo.com/stream_160k", selected?.content)
    }

    @Test
    fun testQualitySelection_StandardBitratePicksClosestTo128k() {
        val streams = listOf(
            createStream("https://googlevideo.com/stream_50k", 50_000),
            createStream("https://googlevideo.com/stream_130k", 130_000),
            createStream("https://googlevideo.com/stream_160k", 160_000)
        )

        val selected = AudioStreamResolver.selectStreamForQuality(streams, AudioQuality.STANDARD, null)
        assertNotNull(selected)
        assertEquals(130_000, selected?.averageBitrate)
        assertEquals("https://googlevideo.com/stream_130k", selected?.content)
    }

    @Test
    fun testQualitySelection_BlacklistedHostIgnored() {
        AudioStreamResolver.blacklistHost("failing-cdn.googlevideo.com")

        val streams = listOf(
            createStream("https://failing-cdn.googlevideo.com/stream_160k", 160_000),
            createStream("https://working-cdn.googlevideo.com/stream_128k", 128_000)
        )

        val selected = AudioStreamResolver.selectStreamForQuality(streams, AudioQuality.HIGH, null)
        assertNotNull(selected)
        assertEquals("https://working-cdn.googlevideo.com/stream_128k", selected?.content)
    }

    @Test
    fun testQualitySelection_EmptyStreamListReturnsNull() {
        val emptyStreams = emptyList<AudioStream>()
        val selected = AudioStreamResolver.selectStreamForQuality(emptyStreams, AudioQuality.AUTO, null)
        assertNull(selected)
    }
}
