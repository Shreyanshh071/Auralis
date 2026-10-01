package com.auralis.music

import com.auralis.music.data.sync.AccountWriteBarrier
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AccountWriteBarrierTest {
    @Test fun `deletion waits for an upload and queued uploads cannot recreate data`() = runTest {
        val blocked = mutableSetOf<String>()
        val gate = AccountWriteBarrier({ it in blocked }, { blocked.add(it) })
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val upload = async { gate.write("a") { entered.complete(Unit); release.await(); "uploaded" } }
        entered.await()
        val queued = async { gate.write("a") { fail("Queued upload ran"); "bad" } }
        runCurrent()
        val deletion = async { gate.pauseAndDrain("a") }
        runCurrent()
        assertFalse(deletion.isCompleted)
        release.complete(Unit)
        assertEquals("uploaded", upload.await())
        deletion.await()
        assertNull(queued.await())
        assertNull(gate.write("a") { "bad" })
    }
    @Test fun `a pending deletion survives a new barrier instance and failure`() = runTest {
        val persisted = mutableSetOf<String>()
        val first = AccountWriteBarrier({ it in persisted }, { persisted.add(it) })
        first.pauseAndDrain("a")
        val restarted = AccountWriteBarrier({ it in persisted }, { persisted.add(it) })
        assertNull(restarted.write("a") { "bad" })
        assertEquals("other-account", restarted.write("b") { "other-account" })
    }
    @Test fun `a failed upload releases the barrier for deletion`() = runTest {
        val blocked = mutableSetOf<String>()
        val gate = AccountWriteBarrier({ it in blocked }, { blocked.add(it) })
        try { gate.write("a") { throw IllegalStateException("offline") } } catch (_: IllegalStateException) {}
        gate.pauseAndDrain("a")
        assertNull(gate.write("a") { "bad" })
    }
}
