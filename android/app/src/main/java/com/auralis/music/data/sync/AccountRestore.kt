package com.auralis.music.data.sync

import com.auralis.music.data.local.entity.PlaybackEventEntity
import com.auralis.music.domain.model.SavedAlbum
import com.auralis.music.domain.model.SavedArtist
import com.auralis.music.domain.model.Track
import com.auralis.music.domain.repository.LibraryRepository
import kotlinx.coroutines.flow.first

/**
 * Runs [block] as one database transaction, so screens observing the library or stats see the
 * state before it and the state after it, never the rows in between.
 */
typealias InTransaction = suspend (block: suspend () -> Unit) -> Unit

/** A playlist in the account backup (`users/{uid}.playlists`). */
data class CloudPlaylist(
    val id: String?,
    val title: String,
    val description: String?,
    val coverUrl: String?,
    val tracks: List<Track>,
    val createdAt: Long = 0L,
    val isCustom: Boolean = true
)

/** The account backup document `users/{uid}`: playlists with their songs, likes, albums, artists. */
data class CloudLibrary(
    val playlists: List<CloudPlaylist> = emptyList(),
    val favorites: List<Track> = emptyList(),
    val albums: List<SavedAlbum> = emptyList(),
    val artists: List<SavedArtist> = emptyList(),
    val pins: List<String> = emptyList(),
    val pinsAt: Long = 0L,
    val deleted: Map<String, Long> = emptyMap()
) {
    fun toSyncedLibrary() = SyncedLibrary(
        playlists = playlists.filter { it.id != null }.map {
            com.auralis.music.domain.model.Playlist(it.id!!, it.title, it.description, it.coverUrl, it.tracks,
                createdAt = it.createdAt, isCustom = it.isCustom)
        },
        favorites = favorites, albums = albums, artists = artists, pins = pins, pinsAt = pinsAt, deleted = deleted
    )

    companion object {
        private fun track(raw: Any?): Track? {
            val m = raw as? Map<*, *> ?: return null
            val id = (m["id"] as? String)?.takeIf { it.isNotBlank() } ?: return null
            return Track(
                id = id,
                title = (m["title"] as? String) ?: "Unknown Track",
                artist = (m["artist"] as? String) ?: "Unknown Artist",
                album = m["album"] as? String,
                thumbnail = (m["thumbnail"] as? String) ?: "",
                duration = (m["duration"] as? Number)?.toLong() ?: 0L
            )
        }

        fun parse(doc: Map<String, Any?>): CloudLibrary = CloudLibrary(
            playlists = (doc["playlists"] as? List<*>).orEmpty().mapNotNull { raw ->
                val p = raw as? Map<*, *> ?: return@mapNotNull null
                CloudPlaylist(
                    id = (p["id"] as? String)?.takeIf { it.isNotBlank() },
                    title = (p["title"] as? String)?.takeIf { it.isNotBlank() } ?: "Restored Playlist",
                    description = p["description"] as? String,
                    coverUrl = p["coverUrl"] as? String,
                    tracks = (p["tracks"] as? List<*>).orEmpty().mapNotNull(::track),
                    createdAt = (p["createdAt"] as? Number)?.toLong() ?: 0L,
                    isCustom = p["isCustom"] as? Boolean ?: true
                )
            },
            favorites = (doc["favorites"] as? List<*>).orEmpty().mapNotNull(::track),
            albums = (doc["savedAlbums"] as? List<*>).orEmpty().mapNotNull { raw ->
                val a = raw as? Map<*, *> ?: return@mapNotNull null
                val id = (a["id"] as? String)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val title = (a["title"] as? String)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                SavedAlbum(
                    id = id,
                    title = title,
                    artist = a["artist"] as? String,
                    thumbnail = a["thumbnail"] as? String,
                    trackCount = (a["trackCount"] as? Number)?.toInt(),
                    savedAt = (a["savedAt"] as? Number)?.toLong() ?: System.currentTimeMillis()
                )
            },
            artists = (doc["savedArtists"] as? List<*>).orEmpty().mapNotNull { raw ->
                val a = raw as? Map<*, *> ?: return@mapNotNull null
                val id = (a["id"] as? String)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val name = (a["name"] as? String)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                SavedArtist(id, name, a["thumbnail"] as? String, a["subscribers"] as? String)
            },
            pins = ((doc["librarySync"] as? Map<*, *>)?.get("pins") as? List<*>).orEmpty().filterIsInstance<String>(),
            pinsAt = ((doc["librarySync"] as? Map<*, *>)?.get("pinsAt") as? Number)?.toLong() ?: 0L,
            deleted = ((doc["librarySync"] as? Map<*, *>)?.get("deleted") as? List<*>).orEmpty().mapNotNull { raw ->
                val d = raw as? Map<*, *> ?: return@mapNotNull null
                val id = d["id"] as? String ?: return@mapNotNull null
                val at = (d["at"] as? Number)?.toLong() ?: return@mapNotNull null
                id to at
            }.toMap()
        )
    }
}

/** What a restore wrote, and which of it wasn't on this phone before (the account brought it). */
data class RestoredLibrary(
    val playlists: Int,
    val liked: Int,
    val addedPlaylistIds: Set<String>,
    val addedFavoriteIds: Set<String>
)

