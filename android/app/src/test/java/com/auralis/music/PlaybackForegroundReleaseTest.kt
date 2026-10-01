package com.auralis.music

import com.auralis.music.service.PlaybackForegroundRelease
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackForegroundReleaseTest {
    @Test
    fun `stream replacement idle callback cannot demote resumed audio`() = runTest {
        var playing = false
        var released = 0
        val release = PlaybackForegroundRelease(this, { playing }, { released++ })
        release.schedule()
        runCurrent()
        playing = true
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(0, released)
    }

    @Test
    fun `buffering after idle retains foreground even before audio starts`() = runTest {
        var buffering = false
        var released = 0
        val release = PlaybackForegroundRelease(this, { buffering }, { released++ })
        release.schedule()
        runCurrent()
        buffering = true
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(0, released)
    }

    @Test
    fun `real pause releases foreground after settling exactly once`() = runTest {
        var released = 0
        val release = PlaybackForegroundRelease(this, { false }, { released++ })
        release.schedule()
        runCurrent()
        advanceTimeBy(999)
        runCurrent()
        assertEquals(0, released)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, released)
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(1, released)
    }

    @Test
    fun `resume cancels pending foreground release`() = runTest {
        var released = 0
        val release = PlaybackForegroundRelease(this, { false }, { released++ })
        release.schedule()
        runCurrent()
        advanceTimeBy(500)
        release.cancel()
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(0, released)
    }

    @Test
    fun `repeated idle notifications do not postpone or duplicate release`() = runTest {
        var released = 0
        val release = PlaybackForegroundRelease(this, { false }, { released++ })
        release.schedule()
        runCurrent()
        advanceTimeBy(500)
        release.schedule()
        advanceTimeBy(500)
        runCurrent()
        assertEquals(1, released)
    }

    @Test
    fun `listen together room keeps paused service foreground`() = runTest {
        var inRoom = false
        var released = 0
        val release = PlaybackForegroundRelease(this, { inRoom }, { released++ })
        release.schedule()
        runCurrent()
        inRoom = true
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(0, released)
    }

    @Test
    fun `service scope cancellation discards pending release`() = runTest {
        val parent = kotlinx.coroutines.Job()
        val scope = kotlinx.coroutines.CoroutineScope(coroutineContext + parent)
        var released = 0
        val release = PlaybackForegroundRelease(scope, { false }, { released++ })
        release.schedule()
        runCurrent()
        parent.cancel()
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(0, released)
    }
}
