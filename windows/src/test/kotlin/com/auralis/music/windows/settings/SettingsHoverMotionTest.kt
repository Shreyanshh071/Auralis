package com.auralis.music.windows.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.HoverInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.auralis.music.ui.components.SmoothHoverIndication
import kotlinx.coroutines.*
import org.jetbrains.skia.Bitmap
import kotlin.test.*

class SettingsHoverMotionTest {
    @Test fun hoverEntryExitAndReversalUseIntermediateFrames() = runBlocking {
        val source = MutableInteractionSource()
        val scene = ImageComposeScene(100, 100, coroutineContext = Dispatchers.Unconfined) {
            MaterialTheme(colorScheme = darkColorScheme()) {
                CompositionLocalProvider(LocalContentColor provides Color.White) {
                    Box(Modifier.fillMaxSize().background(Color.Black).indication(source, SmoothHoverIndication))
                }
            }
        }
        try {
            var time = 0L
            fun frame(): Int = scene.render(time.also { time += 16_666_667L }).use { image ->
                Bitmap.makeFromImage(image).use { it.getColor(50, 50) and 255 }
            }
            repeat(4) { frame() }
            val enter = HoverInteraction.Enter()
            source.emit(enter)
            val entry = List(18) { frame() }
            assertTrue(entry.distinct().size > 5, "Hover must fade in across frames: $entry")
            assertTrue(entry.zipWithNext().all { (a, b) -> b >= a })
            source.emit(HoverInteraction.Exit(enter))
            val exit = List(5) { frame() }
            assertTrue(exit.last() < entry.last(), "Hover exit must begin fading")
            val second = HoverInteraction.Enter()
            source.emit(second)
            val reversal = List(18) { frame() }
            assertTrue(kotlin.math.abs(reversal.first() - exit.last()) < 5, "Reversal must continue from the current highlight")
            assertTrue(reversal.last() > reversal.first())
            source.emit(HoverInteraction.Exit(second))
            val final = List(18) { frame() }
            assertEquals(0, final.last())
        } finally { scene.close() }
    }
}
