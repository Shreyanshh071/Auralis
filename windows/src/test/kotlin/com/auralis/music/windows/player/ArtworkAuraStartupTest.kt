package com.auralis.music.windows.player

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.auralis.music.ui.theme.ArtworkPalette
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.Bitmap
import kotlin.test.Test
import kotlin.test.assertTrue

class ArtworkAuraStartupTest {
    @Test fun dynamicPaintsThePaletteOnItsFirstFrameWhileArtworkLoads() {
        val palette = ArtworkPalette(Color(0xFFDA255E), Color(0xFF602292), Color(0xFF2359BA))
        val scene = ImageComposeScene(160, 90, coroutineContext = Dispatchers.Unconfined) {
            PlayerBackdrop("Dynamic", "file:/missing-auralis-test-cover.png", palette)
        }
        try {
            scene.render(0).use { image ->
                Bitmap.makeFromImage(image).use { bitmap ->
                    val left = bitmap.getColor(10, 10)
                    val right = bitmap.getColor(149, 80)
                    assertTrue(left ushr 24 == 255 && right ushr 24 == 255)
                    assertTrue((left ushr 16 and 255) > (left and 255) + 40)
                    assertTrue((right and 255) > (right ushr 16 and 255) + 40)
                }
            }
        } finally {
            scene.close()
        }
    }
}
