package com.auralis.music.data.connect

import com.auralis.music.domain.model.RepeatMode
import com.auralis.music.domain.model.Track
import org.junit.Assert.*
import org.junit.Test

class ConnectRemotePresentationTest {
    private fun session(active: Boolean, song: String = "song") = ConnectRemoteView(
        track = Track(id = song), queue = listOf(Track(id = song)), index = 0,
        playing = active, buffering = false, positionMs = 1000, anchorLocalMs = 1000,
        durationMs = 10000, shuffle = false, repeat = RepeatMode.OFF, context = null,
        output = ConnectOutput("laptop", "Laptop", ConnectDeviceType.COMPUTER, false, active, active)
    )

    @Test fun laptopClosingUnlocksDismissalAndTicksDoNotRestoreIt() {
        val presentation = ConnectRemotePresentation()
        assertNotNull(presentation.present(session(true)))
        assertFalse(presentation.dismiss(session(true)))
        val ended = session(false)
        assertNotNull(presentation.present(ended))
        assertTrue(presentation.dismiss(ended))
        repeat(5) {
            assertNull(presentation.present(ended.copy(anchorLocalMs = 2000L + it)))
        }
    }

    @Test fun laptopReturningWithTheSameSongShowsItsLiveSessionAgain() {
        val presentation = ConnectRemotePresentation()
        assertTrue(presentation.dismiss(session(false)))
        assertNull(presentation.present(session(false)))
        assertNotNull(presentation.present(session(true)))
        assertNotNull(presentation.present(session(false)))
    }

    @Test fun dismissalDoesNotHideAnotherSongOrAnotherDevice() {
        val presentation = ConnectRemotePresentation()
        val ended = session(false)
        assertTrue(presentation.dismiss(ended))
        assertNotNull(presentation.present(session(false, "another-song")))
        assertNotNull(presentation.present(ended.copy(output = ended.output.copy(deviceId = "other-laptop"))))
        assertNull(presentation.present(null))
        assertNotNull(presentation.present(ended))
    }
}
