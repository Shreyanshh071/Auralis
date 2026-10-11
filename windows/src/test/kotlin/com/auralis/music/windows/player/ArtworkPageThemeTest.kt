package com.auralis.music.windows.player

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
}
