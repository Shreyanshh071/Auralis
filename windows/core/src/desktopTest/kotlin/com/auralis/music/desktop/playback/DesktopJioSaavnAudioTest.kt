package com.auralis.music.desktop.playback

import com.auralis.music.domain.model.Track
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DesktopJioSaavnAudioTest {
    private val track = Track("track", "Tum Hi Ho", "Arijit Singh", album = "Aashiqui 2", duration = 262)
    private val match = DesktopJioSaavnAudio.Recording(track.copy(id = "saavn"), false, true, "")

    @Test fun requiresTheSameRecordingWhenDurationIsKnown() {
        val candidates = listOf(
            match.copy(track = match.track.copy(title = "Tum Hi Ho (Remix)")),
            match.copy(track = match.track.copy(artist = "Another Artist")),
            match.copy(track = match.track.copy(duration = 266)),
            match.copy(has320 = false),
            match,
        )
        assertEquals(match, DesktopJioSaavnAudio.pick(track, candidates))
    }

    @Test fun acceptsExactAlbumRecordingsThatAgreeOnDurationWhenItIsMissing() {
        val queued = Track("Lk3UlEyIW84", "Always", "Daniel Caesar", album = "NEVER ENOUGH")
        val albumRecording = match.copy(track = queued.copy(id = "saavn-album", duration = 225))
        val candidates = listOf(
            albumRecording.copy(track = albumRecording.track.copy(id = "live", title = "Always (Live)")),
            albumRecording.copy(track = albumRecording.track.copy(id = "cover", artist = "Another Artist")),
            albumRecording.copy(track = albumRecording.track.copy(id = "deluxe", album = "NEVER ENOUGH (Deluxe)")),
            albumRecording,
        )
        assertEquals(albumRecording, DesktopJioSaavnAudio.pick(queued, candidates))
        assertNull(DesktopJioSaavnAudio.pick(queued.copy(album = null), candidates))
        val duplicate = albumRecording.copy(track = albumRecording.track.copy(id = "saavn-other"))
        assertEquals(albumRecording, DesktopJioSaavnAudio.pick(queued, candidates + duplicate))
        val differentCut = duplicate.copy(track = duplicate.track.copy(duration = 221))
        assertNull(DesktopJioSaavnAudio.pick(queued, candidates + differentCut))
    }

    @Test fun buildsThe320RenditionOnTheMediaHostAndPreservesItsQuery() {
        assertEquals("https://aac.saavncdn.com/song_320.mp4?token=a%2Bb",
            DesktopJioSaavnAudio.stream320("http://aac.saavncdn.com/song_96.mp4?token=a%2Bb"))
        assertNull(DesktopJioSaavnAudio.stream320("https://other.example/song_96.mp4"))
    }
}
