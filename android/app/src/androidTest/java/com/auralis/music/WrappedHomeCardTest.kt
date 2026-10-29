package com.auralis.music

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.auralis.music.ui.glass.*
import com.auralis.music.ui.home.WrappedHomeCard
import com.auralis.music.ui.theme.*
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

class WrappedHomeCardTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun compactBarTracksLivePaletteInGlassAndNormalThemes() {
        val glassMode = mutableStateOf(false)
        val light = mutableStateOf(false)
        val accent = mutableStateOf(Color(0xFFBFA2FF))
        var opened = 0
        compose.runOnUiThread {
            com.auralis.music.ui.i18n.AppLanguage.init(compose.activity)
            com.auralis.music.ui.i18n.AppLanguage.attach(compose.activity)
        }
        compose.setContent {
            val backdrop = rememberLayerBackdrop()
            val glass = remember(backdrop, light.value) { LiquidGlassContext(backdrop, !light.value, mutableStateOf(0f)) }
            val surface = if (light.value) Color(0xFFF8F6FC) else Color(0xFF15131C)
            MaterialTheme(colorScheme = if (light.value) lightColorScheme() else darkColorScheme()) {
                CompositionLocalProvider(LocalLiquidGlass provides if (glassMode.value) glass else null,
                    LocalDynamicThemePrimary provides accent.value, LocalDynamicThemeSecondary provides accent.value,
                    LocalDynamicThemeSurface provides surface,
                    LocalDynamicThemeOnSurface provides if (light.value) Color(0xFF18141F) else Color.White) {
                    Box(Modifier.fillMaxSize().background(surface).padding(18.dp)) {
                        WrappedHomeCard(onClick = { opened++ })
                    }
                }
            }
        }
        fun capture(name: String): Color {
            compose.waitForIdle()
            val image = compose.onNodeWithTag("wrapped-home-card").captureToImage()
            val pixels = image.toPixelMap()
            val density = compose.activity.resources.displayMetrics.density
            assertTrue("The Wrapped entry should stay compact", pixels.height / density <= 82f)
            File(compose.activity.getExternalFilesDir(null), "wrapped-bar-$name.png").outputStream().use {
                image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            return pixels[(pixels.width * .83f).toInt(), (pixels.height * .20f).toInt()]
        }
        capture("normal-dark")
        compose.runOnIdle { light.value = true }
        capture("normal-light")
        compose.runOnIdle { light.value = false; glassMode.value = true }
        val purple = capture("glass-purple")
        compose.runOnIdle { accent.value = Color(0xFF79D8AC) }
        val green = capture("glass-green")
        assertTrue("Glass tint must follow a song palette change", green.green - green.blue > purple.green - purple.blue + .025f)
        compose.runOnIdle { light.value = true }
        capture("glass-light")
        compose.onNode(hasText("Your Wrapped") and hasClickAction()).performClick()
        compose.runOnIdle { assertTrue(opened == 1) }
    }
}
