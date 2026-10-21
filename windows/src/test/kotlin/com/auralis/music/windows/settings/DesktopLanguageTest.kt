package com.auralis.music.windows.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.ImageComposeScene
import com.auralis.music.ui.i18n.DesktopStrings
import com.auralis.music.ui.i18n.StringLookup
import com.auralis.music.ui.i18n.desktopText
import com.auralis.music.util.PropertiesFileKeyValueStore
import com.auralis.music.windows.SidebarPage
import java.io.File
import java.util.Locale
import kotlin.test.*

class DesktopLanguageTest {
    @Test fun assameseSettingsRenderAtCompactAndLargeSizes() {
        val original = StringLookup.formatLocale()
        val directory = kotlin.io.path.createTempDirectory("assamese-settings-render").toFile()
        val stores = com.auralis.music.desktop.settings.DesktopDataStores(directory)
        try {
            DesktopStrings.install(Locale.forLanguageTag("as"))
            for (width in listOf(720,1080)) {
                val scene = ImageComposeScene(width,760) {
                    MaterialTheme(colorScheme = androidx.compose.material3.darkColorScheme()) {
                        androidx.compose.runtime.CompositionLocalProvider(com.auralis.music.data.datastore.LocalSettingsStores provides stores) {
                            androidx.compose.material3.Surface(androidx.compose.ui.Modifier.fillMaxSize()) {
                                SettingsWindowContent(stores,null,null,null,{},SettingsTab.GENERAL)
                            }
                        }
                    }
                }
                try {
                    repeat(3) { scene.render(it*16_666_667L).close() }
                    scene.render(60_000_000).use { picture -> picture.encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)!!.use { data ->
                        File("build/screens/settings-general-as-$width.png").apply { parentFile.mkdirs();writeBytes(data.bytes) }
                    } }
                } finally { scene.close() }
            }
        } finally { DesktopStrings.install(original) }
    }

    @Test fun everyLanguageInThePickerLoadsItsInterfaceTranslation() {
        val dir = kotlin.io.path.createTempDirectory("desktop-language-catalog-test").toFile()
        val original = StringLookup.formatLocale()
        try {
            val controller = DesktopLanguageController(PropertiesFileKeyValueStore(File(dir, "language.properties"), synchronous = true),
                Locale.ENGLISH, DesktopStrings::install)
            for (tag in controller.available()) {
                controller.apply(tag)
                assertEquals(tag, controller.currentTag())
                assertTrue(SidebarPage.HOME.label.isNotBlank())
                assertTrue(DesktopStrings.missingInterfaceCopy(tag).isEmpty(),
                    "Incomplete desktop catalog for $tag: ${DesktopStrings.missingInterfaceCopy(tag).take(8)}")
                if (Locale.forLanguageTag(tag).language != "en") assertNotEquals("Settings", desktopText("Settings"), "Settings translation for $tag")
            }
        } finally { DesktopStrings.install(original); dir.deleteRecursively() }
    }

    @Test fun assameseTranslatesTheWholeGeneralPaneAndHomeChrome() {
        val original = StringLookup.formatLocale()
        try {
            DesktopStrings.install(Locale.forLanguageTag("as"))
            val labels = listOf("Good evening", "Recents", "New playlist", "Shortcuts", "Familiar Artists",
                "Albums & singles", "Now playing", "Credits", "Main Artist", "Following", "Language", "Activity", "System",
                "App language", "Applies to translated app text", "Play history", "Record songs played on this PC",
                "Close to system tray", "Keep playing when the window is closed", "Song notifications",
                "Show the next song while Auralis is in the background", "Start at login",
                "Launch Auralis with your Windows account", "System title bar",
                "Use Windows window decorations after restarting Auralis") + SettingsTab.entries.map { it.englishLabel }.filter { it != "Discord" }
            labels.forEach { assertNotEquals(it,desktopText(it),"Untranslated Assamese interface label: $it") }
            assertEquals("শুভ সন্ধিয়া",desktopText("Good evening"))
            assertTrue(SettingsTab.GENERAL in matchingSettingsTabs("জাননী"),"Settings search must accept translated keywords")
            val metadata="Shreyanshh — Who Really Cares"
            assertTrue(desktopText("Transfer to %1\$s",metadata).contains(metadata),"Translation must preserve inserted metadata")
            assertTrue(desktopText("123 songs").contains("123"))
            assertNotEquals("123 songs",desktopText("123 songs"))
            assertTrue(desktopText("Loaded 7 models from OpenAI.").contains("OpenAI"))
            assertNotEquals("Loaded 7 models from OpenAI.",desktopText("Loaded 7 models from OpenAI."))
            assertNotEquals("Your 2026 Wrapped",desktopText("Your 2026 Wrapped"))
            assertTrue(desktopText("Your 2026 Wrapped").contains("2026"))
            assertNotEquals("Playlist · 123 songs",com.auralis.music.ui.i18n.desktopCollectionCaption("Playlist · 123 songs"))
            assertEquals("Artist — Song",com.auralis.music.ui.i18n.desktopCollectionCaption("Artist — Song"))
        } finally { DesktopStrings.install(original) }
    }

    @Test fun languagePersistsAndSystemDefaultCanBeRestored() {
        val dir = kotlin.io.path.createTempDirectory("desktop-language-test").toFile()
        val original = StringLookup.formatLocale()
        try {
            fun controller() = DesktopLanguageController(
                PropertiesFileKeyValueStore(File(dir, "language.properties"), synchronous = true), Locale.FRENCH,
                DesktopStrings::install
            )
            val first = controller()
            first.install()
            assertEquals("Paramètres", desktopText("Settings"))
            first.apply("de")
            assertEquals("Einstellungen", desktopText("Settings"))
            val restored = controller()
            restored.install()
            assertEquals("de", restored.currentTag())
            assertEquals("Einstellungen", desktopText("Settings"))
            assertFailsWith<IllegalArgumentException> { restored.apply("not-a-language") }
            assertEquals("de", restored.currentTag())
            restored.apply("")
            assertEquals("", restored.currentTag())
            assertEquals("Paramètres", desktopText("Settings"))
        } finally { DesktopStrings.install(original); dir.deleteRecursively() }
    }

    @Test fun openDesktopLabelsRecomposeWithoutReopeningThePage() {
        val original = StringLookup.formatLocale()
        DesktopStrings.install(Locale.ENGLISH)
        var visible = emptyList<String>()
        val scene = ImageComposeScene(600,240) {
            MaterialTheme {
                val labels = listOf(SidebarPage.HOME.label, SettingsTab.GENERAL.label,
                    desktopText("App language"), desktopText("Close to system tray"))
                SideEffect { visible = labels }
                Column { labels.forEach { Text(it) } }
            }
        }
        try {
            scene.render().close()
            assertEquals("Home", visible.first())
            DesktopStrings.install(Locale.GERMAN)
            repeat(2) { scene.render().close() }
            assertEquals("Startseite", visible.first())
            assertEquals("App-Sprache", visible[2])
            assertEquals("Beim Schließen im Infobereich behalten", visible[3])
            assertTrue(matchingSettingsTabs("Tastenkürzel").contains(SettingsTab.HOTKEYS))
            DesktopStrings.install(Locale.forLanguageTag("hi"))
            repeat(2) { scene.render().close() }
            assertEquals("होम", visible.first())
            assertEquals("ऐप की भाषा", visible[2])
        } finally { scene.close(); DesktopStrings.install(original) }
    }
}