object AccountLibraryRestore {
    /**
     * Writes the whole account library in one transaction: every playlist together with all its
     * songs, and every like. Restoring one row at a time showed playlists with 0 songs that filled
     * in later, and likes arriving one by one.
     */
    suspend fun apply(library: LibraryRepository, cloud: CloudLibrary, inTransaction: InTransaction,
        serviceLinkedPlaylistIds: Set<String> = emptySet()): RestoredLibrary {
        // What this phone had before: only what the restore adds is the account's (removed on sign-out).
        val localPlaylistIds = library.getPlaylists().first().map { it.id }.toSet()
        val localFavoriteIds = library.getFavoriteTracks().first().map { it.id }.toSet()
        val addedPlaylists = mutableSetOf<String>()
        val addedFavorites = mutableSetOf<String>()
        var liked = 0
        inTransaction {
            for (p in cloud.playlists) {
                if (p.id in localPlaylistIds && p.id in serviceLinkedPlaylistIds) continue
                val target = if (p.id != null) library.restorePlaylist(p.id, p.title, p.description, p.coverUrl)
                else library.createPlaylist(p.title, p.description, p.coverUrl)
                if (p.tracks.isNotEmpty()) library.replacePlaylistTracks(target.id, p.tracks)
                if (target.id !in localPlaylistIds) addedPlaylists += target.id
            }
            for (t in cloud.favorites) {
                library.setFavorite(t, true)
                liked++
                if (t.id !in localFavoriteIds) addedFavorites += t.id
            }
            cloud.albums.forEach { library.saveAlbum(it) }
            cloud.artists.forEach { library.saveArtist(it) }
        }
        return RestoredLibrary(cloud.playlists.size, liked, addedPlaylists, addedFavorites)
    }

    /**
     * After signing out: takes off this phone the playlists and likes the account brought here
     * ([playlistIds], [favoriteIds]), in one transaction. Everything else stays: what was here
     * before signing in, and what was made or imported here (by link, Spotify, or picking YouTube
     * Music playlists). Returns how many playlists were removed.
     */
    suspend fun removeAccountItems(
        library: LibraryRepository,
        playlistIds: Set<String>,
        favoriteIds: Set<String>,
        inTransaction: InTransaction
    ): Int {
        if (playlistIds.isEmpty() && favoriteIds.isEmpty()) return 0
        val present = library.getPlaylists().first().map { it.id }.filter { it in playlistIds }
        val liked = library.getFavoriteTracks().first().filter { it.id in favoriteIds }
        inTransaction {
            present.forEach { library.deletePlaylist(it) }
            liked.forEach { library.setFavorite(it, false) }
        }
        return present.size
    }
}

/** One listen from the account's `listening/{day}` documents (StatsCloudSync's layout). */
data class CloudListen(val track: Track, val timestamp: Long, val playTimeMs: Long)

object CloudListens {
    /** Two listens of one song starting this close together are the same listen. */
    const val SAME_LISTEN_WINDOW_MS = 2_000L

    /** The `events` maps of `listening/{day}` documents as listens; malformed entries are skipped. */
    fun parse(dayEvents: List<Map<*, *>>): List<CloudListen> = dayEvents.flatMap { events ->
        events.values.mapNotNull { raw ->
            val e = raw as? Map<*, *> ?: return@mapNotNull null
            val trackId = (e["t"] as? String)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val ts = (e["ts"] as? Number)?.toLong() ?: return@mapNotNull null
            val ms = (e["ms"] as? Number)?.toLong() ?: return@mapNotNull null
            if (ms <= 0L) return@mapNotNull null
            CloudListen(
                Track(
                    id = trackId,
                    title = e["ti"] as? String ?: "Unknown Track",
                    artist = e["ar"] as? String ?: "Unknown Artist",
                    album = e["al"] as? String,
                    thumbnail = e["th"] as? String ?: "",
                    duration = (e["du"] as? Number)?.toLong() ?: 0L
                ),
                ts, ms
            )
        }
    }

    /**
     * Which listens to add and which ones already here grew longer (a listen another device synced
     * while it was still playing). Only measured times are used; nothing is estimated.
     */
    data class Plan(val inserts: List<CloudListen>, val grows: List<CloudListen>) {
        val changed: Int get() = inserts.size + grows.size
    }

    fun plan(incoming: List<CloudListen>, existing: List<PlaybackEventEntity>): Plan {
        class Slot(val timestamp: Long, var playTimeMs: Long, val insertIndex: Int)
        val byTrack = HashMap<String, MutableList<Slot>>()
        existing.forEach { byTrack.getOrPut(it.trackId) { mutableListOf() } += Slot(it.timestamp, it.playTimeMs, -1) }
        val inserts = mutableListOf<CloudListen>()
        val grows = LinkedHashMap<Pair<String, Long>, CloudListen>()
        for (listen in incoming) {
            val slots = byTrack.getOrPut(listen.track.id) { mutableListOf() }
            val slot = slots.firstOrNull { kotlin.math.abs(it.timestamp - listen.timestamp) <= SAME_LISTEN_WINDOW_MS }
            when {
                slot == null -> {
                    inserts += listen
                    slots += Slot(listen.timestamp, listen.playTimeMs, inserts.lastIndex)
                }
                listen.playTimeMs > slot.playTimeMs -> {
                    slot.playTimeMs = listen.playTimeMs
                    if (slot.insertIndex >= 0) inserts[slot.insertIndex] = inserts[slot.insertIndex].copy(playTimeMs = listen.playTimeMs)
                    else grows[listen.track.id to slot.timestamp] = listen.copy(timestamp = slot.timestamp)
                }
            }
        }
        return Plan(inserts, grows.values.toList())
    }
}
