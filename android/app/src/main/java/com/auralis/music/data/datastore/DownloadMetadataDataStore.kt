package com.auralis.music.data.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import com.auralis.music.data.download.DownloadMetadataChoice
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

val Context.downloadMetadataDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "auralis_download_metadata",
    corruptionHandler = ReplaceFileCorruptionHandler {
        emptyPreferences()
    }
)

data class DownloadMetadataSettings(
    val choice: DownloadMetadataChoice = DownloadMetadataChoice(),
    val askEveryDownload: Boolean = true
)

class DownloadMetadataDataStore(context: Context) {
    private val dataStore = context.applicationContext.downloadMetadataDataStore

    companion object {
        val COVER_ART = booleanPreferencesKey("cover_art")
        val LYRICS = booleanPreferencesKey("lyrics")
        val ASK_EVERY_DOWNLOAD = booleanPreferencesKey("ask_every_download")
    }

    val settingsFlow: Flow<DownloadMetadataSettings> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            DownloadMetadataSettings(
                choice = DownloadMetadataChoice(
                    coverArt = preferences[COVER_ART] ?: true,
                    lyrics = preferences[LYRICS] ?: true
                ),
                askEveryDownload = preferences[ASK_EVERY_DOWNLOAD] ?: true
            )
        }

    suspend fun setChoice(choice: DownloadMetadataChoice) {
        dataStore.edit {
            it[COVER_ART] = choice.coverArt
            it[LYRICS] = choice.lyrics
        }
    }

    suspend fun setAskEveryDownload(ask: Boolean) {
        dataStore.edit { it[ASK_EVERY_DOWNLOAD] = ask }
    }
}
