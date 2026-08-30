package com.auralis.music.data.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

val Context.storageDataStore: DataStore<Preferences> by preferencesDataStore(name = "auralis_storage")

data class StorageSettings(
    val songCacheEnabled: Boolean = true,
    val maxSongCacheSizeMb: Int = 1100, // 1.1 GB default matching screenshot
    val maxImageCacheSizeMb: Int = 537  // 537 MB default matching screenshot
)

class StorageDataStore(private val context: Context) {
    private val dataStore = context.storageDataStore

    companion object {
        val SONG_CACHE_ENABLED = booleanPreferencesKey("song_cache_enabled")
        val MAX_SONG_CACHE_SIZE_MB = intPreferencesKey("max_song_cache_size_mb")
        val MAX_IMAGE_CACHE_SIZE_MB = intPreferencesKey("max_image_cache_size_mb")
    }

    val settingsFlow: Flow<StorageSettings> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            StorageSettings(
                songCacheEnabled = preferences[SONG_CACHE_ENABLED] ?: true,
                maxSongCacheSizeMb = preferences[MAX_SONG_CACHE_SIZE_MB] ?: 1100,
                maxImageCacheSizeMb = preferences[MAX_IMAGE_CACHE_SIZE_MB] ?: 537
            )
        }

    suspend fun setSongCacheEnabled(enabled: Boolean) {
        dataStore.edit { it[SONG_CACHE_ENABLED] = enabled }
    }

    suspend fun setMaxSongCacheSizeMb(sizeMb: Int) {
        dataStore.edit { it[MAX_SONG_CACHE_SIZE_MB] = sizeMb }
    }

    suspend fun setMaxImageCacheSizeMb(sizeMb: Int) {
        dataStore.edit { it[MAX_IMAGE_CACHE_SIZE_MB] = sizeMb }
    }
}
