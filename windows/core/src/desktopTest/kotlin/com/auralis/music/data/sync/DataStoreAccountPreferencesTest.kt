package com.auralis.music.data.sync

import androidx.datastore.preferences.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.nio.file.Files
import kotlin.test.*

class DataStoreAccountPreferencesTest {
    @Test fun typedSettingsRestoreWithoutTouchingDevicePreferencesAndJournalSurvivesRestart() = runBlocking {
        val dir = Files.createTempDirectory("preferences-sync").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val a = PreferenceDataStoreFactory.create(scope = scope) { dir.resolve("appearance.preferences_pb") }
        val p = PreferenceDataStoreFactory.create(scope = scope) { dir.resolve("player.preferences_pb") }
        try {
            a.edit { it[stringPreferencesKey("display_density")] = "125%"; it[booleanPreferencesKey("high_refresh_rate")] = false }
            p.edit { it[floatPreferencesKey("volume")] = .3f; it[stringPreferencesKey("private_token")] = "local-only" }
            val local = DataStoreAccountPreferences(a, p)
            local.apply(mapOf("a.mini_player_bg_style" to "Live Mesh", "a.lyrics_animation" to "Spicy Lyrics",
                "a.lyrics_text_size" to "26.0", "a.liquid_glass" to "true", "p.playback_rate" to "1.25",
                "a.mini_player_design" to "Classic mini player", "a.player_design" to "Old player",
                "p.private_token" to "must-not-upload", "a.display_density" to "200%"))
            val values = local.read()
            assertEquals("Live Mesh", values["a.mini_player_bg_style"])
            assertEquals("Spicy Lyrics", values["a.lyrics_animation"])
            assertEquals("26.0", values["a.lyrics_text_size"])
            assertEquals("true", values["a.liquid_glass"])
            assertEquals("1.25", values["p.playback_rate"])
            assertFalse(values.keys.any { it.contains("token") || it.contains("volume") || it.contains("density") })
            assertEquals("125%", a.data.first()[stringPreferencesKey("display_density")])
            assertEquals(false, a.data.first()[booleanPreferencesKey("high_refresh_rate")])
            assertEquals(.3f, p.data.first()[floatPreferencesKey("volume")])
            assertEquals("local-only", p.data.first()[stringPreferencesKey("private_token")])
            assertEquals(false, a.data.first()[booleanPreferencesKey("new_mini_player_design")])
            assertEquals(false, a.data.first()[booleanPreferencesKey("new_player_design")])
            DataStorePreferencesJournal(p).write("saved journal")
            assertEquals("saved journal", DataStorePreferencesJournal(p).read())
        } finally {
            scope.coroutineContext[Job]?.cancelAndJoin()
            dir.listFiles()?.forEach { it.delete() }; dir.delete()
        }
    }
}
