package com.auralis.music.desktop.playback

import com.auralis.music.domain.model.Track
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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

    @Test fun acceptsRecordingsThatAgreeOnDurationWhenItIsMissing() {
        val queued = Track("Lk3UlEyIW84", "Always", "Daniel Caesar", album = "NEVER ENOUGH")
        val albumRecording = match.copy(track = queued.copy(id = "saavn-album", duration = 225))
        val candidates = listOf(
            albumRecording.copy(track = albumRecording.track.copy(id = "live", title = "Always (Live)")),
            albumRecording.copy(track = albumRecording.track.copy(id = "cover", artist = "Another Artist")),
            albumRecording.copy(track = albumRecording.track.copy(id = "deluxe", album = "NEVER ENOUGH (Deluxe)")),
            albumRecording,
        )
        // The song's own album wins over another edition.
        assertEquals(albumRecording, DesktopJioSaavnAudio.pick(queued, candidates))
        // Without an album, the exact title by the exact artist still identifies one runtime.
        assertEquals(225, assertNotNull(DesktopJioSaavnAudio.pick(queued.copy(album = null), candidates)).track.duration)
        val duplicate = albumRecording.copy(track = albumRecording.track.copy(id = "saavn-other"))
        assertEquals(albumRecording, DesktopJioSaavnAudio.pick(queued, candidates + duplicate))
        val differentCut = duplicate.copy(track = duplicate.track.copy(duration = 221))
        assertNull(DesktopJioSaavnAudio.pick(queued, candidates + differentCut))
    }

    @Test fun readsArtistListsAsSetsWhenTheLengthIsUnknown() {
        // From the log: YouTube said 0 s and "Vishal Bhatt & Doli Rani"; JioSaavn lists both singers.
        val queued = Track("5gg46GQ8lvQ", "AI hai Ki De Aai Hai", "Vishal Bhatt & Doli Rani")
        val row = match.copy(track = Track("nitJKgqk", "AI hai Ki De Aai Hai", "Vishal Bhatt, Doli Rani", duration = 185))
        assertEquals(row, DesktopJioSaavnAudio.pick(queued, listOf(row)))
        // Only one of the singers is someone else's credit, not this recording.
        assertNull(DesktopJioSaavnAudio.pick(queued, listOf(row.copy(track = row.track.copy(artist = "Vishal Bhatt")))))
    }

    @Test fun treatsPlaceholderLengthsAsUnknown() {
        // A YouTube album page fills in 210 s when it shows no length.
        val queued = Track("abcdefghijk", "Loving You", "Some Singer", duration = 210)
        val row = match.copy(track = Track("saavn", "Loving You", "Some Singer", duration = 187))
        assertEquals(row, DesktopJioSaavnAudio.pick(queued, listOf(row)))
        // A real length that disagrees is a different recording.
        assertNull(DesktopJioSaavnAudio.pick(queued.copy(duration = 205), listOf(row)))
    }

    @Test fun usesARowWithout320WhenItIsTheOnlyRecording() {
        val only = match.copy(has320 = false)
        assertEquals(only, DesktopJioSaavnAudio.pick(track, listOf(only)))
    }

    @Test fun neverSwapsInAnotherCutOfAVersionedTitle() {
        // YouTube lists "Last Christmas (Pudding Mix)" at 264 s; JioSaavn's Pudding Mix is 407 s and
        // its 264 s row is the plain single. Exact recording only: neither stands in.
        val queued = Track("KhqNTjbQ71A", "Last Christmas (Pudding Mix)", "Wham!", album = "Music From The Edge Of Heaven", duration = 264)
        val rows = listOf(
            match.copy(track = Track("gZRuW6tJ", "Last Christmas (Pudding Mix)", "Wham!", duration = 407)),
            match.copy(track = Track("2zOIhxsj", "Last Christmas", "Wham!", duration = 264)),
            match.copy(track = Track("ZhHLmHdY", "Last Christmas (Single Version)", "Wham!", duration = 260)),
        )
        assertNull(DesktopJioSaavnAudio.pick(queued, rows))
    }

    @Test fun readsTheArtistFromTheTitleOfAReupload() {
        val reupload = Track("LtWSR89UhRg", "Robbie Williams - So This Is Christmas", "aia22", duration = 173)
        val variants = DesktopJioSaavnAudio.lookupVariants(reupload)
        assertEquals(listOf("aia22", "Robbie Williams"), variants.map { it.artist })
        assertEquals("So This Is Christmas", variants.last().title)
        assertEquals(listOf(track), DesktopJioSaavnAudio.lookupVariants(track))
    }

    @Test fun buildsThe320RenditionOnTheMediaHostAndPreservesItsQuery() {
        assertEquals("https://aac.saavncdn.com/song_320.mp4?token=a%2Bb",
            DesktopJioSaavnAudio.streamUrl("http://aac.saavncdn.com/song_96.mp4?token=a%2Bb", has320 = true))
        assertEquals("https://aac.saavncdn.com/song_96.mp4?token=a%2Bb",
            DesktopJioSaavnAudio.streamUrl("http://aac.saavncdn.com/song_96.mp4?token=a%2Bb", has320 = false))
        assertNull(DesktopJioSaavnAudio.streamUrl("https://other.example/song_96.mp4", has320 = true))
    }
}
