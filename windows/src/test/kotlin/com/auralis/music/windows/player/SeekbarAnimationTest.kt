package com.auralis.music.windows.player

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.auralis.music.ui.components.AuralisPlayerSlider
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.EncodedImageFormat
import kotlin.test.*

class SeekbarAnimationTest {
    @Test fun seekingUsesTheNewSongsDurationAfterRecomposition() {
        val duration = mutableStateOf(0L)
        var seek = -1L
        var fraction = 0f
        val scene = ImageComposeScene(440,80,coroutineContext=Dispatchers.Unconfined) {
            val currentDuration = duration.value
            AuralisPlayerSlider(.2f,{ fraction=it },{ seek=(fraction*currentDuration).toLong() },false,0,currentDuration,
                modifier=Modifier.width(440.dp),sliderStyle="Slim",showTimestamps=false)
        }
        try {
            scene.render(0).close()
            duration.value=240_000L
            repeat(3) { scene.render((it+1)*16_666_667L).close() }
            scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Press,androidx.compose.ui.geometry.Offset(220f,22f))
            scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Release,androidx.compose.ui.geometry.Offset(220f,22f))
            assertEquals(120_000L,seek,"Seeking must use the loaded song's duration rather than the initial zero duration")
        } finally { scene.close() }
    }
    @Test fun bothPhoneWaveStylesMoveWhilePlayingAndFlattenWhenPaused() {
        for(style in listOf("Wavy","Squiggly")) {
            val playing = mutableStateOf(true)
            val scene = ImageComposeScene(440,80,coroutineContext=Dispatchers.Unconfined) {
                Box(Modifier.width(440.dp)) {
                    AuralisPlayerSlider(.55f,{}, {},playing.value,550,1000,sliderStyle=style,showTimestamps=false)
                }
            }
            try {
                var frame = 0L
                fun render(): ByteArray {
                    repeat(35) { scene.render(frame++ * 16_666_667).close() }
                    return scene.render(frame++ * 16_666_667).use { it.encodeToData(EncodedImageFormat.PNG)!!.use { it.bytes } }
                }
                val first=render(); val second=render()
                assertFalse(first.contentEquals(second), "$style must animate during playback")
                playing.value=false
                render()
                val paused=render()
                assertTrue(paused.contentEquals(render()), "$style must stop and flatten while paused")
            } finally { scene.close() }
        }
    }
}
