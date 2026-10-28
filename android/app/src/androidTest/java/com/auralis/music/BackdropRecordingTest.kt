package com.auralis.music

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.auralis.music.domain.model.AppearanceSettings
import com.auralis.music.domain.model.Track
import com.auralis.music.ui.components.LiquidGlassPageHeader
import com.auralis.music.ui.glass.*
import com.auralis.music.ui.player.MiniPlayer
import com.auralis.music.ui.theme.LocalAppearanceSettings
import com.auralis.music.ui.theme.LocalReducedMotion
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Actual header and mini player must sample the page even during contact/collapse. */
@RunWith(AndroidJUnit4::class)
@OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)
@androidx.test.filters.SdkSuppress(minSdkVersion = 31)
class BackdropRecordingTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun expandedAndCollapsedChromeKeepsLiveGlassWhileFingerIsDown() {
        val color = mutableStateOf(Color.Red)
        val collapse = mutableStateOf(0f)
        compose.runOnUiThread {
            com.auralis.music.ui.i18n.AppLanguage.init(compose.activity)
            com.auralis.music.ui.i18n.AppLanguage.attach(compose.activity)
        }
        compose.setContent {
            val backdrop = rememberLayerBackdrop()
            val glass = remember(backdrop) { LiquidGlassContext(backdrop, true, collapse) }
            MaterialTheme(colorScheme = darkColorScheme()) {
                CompositionLocalProvider(
                    LocalAppearanceSettings provides AppearanceSettings(
                        liquidGlass = true, miniPlayerDesign = "New mini player",
                        miniPlayerBackgroundStyle = "Live Mesh", pureBlackMiniPlayer = false),
                    LocalReducedMotion provides true,
                    LocalLiquidGlass provides glass,
                    LocalMiniPlayerGlass provides glass,
                    LocalDockCollapse provides collapse
                ) {
                    Box(Modifier.fillMaxSize().testTag("scene")) {
                        Box(Modifier.fillMaxSize().layerBackdrop(backdrop).background(color.value).testTag("feed"))
                        LiquidGlassPageHeader(glass, {}, {}, {}, {}, onOpenStats = {},
                            modifier = Modifier.align(Alignment.TopCenter).testTag("header"))
                        Box(Modifier.align(Alignment.BottomCenter)
                            .width(if (collapse.value == 0f) 400.dp else 260.dp).testTag("mini")) {
                            MiniPlayer(track = Track("glass-test", "Same song", "Same artist", duration = 240),
                                isPlaying = false, onPlayPauseClick = {}, onClick = {})
                        }
                    }
                }
            }
        }
        for (compact in listOf(false, true, false)) {
            compose.runOnIdle { collapse.value = if (compact) 1f else 0f; color.value = Color.Red }
            compose.waitForIdle()
            val before = listOf("logo", "header", "mini").associateWith { blueMinusRed(it) }
            compose.onNodeWithTag("feed").performTouchInput { down(center) }
            compose.runOnIdle { color.value = Color.Blue }
            compose.waitForIdle()
            for (tag in before.keys) {
                val change = blueMinusRed(tag) - before.getValue(tag)
                assertTrue("$tag became solid or used song colours (compact=$compact, change=$change)", change > .025f)
            }
            compose.onNodeWithTag("feed").performTouchInput { up() }
        }
    }

    private fun blueMinusRed(tag: String): Float {
        val pixels = compose.onNodeWithTag(if (tag == "logo") "header" else tag).captureToImage().toPixelMap()
        val density = compose.activity.resources.displayMetrics.density
        // Sample inside each glass shape, never the uncovered page between
        // header controls (which would pass even if the controls were solid).
        val left = when (tag) {
            "logo" -> (20 * density).toInt()
            "header" -> pixels.width - (180 * density).toInt()
            else -> pixels.width / 10
        }
        val right = when (tag) {
            "logo" -> (48 * density).toInt()
            "header" -> pixels.width - (20 * density).toInt()
            else -> pixels.width * 9 / 10
        }
        var sum = 0f
        var count = 0
        for (y in pixels.height / 3 until pixels.height * 2 / 3 step 3) {
            for (x in left until right step 3) {
                val pixel = pixels[x, y]
                sum += pixel.blue - pixel.red
                count++
            }
        }
        return sum / count.coerceAtLeast(1)
    }
}
