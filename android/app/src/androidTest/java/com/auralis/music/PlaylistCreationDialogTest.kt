package com.auralis.music

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.lifecycle.Lifecycle
import com.auralis.music.ui.glass.*
import com.auralis.music.ui.library.PlaylistCreationDialogLayout
import com.auralis.music.ui.theme.LocalDynamicThemePrimary
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class PlaylistCreationDialogTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun glassDialogKeepsItsPageBackdropAfterBackgroundAndResume() {
        compose.setContent {
            val backdrop = rememberLayerBackdrop()
            val glass = remember(backdrop) { LiquidGlassContext(backdrop, true, mutableStateOf(0f)) }
            MaterialTheme(colorScheme = darkColorScheme()) {
                CompositionLocalProvider(LocalLiquidGlass provides glass) {
                    Box(Modifier.fillMaxSize().layerBackdrop(backdrop).background(Color(0xFFFF3030)))
                    PlaylistCreationDialogLayout("Resume test", {}, false, {}, false, false, 0, null, {}, {})
                }
            }
        }
        fun checkBackdrop(label: String) {
            compose.waitForIdle()
            val image = compose.onNodeWithTag("playlist-creation-dialog").captureToImage()
            val pixels = image.toPixelMap()
            val pixel = pixels[(pixels.width * .90f).toInt(), (pixels.height * .17f).toInt()]
            File(compose.activity.getExternalFilesDir(null), "playlist-resume-$label.png").outputStream().use {
                image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            assertTrue("$label must sample the red page, not only draw the neutral glass tint: $pixel",
                pixel.red - pixel.green > .15f)
        }
        checkBackdrop("before")
        repeat(3) { cycle ->
            val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            fun shell(command: String) {
                android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
                    .use { it.readBytes() }
            }
            shell("input keyevent KEYCODE_APP_SWITCH")
            android.os.SystemClock.sleep(350)
            shell("input keyevent KEYCODE_HOME")
            compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            shell("am send-trim-memory com.auralis.music UI_HIDDEN")
            android.os.SystemClock.sleep(350)
            shell("am start -n com.auralis.music/androidx.activity.ComponentActivity --activity-reorder-to-front")
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            checkBackdrop("after-$cycle")
        }
    }

    @Test fun destinationsUseTheCurrentAccentAndGlassWithoutSpotify() {
        val accent = mutableStateOf(Color(0xFFBDA1FF))
        val glassEnabled = mutableStateOf(false)
        val brightBackdrop = mutableStateOf(false)
        var created = 0
        compose.setContent {
            val backdrop = rememberLayerBackdrop()
            val glass = remember(backdrop) { LiquidGlassContext(backdrop, true, mutableStateOf(0f)) }
            var name by remember { mutableStateOf("") }
            var youtube by remember { mutableStateOf(false) }
            MaterialTheme(colorScheme = darkColorScheme(primary = Color.Green)) {
                CompositionLocalProvider(LocalDynamicThemePrimary provides accent.value,
                    LocalLiquidGlass provides if (glassEnabled.value) glass else null) {
                    Box(Modifier.fillMaxSize().layerBackdrop(backdrop).background(Brush.linearGradient(
                        if (brightBackdrop.value) listOf(Color.White, Color(0xFFFFD54F), Color(0xFFFFF1D0))
                        else listOf(Color(0xFF38245F), Color(0xFF142F38), Color(0xFF75503D)))))
                    PlaylistCreationDialogLayout(name, { name = it }, youtube, { youtube = it },
                        busy = false, saved = false, trackCount = 0, error = null, onDismiss = {}, onCreate = { created++ })
                }
            }
        }
        val createButton = compose.onNode(hasText("Create playlist") and hasClickAction())
        createButton.assertIsNotEnabled()
        compose.onNodeWithText("Spotify").assertDoesNotExist()
        compose.onNodeWithText("YouTube Music").performClick().assertIsSelected()
        compose.onNodeWithText("Playlist name").performTextInput("Late night rotation")
        compose.onNodeWithText("Playlist name").performImeAction()
        compose.onNodeWithText("Auralis only").performClick().assertIsSelected()
        createButton.assertIsEnabled()
        fun capture(label: String, expectedAccent: Color) {
            compose.waitForIdle()
            val image = compose.onNodeWithTag("playlist-creation-dialog").captureToImage()
            val pixels = image.toPixelMap()
            var accentPixels = 0
            for (y in 0 until pixels.height step 3) for (x in 0 until pixels.width step 3) {
                val p = pixels[x, y]
                if (abs(p.red - expectedAccent.red) < .06f && abs(p.green - expectedAccent.green) < .06f &&
                    abs(p.blue - expectedAccent.blue) < .06f) accentPixels++
            }
            assertTrue("$label must use its current accent rather than the default green", accentPixels > 50)
            val button = createButton.captureToImage().toPixelMap()
            var darkInk = 0
            for (y in button.height / 3 until button.height * 2 / 3) for (x in button.width / 4 until button.width * 3 / 4) {
                val p = button[x, y]
                if (p.red < .15f && p.green < .15f && p.blue < .15f) darkInk++
            }
            assertTrue("Bright $label actions need contrasting dark text", darkInk > 20)
            File(compose.activity.getExternalFilesDir(null), "playlist-create-$label.png").outputStream().use {
                image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        capture("purple", accent.value)
        compose.runOnIdle { accent.value = Color(0xFF68DCC5) }
        capture("teal", accent.value)
        compose.runOnIdle { glassEnabled.value = true }
        capture("glass", Color.White)
        compose.runOnIdle { brightBackdrop.value = true }
        capture("glass-bright-artwork", Color.White)
        val brightGlass = compose.onNodeWithTag("playlist-creation-dialog").captureToImage().toPixelMap()
        // Blank reading surface between the heading and input, away from the rim.
        val background = brightGlass[(brightGlass.width * .90f).toInt(), (brightGlass.height * .17f).toInt()]
        assertTrue("Glass must preserve readable white text even over bright artwork",
            1.05f / (background.luminance() + .05f) >= 4.5f)
        createButton.performClick()
        compose.runOnIdle { assertTrue(created == 1) }
    }
}
