package com.auralis.music.windows.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ManageAccounts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.EncodedImageFormat
import javax.imageio.ImageIO
import kotlin.test.*

class AccountMenuHoverTest {
    @Test fun hoverHighlightsEntireRectangularRowAndClearsOnExit() {
        var clicks = 0
        val scene = ImageComposeScene(380, 100, coroutineContext = Dispatchers.Unconfined) {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Box(Modifier.fillMaxSize().background(Color(0xFF101010))) {
                    AccountMenuAction("Manage accounts", Icons.Outlined.ManageAccounts) { clicks++ }
                }
            }
        }
        try {
            fun frame(index: Int) = ImageIO.read(scene.render(index * 16_666_667L)
                .encodeToData(EncodedImageFormat.PNG)!!.use { it.bytes }.inputStream())
            repeat(5) { frame(it) }
            val idle = frame(5)
            scene.sendPointerEvent(PointerEventType.Move, Offset(300f, 25f))
            val hovered = (6..15).map(::frame).last()
            for ((x, y) in listOf(0 to 1, 379 to 1, 0 to 50, 379 to 50, 300 to 25)) {
                assertNotEquals(idle.getRGB(x, y), hovered.getRGB(x, y), "Hover must reach row edges at $x,$y")
            }
            assertEquals(0, clicks)
            scene.sendPointerEvent(PointerEventType.Press, Offset(300f, 25f))
            scene.sendPointerEvent(PointerEventType.Release, Offset(300f, 25f))
            frame(16)
            assertEquals(1, clicks)
            scene.sendPointerEvent(PointerEventType.Move, Offset(300f, 80f))
            val exited = (17..25).map(::frame).last()
            assertEquals(idle.getRGB(300, 25), exited.getRGB(300, 25), "Highlight must clear on exit")
        } finally { scene.close() }
    }
}
