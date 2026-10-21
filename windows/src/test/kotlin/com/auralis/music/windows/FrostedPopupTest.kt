package com.auralis.music.windows

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.jetbrains.skia.EncodedImageFormat
import javax.imageio.ImageIO
import java.io.File
import kotlin.test.*

class FrostedPopupTest {
    @Test fun mattePopupHasNoReflectiveTopGradient() {
        val scene = ImageComposeScene(280, 150) {
            MaterialTheme(colorScheme = darkColorScheme()) {
                LiquidPopupSurface(Modifier.fillMaxSize(), tint = Color(0xFF242428).copy(alpha = .62f), frosted = true) {
                    Column { GlassDropdownMenuHeader("Sound output")
                        GlassDropdownMenuItem({ Text("Windows default") }, {}, selected = true)
                        GlassDropdownMenuItem({ Text("Speakers (Realtek(R) Audio)") }, {}) }
                }
            }
        }
        try {
            val bytes = scene.render(0).use { it.encodeToData(EncodedImageFormat.PNG)!!.use { d -> d.bytes } }
            File("build/screens/frosted-output.png").apply { parentFile.mkdirs(); writeBytes(bytes) }
            val image = ImageIO.read(bytes.inputStream())
            assertEquals(image.getRGB(270, 30), image.getRGB(270, 120), "Matte fill has no reflective vertical gradient")
        } finally { scene.close() }
    }
}
