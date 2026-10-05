package com.auralis.music

import com.auralis.music.data.service.awaitNativeDuringWebStartup
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PlaybackStartupRaceTest {
    @Test fun `ready native audio can replace a web page still loading`() = runTest {
        val stream = CompletableDeferred<String?>()
        val web = MutableStateFlow(false)
        val result = async { awaitNativeDuringWebStartup(stream, web) }
        runCurrent()
        assertFalse(result.isCompleted)
        stream.complete("https://example.com/audio")
        assertEquals("https://example.com/audio", result.await())
    }

    @Test fun `once web audio starts later extraction only populates cache`() = runTest {
        val stream = CompletableDeferred<String?>()
        val web = MutableStateFlow(false)
        val result = async { awaitNativeDuringWebStartup(stream, web) }
        runCurrent()
        web.value = true
        runCurrent()
        assertNull(result.await())
        assertFalse(stream.isCancelled)
        web.value = false
        stream.complete("https://example.com/audio")
        assertNull(result.await())
    }

    @Test fun `failed extraction leaves web startup running`() = runTest {
        val failed = CompletableDeferred<String?>().apply { complete(null) }
        assertNull(awaitNativeDuringWebStartup(failed, MutableStateFlow(false)))
    }

    @Test fun `already playing web audio wins even if native stream is also ready`() = runTest {
        val ready = CompletableDeferred<String?>().apply { complete("https://example.com/audio") }
        assertNull(awaitNativeDuringWebStartup(ready, MutableStateFlow(true)))
    }

    @Test fun `cancelling a skipped track stops waiting without starting its native audio`() = runTest {
        val stream = CompletableDeferred<String?>()
        val result = async { awaitNativeDuringWebStartup(stream, MutableStateFlow(false)) }
        runCurrent()
        result.cancel()
        result.join()
        assertTrue(result.isCancelled)
        stream.complete("https://example.com/old-song")
    }
}
