package com.auralis.music

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.auralis.music.ui.onboarding.TourIntroAnimation
import com.auralis.music.ui.onboarding.TourIntroLogo
import com.auralis.music.ui.screens.wrapped.WrappedBackground
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Run with -PmotionArtworkTests; the real account remains untouched. */
@RunWith(AndroidJUnit4::class)
class TourIntroAnimationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun startupPathsDrawBeforeLinesRevealAndReducedMotionShowsCompletedLogo() {
        val motion = mutableStateOf(TourIntroAnimation(false))
        compose.setContent {
            val intro = motion.value
            WrappedBackground(Modifier.testTag("intro-scene"), animate = false, cacheRotatingScene = true,
                rotationDegrees = { intro.rotation }, linesReveal = { intro.lines }, detailsOpacity = { intro.lines }) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    TourIntroLogo(intro, Modifier.size(208.dp).testTag("intro-logo"))
                }
            }
        }
        var previousInk = 0
        for (ms in listOf(0f, 400f, 850f, 1900f, 3500f)) {
            compose.runOnIdle { motion.value.elapsedMs = ms }
            val logo = compose.onNodeWithTag("intro-logo").captureToImage().toPixelMap()
            var ink = 0
            for (y in 0 until logo.height) for (x in 0 until logo.width) {
                val pixel = logo[x, y]
                if (pixel.red > .8f && pixel.green > .8f && pixel.blue > .8f) ink++
            }
            if (ms <= 850f) {
                assertTrue("Logo drawing lost strokes at $ms", ink >= previousInk)
                if (ms > 0f) assertTrue("Logo did not draw at $ms", ink > previousInk)
                previousInk = ink
            }
            val image = compose.onNodeWithTag("intro-scene").captureToImage().asAndroidBitmap()
            File(compose.activity.getExternalFilesDir(null), "tour-intro-${ms.toInt()}.png").outputStream().use {
                image.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        compose.runOnIdle { motion.value = TourIntroAnimation(true) }
        val pixels = compose.onNodeWithTag("intro-logo").captureToImage().toPixelMap()
        var completedInk = 0
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
            val pixel = pixels[x, y]
            if (pixel.red > .8f && pixel.green > .8f && pixel.blue > .8f) completedInk++
        }
        assertTrue("Reduced motion hid part of the logo", completedInk >= previousInk * .99f)
    }
}
