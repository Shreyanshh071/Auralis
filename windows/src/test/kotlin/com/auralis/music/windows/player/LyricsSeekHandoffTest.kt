package com.auralis.music.windows.player

import com.auralis.music.desktop.playback.PlaybackSeekGate
import com.auralis.music.ui.lyrics.ExperimentalPendingSeekTarget
import com.auralis.music.ui.lyrics.hasReached
import kotlin.test.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.*
import com.auralis.music.data.service.PlaybackClockSource
import com.auralis.music.domain.model.*
import com.auralis.music.ui.lyrics.*
import com.auralis.music.ui.theme.LocalAppearanceSettings
import kotlinx.coroutines.*
import kotlinx.coroutines.swing.Swing

class LyricsSeekHandoffTest {
    @Test fun clickingALyricKeepsTheTargetThenFollowsAudioPastTheFirstReport() = runBlocking(Dispatchers.Swing) {
        var raw = 5000L
        val clock = object : PlaybackClockSource {
            override fun rawPositionMs() = raw
            override fun isPlaying() = true
            override fun speed() = 1f
        }
        val position = mutableLongStateOf(raw)
        var list: LazyListState? = null
        var requested = -1L
        val ui = LyricsUiPlatform(DefaultLyricsUi.strings,
            lyricSelectionEnabled = DesktopLyricsUi.lyricSelectionEnabled,
            manualScrollModifier = { state, onInput ->
            list = state
            lyricsWheelScroll(state, onInput)
        })
        val data = LyricsData(SyncType.RICHSYNC, List(50) { i ->
            LyricLine(i * 500L, "Phrase $i", words = listOf(LyricWord("Phrase $i", i * 500L, 500L)))
        }, provider = LyricsProvider.AMLL)
        val scene = ImageComposeScene(500, 400, coroutineContext = Dispatchers.Swing) {
            MaterialTheme { CompositionLocalProvider(LocalLyricsUi provides ui,
                LocalAppearanceSettings provides AppearanceSettings(lyricsTextSize = 22f,
                    lyricsAnimation = LyricsAnimationMode.SPICY_LYRICS.displayName, autoScrollLyrics = true)) {
                Box(Modifier.fillMaxSize().background(Color(0xFF243447))) {
                    ExperimentalLyricsView(data, position, { requested = it }, isPlaying = true,
                        isBuffering = false, lyricsClockSource = clock,
                        track = Track("seek-handoff", "Fixture", "Artist", duration = 25))
                }
            } }
        }
        try {
            suspend fun frames(count: Int) = repeat(count) {
                scene.render(System.nanoTime()).close()
                // rememberCoroutineScope's desktop scroll animation also uses
                // the Swing frame clock. Advance real UI time alongside scene time.
                delay(16L)
            }
            frames(70)
            val state = assertNotNull(list)
            fun lineCenter(index: Int): Float {
                val item = state.layoutInfo.visibleItemsInfo.first { it.index == index }
                return item.offset + item.size / 2f
            }
            val originalAnchor = lineCenter(10)
            val clicked = state.layoutInfo.visibleItemsInfo.first {
                val screenTop = it.offset - state.layoutInfo.viewportStartOffset
                it.index > 10 && screenTop > 0 && screenTop + it.size < 350
            }
            val point = Offset(250f, clicked.offset - state.layoutInfo.viewportStartOffset + clicked.size / 2f)
            scene.sendPointerEvent(PointerEventType.Press, point)
            scene.sendPointerEvent(PointerEventType.Release, point)
            // Supply decoder confirmation before the six-second failed-seek
            // recovery deadline. Sixty software-rendered frames can exceed it
            // on a loaded machine, correctly returning to the old audio position.
            frames(4)
            assertEquals(clicked.index * 500L, requested)
            delay(550L)
            raw = requested + 800L; position.longValue = raw; frames(2)
            raw += 16L; position.longValue = raw; frames(80)
            assertTrue(kotlin.math.abs(lineCenter(clicked.index + 1) - originalAnchor) < 5f,
                "The next decoded lyric must take over immediately instead of leaving the clicked line pinned: anchor=$originalAnchor, center=${lineCenter(clicked.index + 1)}")
        } finally { scene.close() }
    }

    @Test fun oldReportsCannotUndoForwardOrBackwardSeeks() {
        for ((old, target) in listOf(10_000L to 80_000L, 80_000L to 10_000L)) {
            val gate = PlaybackSeekGate()
            gate.request(target, 0L)
            assertFalse(gate.accept(old + 100, 100_000_000L, 1f))
            assertTrue(gate.isPending())
            assertTrue(gate.accept(target + 650, 700_000_000L, 1f), "Delayed first report must resume the audio clock")
            assertFalse(gate.isPending())
        }
    }

    @Test fun rapidClicksOnlyDispatchTheLatestQueuedNativeSeek() {
        val gate = PlaybackSeekGate()
        val requests = listOf(100_000L, 20_000L, 150_000L).mapIndexed { i, target ->
            gate.request(target, i * 100_000_000L)
        }
        assertEquals(listOf(150_000L), requests.filter(gate::isCurrent).map { it.targetMs })
        assertFalse(gate.accept(20_050L, 400_000_000L, 1f))
        assertTrue(gate.accept(150_100L, 500_000_000L, 1f))
    }

    @Test fun trackChangeInvalidatesQueuedSeeksAndFailedSeeksRecover() {
        val gate = PlaybackSeekGate()
        val first = gate.request(60_000L, 0L)
        gate.reset()
        assertFalse(gate.isCurrent(first))
        assertTrue(gate.accept(0L, 1L, 1f))
        gate.request(90_000L, 0L)
        assertFalse(gate.accept(10_000L, 5_000_000_000L, 1f))
        assertTrue(gate.accept(11_000L, 6_000_000_000L, 1f))
        assertFalse(gate.isPending())
    }

    @Test fun highlightHandsOffWhenFirstAudioReportIsBeyondTheOld350msWindow() {
        val pending = ExperimentalPendingSeekTarget(20, 40_000L, 1L, timestamp = 1000L)
        assertFalse(pending.hasReached(10_000L, 1100L, true), "A stale position cannot cancel the pending lyric")
        assertTrue(pending.hasReached(40_800L, 1800L, true), "Audio must take over instead of freezing the highlight for 1.5 seconds")
        assertTrue(pending.hasReached(41_500L, 2000L, true), "Playback at 2x must converge too")
        assertFalse(pending.hasReached(50_000L, 1200L, true))
    }

    @Test fun pausedSeekConvergesWithoutWaitingForForwardMotion() {
        val pending = ExperimentalPendingSeekTarget(20, 40_000L, 1L, timestamp = 1000L)
        assertTrue(pending.hasReached(40_000L, 1000L, false))
        assertFalse(pending.hasReached(40_800L, 1800L, false))
    }
}
