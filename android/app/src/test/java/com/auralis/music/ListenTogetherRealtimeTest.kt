package com.auralis.music

import com.auralis.music.data.sync.ListenTogetherSyncMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ListenTogetherRealtimeTest {

    @Test
    fun `skips are locked for 3 seconds after a song change`() {
        assertEquals(3_000L, ListenTogetherSyncMath.trackChangeLockRemainingMs(lastTrackChangeAtMs = 10_000L, nowMs = 10_000L))
        assertEquals(1_000L, ListenTogetherSyncMath.trackChangeLockRemainingMs(10_000L, 12_000L))
        assertEquals(0L, ListenTogetherSyncMath.trackChangeLockRemainingMs(10_000L, 13_000L))
        assertEquals(0L, ListenTogetherSyncMath.trackChangeLockRemainingMs(0L, 1_000_000L))
    }

    @Test
    fun `a guest playing the host's exact video is in sync`() {
        assertTrue(ListenTogetherSyncMath.isPlayingHostAudio("vid123", "sp_abc", "vid123", null))
    }

    @Test
    fun `a guest whose own lookup picked the host's video is in sync`() {
        assertTrue(ListenTogetherSyncMath.isPlayingHostAudio("sp_abc", "sp_abc", "vid123", "vid123"))
    }

    @Test
    fun `a guest whose own lookup picked another recording must switch`() {
        assertFalse(ListenTogetherSyncMath.isPlayingHostAudio("sp_abc", "sp_abc", "vid123", "radioEdit9"))
        assertFalse(ListenTogetherSyncMath.isPlayingHostAudio("sp_abc", "sp_abc", "vid123", null))
    }

    @Test
    fun `before the host has resolved its video, the track id decides`() {
        assertTrue(ListenTogetherSyncMath.isPlayingHostAudio("yt1", "yt1", null, null))
        assertFalse(ListenTogetherSyncMath.isPlayingHostAudio("yt2", "yt1", null, null))
        assertFalse(ListenTogetherSyncMath.isPlayingHostAudio(null, "yt1", "yt1", null))
    }

    @Test
    fun `guests correct drift beyond 700ms, not 2500ms`() {
        assertFalse(ListenTogetherSyncMath.shouldResync(10_000L, 10_600L, ListenTogetherSyncMath.GUEST_MAX_DRIFT_MS))
        assertTrue(ListenTogetherSyncMath.shouldResync(10_000L, 10_800L, ListenTogetherSyncMath.GUEST_MAX_DRIFT_MS))
    }

    @Test
    fun `catch-up jumps learn how long this phone takes to restart`() {
        // Landed 1 s behind the host after a jump with a 200 ms lead: aim further ahead next time.
        val next = ListenTogetherSyncMath.nextSeekLead(currentLeadMs = 200L, lagMs = 1_000L)
        assertEquals(1_000L, next)
        // Next jump lands 200 ms behind: close the rest of the gap.
        assertEquals(1_160L, ListenTogetherSyncMath.nextSeekLead(next, lagMs = 200L))
        // Overshot (landed ahead): back off.
        assertEquals(840L, ListenTogetherSyncMath.nextSeekLead(1_000L, lagMs = -200L))
        // Never more than 3 s, never negative.
        assertEquals(3_000L, ListenTogetherSyncMath.nextSeekLead(2_900L, lagMs = 5_000L))
        assertEquals(0L, ListenTogetherSyncMath.nextSeekLead(100L, lagMs = -5_000L))
    }

    @Test
    fun `picking a song counts as a song change for the skip lock`() {
        val t = com.auralis.music.domain.model.Track(id = "x", title = "Song")
        assertTrue(com.auralis.music.data.sync.GuestCommand(com.auralis.music.data.sync.GuestCommand.PLAY_TRACK, track = t).changesSong)
        assertTrue(com.auralis.music.data.sync.GuestCommand(com.auralis.music.data.sync.GuestCommand.NEXT).changesSong)
        assertFalse(com.auralis.music.data.sync.GuestCommand(com.auralis.music.data.sync.GuestCommand.TOGGLE).changesSong)
        assertFalse(com.auralis.music.data.sync.GuestCommand(com.auralis.music.data.sync.GuestCommand.SEEK, 5_000L).changesSong)
    }

    @Test
    fun `a room closes after 5 minutes with no guests or 30 minutes with nothing playing`() {
        val min = 60_000L
        assertEquals(null, ListenTogetherSyncMath.roomClosingReason(aloneSinceMs = 0L, idleSinceMs = null, nowMs = 4 * min))
        assertEquals(com.auralis.music.data.sync.ROOM_CLOSING_EMPTY, ListenTogetherSyncMath.roomClosingReason(0L, null, 5 * min))
        assertEquals(null, ListenTogetherSyncMath.roomClosingReason(null, 0L, 29 * min))
        assertEquals(com.auralis.music.data.sync.ROOM_CLOSING_IDLE, ListenTogetherSyncMath.roomClosingReason(null, 0L, 30 * min))
        // Guests present and music playing: stays open however long.
        assertEquals(null, ListenTogetherSyncMath.roomClosingReason(null, null, 1_000 * min))
    }

    @Test
    fun `a quiet phone is never dropped before the room's own closing rules would close it`() {
        // Android freezes an idle app in the background; its check-ins pause but it's still in the room.
        assertTrue(ListenTogetherSyncMath.PRESENCE_STALE_MS >
            ListenTogetherSyncMath.ROOM_IDLE_CLOSE_MS + ListenTogetherSyncMath.ROOM_CLOSE_WARNING_MS)
        val now = 10_000_000L
        assertFalse(ListenTogetherSyncMath.isPresenceStale(lastSeenServerMs = now - 2 * 60_000L, nowServerMs = now))
    }
}
