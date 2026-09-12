package com.auralis.music.data.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.auralis.music.domain.model.Track
import com.auralis.music.domain.model.TrackSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.booleanPreferencesKey

val Context.queueDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "auralis_queue",
    corruptionHandler = ReplaceFileCorruptionHandler {
        emptyPreferences()
    }
)

data class PersistedQueue(
    val tracks: List<Track> = emptyList(),
    val currentIndex: Int = -1,
    val lastPositionMs: Long = 0L,
    val isUserQueue: Boolean = false,
    val isShuffled: Boolean = false,
    val repeatMode: String = "OFF"
)

class QueueDataStore(private val context: Context) {
    private val dataStore = context.queueDataStore

    val persistedQueueFlow: Flow<PersistedQueue> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { prefs ->
            val json = prefs[QUEUE_JSON] ?: "[]"
            val index = prefs[CURRENT_INDEX] ?: -1
            val pos = prefs[LAST_POSITION_MS] ?: 0L
            val isUser = prefs[IS_USER_QUEUE] ?: false
            val shuffled = prefs[IS_SHUFFLED] ?: false
            val repeat = prefs[REPEAT_MODE] ?: "OFF"
            PersistedQueue(
                tracks = deserializeTracks(json),
                currentIndex = index,
                lastPositionMs = pos,
                isUserQueue = isUser,
                isShuffled = shuffled,
                repeatMode = repeat
            )
        }

    suspend fun saveQueue(
        tracks: List<Track>,
        currentIndex: Int,
        positionMs: Long = 0L,
        isUserQueue: Boolean = false,
        isShuffled: Boolean = false,
        repeatMode: String = "OFF"
    ) {
        val json = serializeTracks(tracks)
        dataStore.edit { prefs ->
            prefs[QUEUE_JSON] = json
            prefs[CURRENT_INDEX] = currentIndex
            prefs[LAST_POSITION_MS] = positionMs
            prefs[IS_USER_QUEUE] = isUserQueue
            prefs[IS_SHUFFLED] = isShuffled
            prefs[REPEAT_MODE] = repeatMode
        }
    }

    suspend fun clearPersistedQueue() {
        dataStore.edit { prefs ->
            prefs.remove(QUEUE_JSON)
            prefs.remove(CURRENT_INDEX)
            prefs.remove(LAST_POSITION_MS)
            prefs.remove(IS_USER_QUEUE)
            prefs.remove(IS_SHUFFLED)
            prefs.remove(REPEAT_MODE)
        }
    }

    companion object {
        private val QUEUE_JSON = stringPreferencesKey("persisted_queue_json")
        private val CURRENT_INDEX = intPreferencesKey("persisted_current_index")
        private val LAST_POSITION_MS = longPreferencesKey("persisted_position_ms")
        private val IS_USER_QUEUE = booleanPreferencesKey("persisted_is_user_queue")
        private val IS_SHUFFLED = booleanPreferencesKey("persisted_is_shuffled")
        private val REPEAT_MODE = stringPreferencesKey("persisted_repeat_mode")

        internal fun serializeTracks(tracks: List<Track>): String {
            val array = JSONArray()
            for (t in tracks) {
                val obj = JSONObject().apply {
                    put("id", t.id)
                    put("title", t.title)
                    put("artist", t.artist)
                    put("album", t.album ?: "")
                    put("duration", t.duration)
                    put("thumbnail", t.thumbnail)
                    put("source", t.source.name)
                    put("channelTitle", t.channelTitle ?: "")
                    put("views", t.views ?: "")
                    put("dominantColor", t.dominantColor ?: 0)
                }
                array.put(obj)
            }
            return array.toString()
        }

        internal fun deserializeTracks(jsonString: String): List<Track> {
            val result = mutableListOf<Track>()
            try {
                val array = JSONArray(jsonString)
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val id = obj.optString("id")
                    val title = obj.optString("title")
                    val artist = obj.optString("artist")
                    val album = obj.optString("album").ifBlank { null }
                    val duration = obj.optLong("duration", 0L)
                    val thumbnail = obj.optString("thumbnail")
                    val sourceStr = obj.optString("source", TrackSource.YOUTUBE.name)
                    val source = try { TrackSource.valueOf(sourceStr) } catch (_: Exception) { TrackSource.YOUTUBE }
                    val channelTitle = obj.optString("channelTitle").ifBlank { null }
                    val views = obj.optString("views").ifBlank { null }
                    val domColor = obj.optInt("dominantColor", 0).let { if (it != 0) it else null }

                    if (id.isNotBlank() && title.isNotBlank()) {
                        result.add(
                            Track(
                                id = id,
                                title = title,
                                artist = artist,
                                album = album,
                                duration = duration,
                                thumbnail = thumbnail,
                                source = source,
                                channelTitle = channelTitle,
                                views = views,
                                dominantColor = domColor
                            )
                        )
                    }
                }
            } catch (_: Exception) {
                // Return empty list on parse failure
            }
            return result
        }
    }
}
