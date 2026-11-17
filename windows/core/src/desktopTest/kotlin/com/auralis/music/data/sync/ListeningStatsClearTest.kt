package com.auralis.music.data.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import java.io.IOException
import kotlin.test.*

class ListeningStatsClearTest {
    @Test fun refusedAccountClearKeepsTheLocalHistory() = runBlocking {
        var local = listOf("recorded-listen")
        assertFailsWith<IOException> {
            ListeningStatsClear.run(true, { throw IOException("offline") }, { local = emptyList() })
        }
        assertEquals(listOf("recorded-listen"), local)
    }

    @Test fun acknowledgedAccountClearRemovesTheLocalCopyAfterward() = runBlocking {
        val actions = mutableListOf<String>()
        ListeningStatsClear.run(true, { actions += "acknowledged" }, { actions += "local-clear" })
        assertEquals(listOf("acknowledged", "local-clear"), actions)
    }

    @Test fun localOnlyClearDoesNotContactTheAccount() = runBlocking {
        var local = listOf("recorded-listen")
        ListeningStatsClear.run(false, { error("Must not contact the account") }, { local = emptyList() })
        assertTrue(local.isEmpty())
    }

    @Test fun cancellationDoesNotRemoveLocalHistory() = runBlocking {
        var cleared = false
        assertFailsWith<CancellationException> {
            ListeningStatsClear.run(true, { throw CancellationException("closed") }, { cleared = true })
        }
        assertFalse(cleared)
    }
}
