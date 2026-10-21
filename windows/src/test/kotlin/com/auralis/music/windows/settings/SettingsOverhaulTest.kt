package com.auralis.music.windows.settings

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.input.key.*
import com.auralis.music.data.datastore.LocalSettingsStores
import com.auralis.music.desktop.settings.DesktopDataStores
import com.auralis.music.domain.model.Track
import com.auralis.music.util.PropertiesFileKeyValueStore
import java.io.File
import org.json.JSONObject
import kotlin.test.*

class SettingsOverhaulTest {
    @Test fun searchClearButtonWorksInCompactField() {
        val value = mutableStateOf("notifications")
        val scene = ImageComposeScene(200,40) {
            MaterialTheme(colorScheme = darkColorScheme()) {
                SettingsSearchField(value.value) { value.value = it }
            }
        }
        try {
            scene.render().close()
            scene.sendPointerEvent(PointerEventType.Press, Offset(178f,20f), type = PointerType.Mouse)
            scene.sendPointerEvent(PointerEventType.Release, Offset(178f,20f), type = PointerType.Mouse)
            scene.render().close()
            assertEquals("", value.value)
        } finally { scene.close() }
    }

    @Test fun settingsSearchFindsControlsAndHandlesEmptyResults() {
        assertEquals(listOf(SettingsTab.GENERAL), matchingSettingsTabs("song notifications"))
        assertEquals(listOf(SettingsTab.PLAYBACK), matchingSettingsTabs("crossfade"))
        assertEquals(listOf(SettingsTab.SCROBBLING), matchingSettingsTabs(" Last.FM "))
        assertEquals(SettingsTab.entries.toList(), matchingSettingsTabs("  "))
        assertTrue(matchingSettingsTabs("something nonexistent").isEmpty())
    }

