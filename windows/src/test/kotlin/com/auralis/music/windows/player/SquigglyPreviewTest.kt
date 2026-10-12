package com.auralis.music.windows.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.auralis.music.ui.components.AuralisSlider
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test

class SquigglyPreviewTest {
    @Test fun renderRevisedWaveSpacing() {
        val scene = ImageComposeScene(760, 70, coroutineContext = Dispatchers.Unconfined) {
            MaterialTheme { Box(Modifier.fillMaxSize().background(Color(0xFF1D1718))) {
                AuralisSlider(.25f, {}, {}, true, 55_000, 220_000, sliderStyle = "Squiggly", showTimestamps = false)
            } }
        }
        try {
            repeat(8) { scene.render(it * 100_000_000L).close() }
            val bytes = scene.render(900_000_000L).use { it.encodeToData(EncodedImageFormat.PNG)!!.use { it.bytes } }
            File("build/screens/squiggly-revised.png").writeBytes(bytes)
        } finally { scene.close() }
    }
}
