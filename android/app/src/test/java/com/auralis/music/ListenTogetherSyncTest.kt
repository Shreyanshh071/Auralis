package com.auralis.music

import com.auralis.music.data.sync.ListenTogetherSyncMath
import org.junit.Assert.*
import org.junit.Test

class ListenTogetherSyncTest {

    @Test
    fun `calculateEstimatedHostPosition accurately extrapolates position based on elapsed time and rate`() {
        val broadcastPos = 30_000L // 30s
        val broadcastTime = 1_000_000L
        val now = 1_005_000L // 5 seconds later

        // Normal 1.0x playback rate -> 30s + 5s = 35s
        val estimated1x = ListenTogetherSyncMath.calculateEstimatedHostPosition(
            broadcastPositionMs = broadcastPos,
            broadcastTimestampMs = broadcastTime,
            isPlaying = true,
            playbackRate = 1.0f,
            nowMs = now
        )
        assertEquals(35_000L, estimated1x)

        // 1.5x playback rate -> 30s + 7.5s = 37.5s
        val estimated15x = ListenTogetherSyncMath.calculateEstimatedHostPosition(
            broadcastPositionMs = broadcastPos,
            broadcastTimestampMs = broadcastTime,
            isPlaying = true,
            playbackRate = 1.5f,
            nowMs = now
        )
        assertEquals(37_500L, estimated15x)

        // When paused, position does not advance
        val paused = ListenTogetherSyncMath.calculateEstimatedHostPosition(
            broadcastPositionMs = broadcastPos,
            broadcastTimestampMs = broadcastTime,
            isPlaying = false,
            playbackRate = 1.0f,
            nowMs = now
        )
        assertEquals(30_000L, paused)
    }

    @Test
    fun `shouldResync triggers only when client drift exceeds 2500ms threshold`() {
        val hostPos = 50_000L

        // Client at 49_000ms (1000ms drift) -> within tolerance
        assertFalse(ListenTogetherSyncMath.shouldResync(clientPositionMs = 49_000L, estimatedHostPositionMs = hostPos))

        // Client at 51_500ms (1500ms drift) -> within tolerance
        assertFalse(ListenTogetherSyncMath.shouldResync(clientPositionMs = 51_500L, estimatedHostPositionMs = hostPos))

        // Client at 47_000ms (3000ms drift) -> resync needed!
        assertTrue(ListenTogetherSyncMath.shouldResync(clientPositionMs = 47_000L, estimatedHostPositionMs = hostPos))

        // Client at 53_000ms (3000ms drift) -> resync needed!
        assertTrue(ListenTogetherSyncMath.shouldResync(clientPositionMs = 53_000L, estimatedHostPositionMs = hostPos))
    }

    @Test
    fun `pill notification types and construction work correctly`() {
        val joinPill = com.auralis.music.ui.viewmodel.PillNotification(
            message = "Alice joined the room",
            type = com.auralis.music.ui.viewmodel.PillType.MEMBER_JOINED
        )
        assertEquals("Alice joined the room", joinPill.message)
        assertEquals(com.auralis.music.ui.viewmodel.PillType.MEMBER_JOINED, joinPill.type)

        val leavePill = com.auralis.music.ui.viewmodel.PillNotification(
            message = "Bob has left the room",
            type = com.auralis.music.ui.viewmodel.PillType.MEMBER_LEFT
        )
        assertEquals("Bob has left the room", leavePill.message)
        assertEquals(com.auralis.music.ui.viewmodel.PillType.MEMBER_LEFT, leavePill.type)

        val hostDisconnectPill = com.auralis.music.ui.viewmodel.PillNotification(
            message = "Host has disconnected",
            type = com.auralis.music.ui.viewmodel.PillType.HOST_DISCONNECTED
        )
        assertEquals("Host has disconnected", hostDisconnectPill.message)
        assertEquals(com.auralis.music.ui.viewmodel.PillType.HOST_DISCONNECTED, hostDisconnectPill.type)
    }
}
