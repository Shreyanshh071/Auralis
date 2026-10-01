
package com.auralis.music

import com.auralis.music.data.sync.RoomCleanupActions
import com.auralis.music.data.sync.cleanDepartedRoom
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class RoomCleanupTest {
    private class Actions(val failAt: String? = null, val current: Boolean = true) : RoomCleanupActions {
        val calls = mutableListOf<String>()
        private fun step(name: String) { calls += name; if (name == failAt) error("offline") }
        override suspend fun sessionIsCurrent(): Boolean { calls += "session"; return current }
        override suspend fun closeRoom() = step("close")
        override suspend fun removeRecommendations() = step("requests")
        override suspend fun verifyRecommendationsRemoved() = step("verify-requests")
        override suspend fun removeRoom() = step("room")
        override suspend fun removeVotes() = step("votes")
        override suspend fun verifyVotesRemoved() = step("verify-votes")
        override suspend fun removeMembers() = step("members")
        override suspend fun verifyMembersRemoved() = step("verify-members")
    }
    @Test fun `host closes first and confirms request deletion before removing room`() = runTest {
        val actions = Actions()
        cleanDepartedRoom(true, actions)
        assertEquals(listOf("session", "close", "requests", "verify-requests", "room", "members", "verify-members"), actions.calls)
    }
    @Test fun `guest keeps requests and room and removes only votes and membership`() = runTest {
        val actions = Actions()
        cleanDepartedRoom(false, actions)
        assertEquals(listOf("session", "votes", "verify-votes", "members", "verify-members"), actions.calls)
    }
    @Test fun `old cleanup never changes a new session`() = runTest {
        for (host in listOf(true, false)) {
            val actions = Actions(current = false)
            cleanDepartedRoom(host, actions)
            assertEquals(listOf("session"), actions.calls)
        }
    }
    @Test fun `failed request cleanup never deletes its parent or loses permission to retry`() = runTest {
        for (stage in listOf("close", "requests", "verify-requests")) {
            val actions = Actions(stage)
            var failed = false
            try { cleanDepartedRoom(true, actions) } catch (_: IllegalStateException) { failed = true }
            assertTrue(failed)
            assertFalse(actions.calls.contains("room"))
        }
    }
    @Test fun `partial host failure does not report completion and retry runs every required step`() = runTest {
        for (stage in listOf("room", "members", "verify-members")) {
            val actions = Actions(stage)
            var failed = false
            try { cleanDepartedRoom(true, actions) } catch (_: IllegalStateException) { failed = true }
            assertTrue(failed)
            val retry = Actions()
            cleanDepartedRoom(true, retry)
            assertEquals("verify-members", retry.calls.last())
        }
    }
    @Test fun `guest remains discoverable until votes have been verified removed`() = runTest {
        val actions = Actions("verify-votes")
        try { cleanDepartedRoom(false, actions) } catch (_: IllegalStateException) {}
        assertFalse(actions.calls.contains("members"))
    }
}