    @Test fun popupFadesInOnEveryOpening() {
        repeat(2) {
            val scene = ImageComposeScene(900,600, coroutineContext = kotlinx.coroutines.Dispatchers.Unconfined) {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    SettingsPopupOverlay(Modifier.fillMaxSize(), {}) {
                        Surface(Modifier.fillMaxSize(), color = androidx.compose.ui.graphics.Color.White) {}
                    }
                }
            }
            try {
                fun centerAlpha(time: Long): Int = scene.render(time).use { image ->
                    val pixel = IntArray(1)
                    image.toComposeImageBitmap().readPixels(pixel, startX = 450, startY = 300, width = 1, height = 1)
                    pixel[0].ushr(24)
                }
                // A slow first paint must not consume the opening transition.
                assertEquals(0, centerAlpha(2_000_000_000), "Opening starts transparent even after a slow first paint")
                var middle = 0
                for (frame in 1..12) middle = centerAlpha(2_000_000_000 + frame * 16_666_667L)
                assertTrue(middle in 1..254, "Popup fades through an intermediate frame: $middle")
                for (frame in 13..32) centerAlpha(2_000_000_000 + frame * 16_666_667L)
                assertEquals(255, centerAlpha(2_550_000_000), "Opening finishes fully visible")
            } finally { scene.close() }
        }
    }

    @OptIn(androidx.compose.ui.InternalComposeUiApi::class)
    @Test fun popupFitsClientAreaAndDismissesWithoutAffectingContentClicks() {
        for ((width, height) in listOf(1280 to 900, 900 to 600)) {
            var closes = 0
            val scene = ImageComposeScene(width, height, coroutineContext = kotlinx.coroutines.Dispatchers.Unconfined) {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    SettingsPopupOverlay(Modifier.fillMaxSize(), { closes++ }) {
                        Surface(Modifier.fillMaxSize()) { Text("Settings popup") }
                    }
                }
            }
            try {
                scene.render().close()
                fun click(x: Float, y: Float) {
                    scene.sendPointerEvent(PointerEventType.Press, Offset(x,y), type = PointerType.Mouse)
                    scene.sendPointerEvent(PointerEventType.Release, Offset(x,y), type = PointerType.Mouse)
                    scene.render().close()
                }
                click(width / 2f, height / 2f)
                assertEquals(0, closes, "Click inside the popup must not dismiss")
                scene.sendKeyEvent(KeyEvent(Key.Escape, KeyEventType.KeyDown))
                assertEquals(0, closes)
                scene.sendKeyEvent(KeyEvent(Key.Escape, KeyEventType.KeyUp))
                assertEquals(1, closes, "Escape dismisses once")
                click(4f, 4f)
                assertEquals(2, closes, "Backdrop remains reachable in smaller windows")
            } finally { scene.close() }
        }
    }

    @Test fun desktopPreferencesPersistWithoutTouchingExistingAccounts() {
        val dir=kotlin.io.path.createTempDirectory("desktop-preferences-test").toFile()
        try {
            val file=File(dir,"preferences.properties")
            val a=DesktopPreferencesStore(PropertiesFileKeyValueStore(file,synchronous=true))
            a.set("zoom",1.25);a.set("normalize",true);a.set("hotkey.play_pause","Ctrl+Alt+P")
            val b=DesktopPreferencesStore(PropertiesFileKeyValueStore(file,synchronous=true))
            assertEquals(1.25,b.number("zoom",1.0));assertTrue(b.bool("normalize"));assertEquals("Ctrl+Alt+P",b.text("hotkey.play_pause"))
        } finally {dir.deleteRecursively()}
    }
    @Test fun shortcutParsingHandlesOrderAndRejectsInvalidBindings() {
        assertEquals(parseHotkey("Ctrl+Alt+F9"),parseHotkey("Alt+Ctrl+F9"))
        assertFailsWith<IllegalArgumentException> {parseHotkey("P")}
        assertFailsWith<IllegalStateException> {parseHotkey("Ctrl+Banana")}
        assertNotEquals(parseHotkey("Ctrl+Alt+P"),parseHotkey("Ctrl+Alt+Shift+P"))
    }
    @Test fun scrobblingUsesRulesCorrectionsAndListeningThresholds() {
        val track=Track("id","Song (Official Audio)","Artist - Topic",album="Album")
        val config=JSONObject().put("rules","""[{"field":"title","find":"Song","replace":"Corrected"}]""")
        assertEquals(Triple("Corrected","Artist","Album"),scrobbleMetadata(track,config))
        config.put("edits","""{"id":{"title":"Override","artist":"Correct artist"}}""")
        assertEquals(Triple("Override","Correct artist","Album"),scrobbleMetadata(track,config))
        assertEquals(90_000,scrobbleThreshold(180_000,50.0,4.0))
        assertEquals(240_000,scrobbleThreshold(900_000,50.0,4.0))
        assertEquals(120_000,scrobbleThreshold(180_000,66.6666667,4.0))
        assertEquals(ScrobbleApi.signature(mapOf("b" to "2","a" to "1"),"secret"),ScrobbleApi.signature(mapOf("a" to "1","b" to "2"),"secret"))
    }
    @Test fun allSectionsRenderAtSmallAndLargeSizesAndCloseWorks() {
        val dir=kotlin.io.path.createTempDirectory("settings-render-test").toFile()
        val stores=DesktopDataStores(dir)
        val now=object:com.auralis.music.ui.settings.NowPlayingControl {
            override val track=kotlinx.coroutines.flow.MutableStateFlow<Track?>(null)
            override val isPlaying=kotlinx.coroutines.flow.MutableStateFlow(false)
            override val positionMs=kotlinx.coroutines.flow.MutableStateFlow(0L)
            override val durationMs=kotlinx.coroutines.flow.MutableStateFlow(0L)
            override fun seekTo(ms:Long) {}
        }
        val platform=java.lang.reflect.Proxy.newProxyInstance(javaClass.classLoader,arrayOf(com.auralis.music.ui.settings.SettingsPlatform::class.java)) {_,method,_->
            when(method.name) {"getNowPlaying"->now;"getAppVersion"->"Test";else->null}
        } as com.auralis.music.ui.settings.SettingsPlatform
        for(width in listOf(1080,720)) for(tab in SettingsTab.entries) {
            var closes=0
            val scene=ImageComposeScene(width,720) {
                MaterialTheme(colorScheme=darkColorScheme()) {
                    CompositionLocalProvider(LocalSettingsStores provides stores,com.auralis.music.ui.settings.LocalSettingsPlatform provides platform) {
                        Surface(Modifier.fillMaxSize()) { SettingsWindowContent(stores,null,null,null,{closes++},tab) }
                    }
                }
            }
            try {
                repeat(3) {scene.render(it*16_666_667L).close()}
                if(tab in listOf(SettingsTab.GENERAL,SettingsTab.APPEARANCE,SettingsTab.PLAYBACK,SettingsTab.SCROBBLING)) {
                    scene.render(50_000_000).use {image->image.encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)!!.use {data->
                        File("build/screens/settings-${tab.name.lowercase()}-$width.png").apply {parentFile.mkdirs();writeBytes(data.bytes)}
                    }}
                }
                scene.sendPointerEvent(PointerEventType.Press,Offset(width-45f,38f),type=PointerType.Mouse)
                scene.sendPointerEvent(PointerEventType.Release,Offset(width-45f,38f),type=PointerType.Mouse)
                scene.render(90_000_000).close();assertEquals(1,closes,"Close from $tab at $width")
            } finally {scene.close()}
        }
        for ((width, height, light) in listOf(Triple(1280,900,false), Triple(900,600,false), Triple(900,600,true))) {
        val popup = ImageComposeScene(width,height) {
            MaterialTheme(colorScheme=if (light) lightColorScheme() else darkColorScheme()) {
                CompositionLocalProvider(LocalSettingsStores provides stores, com.auralis.music.ui.settings.LocalSettingsPlatform provides platform) {
                    SettingsPopupOverlay(Modifier.fillMaxSize(), {}) {
                        SettingsWindowContent(stores,null,null,null,{})
                    }
                }
            }
        }
        try {
            repeat(32) { popup.render(it*16_666_667L).close() }
            popup.render(550_000_000).use { image -> image.encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)!!.use { data ->
                File("build/screens/settings-popup-$width-${if (light) "light" else "dark"}.png").apply { parentFile.mkdirs(); writeBytes(data.bytes) }
            } }
        } finally { popup.close() }
        }
    }
}
