package com.auralis.music.desktop.library

import com.auralis.music.domain.model.Track
import com.auralis.music.domain.model.TrackSource
import com.auralis.music.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * A JSON file written in the background: changes are batched for half a second, then written to a
 * temporary file and renamed over the old one, so a crash never leaves a half-written file.
 */
class JsonFileStore(private val file: File) {
    private val writer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "store-${file.name}").apply { isDaemon = true } }
    @Volatile private var pending: ScheduledFuture<*>? = null

    fun read(): JSONObject? = try {
        if (file.isFile) JSONObject(file.readText()) else null
    } catch (e: Exception) {
        Log.w("JsonFileStore", "Unreadable ${file.name}: ${e.message}")
        // Keep the unreadable file for recovery instead of overwriting it with an empty store.
        runCatching { file.copyTo(File(file.parentFile, file.name + ".corrupt"), overwrite = true) }
        null
    }

    fun write(snapshot: () -> JSONObject) {
        pending?.cancel(false)
        pending = writer.schedule({ save(snapshot()) }, 500, TimeUnit.MILLISECONDS)
    }

    private fun save(json: JSONObject) {
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json.toString())
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        } catch (e: Exception) {
            Log.w("JsonFileStore", "Couldn't save ${file.name}: ${e.message}")
        }
    }
}

/** Track <-> JSON, every field. */
object TrackJson {
    fun encode(t: Track): JSONObject = JSONObject().apply {
        put("id", t.id)
        put("title", t.title)
        put("artist", t.artist)
        putOpt("album", t.album)
        putOpt("albumId", t.albumId)
        put("duration", t.duration)
        put("thumbnail", t.thumbnail)
        put("source", t.source.name)
        putOpt("channelTitle", t.channelTitle)
        putOpt("views", t.views)
        putOpt("dominantColor", t.dominantColor)
    }

    fun decode(o: JSONObject): Track = Track(
        id = o.optString("id"),
        title = o.optString("title"),
        artist = o.optString("artist"),
        album = o.optStringOrNull("album"),
        albumId = o.optStringOrNull("albumId"),
        duration = o.optLong("duration"),
        thumbnail = o.optString("thumbnail"),
        source = runCatching { TrackSource.valueOf(o.optString("source")) }.getOrDefault(TrackSource.YOUTUBE),
        channelTitle = o.optStringOrNull("channelTitle"),
        views = o.optStringOrNull("views"),
        dominantColor = if (o.has("dominantColor") && !o.isNull("dominantColor")) o.getInt("dominantColor") else null
    )

    fun encodeList(tracks: List<Track>) = JSONArray().apply { tracks.forEach { put(encode(it)) } }
    fun decodeList(a: JSONArray?): List<Track> = if (a == null) emptyList() else List(a.length()) { decode(a.getJSONObject(it)) }
}

internal fun JSONObject.optStringOrNull(key: String): String? = if (has(key) && !isNull(key)) getString(key) else null
