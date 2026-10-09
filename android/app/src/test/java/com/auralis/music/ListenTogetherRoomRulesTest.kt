package com.auralis.music

import com.auralis.music.data.sync.GuestCommand
import com.auralis.music.data.sync.GuestCommandTracker
import com.auralis.music.data.sync.GuestSongDecision
import com.auralis.music.data.sync.RoomMember
import com.auralis.music.data.sync.RoomSettings
import com.auralis.music.data.sync.decideGuestSong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ListenTogetherRoomRulesTest {

    @Test
    fun `queue edits follow add songs independently of playback and approval`() {
        val edits = listOf(GuestCommand(GuestCommand.MOVE_QUEUE_ITEM), GuestCommand(GuestCommand.REMOVE_QUEUE_ITEM))
        for (edit in edits) {
            assertTrue(RoomSettings(guestsCanAddSongs = true).allows(edit))
            assertTrue(RoomSettings(guestsCanAddSongs = true, requireApproval = true).allows(edit))
            assertTrue(!RoomSettings(guestsCanAddSongs = false, guestsCanControlPlayback = true,
                guestsCanPlaySongs = true, requireApproval = true).allows(edit))
            assertTrue(!edit.changesSong)
        }
    }

    @Test
    fun `queue edits reject stale ordering and invalid destinations`() {
        val queue = listOf("a", "b", "c").map { com.auralis.music.domain.model.Track(id = it) }
        val edit = GuestCommand(GuestCommand.MOVE_QUEUE_ITEM, fromIndex = 0, toIndex = 2,
            queueIds = queue.map { it.id })
        assertTrue(edit.isValidQueueEdit(queue))
        assertTrue(!edit.isValidQueueEdit(queue.reversed()))
        assertTrue(!edit.isValidQueueEdit(queue.dropLast(1)))
        assertTrue(!edit.copy(fromIndex = -1).isValidQueueEdit(queue))
        assertTrue(!edit.copy(toIndex = 3).isValidQueueEdit(queue))
        assertTrue(!edit.copy(toIndex = 0).isValidQueueEdit(queue))
        assertTrue(!edit.copy(queueIds = emptyList()).isValidQueueEdit(queue))
    }

    @Test
    fun `queue removals identify the selected duplicate by its original index`() {
        val queue = listOf("a", "b", "a").map { com.auralis.music.domain.model.Track(id = it) }
        val edit = GuestCommand(GuestCommand.REMOVE_QUEUE_ITEM, fromIndex = 2, queueIds = queue.map { it.id })
        assertTrue(edit.isValidQueueEdit(queue))
        assertTrue(!edit.isValidQueueEdit(listOf(queue[0], queue[2], queue[1])))
        assertTrue(!edit.copy(fromIndex = 3).isValidQueueEdit(queue))
        assertTrue(!edit.isValidQueueEdit(emptyList()))
    }

    @Test
    fun `edits in a long shared queue map to the correct host indices`() {
        val queue = (0 until 200).map { com.auralis.music.domain.model.Track(id = "song_$it") }
        val (window, _) = com.auralis.music.data.sync.ListenTogetherSyncMath.queueWindow(queue, 120)
        val edit = GuestCommand(GuestCommand.MOVE_QUEUE_ITEM, fromIndex = 2, toIndex = 5,
            queueIds = window.map { it.id })
        assertEquals(100, edit.queueEditOffset(queue, 120))
        assertEquals(null, edit.queueEditOffset(queue, 5))
        assertEquals(null, edit.queueEditOffset(emptyList(), 0))
    }

    private val host = RoomMember(id = "h", name = "Host", isHost = true)
    private fun guest(seq: Long?, type: String = GuestCommand.NEXT) =
        RoomMember(id = "g", name = "Guest", command = seq?.let { GuestCommand(type, 0L, it) })

    @Test
    fun `a guest's first request after joining is carried out`() {
        val tracker = GuestCommandTracker()
        assertTrue(tracker.newCommands(listOf(host, guest(null)), allowed = true).isEmpty())
        val fresh = tracker.newCommands(listOf(host, guest(100)), allowed = true)
        assertEquals(listOf(GuestCommand.NEXT), fresh.map { it.second.type })
    }

    @Test
    fun `each request runs once, even when the roster is re-read`() {
        val tracker = GuestCommandTracker()
        tracker.newCommands(listOf(guest(null)), allowed = true)
        assertEquals(1, tracker.newCommands(listOf(guest(100)), allowed = true).size)
        assertTrue(tracker.newCommands(listOf(guest(100)), allowed = true).isEmpty())
        assertEquals(1, tracker.newCommands(listOf(guest(200, GuestCommand.TOGGLE)), allowed = true).size)
    }

    @Test
    fun `a request already there when the host first looks is not replayed`() {
        val tracker = GuestCommandTracker()
        assertTrue(tracker.newCommands(listOf(guest(100)), allowed = true).isEmpty())
    }

    @Test
    fun `requests are ignored while the host doesn't allow control, and not replayed later`() {
        val tracker = GuestCommandTracker()
        tracker.newCommands(listOf(guest(null)), allowed = false)
        assertTrue(tracker.newCommands(listOf(guest(100)), allowed = false).isEmpty())
        assertTrue(tracker.newCommands(listOf(guest(100)), allowed = true).isEmpty())
        assertEquals(1, tracker.newCommands(listOf(guest(150)), allowed = true).size)
    }

    @Test
    fun `the host's own record never counts as a request`() {
        val tracker = GuestCommandTracker()
        val hostWithCommand = host.copy(command = GuestCommand(GuestCommand.NEXT, 0L, 1))
        tracker.newCommands(listOf(hostWithCommand), allowed = true)
        assertTrue(tracker.newCommands(listOf(hostWithCommand.copy(command = GuestCommand(GuestCommand.NEXT, 0L, 2))), allowed = true).isEmpty())
    }

    @Test
    fun `guest songs follow the room rules`() {
        assertEquals(GuestSongDecision.ADD, RoomSettings(guestsCanAddSongs = true, requireApproval = false).decideGuestSong())
        assertEquals(GuestSongDecision.WAIT_FOR_HOST, RoomSettings(guestsCanAddSongs = true, requireApproval = true).decideGuestSong())
        // Approval on: guests can ask even with adding off; the host decides.
        assertEquals(GuestSongDecision.WAIT_FOR_HOST, RoomSettings(guestsCanAddSongs = false, requireApproval = true).decideGuestSong())
        assertEquals(GuestSongDecision.DECLINE, RoomSettings(guestsCanAddSongs = false, requireApproval = false).decideGuestSong())
    }

    @Test
    fun `room settings survive the round trip through the room document`() {
        val settings = RoomSettings(guestsCanAddSongs = false, guestsCanControlPlayback = true, guestsCanPlaySongs = true, requireApproval = true)
        assertEquals(settings, RoomSettings.from(settings.toMap()))
        assertEquals(RoomSettings(), RoomSettings.from(null)) // rooms made by older app versions
    }

    @Test
    fun `playing songs and controlling playback are separate permissions`() {
        val song = GuestCommand(GuestCommand.PLAY_TRACK, 0L, 1L, com.auralis.music.domain.model.Track(id = "x"))
        val pause = GuestCommand(GuestCommand.TOGGLE, 0L, 1L)
        val playOnly = RoomSettings(guestsCanControlPlayback = false, guestsCanPlaySongs = true)
        assertTrue(playOnly.allows(song))
        assertTrue(!playOnly.allows(pause))
        val controlOnly = RoomSettings(guestsCanControlPlayback = true, guestsCanPlaySongs = false)
        assertTrue(controlOnly.allows(pause))
        assertTrue(!controlOnly.allows(song))
        // Only song changes (not play/pause/seek) trigger the 3-second skip lock.
        assertTrue(playOnly.guestsCanChangeSong && !controlOnly.guestsCanChangeSong)
        assertTrue(!RoomSettings().guestsCanChangeSong)
    }

    @Test
    fun `the host carries out only the requests the room allows`() {
        val tracker = GuestCommandTracker()
        val rules = RoomSettings(guestsCanPlaySongs = true)
        tracker.newCommands(listOf(guest(null))) { rules.allows(it) }
        // Play/pause needs playback control, which is off here.
        assertTrue(tracker.newCommands(listOf(guest(100, GuestCommand.TOGGLE))) { rules.allows(it) }.isEmpty())
        val song = RoomMember(id = "g", name = "Guest", command = GuestCommand(GuestCommand.PLAY_TRACK, 0L, 200L, com.auralis.music.domain.model.Track(id = "x")))
        assertEquals(1, tracker.newCommands(listOf(song)) { rules.allows(it) }.size)
    }

    @Test
    fun `picking a song follows the play switch and the approval switch together`() {
        val pick = GuestCommand(GuestCommand.PLAY_TRACK, 0L, 1L, com.auralis.music.domain.model.Track(id = "x"))
        // play off, approval off: blocked
        assertTrue(!RoomSettings(guestsCanPlaySongs = false, requireApproval = false).allows(pick))
        // play off, approval on: allowed as a request
        RoomSettings(guestsCanPlaySongs = false, requireApproval = true).let { assertTrue(it.allows(pick) && it.approvalApplies) }
        // play on, approval off: plays directly
        RoomSettings(guestsCanPlaySongs = true, requireApproval = false).let { assertTrue(it.allows(pick) && !it.approvalApplies) }
        // play on, approval on: still a request
        RoomSettings(guestsCanPlaySongs = true, requireApproval = true).let { assertTrue(it.allows(pick) && it.approvalApplies) }
    }

    @Test
    fun `approval never opens playback control`() {
        val pause = GuestCommand(GuestCommand.TOGGLE, 0L, 1L)
        assertTrue(!RoomSettings(guestsCanControlPlayback = false, requireApproval = true).allows(pause))
    }

    @Test
    fun `playback control covers play pause and seek, not changing the song`() {
        val control = RoomSettings(guestsCanControlPlayback = true, guestsCanPlaySongs = false)
        assertTrue(control.allows(GuestCommand(GuestCommand.TOGGLE)))
        assertTrue(control.allows(GuestCommand(GuestCommand.SEEK, 5_000L)))
        assertTrue(!control.allows(GuestCommand(GuestCommand.NEXT)))
        assertTrue(!control.allows(GuestCommand(GuestCommand.PREVIOUS)))
        val songs = RoomSettings(guestsCanControlPlayback = false, guestsCanPlaySongs = true)
        assertTrue(songs.allows(GuestCommand(GuestCommand.NEXT)) && songs.allows(GuestCommand(GuestCommand.PREVIOUS)))
        assertTrue(!songs.allows(GuestCommand(GuestCommand.TOGGLE)))
        // With approval on, a skip is allowed as a request even with "play songs" off.
        assertTrue(RoomSettings(requireApproval = true).allows(GuestCommand(GuestCommand.NEXT)))
    }

    @Test
    fun `play and pause name the wanted state and follow playback control`() {
        val control = RoomSettings(guestsCanControlPlayback = true)
        assertTrue(control.allows(GuestCommand(GuestCommand.PLAY)) && control.allows(GuestCommand(GuestCommand.PAUSE)))
        assertTrue(!RoomSettings(guestsCanPlaySongs = true).allows(GuestCommand(GuestCommand.PAUSE)))
        assertTrue(!GuestCommand(GuestCommand.PAUSE).changesSong)
    }
}
