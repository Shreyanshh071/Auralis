package com.auralis.music

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.auralis.music.data.datastore.AppearanceSettingsDataStore
import com.auralis.music.ui.onboarding.AppTour
import com.auralis.music.ui.theme.LocalReducedMotion
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
/** Run with -PmotionArtworkTests to avoid account and audio-service startup. */
class LiquidGlassTourTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun glassComparisonPersistsChoicesAndAllEightPagesRemainReachable() {
        compose.runOnUiThread {
            com.auralis.music.ui.i18n.AppLanguage.init(compose.activity.applicationContext)
            com.auralis.music.ui.i18n.AppLanguage.attach(compose.activity)
        }
        val store = AppearanceSettingsDataStore(compose.activity)
        val original = runBlocking { store.settingsFlow.first() }
        val finished = AtomicBoolean(false)
        try {
            runBlocking { store.updateSettings(original.copy(liquidGlass = false)) }
            compose.mainClock.autoAdvance = false
            compose.setContent {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    CompositionLocalProvider(LocalReducedMotion provides true) {
                        Box(Modifier.fillMaxSize().testTag("tour-screen")) {
                            AppTour(onFinish = { finished.set(true) })
                        }
                    }
                }
            }
            compose.mainClock.advanceTimeBy(350)
            compose.onNodeWithText("Next").performClick()
            compose.mainClock.advanceTimeBy(350)
            compose.onNodeWithText("Make it yours.").assertIsDisplayed()
            compose.onNodeWithText("Next").performClick()
            compose.mainClock.advanceTimeBy(350)
            compose.onNodeWithText("A little more glass.").assertIsDisplayed()
            compose.onNodeWithText("Off (default)").assertIsSelected()
            compose.onAllNodesWithText(com.auralis.music.ui.i18n.str(R.string.quick_picks)).onFirst().assertIsDisplayed()
            capture("tour-glass-off.png")
            compose.onNodeWithText("On", substring = false).performClick()
            compose.waitUntil(5_000) { runBlocking { store.settingsFlow.first().liquidGlass } }
            compose.mainClock.advanceTimeBy(350)
            compose.onNodeWithText("On", substring = false).assertIsSelected()
            capture("tour-glass-on.png")
            compose.onNodeWithTag("tour-glass-feed").performTouchInput { swipeUp() }
            compose.mainClock.advanceTimeBy(350)
            compose.onNodeWithTag("tour-preview-chrome").assert(
                SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "Collapsed"))
            capture("tour-glass-scrolled.png")
            compose.onNodeWithTag("tour-glass-feed").performTouchInput { swipeDown() }
            compose.mainClock.advanceTimeBy(500)
            compose.onNodeWithTag("tour-preview-chrome").assert(
                SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "Expanded"))
            compose.onNodeWithText("Off (default)").performClick()
            compose.waitUntil(5_000) { runBlocking { !store.settingsFlow.first().liquidGlass } }
            compose.mainClock.advanceTimeBy(350)
            compose.onNodeWithText("Off (default)").assertIsSelected()
            for (title in listOf("Hear every detail.", "Feel every word.", "Take music with you.", "You’re in control.", "Where is your sound?")) {
                compose.onNodeWithText("Next").performClick()
                compose.mainClock.advanceTimeBy(350)
                compose.onNodeWithText(title).assertIsDisplayed()
            }
            compose.onNodeWithText("Get started").performClick()
            assertTrue(finished.get())
        } finally {
            runBlocking { store.updateSettings(original) }
        }
    }

    private fun capture(name: String) {
        val bitmap = compose.onNodeWithTag("tour-screen").captureToImage().asAndroidBitmap()
        File(compose.activity.getExternalFilesDir(null), name).outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
