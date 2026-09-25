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

    @Test
    fun `clock offset sample uses round trip midpoint and half round trip uncertainty`() {
        // Local clock 60s behind the server; server stamped mid-flight.
        val sample = ListenTogetherSyncMath.clockOffsetSample(sentAtMs = 1_000L, ackAtMs = 1_200L, serverStampMs = 61_100L)
        assertEquals(60_000L, sample.offsetMs)
        assertEquals(100L, sample.uncertaintyMs)

        val noisy = sample.copy(offsetMs = 59_000L, uncertaintyMs = 900L)
        assertEquals(sample, ListenTogetherSyncMath.bestClockOffset(listOf(noisy, sample)))
        assertNull(ListenTogetherSyncMath.bestClockOffset(emptyList()))
    }

    @Test
    fun `host broadcast time uses server stamp so skewed phone clocks do not shift position`() {
        // Host clock is 30s fast; its wall-clock stamp would make us start 30s into the song.
        val serverStamp = 100_000L
        val hostWallClock = 130_000L
        val guestOffset = 5_000L // server is 5s ahead of the guest
        val broadcastLocal = ListenTogetherSyncMath.hostBroadcastLocalTime(serverStamp, hostWallClock, guestOffset)
        assertEquals(95_000L, broadcastLocal)

        val estimated = ListenTogetherSyncMath.calculateEstimatedHostPosition(
            broadcastPositionMs = 0L,
            broadcastTimestampMs = broadcastLocal,
            isPlaying = true,
            nowMs = 97_000L // 2s after the broadcast on the guest's clock
        )
        assertEquals(2_000L, estimated)

        // Without a server stamp or an offset, fall back to the host's own stamp.
        assertEquals(hostWallClock, ListenTogetherSyncMath.hostBroadcastLocalTime(null, hostWallClock, guestOffset))
        assertEquals(hostWallClock, ListenTogetherSyncMath.hostBroadcastLocalTime(serverStamp, hostWallClock, null))
    }

    @Test
    fun `presence goes stale only after the timeout and unknown stamps never do`() {
        val now = 1_000_000L
        assertFalse(ListenTogetherSyncMath.isPresenceStale(now - 20_000L, now))
        assertFalse(ListenTogetherSyncMath.isPresenceStale(now - ListenTogetherSyncMath.PRESENCE_STALE_MS, now))
        assertTrue(ListenTogetherSyncMath.isPresenceStale(now - ListenTogetherSyncMath.PRESENCE_STALE_MS - 1, now))
        assertFalse(ListenTogetherSyncMath.isPresenceStale(null, now))
    }

    @Test
    fun `queue window keeps the current track inside even deep into a long queue`() {
        val queue = (0 until 200).toList()

        val (start, startIdx) = ListenTogetherSyncMath.queueWindow(queue, 3)
        assertEquals(50, start.size)
        assertEquals(0, start.first())
        assertEquals(3, startIdx)

        val (middle, middleIdx) = ListenTogetherSyncMath.queueWindow(queue, 120)
        assertEquals(50, middle.size)
        assertEquals(120, middle[middleIdx])
        assertEquals(110, middle.first())

        val (end, endIdx) = ListenTogetherSyncMath.queueWindow(queue, 199)
        assertEquals(50, end.size)
        assertEquals(199, end[endIdx])
        assertEquals(199, end.last())

        val (short, shortIdx) = ListenTogetherSyncMath.queueWindow(listOf("a", "b", "c"), 2)
        assertEquals(listOf("a", "b", "c"), short)
        assertEquals(2, shortIdx)

        assertEquals(emptyList<Int>() to 0, ListenTogetherSyncMath.queueWindow(emptyList<Int>(), 5))
    }

    @Test
    fun `resolveQueueIndex trusts the broadcast index for duplicate tracks and falls back by id`() {
        fun t(id: String) = com.auralis.music.domain.model.Track(id = id)
        val queue = listOf(t("a"), t("b"), t("a"), t("c"))

        assertEquals(2, ListenTogetherSyncMath.resolveQueueIndex(queue, 2, "a"))
        assertEquals(3, ListenTogetherSyncMath.resolveQueueIndex(queue, 0, "c"))
        assertEquals(0, ListenTogetherSyncMath.resolveQueueIndex(queue, 1, "missing"))
        assertEquals(1, ListenTogetherSyncMath.resolveQueueIndex(queue, 99, "b"))
    }
}
