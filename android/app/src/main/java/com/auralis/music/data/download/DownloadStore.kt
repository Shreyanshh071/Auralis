package com.auralis.music.data.download

import com.auralis.music.domain.model.Track
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** Logical IDs own their files; resolver aliases never change offline identity. */
internal class DownloadStore(private val filesDir: File) {
    private val metadata = File(filesDir, "downloaded_tracks_v1.json")
    private val backup = File(filesDir, "downloaded_tracks_v1.json.bak")
    private val pending = File(filesDir, "downloaded_tracks_v1.json.new")
    private var tracks: List<Track> = emptyList()

    fun audioFile(id: String, extension: String = "m4a"): File {
        require(id.isNotBlank() && '/' !in id && '\\' !in id) { "Invalid download file ID" }
        return File(File(filesDir, "audio_downloads"), "$id.$extension")
    }

    fun artworkFile(id: String): File {
        require(id.isNotBlank() && '/' !in id && '\\' !in id) { "Invalid download file ID" }
        val dir = File(filesDir, "artwork_downloads").apply { if (!exists()) mkdirs() }
        return File(dir, "$id.jpg")
    }

    @Synchronized
    fun load(): List<Track> {
        // A backup means the previous replacement did not finish. Keep the last committed data.
        if (backup.exists()) {
            if (metadata.exists() && !metadata.delete()) throw IOException("Cannot recover download metadata")
            if (!backup.renameTo(metadata)) throw IOException("Cannot restore download metadata backup")
        }
        val array = if (metadata.exists()) JSONArray(metadata.readText()) else JSONArray()
        tracks = (0 until array.length()).map { index ->
            val obj = array.getJSONObject(index)
            val trkId = obj.getString("id")
            var thumb = obj.optString("thumbnail", "")
            if (thumb.isBlank()) {
                val art = artworkFile(trkId)
                if (art.exists() && art.length() > 500) {
                    thumb = android.net.Uri.fromFile(art).toString()
                }
            }
            Track(
                id = trkId, title = obj.optString("title", "Unknown Title"),
                artist = obj.optString("artist", "Unknown Artist"), duration = obj.optLong("duration", 0L),
                thumbnail = thumb, album = obj.optString("album").takeIf { it.isNotBlank() }
            )
        }.filter { validAudio(it.id) }.distinctBy { it.id }
        return tracks.toList()
    }

    @Synchronized
    fun downloadedFile(id: String): File? =
        if (tracks.any { it.id == id } && validAudio(id)) audioFile(id) else null

    @Synchronized
    fun add(track: Track): List<Track> {
        check(validAudio(track.id)) { "Downloaded audio file is missing or incomplete" }
        return replace(tracks.filterNot { it.id == track.id } + track)
    }

    @Synchronized
    fun remove(id: String): List<Track> = replace(tracks.filterNot { it.id == id })

    @Synchronized
    fun clear(): List<Track> = replace(emptyList())

    private fun validAudio(id: String): Boolean = runCatching {
        audioFile(id).let { it.isFile && it.length() > 1024 }
    }.getOrDefault(false)

    private fun replace(next: List<Track>): List<Track> {
        val array = JSONArray()
        next.forEach { track ->
            array.put(JSONObject().apply {
                put("id", track.id); put("title", track.title); put("artist", track.artist)
                put("duration", track.duration); put("thumbnail", track.thumbnail); put("album", track.album ?: "")
            })
        }
        // Same-directory rename, with synced temporary contents and a recoverable old version.
        FileOutputStream(pending).use { output ->
            output.write(array.toString().toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        if (metadata.exists() && !metadata.renameTo(backup)) throw IOException("Cannot back up download metadata")
        try {
            if (!pending.renameTo(metadata)) throw IOException("Cannot replace download metadata")
            if (backup.exists() && !backup.delete()) throw IOException("Cannot finish download metadata replacement")
        } catch (e: Exception) {
            if (backup.exists()) {
                metadata.delete()
                backup.renameTo(metadata)
            }
            throw e
        }
        tracks = next
        return tracks.toList()
    }
}
