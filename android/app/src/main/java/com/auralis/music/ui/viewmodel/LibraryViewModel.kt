package com.auralis.music.ui.viewmodel

import com.auralis.music.R
import com.auralis.music.ui.i18n.str

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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
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
    WEEKLY_MOST,
    MONTHLY_MOST,
    UPLOADED
}

data class LibraryUiState(
    val selectedFilter: LibraryFilter = LibraryFilter.PLAYLISTS,
    val playlists: List<Playlist> = emptyList(),
    val favorites: List<Track> = emptyList(),
    val downloadedTracks: List<Track> = emptyList(),
    val downloadedJobs: List<com.auralis.music.data.download.PlaylistDownloadJobEntity> = emptyList(),
    val savedArtists: List<SavedArtist> = emptyList(),
    val savedAlbums: List<SavedAlbum> = emptyList(),
    val top50Tracks: List<Track> = emptyList(),
    /** Most played in the last 7 / 30 days (Settings → Content → Wrapped → Most playlists). */
    val weeklyMostTracks: List<Track> = emptyList(),
    val monthlyMostTracks: List<Track> = emptyList(),
    val cachedTracks: List<Track> = emptyList(),
    val selectedPlaylist: Playlist? = null,
    val selectedSmartCollection: SmartCollectionType? = null,
    val isGridView: Boolean = true,
    val sortOrder: String = "Date added",
    val recentPlayedAtByTrackId: Map<String, Long> = emptyMap(),
    val isImporting: Boolean = false,
    val importMessage: String? = null,
    val isImportingSpotify: Boolean = false,
    val spotifyImportMessage: String? = null
)

