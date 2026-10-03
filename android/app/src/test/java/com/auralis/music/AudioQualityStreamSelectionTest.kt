package com.auralis.music

import com.auralis.music.data.network.AudioStreamResolver
import com.auralis.music.domain.model.AudioQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType

// NewPipe reports averageBitrate in kbps (itag 251 = 160, itag 140 = 128).
class AudioQualityStreamSelectionTest {

    private fun createStream(
        url: String,
        kbps: Int,
        format: MediaFormat = MediaFormat.OPUS,
        trackType: AudioTrackType? = null,
    ): AudioStream {
        return AudioStream.Builder()
            .setId("stream-$kbps-$trackType")
            .setContent(url, false)
            .setDeliveryMethod(org.schabi.newpipe.extractor.stream.DeliveryMethod.PROGRESSIVE_HTTP)
            .setMediaFormat(format)
            .setAverageBitrate(kbps)
            .apply { if (trackType != null) setAudioTrackType(trackType) }
            .build()
    }

    private val youtubeLadder = listOf(
        createStream("https://googlevideo.com/opus_50", 50),
        createStream("https://googlevideo.com/opus_70", 70),
        createStream("https://googlevideo.com/aac_128", 128, MediaFormat.M4A),
        createStream("https://googlevideo.com/opus_160", 160),
    )

    @Test
    fun testQualitySelection_LowBitratePicksLowest() {
        val selected = AudioStreamResolver.selectStreamForQuality(youtubeLadder, AudioQuality.LOW, null)
        assertNotNull(selected)
        assertEquals("https://googlevideo.com/opus_50", selected?.content)
    }

    @Test
    fun testQualitySelection_HighBitratePicksOpus160() {
        val selected = AudioStreamResolver.selectStreamForQuality(youtubeLadder, AudioQuality.HIGH, null)
        assertEquals("https://googlevideo.com/opus_160", selected?.content)
    }

    @Test
    fun testQualitySelection_AutoPicksOpus160() {
        val selected = AudioStreamResolver.selectStreamForQuality(youtubeLadder, AudioQuality.AUTO, null)
        assertEquals("https://googlevideo.com/opus_160", selected?.content)
    }

    @Test
    fun testQualitySelection_StandardPicksAac128() {
        val selected = AudioStreamResolver.selectStreamForQuality(youtubeLadder, AudioQuality.STANDARD, null)
        assertEquals("https://googlevideo.com/aac_128", selected?.content)
    }

    @Test
    fun testQualitySelection_DubbedTrackIgnoredWhenOriginalExists() {
        val streams = listOf(
            createStream("https://googlevideo.com/dubbed_160", 160, trackType = AudioTrackType.DUBBED),
            createStream("https://googlevideo.com/original_160", 160, trackType = AudioTrackType.ORIGINAL),
            createStream("https://googlevideo.com/descriptive_160", 160, trackType = AudioTrackType.DESCRIPTIVE),
        )
        val selected = AudioStreamResolver.selectStreamForQuality(streams, AudioQuality.AUTO, null)
        assertEquals("https://googlevideo.com/original_160", selected?.content)
    }

    @Test
    fun testQualitySelection_OnlyDubbedTracksStillPlays() {
        val streams = listOf(
            createStream("https://googlevideo.com/dubbed_128", 128, MediaFormat.M4A, AudioTrackType.DUBBED),
            createStream("https://googlevideo.com/dubbed_160", 160, trackType = AudioTrackType.DUBBED),
        )
        val selected = AudioStreamResolver.selectStreamForQuality(streams, AudioQuality.AUTO, null)
        assertEquals("https://googlevideo.com/dubbed_160", selected?.content)
    }

    @Test
    fun testQualitySelection_BlacklistedHostIgnored() {
        AudioStreamResolver.blacklistHost("failing-cdn.googlevideo.com")

        val streams = listOf(
            createStream("https://failing-cdn.googlevideo.com/stream_160", 160),
            createStream("https://working-cdn.googlevideo.com/stream_128", 128)
        )

        val selected = AudioStreamResolver.selectStreamForQuality(streams, AudioQuality.HIGH, null)
        assertNotNull(selected)
        assertEquals("https://working-cdn.googlevideo.com/stream_128", selected?.content)
    }

    @Test
    fun testQualitySelection_EmptyStreamListReturnsNull() {
        val emptyStreams = emptyList<AudioStream>()
        val selected = AudioStreamResolver.selectStreamForQuality(emptyStreams, AudioQuality.AUTO, null)
        assertNull(selected)
    }
}
