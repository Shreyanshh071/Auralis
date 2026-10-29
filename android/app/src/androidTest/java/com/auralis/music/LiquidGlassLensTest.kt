package com.auralis.music

import androidx.activity.ComponentActivity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.auralis.music.ui.glass.*
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class LiquidGlassLensTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun lensBendsTheBackgroundBeyondTheOldFixedRim() {
        compose.setContent {
            Row {
                for (index in 0..1) {
                    val backdrop = rememberLayerBackdrop()
                    val glass = remember(backdrop) { LiquidGlassContext(backdrop, true, mutableStateOf(0f)) }
                    Box(Modifier.width(150.dp).height(300.dp).testTag("lens-$index")) {
                        Canvas(Modifier.matchParentSize().layerBackdrop(backdrop)) {
                            drawRect(Color(0xFF20202A))
                            val step = 20.dp.toPx()
                            var x = 0f
                            while (x < size.width) { drawRect(Color.White, Offset(x, 0f), Size(1.dp.toPx(), size.height)); x += step }
                            var y = 0f
                            while (y < size.height) { drawRect(Color.White, Offset(0f, y), Size(size.width, 1.dp.toPx())); y += step }
                        }
                        Box(Modifier.matchParentSize().liquidGlass(glass, RoundedCornerShape(18.dp),
                            tint = Color.Transparent, blurRadius = 0.dp, lensDepth = if (index == 0) 0.dp else 24.dp))
                    }
                }
            }
        }
        compose.waitForIdle()
        val flat = compose.onNodeWithTag("lens-0").captureToImage().toPixelMap()
        val lens = compose.onNodeWithTag("lens-1").captureToImage().toPixelMap()
        val density = compose.activity.resources.displayMetrics.density
        var bent = 0
        for (x in (30 * density).toInt() until (65 * density).toInt()) {
            for (y in (50 * density).toInt() until (250 * density).toInt()) {
                if (kotlin.math.abs(flat[x,y].red - lens[x,y].red) > .2f) bent++
            }
        }
        assertTrue("The phone lens must warp lines beyond the old 24dp rim", bent > 250)
    }
}
