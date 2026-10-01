package com.auralis.music.data.datastore

import android.content.Context
import com.auralis.music.domain.recommendations.PlaylistListeningStats
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

/** Local, playlist-ID keyed listening totals; titles can change or be shared by other playlists. */
object PlaylistListeningStore {
    private const val PREFS = "playlist_listening"
    private const val STATS = "stats"
    private val mutableStats = MutableStateFlow<Map<String, PlaylistListeningStats>>(emptyMap())
    val stats: StateFlow<Map<String, PlaylistListeningStats>> = mutableStats
    private var loaded = false

    @Synchronized
    fun load(context: Context) {
        if (loaded) return
        val json = runCatching {
            JSONObject(context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(STATS, "{}") ?: "{}")
        }.getOrDefault(JSONObject())
        val parsed = mutableMapOf<String, PlaylistListeningStats>()
        val keys = json.keys()
        while (keys.hasNext()) {
            val id = keys.next()
            val value = json.optJSONObject(id) ?: continue
            parsed[id] = PlaylistListeningStats(value.optInt("sessions").coerceAtLeast(0), value.optLong("listenedMs").coerceAtLeast(0L))
        }
        mutableStats.value = parsed
        loaded = true
    }

    @Synchronized
    fun recordSession(context: Context, playlistId: String) {
        if (playlistId.isBlank() || playlistId.startsWith("smart_")) return
        load(context)
        val old = mutableStats.value[playlistId] ?: PlaylistListeningStats()
        save(context, playlistId, old.copy(sessions = old.sessions + 1))
    }

    @Synchronized
    fun recordListening(context: Context, playlistId: String, durationMs: Long) {
        if (durationMs <= 0 || playlistId.isBlank() || playlistId.startsWith("smart_")) return
        load(context)
        val old = mutableStats.value[playlistId] ?: PlaylistListeningStats()
        save(context, playlistId, old.copy(listenedMs = old.listenedMs + durationMs))
    }

    @Synchronized
    fun clear(context: Context) {
        mutableStats.value = emptyMap()
        loaded = true
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(STATS).apply()
    }

    private fun save(context: Context, playlistId: String, value: PlaylistListeningStats) {
        val updated = mutableStats.value + (playlistId to value)
        mutableStats.value = updated
        val json = JSONObject()
        updated.forEach { (id, entry) ->
            json.put(id, JSONObject().put("sessions", entry.sessions).put("listenedMs", entry.listenedMs))
        }
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(STATS, json.toString()).apply()
    }
}
