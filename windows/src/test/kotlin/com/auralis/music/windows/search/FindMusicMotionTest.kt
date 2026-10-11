package com.auralis.music.windows.search

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import com.auralis.music.domain.repository.SearchRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.jetbrains.skia.EncodedImageFormat
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FindMusicMotionTest {
    @Test fun popupAppearsOnFirstFramesAndContinuesAnimating() {
        val repository = Proxy.newProxyInstance(SearchRepository::class.java.classLoader,
            arrayOf(SearchRepository::class.java)) { _, method, _ ->
            check(method.name == "getRecentSearchQueries") { "Opening search must not call ${method.name}" }
            MutableStateFlow(listOf("recent song"))
        } as SearchRepository
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val state = DesktopSearchState(repository, scope)
        val scene = ImageComposeScene(640, 600, coroutineContext = Dispatchers.Unconfined) {
            MaterialTheme {
                FindMusicDropdown(state, null, false, { _, _ -> }, {}, {}, {}, {}, {})
            }
        }
        try {
            // Exclude the button ripple and the text field's blinking caret.
            fun frame(index: Int): IntArray {
                val bytes = scene.render(index * 16_666_667L)
                    .encodeToData(EncodedImageFormat.PNG)!!.use { it.bytes }
                val image = javax.imageio.ImageIO.read(bytes.inputStream())
                return image.getRGB(0, 160, 480, 360, null, 0, 480)
            }
            repeat(5) { frame(it) }
            val closed = frame(5)
            scene.sendPointerEvent(PointerEventType.Press, Offset(80f, 27f))
            scene.sendPointerEvent(PointerEventType.Release, Offset(80f, 27f))
            val frames = (6..30).map(::frame)
            assertTrue(frames.take(3).any { !it.contentEquals(closed) }, "Search must draw in its first three frames")
            assertFalse(frames[3].contentEquals(frames.last()), "The tray must still animate after mounting")
            assertTrue(frames.takeLast(3).all { it.contentEquals(frames.last()) }, "Opening must settle within 400ms")
            scene.sendPointerEvent(PointerEventType.Press, Offset(600f, 570f))
            scene.sendPointerEvent(PointerEventType.Release, Offset(600f, 570f))
            val closing = (31..47).map(::frame)
            assertFalse(closing.first().contentEquals(closed), "Dismissal must animate instead of removing the tray immediately")
            assertTrue(closing.last().contentEquals(closed), "Dismissed search must leave no overlay")
            scene.sendPointerEvent(PointerEventType.Press, Offset(80f, 27f))
            scene.sendPointerEvent(PointerEventType.Release, Offset(80f, 27f))
            val reopening = (48..72).map(::frame)
            assertTrue(reopening.take(3).any { !it.contentEquals(closed) }, "Reopening must also be immediate")
            assertFalse(reopening[3].contentEquals(reopening.last()), "Reopening must animate again")
        } finally {
            scene.close()
            scope.cancel()
        }
    }
}
