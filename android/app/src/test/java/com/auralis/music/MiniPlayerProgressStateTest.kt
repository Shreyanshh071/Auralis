package com.auralis.music

import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import com.auralis.music.ui.player.ProgressState
import org.junit.Assert.assertEquals
import org.junit.Test

class MiniPlayerProgressStateTest {

    @Test
    fun progressCalculation_exactKeyframes() {
        val positionState = mutableStateOf(0L)
        val durationState = mutableStateOf(200_000L) // 200 seconds
        val progressState = ProgressState(positionState, durationState)

        // 0%
        positionState.value = 0L
        assertEquals(0.0f, progressState.progress, 0.001f)

        // 10%
        positionState.value = 20_000L
        assertEquals(0.10f, progressState.progress, 0.001f)

        // 25%
        positionState.value = 50_000L
        assertEquals(0.25f, progressState.progress, 0.001f)

        // 50%
        positionState.value = 100_000L
        assertEquals(0.50f, progressState.progress, 0.001f)

        // 75%
        positionState.value = 150_000L
        assertEquals(0.75f, progressState.progress, 0.001f)

        // 90%
        positionState.value = 180_000L
        assertEquals(0.90f, progressState.progress, 0.001f)

        // 100%
        positionState.value = 200_000L
        assertEquals(1.0f, progressState.progress, 0.001f)
    }

    @Test
    fun progressCalculation_seekingAndSongChange() {
        val positionState = mutableStateOf(100_000L) // 50% initially
        val durationState = mutableStateOf(200_000L)
        val progressState = ProgressState(positionState, durationState)

        // If song is already at 50% when mini player appears, first evaluation is 50%
        assertEquals(0.50f, progressState.progress, 0.001f)

        // Seek to 20% (40,000ms)
        positionState.value = 40_000L
        assertEquals(0.20f, progressState.progress, 0.001f)

        // Change songs: new song duration 300,000ms, reset to 0ms
        durationState.value = 300_000L
        positionState.value = 0L
        assertEquals(0.0f, progressState.progress, 0.001f)

        // Edge case: duration 0L produces 0f without division by zero crash
        durationState.value = 0L
        positionState.value = 50_000L
        assertEquals(0.0f, progressState.progress, 0.001f)
    }
}
