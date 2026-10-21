package com.auralis.music.ui.i18n

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class DesktopStringsTest {
    @Test fun desktopLabelsUseSharedAndDesktopTranslationsWithEnglishFallback() {
        try {
            DesktopStrings.install(Locale.GERMAN)
            assertEquals("Einstellungen", desktopText("Settings"))
            assertEquals("Globale Tastenkürzel", desktopText("Global hotkeys"))
            assertEquals("Interface copy with no translation yet", desktopText("Interface copy with no translation yet"))
            DesktopStrings.install(Locale.forLanguageTag("fr-CA"))
            assertEquals("Paramètres", desktopText("Settings"))
            assertEquals("Notifications de morceaux", desktopText("Song notifications"))
            DesktopStrings.install(Locale.forLanguageTag("hi"))
            assertEquals("सेटिंग्स", desktopText("Settings"))
            assertEquals("सिस्टम टाइटल बार", desktopText("System title bar"))
        } finally { DesktopStrings.install(Locale.ENGLISH) }
    }

    @Test
    fun readsThePhonesStringsWithEscapesAndArguments() {
        DesktopStrings.install(Locale.ENGLISH)
        assertEquals("Auralis", str(Strings.app_name))
        assertEquals("Added \"Blinding Lights\" to room queue", str(Strings.added_x_to_room_queue, "Blinding Lights"))
        assertEquals("Room: Party (3 connected)", str(Strings.room_x_x_connected, "Party", 3))
    }

    @Test
    fun usesTheTranslationForTheLocaleAndEnglishForMissingOnes() {
        DesktopStrings.install(Locale.GERMAN)
        val german = str(Strings.history)
        DesktopStrings.install(Locale.ENGLISH)
        assertEquals("History", str(Strings.history))
        assertNotEquals("History", german)
    }

    @Test
    fun mapsLocalesToAndroidFolders() {
        assertEquals(listOf("values-zh-rCN", "values-zh"), DesktopStrings.folderCandidates(Locale.forLanguageTag("zh-CN")))
        assertEquals("values-in", DesktopStrings.folderCandidates(Locale.forLanguageTag("id")).last())
        assertEquals("values-b+sr+Latn", DesktopStrings.folderCandidates(Locale.forLanguageTag("sr-Latn")).first())
    }

    @Test
    fun unescapesLikeAndroid() {
        assertEquals("It's \"here\"", DesktopStrings.unescape("""It\'s \"here\""""))
        assertEquals("a b", DesktopStrings.unescape("a   \n  b"))
        assertEquals("line\nbreak", DesktopStrings.unescape("""line\nbreak"""))
    }
}
