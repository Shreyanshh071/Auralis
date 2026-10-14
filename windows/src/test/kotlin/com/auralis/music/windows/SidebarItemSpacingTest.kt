package com.auralis.music.windows

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test

class SidebarItemSpacingTest {
    @Test
    fun navigationItemsDoNotOverlapWhenSelectedAndHovered() {
        var selectedPage: SidebarPage? = SidebarPage.DOWNLOADS
        val scene = ImageComposeScene(260, 600, coroutineContext = Dispatchers.Unconfined) {
            MaterialTheme(colorScheme = darkColorScheme()) {
                NavigationSidebar(
                    selected = selectedPage,
                    collapsed = false,
                    onToggle = {},
                    onSelect = { selectedPage = it }
                )
            }
        }
        try {
            repeat(4) { scene.render(it * 16_666_667L).close() }
            scene.sendPointerEvent(PointerEventType.Move, Offset(50f, 189f), type = PointerType.Mouse)
            repeat(4) { scene.render((it + 4) * 16_666_667L).close() }
            val image = scene.render(200_000_000L)
            File("build/screens").mkdirs()
            File("build/screens/sidebar-history-hover.png").writeBytes(
                image.encodeToData(EncodedImageFormat.PNG)!!.bytes
            )
            image.close()
        } finally {
            scene.close()
        }
    }
}
