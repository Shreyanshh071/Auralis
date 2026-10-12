package com.auralis.music.windows

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import com.auralis.music.windows.settings.ResponsiveSettingsSlider
import kotlinx.coroutines.Dispatchers
import kotlin.test.*

class SettingsSliderGestureTest {
    @Test fun pointerUpdatesImmediatelyAndCommitsOnlyOnRelease() {
        val saved = mutableFloatStateOf(.2f)
        var commits = 0
        var result = 0f
        val scene = ImageComposeScene(440,100,coroutineContext = Dispatchers.Unconfined) {
            MaterialTheme {
                ResponsiveSettingsSlider("Size", saved.floatValue, 0f..1f, { it.toString() }) {
                    commits++; result = it // Deliberately delayed persistence acknowledgement.
                }
            }
        }
        try {
            repeat(3) { scene.render(it * 16_666_667L).close() }
            scene.sendPointerEvent(PointerEventType.Press, Offset(90f,45f))
            scene.sendPointerEvent(PointerEventType.Move, Offset(350f,45f))
            scene.render(100_000_000).close()
            assertEquals(0, commits, "Dragging must not perform settings writes")
            scene.sendPointerEvent(PointerEventType.Release, Offset(350f,45f))
            assertEquals(1, commits, "Only the final gesture value is saved")
            assertTrue(result > .7f, "Dragging should reach the pointer without waiting for storage")
            saved.floatValue = result
            scene.render(200_000_000).close()
        } finally { scene.close() }
    }
}
