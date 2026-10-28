package com.auralis.music.windows.settings

import androidx.datastore.preferences.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import java.io.File
import kotlin.test.*

class InstallerDefaultsTest {
    @Test fun packagedDefaultsReproduceTheNormalProfileWithoutPersonalData() = runBlocking {
        val dir = java.nio.file.Files.createTempDirectory("packaged-defaults").toFile()
        try {
            assertTrue(InstallerDefaults.install(dir))
            val captured = InstallerDefaults.snapshot(dir)
            val appearance = captured.getJSONObject("stores").getJSONObject("auralis_appearance_settings")
            assertEquals("White", appearance.getJSONObject("color_palette").getString("value"))
            assertEquals("Dark Mode", appearance.getJSONObject("app_theme").getString("value"))
            assertTrue(appearance.getJSONObject("liquid_glass").getBoolean("value"))
            assertEquals("Spicy Lyrics", appearance.getJSONObject("lyrics_animation").getString("value"))
            assertEquals("Left", appearance.getJSONObject("lyrics_text_position").getString("value"))
            val props = captured.getJSONObject("properties")
            assertEquals("false", props.getJSONObject("desktop-home.properties").getString("recentsGrid"))
            assertEquals("true", props.getJSONObject("desktop-system.properties").getString("closeToTray"))
            assertFalse(File(dir, "account-session.bin").exists())
            assertFalse(File(dir, "connect.properties").exists())
            assertFalse(File(dir, "history.json").exists())
            assertFalse(File(dir, "library.json").exists())
        } finally { dir.deleteRecursively() }
    }

    @Test fun firstLaunchReceivesDefaultsButLaterChangesAndExistingProfilesAreUntouched() = runBlocking {
        val dir = java.nio.file.Files.createTempDirectory("release-defaults").toFile()
        val manifest = JSONObject("""{"version":1,"stores":{"auralis_appearance_settings":{"color_palette":{"type":"string","value":"White"}}},"properties":{"desktop-system.properties":{"closeToTray":"true"}}}""")
        try {
            assertTrue(InstallerDefaults.install(dir, manifest))
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val store = PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(dir, "settings/auralis_appearance_settings.preferences_pb") })
                assertEquals("White", store.data.first()[stringPreferencesKey("color_palette")])
                store.edit { it[stringPreferencesKey("color_palette")] = "Dynamic" }
                assertFalse(InstallerDefaults.install(dir, manifest))
                assertEquals("Dynamic", store.data.first()[stringPreferencesKey("color_palette")])
            } finally { scope.coroutineContext[Job]?.cancelAndJoin() }
            assertTrue(File(dir, "desktop-system.properties").readText().contains("closeToTray=true"))
        } finally { dir.deleteRecursively() }
    }

    @Test fun snapshotOmitsAccountTokensProxyCredentialsAndComputerPaths() = runBlocking {
        val dir = java.nio.file.Files.createTempDirectory("sanitized-defaults").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val file = File(dir, "settings/discord_rpc_preferences.preferences_pb")
            val store = PreferenceDataStoreFactory.create(scope = scope, produceFile = { file })
            store.edit {
                it[stringPreferencesKey("discord_token")] = "private-token"
                it[stringPreferencesKey("discord_username")] = "private-user"
                it[booleanPreferencesKey("discord_enable_rich_presence")] = true
            }
            scope.coroutineContext[Job]?.cancelAndJoin()
            File(dir, "account-session.bin").writeText("account-secret")
            val snapshot = InstallerDefaults.snapshot(dir).toString()
            assertFalse(snapshot.contains("private-token"))
            assertFalse(snapshot.contains("private-user"))
            assertFalse(snapshot.contains("account-secret"))
            assertTrue(snapshot.contains("discord_enable_rich_presence"))
            assertFalse(InstallerDefaults.safeKey("spicy_lyrics_api_key"))
            val clean = InstallerDefaults.safeJson(JSONObject().put("bodyFont", "Default").put("proxy_password", "secret")
                .put("fontFile", "C:/Users/owner/private.ttf"))
            assertEquals(setOf("bodyFont"), clean.keySet())
        } finally { scope.cancel(); dir.deleteRecursively() }
    }

    @Test fun existingAccountProfileIsNeverReseeded() = runBlocking {
        val dir = java.nio.file.Files.createTempDirectory("existing-profile").toFile()
        try {
            File(dir, "library.json").writeText("{}")
            assertFalse(InstallerDefaults.install(dir, JSONObject().put("version", 1)))
            assertFalse(File(dir, "installer-defaults-version").exists())
        } finally { dir.deleteRecursively() }
    }
}
