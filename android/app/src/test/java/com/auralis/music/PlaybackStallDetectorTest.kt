package com.auralis.music

import com.auralis.music.data.sync.PlaybackStallDetector
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackStallDetectorTest {

    /** Starts a song at [t] that loads in [loadMs] and then plays. */
    private fun PlaybackStallDetector.songStarts(t: Long, loadMs: Long = 2_000L): Long {
        onTrackStart(t)
        onPlayerState(isPlaying = false, isBuffering = true, nowMs = t)
        onPlayerState(isPlaying = true, isBuffering = false, nowMs = t + loadMs)
        return t + loadMs
    }

    private fun PlaybackStallDetector.stall(t: Long, lengthMs: Long): Long {
        onPlayerState(isPlaying = true, isBuffering = false, nowMs = t - 100)
        onPlayerState(isPlaying = false, isBuffering = true, nowMs = t)
        var now = t
        while (now < t + lengthMs) { now += 500; tick(now) }
        onPlayerState(isPlaying = true, isBuffering = false, nowMs = t + lengthMs)
        return t + lengthMs
    }

    @Test
    fun `normal song loading is never a problem`() {
        val d = PlaybackStallDetector()
        var t = 0L
        repeat(10) { t = d.songStarts(t, loadMs = 8_000L) + 60_000L }
        assertFalse(d.isPoor)
    }

    @Test
    fun `loading after a seek or catch-up jump is not a problem`() {
        val d = PlaybackStallDetector()
        var t = d.songStarts(0L)
        repeat(5) {
            t += 20_000L
            d.onSeek(t)
            d.onPlayerState(isPlaying = false, isBuffering = true, nowMs = t)
            d.onPlayerState(isPlaying = true, isBuffering = false, nowMs = t + 3_000L)
        }
        assertFalse(d.isPoor)
    }

    @Test
    fun `one short mid-song blip is not a problem`() {
        val d = PlaybackStallDetector()
        val t = d.songStarts(0L)
        d.stall(t + 30_000L, 2_000L)
        assertFalse(d.isPoor)
    }

    @Test
    fun `two mid-song pauses within two minutes are a problem`() {
        val d = PlaybackStallDetector()
        val t = d.songStarts(0L)
        d.stall(t + 30_000L, 2_000L)
        d.stall(t + 90_000L, 2_000L)
        assertTrue(d.isPoor)
    }

    @Test
    fun `pauses far apart are not a problem`() {
        val d = PlaybackStallDetector()
        val t = d.songStarts(0L)
        d.stall(t + 30_000L, 2_000L)
        d.stall(t + 200_000L, 2_000L)
        assertFalse(d.isPoor)
    }

    @Test
    fun `one long mid-song pause is a problem while it is still happening`() {
        val d = PlaybackStallDetector()
        val t = d.songStarts(0L) + 30_000L
        d.onPlayerState(isPlaying = true, isBuffering = false, nowMs = t - 100)
        d.onPlayerState(isPlaying = false, isBuffering = true, nowMs = t)
        d.tick(t + 3_000L)
        assertFalse(d.isPoor)
        d.tick(t + 6_500L)
        assertTrue(d.isPoor)
    }

    @Test
    fun `a song that takes too long to start is a problem`() {
        val d = PlaybackStallDetector()
        d.onTrackStart(0L)
        d.onPlayerState(isPlaying = false, isBuffering = true, nowMs = 0L)
        d.tick(11_000L)
        assertFalse(d.isPoor)
        d.tick(12_500L)
        assertTrue(d.isPoor)
    }

    @Test
    fun `a new song held paused at the start is not a slow start`() {
        val d = PlaybackStallDetector()
        d.songStarts(0L)
        // The song changes while paused (the room holds it at 0:00): no "playing" ever arrives.
        d.onPlayerState(isPlaying = false, isBuffering = false, nowMs = 5_000L)
        d.onTrackStart(6_000L)
        var now = 6_000L
        while (now < 60_000L) { now += 1_000; d.tick(now) }
        assertFalse(d.isPoor)
    }

    @Test
    fun `a pause by the user is not a stall`() {
        val d = PlaybackStallDetector()
        val t = d.songStarts(0L)
        d.onPlayerState(isPlaying = false, isBuffering = false, nowMs = t + 10_000L)
        d.tick(t + 60_000L)
        assertFalse(d.isPoor)
    }

    @Test
    fun `it recovers after three quiet minutes`() {
        val d = PlaybackStallDetector()
        val t = d.songStarts(0L)
        d.stall(t + 30_000L, 2_000L)
        val end = d.stall(t + 60_000L, 2_000L)
        assertTrue(d.isPoor)
        d.tick(end + 170_000L)
        assertTrue(d.isPoor)
        d.tick(end + 181_000L)
        assertFalse(d.isPoor)
    }
}
