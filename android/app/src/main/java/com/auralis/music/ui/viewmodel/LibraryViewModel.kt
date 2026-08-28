package com.auralis.music.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.auralis.music.data.network.SpotifyPlaylistImporter
import com.auralis.music.data.network.YouTubePlaylistImporter
import com.auralis.music.domain.model.Playlist
import com.auralis.music.domain.model.SavedAlbum
import com.auralis.music.domain.model.SavedArtist
import com.auralis.music.domain.model.Track
import com.auralis.music.domain.model.TrackSource
import com.auralis.music.domain.repository.HistoryRepository
import com.auralis.music.domain.repository.LibraryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

enum class LibraryFilter { PLAYLISTS, SONGS, ALBUMS, ARTISTS, PODCASTS }

enum class SmartCollectionType {
    LIKED,
    DOWNLOADED,
    CACHED,
    MY_TOP_50,
    UPLOADED
}

data class LibraryUiState(
    val selectedFilter: LibraryFilter = LibraryFilter.PLAYLISTS,
    val playlists: List<Playlist> = emptyList(),
    val favorites: List<Track> = emptyList(),
    val savedArtists: List<SavedArtist> = emptyList(),
    val savedAlbums: List<SavedAlbum> = emptyList(),
    val top50Tracks: List<Track> = emptyList(),
    val cachedTracks: List<Track> = emptyList(),
    val selectedPlaylist: Playlist? = null,
    val selectedSmartCollection: SmartCollectionType? = null,
    val isGridView: Boolean = true,
    val sortOrder: String = "Date added",
    val isImporting: Boolean = false,
    val importMessage: String? = null,
    val isImportingSpotify: Boolean = false,
    val spotifyImportMessage: String? = null
)

