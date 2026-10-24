package com.auralis.music.ui.lyrics

import org.junit.Assert.*
import org.junit.Test

class LyricsSeekHandoffTest {
    @Test fun oldAdvancingCallbacksCannotPullLyricsBackAfterTap() {
        for (elapsed in listOf(25L, 50L, 100L, 250L, 500L)) {
            assertTrue(shouldHoldLyricsSeek(120000, 10000 + elapsed, elapsed, true, true, false))
        }
        assertFalse(shouldHoldLyricsSeek(120000, 120100, 600, true, true, false))
    }

    @Test fun bufferingAndPausedSeeksHaveStableHandoffs() {
        assertTrue(shouldHoldLyricsSeek(120000, 120000, 100, false, true, true))
        assertFalse(shouldHoldLyricsSeek(120000, 120000, 100, false, false, false))
        assertTrue(shouldHoldLyricsSeek(120000, 10000, 3000, false, true, true))
        assertFalse(shouldHoldLyricsSeek(120000, 10000, 6001, false, true, true))
    }
}
