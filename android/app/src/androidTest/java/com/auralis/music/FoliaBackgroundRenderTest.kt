package com.auralis.music

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.auralis.music.ui.player.*
import com.auralis.music.ui.theme.ArtworkPalette
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class FoliaBackgroundRenderTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun appearancePickerCanReachEveryNewBackgroundWithoutChangingPreferences() {
        compose.setContent { MaterialTheme { com.auralis.music.ui.screens.AppearanceScreen(onDismiss={}) } }
        val title=compose.activity.getString(com.auralis.music.R.string.player_background_style)
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(title))
        compose.onNodeWithText(title).performClick()
        PlayerBackgroundStyle.entries.filter { it.isFolia }.forEach {
            compose.onNodeWithText(it.displayName).performScrollTo().assertIsDisplayed().assertHasClickAction()
        }
    }

    @Test fun actualAnimationClockFreezesWhenPausedOrHidden() {
        val playing = mutableStateOf(false)
        val visible = mutableStateOf(true)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            FoliaBackground(PlayerBackgroundStyle.FOLIA_LATENT,null,
                ArtworkPalette(Color.Magenta,Color.Cyan,Color.Blue),playing.value,visible.value,
                Modifier.size(240.dp,400.dp).testTag("clock"))
        }
        fun pixels(): IntArray {
            compose.waitForIdle()
            val p=compose.onNodeWithTag("clock").captureToImage().toPixelMap()
            return IntArray(p.width*p.height) { p[it%p.width,it/p.width].toArgb() }
        }
        val paused=pixels()
        compose.mainClock.advanceTimeBy(1000)
        assertArrayEquals(paused,pixels())
        compose.runOnIdle { playing.value=true }
        compose.mainClock.advanceTimeBy(1000)
        val moved=pixels()
        assertFalse("Running native frame clock must move the geometry",paused.contentEquals(moved))
        compose.runOnIdle { playing.value=false }
        compose.mainClock.advanceTimeBy(32)
        val stopped=pixels()
        compose.mainClock.advanceTimeBy(1000)
        assertArrayEquals(stopped,pixels())
        compose.runOnIdle { playing.value=true; visible.value=false }
        compose.mainClock.advanceTimeBy(1000)
        assertArrayEquals(stopped,pixels())
    }

    @Test fun realPlayerBackgroundHostPreservesForegroundControlsForEverySelection() {
        val selected = mutableStateOf(PlayerBackgroundStyle.FOLIA_LATENT)
        var clicks = 0
        val palette = ArtworkPalette(Color(0xFFE478A4),Color(0xFF467FC9),Color(0xFFBDA0F7))
        compose.setContent {
            MaterialTheme {
                Box(Modifier.size(240.dp,400.dp)) {
                    PlayerBackground(selected.value,null,palette,Modifier.fillMaxSize(),isPlaying=false)
                    Button(onClick={ clicks++ },modifier=Modifier.testTag("controls")) { Text("Play / pause") }
                }
            }
        }
        PlayerBackgroundStyle.entries.filter { it.isFolia }.forEach { style ->
            compose.runOnIdle { selected.value=PlayerBackgroundStyle.fromKey(style.displayName) }
            compose.waitForIdle()
            compose.onNodeWithTag("controls").assertIsDisplayed().performClick()
        }
        compose.runOnIdle { assertEquals(5,clicks) }
    }

    @Test fun everySelectableBackgroundRendersDistinctInkAndAnimatedModesMove() {
        val styles = PlayerBackgroundStyle.entries.filter { it.isFolia }
        val selected = mutableStateOf(styles.first())
        val time = mutableFloatStateOf(0f)
        val palette = ArtworkPalette(Color(0xFFE478A4), Color(0xFF467FC9), Color(0xFFBDA0F7))
        val source = Bitmap.createBitmap(64,64,Bitmap.Config.ARGB_8888).apply {
            for(y in 0 until height) for(x in 0 until width) setPixel(x,y,
                android.graphics.Color.rgb(x*4,y*4,if ((x/8+y/8)%2==0) 240 else 30))
        }
        val treatments = styles.associateWith { if(it.foliaUsesArtwork)
            foliaProcessArtwork(source,it,palette.primary.toArgb(),palette.secondary.toArgb()).asImageBitmap() else null }
        compose.setContent {
            Canvas(Modifier.size(240.dp,400.dp).testTag("folia")) {
                drawFoliaBackground(selected.value,palette,time.floatValue,treatments[selected.value])
            }
        }
        fun pixels(): IntArray {
            compose.waitForIdle()
            val map=compose.onNodeWithTag("folia").captureToImage().toPixelMap()
            return IntArray(map.width*map.height) { map[it%map.width,it/map.width].toArgb() }
        }
        val hashes=mutableSetOf<Int>()
        for(style in styles) {
            compose.runOnIdle { selected.value=style; time.floatValue=0f }
            val first=pixels()
            val folder = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
                .targetContext.getExternalFilesDir("folia-render-checks")!!
            folder.mkdirs()
            val image = compose.onNodeWithTag("folia").captureToImage()
            val saved = Bitmap.createBitmap(first,image.width,image.height,Bitmap.Config.ARGB_8888)
            java.io.File(folder,"${style.name}.png").outputStream().use { saved.compress(Bitmap.CompressFormat.PNG,100,it) }
            saved.recycle()
            assertTrue("$style must render colors, not a blank fallback",first.distinct().size>20)
            assertTrue("$style must be visually distinct",hashes.add(first.contentHashCode()))
            assertArrayEquals("Paused frame must be stable",first,pixels())
            compose.runOnIdle { time.floatValue=12f }
            val later=pixels()
            if(style.foliaAnimated) assertFalse("$style must move",first.contentEquals(later))
            else assertArrayEquals("Static image effects must not animate",first,later)
        }
        source.recycle()
    }
}
