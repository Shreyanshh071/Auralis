package com.auralis.music.data.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.auralis.music.domain.recognition.RecognitionHistoryItem
import com.auralis.music.domain.recognition.RecognitionResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.recognitionHistoryDataStore: DataStore<Preferences> by preferencesDataStore(name = "recognition_history")

class MusicRecognitionHistoryDataStore(
    private val context: Context
) {
    private val dataStore = context.recognitionHistoryDataStore

    private val json = Json {
        isLenient = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    companion object {
        private val HISTORY_KEY = stringPreferencesKey("music_recognition_history_json")
        private const val MAX_HISTORY_LIMIT = 50
    }

    val historyFlow: Flow<List<RecognitionHistoryItem>> = dataStore.data.map { preferences ->
        val raw = preferences[HISTORY_KEY]
        if (raw.isNullOrBlank()) {
            emptyList()
        } else {
            try {
                json.decodeFromString<List<RecognitionHistoryItem>>(raw)
            } catch (e: Exception) {
                emptyList()
            }
        }
    }

    suspend fun addRecognition(result: RecognitionResult) {
        val entry = RecognitionHistoryItem(
            trackId = result.trackId,
            title = result.title,
            artist = result.artist,
            album = result.album,
            coverArtUrl = result.coverArtUrl,
            coverArtHqUrl = result.coverArtHqUrl,
            genre = result.genre,
            releaseDate = result.releaseDate,
            label = result.label,
            recognizedAtEpochMillis = System.currentTimeMillis()
        )

        dataStore.edit { preferences ->
            val currentRaw = preferences[HISTORY_KEY]
            val current = if (currentRaw.isNullOrBlank()) {
                emptyList()
            } else {
                try {
                    json.decodeFromString<List<RecognitionHistoryItem>>(currentRaw)
                } catch (e: Exception) {
                    emptyList()
                }
            }

            val next = (listOf(entry) + current.filterNot { it.trackId == entry.trackId || (it.title.equals(entry.title, true) && it.artist.equals(entry.artist, true)) })
                .take(MAX_HISTORY_LIMIT)

            preferences[HISTORY_KEY] = json.encodeToString(next)
        }
    }

    suspend fun removeRecognition(trackId: String) {
        dataStore.edit { preferences ->
            val currentRaw = preferences[HISTORY_KEY] ?: return@edit
            val current = try {
                json.decodeFromString<List<RecognitionHistoryItem>>(currentRaw)
            } catch (e: Exception) {
                emptyList()
            }
            val next = current.filterNot { it.trackId == trackId }
            preferences[HISTORY_KEY] = json.encodeToString(next)
        }
    }

    suspend fun clearHistory() {
        dataStore.edit { preferences ->
            preferences.remove(HISTORY_KEY)
        }
    }
}
