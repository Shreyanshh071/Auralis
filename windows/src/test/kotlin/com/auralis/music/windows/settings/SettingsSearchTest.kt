package com.auralis.music.windows.settings

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SettingsSearchTest {
    @Test fun findsSettingsInsideEveryTabAndSaysWhereTheyLive() {
        assertEquals(SettingsTab.PLAYBACK, searchSettings("crossfade").first().tab)
        assertEquals("Normalize volume", searchSettings("normalize").first().title)
        assertEquals(SettingsTab.GENERAL, searchSettings("tray").first().tab)
        assertEquals(SettingsTab.DISCORD, searchSettings("paused").first().tab)
        assertEquals(SettingsTab.AI, searchSettings("api key").first().tab)
        // Every word has to match, in any field.
        assertTrue(searchSettings("lyrics font").any { it.title == "Lyrics font size" })
        assertTrue(searchSettings("zzzz").isEmpty())
        assertTrue(SettingsTab.PLAYBACK in matchingSettingsTabs("crossfade"))
    }

    @Test fun everyRowTitleAndGroupIsOneTheSettingsWindowDraws() {
        // A jump finds its row by title and falls back to its group heading, so both must be the
        // strings the panes pass to the setting rows and groups.
        val root = File("src/main/kotlin/com/auralis/music/windows")
        val source = listOf("settings/SettingsWindow.kt", "settings/DesktopSettingsDialog.kt", "discord/DiscordSettingsPopup.kt")
            .joinToString("\n") { File(root, it).readText() }
        val missing = settingEntries.flatMap { entry ->
            listOfNotNull(entry.title.takeIf { entry.onRow }, entry.group.takeUnless { it == "Global hotkeys" || it == "Discord" })
        }.distinct().filter { "\"$it\"" !in source }
        assertTrue(missing.isEmpty(), "Not drawn by any settings pane: $missing")
    }
}
