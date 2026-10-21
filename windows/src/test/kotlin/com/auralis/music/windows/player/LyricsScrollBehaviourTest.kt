package com.auralis.music.windows.player

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.unit.dp
import com.auralis.music.domain.model.*
import com.auralis.music.ui.lyrics.*
import com.auralis.music.ui.theme.LocalAppearanceSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class LyricsScrollBehaviourTest {
    private class WheelScene(val rowHeight: Int) : AutoCloseable {
        val list = LazyListState()
        var inputs = 0
        var frame = 0L
        val scene = ImageComposeScene(500,600,coroutineContext=Dispatchers.Unconfined) {
            MaterialTheme {
                LazyColumn(state=list,modifier=Modifier.fillMaxSize().then(lyricsWheelScroll(list) { inputs++ })) {
                    items(100) { Text("Lyric $it",Modifier.height(rowHeight.dp)) }
                }
            }
        }
        init { frames(4) }
        fun position() = list.firstVisibleItemIndex * rowHeight + list.firstVisibleItemScrollOffset
        /** Scene clock in ms; events are stamped with it so gesture timing is deterministic. */
        fun now() = frame * 1000L / 60L
        fun frames(count: Int): List<Int> = List(count) {
            scene.render(frame++ * 16_666_667L).close(); position()
        }
        fun wheel(delta: Float) = scene.sendPointerEvent(PointerEventType.Scroll,Offset(250f,250f),
            scrollDelta=Offset(0f,delta),timeMillis=now(),type=PointerType.Mouse)
        /** [count] events of [delta], [gapFrames] frames apart, then settles; returns the travel. */
        fun gesture(delta: Float, count: Int, gapFrames: Int, settle: Int = 60): Int {
            val start = position()
            repeat(count) { wheel(delta); frames(gapFrames) }
            frames(settle)
            return position() - start
        }
        /** Frames after the last input until the list stops moving. */
        fun framesToSettle(): Int {
            var last = position(); var still = 0; var n = 0
            while (still < 3 && n < 300) {
                frames(1); n++
                if (position() == last) still++ else { still = 0; last = position() }
            }
            return n - still
        }
        override fun close() = scene.close()
    }

    @Test fun wheelTravelIsAnimatedAndIndependentOfLyricRowHeight() {
        val ends = listOf(44,132).map { height -> WheelScene(height).use { fixture ->
            fixture.wheel(1f)
            val positions = fixture.frames(45)
            assertTrue(positions.distinct().size > 5,"Wheel movement needs visible intermediate frames: $positions")
            assertTrue(positions.take(2).any { it in 1..40 },"A notch cannot land immediately")
            assertEquals(1,fixture.inputs,"The same wheel event must not reach two scroll handlers")
            assertTrue(fixture.position() in 47..49,"A notch has bounded pixel travel, not item-based travel")
            fixture.position()
        } }
        assertEquals(ends[0],ends[1],"Short line-synced and tall word-synced rows must scroll at the same speed")
    }

    @Test fun slowNotchesAddUpLinearly() {
        WheelScene(60).use { fixture ->
            val one = fixture.gesture(1f,1,20)
            val three = fixture.gesture(1f,3,10) // 167ms apart: no acceleration
            val ten = fixture.gesture(1f,10,10)
            assertTrue(one in 47..49,"one=$one")
            assertTrue(three in 142..146,"Three deliberate notches travel three times as far: $three")
            assertTrue(ten in 475..485,"Ten deliberate notches travel ten times as far: $ten")
        }
    }

    @Test fun fasterSpinsTravelFartherThanTheSameNotchesSpunSlowly() {
        val slow = WheelScene(60).use { it.gesture(1f,12,8) }   // 133ms apart
        val brisk = WheelScene(60).use { it.gesture(1f,12,3) }  // 50ms apart
        val rapid = WheelScene(60).use { it.gesture(1f,12,1) }  // 17ms apart
        assertTrue(slow in 570..580,"slow=$slow")
        assertTrue(brisk > slow*1.3f,"Brisk spins move farther: slow=$slow brisk=$brisk")
        assertTrue(rapid > brisk*1.2f,"Rapid spins move farther still: brisk=$brisk rapid=$rapid")
        // A large burst is no longer flattened to the same half-screen as a small one.
        val small = WheelScene(60).use { it.gesture(1f,2,1) }
        val large = WheelScene(60).use { it.gesture(1f,20,1) }
        assertTrue(large > small*8,"small=$small large=$large")
        // Big per-event deltas (hyper-scroll wheels) are not clamped either.
        val hyper = WheelScene(60).use { it.gesture(6f,1,1) }
        assertTrue(hyper in 285..291,"A 6-notch event travels six notches: $hyper")
    }

    @Test fun touchpadDeltasAreProportionalAndNeverAccelerated() {
        WheelScene(60).use { fixture ->
            val light = fixture.gesture(.1f,20,1)
            val firm = fixture.gesture(.3f,20,1)
            val fine = fixture.gesture(.02f,10,1)
            assertTrue(light in 94..98,"20 x 0.1 notch = 96px: $light")
            assertTrue(firm in 285..291,"20 x 0.3 notch = 288px: $firm")
            assertTrue(fine in 9..10,"Tiny touchpad input still moves a little: $fine")
            // A whole-number delta inside a touchpad gesture does not switch to wheel acceleration.
            val mixed = fixture.gesture(.5f,1,1,0) + fixture.gesture(1f,6,1)
            assertTrue(mixed in 310..314,"0.5 + 6 x 1 notch = 312px, unaccelerated: $mixed")
        }
    }

    @Test fun motionStopsPromptlyAfterInputEnds() {
        WheelScene(60).use { fixture ->
            val start = fixture.position()
            repeat(20) { fixture.wheel(1f); fixture.frames(1) }
            val atRelease = fixture.position()
            fixture.frames(15)
            val shortlyAfter = fixture.position()
            val wheelTail = fixture.framesToSettle() + 15
            val total = fixture.position() - start
            assertTrue(shortlyAfter - start >= total * .95f,
                "95% of a rapid spin's travel is done 250ms after the last notch: ${atRelease-start}/${shortlyAfter-start}/$total")
            assertTrue(wheelTail <= 36,"and it is fully still by ~0.6s: $wheelTail")
            repeat(30) { fixture.wheel(.25f); fixture.frames(1) }
            val padTail = fixture.framesToSettle()
            assertTrue(padTail <= 10,"Touchpad motion follows the fingers without queued travel: $padTail")
            assertFalse(fixture.list.isScrollInProgress)
        }
    }

    @Test fun reversingRespondsAtOnceWithoutStoredMomentum() {
        WheelScene(60).use { fixture ->
            repeat(12) { fixture.wheel(3f); fixture.frames(1) }
            val beforeReverse = fixture.position()
            fixture.wheel(-1f)
            val after = fixture.frames(3)
            assertTrue(after[0] <= beforeReverse,"Reversing discards pending forward travel at once: $beforeReverse -> $after")
            assertTrue(after[2] < beforeReverse,"and moves the other way within a few frames: $beforeReverse -> $after")
            fixture.frames(30)
            assertTrue(fixture.gesture(-1f,1,1) in -49..-47,"A fresh notch after a reversal is not accelerated")
            // Touchpad reversal mid-swipe.
            repeat(10) { fixture.wheel(.4f); fixture.frames(1) }
            val swipe = fixture.position()
            fixture.wheel(-.2f)
            val back = fixture.frames(2)
            assertTrue(back.last() < swipe,"Touchpad reversal: $swipe -> $back")
            fixture.frames(30)
            runBlocking { fixture.list.scrollToItem(99) }
            fixture.frames(3)
            fixture.wheel(100f); fixture.frames(30)
            val bottom = fixture.position()
            fixture.frames(20)
            fixture.wheel(-1f); fixture.frames(45)
            assertTrue(fixture.position() < bottom-40,"The end cannot accumulate momentum that swallows a reversal")
            assertFalse(fixture.list.isScrollInProgress)
        }
    }

    @Test fun actualLineAndWordSyncedViewsKeepWheelMotionSeparateFromFollowing() {
        for (type in listOf(SyncType.LINE_SYNC,SyncType.RICHSYNC)) {
            val position = mutableLongStateOf(60000L)
            var list: LazyListState? = null
            var inputCount = 0
            val ui = LyricsUiPlatform(DefaultLyricsUi.strings, roundedMouseHover=true, manualScrollModifier={ state,onUser ->
                list = state
                lyricsWheelScroll(state) { inputCount++; onUser() }
            })
            val lines = List(80) { i -> LyricLine(i*2000L,"Line $i in this song",words=if(type==SyncType.RICHSYNC)
                listOf(LyricWord("Line $i ",i*2000L,900),LyricWord("in this song",i*2000L+900,1000)) else null) }
            val lyrics = LyricsData(type,lines,provider=LyricsProvider.AMLL)
            val scene = ImageComposeScene(620,600,coroutineContext=Dispatchers.Unconfined) {
                MaterialTheme { CompositionLocalProvider(LocalLyricsUi provides ui,
                    LocalAppearanceSettings provides AppearanceSettings(lyricsTextSize=24f)) {
                    ExperimentalLyricsView(lyrics,position,{},isPlaying=false,isBuffering=false,track=Track("scroll-fixture","Fixture","Artist",duration=160))
                } }
            }
            try {
                var frame=0L
                fun frames(count:Int) = repeat(count) { scene.render(frame++*16_666_667L).close() }
                frames(50)
                val state=assertNotNull(list)
                val reference=state.layoutInfo.visibleItemsInfo.last().index
                val before=state.layoutInfo.visibleItemsInfo.first { it.index==reference }.offset
                fun offset() = state.layoutInfo.visibleItemsInfo.firstOrNull { it.index==reference }?.offset
                // Following resumes 1.5s (wall clock) after scrolling stops, so measure as the wheel lands.
                scene.sendPointerEvent(PointerEventType.Scroll,Offset(310f,300f),scrollDelta=Offset(0f,1f),type=PointerType.Mouse)
                frames(25)
                val after=assertNotNull(offset())
                assertTrue(before-after in 45..51,"$type must retain the controlled wheel distance: ${before-after}")
                assertEquals(1,inputCount)
                // Playback moving to new lines mid-gesture must not pull against the wheel:
                // three deliberate notches move exactly three notches, always forward.
                var clock=frame*1000L/60L
                val trace=mutableListOf<Int?>()
                repeat(3) { notch ->
                    scene.sendPointerEvent(PointerEventType.Scroll,Offset(310f,300f),scrollDelta=Offset(0f,1f),
                        timeMillis=clock,type=PointerType.Mouse)
                    if (notch==1) position.longValue=64000L
                    repeat(10) { frames(1); trace+=offset() }
                    clock+=167L
                }
                repeat(20) { frames(1); trace+=offset() }
                val visible=trace.filterNotNull()
                assertTrue(visible.zipWithNext().all { (a,b) -> b<=a },"$type: following moved against the wheel: $trace")
                assertTrue(after-visible.last() in 140..148,"$type: three notches = 144px, got ${after-visible.last()}")
            } finally { scene.close() }
        }
    }

    @Test fun interruptingAWheelGestureDoesNotDisableFutureWheelInput() {
        WheelScene(60).use { fixture ->
            fixture.wheel(2f); fixture.frames(3)
            runBlocking { fixture.list.scroll(androidx.compose.foundation.MutatePriority.UserInput) { scrollBy(1200f) } }
            fixture.frames(20)
            val before=fixture.position()
            fixture.wheel(1f); fixture.frames(45)
            assertTrue(fixture.position()-before in 47..49)
        }
    }

    @Test fun fineWheelDeltasAccumulateInsteadOfBeingDiscarded() {
        WheelScene(60).use { fixture ->
            repeat(40) { fixture.wheel(.005f); fixture.frames(1) }
            fixture.frames(45)
            assertTrue(fixture.position() in 8..11,"Fine wheel input must still move the lyrics: ${fixture.position()}")
        }
    }

    @Test fun smallAutomaticCorrectionsHaveVisibleIntermediateFrames() {
        val position=mutableLongStateOf(30000L)
        val follow=mutableStateOf(true)
        var list:LazyListState?=null
        val ui=LyricsUiPlatform(DefaultLyricsUi.strings,manualScrollModifier={ state,_ -> list=state;Modifier })
        val lyrics=LyricsData(SyncType.LINE_SYNC,List(80) { LyricLine(it*2000L,"Short line $it") },provider=LyricsProvider.AMLL)
        val scene=ImageComposeScene(500,400,coroutineContext=Dispatchers.Unconfined) {
            MaterialTheme { CompositionLocalProvider(LocalLyricsUi provides ui,
                LocalAppearanceSettings provides AppearanceSettings(lyricsTextSize=14f,autoScrollLyrics=follow.value)) {
                ExperimentalLyricsView(lyrics,position,{},isPlaying=false,isBuffering=false,track=Track("follow-fixture","Fixture","Artist",duration=160))
            } }
        }
        try {
            var frame=0L
            fun render() { scene.render(frame++*16_666_667L).close() }
            repeat(70) { render() }
            val state=assertNotNull(list)
            follow.value=false
            repeat(4) { render() }
            runBlocking { state.scroll { scrollBy(24f) } }
            repeat(4) { render() }
            val reference=state.layoutInfo.visibleItemsInfo.last().index
            val before=state.layoutInfo.visibleItemsInfo.first { it.index==reference }.offset
            follow.value=true
            val offsets=List(40) { render();state.layoutInfo.visibleItemsInfo.firstOrNull { it.index==reference }?.offset }
            assertTrue(offsets.filterNotNull().distinct().size>8,"Even sub-48px following must animate: $offsets")
            val distance=before-assertNotNull(offsets.last())
            assertTrue(kotlin.math.abs(distance) in 4..47,"Correction must stay below the old 48px snap threshold: $distance; offsets=$offsets")
        } finally { scene.close() }
    }
}
