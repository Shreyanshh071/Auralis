package com.auralis.music

import com.auralis.music.data.network.AudioVersionPreference
import com.auralis.music.domain.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Prefer audio-only versions": which song release may stand in for a music video. */
class AudioVersionPreferenceTest {
    // Blinding Lights: the official video runs 4:23 with its skits, the song 3:20.
    private val video = Track(id = "4NRXx6U8ABQ", title = "Blinding Lights", artist = "The Weeknd", duration = 263)

    private fun song(id: String, title: String, artist: String = "The Weeknd", duration: Long = 200, album: String? = "After Hours") =
        Track(id = id, title = title, artist = artist, duration = duration, album = album)

    @Test
    fun `the song release replaces its music video`() {
        val picked = AudioVersionPreference.pickAudioVersion(video, listOf(song("fHI8X4OXluQ", "Blinding Lights")))
        assertEquals("fHI8X4OXluQ", picked?.id)
    }

    @Test
    fun `a remix or live take never stands in, whatever its length`() {
        val candidates = listOf(
            song("remix000001", "Blinding Lights (Chromatics Remix)", duration = 262),
            song("live0000001", "Blinding Lights (Live)", duration = 255),
            song("acoustic001", "Blinding Lights - Acoustic", duration = 200)
        )
        assertNull(AudioVersionPreference.pickAudioVersion(video, candidates))
    }

    @Test
    fun `the first safe release wins over an earlier clashing one`() {
        val candidates = listOf(
            song("remix000001", "Blinding Lights (Remix)", duration = 200),
            song("fHI8X4OXluQ", "Blinding Lights")
        )
        assertEquals("fHI8X4OXluQ", AudioVersionPreference.pickAudioVersion(video, candidates)?.id)
    }

    @Test
    fun `another artist's song of the same name is not the release`() {
        assertNull(AudioVersionPreference.pickAudioVersion(video, listOf(song("cover000001", "Blinding Lights", artist = "Some Cover Band"))))
    }

    @Test
    fun `a duet's release matches when the video credits both artists`() {
        // get_queue's byline for kJQP7kiw5Fk: "Luis Fonsi & Daddy Yankee", 4:42; the song is 3:49.
        val duet = Track(id = "kJQP7kiw5Fk", title = "Despacito", artist = "Luis Fonsi & Daddy Yankee", duration = 282)
        val release = song("FXovf5dsRTw", "Despacito", artist = "Luis Fonsi & Daddy Yankee", duration = 229, album = "VIDA")
        assertEquals("FXovf5dsRTw", AudioVersionPreference.pickAudioVersion(duet, listOf(release))?.id)
    }

    @Test
    fun `an uncredited feature is a different recording`() {
        assertNull(AudioVersionPreference.pickAudioVersion(video, listOf(song("feat0000001", "Blinding Lights (feat. ROSALÍA)", duration = 200))))
    }

    @Test
    fun `a different song is never picked`() {
        assertNull(AudioVersionPreference.pickAudioVersion(video, listOf(song("savethetear", "Save Your Tears"))))
    }

    @Test
    fun `song must fit inside the video`() {
        assertTrue(AudioVersionPreference.fitsInsideVideo(263, 200))
        assertTrue(AudioVersionPreference.fitsInsideVideo(204, 200))
        assertTrue(AudioVersionPreference.fitsInsideVideo(200, 205)) // rounding and fades
        assertFalse(AudioVersionPreference.fitsInsideVideo(200, 260)) // longer than the video: extended cut
        assertFalse(AudioVersionPreference.fitsInsideVideo(600, 200)) // a short film, not this song
        assertFalse(AudioVersionPreference.fitsInsideVideo(300, 120)) // under half the video
        assertTrue(AudioVersionPreference.fitsInsideVideo(0, 200))
    }

    @Test
    fun `an unknown artist is never matched`() {
        assertNull(AudioVersionPreference.pickAudioVersion(video.copy(artist = "YouTube Artist"), listOf(song("fHI8X4OXluQ", "Blinding Lights"))))
    }
}
