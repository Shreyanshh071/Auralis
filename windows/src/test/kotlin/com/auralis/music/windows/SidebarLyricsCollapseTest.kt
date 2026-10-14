package com.auralis.music.windows

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import com.auralis.music.windows.player.SidebarLyricsCard
import kotlinx.coroutines.Dispatchers
import kotlin.test.*

class SidebarLyricsCollapseTest {
    @Test fun collapseAndReopenAnimateThroughIntermediateHeights() {
        var collapsed by mutableStateOf(false)
        var height = 0
        val scene = ImageComposeScene(340, 400, coroutineContext = Dispatchers.Unconfined) {
            MaterialTheme {
                Box(Modifier.fillMaxSize()) {
                    SidebarLyricsCard(collapsed, { collapsed = !collapsed }, {}, {},
                        Modifier.width(320.dp).onGloballyPositioned { height = it.size.height }) {
                        Text("Every single day")
                    }
                }
            }
        }
        try {
            var frame = 0L
            fun render() { scene.render(frame++ * 16_666_667L).close() }
            repeat(4) { render() }
            assertEquals(320, height)
            collapsed = true
            val closing = buildList { repeat(25) { render(); add(height) } }
            assertEquals(48, height)
            assertTrue(closing.distinct().size > 8, "Closing must animate across frames")
            assertTrue(closing.zipWithNext().all { (a,b) -> b <= a })
            collapsed = false
            val opening = buildList { repeat(25) { render(); add(height) } }
            assertEquals(320, height)
            assertTrue(opening.distinct().size > 8)
            assertTrue(opening.zipWithNext().all { (a,b) -> b >= a })
            collapsed = true
            repeat(7) { render() }
            val reversingHeight = height
            collapsed = false
            render()
            assertTrue(kotlin.math.abs(height - reversingHeight) < 60, "Reversing must continue from the current height: $reversingHeight -> $height")
            repeat(25) { render() }
            assertEquals(320, height)
        } finally { scene.close() }
    }

    @Test fun collapseInConstrainedColumn() {
        var collapsed by mutableStateOf(false)
        var height = 0
        val scene = ImageComposeScene(340, 500, coroutineContext = Dispatchers.Unconfined) {
            MaterialTheme {
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.height(300.dp)) // leaves 200dp
                    SidebarLyricsCard(collapsed, { collapsed = !collapsed }, {}, {},
                        Modifier.weight(1f, fill = false).width(320.dp).onGloballyPositioned { height = it.size.height }) {
                        Text("Every single day")
                    }
                }
            }
        }
        try {
            var frame = 0L
            fun render() { scene.render(frame++ * 16_666_667L).close() }
            repeat(4) { render() }
            assertEquals(200, height)
            collapsed = true
            val closing = buildList { repeat(25) { render(); add(height) } }
            assertEquals(48, height)
            assertTrue(closing.distinct().size > 8, "Closing must have smooth intermediate heights")
            assertTrue(closing.zipWithNext().all { (a,b) -> b <= a }, "Heights must monotonically decrease")
            assertTrue(closing.take(5).distinct().size >= 2, "Closing must start animating without prolonged freeze: ${closing.take(5)}")
        } finally { scene.close() }
    }
}