class LibraryViewModel(
    private val libraryRepository: LibraryRepository,
    private val youtubeImporter: YouTubePlaylistImporter = YouTubePlaylistImporter(),
    private val spotifyImporter: SpotifyPlaylistImporter = SpotifyPlaylistImporter(),
    private val historyRepository: HistoryRepository? = null,
    private val matchReviewPrefs: android.content.SharedPreferences? = null,
    private val statsRepository: com.auralis.music.domain.repository.StatsRepository? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(LibraryUiState())
    val uiState: StateFlow<LibraryUiState> = _uiState.asStateFlow()

    /** Bumped by [refresh]: recomputes the "last 7 / 30 days" windows from the current time. */
    private val statsRefreshTick = MutableStateFlow(0)
    private var selectPlaylistJob: kotlinx.coroutines.Job? = null
    private val matchingSpotifyPlaylists = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val refreshedSpotifyPlaylists = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val spotifyPreparation = kotlinx.coroutines.channels.Channel<List<Playlist>>(
        kotlinx.coroutines.channels.Channel.CONFLATED)
    private val attemptedSpotifyTracks = mutableSetOf<String>()

    private suspend fun refreshSpotifyPlaylistMetadata(playlistId: String) {
        if (!com.auralis.music.data.network.SpotifySession.isSignedIn || !refreshedSpotifyPlaylists.add(playlistId)) return
        try {
            val remoteId = playlistId.removePrefix("imported:spotify:")
            val remote = com.auralis.music.data.network.SpotifyLibrary.fetchPlaylist(
                playlistId = remoteId, importer = spotifyImporter, matchTracks = false
            ) ?: run { refreshedSpotifyPlaylists.remove(playlistId); return }
            val current = libraryRepository.getPlaylists().firstOrNull()?.find { it.id == playlistId } ?: return
            if (remote.tracks.isEmpty()) return
            val refreshed = remote.tracks.mapIndexed { index, latestTrack ->
                val saved = current.tracks.getOrNull(index)
                if (saved != null && !saved.id.startsWith("sp_") &&
                    saved.title.equals(latestTrack.title, ignoreCase = true) &&
                    saved.artist.equals(latestTrack.artist, ignoreCase = true) &&
                    kotlin.math.abs(saved.duration - latestTrack.duration) <= 4L
                ) saved.copy(album = latestTrack.album, thumbnail = latestTrack.thumbnail) else latestTrack
            }
            if (refreshed != current.tracks) {
                libraryRepository.replacePlaylistTracks(playlistId, refreshed)
            }
            if (remote.title != current.title || remote.coverUrl != current.coverUrl) {
                libraryRepository.updatePlaylist(playlistId, remote.title, remote.description, remote.coverUrl)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            refreshedSpotifyPlaylists.remove(playlistId)
            android.util.Log.w("SpotifyLibrary", "Could not refresh $playlistId: ${e.message}")
        }
    }

    private suspend fun matchSpotifyPlaylistProgressively(playlistId: String) {
        if (!matchingSpotifyPlaylists.add(playlistId)) return
        try {
            // Save playback mappings in small batches without rewriting library rows.
            while (true) {
                while (com.auralis.music.data.network.AudioStreamResolver.isPlaybackResolving) {
                    kotlinx.coroutines.delay(200)
                }
                val current = libraryRepository.getPlaylists().firstOrNull()?.find { it.id == playlistId } ?: return
                val batch = current.tracks.filter { it.id.startsWith("sp_") && it.id !in attemptedSpotifyTracks }.take(4)
                if (batch.isEmpty()) break
                attemptedSpotifyTracks.addAll(batch.map { it.id })
                spotifyImporter.enrichTracksWithYouTubeData(batch)
                kotlinx.coroutines.delay(200)
            }
        } finally {
            matchingSpotifyPlaylists.remove(playlistId)
        }
    }

    init {
        // One worker prepares recording IDs in the background. No stream extraction or
        // playlist refetch is needed, and foreground playback gets priority between batches.
        viewModelScope.launch(Dispatchers.IO) {
            for (playlists in spotifyPreparation) {
                for (playlist in playlists) {
                    try {
                        matchSpotifyPlaylistProgressively(playlist.id)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        android.util.Log.w("SpotifyLibrary", "Recording preparation failed: ${e.message}")
                    }
                }
                // Liked Songs retain their local favorite IDs; only persist their playback
                // mapping so preparation cannot add duplicate favorites.
                val liked = _uiState.value.favorites.filter {
                    it.id.startsWith("sp_") && it.id !in attemptedSpotifyTracks
                }
                for (batch in liked.chunked(4)) {
                    while (com.auralis.music.data.network.AudioStreamResolver.isPlaybackResolving) {
                        kotlinx.coroutines.delay(200)
                    }
                    attemptedSpotifyTracks.addAll(batch.map { it.id })
                    spotifyImporter.enrichTracksWithYouTubeData(batch)
                    kotlinx.coroutines.delay(200)
                }
            }
        }
        historyRepository?.let { repository ->
            viewModelScope.launch {
                repository.getHistory().collect { history ->
                    _uiState.update { state ->
                        state.copy(
                            recentPlayedAtByTrackId = history
                                .groupBy { it.track.id }
                                .mapValues { (_, plays) -> plays.maxOf { it.playedAt } }
                        )
                    }
                }
            }
        }

        // Top / weekly / monthly most played, from measured listens only (playback events).
        statsRepository?.let { stats ->
            viewModelScope.launch {
                kotlinx.coroutines.flow.combine(
                    com.auralis.music.data.datastore.ContentSettingsStore.current
                        .map { it.topLength to it.showMostStatsPlaylists }
                        .distinctUntilChanged(),
                    statsRefreshTick
                ) { settings, _ -> settings }
                    .collectLatest { (topLength, showMost) ->
                        val now = System.currentTimeMillis()
                        val day = 24L * 60 * 60 * 1000
                        // Open-ended "to" so listens logged after this screen opened still count.
                        kotlinx.coroutines.flow.combine(
                            stats.observeTopSongs(0L, Long.MAX_VALUE, limit = topLength),
                            if (showMost) stats.observeTopSongs(now - 7 * day, Long.MAX_VALUE, limit = MOST_PLAYLIST_SIZE)
                            else kotlinx.coroutines.flow.flowOf(emptyList()),
                            if (showMost) stats.observeTopSongs(now - 30 * day, Long.MAX_VALUE, limit = MOST_PLAYLIST_SIZE)
                            else kotlinx.coroutines.flow.flowOf(emptyList())
                        ) { top, weekly, monthly -> Triple(top, weekly, monthly) }
                            .collect { (top, weekly, monthly) ->
                                _uiState.update { state ->
                                    state.copy(
                                        top50Tracks = top.map { it.track },
                                        weeklyMostTracks = weekly.map { it.track },
                                        monthlyMostTracks = monthly.map { it.track }
                                    )
                                }
                            }
                    }
            }
        }

        // Collect playlists
        viewModelScope.launch {
            libraryRepository.getPlaylists().collect { playlists ->
                spotifyPreparation.trySend(playlists.filter { playlist ->
                    playlist.tracks.any { it.id.startsWith("sp_") }
                })
                _uiState.update { state ->
                    val updatedSelected = if (state.selectedPlaylist != null && !state.selectedPlaylist.id.startsWith("smart_")) {
                        playlists.find { it.id == state.selectedPlaylist.id } ?: state.selectedPlaylist
                    } else {
                        state.selectedPlaylist
                    }
                    state.copy(playlists = playlists, selectedPlaylist = updatedSelected)
                }
            }
        }

        // Collect favorites
        viewModelScope.launch {
            libraryRepository.getFavoriteTracks().collect { favs ->
                _uiState.update { it.copy(favorites = favs) }
                if (favs.any { it.id.startsWith("sp_") }) {
                    spotifyPreparation.trySend(_uiState.value.playlists.filter { playlist ->
                        playlist.tracks.any { it.id.startsWith("sp_") }
                    })
                }
            }
        }

        // Collect downloaded tracks
        viewModelScope.launch {
            com.auralis.music.data.download.AuralisDownloadManager.downloadedTracks.collect { downloaded ->
                _uiState.update { state ->
                    val updated = state.copy(downloadedTracks = downloaded)
                    if (state.selectedSmartCollection == SmartCollectionType.DOWNLOADED) {
                        val current = state.selectedPlaylist
                        if (current != null && current.id == "smart_downloaded_all") {
                            updated.copy(
                                selectedPlaylist = current.copy(
                                    description = "${downloaded.size} offline songs",
                                    tracks = downloaded
                                )
                            )
                        } else updated
                    } else updated
                }
            }
        }

        // Collect playlist download jobs for folder-based downloads
        viewModelScope.launch {
            com.auralis.music.data.download.PlaylistDownloadCoordinator.jobs.collect { jobsMap ->
                _uiState.update { state ->
                    val jobsList = jobsMap.values.toList()
                    val updated = state.copy(downloadedJobs = jobsList)
                    val current = state.selectedPlaylist
                    if (state.selectedSmartCollection == SmartCollectionType.DOWNLOADED && current != null && current.id.startsWith("smart_downloaded_folder_")) {
                        val folderJobId = current.id.removePrefix("smart_downloaded_folder_")
                        val job = jobsMap.values.firstOrNull { it.jobId == folderJobId || it.playlistId == folderJobId }
                        if (job != null) {
                            val allTracks = com.auralis.music.data.download.PlaylistDownloadCoordinator.tracks(job)
                            val successfulTrackIds = com.auralis.music.data.download.PlaylistDownloadCoordinator.results(job)
                                .filter { it.outcome in com.auralis.music.data.download.PlaylistDownloadRepository.SUCCESS_OUTCOMES }
                                .map { it.trackId }
                                .toSet()
                            val downloadedForJob = allTracks.filter { it.id in successfulTrackIds && com.auralis.music.data.download.AuralisDownloadManager.isDownloaded(it.id) }
                            val originalPlaylist = state.playlists.firstOrNull { it.id == job.playlistId || it.title.equals(job.playlistName, ignoreCase = true) }
                            updated.copy(
                                selectedPlaylist = current.copy(
                                    description = "${downloadedForJob.size} of ${allTracks.size} offline songs",
                                    coverUrl = originalPlaylist?.coverUrl?.takeIf { it.isNotBlank() },
                                    tracks = downloadedForJob
                                )
                            )
                        } else updated
                    } else updated
                }
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

    fun toggleSaveArtist(artist: SavedArtist) {
        viewModelScope.launch {
            val isSaved = _uiState.value.savedArtists.any { it.id == artist.id || it.name.equals(artist.name, ignoreCase = true) }
            if (isSaved) {
                libraryRepository.removeArtist(artist.id)
            } else {
                libraryRepository.saveArtist(artist)
            }
        }
    }

    fun toggleSaveAlbum(album: SavedAlbum) {
        viewModelScope.launch {
            val isSaved = _uiState.value.savedAlbums.any { it.id == album.id || it.title.equals(album.title, ignoreCase = true) }
            if (isSaved) {
                libraryRepository.removeAlbum(album.id)
            } else {
                libraryRepository.saveAlbum(album)
            }
        }
    }

    fun enrichPlaylist(playlist: Playlist) {
        if (playlist.id.startsWith("imported:spotify:")) {
            // Opening a playlist must not fetch and rematch its entire remote library.
            return
        }
        viewModelScope.launch {
            try {
                val needsEnrich = playlist.tracks.any {
                    it.thumbnail.contains("mosaic.scdn.co") ||
                    it.thumbnail.contains("image-cdn") ||
                    it.id.startsWith("sp_") ||
                    (playlist.id.startsWith("sp_") && !playlist.coverUrl.isNullOrBlank() && it.thumbnail == playlist.coverUrl)
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

    /**
     * One pass over every saved playlist with the current recording matcher: songs an earlier
     * matcher bound to another recording (a remix, a re-recording, a feat. release) are moved to
     * the right one, keeping their place and their Spotify details. Runs once per matcher version.
     */
    private suspend fun reviewSavedRecordingMatchesOnce() {
        val prefs = matchReviewPrefs ?: return
        if (prefs.getInt(RECORDING_REVIEW_KEY, 0) >= RECORDING_REVIEW_VERSION) return
        kotlinx.coroutines.delay(8_000) // let startup playback go first
        try {
            val playlists = libraryRepository.getPlaylists().firstOrNull() ?: return
            val saved = playlists.flatMap { it.tracks }
                .distinctBy { it.id }
                .filterNot { it.id.startsWith("sp_") || it.id.startsWith("spotify:") }
        val gate = kotlinx.coroutines.sync.Semaphore(1)
            val corrections = java.util.concurrent.ConcurrentHashMap<String, Track>()
            kotlinx.coroutines.coroutineScope {
                saved.forEach { track ->
                    launch {
                        gate.acquire()
                        try {
                            while (com.auralis.music.data.network.AudioStreamResolver.isPlaybackResolving) {
                                kotlinx.coroutines.delay(1_000)
                            }
                            spotifyImporter.correctedMatch(track)?.let { corrections[track.id] = it }
                        } catch (e: Exception) {
                            if (e is kotlinx.coroutines.CancellationException) throw e
                        } finally {
                            gate.release()
                        }
                    }
                }
            }
            for (pl in playlists) {
                if (pl.tracks.none { corrections.containsKey(it.id) }) continue
                libraryRepository.replacePlaylistTracks(pl.id, pl.tracks.map { corrections[it.id] ?: it })
            }
            android.util.Log.i("LibraryViewModel", "Recording review: ${corrections.size} of ${saved.size} saved songs moved to the right recording")
            prefs.edit().putInt(RECORDING_REVIEW_KEY, RECORDING_REVIEW_VERSION).apply()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            android.util.Log.w("LibraryViewModel", "Recording review failed, will retry next launch: ${e.message}")
        }
    }

    private suspend fun enrichExistingPlaylistsWithArtwork() {
        try {
            kotlinx.coroutines.delay(3000) // Delay startup enrichment so initial user playback has 100% priority
            val playlists = libraryRepository.getPlaylists().firstOrNull() ?: return
            for (pl in playlists) {
                if (pl.id.startsWith("imported:spotify:")) continue
                while (com.auralis.music.data.network.AudioStreamResolver.isPlaybackResolving) {
                    kotlinx.coroutines.delay(1000)
                }

                // If album playlist has missing coverUrl, backfill it from its authentic track thumbnail
                if (pl.coverUrl.isNullOrBlank() && com.auralis.music.ui.library.isAlbumPlaylist(pl)) {
                    val canonicalArt = pl.tracks.firstOrNull { !it.thumbnail.isNullOrBlank() }?.thumbnail
                    if (!canonicalArt.isNullOrBlank()) {
                        try {
                            libraryRepository.updatePlaylist(pl.id, pl.title, pl.description, canonicalArt)
                        } catch (_: Exception) {}
                    }
                }

                val hasCorruptedTitles = pl.tracks.any {
                    it.title.startsWith("From \"", ignoreCase = true) ||
                    it.title.startsWith("From '", ignoreCase = true)
                }
                val needsEnrich = hasCorruptedTitles || pl.tracks.any {
                    it.thumbnail.contains("mosaic.scdn.co") ||
                    it.thumbnail.contains("image-cdn") ||
                    it.id.startsWith("sp_") ||
                    (pl.id.startsWith("sp_") && !pl.coverUrl.isNullOrBlank() && it.thumbnail == pl.coverUrl)
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

    /**
     * Pull-to-refresh. Library data is local and already live, so this recomputes what is
     * time-based (Top, Weekly and Monthly Most) and re-reads offline songs.
     */
    suspend fun refresh() {
        statsRefreshTick.value += 1
        // Give the re-queries a moment to land before the indicator hides.
        kotlinx.coroutines.delay(600)
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
                title = str(R.string.liked_music),
                description = str(R.string.auto_saved_tracks),
                tracks = _uiState.value.favorites
            )
            SmartCollectionType.DOWNLOADED -> null
            SmartCollectionType.CACHED -> Playlist(
                id = "smart_cached",
                title = str(R.string.cached_stream_cache),
                description = str(R.string.locally_buffered_tracks),
                tracks = _uiState.value.cachedTracks
            )
            SmartCollectionType.MY_TOP_50 -> Playlist(
                id = "smart_top_50",
                title = str(R.string.top_most_played),
                description = str(R.string.your_most_played_tracks),
                tracks = _uiState.value.top50Tracks
            )
            SmartCollectionType.WEEKLY_MOST -> Playlist(
                id = "smart_weekly_most",
                title = str(R.string.weekly_most_played),
                description = str(R.string.most_played_in_the_last_7_days),
                tracks = _uiState.value.weeklyMostTracks
            )
            SmartCollectionType.MONTHLY_MOST -> Playlist(
                id = "smart_monthly_most",
                title = str(R.string.monthly_most_played),
                description = str(R.string.most_played_in_the_last_30_days),
                tracks = _uiState.value.monthlyMostTracks
            )
            SmartCollectionType.UPLOADED -> Playlist(
                id = "smart_uploaded",
                title = str(R.string.uploaded_music),
                description = str(R.string.user_uploaded_files),
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

    fun createPlaylistAndAddTracks(
        title: String,
        tracks: List<Track>,
        description: String? = null,
        coverUrl: String? = null,
        onCreated: ((Playlist) -> Unit)? = null
    ) {
        if (title.isBlank()) return
        viewModelScope.launch {
            try {
                val cleanTitle = title.trim()
                val targetPlaylist = libraryRepository.createPlaylist(
                    title = cleanTitle,
                    description = description?.trim(),
                    coverUrl = coverUrl
                )
                if (tracks.isNotEmpty()) {
                    libraryRepository.replacePlaylistTracks(targetPlaylist.id, tracks)
                }
                val populated = targetPlaylist.copy(
                    tracks = tracks,
                    coverUrl = coverUrl ?: targetPlaylist.coverUrl
                )
                _uiState.update { state ->
                    state.copy(playlists = listOf(populated) + state.playlists)
                }
                onCreated?.invoke(populated)
            } catch (e: Exception) {
                android.util.Log.e("LibraryViewModel", "createPlaylistAndAddTracks failed: ${e.message}", e)
            }
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
        val isSpotify = playlist.id.startsWith("sp_") || playlist.tracks.any { it.id.startsWith("sp_") || it.thumbnail.contains("mosaic.scdn.co") || it.thumbnail.contains("image-cdn") }
        _uiState.update { it.copy(isImporting = true, importMessage = if (isSpotify) str(R.string.matching_songs_to_verified_youtube_track) else str(R.string.syncing_x_with_youtube_music, playlist.title)) }
        viewModelScope.launch {
            try {
                if (isSpotify && playlist.tracks.isNotEmpty()) {
                    val enriched = spotifyImporter.enrichTracksWithYouTubeData(
                        tracks = playlist.tracks,
                        onProgress = { progressText ->
                            _uiState.update { it.copy(importMessage = progressText) }
                        }
                    )
                    libraryRepository.replacePlaylistTracks(playlist.id, enriched)
                    val updatedPlaylist = playlist.copy(tracks = enriched)
                    _uiState.update {
                        it.copy(
                            isImporting = false,
                            importMessage = str(R.string.successfully_matched_x_songs_for_x, enriched.size, playlist.title),
                            selectedPlaylist = if (it.selectedPlaylist?.id == playlist.id) updatedPlaylist else it.selectedPlaylist
                        )
                    }
                    onComplete?.invoke(enriched.size)
                    return@launch
                }

                val queryOrId = playlist.id.ifBlank { playlist.title }
                val imported = youtubeImporter.importPlaylist(queryOrId) ?: youtubeImporter.importPlaylist(playlist.title)
                if (imported != null && imported.tracks.isNotEmpty()) {
                    val existingIds = playlist.tracks.map { it.id }.toSet()
                    val newTracks = imported.tracks.filter { it.id !in existingIds }
                    val mergedTracks = playlist.tracks + newTracks
                    val resolvedCover = playlist.coverUrl?.ifBlank { null } ?: imported.coverUrl?.ifBlank { null }
                    if (resolvedCover != null && resolvedCover != playlist.coverUrl) {
                        libraryRepository.updatePlaylist(playlist.id, playlist.title, playlist.description, resolvedCover)
                    }
                    libraryRepository.reorderPlaylist(playlist.id, mergedTracks)
                    val updatedPlaylist = playlist.copy(tracks = mergedTracks, coverUrl = resolvedCover ?: playlist.coverUrl)
                    _uiState.update {
                        it.copy(
                            isImporting = false,
                            importMessage = str(R.string.synced_x_songs_for_x, mergedTracks.size, playlist.title),
                            selectedPlaylist = if (it.selectedPlaylist?.id == playlist.id) updatedPlaylist else it.selectedPlaylist
                        )
                    }
                    onComplete?.invoke(mergedTracks.size)
                } else {
                    _uiState.update {
                        it.copy(
                            isImporting = false,
                            importMessage = str(R.string.x_is_up_to_date, playlist.title)
                        )
                    }
                    onComplete?.invoke(playlist.tracks.size)
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isImporting = false,
                        importMessage = str(R.string.sync_failed_x, e.localizedMessage)
                    )
                }
                onComplete?.invoke(playlist.tracks.size)
            }
        }
    }

    fun reorderPlaylistTracks(playlistId: String, fromIndex: Int, toIndex: Int) {
        if (playlistId.startsWith("smart_")) return
        val currentPlaylist = _uiState.value.selectedPlaylist?.takeIf { it.id == playlistId }
            ?: _uiState.value.playlists.firstOrNull { it.id == playlistId }
            ?: return

        if (fromIndex !in currentPlaylist.tracks.indices || toIndex !in currentPlaylist.tracks.indices || fromIndex == toIndex) {
            return
        }

        val reordered = com.auralis.music.domain.library.PlaylistManager.reorderTracks(currentPlaylist.tracks, fromIndex, toIndex)
        val updatedPlaylist = currentPlaylist.copy(tracks = reordered)

        _uiState.update { state ->
            state.copy(
                selectedPlaylist = if (state.selectedPlaylist?.id == playlistId) updatedPlaylist else state.selectedPlaylist,
                playlists = state.playlists.map { if (it.id == playlistId) updatedPlaylist else it }
            )
        }

        viewModelScope.launch {
            try {
                libraryRepository.reorderPlaylist(playlistId, reordered)
            } catch (e: Exception) {
                android.util.Log.e("LibraryViewModel", "Failed to persist reordered playlist: ${e.message}")
            }
        }
    }

    fun deletePlaylist(playlistId: String) {
        if (playlistId.startsWith("smart_")) return
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
            val wasInDownloaded = _uiState.value.selectedSmartCollection == SmartCollectionType.DOWNLOADED && _uiState.value.selectedPlaylist != null
            _uiState.update {
                it.copy(
                    selectedPlaylist = null,
                    selectedSmartCollection = if (wasInDownloaded) SmartCollectionType.DOWNLOADED else null
                )
            }
            return
        }
        if (initialPlaylist != null && initialPlaylist.id.startsWith("smart_downloaded_")) {
            _uiState.update { it.copy(selectedPlaylist = initialPlaylist, selectedSmartCollection = SmartCollectionType.DOWNLOADED) }
            return
        }
        val cached = initialPlaylist ?: _uiState.value.playlists.find { it.id == playlistId }
        if (cached != null) {
            _uiState.update { it.copy(selectedPlaylist = cached, selectedSmartCollection = null) }
        }
        // Keep the Room Flow subscription for live updates (track adds/removes/reorders),
        // but use distinctUntilChanged to skip redundant emissions that match the cached data.
        selectPlaylistJob = viewModelScope.launch {
            libraryRepository.getPlaylist(playlistId)
                .distinctUntilChanged { old, new ->
                    if (old === new) return@distinctUntilChanged true
                    if (old == null || new == null) return@distinctUntilChanged false
                    old.id == new.id &&
                    old.tracks.size == new.tracks.size &&
                    old.title == new.title &&
                    old.coverUrl == new.coverUrl &&
                    old.tracks.indices.all { old.tracks[it].id == new.tracks[it].id }
                }
                .collectLatest { pl ->
                    // Only update if the data actually differs from current state
                    val current = _uiState.value.selectedPlaylist
                    val tracksEqual = current?.tracks?.size == pl?.tracks?.size &&
                        (current?.tracks == null || pl?.tracks == null || current.tracks.indices.all { current.tracks[it].id == pl.tracks[it].id })
                    if (current?.id != pl?.id ||
                        !tracksEqual ||
                        current?.title != pl?.title ||
                        current?.coverUrl != pl?.coverUrl
                    ) {
                        _uiState.update { it.copy(selectedPlaylist = pl, selectedSmartCollection = null) }
                    }
                }
        }
    }

    fun closeSmartCollection() {
        selectPlaylistJob?.cancel()
        _uiState.update { it.copy(selectedPlaylist = null, selectedSmartCollection = null) }
    }

    fun removePlaylistDownloads(context: android.content.Context, jobId: String) {
        com.auralis.music.data.download.PlaylistDownloadCoordinator.removePlaylistDownloads(context, jobId)
        if (_uiState.value.selectedPlaylist?.id?.contains(jobId) == true) {
            selectPlaylist(null)
        }
    }

    fun retryPlaylistDownload(context: android.content.Context, jobId: String) {
        com.auralis.music.data.download.PlaylistDownloadCoordinator.retry(context, jobId)
    }

    fun addTrackToPlaylist(playlistId: String, track: Track) {
        viewModelScope.launch {
            val added = libraryRepository.addTrackToPlaylist(playlistId, track)
            val playlistTitle = _uiState.value.playlists.firstOrNull { it.id == playlistId }?.title
            if (added) {
                _uiState.update { state ->
                    val updatedPlaylists = state.playlists.map { pl ->
                        if (pl.id == playlistId && pl.tracks.none { it.id == track.id }) {
                            pl.copy(tracks = pl.tracks + track)
                        } else pl
                    }
                    val updatedSelected = if (state.selectedPlaylist?.id == playlistId && state.selectedPlaylist.tracks.none { it.id == track.id }) {
                        state.selectedPlaylist.copy(tracks = state.selectedPlaylist.tracks + track)
                    } else state.selectedPlaylist
                    state.copy(playlists = updatedPlaylists, selectedPlaylist = updatedSelected)
                }
                val msg = if (!playlistTitle.isNullOrBlank()) str(R.string.added_to_x, playlistTitle) else str(R.string.added_to_playlist)
                com.auralis.music.ui.components.AppPillManager.showPill(msg)
            } else {
                val msg = if (!playlistTitle.isNullOrBlank()) str(R.string.already_in_x, playlistTitle) else str(R.string.already_in_this_playlist)
                com.auralis.music.ui.components.AppPillManager.showPill(msg)
            }
        }
    }

    fun addTracksToPlaylist(playlistId: String, tracks: List<Track>) {
        viewModelScope.launch {
            var addedCount = 0
            val newTracks = mutableListOf<Track>()
            tracks.forEach { track ->
                if (libraryRepository.addTrackToPlaylist(playlistId, track)) {
                    addedCount++
                    newTracks.add(track)
                }
            }
            if (addedCount > 0) {
                _uiState.update { state ->
                    val updatedPlaylists = state.playlists.map { pl ->
                        if (pl.id == playlistId) {
                            pl.copy(tracks = pl.tracks + newTracks)
                        } else pl
                    }
                    val updatedSelected = if (state.selectedPlaylist?.id == playlistId) {
                        state.selectedPlaylist.copy(tracks = state.selectedPlaylist.tracks + newTracks)
                    } else state.selectedPlaylist
                    state.copy(playlists = updatedPlaylists, selectedPlaylist = updatedSelected)
                }
                com.auralis.music.ui.components.AppPillManager.showPill(str(R.string.added_x_tracks, addedCount))
            } else {
                com.auralis.music.ui.components.AppPillManager.showPill(str(R.string.tracks_already_in_playlist))
            }
        }
    }

    fun removeTrackFromPlaylist(playlistId: String, trackId: String) {
        _uiState.update { state ->
            val updatedSelected = if (state.selectedPlaylist?.id == playlistId) {
                state.selectedPlaylist.copy(tracks = state.selectedPlaylist.tracks.filter { it.id != trackId })
            } else state.selectedPlaylist
            val updatedPlaylists = state.playlists.map { pl ->
                if (pl.id == playlistId) {
                    pl.copy(tracks = pl.tracks.filter { it.id != trackId })
                } else pl
            }
            val updatedFavorites = if (playlistId == "smart_favorites") {
                state.favorites.filter { it.id != trackId }
            } else state.favorites
            state.copy(
                selectedPlaylist = updatedSelected,
                playlists = updatedPlaylists,
                favorites = updatedFavorites
            )
        }
        viewModelScope.launch {
            if (playlistId == "smart_downloaded" || playlistId == "smart_downloaded_all" || playlistId.startsWith("smart_downloaded_folder_")) {
                com.auralis.music.data.download.AuralisDownloadManager.removeDownload(trackId)
            } else if (playlistId == "smart_favorites") {
                val track = _uiState.value.favorites.find { it.id == trackId }
                    ?: libraryRepository.getFavoriteTracks().firstOrNull()?.find { it.id == trackId }
                if (track != null) {
                    libraryRepository.setFavorite(track, false)
                }
            } else {
                libraryRepository.removeTrackFromPlaylist(playlistId, trackId)
            }
        }
    }

    fun importYouTubePlaylist(urlOrId: String) {
        if (urlOrId.isBlank()) return
        _uiState.update { it.copy(isImporting = true, importMessage = null) }

        viewModelScope.launch {
            try {
                val imported = youtubeImporter.importPlaylist(urlOrId)
                if (imported != null) {
                    val resolvedCover = imported.coverUrl?.ifBlank { null }
                    val playlist = libraryRepository.upsertImportedPlaylist(
                        source = "youtube_music",
                        remoteId = imported.id,
                        title = imported.title,
                        description = imported.description,
                        coverUrl = resolvedCover
                    )
                    libraryRepository.replacePlaylistTracks(playlist.id, imported.tracks)
                    _uiState.update {
                        it.copy(
                            isImporting = false,
                            importMessage = str(R.string.imported_x_x_songs, imported.title, imported.tracks.size)
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            isImporting = false,
                            importMessage = "Couldn't read this YouTube Music playlist. Check the link and try again."
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isImporting = false,
                        importMessage = e.localizedMessage ?: str(R.string.failed_to_import_playlist)
                    )
                }
            }
        }
    }

    /**
     * Imports playlists picked from the signed-in YouTube Music library, one after another.
     * Liked Music goes into liked songs. Reimporting the same remote playlist refreshes it.
     */
    fun importYouTubeLibraryPlaylists(selected: List<com.auralis.music.data.network.YouTubeMusicLibrary.LibraryPlaylist>) {
        if (selected.isEmpty()) return
        _uiState.update { it.copy(isImporting = true, importMessage = null) }
        viewModelScope.launch {
            var imported = 0
            var failed = 0
            for ((index, item) in selected.withIndex()) {
                _uiState.update { it.copy(importMessage = str(R.string.importing_x_of_x_x, index + 1, selected.size, item.title)) }
                try {
                    val remote = youtubeImporter.importPlaylistById(item.id)
                    if (remote == null) {
                        failed++
                        continue
                    }
                    if (item.isLikedMusic) {
                        remote.tracks.forEach { libraryRepository.setFavorite(it, true) }
                    } else {
                        val cover = remote.coverUrl?.ifBlank { null } ?: item.thumbnail?.ifBlank { null }
                        val playlist = libraryRepository.upsertImportedPlaylist(
                            source = "youtube_music",
                            remoteId = remote.id,
                            title = remote.title,
                            description = remote.description,
                            coverUrl = cover
                        )
                        libraryRepository.replacePlaylistTracks(playlist.id, remote.tracks)
                    }
                    imported++
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failed++
                }
            }
            val noun = if (imported == 1) "playlist" else "playlists"
            _uiState.update {
                it.copy(
                    isImporting = false,
                    importMessage = if (failed == 0) str(R.string.imported_x_x, imported, noun) else str(R.string.imported_x_x_x_couldn_t_be_read, imported, noun, failed)
                )
            }
        }
    }

    fun clearYouTubeImportMessage() {
        _uiState.update { it.copy(importMessage = null) }
    }

    /** Imports only Spotify playlists that do not have a local imported ID yet. */
    fun importSpotifyLibraryPlaylists(selected: List<com.auralis.music.data.network.SpotifyLibrary.LibraryPlaylist>) {
        if (_uiState.value.isImportingSpotify) return
        _uiState.update { it.copy(isImportingSpotify = true, spotifyImportMessage = null) }
        viewModelScope.launch(Dispatchers.IO) {
            val existingIds = libraryRepository.getPlaylists().firstOrNull().orEmpty().map { it.id }.toSet()
            val likedSongsKey = "spotify_liked_songs_imported:${com.auralis.music.data.network.SpotifySession.userId}"
            val hasImportedLikedSongs = matchReviewPrefs?.getBoolean(likedSongsKey, false) == true ||
                libraryRepository.getFavoriteTracks().firstOrNull().orEmpty().any { it.id.startsWith("sp_") }
            if (hasImportedLikedSongs) matchReviewPrefs?.edit()?.putBoolean(likedSongsKey, true)?.apply()
            val toImport = selected.filter { item ->
                if (item.isLikedSongs) !hasImportedLikedSongs
                else "imported:spotify:${item.id}" !in existingIds
            }
            if (toImport.isEmpty()) {
                _uiState.update { it.copy(isImportingSpotify = false, spotifyImportMessage = "All Spotify playlists are already imported.") }
                return@launch
            }
            var imported = 0
            var failed = 0
            val failedTitles = mutableListOf<String>()
            for ((index, item) in toImport.withIndex()) {
                _uiState.update { it.copy(spotifyImportMessage = str(R.string.importing_x_of_x_x, index + 1, toImport.size, item.title)) }
                try {
                    if (item.isLikedSongs) {
                        var tracks = emptyList<Track>()
                        repeat(2) { attempt ->
                            if (tracks.isEmpty()) {
                                if (attempt > 0) kotlinx.coroutines.delay(500)
                                tracks = com.auralis.music.data.network.SpotifyLibrary.fetchLikedSongsTracks(
                                    importer = spotifyImporter,
                                    onProgress = { progressText ->
                                        _uiState.update { it.copy(spotifyImportMessage = progressText) }
                                    },
                                    matchTracks = false
                                )
                            }
                        }
                        if (tracks.isNotEmpty()) {
                            tracks.forEach { libraryRepository.setFavorite(it, true) }
                            matchReviewPrefs?.edit()?.putBoolean(likedSongsKey, true)?.apply()
                            imported++
                        } else {
                            failed++
                            failedTitles.add(item.title)
                        }
                    } else {
                        var remote: Playlist? = null
                        repeat(2) { attempt ->
                            if (remote == null) {
                                if (attempt > 0) kotlinx.coroutines.delay(500)
                                remote = com.auralis.music.data.network.SpotifyLibrary.fetchPlaylist(
                                    playlistId = item.id,
                                    importer = spotifyImporter,
                                    onProgress = { progressText ->
                                        _uiState.update { it.copy(spotifyImportMessage = progressText) }
                                    },
                                    matchTracks = false
                                )
                            }
                        }
                        if (remote == null) {
                            failed++
                            failedTitles.add(item.title)
                            continue
                        }
                        val importedPlaylist = remote ?: continue
                        val cover = importedPlaylist.coverUrl?.ifBlank { null } ?: item.thumbnail?.ifBlank { null }
                        val playlist = libraryRepository.upsertImportedPlaylist(
                            source = "spotify",
                            remoteId = importedPlaylist.id.removePrefix("sp_"),
                            title = importedPlaylist.title,
                            description = importedPlaylist.description,
                            coverUrl = cover
                        )
                        libraryRepository.replacePlaylistTracks(playlist.id, importedPlaylist.tracks)
                        imported++
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    android.util.Log.e("SpotifyLibrary", "Failed to import ${item.title}: ${e.message}")
                    failed++
                    failedTitles.add(item.title)
                }
            }
            val noun = if (imported == 1) "playlist" else "playlists"
            _uiState.update {
                it.copy(
                    isImportingSpotify = false,
                    spotifyImportMessage = if (failed == 0) str(R.string.imported_x_x, imported, noun)
                    else "Imported $imported/${toImport.size} playlists. Failed: ${failedTitles.take(3).joinToString()}${if (failedTitles.size > 3) " (+${failedTitles.size - 3} more)" else ""}. Tap Import all to retry."
                )
            }
        }
    }

    fun clearSpotifyImportMessage() {
        _uiState.update { it.copy(spotifyImportMessage = null) }
    }

    fun importSpotifyPlaylist(urlOrLink: String, onComplete: ((Boolean, String) -> Unit)? = null) {
        val trimmed = urlOrLink.trim()
        if (trimmed.isBlank()) return
        android.util.Log.i("SpotifyImporter", "importSpotifyPlaylist called in ViewModel with: '$trimmed'")
        _uiState.update { it.copy(isImportingSpotify = true, spotifyImportMessage = str(R.string.connecting_to_spotify)) }

        viewModelScope.launch {
            try {
                val imported = spotifyImporter.importPlaylist(
                    urlOrId = trimmed,
                    onProgress = { progressText ->
                        _uiState.update { it.copy(spotifyImportMessage = progressText) }
                    }
                )
                if (imported != null && (imported.tracks.isNotEmpty() || imported.title.isNotBlank())) {
                    // 1. Immediately create the playlist in Room DB so user has 0ms wait time
                    val playlist = libraryRepository.upsertImportedPlaylist(
                        source = "spotify",
                        remoteId = imported.id.removePrefix("sp_"),
                        title = imported.title,
                        description = imported.description,
                        coverUrl = imported.coverUrl
                    )
                    libraryRepository.replacePlaylistTracks(playlist.id, imported.tracks)
                    val successMsg = str(R.string.imported_x_x_songs, imported.title, imported.tracks.size)
                    android.util.Log.i("SpotifyImporter", successMsg)
                    _uiState.update {
                        it.copy(
                            isImportingSpotify = false,
                            spotifyImportMessage = successMsg
                        )
                    }
                    onComplete?.invoke(true, successMsg)

                    // 2. High-speed parallel audio matching in the background
                    if (imported.tracks.isNotEmpty()) {
                        launch(Dispatchers.IO) {
                            try {
                                val enrichedTracks = spotifyImporter.enrichTracksWithYouTubeData(
                                    tracks = imported.tracks,
                                    onProgress = { progressText ->
                                        _uiState.update { it.copy(spotifyImportMessage = progressText) }
                                    }
                                )
                                libraryRepository.replacePlaylistTracks(playlist.id, enrichedTracks)
                                _uiState.update {
                                    it.copy(spotifyImportMessage = str(R.string.all_x_songs_in_x_matched_with_official_a, enrichedTracks.size, imported.title))
                                }
                            } catch (e: Exception) {
                                android.util.Log.e("SpotifyImporter", "Background enrichment failed: ${e.message}")
                            }
                        }
                    }
                } else {
                    val errorMsg = str(R.string.could_not_parse_spotify_playlist_please)
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
                val errorMsg = e.localizedMessage ?: str(R.string.failed_to_import_spotify_playlist)
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

private const val RECORDING_REVIEW_KEY = "recording_review_version"
private const val RECORDING_REVIEW_VERSION = 3

/** Weekly / Monthly Most playlists hold every song played in the window (as Metrolist), capped for safety. */
private const val MOST_PLAYLIST_SIZE = 500
