package com.auralis.music

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class GlassCaptureWorkTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun resumeRetainsTheCaptureAndRedrawsAnOtherwiseStaticPage() {
        val owner = object : androidx.lifecycle.LifecycleOwner {
            val registry = androidx.lifecycle.LifecycleRegistry.createUnsafe(this)
            override val lifecycle: androidx.lifecycle.Lifecycle get() = registry
        }
        owner.registry.currentState = androidx.lifecycle.Lifecycle.State.RESUMED
        val capture = java.util.concurrent.atomic.AtomicReference<com.kyant.backdrop.backdrops.LayerBackdrop>()
        val draws = AtomicInteger()
        compose.setContent {
            CompositionLocalProvider(androidx.lifecycle.compose.LocalLifecycleOwner provides owner) {
                val page = rememberLayerBackdrop()
                SideEffect { capture.set(page) }
                Box(Modifier.fillMaxSize().layerBackdrop(page).drawWithContent {
                    draws.incrementAndGet()
                    drawContent()
                }.background(Color.Red))
            }
        }
        compose.waitForIdle()
        val retained = capture.get()
        repeat(3) {
            val before = draws.get()
            compose.runOnIdle {
                owner.registry.currentState = androidx.lifecycle.Lifecycle.State.CREATED
                owner.registry.currentState = androidx.lifecycle.Lifecycle.State.RESUMED
            }
            compose.waitForIdle()
            org.junit.Assert.assertSame("Resume must not release the layer sampled by retained dialogs", retained, capture.get())
            assertTrue("Resume must re-record static page content", draws.get() > before)
        }
    }

    @Test fun nestedGlassSourcesDrawPageContentOnlyOncePerUpdate() {
        val frame = mutableIntStateOf(0)
        val draws = AtomicInteger()
        compose.setContent {
            val page = rememberLayerBackdrop()
            val header = rememberLayerBackdrop()
            Box(Modifier.fillMaxSize().layerBackdrop(page, frame)) {
                Box(Modifier.fillMaxSize().layerBackdrop(header, frame).drawWithContent {
                    frame.intValue
                    draws.incrementAndGet()
                    drawContent()
                }.background(Color(0xFF172432))) {
                    Column {
                        repeat(30) { index ->
                            Box(Modifier.fillMaxWidth().height(36.dp).padding(3.dp)
                                .background(if (index % 2 == 0) Color(0xFF41636C) else Color(0xFF61486D)))
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        draws.set(0)
        repeat(30) { index ->
            compose.runOnIdle { frame.intValue = index + 1 }
            compose.waitForIdle()
        }
        android.util.Log.i("GlassCaptureWork", "30 page updates, content draws=${draws.get()}")
        assertTrue("Nested captures must reuse the recorded page instead of multiplying content draws: ${draws.get()}",
            draws.get() <= 35)
    }
}
