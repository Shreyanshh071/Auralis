package com.auralis.music.data.sync

import com.auralis.music.domain.model.Playlist
import com.auralis.music.domain.model.SavedAlbum
import com.auralis.music.domain.model.SavedArtist
import com.auralis.music.domain.model.Track
import org.json.JSONArray
import org.json.JSONObject

/** One side of a sync: a device's library, or the account's copy of it. */
data class SyncedLibrary(
    val playlists: List<Playlist> = emptyList(),
    val favorites: List<Track> = emptyList(),
    val albums: List<SavedAlbum> = emptyList(),
    val artists: List<SavedArtist> = emptyList(),
    val pins: List<String> = emptyList(),
    val pinsAt: Long = 0L,
    /** Playlist id to when it was deleted. Only this marker removes a playlist from other devices. */
    val deleted: Map<String, Long> = emptyMap()
)

/** What this device and the account last agreed on: how a change is told from a missing item. */
data class SyncBaseline(
    val playlists: Map<String, String> = emptyMap(),
    val favorites: Set<String> = emptySet(),
    val albums: Set<String> = emptySet(),
    val artists: Set<String> = emptySet()
) {
    fun encode(): String = JSONObject()
        .put("playlists", JSONObject(playlists))
        .put("favorites", JSONArray(favorites))
        .put("albums", JSONArray(albums))
        .put("artists", JSONArray(artists))
        .toString()

    companion object {
        private fun strings(array: JSONArray?) =
            (0 until (array?.length() ?: 0)).mapNotNullTo(HashSet()) { array?.optString(it)?.takeIf(String::isNotBlank) }

        fun decode(raw: String?): SyncBaseline? = runCatching {
            val json = JSONObject(raw?.takeIf { it.isNotBlank() } ?: return null)
            val playlists = json.getJSONObject("playlists")
            SyncBaseline(
                playlists.keys().asSequence().associateWith { playlists.getString(it) },
                strings(json.optJSONArray("favorites")), strings(json.optJSONArray("albums")), strings(json.optJSONArray("artists"))
            )
        }.getOrNull()
    }
}

/** The result of one sync: what to send to the account and what to change on this device. */
data class SyncPlan(
    val account: SyncedLibrary,
    val accountChanged: Boolean,
    val writePlaylists: List<Playlist>,
    val addedPlaylistIds: Set<String>,
    val deletePlaylists: Set<String>,
    val addFavorites: List<Track>,
    val removeFavorites: Set<String>,
    val addAlbums: List<SavedAlbum>,
    val removeAlbums: Set<String>,
    val addArtists: List<SavedArtist>,
    val removeArtists: Set<String>,
    val pins: List<String>,
    val pinsAt: Long,
    val baseline: SyncBaseline
)

/**
 * Three-way merge of a device's library with the account's. Every device runs this instead of
 * overwriting the account, so a device that hasn't caught up can't erase what another added.
 *
 * Without a baseline (first sync, or the local library was wiped) nothing counts as deleted and
 * both sides simply combine: a mistaken merge leaves an extra playlist, a mistaken delete loses one.
 */
object AccountLibraryMerge {
    private const val DELETION_MEMORY_MS = 180L * 24 * 60 * 60 * 1000
    private const val MASS_LOSS_MINIMUM = 3

    fun fingerprint(playlist: Playlist): String =
        (playlist.title + "\n" + playlist.tracks.joinToString("\n") { it.id }).hashCode().toString()

    /** Most of a list vanishing at once is lost data, not the user's choice. */
    private fun looksLost(known: Int, missing: Int) = missing >= MASS_LOSS_MINIMUM && missing * 2 >= known

    private class Items<T>(val merged: List<T>, val add: List<T>, val remove: Set<String>)

    private fun <T> mergeItems(local: List<T>, cloud: List<T>, baseline: Set<String>?, id: (T) -> String): Items<T> {
        val localIds = local.mapTo(HashSet(), id)
        val cloudIds = cloud.mapTo(HashSet(), id)
        val trusted = baseline?.takeUnless {
            looksLost(it.size, it.count { known -> known !in localIds }) ||
                looksLost(it.size, it.count { known -> known !in cloudIds })
        }.orEmpty()
        val remove = local.map(id).filterTo(HashSet()) { it !in cloudIds && it in trusted }
        val add = cloud.filter { id(it) !in localIds && id(it) !in trusted }
        return Items(local.filter { id(it) !in remove } + add, add, remove)
    }

    private fun bothChanged(local: Playlist, cloud: Playlist): Playlist {
        val here = local.tracks.mapTo(HashSet()) { it.id }
        return local.copy(tracks = local.tracks + cloud.tracks.filter { it.id !in here })
    }

