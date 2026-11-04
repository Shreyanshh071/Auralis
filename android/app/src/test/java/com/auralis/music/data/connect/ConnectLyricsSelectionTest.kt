package com.auralis.music.data.connect

import com.auralis.music.domain.model.*
import org.junit.Assert.*
import org.junit.Test

class ConnectLyricsSelectionTest {
    @Test fun providerAndRecordingSurviveTheSessionRoundTrip() {
        val track = ConnectTrack("video", "Title", "Artist", lyricsProvider = "SPICY_LYRICS",
            lyricsSpotifyId = "abcdefghijklmnopqrstuv", lyricsTitle = "Title - 2011 Remaster",
            lyricsArtist = "Artist", lyricsDurationMs = 215000)
        val session = ConnectSession(track = track)
        assertEquals(track, ConnectCodec.decodeSession(ConnectCodec.encodeSession(session)).track)
    }

    @Test fun olderSessionsAndUnknownProvidersStayCompatible() {
        val old = ConnectCodec.encodeSession(ConnectSession(track = ConnectTrack("video", "Title", "Artist")))
        assertNull(ConnectCodec.decodeSession(old).track!!.lyricsProvider)
        val invalid = old.toMutableMap().apply {
            put("track", mapOf("id" to "video", "lyricsProvider" to "UNKNOWN", "lyricsSpotifyId" to "invalid"))
        }
        assertNull(ConnectCodec.decodeSession(invalid).track!!.lyricsProvider)
        assertNull(ConnectCodec.decodeSession(invalid).track!!.lyricsSpotifyId)
    }

    @Test fun localSelectionCannotLeakAcrossSongs() {
        ConnectLyricsState.publish("a", LyricsData(SyncType.LINE_SYNC, listOf(LyricLine(0, "hello")), provider = LyricsProvider.QQMUSIC))
        assertEquals(LyricsProvider.QQMUSIC, ConnectLyricsState.current("a")!!.provider)
        assertNull(ConnectLyricsState.current("b"))
        ConnectLyricsState.publish("b", null)
        assertNull(ConnectLyricsState.current("a"))
    }
}
