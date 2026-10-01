
package com.auralis.music

import android.content.Context
import android.content.SharedPreferences
import com.auralis.music.data.sync.RoomCleanupPreferences
import com.auralis.music.data.sync.RoomCleanupTask
import com.auralis.music.data.sync.roomCleanupStillMatches
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Test

class RoomCleanupPersistenceTest {
    private fun context(values: MutableMap<String, String?>, commit: Boolean = true): Context {
        val context = mockk<Context>()
        val prefs = mockk<SharedPreferences>()
        val editor = mockk<SharedPreferences.Editor>()
        val pending = mutableMapOf<String, String?>()
        every { context.getSharedPreferences(any(), any()) } returns prefs
        every { prefs.getString(any(), any()) } answers { values[firstArg<String>()] ?: secondArg<String?>() }
        every { prefs.edit() } returns editor
        every { editor.putString(any(), any()) } answers { pending[firstArg<String>()] = secondArg<String?>(); editor }
        every { editor.commit() } answers { if (commit) { values.putAll(pending); pending.clear() }; commit }
        return context
    }
    @Test fun `pending exit survives a new store and keeps exact UID room and session`() {
        val values = mutableMapOf<String, String?>()
        val first = RoomCleanupPreferences(context(values))
        val session = RoomCleanupTask(uid = "host", code = "ROOM", host = true, joinedAt = 123)
        first.saveActive(session)
        assertEquals(session, first.enqueue("host", "ROOM", true))
        val restarted = RoomCleanupPreferences(context(values))
        assertNull(restarted.active())
        assertEquals(listOf(session), restarted.pending())
        restarted.remove(session.id)
        assertTrue(RoomCleanupPreferences(context(values)).pending().isEmpty())
    }
    @Test fun `duplicate exit saves one retry and another account cannot discard it`() {
        val values = mutableMapOf<String, String?>()
        val store = RoomCleanupPreferences(context(values))
        val first = store.enqueue("a", "ROOM", false)
        assertEquals(first.id, store.enqueue("a", "ROOM", false).id)
        store.enqueue("b", "OTHER", true)
        store.forget("b")
        assertEquals(listOf(first), store.pending())
    }
    @Test fun `failed persistence throws before cleanup can start`() {
        val store = RoomCleanupPreferences(context(mutableMapOf(), commit = false))
        var failed = false
        try { store.enqueue("a", "ROOM", true) } catch (_: IllegalStateException) { failed = true }
        assertTrue(failed)
    }
    @Test fun `session identity protects rejoined guests and reused hosted rooms`() {
        val host = RoomCleanupTask(uid = "a", code = "ROOM", host = true, joinedAt = 100)
        assertFalse(roomCleanupStillMatches(host, true, "other", true, 100))
        assertFalse(roomCleanupStillMatches(host, true, "a", true, 200))
        val guest = host.copy(host = false)
        assertFalse(roomCleanupStillMatches(guest, true, "other", true, 200))
        assertTrue(roomCleanupStillMatches(host, false, null, false, null))
        assertTrue(roomCleanupStillMatches(guest, true, "other", true, 100))
    }
}
