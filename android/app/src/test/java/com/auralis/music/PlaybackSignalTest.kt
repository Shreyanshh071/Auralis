package com.auralis.music

import com.auralis.music.data.service.PlaybackSignals
import com.auralis.music.data.service.SignalSource
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackSignalTest {
    private val youtube = "https://rr1---sn-abc.googlevideo.com/videoplayback?expire=1&itag=251&clen=4000000&mime=audio%2Fwebm"
    private val saavn = "https://aac.saavncdn.com/123/abc_320.mp4"

    @Test fun `the stream url names where the audio came from`() {
        assertEquals(SignalSource.YOUTUBE, PlaybackSignals.sourceOf(youtube))
        assertEquals(SignalSource.JIOSAAVN, PlaybackSignals.sourceOf(saavn))
        assertEquals(SignalSource.DOWNLOAD, PlaybackSignals.sourceOf("file:///data/song.m4a"))
        assertEquals(SignalSource.OTHER, PlaybackSignals.sourceOf("https://example.com/a.mp3"))
    }

    @Test fun `bitrate is the real file size over its length`() {
        assertEquals(160, PlaybackSignals.bitrateOf(youtube, durationMs = 200_000L, fileBytes = null, reportedBitsPerSecond = -1))
        assertEquals(251, PlaybackSignals.itagOf(youtube))
    }

    @Test fun `a downloaded file uses its size on disk`() {
        assertEquals(320, PlaybackSignals.bitrateOf("file:///song.m4a", 100_000L, 4_000_000L, -1))
    }

    @Test fun `the container's figure is used when there is no size`() {
        assertEquals(129, PlaybackSignals.bitrateOf("https://x.googlevideo.com/videoplayback", 100_000L, null, 129_000))
    }

    @Test fun `JioSaavn's rendition comes from its file name, last`() {
        assertEquals(320, PlaybackSignals.bitrateOf(saavn, 100_000L, null, -1))
        assertEquals(318, PlaybackSignals.bitrateOf(saavn, 100_000L, null, 318_000))
    }

    @Test fun `the visitor session is read from YouTube's service worker data`() {
        val row = (0..13).joinToString(",") { if (it == 13) "\"visitor\"" else "null" }
        assertEquals("visitor", com.auralis.music.data.network.InnerTubePlayerResolver.parseVisitorData(")]}'\n[[0,1,[[[$row]]]]]"))
        assertEquals(null, com.auralis.music.data.network.InnerTubePlayerResolver.parseVisitorData("not json"))
    }

    @Test fun `songs downloaded on their own are named with their artist`() {
        val track = com.auralis.music.domain.model.Track(id = "a", title = "Like Him", artist = "Tyler")
        assertEquals("Like Him - Tyler.m4a", com.auralis.music.data.download.PlaylistDownloadPaths.individualFileName(track))
    }

    @Test fun `an unknown bitrate stays unknown`() {
        assertEquals(null, PlaybackSignals.bitrateOf("https://example.com/a_320.mp4", null, null, -1))
    }
}
