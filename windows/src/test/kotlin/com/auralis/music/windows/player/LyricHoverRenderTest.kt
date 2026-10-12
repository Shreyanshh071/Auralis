package com.auralis.music.windows.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import com.auralis.music.domain.model.LyricLine
import com.auralis.music.domain.model.SyncType
import com.auralis.music.ui.lyrics.*
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.*

class LyricHoverRenderTest {
    @Test fun inactiveHoverStaysMutedAndActiveLyricsStayBright() {
        for (sidebar in listOf(false, true)) {
            val active = mutableStateOf(false)
            var clicks = 0
            val position = mutableLongStateOf(0L)
            val scene = ImageComposeScene(500, 90, coroutineContext = Dispatchers.Unconfined) {
                MaterialTheme {
                    CompositionLocalProvider(LocalLyricsUi provides LyricsUiPlatform(
                        strings = DefaultLyricsUi.strings, roundedMouseHover = true)) {
                        Box(Modifier.fillMaxSize().background(Color(0xFF48214F))) {
                            if (sidebar) SidebarLyricLine("Unsung lyric preview", active.value, true, { clicks++ })
                            else ExperimentalLyricsLine(2, LyricLine(10000, "Unsung lyric preview"),
                                true, active.value, false, false, false, position,
                                24f, 1.3f, Color.White, "Left", true, 0, SyncType.LINE_SYNC,
                                standardBlur = true, lineCenterPx = 150f, activeLineCenterPx = 20f,
                                viewportStartPx = 0f, viewportEndPx = 500f,
                                onSizeChanged = {}, onClick = { clicks++ }, onLongClick = {})
                        }
                    }
                }
            }
            try {
                var frame = 0L
                fun render(name: String): java.awt.image.BufferedImage {
                    repeat(35) { scene.render(frame++ * 16_666_667L).close() }
                    val bytes = scene.render(frame++ * 16_666_667L).use {
                        it.encodeToData(EncodedImageFormat.PNG)!!.use { data -> data.bytes }
                    }
                    val dir = File("build/screens/lyric-hover").apply { mkdirs() }
                    File(dir, "${if(sidebar) "sidebar" else "player"}-$name.png").writeBytes(bytes)
                    return ImageIO.read(bytes.inputStream())
                }
                render("idle")
                scene.sendPointerEvent(PointerEventType.Move, Offset(100f,25f), type = PointerType.Mouse)
                val hovered = render("hovered")
                fun peak(image: java.awt.image.BufferedImage): Int = (8 until 60).maxOf { y ->
                    (20 until 270).maxOf { x -> image.getRGB(x,y) shr 16 and 255 }
                }
                assertTrue(peak(hovered) in 140..220, "Unsung hover should be readable muted grey, not singing white")
                assertTrue((hovered.getRGB(400,25) shr 16 and 255) > 85, "Hover includes a rounded row highlight")
                scene.sendPointerEvent(PointerEventType.Press, Offset(100f,25f), type = PointerType.Mouse)
                scene.sendPointerEvent(PointerEventType.Release, Offset(100f,25f), type = PointerType.Mouse)
                assertEquals(1, clicks, "Highlighted lyric remains clickable")
                active.value = true
                assertTrue(peak(render("active")) > 245, "Singing line remains bright")
            } finally { scene.close() }
        }
    }
}