    private fun oldest(a: Long, b: Long) = if (a > 0 && (b <= 0 || a < b)) a else b

    /**
     * @param protected playlists another sync owns (YouTube two-way sync): songs are only ever added to them here, and that sync takes them on to the service.
     * @param duplicates a cloud playlist this device already holds under another id: never added here.
     */
    fun merge(
        local: SyncedLibrary,
        cloud: SyncedLibrary,
        baseline: SyncBaseline?,
        now: Long,
        protected: Set<String> = emptySet(),
        duplicates: (Playlist) -> Boolean = { false }
    ): SyncPlan {
        val localById = local.playlists.associateBy { it.id }
        val cloudById = cloud.playlists.associateBy { it.id }
        val known = baseline?.playlists?.takeUnless { base ->
            local.playlists.isEmpty() ||
                looksLost(base.size, base.keys.count { it !in localById && it !in cloud.deleted })
        }
        val deleted = cloud.deleted.filterValues { now - it < DELETION_MEMORY_MS }.toMutableMap()
        val upload = mutableListOf<Playlist>()
        val writes = mutableListOf<Playlist>()
        val added = mutableSetOf<String>()
        val removals = mutableSetOf<String>()

        for (id in LinkedHashSet(local.playlists.map { it.id } + cloud.playlists.map { it.id })) {
            val here = localById[id]
            val there = cloudById[id]
            val deletedAt = deleted[id]
            when {
                here == null && there != null -> when {
                    known?.containsKey(id) == true -> deleted[id] = now
                    deletedAt != null && there.createdAt <= deletedAt -> Unit
                    duplicates(there) -> upload += there
                    else -> { upload += there; writes += there; added += id }
                }
                here != null && deletedAt != null && here.createdAt <= deletedAt && id !in protected -> removals += id
                here != null && there == null -> { deleted.remove(id); upload += here }
                here != null && there != null -> {
                    deleted.remove(id)
                    val createdAt = oldest(here.createdAt, there.createdAt)
                    val mine = fingerprint(here)
                    val theirs = fingerprint(there)
                    val agreed = known?.get(id)
                    val merged = when {
                        mine == theirs || agreed == theirs -> here
                        agreed == mine && id !in protected -> there
                        else -> bothChanged(here, there)
                    }.copy(createdAt = createdAt)
                    upload += merged
                    if (fingerprint(merged) != mine || createdAt != here.createdAt) writes += merged
                }
            }
        }

        val favorites = mergeItems(local.favorites, cloud.favorites, baseline?.favorites) { it.id }
        val albums = mergeItems(local.albums, cloud.albums, baseline?.albums) { it.id }
        val artists = mergeItems(local.artists, cloud.artists, baseline?.artists) { it.id }
        val cloudPinsWin = cloud.pinsAt > local.pinsAt
        val pins = if (cloudPinsWin) cloud.pins else local.pins
        val pinsAt = maxOf(local.pinsAt, cloud.pinsAt)
        val account = SyncedLibrary(upload, favorites.merged, albums.merged, artists.merged, pins, pinsAt, deleted)

        return SyncPlan(
            account = account,
            accountChanged = upload.map { it.id to fingerprint(it) }.toSet() != cloud.playlists.map { it.id to fingerprint(it) }.toSet() ||
                upload.associate { it.id to it.createdAt } != cloud.playlists.associate { it.id to it.createdAt } ||
                favorites.merged.mapTo(HashSet()) { it.id } != cloud.favorites.mapTo(HashSet()) { it.id } ||
                albums.merged.mapTo(HashSet()) { it.id } != cloud.albums.mapTo(HashSet()) { it.id } ||
                artists.merged.mapTo(HashSet()) { it.id } != cloud.artists.mapTo(HashSet()) { it.id } ||
                pins != cloud.pins || pinsAt != cloud.pinsAt || deleted != cloud.deleted,
            writePlaylists = writes,
            addedPlaylistIds = added,
            deletePlaylists = removals,
            addFavorites = favorites.add,
            removeFavorites = favorites.remove,
            addAlbums = albums.add,
            removeAlbums = albums.remove,
            addArtists = artists.add,
            removeArtists = artists.remove,
            pins = pins,
            pinsAt = pinsAt,
            baseline = SyncBaseline(
                upload.associate { it.id to fingerprint(it) },
                favorites.merged.mapTo(HashSet()) { it.id },
                albums.merged.mapTo(HashSet()) { it.id },
                artists.merged.mapTo(HashSet()) { it.id }
            )
        )
    }
}
