package com.auralis.music.windows.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.*
import com.auralis.music.desktop.settings.DesktopDataStores
import com.auralis.music.ui.components.LocalGlassDialogHost
import com.auralis.music.util.PropertiesFileKeyValueStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.io.File
import kotlin.test.*

class SettingsBehaviourTest {
    @Test fun lyricClickSettingControlsSeeking() {
        val appearance = mutableStateOf(com.auralis.music.domain.model.AppearanceSettings(changeLyricsOnTap = false))
        var seeks = 0
        val scene = ImageComposeScene(400,120, coroutineContext = Dispatchers.Unconfined) {
            MaterialTheme {
                CompositionLocalProvider(com.auralis.music.ui.theme.LocalAppearanceSettings provides appearance.value) {
                    com.auralis.music.windows.player.SidebarLyricLine("A lyric line", active=true, synced=true, onClick={ seeks++ })
                }
            }
        }
        try {
            scene.render().close()
            click(scene,40f,20f)
            assertEquals(0,seeks)
            appearance.value = appearance.value.copy(changeLyricsOnTap=true)
            scene.render().close()
            click(scene,40f,20f)
            assertEquals(1,seeks)
        } finally { scene.close() }
    }

    @Test fun clickingAToggleRowUpdatesItsDisplayAndTheActualTheme() {
        val dir = kotlin.io.path.createTempDirectory("settings-behaviour").toFile()
        val prefs = DesktopPreferencesStore(PropertiesFileKeyValueStore(File(dir,"prefs"), synchronous = true))
        var visible = false
        var accent = Color.Unspecified
        var font: androidx.compose.ui.text.font.FontFamily? = null
        val scene = ImageComposeScene(480,160, coroutineContext = Dispatchers.Unconfined) {
            MaterialTheme(colorScheme = darkColorScheme()) {
                DesktopThemeOverrides(prefs) {
                    val enabled = prefs.bool("enabled")
                    val primary = MaterialTheme.colorScheme.primary
                    val bodyFont = MaterialTheme.typography.bodyMedium.fontFamily
                    SideEffect { visible = enabled; accent = primary; font = bodyFont }
                    Column {
                        SettingToggle("Live setting", checked = enabled, onChecked = {
                            prefs.set("enabled", it)
                            prefs.set("accent", if (it) "#00CC88" else "")
                            prefs.set("bodyFont", if (it) "Arial" else "Default")
                        })
                    }
                }
            }
        }
        try {
            scene.render().close()
            val oldFont = font
            click(scene,40f,34f)
            assertTrue(prefs.bool("enabled"))
            assertTrue(visible, "The control must redraw after the same click that saved it")
            assertEquals(Color(0xFF00CC88), accent, "Theme must apply without reopening Settings")
            assertNotEquals(oldFont, font, "The selected body font must reach the theme")
            click(scene,444f,34f)
            assertFalse(visible, "Clicking the switch itself toggles exactly once")
        } finally { scene.close(); dir.deleteRecursively() }
    }

    @Test fun sliderCommitsTheDraggedValueAndRedraws() {
        var saved = 1f
        val value = mutableFloatStateOf(1f)
        val scene = ImageComposeScene(480,180, coroutineContext = Dispatchers.Unconfined) {
            MaterialTheme {
                SettingSlider("Zoom", value.floatValue, .75f..1.5f, { it.toString() }) { saved = it; value.floatValue = it }
            }
        }
        try {
            scene.render().close()
            scene.sendPointerEvent(PointerEventType.Press,Offset(330f,64f),type=PointerType.Mouse)
            scene.sendPointerEvent(PointerEventType.Move,Offset(420f,64f),type=PointerType.Mouse)
            scene.sendPointerEvent(PointerEventType.Release,Offset(420f,64f),type=PointerType.Mouse)
            repeat(2) { scene.render().close() }
            assertTrue(saved > 1.2f, "Dragging the slider must invoke its save callback: $saved")
            assertEquals(saved, value.floatValue)
        } finally { scene.close() }
    }

    @Test fun choiceOpensAboveSettingsAndSavesTheClickedOption() {
        val selected = mutableStateOf("Low")
        val scene = ImageComposeScene(600,600, coroutineContext = Dispatchers.Unconfined) {
            MaterialTheme {
                CompositionLocalProvider(LocalGlassDialogHost provides { dismiss, content -> SettingsOptionPopup(dismiss,content) }) {
                    Column { SettingChoice("Quality", selected=selected.value, options=listOf("Low","High")) { selected.value = it } }
                }
            }
        }
        try {
            scene.render().close()
            click(scene,200f,34f)
            scene.render().use { image -> image.encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)!!.use { data ->
                File("build/screens/settings-choice-open.png").apply {parentFile.mkdirs();writeBytes(data.bytes)}
            } }
            click(scene,300f,320f)
            assertEquals("High",selected.value, "The popup option must receive the click")
        } finally { scene.close() }
    }

    @Test fun appearanceUpdatesAreAtomicAndAllOfferedSeekStylesRoundTrip() = runBlocking {
        val dir=kotlin.io.path.createTempDirectory("appearance-behaviour").toFile()
        val store=DesktopDataStores(dir).appearance
        coroutineScope {
            listOf(
                async { store.update { it.copy(appTheme="Light Mode") } },
                async { store.update { it.copy(dynamicTheme=false) } },
                async { store.update { it.copy(lyricsTextSize=31f) } }
            ).awaitAll()
        }
        val actual=store.settingsFlow.first()
        assertEquals("Light Mode",actual.appTheme)
        // Liquidify is permanent on Windows: the stored flag no longer turns it off.
        assertTrue(actual.dynamicTheme)
        assertEquals(31f,actual.lyricsTextSize)
        for(style in desktopSeekBarStyles) {
            store.update { it.copy(playerSliderStyle=style) }
            assertEquals(style,store.settingsFlow.first().playerSliderStyle)
        }
    }

    private fun click(scene: ImageComposeScene,x:Float,y:Float) {
        scene.sendPointerEvent(PointerEventType.Press,Offset(x,y),type=PointerType.Mouse)
        scene.sendPointerEvent(PointerEventType.Release,Offset(x,y),type=PointerType.Mouse)
        repeat(2) { scene.render().close() }
    }
}
