package com.auralis.music.windows.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.graphics.Color
import com.auralis.music.ui.theme.*
import kotlin.test.*

class ArtworkPageThemeTest {
    @Test fun sharedPagesReceiveTheSameArtworkSurfacesAsDesktopPages() {
        for (artwork in listOf(null, "")) {
            val base = darkColorScheme(background = Color(0xFF111111), surface = Color(0xFF222222))
            var checked = false
            val scene = ImageComposeScene(40, 40) {
                MaterialTheme(colorScheme = base) {
                    CompositionLocalProvider(LocalDynamicThemeBackground provides Color.Red,
                        LocalDynamicThemeSurface provides Color.Blue,
                        LocalDynamicThemeOnBackground provides Color.Yellow,
                        LocalDynamicThemeOnSurface provides Color.Green) {
                        ArtworkAppBackground(artwork, DefaultArtworkPalette) {
                            assertEquals(MaterialTheme.colorScheme.background, MaterialTheme.dynamicBackground)
                            assertEquals(MaterialTheme.colorScheme.surface, MaterialTheme.dynamicSurface)
                            assertEquals(MaterialTheme.colorScheme.onBackground, MaterialTheme.dynamicOnBackground)
                            assertEquals(MaterialTheme.colorScheme.onSurface, MaterialTheme.dynamicOnSurface)
                            if (artwork != null) {
                                assertTrue(MaterialTheme.dynamicBackground.alpha < .2f)
                                assertTrue(MaterialTheme.dynamicSurface.alpha < .3f)
                            } else assertEquals(base.background, MaterialTheme.dynamicBackground)
                            checked = true
                        }
                    }
                }
            }
            try { scene.render(0); assertTrue(checked) } finally { scene.close() }
        }
    }
    @Test fun artworkKeepsItsColoursUnderNeutralGlass() {
        val dir = java.io.File("build/screens/artwork-colours").apply { mkdirs() }
        fun cover(name: String, colour: java.awt.Color): java.io.File {
            val image = java.awt.image.BufferedImage(400, 400, java.awt.image.BufferedImage.TYPE_INT_RGB)
            image.createGraphics().apply {
                color = colour; fillRect(0, 0, 400, 400)
                color = java.awt.Color.BLACK; fillRect(175, 100, 50, 200)
                color = java.awt.Color.RED; fillRect(300, 20, 35, 8)
                dispose()
            }
            return java.io.File(dir, name).also { javax.imageio.ImageIO.write(image, "png", it) }
        }
        val white = cover("neutral-cover.png", java.awt.Color.WHITE)
        val blue = cover("blue-cover.png", java.awt.Color(0, 128, 255))
        val artwork = androidx.compose.runtime.mutableStateOf(white.absolutePath)
        val scene = ImageComposeScene(400, 400, coroutineContext = kotlinx.coroutines.Dispatchers.Unconfined) {
            MaterialTheme(colorScheme = darkColorScheme()) {
                // A sparse coloured signature must not tint the whole white cover pink.
                ArtworkAppBackground(artwork.value, ArtworkPalette(Color.Red, Color.Magenta, Color.Red)) {
                    androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().height(150.dp).background(MaterialTheme.colorScheme.surface))
                }
            }
        }
        try {
            var frame = 0L
            fun render(name: String): java.awt.image.BufferedImage {
                repeat(65) { scene.render(frame++ * 16_666_667L).close(); Thread.sleep(15) }
                val bytes = scene.render(frame++ * 16_666_667L).use {
                    it.encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)!!.use { data -> data.bytes }
                }
                java.io.File(dir, name).writeBytes(bytes)
                return javax.imageio.ImageIO.read(bytes.inputStream())
            }
            val neutral = render("neutral-page.png")
            fun rgb(image: java.awt.image.BufferedImage, x: Int, y: Int): List<Int> {
                val pixel = image.getRGB(x,y)
                return listOf(pixel shr 16 and 255, pixel shr 8 and 255, pixel and 255)
            }
            val background = rgb(neutral, 60, 260)
            assertTrue(background.all { it in 110..118 }, "White artwork should have 45% brightness: $background")
            val panel = rgb(neutral, 60, 60)
            assertTrue(panel.max() - panel.min() <= 1, "Neutral cover must remain neutral beneath panels: $panel")
            artwork.value = blue.absolutePath
            val colour = rgb(render("blue-page.png"), 60, 260)
            assertTrue(colour[0] < 3 && colour[1] in 53..62 && colour[2] in 110..118,
                "New cover should retain its own hue and brightness: $colour")
        } finally { scene.close() }
    }

}
