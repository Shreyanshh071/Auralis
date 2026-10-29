package com.auralis.music.desktop.library

import com.auralis.music.data.network.ContentSettingsSource
import com.auralis.music.domain.model.AddToPlaylistPosition
import com.auralis.music.domain.model.Playlist
import com.auralis.music.domain.model.SavedAlbum
import com.auralis.music.domain.model.SavedArtist
import com.auralis.music.domain.model.Track
import com.auralis.music.domain.repository.LibraryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * The desktop library (%APPDATA%\Auralis\library.json): liked songs, playlists, saved artists and
 * albums, with the phone's rules (newest likes first, re-liking keeps the original date, no
 * duplicate songs in a playlist, Settings' add-to-playlist position).
 */
class DesktopLibraryRepository(file: File) : LibraryRepository {
    private data class Liked(val track: Track, val addedAt: Long)

    private val store = JsonFileStore(file)
    private val favorites = MutableStateFlow<List<Liked>>(emptyList())
    private val playlists = MutableStateFlow<List<Playlist>>(emptyList())
    private val artists = MutableStateFlow<List<SavedArtist>>(emptyList())
    private val albums = MutableStateFlow<List<SavedAlbum>>(emptyList())

    fun tracksForLyrics(): Sequence<Track> = favorites.value.asSequence().map { it.track } +
        playlists.value.asSequence().flatMap { it.tracks.asSequence() }

    init {
        store.read()?.let { json ->
            favorites.value = json.optJSONArray("favorites")?.let { a ->
                List(a.length()) { i -> a.getJSONObject(i).let { Liked(TrackJson.decode(it.getJSONObject("track")), it.optLong("addedAt")) } }
            }.orEmpty()
            playlists.value = json.optJSONArray("playlists")?.let { a ->
                List(a.length()) { i ->
                    val o = a.getJSONObject(i)
                    Playlist(
                        id = o.getString("id"), title = o.getString("title"),
                        description = o.optStringOrNull("description"), coverUrl = o.optStringOrNull("coverUrl"),
                        tracks = TrackJson.decodeList(o.optJSONArray("tracks")),
                        createdAt = o.optLong("createdAt"), isCustom = o.optBoolean("isCustom", true)
                    )
                }
            }.orEmpty()
            artists.value = json.optJSONArray("artists")?.let { a ->
                List(a.length()) { i ->
                    val o = a.getJSONObject(i)
                    SavedArtist(o.getString("id"), o.getString("name"), o.optStringOrNull("thumbnail"), o.optStringOrNull("subscribers"), o.optStringOrNull("query"), o.optLong("savedAt"))
                }
            }.orEmpty()
            albums.value = json.optJSONArray("albums")?.let { a ->
                List(a.length()) { i ->
                    val o = a.getJSONObject(i)
                    SavedAlbum(o.getString("id"), o.getString("title"), o.optStringOrNull("artist"), o.optStringOrNull("thumbnail"),
                        if (o.has("trackCount") && !o.isNull("trackCount")) o.getInt("trackCount") else null, o.optLong("savedAt"))
                }
            }.orEmpty()
        }
    }

    private fun save() = store.write {
        JSONObject().apply {
            put("version", 1)
            put("favorites", JSONArray().apply { favorites.value.forEach { put(JSONObject().put("track", TrackJson.encode(it.track)).put("addedAt", it.addedAt)) } })
            put("playlists", JSONArray().apply {
                playlists.value.forEach { p ->
                    put(JSONObject().put("id", p.id).put("title", p.title).putOpt("description", p.description).putOpt("coverUrl", p.coverUrl)
                        .put("tracks", TrackJson.encodeList(p.tracks)).put("createdAt", p.createdAt).put("isCustom", p.isCustom))
                }
            })
            put("artists", JSONArray().apply {
                artists.value.forEach { a -> put(JSONObject().put("id", a.id).put("name", a.name).putOpt("thumbnail", a.thumbnail).putOpt("subscribers", a.subscribers).putOpt("query", a.query).put("savedAt", a.savedAt)) }
            })
            put("albums", JSONArray().apply {
                albums.value.forEach { a -> put(JSONObject().put("id", a.id).put("title", a.title).putOpt("artist", a.artist).putOpt("thumbnail", a.thumbnail).putOpt("trackCount", a.trackCount).put("savedAt", a.savedAt)) }
            })
        }
    }

    // ── Liked songs ──
    override fun getFavoriteTracks(): Flow<List<Track>> = favorites.map { list -> list.sortedByDescending { it.addedAt }.map { it.track } }
    override fun isFavorite(trackId: String): Flow<Boolean> = favorites.map { list -> list.any { it.track.id == trackId } }
    override suspend fun toggleFavorite(track: Track) = setFavorite(track, favorites.value.none { it.track.id == track.id })

