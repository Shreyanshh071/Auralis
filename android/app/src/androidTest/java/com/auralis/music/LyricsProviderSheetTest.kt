package com.auralis.music

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.auralis.music.domain.model.*
import com.auralis.music.ui.glass.*
import com.auralis.music.ui.player.LyricsProviderSheet
import com.auralis.music.ui.theme.*
import com.kyant.backdrop.backdrops.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class LyricsProviderSheetTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun animatedLyricsUnderlayRestsDuringMenuAndResumesAfterDismissal() {
        val recorded = java.util.concurrent.atomic.AtomicInteger()
        val show = mutableStateOf(false)
        val position = mutableStateOf(1234L)
        val modal = com.auralis.music.ui.player.PlayerModalVisualState()
        compose.runOnUiThread {
            com.auralis.music.ui.i18n.AppLanguage.init(compose.activity)
            com.auralis.music.ui.i18n.AppLanguage.attach(compose.activity)
        }
        compose.mainClock.autoAdvance = false
        compose.setContent {
            val record: androidx.compose.ui.graphics.drawscope.ContentDrawScope.() -> Unit = remember {
                { recorded.incrementAndGet(); drawContent() }
            }
            val page = rememberLayerBackdrop(onDraw = record)
            val glass = remember(page) { LiquidGlassContext(page, true, mutableStateOf(0f)) }
            MaterialTheme(colorScheme = darkColorScheme()) {
                CompositionLocalProvider(LocalLiquidGlass provides glass,
                    com.auralis.music.ui.player.LocalPlayerModalVisualState provides modal) {
                    Box(Modifier.fillMaxSize().layerBackdrop(page).background(Color(0xFF181818))) {
                        val rotation = rememberPreviewRotation(20_000)
                        Box(Modifier.fillMaxSize().then(Modifier.background(Color(0xFF281E30)))
                            .drawWithContent { rotation.value; drawContent() })
                        com.auralis.music.ui.lyrics.SyncedLyricsView(null, position, {}, isLoading = true)
                    }
                    if (show.value) LyricsProviderSheet(LyricsProvider.entries.filter { it != LyricsProvider.LOCAL },
                        LyricsProvider.UNISON, SyncType.RICHSYNC, emptyMap(), emptyMap(), {}, { show.value = false })
                }
            }
        }
        fun frames(count: Int) {
            repeat(count) { compose.mainClock.advanceTimeByFrame(); compose.waitForIdle() }
        }
        frames(5)
        recorded.set(0)
        frames(30)
        val active = recorded.get()
        assertTrue("Fixture must animate and capture the real loading ring/background: $active", active >= 15)
        compose.runOnUiThread { show.value = true }
        frames(60)
        recorded.set(0)
        compose.runOnUiThread { position.value = 5678L }
        frames(30)
        compose.onNode(hasScrollAction()).performTouchInput { swipeUp(durationMillis = 500) }
        frames(30)
        val covered = recorded.get()
        assertTrue("Covered animation must stop invalidating the glass: $covered", covered <= 5)
        assertEquals("Visual suspension must not modify the playback clock", 5678L, position.value)
        compose.runOnUiThread { show.value = false }
        frames(5)
        recorded.set(0)
        frames(30)
        val resumed = recorded.get()
        assertTrue("Animations must resume after dismissal: $resumed", resumed >= 15)
        android.util.Log.i("ProviderScrollWork", "Animated underlay: active=$active, menu=$covered, resumed=$resumed")
        compose.mainClock.autoAdvance = true
    }

    @Test fun scrollingChoicesDoesNotRebuildGlassOrLeaveWhiteEdgeLines() {
        val samples = java.util.concurrent.atomic.AtomicInteger()
        compose.runOnUiThread {
            com.auralis.music.ui.i18n.AppLanguage.init(compose.activity)
            com.auralis.music.ui.i18n.AppLanguage.attach(compose.activity)
        }
        compose.setContent {
            val page = rememberLayerBackdrop()
            val counted = remember(page) { object : com.kyant.backdrop.Backdrop {
                override val isCoordinatesDependent = true
                override fun androidx.compose.ui.graphics.drawscope.DrawScope.drawBackdrop(
                    density: androidx.compose.ui.unit.Density, coordinates: androidx.compose.ui.layout.LayoutCoordinates?,
                    layerBlock: (androidx.compose.ui.graphics.GraphicsLayerScope.() -> Unit)?) {
                    samples.incrementAndGet()
                    with(page) { drawBackdrop(density, coordinates, layerBlock) }
                }
            } }
            val glass = remember(counted) { LiquidGlassContext(counted, true, mutableStateOf(0f)) }
            MaterialTheme(colorScheme = darkColorScheme()) {
                Box(Modifier.fillMaxSize().layerBackdrop(page).background(Color(0xFF181818)))
                CompositionLocalProvider(LocalLiquidGlass provides glass) {
                    LyricsProviderSheet(LyricsProvider.entries.filter { it != LyricsProvider.LOCAL },
                        LyricsProvider.UNISON, SyncType.RICHSYNC, emptyMap(), emptyMap(), {}, {})
                }
            }
        }
        compose.waitForIdle()
        val beforeBounds = compose.onNodeWithTag("lyrics-provider-sheet").fetchSemanticsNode().boundsInRoot
        assertTrue("Provider menu should occupy roughly the lower half of the screen",
            beforeBounds.height <= compose.activity.resources.displayMetrics.heightPixels * .56f)
        samples.set(0)
        repeat(3) {
            compose.onNode(hasScrollAction()).performTouchInput {
                swipe(start = androidx.compose.ui.geometry.Offset(width * .5f, height * .8f),
                    end = androidx.compose.ui.geometry.Offset(width * .5f, height * .25f), durationMillis = 500)
            }
            compose.waitForIdle()
        }
        val draws = samples.get()
        val afterBounds = compose.onNodeWithTag("lyrics-provider-sheet").fetchSemanticsNode().boundsInRoot
        android.util.Log.i("ProviderScrollWork", "Three menu swipes: glass samples=$draws")
        assertTrue("Scrolling a static backdrop must not rebuild the glass: $draws", draws <= 5)
        val image = compose.onNodeWithTag("lyrics-provider-sheet").captureToImage()
        File(compose.activity.getExternalFilesDir(null), "lyrics-picker-scroll-edges.png").outputStream().use {
            image.asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        assertEquals("First scroll must move the choices without resizing the sheet", beforeBounds.height, afterBounds.height, 1f)
        assertEquals("The menu must already be fully expanded", beforeBounds.top, afterBounds.top, 1f)
        val pixels = image.toPixelMap()
        for (y in pixels.height / 3 until pixels.height - 12 step 9) {
            assertTrue("No white line on the sheet's left edge", pixels[1, y].red < .35f)
            assertTrue("No white line on the sheet's right edge", pixels[pixels.width - 2, y].red < .35f)
        }
    }

    @Test fun playerOverlaySamplesTheVisiblePlayerInsteadOfTheLibraryBelowIt() {
        val playerColor = mutableStateOf(Color.Green)
        compose.runOnUiThread {
            com.auralis.music.ui.i18n.AppLanguage.init(compose.activity)
            com.auralis.music.ui.i18n.AppLanguage.attach(compose.activity)
        }
        compose.setContent {
            val library = rememberLayerBackdrop()
            val playerBackground = rememberLayerBackdrop()
            val playerContent = rememberLayerBackdrop()
            val combined = rememberCombinedBackdrop(library, playerBackground, playerContent)
            val glass = remember(combined) { LiquidGlassContext(combined, true, mutableStateOf(0f)) }
            MaterialTheme(colorScheme = darkColorScheme()) {
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().layerBackdrop(library).background(Color.Red))
                    Box(Modifier.fillMaxSize().layerBackdrop(playerBackground).background(Color.Blue))
                    Box(Modifier.fillMaxSize().layerBackdrop(playerContent).background(playerColor.value))
                    CompositionLocalProvider(LocalLiquidGlass provides glass) {
                        LyricsProviderSheet(listOf(LyricsProvider.SPICY_LYRICS, LyricsProvider.QQMUSIC),
                            LyricsProvider.SPICY_LYRICS, SyncType.RICHSYNC, emptyMap(), emptyMap(), {}, {})
                    }
                }
            }
        }
        fun sample(): Color {
            compose.waitForIdle()
            val p = compose.onNodeWithTag("lyrics-provider-sheet").captureToImage().toPixelMap()
            return p[(p.width * .95f).toInt(), (p.height * .14f).toInt()]
        }
        val green = sample()
        assertTrue("Picker must show the green player, not the red library or blue lower layer",
            green.green > green.red + .15f && green.green > green.blue + .15f)
        compose.runOnIdle { playerColor.value = Color.Blue }
        val blue = sample()
        assertTrue("Picker must follow the live player layer", blue.blue > blue.green + .15f)
    }

    @Test fun pickerUsesLivePaletteAndSamplesGlassWithWorkingChoices() {
        val glassMode = mutableStateOf(false)
        val light = mutableStateOf(false)
        val page = mutableStateOf(Color.Red)
        var picked: LyricsProvider? = null
        compose.runOnUiThread {
            com.auralis.music.ui.i18n.AppLanguage.init(compose.activity)
            com.auralis.music.ui.i18n.AppLanguage.attach(compose.activity)
        }
        compose.setContent {
            val backdrop = rememberLayerBackdrop()
            val glass = remember(backdrop) { LiquidGlassContext(backdrop, true, mutableStateOf(0f)) }
            MaterialTheme(colorScheme = if (light.value) lightColorScheme() else darkColorScheme()) {
                CompositionLocalProvider(LocalLiquidGlass provides if (glassMode.value) glass else null,
                    LocalDynamicThemePrimary provides Color(0xFFBFA2FF),
                    LocalDynamicThemeSurface provides if (light.value) Color(0xFFF5EEFF) else Color(0xFF21192F),
                    LocalDynamicThemeOnSurface provides if (light.value) Color(0xFF21192F) else Color.White) {
                    Box(Modifier.fillMaxSize().layerBackdrop(backdrop).background(page.value))
                    LyricsProviderSheet(listOf(LyricsProvider.SPICY_LYRICS, LyricsProvider.QQMUSIC),
                        LyricsProvider.SPICY_LYRICS, SyncType.RICHSYNC, emptyMap(), emptyMap(), { picked = it }, {})
                }
            }
        }
        fun capture(name: String): Color {
            compose.waitForIdle()
            val image = compose.onNodeWithTag("lyrics-provider-sheet").captureToImage()
            File(compose.activity.getExternalFilesDir(null), "lyrics-picker-$name.png").outputStream().use {
                image.asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            val p = image.toPixelMap()
            return p[(p.width * .95f).toInt(), (p.height * .14f).toInt()]
        }
        val dark = capture("normal-dark")
        compose.onNodeWithText("QQ Music").performClick()
        assertEquals(LyricsProvider.QQMUSIC, picked)
        compose.runOnIdle { light.value = true }
        val bright = capture("normal-light")
        assertTrue("Normal picker must follow the active surface", bright.red > dark.red + .3f)
        compose.runOnIdle { glassMode.value = true; light.value = false }
        val red = capture("glass-red")
        compose.runOnIdle { page.value = Color.Blue }
        val blue = capture("glass-blue")
        assertTrue("Glass picker must sample its page, not remain a solid sheet", blue.blue - blue.red > red.blue - red.red + .15f)
    }
}
