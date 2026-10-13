package com.auralis.music.windows.together

import com.auralis.music.data.sync.*
import com.auralis.music.domain.model.Track
import org.json.JSONObject
import kotlin.test.*

class AndroidRoomRulesTest {
    @Test fun allRuleCombinationsMatchAndroidPermissionsAndApproval() {
        for (mask in 0..15) {
            val settings = RoomSettings(mask and 1 != 0, mask and 2 != 0, mask and 4 != 0, mask and 8 != 0)
            assertEquals(settings, DesktopTogether.settingsFrom(JSONObject(settings.toMap())))
            assertEquals(settings.guestsCanControlPlayback, settings.allows(GuestCommand(GuestCommand.SEEK)))
            assertEquals(settings.guestsCanAddSongs, settings.allows(GuestCommand(GuestCommand.REMOVE_QUEUE_ITEM)))
            assertEquals(settings.guestsCanPlaySongs || settings.requireApproval, settings.allows(GuestCommand(GuestCommand.NEXT)))
            assertEquals(when {
                settings.requireApproval -> GuestSongDecision.WAIT_FOR_HOST
                settings.guestsCanAddSongs -> GuestSongDecision.ADD
                else -> GuestSongDecision.DECLINE
            }, settings.decideGuestSong())
        }
    }

    @Test fun staleAndDeniedCommandsNeverReplayWhenPermissionsChange() {
        val tracker = GuestCommandTracker()
        fun member(seq: Long) = RoomMember(id = "guest", command = GuestCommand(GuestCommand.NEXT, seq = seq))
        assertTrue(tracker.newCommands(listOf(member(5)), true).isEmpty())
        assertTrue(tracker.newCommands(listOf(member(6)), false).isEmpty())
        assertTrue(tracker.newCommands(listOf(member(6)), true).isEmpty())
        assertEquals(7L, tracker.newCommands(listOf(member(7)), true).single().second.seq)
        assertTrue(tracker.newCommands(listOf(member(7)), true).isEmpty())
    }

    @Test fun queueEditsUseTheSharedWindowAndRejectStaleOrdering() {
        val queue = (0..249).map { Track(id = "$it", title = "$it", artist = "test") }
        val (window, index) = ListenTogetherSyncMath.queueWindow(queue, 175)
        val request = GuestCommand(GuestCommand.MOVE_QUEUE_ITEM, fromIndex = index + 1,
            toIndex = index + 2, queueIds = window.map { it.id })
        assertEquals(150, request.queueEditOffset(queue, 175))
        assertNull(request.queueEditOffset(queue.reversed(), 175))
        assertNull(request.copy(fromIndex = 101).queueEditOffset(queue, 175))
        assertEquals(99, ListenTogetherSyncMath.queueWindow(queue, 249).second)
    }

    @Test fun roomWarningsAndSyncUseAndroidTiming() {
        assertNull(ListenTogetherSyncMath.roomClosingReason(0, null, 299999))
        assertEquals(ROOM_CLOSING_EMPTY, ListenTogetherSyncMath.roomClosingReason(0, null, 300000))
        assertEquals(ROOM_CLOSING_IDLE, ListenTogetherSyncMath.roomClosingReason(null, 0, 1800000))
        assertEquals(1500L, DesktopTogether.estimatedPosition(1000, 100, true, 1100, .5))
        assertEquals(1000L, DesktopTogether.estimatedPosition(1000, 100, false, 1100, 1.0))
        assertEquals(3000L, ListenTogetherSyncMath.nextSeekLead(200, 10000))
    }
}
