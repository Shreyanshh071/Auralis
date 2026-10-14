package com.auralis.music.windows.player

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.auralis.music.desktop.library.JsonFileStore
import com.auralis.music.desktop.library.TrackJson
import com.auralis.music.domain.model.Track
import com.auralis.music.ui.theme.ArtworkPalette
import com.auralis.music.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class SavedPlaybackSession(
    val track: Track,
    val queue: List<Track> = listOf(track),
    val queueIndex: Int = 0,
    val artworkUrl: String? = track.thumbnail.takeIf { it.isNotBlank() },
    val palette: ArtworkPalette? = null
)

/**
 * Persists the user's last played song, queue, and artwork theme palette across app launches.
 * When the app closes and re-opens, the last played track and its exact artwork theme are restored
 * immediately on the very first frame, avoiding any grayish fallback state.
 */
object DesktopPlaybackSessionStore {
    private const val TAG = "PlaybackSession"
    private var store: JsonFileStore? = null
    @Volatile private var cachedSession: SavedPlaybackSession? = null

    fun init(dataDir: File) {
        val file = File(dataDir, "playback-session.json")
        store = JsonFileStore(file)
        cachedSession = loadInternal(file, dataDir)
    }

    fun currentSession(): SavedPlaybackSession? = cachedSession

    private fun loadInternal(sessionFile: File, dataDir: File): SavedPlaybackSession? {
        try {
            if (sessionFile.isFile) {
                val json = JSONObject(sessionFile.readText())
                val trackObj = json.optJSONObject("track")
                if (trackObj != null) {
                    val track = TrackJson.decode(trackObj)
                    val queueArray = json.optJSONArray("queue")
                    val queue = if (queueArray != null && queueArray.length() > 0) {
                        List(queueArray.length()) { i -> TrackJson.decode(queueArray.getJSONObject(i)) }
                    } else listOf(track)
                    val queueIndex = json.optInt("queueIndex", 0).coerceIn(0, (queue.size - 1).coerceAtLeast(0))
                    val artworkUrl = json.optString("artworkUrl").ifBlank { track.thumbnail.takeIf { it.isNotBlank() } }
                    val paletteObj = json.optJSONObject("palette")
                    val palette = paletteObj?.let(::decodePalette)
                    Log.i(TAG, "Restored last played song: '${track.title}' by ${track.artist}")
                    return SavedPlaybackSession(track, queue, queueIndex, artworkUrl, palette)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read playback-session.json: ${e.message}")
        }

        // Fallback: If no session file exists yet, recover last played track from history.json!
        try {
            val historyFile = File(dataDir, "history.json")
            if (historyFile.isFile) {
                val json = JSONObject(historyFile.readText())
                val histArray = json.optJSONArray("history")
                if (histArray != null && histArray.length() > 0) {
                    val firstEntry = histArray.getJSONObject(0)
                    val trackObj = firstEntry.optJSONObject("track")
                    if (trackObj != null) {
                        val track = TrackJson.decode(trackObj)
                        Log.i(TAG, "Recovered last played song from history: '${track.title}' by ${track.artist}")
                        return SavedPlaybackSession(
                            track = track,
                            queue = listOf(track),
                            queueIndex = 0,
                            artworkUrl = track.thumbnail.takeIf { it.isNotBlank() },
                            palette = null
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read history.json fallback: ${e.message}")
        }

        return null
    }

    fun save(
        track: Track,
        queue: List<Track> = listOf(track),
        queueIndex: Int = 0,
        artworkUrl: String? = track.thumbnail.takeIf { it.isNotBlank() },
        palette: ArtworkPalette? = null
    ) {
        if (track.id.isBlank()) return
        val existingPalette = palette?.takeIf { !it.isDefault } ?: cachedSession?.palette
        val existingArtwork = artworkUrl?.takeIf { it.isNotBlank() } ?: cachedSession?.artworkUrl ?: track.thumbnail.takeIf { it.isNotBlank() }
        val session = SavedPlaybackSession(track, queue, queueIndex, existingArtwork, existingPalette)
        cachedSession = session

        store?.write {
            JSONObject().apply {
                put("version", 1)
                put("track", TrackJson.encode(track))
                put("queue", JSONArray().apply { queue.take(50).forEach { put(TrackJson.encode(it)) } })
                put("queueIndex", queueIndex)
                putOpt("artworkUrl", existingArtwork)
                if (existingPalette != null && !existingPalette.isDefault) {
                    put("palette", encodePalette(existingPalette))
                }
            }
        }
    }

    fun updatePalette(palette: ArtworkPalette) {
        if (palette.isDefault) return
        val current = cachedSession ?: return
        if (current.palette != palette) {
            save(current.track, current.queue, current.queueIndex, current.artworkUrl, palette)
        }
    }

    internal fun encodePalette(palette: ArtworkPalette): JSONObject = JSONObject().apply {
        put("primary", palette.primary.toArgb())
        put("secondary", palette.secondary.toArgb())
        put("tertiary", palette.tertiary.toArgb())
        val hasSeed = palette.seedColor != Color.Unspecified
        put("hasSeed", hasSeed)
        put("seedColor", if (hasSeed) palette.seedColor.toArgb() else 0)
        put("isMonochrome", palette.isMonochrome)
        put("glowColors", JSONArray().apply {
            palette.glowColors.forEach { put(it.toArgb()) }
        })
    }

    internal fun decodePalette(json: JSONObject): ArtworkPalette {
        val primary = Color(json.optInt("primary"))
        val secondary = Color(json.optInt("secondary"))
        val tertiary = Color(json.optInt("tertiary"))
        val hasSeed = json.optBoolean("hasSeed", true)
        val seedColor = if (hasSeed) Color(json.optInt("seedColor", json.optInt("primary"))) else Color.Unspecified
        val isMonochrome = json.optBoolean("isMonochrome", false)
        val glowArray = json.optJSONArray("glowColors")
        val glowColors = if (glowArray != null) {
            List(glowArray.length()) { Color(glowArray.getInt(it)) }
        } else emptyList()
        return ArtworkPalette(
            primary = primary,
            secondary = secondary,
            tertiary = tertiary,
            seedColor = seedColor,
            isMonochrome = isMonochrome,
            glowColors = glowColors
        )
    }
}
