
package com.auralis.music

import com.auralis.music.data.sync.AccountDeletionActions
import com.auralis.music.data.sync.deleteAccountOnSpark
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class SparkAccountDeletionTest {
    private class Fake(val failAt: String? = null) : AccountDeletionActions {
        val calls = mutableListOf<String>()
        private fun step(name: String) { calls += name; if (name == failAt) error("interrupted") }
        override suspend fun blockRemoteWrites() = step("block")
        override suspend fun stopRoomSession() = step("stop")
        override suspend fun clearRooms() = step("rooms")
        override suspend fun clearLibrary() = step("library")
        override suspend fun verifyCleanup() = step("verify")
        override suspend fun deleteAuth() = step("auth")
    }
    @Test fun `block uploads and verify cloud cleanup before deleting Auth`() = runTest {
        val steps = Fake()
        deleteAccountOnSpark(steps)
        assertEquals(listOf("block", "stop", "rooms", "library", "verify", "auth"), steps.calls)
    }
    @Test fun `every partial failure stops success and permits a complete retry`() = runTest {
        for (stage in listOf("block", "stop", "rooms", "library", "verify", "auth")) {
            val steps = Fake(stage)
            var failed = false
            try { deleteAccountOnSpark(steps) } catch (_: IllegalStateException) { failed = true }
            assertTrue(stage, failed)
            assertEquals(stage, steps.calls.last())
            if (stage != "auth") assertFalse(steps.calls.contains("auth"))
            val retry = Fake()
            deleteAccountOnSpark(retry)
            assertEquals("auth", retry.calls.last())
        }
    }
}
