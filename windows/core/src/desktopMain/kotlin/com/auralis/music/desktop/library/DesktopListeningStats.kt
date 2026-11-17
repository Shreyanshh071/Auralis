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
     * the same song starting within 2 s is the same listen. A listen the other device uploaded
     * while it was still playing arrives again longer later; then the copy here grows to match
     * (never shrinks). Saved once. Returns how many listens were added or grew.
     */
    @Synchronized fun importListens(listens: List<Listen>): Int {
        val current = mutable.value.toMutableList()
        val byTrack = current.withIndex().groupBy({ it.value.track.id }, { it.index })
        var changed = 0
        val fresh = mutableListOf<Listen>()
        for (incoming in listens.distinctBy { it.id }) {
            if (incoming.timeMs <= 0 || incoming.track.id.isBlank()) continue
            val match = byTrack[incoming.track.id].orEmpty().firstOrNull { kotlin.math.abs(current[it].startedAt - incoming.startedAt) <= 2_000L }
            if (match == null) { fresh += incoming; changed++ }
            else if (incoming.timeMs > current[match].timeMs) {
                current[match] = current[match].copy(timeMs = incoming.timeMs); changed++
            }
        }
        if (changed == 0) return 0
        mutable.value = (current + fresh).sortedBy { it.startedAt }
        persist()
        return changed
    }

    @Volatile private var artistListsCache: Pair<List<Listen>, Map<String, List<com.auralis.music.domain.model.TrackArtist>>>? = null

    /**
     * Each song's per-artist list from any of its listens (the newest that has one), over the
     * whole history. Every listen keeps its own copy of the song, and listens recorded before
     * the list existed have none; with this, playing a song once fixes all its past listens.
     */
    val artistLists: Map<String, List<com.auralis.music.domain.model.TrackArtist>>
        get() {
            val all = listens.value
            artistListsCache?.let { (source, map) -> if (source === all) return map }
            return knownArtists(all).also { artistListsCache = all to it }
        }

    @Synchronized fun clear() { mutable.value = emptyList(); clearVersion++; persist() }

    /** Another device cleared the account's stats: listens before [cutoff] go here too. Returns how many. */
    @Synchronized fun removeBefore(cutoff: Long): Int {
        val kept = mutable.value.filter { it.startedAt >= cutoff }
        val removed = mutable.value.size - kept.size
        if (removed > 0) { mutable.value = kept; clearVersion++; persist() }
        return removed
    }

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
        /**
         * [listens] per credited artist, most listened first: a collab counts in full for each
         * artist on it, a label upload for its real artists (see OfficialArtistResolver.credits).
         * Label uploads not looked up yet are queued for a background lookup, newest first.
         */
        /** Each track id's newest non-empty per-artist list in [listens]. */
        fun knownArtists(listens: List<Listen>): Map<String, List<com.auralis.music.domain.model.TrackArtist>> {
            val newest = HashMap<String, Listen>()
            for (listen in listens) {
                if (listen.track.artists.isEmpty()) continue
                val current = newest[listen.track.id]
                if (current == null || listen.startedAt > current.startedAt) newest[listen.track.id] = listen
            }
            return newest.mapValues { it.value.track.artists }
        }

        /** The artists [track] credits, using another listen's per-artist list when this copy has none. */
        fun credits(track: Track, known: Map<String, List<com.auralis.music.domain.model.TrackArtist>>): List<String> {
            val filled = if (track.artists.isEmpty()) known[track.id]?.let { track.copy(artists = it) } ?: track else track
            return com.auralis.music.data.network.OfficialArtistResolver.credits(filled)
        }

        fun artistGroups(
            listens: List<Listen>,
            known: Map<String, List<com.auralis.music.domain.model.TrackArtist>> = knownArtists(listens)
        ): List<Pair<String, List<Listen>>> {
            var queued = 0
            for (listen in listens.asReversed()) {
                if (queued >= 25) break
                if (com.auralis.music.data.network.OfficialArtistResolver.needsLookup(listen.track)) {
                    com.auralis.music.data.network.OfficialArtistResolver.request(listen.track); queued++
                }
            }
            return listens.flatMap { listen ->
                credits(listen.track, known).mapNotNull { name ->
                    com.auralis.music.domain.stats.ArtistCredits.key(name)?.let { Triple(it, name, listen) }
                }
            }.groupBy { it.first }.values.map { credits ->
                // The most common spelling names the artist.
                credits.groupingBy { it.second }.eachCount().maxBy { it.value }.key to credits.map { it.third }
            }.sortedByDescending { (_, list) -> list.sumOf { it.timeMs } }
        }

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