    override suspend fun setFavorite(track: Track, isFavorite: Boolean) {
        favorites.update { list ->
            when {
                // Re-liking keeps when it was first liked, as on the phone.
                isFavorite && list.any { it.track.id == track.id } -> list
                isFavorite -> list + Liked(track, System.currentTimeMillis())
                else -> list.filterNot { it.track.id == track.id }
            }
        }
        save()
    }

    suspend fun likedSeeds(limit: Int): List<Track> = favorites.value.sortedByDescending { it.addedAt }.take(limit).map { it.track }

    // ── Playlists ──
    override fun getPlaylists(): Flow<List<Playlist>> = playlists.map { list -> list.sortedByDescending { it.createdAt } }
    override fun getPlaylist(playlistId: String): Flow<Playlist?> = playlists.map { list -> list.firstOrNull { it.id == playlistId } }

    override suspend fun createPlaylist(title: String, description: String?, coverUrl: String?): Playlist {
        val playlist = Playlist(UUID.randomUUID().toString(), title, description, coverUrl, createdAt = System.currentTimeMillis())
        playlists.update { it + playlist }
        save()
        return playlist
    }

    override suspend fun restorePlaylist(id: String, title: String, description: String?, coverUrl: String?): Playlist {
        val existing = playlists.value.firstOrNull { it.id == id }
        val playlist = existing?.copy(title = title, description = description, coverUrl = coverUrl) ?: Playlist(id, title, description, coverUrl)
        playlists.update { list -> if (existing != null) list.map { if (it.id == id) playlist else it } else list + playlist }
        save()
        return playlist
    }

    override suspend fun upsertImportedPlaylist(source: String, remoteId: String, title: String, description: String?, coverUrl: String?): Playlist =
        restorePlaylist("imported:$source:$remoteId", title, description, coverUrl)

    override suspend fun updatePlaylist(playlistId: String, title: String, description: String?, coverUrl: String?) =
        editPlaylist(playlistId) { it.copy(title = title, description = description, coverUrl = coverUrl) }

    override suspend fun addTrackToPlaylist(playlistId: String, track: Track): Boolean {
        val playlist = playlists.value.firstOrNull { it.id == playlistId } ?: return false
        if (playlist.tracks.any { it.id == track.id }) return false
        val atStart = ContentSettingsSource.current().addToPlaylistPosition == AddToPlaylistPosition.BEGINNING
        editPlaylist(playlistId) { it.copy(tracks = if (atStart) listOf(track) + it.tracks else it.tracks + track) }
        return true
    }

    override suspend fun removeTrackFromPlaylist(playlistId: String, trackId: String) =
        editPlaylist(playlistId) { p -> p.copy(tracks = p.tracks.filterNot { it.id == trackId }) }

    override suspend fun deletePlaylist(playlistId: String) {
        playlists.update { list -> list.filterNot { it.id == playlistId } }
        save()
    }

    override suspend fun reorderPlaylist(playlistId: String, tracks: List<Track>) = editPlaylist(playlistId) { it.copy(tracks = tracks) }
    override suspend fun replacePlaylistTracks(playlistId: String, tracks: List<Track>) = editPlaylist(playlistId) { it.copy(tracks = tracks) }

    override suspend fun getAllTracks(): List<Track> =
        (favorites.value.map { it.track } + playlists.value.flatMap { it.tracks }).distinctBy { it.id }

    private fun editPlaylist(id: String, change: (Playlist) -> Playlist) {
        playlists.update { list -> list.map { if (it.id == id) change(it) else it } }
        save()
    }

    // ── Saved artists and albums ──
    override fun getSavedArtists(): Flow<List<SavedArtist>> = artists.map { list -> list.sortedByDescending { it.savedAt } }
    override fun isArtistSaved(artistId: String): Flow<Boolean> = artists.map { list -> list.any { it.id == artistId } }
    override suspend fun saveArtist(artist: SavedArtist) {
        artists.update { list -> list.filterNot { it.id == artist.id } + artist }
        save()
    }
    override suspend fun removeArtist(artistId: String) {
        artists.update { list -> list.filterNot { it.id == artistId } }
        save()
    }

    override fun getSavedAlbums(): Flow<List<SavedAlbum>> = albums.map { list -> list.sortedByDescending { it.savedAt } }
    override fun isAlbumSaved(albumId: String): Flow<Boolean> = albums.map { list -> list.any { it.id == albumId } }
    override suspend fun saveAlbum(album: SavedAlbum) {
        albums.update { list -> list.filterNot { it.id == album.id } + album }
        save()
    }
    override suspend fun removeAlbum(albumId: String) {
        albums.update { list -> list.filterNot { it.id == albumId } }
        save()
    }
}
