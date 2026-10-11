package com.auralis.music.desktop.library

import com.auralis.music.domain.model.Track
import com.auralis.music.domain.recommendations.TrackDeduplicator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Measured listening sessions, independent of Android's database and the old play counts. */
class DesktopListeningStats(private val file: File) {
    data class Listen(val id: String, val track: Track, val startedAt: Long, val timeMs: Long) {
        val countsAsPlay: Boolean get() = timeMs >= if (track.duration in 1..59) track.duration * 500 else 30_000
    }
    private val mutable = MutableStateFlow<List<Listen>>(emptyList())
    val listens = mutable.asStateFlow()
    @Volatile var clearVersion: Long = 0; private set

    init {
        JsonFileStore(file).read()?.optJSONArray("listens")?.let { array ->
            mutable.value = List(array.length()) { i ->
                val o = array.getJSONObject(i)
                Listen(o.getString("id"), TrackJson.decode(o.getJSONObject("track")), o.getLong("startedAt"), o.getLong("timeMs"))
            }
        }
    }

    @Synchronized fun record(id: String, track: Track, startedAt: Long, timeMs: Long) {
        if (timeMs <= 0 || track.id.isBlank()) return
        // Updating a session doesn't count a new play every time the recorder flushes.
        mutable.value = mutable.value.filterNot { it.id == id } + Listen(id, track, startedAt, timeMs)
        persist()
    }

    /**
     * Adds listens measured elsewhere (the phone's account backup), skipping any already here:
     * the same song starting within 2 s is the same listen. Saved once. Returns how many were new.
     */
    @Synchronized fun importListens(listens: List<Listen>): Int {
        val existing = mutable.value
        val byTrack = existing.groupBy { it.track.id }
        val fresh = listens.filter { incoming ->
            incoming.timeMs > 0 && incoming.track.id.isNotBlank() &&
                byTrack[incoming.track.id].orEmpty().none { kotlin.math.abs(it.startedAt - incoming.startedAt) <= 2_000L }
        }.distinctBy { it.id }
        if (fresh.isEmpty()) return 0
        mutable.value = (existing + fresh).sortedBy { it.startedAt }
        persist()
        return fresh.size
    }

    @Synchronized fun clear() { mutable.value = emptyList(); clearVersion++; persist() }

    private fun persist() {
        file.parentFile?.mkdirs()
        val json = JSONObject().put("version", 1).put("listens", JSONArray().apply {
            mutable.value.forEach { e -> put(JSONObject().put("id", e.id).put("track", TrackJson.encode(e.track))
                .put("startedAt", e.startedAt).put("timeMs", e.timeMs)) }
        })
        val temporary = File(file.parentFile, file.name + ".tmp")
        temporary.writeText(json.toString())
        try { Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
        catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    companion object {
        fun songKey(track: Track): String {
            val fp = TrackDeduplicator.getSongFingerprint(track)
            return "${fp.normalizedArtist}|${fp.normalizedCoreTitle.ifBlank { fp.normalizedTitle }.ifBlank { track.id }}"
        }
    }
}

/** Monotonic elapsed time only: seeks, pauses, buffering and a sleeping PC add no time. */
class ListeningAccumulator {
    var track: Track? = null; private set
    var sessionId: String = ""; private set
    var startedAt: Long = 0; private set
    var timeMs: Long = 0; private set
    private var lastNs = 0L
    private var wasPlaying = false

    fun reset() { track = null; sessionId = ""; timeMs = 0; lastNs = 0; wasPlaying = false }

    fun sample(next: Track?, playing: Boolean, nowNs: Long, wallMs: Long): Boolean {
        val elapsed = if (lastNs == 0L) 0 else (nowNs - lastNs) / 1_000_000
        if (wasPlaying && next?.id == track?.id && elapsed in 1..2000) timeMs += elapsed
        val changed = next?.id != track?.id
        lastNs = nowNs
        wasPlaying = playing
        if (changed) {
            track = next; timeMs = 0; startedAt = wallMs
            sessionId = java.util.UUID.randomUUID().toString()
        }
        return changed
    }
}
