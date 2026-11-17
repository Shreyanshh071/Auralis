package com.auralis.music

import com.auralis.music.data.sync.ListeningStatsClear
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class ListeningStatsClearTest {
    @Test fun refusedAccountClearPreservesLocalHistory() = runBlocking {
        var history = listOf("recorded-listen")
        try {
            ListeningStatsClear.run(true, { throw IOException("offline") }, { history = emptyList() })
            fail("Clear must report the server failure")
        } catch (_: IOException) { }
        assertEquals(listOf("recorded-listen"), history)
    }

    @Test fun localOnlyClearNeverSendsAnAccountRequest() = runBlocking {
        var cleared = false
        ListeningStatsClear.run(false, { error("Must not contact account") }, { cleared = true })
        assertTrue(cleared)
    }
}
