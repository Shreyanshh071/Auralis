package com.auralis.music.data.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

data class UpdaterSettings(
    val autoCheckUpdates: Boolean = true,
    val enableNotifications: Boolean = true
)

class UpdaterDataStore(private val dataStore: DataStore<Preferences>) {

    companion object {
        val AUTO_CHECK_UPDATES = booleanPreferencesKey("auto_check_updates")
        val ENABLE_NOTIFICATIONS = booleanPreferencesKey("enable_notifications")
    }

    val settingsFlow: Flow<UpdaterSettings> = dataStore.data
        .catch { exception ->
            if (exception is IOException) emit(emptyPreferences()) else throw exception
        }
        .map { preferences ->
            UpdaterSettings(
                autoCheckUpdates = preferences[AUTO_CHECK_UPDATES] ?: true,
                enableNotifications = preferences[ENABLE_NOTIFICATIONS] ?: true
            )
        }

    suspend fun setAutoCheckUpdates(enabled: Boolean) {
        dataStore.edit { it[AUTO_CHECK_UPDATES] = enabled }
    }

    suspend fun setEnableNotifications(enabled: Boolean) {
        dataStore.edit { it[ENABLE_NOTIFICATIONS] = enabled }
    }
}
