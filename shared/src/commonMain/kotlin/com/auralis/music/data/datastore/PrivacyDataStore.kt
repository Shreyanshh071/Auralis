package com.auralis.music.data.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import com.auralis.music.domain.model.PrivacySettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

class PrivacyDataStore(private val dataStore: DataStore<Preferences>) {

    companion object {
        val PAUSE_LISTEN_HISTORY = booleanPreferencesKey("pause_listen_history")
        val PAUSE_SEARCH_HISTORY = booleanPreferencesKey("pause_search_history")
        val DISABLE_SCREENSHOT = booleanPreferencesKey("disable_screenshot")
    }

    val settingsFlow: Flow<PrivacySettings> = dataStore.data
        .catch { exception ->
            if (exception is IOException) emit(emptyPreferences()) else throw exception
        }
        .map { preferences ->
            PrivacySettings(
                pauseListenHistory = preferences[PAUSE_LISTEN_HISTORY] ?: false,
                pauseSearchHistory = preferences[PAUSE_SEARCH_HISTORY] ?: false,
                disableScreenshot = preferences[DISABLE_SCREENSHOT] ?: false
            )
        }

    suspend fun setPauseListenHistory(paused: Boolean) {
        dataStore.edit { it[PAUSE_LISTEN_HISTORY] = paused }
    }

    suspend fun setPauseSearchHistory(paused: Boolean) {
        dataStore.edit { it[PAUSE_SEARCH_HISTORY] = paused }
    }

    suspend fun setDisableScreenshot(disabled: Boolean) {
        dataStore.edit { it[DISABLE_SCREENSHOT] = disabled }
    }
}
