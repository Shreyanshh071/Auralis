package com.auralis.music.desktop.playback

import com.auralis.music.domain.model.Track
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopSoundCloudAudioTest {
    private val song = Track("5tpQaCAq6Qc", "Loving Machine", "TV Girl", album = "Who Really Cares", duration = 227)
    private fun upload(name: String, uploader: String, seconds: Long, url: String = "https://soundcloud.com/x/${name.hashCode()}") =
        DesktopSoundCloudAudio.Upload(url, name, uploader, seconds)

    @Test fun readsTheArtistFromALabelsUploadTitle() {
        val label = upload("TV Girl - Loving Machine", "Some Label", 227)
        val official = upload("Loving Machine", "TV Girl", 228)
        val picked = DesktopSoundCloudAudio.pick(song, listOf(label, official))
        // Both are the recording; the artist's own account goes first.
        assertEquals(listOf(official, label), picked)
        val asTrack = DesktopSoundCloudAudio.asTrack(song, label)
        assertEquals("Loving Machine", asTrack.title)
        assertEquals("Some Label", DesktopSoundCloudAudio.asTrack(song, upload("Not TV Girl - Loving Machine", "Some Label", 227)).artist)
    }

    @Test fun refusesOtherVersionsCoversAndOtherLengths() {
        val candidates = listOf(
            upload("Loving Machine (Slowed + Reverb)", "TV Girl", 260),
            upload("Loving Machine (Remix)", "TV Girl", 227),
            upload("Loving Machine", "Twinkle Twinkle Little Rock Star", 235),
            upload("Loving Machine", "TV Girl", 240),
        )
        assertEquals(emptyList(), DesktopSoundCloudAudio.pick(song, candidates))
    }

    @Test fun needsOneAgreedRuntimeWhenTheLengthIsUnknown() {
        val unknown = song.copy(duration = 0)
        val a = upload("Loving Machine", "TV Girl", 227, url = "a")
        val b = upload("Loving Machine", "TV Girl", 227, url = "b")
        assertEquals(listOf(a, b), DesktopSoundCloudAudio.pick(unknown, listOf(a, b)))
        assertEquals(emptyList(), DesktopSoundCloudAudio.pick(unknown, listOf(a, upload("Loving Machine", "TV Girl", 260, url = "c"))))
    }

    @Test fun refusesAPreviewServedInPlaceOfTheSong() {
        val listed = upload("Loving Machine", "TV Girl", 227)
        assertFalse(DesktopSoundCloudAudio.fullLength(30, listed, song))
        assertTrue(DesktopSoundCloudAudio.fullLength(228, listed, song))
        // Unknown or placeholder lengths on our side: only the upload's own listing is checked.
        assertTrue(DesktopSoundCloudAudio.fullLength(227, listed, song.copy(duration = 210)))
    }
}