class LibraryViewModel(
    private val libraryRepository: LibraryRepository,
    private val youtubeImporter: YouTubePlaylistImporter = YouTubePlaylistImporter(),
    private val spotifyImporter: SpotifyPlaylistImporter = SpotifyPlaylistImporter()
) : ViewModel() {

    private val _uiState = MutableStateFlow(LibraryUiState())
    val uiState: StateFlow<LibraryUiState> = _uiState.asStateFlow()
    private var selectPlaylistJob: kotlinx.coroutines.Job? = null

    init {
        // Collect playlists
        viewModelScope.launch {
            libraryRepository.getPlaylists().collect { playlists ->
                _uiState.update { it.copy(playlists = playlists) }
            }
        }

        // Collect favorites
        viewModelScope.launch {
            libraryRepository.getFavoriteTracks().collect { favs ->
                _uiState.update { it.copy(favorites = favs) }
            }
        }

        // Collect artists
        viewModelScope.launch {
            libraryRepository.getSavedArtists().collect { artists ->
                _uiState.update { it.copy(savedArtists = artists) }
            }
        }

        // Collect albums
        viewModelScope.launch {
            libraryRepository.getSavedAlbums().collect { albums ->
                _uiState.update { it.copy(savedAlbums = albums) }
            }
        }
    }

    fun enrichPlaylist(playlist: Playlist) {
        viewModelScope.launch {
            try {
                val needsEnrich = playlist.tracks.any {
                    it.thumbnail.contains("mosaic.scdn.co") ||
                    it.thumbnail.contains("image-cdn") ||
                    it.id.startsWith("sp_") ||
                    (!playlist.coverUrl.isNullOrBlank() && it.thumbnail == playlist.coverUrl)
                }
                if (needsEnrich && playlist.tracks.isNotEmpty()) {
                    android.util.Log.i("LibraryViewModel", "Enriching playlist '${playlist.title}' (${playlist.tracks.size} tracks) with official artwork...")
                    val enriched = spotifyImporter.enrichTracksWithYouTubeData(playlist.tracks)
                    libraryRepository.replacePlaylistTracks(playlist.id, enriched)
                    _uiState.update { state ->
                        if (state.selectedPlaylist?.id == playlist.id) {
                            state.copy(selectedPlaylist = playlist.copy(tracks = enriched))
                        } else state
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("LibraryViewModel", "enrichPlaylist failed: ${e.message}")
            }
        }
    }

    private suspend fun enrichExistingPlaylistsWithArtwork() {
        try {
            kotlinx.coroutines.delay(3000) // Delay startup enrichment so initial user playback has 100% priority
            val playlists = libraryRepository.getPlaylists().firstOrNull() ?: return
            for (pl in playlists) {
                while (com.auralis.music.data.network.AudioStreamResolver.isPlaybackResolving) {
                    kotlinx.coroutines.delay(1000)
                }
                val hasCorruptedTitles = pl.tracks.any {
                    it.title.startsWith("From \"", ignoreCase = true) ||
                    it.title.startsWith("From '", ignoreCase = true)
                }
                val needsEnrich = hasCorruptedTitles || pl.tracks.any {
                    it.thumbnail.contains("mosaic.scdn.co") ||
                    it.thumbnail.contains("image-cdn") ||
                    it.id.startsWith("sp_") ||
                    (!pl.coverUrl.isNullOrBlank() && it.thumbnail == pl.coverUrl)
                }
                if (needsEnrich && pl.tracks.isNotEmpty()) {
                    android.util.Log.i("LibraryViewModel", "Auto-enriching/repairing playlist '${pl.title}'...")
                    val enriched = spotifyImporter.enrichTracksWithYouTubeData(pl.tracks)
                    libraryRepository.replacePlaylistTracks(pl.id, enriched)
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("LibraryViewModel", "Enrich existing playlists notice: ${e.message}")
        }
    }

    fun setFilter(filter: LibraryFilter) {
        _uiState.update { it.copy(selectedFilter = filter, selectedPlaylist = null, selectedSmartCollection = null) }
    }

    fun toggleGridView() {
        _uiState.update { it.copy(isGridView = !it.isGridView) }
    }

    fun setSortOrder(sort: String) {
        _uiState.update { it.copy(sortOrder = sort) }
    }

    fun openSmartCollection(type: SmartCollectionType) {
        val virtualPlaylist = when (type) {
            SmartCollectionType.LIKED -> Playlist(
                id = "smart_liked",
                title = "Liked Music",
                description = "Auto-saved tracks",
                tracks = _uiState.value.favorites
            )
            SmartCollectionType.DOWNLOADED -> Playlist(
                id = "smart_downloaded",
                title = "Downloaded",
                description = "Offline available music",
                tracks = _uiState.value.favorites.take(15)
            )
            SmartCollectionType.CACHED -> Playlist(
                id = "smart_cached",
                title = "Cached Stream Cache",
                description = "Locally buffered tracks",
                tracks = _uiState.value.favorites.take(10)
            )
            SmartCollectionType.MY_TOP_50 -> Playlist(
                id = "smart_top50",
                title = "My Top 50",
                description = "Your most played tracks",
                tracks = _uiState.value.favorites
            )
            SmartCollectionType.UPLOADED -> Playlist(
                id = "smart_uploaded",
                title = "Uploaded Music",
                description = "User uploaded files",
                tracks = emptyList()
            )
        }
        _uiState.update { it.copy(selectedPlaylist = virtualPlaylist, selectedSmartCollection = type) }
    }

    fun createPlaylist(title: String, description: String? = null) {
        if (title.isBlank()) return
        viewModelScope.launch {
            libraryRepository.createPlaylist(title.trim(), description?.trim())
        }
    }

    fun createPlaylistAndAddTrack(title: String, track: Track, description: String? = null) {
        if (title.isBlank()) return
        viewModelScope.launch {
            val playlist = libraryRepository.createPlaylist(title.trim(), description?.trim())
            libraryRepository.addTrackToPlaylist(playlist.id, track)
        }
    }

    fun editPlaylist(playlistId: String, newTitle: String, newDescription: String?, newCoverUrl: String? = null) {
        if (newTitle.isBlank()) return
        viewModelScope.launch {
            libraryRepository.updatePlaylist(
                playlistId = playlistId,
                title = newTitle.trim(),
                description = newDescription?.trim(),
                coverUrl = newCoverUrl?.trim()
            )
            // Update selectedPlaylist in UI state immediately if currently viewing it
            val currentSelected = _uiState.value.selectedPlaylist
            if (currentSelected?.id == playlistId) {
                _uiState.update {
                    it.copy(
                        selectedPlaylist = currentSelected.copy(
                            title = newTitle.trim(),
                            description = newDescription?.trim(),
                            coverUrl = newCoverUrl?.trim()
                        )
                    )
                }
            }
        }
    }

    fun syncPlaylist(playlist: Playlist, onComplete: ((Int) -> Unit)? = null) {
        _uiState.update { it.copy(isImporting = true, importMessage = "Syncing '${playlist.title}' with YouTube Music...") }
        viewModelScope.launch {
            try {
                val queryOrId = playlist.id.ifBlank { playlist.title }
                val imported = youtubeImporter.importPlaylist(queryOrId) ?: youtubeImporter.importPlaylist(playlist.title)
                if (imported != null && imported.tracks.isNotEmpty()) {
                    val existingIds = playlist.tracks.map { it.id }.toSet()
                    val newTracks = imported.tracks.filter { it.id !in existingIds }
                    val mergedTracks = playlist.tracks + newTracks
                    libraryRepository.reorderPlaylist(playlist.id, mergedTracks)
                    val updatedPlaylist = playlist.copy(tracks = mergedTracks)
                    _uiState.update {
                        it.copy(
                            isImporting = false,
                            importMessage = "Synced ${mergedTracks.size} songs for '${playlist.title}'!",
                            selectedPlaylist = if (it.selectedPlaylist?.id == playlist.id) updatedPlaylist else it.selectedPlaylist
                        )
                    }
                    onComplete?.invoke(mergedTracks.size)
                } else {
                    _uiState.update {
                        it.copy(
                            isImporting = false,
                            importMessage = "'${playlist.title}' is up to date."
                        )
                    }
                    onComplete?.invoke(playlist.tracks.size)
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isImporting = false,
                        importMessage = "Sync failed: ${e.localizedMessage}"
                    )
                }
                onComplete?.invoke(-1)
            }
        }
    }

    fun deletePlaylist(playlistId: String) {
        viewModelScope.launch {
            libraryRepository.deletePlaylist(playlistId)
            if (_uiState.value.selectedPlaylist?.id == playlistId) {
                _uiState.update { it.copy(selectedPlaylist = null, selectedSmartCollection = null) }
            }
        }
    }

    fun selectPlaylist(playlistId: String?, initialPlaylist: Playlist? = null) {
        selectPlaylistJob?.cancel()
        if (playlistId == null) {
            _uiState.update { it.copy(selectedPlaylist = null, selectedSmartCollection = null) }
            return
        }
        val cached = initialPlaylist ?: _uiState.value.playlists.find { it.id == playlistId }
        if (cached != null) {
            _uiState.update { it.copy(selectedPlaylist = cached, selectedSmartCollection = null) }
        }
        selectPlaylistJob = viewModelScope.launch {
            libraryRepository.getPlaylist(playlistId).collect { pl ->
                _uiState.update { it.copy(selectedPlaylist = pl, selectedSmartCollection = null) }
                if (pl != null) {
                }
            }
        }
    }

    fun addTrackToPlaylist(playlistId: String, track: Track) {
        viewModelScope.launch {
            libraryRepository.addTrackToPlaylist(playlistId, track)
        }
    }

    fun removeTrackFromPlaylist(playlistId: String, trackId: String) {
        viewModelScope.launch {
            libraryRepository.removeTrackFromPlaylist(playlistId, trackId)
        }
    }

    fun importYouTubePlaylist(urlOrId: String) {
        if (urlOrId.isBlank()) return
        _uiState.update { it.copy(isImporting = true, importMessage = null) }

        viewModelScope.launch {
            try {
                val imported = youtubeImporter.importPlaylist(urlOrId)
                if (imported != null) {
                    val playlist = libraryRepository.createPlaylist(
                        title = imported.title,
                        description = imported.description
                    )
                    libraryRepository.replacePlaylistTracks(playlist.id, imported.tracks)
                    _uiState.update {
                        it.copy(
                            isImporting = false,
                            importMessage = "Imported '${imported.title}' (${imported.tracks.size} songs)"
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            isImporting = false,
                            importMessage = "Could not import playlist. Make sure it is Public or Unlisted in YouTube Music."
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isImporting = false,
                        importMessage = e.localizedMessage ?: "Failed to import playlist"
                    )
                }
            }
        }
    }

    fun clearYouTubeImportMessage() {
        _uiState.update { it.copy(importMessage = null) }
    }

    fun clearSpotifyImportMessage() {
        _uiState.update { it.copy(spotifyImportMessage = null) }
    }

    fun importSpotifyPlaylist(urlOrLink: String, onComplete: ((Boolean, String) -> Unit)? = null) {
        val trimmed = urlOrLink.trim()
        if (trimmed.isBlank()) return
        android.util.Log.i("SpotifyImporter", "importSpotifyPlaylist called in ViewModel with: '$trimmed'")
        _uiState.update { it.copy(isImportingSpotify = true, spotifyImportMessage = "Connecting to Spotify...") }

        viewModelScope.launch {
            try {
                val imported = spotifyImporter.importPlaylist(
                    urlOrId = trimmed,
                    onProgress = { progressText ->
                        _uiState.update { it.copy(spotifyImportMessage = progressText) }
                    }
                )
                if (imported != null && (imported.tracks.isNotEmpty() || imported.title.isNotBlank())) {
                    val playlist = libraryRepository.createPlaylist(
                        title = imported.title,
                        description = imported.description
                    )
                    if (!imported.coverUrl.isNullOrBlank()) {
                        libraryRepository.updatePlaylist(
                            playlistId = playlist.id,
                            title = imported.title,
                            description = imported.description,
                            coverUrl = imported.coverUrl
                        )
                    }
                    libraryRepository.replacePlaylistTracks(playlist.id, imported.tracks)
                    val successMsg = "Imported '${imported.title}' (${imported.tracks.size} songs from Spotify)"
                    android.util.Log.i("SpotifyImporter", successMsg)
                    _uiState.update {
                        it.copy(
                            isImportingSpotify = false,
                            spotifyImportMessage = successMsg,
                            importMessage = successMsg
                        )
                    }
                    onComplete?.invoke(true, successMsg)
                } else {
                    val errorMsg = "Could not parse Spotify playlist. Please check the link."
                    android.util.Log.e("SpotifyImporter", "Import returned null for: '$trimmed'")
                    _uiState.update {
                        it.copy(
                            isImportingSpotify = false,
                            spotifyImportMessage = errorMsg
                        )
                    }
                    onComplete?.invoke(false, errorMsg)
                }
            } catch (e: Exception) {
                val errorMsg = e.localizedMessage ?: "Failed to import Spotify playlist"
                android.util.Log.e("SpotifyImporter", "Import exception: $errorMsg", e)
                _uiState.update {
                    it.copy(
                        isImportingSpotify = false,
                        spotifyImportMessage = errorMsg
                    )
                }
                onComplete?.invoke(false, errorMsg)
            }
        }
    }

    suspend fun exportLibraryJson(): String = withContext(Dispatchers.IO) {
        val root = JSONObject()
        val playlistsArray = JSONArray()

        for (pl in _uiState.value.playlists) {
            val plObj = JSONObject().apply {
                put("id", pl.id)
                put("title", pl.title)
                put("description", pl.description)
                put("coverUrl", pl.coverUrl)
                val tracksArr = JSONArray()
                for (t in pl.tracks) {
                    val tObj = JSONObject().apply {
                        put("id", t.id)
                        put("title", t.title)
                        put("artist", t.artist)
                        put("album", t.album)
                        put("thumbnail", t.thumbnail)
                        put("duration", t.duration)
                    }
                    tracksArr.put(tObj)
                }
                put("tracks", tracksArr)
            }
            playlistsArray.put(plObj)
        }
        root.put("playlists", playlistsArray)

        val favsArr = JSONArray()
        for (f in _uiState.value.favorites) {
            val fObj = JSONObject().apply {
                put("id", f.id)
                put("title", f.title)
                put("artist", f.artist)
                put("album", f.album)
                put("thumbnail", f.thumbnail)
                put("duration", f.duration)
            }
            favsArr.put(fObj)
        }
        root.put("favorites", favsArr)

        root.toString(2)
    }

    fun importLibraryJson(jsonString: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val root = JSONObject(jsonString)
                val playlistsArr = root.optJSONArray("playlists")
                if (playlistsArr != null) {
                    for (i in 0 until playlistsArr.length()) {
                        val plObj = playlistsArr.optJSONObject(i) ?: continue
                        val title = plObj.optString("title", "Imported Playlist")
                        val desc = plObj.optString("description")
                        val playlist = libraryRepository.createPlaylist(title, desc)

                        val tracksArr = plObj.optJSONArray("tracks")
                        if (tracksArr != null) {
                            val tracks = mutableListOf<Track>()
                            for (j in 0 until tracksArr.length()) {
                                val tObj = tracksArr.optJSONObject(j) ?: continue
                                val track = Track(
                                    id = tObj.optString("id"),
                                    title = tObj.optString("title"),
                                    artist = tObj.optString("artist"),
                                    album = tObj.optString("album"),
                                    thumbnail = tObj.optString("thumbnail"),
                                    duration = tObj.optLong("duration", 210L),
                                    source = TrackSource.YOUTUBE
                                )
                                tracks.add(track)
                            }
                            libraryRepository.replacePlaylistTracks(playlist.id, tracks)
                        }
                    }
                }

                val favsArr = root.optJSONArray("favorites")
                if (favsArr != null) {
                    for (i in 0 until favsArr.length()) {
                        val fObj = favsArr.optJSONObject(i) ?: continue
                        val track = Track(
                            id = fObj.optString("id"),
                            title = fObj.optString("title"),
                            artist = fObj.optString("artist"),
                            album = fObj.optString("album"),
                            thumbnail = fObj.optString("thumbnail"),
                            duration = fObj.optLong("duration", 210L),
                            source = TrackSource.YOUTUBE
                        )
                        libraryRepository.toggleFavorite(track)
                    }
                }
            } catch (_: Exception) {}
        }
    }
}
