package com.auralis.music.windows

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import java.io.File
import kotlin.test.*

class SidebarCollapseContentTest {
    @Test fun contentKeepsItsOwnWidthThroughoutCollapseExpansionAndReversal() {
        for ((expanded, compact) in listOf(232 to 64, 340 to 40)) {
            var collapsed by mutableStateOf(true)
            var viewportWidth = 0
            val widths = mutableMapOf<Boolean, Int>()
            val textHeights = mutableListOf<Int>()
            val scene = ImageComposeScene(500, 400, coroutineContext = Dispatchers.Unconfined) {
                MaterialTheme {
                    val width by animateDpAsState((if (collapsed) compact else expanded).dp, tween(280))
                    Box(Modifier.width(width).fillMaxHeight().onGloballyPositioned { viewportWidth = it.size.width }) {
                        SidebarCollapseContent(collapsed, expanded.dp, compact.dp) { compactMode ->
                            Column(Modifier.fillMaxSize().background(Color(0xFF35303F))
                                .onGloballyPositioned { widths[compactMode] = it.size.width }) {
                                if (!compactMode) Text("Auralis navigation", onTextLayout = { textHeights += it.lineCount })
                                repeat(5) { Text(if (compactMode) "•" else "Shortcut $it") }
                            }
                        }
                    }
                }
            }
            try {
                var frame = 0L
                fun render() {
                    scene.render(frame++ * 16_666_667L).close()
                    widths[false]?.let { assertEquals(expanded, it, "Expanded content must never be squeezed into the animating viewport") }
                    widths[true]?.let { assertEquals(compact, it, "Compact covers must never stretch across the expanded panel") }
                    assertTrue(textHeights.all { it == 1 }, "Expanded labels must not wrap vertically")
                }
                repeat(4) { render() }
                assertEquals(compact, viewportWidth)
                collapsed = false
                repeat(6) { render() }
                assertTrue(viewportWidth in compact + 1 until expanded)
                val output = File("build/screens/sidebar-collapse-$compact.png").apply { parentFile.mkdirs() }
                scene.render(frame++ * 16_666_667L).use { image -> image.encodeToData()!!.use { output.writeBytes(it.bytes) } }
                val beforeReverse = viewportWidth
                collapsed = true
                render()
                assertTrue(kotlin.math.abs(viewportWidth - beforeReverse) < 60, "Reversal must continue from the current width")
                repeat(25) { render() }
                assertEquals(compact, viewportWidth)
                collapsed = false
                val opening = buildList { repeat(25) { render(); add(viewportWidth) } }
                assertEquals(expanded, viewportWidth)
                assertTrue(opening.zipWithNext().all { (a, b) -> b >= a })
                collapsed = true
                val closing = buildList { repeat(25) { render(); add(viewportWidth) } }
                assertEquals(compact, viewportWidth)
                assertTrue(closing.zipWithNext().all { (a, b) -> b <= a })
                assertEquals(setOf(false, true), widths.keys)
            } finally { scene.close() }
        }
    }
}
