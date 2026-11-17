package com.auralis.music.windows.player

import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import com.auralis.music.data.service.PlaybackClockSource
import com.auralis.music.ui.lyrics.rememberLyricsClock
import com.auralis.music.ui.player.LocalPlaybackVisualsActive
import kotlinx.coroutines.*
import kotlin.test.*

class PlaybackVisualsTest {
    @Test fun hiddenLyricsStopSamplingAndResumeAtCurrentAudioPosition() = runBlocking {
        val frames = BroadcastFrameClock()
        val recomposer = Recomposer(coroutineContext + frames)
        val composition = Composition(object : AbstractApplier<Unit>(Unit) {
            override fun insertTopDown(index: Int, instance: Unit) {}
            override fun insertBottomUp(index: Int, instance: Unit) {}
            override fun remove(index: Int, count: Int) {}
            override fun move(from: Int, to: Int, count: Int) {}
            override fun onClear() {}
        }, recomposer)
        val runner = launch(frames) { recomposer.runRecomposeAndApplyChanges() }
        val visible = mutableStateOf(true)
        val coarse = mutableLongStateOf(1_000L)
        var raw = 1_000L
        var samples = 0
        val source = object : PlaybackClockSource {
            override fun rawPositionMs(): Long { samples++; return raw }
            override fun isPlaying() = true
            override fun speed() = 1f
        }
        var shown: State<Long>? = null
        var frameTime = 0L
        suspend fun settle() {
            repeat(12) {
                Snapshot.sendApplyNotifications()
                yield()
                frameTime += 16_666_667L
                frames.sendFrame(frameTime)
                yield()
            }
        }
        try {
            composition.setContent {
                CompositionLocalProvider(LocalPlaybackVisualsActive provides visible.value) {
                    shown = rememberLyricsClock(source, true, coarse)
                }
            }
            settle()
            assertTrue(samples > 0)
            visible.value = false
            settle()
            val hiddenSamples = samples
            val hiddenPosition = shown!!.value
            raw = 9_000L
            coarse.longValue = raw
            settle()
            assertEquals(hiddenSamples, samples, "Covered/minimized lyrics must stop reading the audio clock")
            assertEquals(hiddenPosition, shown!!.value, "Hidden lyrics must not invalidate drawing")
            visible.value = true
            settle()
            assertTrue(samples > hiddenSamples)
            assertEquals(raw, shown!!.value, "Restoring the view must pick up the current audio position")
        } finally {
            composition.dispose()
            recomposer.cancel()
            runner.cancelAndJoin()
        }
    }
}
