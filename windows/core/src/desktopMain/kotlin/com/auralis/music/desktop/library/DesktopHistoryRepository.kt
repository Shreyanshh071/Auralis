package com.auralis.music.desktop.library

import com.auralis.music.domain.model.HistoryEntry
import com.auralis.music.domain.model.PlayCountEntry
import com.auralis.music.domain.model.Track
import com.auralis.music.domain.recommendations.TrackDeduplicator
import com.auralis.music.domain.repository.HistoryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Desktop listening history (%APPDATA%\Auralis\history.json), with the phone's rules: one entry
 * per song (playing it again moves it to the top), other uploads of the same song replaced, the
 * newest 100 kept, and a play count per song.
 */
class DesktopHistoryRepository(file: File, private val library: DesktopLibraryRepository) : HistoryRepository {
    private val store = JsonFileStore(file)
    private val history = MutableStateFlow<List<HistoryEntry>>(emptyList())
    private val counts = MutableStateFlow<Map<String, PlayCountEntry>>(emptyMap())
    private val importedTimes = mutableMapOf<String, Long>()
    private var historyAccount: String? = null
    private var importedRows: Map<String, Long> = emptyMap()

    init {
        store.read()?.let { json ->
            historyAccount = json.optString("historyAccount").takeIf { it.isNotBlank() && it != "null" }
            json.optJSONObject("importedRows")?.let { rows ->
                importedRows = rows.keys().asSequence().associateWith { rows.optLong(it) }
            }
            json.optJSONObject("importedHistoryTimes")?.let { times ->
                times.keys().forEach { importedTimes[it] = times.optLong(it) }
            }
            history.value = json.optJSONArray("history")?.let { a ->
                List(a.length()) { i -> a.getJSONObject(i).let { HistoryEntry(TrackJson.decode(it.getJSONObject("track")), it.optLong("playedAt")) } }
            }.orEmpty()
            counts.value = json.optJSONArray("counts")?.let { a ->
                List(a.length()) { i ->
                    val o = a.getJSONObject(i)
                    PlayCountEntry(o.getString("trackId"), o.optInt("count"), o.optLong("lastPlayed"), TrackJson.decode(o.getJSONObject("track")))
                }.associateBy { it.trackId }
            }.orEmpty()
        }
    }

    private fun save() = store.write {
        JSONObject().apply {
            put("version", 1)
            putOpt("historyAccount", historyAccount)
            put("importedRows", JSONObject(importedRows))
            put("importedHistoryTimes", JSONObject(synchronized(importedTimes) { importedTimes.toMap() }))
            put("history", JSONArray().apply { history.value.forEach { put(JSONObject().put("track", TrackJson.encode(it.track)).put("playedAt", it.playedAt)) } })
            put("counts", JSONArray().apply {
                counts.value.values.forEach { put(JSONObject().put("trackId", it.trackId).put("count", it.count).put("lastPlayed", it.lastPlayed).put("track", TrackJson.encode(it.track))) }
            })
        }
    }

    override fun getHistory(): Flow<List<HistoryEntry>> = history.map { list -> list.sortedByDescending { it.playedAt } }

    override suspend fun setHistoryAccount(accountId: String?) {
        if (historyAccount == accountId) return
        val oldRows = importedRows
        history.update { entries -> entries.filterNot { oldRows[it.track.id] == it.playedAt } }
        synchronized(importedTimes) { importedTimes.clear() }
        importedRows = emptyMap()
        historyAccount = accountId
        save()
    }

    override suspend fun importHistory(entries: List<HistoryEntry>, accountId: String?) {
        setHistoryAccount(accountId)
        // Re-downloading the same listen must not undo a local history removal or clear.
        val fresh = synchronized(importedTimes) {
            entries.filter { it.playedAt > (importedTimes[it.track.id] ?: 0L) }.also { items ->
                items.forEach { importedTimes[it.track.id] = maxOf(importedTimes[it.track.id] ?: 0L, it.playedAt) }
            }
        }
        if (fresh.isEmpty()) return
        history.update { existing ->
            com.auralis.music.domain.model.mergeSyncedHistory(existing, fresh)
        }
        val newTimes = fresh.groupBy { it.track.id }.mapValues { (_, entries) -> entries.maxOf { it.playedAt } }
        importedRows = importedRows + history.value.filter { newTimes[it.track.id] == it.playedAt }
            .associate { it.track.id to it.playedAt }
        save()
    }

    override suspend fun addToHistory(track: Track) {
        if (track.id.isBlank()) return
        val fp = TrackDeduplicator.getSongFingerprint(track)
        history.update { list ->
            val kept = list.filterNot { entry ->
                entry.track.id == track.id ||
                    TrackDeduplicator.isDuplicateSong(TrackDeduplicator.getSongFingerprint(entry.track), fp, matchAlternateVersions = true)
            }
            (kept + HistoryEntry(track, System.currentTimeMillis())).sortedByDescending { it.playedAt }.take(HISTORY_CAP)
        }
        save()
    }

    override suspend fun removeFromHistory(trackId: String) {
        val target = history.value.firstOrNull { it.track.id == trackId }?.track
        val fp = target?.let { TrackDeduplicator.getSongFingerprint(it) }
        history.update { list ->
            list.filterNot { entry ->
                entry.track.id == trackId ||
                    (fp != null && TrackDeduplicator.isDuplicateSong(TrackDeduplicator.getSongFingerprint(entry.track), fp, matchAlternateVersions = true))
            }
        }
        save()
    }

    override suspend fun clearHistory() {
        history.value = emptyList()
        counts.value = emptyMap()
        save()
    }

    override fun getTopPlayedTracks(): Flow<List<PlayCountEntry>> = counts.map { map -> map.values.sortedByDescending { it.count }.take(TOP_CAP) }

    override suspend fun recordPlay(track: Track) {
        if (track.id.isBlank()) return
        counts.update { map ->
            val count = (map[track.id]?.count ?: 0) + 1
            map + (track.id to PlayCountEntry(track.id, count, System.currentTimeMillis(), track))
        }
        save()
    }

    override suspend fun getPlayCounts(): List<PlayCountEntry> = counts.value.values.toList()

    /** The phone's query: up to 20 history songs last played before [cutoffTimestamp], oldest first. */
    override suspend fun getForgottenFavorites(cutoffTimestamp: Long): List<Track> =
        history.value.filter { it.playedAt < cutoffTimestamp }.sortedBy { it.playedAt }.take(20).map { it.track }

    /** The phone's query: up to 30 history songs played since [fromTimestamp], newest first. */
    override suspend fun getRecentHeavyRotation(fromTimestamp: Long): List<Track> =
        history.value.filter { it.playedAt >= fromTimestamp }.sortedByDescending { it.playedAt }.take(30).map { it.track }

    override suspend fun getLikedSeeds(limit: Int): List<Track> = library.likedSeeds(limit)

    private companion object {
        const val HISTORY_CAP = 100
        const val TOP_CAP = 100
    }
}
