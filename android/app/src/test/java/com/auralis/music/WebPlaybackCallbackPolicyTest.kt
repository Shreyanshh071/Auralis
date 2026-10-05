package com.auralis.music

import com.auralis.music.data.service.acceptsWebPlaybackCallback
import com.auralis.music.data.service.acceptsWebPlaybackPage
import org.junit.Assert.*
import org.junit.Test

class WebPlaybackCallbackPolicyTest {
    @Test fun `page finishing after native handoff cannot restart web audio`() {
        assertTrue(acceptsWebPlaybackPage("recording-a", 10, 10, "recording-a"))
        // stop clears the active recording and advances the request, while Chromium
        // can still deliver the old navigation's page-finished event.
        assertFalse(acceptsWebPlaybackPage(null, 11, 10, "recording-a"))
        assertFalse(acceptsWebPlaybackCallback(null, 11, 10))
        assertFalse(acceptsWebPlaybackCallback(null, 11, 11))
    }

    @Test fun `previous recording callbacks cannot control the new song`() {
        assertFalse(acceptsWebPlaybackPage("recording-b", 12, 10, "recording-a"))
        assertFalse(acceptsWebPlaybackPage("recording-b", 12, 12, "recording-a"))
        assertFalse(acceptsWebPlaybackCallback("recording-b", 12, 10))
        assertTrue(acceptsWebPlaybackPage("recording-b", 12, 12, "recording-b"))
        assertTrue(acceptsWebPlaybackCallback("recording-b", 12, 12))
    }

    @Test fun `old navigation to the same recording cannot inherit a new request`() {
        assertFalse(acceptsWebPlaybackPage("recording-a", 13, 10, "recording-a"))
        assertFalse(acceptsWebPlaybackCallback("recording-a", 13, 0))
    }
}
