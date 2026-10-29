package com.auralis.music

import com.auralis.music.ui.viewmodel.HomeBackgroundWork
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeBackgroundWorkTest {
    @Test fun optionalWorkWaitsForStartupAndTheEndOfScrolling() = runTest {
        val work = HomeBackgroundWork(true)
        var started = false
        launch { work.awaitStartup(); started = true }
        advanceTimeBy(2_499)
        assertFalse(started)
        work.onScrollChanged(true)
        advanceTimeBy(5_000)
        assertFalse(started)
        work.onScrollChanged(false)
        advanceTimeBy(599)
        assertFalse(started)
        advanceUntilIdle()
        assertTrue(started)
    }

    @Test fun scrollingAgainDuringTheQuietPeriodPostponesWork() = runTest {
        val work = HomeBackgroundWork(true)
        var started = false
        launch { work.awaitIdle(); started = true }
        advanceTimeBy(300)
        work.onScrollChanged(true)
        advanceTimeBy(1_000)
        assertFalse(started)
        work.onScrollChanged(false)
        advanceUntilIdle()
        assertTrue(started)
    }
}
