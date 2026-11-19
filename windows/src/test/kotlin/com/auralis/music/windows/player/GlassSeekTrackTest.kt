package com.auralis.music.windows.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.Bitmap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GlassSeekTrackTest {
    @Test fun bothSizesShowTheTimePreviewAndStillSeek() {
        for (compact in listOf(false, true)) {
            val progress = mutableFloatStateOf(.25f)
            var commits = 0
            val scene = ImageComposeScene(320, 150, coroutineContext = Dispatchers.Unconfined) {
                val backdrop = rememberLayerBackdrop()
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Box(Modifier.matchParentSize().layerBackdrop(backdrop).background(Color(0xFF705072)))
                    GlassSeekTrack(progress.floatValue, null, 120_000L, backdrop, compact,
                        onValueChange = { progress.floatValue = it },
                        onValueChangeFinished = { commits++ },
                        modifier = Modifier.width(240.dp))
                }
            }
            try {
                var time = 0L
                fun pixels(): IntArray {
                    repeat(15) { scene.render(time).close(); time += 16_666_667L }
                    return scene.render(time).use { image ->
                        Bitmap.makeFromImage(image).use { bitmap ->
                            IntArray(320 * 150) { bitmap.getColor(it % 320, it / 320) }
                        }
                    }
                }
                val rest = pixels()
                scene.sendPointerEvent(PointerEventType.Move, Offset(196f, 75f), type = PointerType.Mouse)
                val hover = pixels()
                assertTrue((35..58).sumOf { y ->
                    (174..218).count { x -> hover[y * 320 + x] != rest[y * 320 + x] }
                } > 110, "The time bubble renders above the track (compact=$compact)")
                scene.sendPointerEvent(PointerEventType.Press, Offset(196f, 75f), type = PointerType.Mouse)
                scene.sendPointerEvent(PointerEventType.Release, Offset(196f, 75f), type = PointerType.Mouse)
                assertEquals(.65f, progress.floatValue, .01f)
                assertEquals(1, commits)
            } finally {
                scene.close()
            }
        }
    }
}
