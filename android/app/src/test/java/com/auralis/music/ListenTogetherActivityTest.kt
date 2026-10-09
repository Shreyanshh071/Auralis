package com.auralis.music

import com.auralis.music.data.sync.RoomActivity
import com.auralis.music.data.sync.PendingRoomAction
import com.auralis.music.data.sync.GuestCommand
import com.auralis.music.domain.model.Track
import org.junit.Assert.assertFalse
import com.auralis.music.data.sync.RoomActivityTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ListenTogetherActivityTest {
    @Test
    fun `every listener receives accepted actions once`() {
        val listeners = List(3) { RoomActivityTracker() }
        val history = mutableListOf<RoomActivity>()
        listeners.forEach { assertTrue(it.newActivities(history).isEmpty()) }
        for ((index, message) in listOf(
            "Alice pressed play", "Bob paused", "Alice skipped to the next song",
            "Bob moved the song position", "Alice added Dracula"
        ).withIndex()) {
            val event = RoomActivity(index.toString(), message)
            // Exercise the same map format written to/read from the room document.
            history += RoomActivity.from(event.toMap())!!
            listeners.forEach { listener ->
                assertEquals(listOf(event), listener.newActivities(history))
                assertTrue(listener.newActivities(history).isEmpty())
            }
        }
    }

    @Test
    fun `joining or reconnecting does not replay historical actions`() {
        val history = listOf(RoomActivity("old", "Alice paused"))
        val listener = RoomActivityTracker()
        assertTrue(listener.newActivities(history).isEmpty())
        assertTrue(listener.newActivities(history).isEmpty())
        val fresh = RoomActivity("new", "Alice pressed play")
        assertEquals(listOf(fresh), listener.newActivities(history + fresh))
        assertTrue(RoomActivityTracker().newActivities(history + fresh).isEmpty())
    }

    @Test
    fun `distinct actions with identical messages are still announced`() {
        val listener = RoomActivityTracker()
        listener.newActivities(emptyList())
        val first = RoomActivity("1", "Alice paused")
        val second = RoomActivity("2", "Alice paused")
        assertEquals(listOf(first), listener.newActivities(listOf(first)))
        assertEquals(listOf(second), listener.newActivities(listOf(first, second)))
    }

    @Test
    fun `rotating history retains new actions and ignores already seen ones`() {
        val listener = RoomActivityTracker()
        val history = List(RoomActivity.HISTORY_LIMIT) { RoomActivity(it.toString(), "Action $it") }
        listener.newActivities(history)
        val next = RoomActivity("next", "Bob paused")
        val rotated = (history + next).takeLast(RoomActivity.HISTORY_LIMIT)
        assertEquals(listOf(next), listener.newActivities(rotated))
        assertTrue(listener.newActivities(rotated).isEmpty())
    }

    @Test
    fun `malformed activity cannot break room observation`() {
        for (value in listOf(null, "bad", emptyMap<String, String>(),
            mapOf("id" to "x"), mapOf("id" to "", "message" to "hi"),
            mapOf("id" to "x", "message" to 12))) {
            assertNull(RoomActivity.from(value))
        }
    }
    @Test
    fun `host and either listener notify everyone except the actor`() {
        val members = listOf("shrey", "tejas", "sharthak")
        val trackers = members.associateWith { RoomActivityTracker().also { it.newActivities(emptyList()) } }
        val history = mutableListOf<RoomActivity>()
        for (actor in members) {
            for (action in listOf("pressed play", "paused", "played Dracula", "moved the song position")) {
                val event = RoomActivity("${history.size}", "$actor $action", actor)
                history += RoomActivity.from(event.toMap())!!
                for (member in members) {
                    val received = trackers.getValue(member).newActivities(history).filter { it.shouldNotify(member) }
                    assertEquals(if (member == actor) emptyList<RoomActivity>() else listOf(event), received)
                    assertTrue(trackers.getValue(member).newActivities(history).isEmpty())
                }
            }
        }
    }

    @Test
    fun `older clients activity remains readable without actor metadata`() {
        val event = RoomActivity.from(mapOf("id" to "old", "message" to "Alice paused"))!!
        assertEquals("", event.actorId)
        assertTrue(event.shouldNotify("bob"))
    }
    @Test
    fun `delayed song switch keeps listener attribution and ignores unrelated actions`() {
        val pending = PendingRoomAction(GuestCommand(GuestCommand.PLAY_TRACK), "tejas", "Tejas played Dracula", "dracula", 15000)
        assertFalse(pending.matches(GuestCommand(GuestCommand.PAUSE)))
        assertFalse(pending.matches(GuestCommand(GuestCommand.PLAY_TRACK, track = Track(id = "other", title = "Other"))))
        assertTrue(pending.matches(GuestCommand(GuestCommand.PLAY_TRACK, track = Track(id = "dracula", title = "Dracula"))))
        assertEquals("tejas", pending.actorId)
    }

    @Test
    fun `delayed seek keeps listener attribution only for requested position`() {
        val pending = PendingRoomAction(GuestCommand(GuestCommand.SEEK, positionMs = 12345), "sharthak", "Sharthak moved the song position", null, 15000)
        assertFalse(pending.matches(GuestCommand(GuestCommand.SEEK, positionMs = 67890)))
        assertTrue(pending.matches(GuestCommand(GuestCommand.SEEK, positionMs = 12345)))
    }

    @Test
    fun `cold play and previous restart retain requesting listener`() {
        val play = PendingRoomAction(GuestCommand(GuestCommand.PLAY), "tejas", "Tejas pressed play", "dracula", 15000)
        assertTrue(play.matches(GuestCommand(GuestCommand.PLAY_TRACK, track = Track(id = "dracula", title = "Dracula"))))
        val previous = PendingRoomAction(GuestCommand(GuestCommand.PREVIOUS), "sharthak", "Sharthak went back", "previous", 15000)
        assertTrue(previous.matches(GuestCommand(GuestCommand.SEEK, positionMs = 0)))
    }
}
