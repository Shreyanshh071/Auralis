package com.auralis.music

import com.auralis.music.ui.lyrics.MAX_CLOCK_CARRY_MS
import com.auralis.music.ui.lyrics.carriedPositionMs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The timing rules behind the word-sweep clock, tested against the pure
 * interpolation function so no player or Compose runtime is needed.
 *
 * Every case here maps to an on-device failure the interpolation exists to
 * prevent: a stepping sweep, a sweep that keeps running while paused, a sweep
 * that sprints ahead during a buffer stall, or one that walks backwards.
 */
class LyricsClockTest {

    @Test
    fun `carries the position forward across a plateau in the raw reading`() {
        // The engine reported 10_000ms at wall time 500; it is now wall time 540
        // and the engine still says 10_000. 40ms of audio has played.
        val pos = carriedPositionMs(
            rawMs = 10_000L,
            anchorRawMs = 10_000L,
            anchorWallMs = 500L,
            nowWallMs = 540L,
            speed = 1.0f,
            isPlaying = true
        )
        assertEquals(10_040L, pos)
    }

    @Test
    fun `snaps to the raw reading whenever it changes`() {
        // This one branch covers seek, resume, track change and buffer recovery:
        // a reading that differs from the anchor is the truth, full stop.
        val seeked = carriedPositionMs(
            rawMs = 92_000L,
            anchorRawMs = 10_000L,
            anchorWallMs = 500L,
            nowWallMs = 540L,
            speed = 1.0f,
            isPlaying = true
        )
        assertEquals(92_000L, seeked)

        // Backwards seeks snap just as hard — no easing towards the target.
        val rewound = carriedPositionMs(
            rawMs = 3_000L,
            anchorRawMs = 60_000L,
            anchorWallMs = 500L,
            nowWallMs = 900L,
            speed = 1.0f,
            isPlaying = true
        )
        assertEquals(3_000L, rewound)
    }

    @Test
    fun `clamps the carry so a stalled clock cannot run away`() {
        // A buffer underrun: 500ms of wall time with no new reading. Without the
        // clamp the highlight would be half a second ahead of the audio.
        val pos = carriedPositionMs(
            rawMs = 10_000L,
            anchorRawMs = 10_000L,
            anchorWallMs = 0L,
            nowWallMs = 500L,
            speed = 1.0f,
            isPlaying = true
        )
        assertEquals(10_000L + MAX_CLOCK_CARRY_MS, pos)
        assertTrue(pos - 10_000L <= MAX_CLOCK_CARRY_MS)
    }

    @Test
    fun `never extrapolates while stopped so a paused highlight freezes`() {
        // Requirement: pause mid-word and the sweep must stop where it is.
        val pos = carriedPositionMs(
            rawMs = 10_000L,
            anchorRawMs = 10_000L,
            anchorWallMs = 0L,
            nowWallMs = 5_000L,
            speed = 1.0f,
            isPlaying = false
        )
        assertEquals(10_000L, pos)
    }

    @Test
    fun `scales the carry by playback rate`() {
        val halfSpeed = carriedPositionMs(
            rawMs = 10_000L, anchorRawMs = 10_000L,
            anchorWallMs = 0L, nowWallMs = 80L,
            speed = 0.5f, isPlaying = true
        )
        assertEquals(10_040L, halfSpeed)

        val doubleSpeed = carriedPositionMs(
            rawMs = 10_000L, anchorRawMs = 10_000L,
            anchorWallMs = 0L, nowWallMs = 40L,
            speed = 2.0f, isPlaying = true
        )
        assertEquals(10_080L, doubleSpeed)

        // The clamp bounds wall time, so at 2x the carry can reach 2 * MAX.
        val clampedAtDouble = carriedPositionMs(
            rawMs = 10_000L, anchorRawMs = 10_000L,
            anchorWallMs = 0L, nowWallMs = 5_000L,
            speed = 2.0f, isPlaying = true
        )
        assertEquals(10_000L + MAX_CLOCK_CARRY_MS * 2, clampedAtDouble)
    }

    @Test
    fun `a negative wall delta cannot walk the position backwards`() {
        val pos = carriedPositionMs(
            rawMs = 10_000L,
            anchorRawMs = 10_000L,
            anchorWallMs = 900L,
            nowWallMs = 500L,
            speed = 1.0f,
            isPlaying = true
        )
        assertEquals(10_000L, pos)
    }

    @Test
    fun `a zero or negative speed contributes no carry`() {
        assertEquals(
            10_000L,
            carriedPositionMs(10_000L, 10_000L, 0L, 80L, 0.0f, true)
        )
        assertEquals(
            10_000L,
            carriedPositionMs(10_000L, 10_000L, 0L, 80L, -1.5f, true)
        )
    }

    @Test
    fun `a sequence of frames on one plateau is monotonic and bounded`() {
        // Simulates 16ms frames against a 60ms-stale reading.
        var previous = Long.MIN_VALUE
        for (frame in 0..10) {
            val pos = carriedPositionMs(
                rawMs = 30_000L,
                anchorRawMs = 30_000L,
                anchorWallMs = 0L,
                nowWallMs = frame * 16L,
                speed = 1.0f,
                isPlaying = true
            )
            assertTrue("frame $frame went backwards", pos >= previous)
            assertTrue("frame $frame exceeded the carry clamp", pos <= 30_000L + MAX_CLOCK_CARRY_MS)
            previous = pos
        }
    }
}
