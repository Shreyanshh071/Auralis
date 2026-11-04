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
    private var individualIds: Set<String> = emptySet()
    private var lyricsChoices: Map<String, Boolean> = emptyMap()
    private var coverChoices: Map<String, Boolean> = emptyMap()

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
        coverChoices = (0 until array.length()).mapNotNull { index ->
            val obj = array.getJSONObject(index)
            if (obj.has("coverRequested")) obj.getString("id") to obj.getBoolean("coverRequested") else null
        }.toMap()
        // Missing on older downloads means unknown, never infer a choice from today's defaults.
        lyricsChoices = (0 until array.length()).mapNotNull { index ->
            val obj = array.getJSONObject(index)
            if (obj.has("lyricsRequested")) obj.getString("id") to obj.getBoolean("lyricsRequested") else null
        }.toMap()
        individualIds = (0 until array.length()).mapNotNull { index ->
            array.getJSONObject(index).takeIf { it.optBoolean("individual", false) }?.getString("id")
        }.toSet()
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
        val validIds = tracks.mapTo(mutableSetOf()) { it.id }
        individualIds = individualIds.intersect(validIds)
        lyricsChoices = lyricsChoices.filterKeys { it in validIds }
        coverChoices = coverChoices.filterKeys { it in validIds }
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

    @Synchronized
    fun individualTrackIds(): Set<String> = individualIds.toSet()

    @Synchronized
    fun downloadLyricsChoices(): Map<String, Boolean> = lyricsChoices.toMap()

    @Synchronized
    fun downloadCoverChoices(): Map<String, Boolean> = coverChoices.toMap()

    @Synchronized
    fun recordCoverChoice(id: String, requested: Boolean): List<Track> {
        if (tracks.none { it.id == id } || coverChoices[id] == requested) return tracks.toList()
        return replace(tracks, nextCoverChoices = coverChoices + (id to requested))
    }

    @Synchronized
    fun recordLyricsChoice(id: String, requested: Boolean): List<Track> {
        if (tracks.none { it.id == id } || lyricsChoices[id] == requested) return tracks.toList()
        return replace(tracks, nextLyricsChoices = lyricsChoices + (id to requested))
    }

    @Synchronized
    fun markIndividual(id: String): List<Track> {
        if (tracks.none { it.id == id } || id in individualIds) return tracks.toList()
        return replace(tracks, individualIds + id)
    }

    private fun validAudio(id: String): Boolean = runCatching {
        audioFile(id).let { it.isFile && it.length() > 1024 }
    }.getOrDefault(false)

    private fun replace(next: List<Track>, nextIndividualIds: Set<String> = individualIds,
        nextLyricsChoices: Map<String, Boolean> = lyricsChoices,
        nextCoverChoices: Map<String, Boolean> = coverChoices): List<Track> {
        val array = JSONArray()
        next.forEach { track ->
            array.put(JSONObject().apply {
                put("id", track.id); put("title", track.title); put("artist", track.artist)
                put("duration", track.duration); put("thumbnail", track.thumbnail); put("album", track.album ?: "")
                put("individual", track.id in nextIndividualIds)
                nextLyricsChoices[track.id]?.let { put("lyricsRequested", it) }
                nextCoverChoices[track.id]?.let { put("coverRequested", it) }
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
        val validIds = next.mapTo(mutableSetOf()) { it.id }
        individualIds = nextIndividualIds.intersect(validIds)
        lyricsChoices = nextLyricsChoices.filterKeys { it in validIds }
        coverChoices = nextCoverChoices.filterKeys { it in validIds }
        return tracks.toList()
    }
}
