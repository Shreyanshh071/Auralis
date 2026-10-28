package com.auralis.music

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.auralis.music.data.datastore.AppearanceSettingsDataStore
import com.auralis.music.domain.model.AppearanceSettings
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppearanceDefaultsTest {
    @Test
    fun `fresh install matches phone appearance with liquid glass off by default`() {
        val snapshot = javaClass.getResourceAsStream("/appearance/personal-defaults.json")!!
            .bufferedReader().use { JSONObject(it.readText()) }
        val preferences = mutablePreferencesOf()
        snapshot.keys().forEach { key ->
            when (val value = snapshot.get(key)) {
                is Boolean -> preferences[booleanPreferencesKey(key)] = value
                is Int -> preferences[intPreferencesKey(key)] = value
                is Number -> preferences[floatPreferencesKey(key)] = value.toFloat()
                is String -> preferences[stringPreferencesKey(key)] = value
                else -> error("Unsupported appearance setting: $key")
            }
        }
        assertEquals(50, snapshot.length())
        val phoneSettings = AppearanceSettingsDataStore.fromPreferences(preferences)
        assertEquals(phoneSettings.copy(liquidGlass = false), AppearanceSettings())
        assertEquals(phoneSettings.copy(liquidGlass = false), AppearanceSettingsDataStore.fromPreferences(emptyPreferences()))
        assertTrue(phoneSettings.liquidGlass) // An existing saved choice still wins.
    }

    @Test
    fun `saved choices override defaults including legacy new player flag`() {
        val preferences = mutablePreferencesOf(
            AppearanceSettingsDataStore.NEW_PLAYER_DESIGN to true,
            AppearanceSettingsDataStore.LIQUID_GLASS to false,
            AppearanceSettingsDataStore.LYRICS_ANIMATION to "Fade",
            AppearanceSettingsDataStore.MINI_PLAYER_BG_STYLE to "Blur",
            AppearanceSettingsDataStore.SHOW_TOP_PLAYLIST to true
        )
        val settings = AppearanceSettingsDataStore.fromPreferences(preferences)
        assertEquals("New player", settings.playerDesign)
        assertTrue(settings.newPlayerDesign)
        assertFalse(settings.liquidGlass)
        assertEquals("Fade", settings.lyricsAnimation)
        assertEquals("Blur", settings.miniPlayerBackgroundStyle)
        assertTrue(settings.showTopPlaylist)
        preferences[AppearanceSettingsDataStore.PLAYER_DESIGN] = "Immersive player"
        assertEquals("Immersive player", AppearanceSettingsDataStore.fromPreferences(preferences).playerDesign)
    }

    @Test
    fun `legacy experimental lyrics still migrate and saved slider aliases normalize`() {
        val preferences = mutablePreferencesOf(
            AppearanceSettingsDataStore.EXPERIMENTAL_LYRICS to true,
            AppearanceSettingsDataStore.PLAYER_SLIDER_STYLE to "Thin Line"
        )
        val settings = AppearanceSettingsDataStore.fromPreferences(preferences)
        assertEquals("MetroLyrics", settings.lyricsAnimation)
        assertEquals("Slim", settings.playerSliderStyle)
        preferences[AppearanceSettingsDataStore.LYRICS_ANIMATION] = "Spicy Lyrics"
        assertEquals("Spicy Lyrics", AppearanceSettingsDataStore.fromPreferences(preferences).lyricsAnimation)
    }
}
