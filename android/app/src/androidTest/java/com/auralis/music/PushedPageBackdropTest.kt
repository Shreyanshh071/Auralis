package com.auralis.music

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.auralis.music.ui.theme.AuralisPushedPage
import com.auralis.music.ui.theme.auralisPushParent
import com.auralis.music.ui.theme.rememberPushProgress
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PushedPageBackdropTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun openingAndClosingSettingsNeverExposesHomeThroughThePageStack() {
        val open = mutableStateOf(false)
        val profile = Color(0f, .4f, 0f)
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = profile)) {
                val progress = rememberPushProgress(open.value)
                Box(Modifier.fillMaxSize().background(Color.Magenta).testTag("stack")) {
                    Box(Modifier.fillMaxSize().auralisPushParent(progress).background(profile))
                    AuralisPushedPage(if (open.value) "settings" else null) {
                        Box(Modifier.fillMaxSize().background(Color.Blue))
                    }
                }
            }
        }
        compose.mainClock.autoAdvance = false
        for (pushed in listOf(true, false)) {
            compose.runOnIdle { open.value = pushed }
            repeat(5) {
                compose.mainClock.advanceTimeBy(40)
                val pixels = compose.onNodeWithTag("stack").captureToImage().toPixelMap()
                val center = pixels[pixels.width / 2, pixels.height / 2]
                assertTrue("Home leaked through during a page transition: $center", center.red < .05f)
            }
        }
    }
}
