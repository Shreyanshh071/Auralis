package com.auralis.music.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.auralis.music.domain.model.Artist
import com.auralis.music.domain.model.ArtistPage
import com.auralis.music.domain.model.SearchResults
import com.auralis.music.domain.model.SearchTopResult
import com.auralis.music.domain.search.SearchQueryMatcher
import com.auralis.music.domain.model.Track
import com.auralis.music.domain.recognition.AudioRecognitionManager
import com.auralis.music.domain.recognition.RecognitionHistoryItem
import com.auralis.music.domain.recognition.RecognitionMode
import com.auralis.music.domain.recognition.RecognitionState
import com.auralis.music.domain.repository.SearchRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface ExploreDetail {
    val key: String
    data class Artist(
        val artistPage: ArtistPage,
        val isLoading: Boolean = false,
        val loadFailed: Boolean = false,
        val stableKey: String = "artist:${artistPage.artist.id.ifBlank { artistPage.artist.name }}"
    ) : ExploreDetail {
        override val key: String get() = stableKey
    }
    data class Album(
        val album: com.auralis.music.domain.model.PlaylistResult,
        val tracks: List<Track> = emptyList(),
        val isLoading: Boolean = false,
        val stableKey: String = "album:${album.id.ifBlank { album.title }}"
    ) : ExploreDetail {
        override val key: String get() = stableKey
    }
}

data class SearchUiState(
    val query: String = "",
    val suggestions: List<String> = emptyList(),
    val liveSongRecommendations: List<Track> = emptyList(),
    val searchResults: SearchResults = SearchResults(),
    val recentQueries: List<String> = emptyList(),
    val isSearching: Boolean = false,
    val hasSubmittedSearch: Boolean = false,
    val searchFailed: Boolean = false,
    val isRecognitionOpen: Boolean = false,
    val detailStack: List<ExploreDetail> = emptyList(),
    val selectedArtistPage: ArtistPage? = null,
    val isLoadingArtist: Boolean = false,
    val selectedAlbum: com.auralis.music.domain.model.PlaylistResult? = null,
    val selectedAlbumTracks: List<Track> = emptyList(),
    val isLoadingAlbum: Boolean = false
)

class SearchViewModel(
    private val searchRepository: SearchRepository,
    private val context: Context? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    val recognitionManager: AudioRecognitionManager? = context?.let {
        AudioRecognitionManager(it, searchRepository, viewModelScope)
    }

    val recognitionState: StateFlow<RecognitionState> =
        recognitionManager?.state ?: MutableStateFlow(RecognitionState()).asStateFlow()

    val recognitionHistory: StateFlow<List<RecognitionHistoryItem>> =
        recognitionManager?.historyFlow?.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            emptyList()
        ) ?: MutableStateFlow<List<RecognitionHistoryItem>>(emptyList()).asStateFlow()

    fun clearRecognitionHistory() {
        recognitionManager?.clearHistory()
    }

    fun clearSearchHistory() {
        viewModelScope.launch {
            searchRepository.clearSearchHistory()
            _uiState.update { it.copy(recentQueries = emptyList(), suggestions = emptyList(), liveSongRecommendations = emptyList()) }
        }
    }

    fun removeRecognitionHistoryItem(trackId: String) {
        recognitionManager?.removeHistoryItem(trackId)
    }

    private var searchJob: Job? = null
    private var suggestionsJob: Job? = null
    private var liveSongsJob: Job? = null
    private val liveSongCache = linkedMapOf<String, List<Track>>()
    private val artistPages = java.util.concurrent.ConcurrentHashMap<String, ArtistPage>()
    private val artistRequests = java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Deferred<ArtistPage?>>()

    private fun artistPageRequest(artist: Artist): kotlinx.coroutines.Deferred<ArtistPage?> {
        val key = artist.name.lowercase()
        artistRequests[key]?.let { return it }
        val request = viewModelScope.async(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            val page = try {
                withTimeout(12_000L) {
                    com.auralis.music.data.network.LocalizedContent.run { searchRepository.getArtistPage(artist) }
                }
            } catch (e: Exception) {
                if (e is CancellationException && e !is TimeoutCancellationException) throw e
                null
            }
            if (page != null && (page.topSongs.isNotEmpty() || page.albums.isNotEmpty() || page.singles.isNotEmpty())) {
                if (artistPages.size >= 24) artistPages.remove(artistPages.keys.first())
                artistPages[key] = page
            }
            page
        }
        artistRequests[key] = request
        request.invokeOnCompletion { artistRequests.remove(key, request) }
        request.start()
        return request
    }

    private fun preloadSearchArtists(results: SearchResults) {
        val credits = com.auralis.music.domain.recommendations.SimilarSeedPlanner
            .splitArtistCredit(results.songs.firstOrNull()?.artist)
        val artists = listOfNotNull(results.primaryArtist).filter {
            com.auralis.music.domain.recommendations.SimilarSeedPlanner.splitArtistCredit(it.name).size == 1
        } + credits.map { name ->
            results.artists.firstOrNull { it.name.equals(name, ignoreCase = true) }
                ?: Artist(id = "yt:$name", name = name)
        } + results.artists
        artists.distinctBy { it.name.lowercase() }.take(2).forEach { artist ->
            if (artistPages[artist.name.lowercase()] == null) artistPageRequest(artist)
        }
    }

    fun onQueryChange(newQuery: String) {
        suggestionsJob?.cancel()
        liveSongsJob?.cancel()
        searchJob?.cancel()

        if (newQuery.isBlank()) {
            _uiState.update {
                it.copy(
                    query = newQuery,
                    suggestions = emptyList(),
                    liveSongRecommendations = emptyList(),
                    searchResults = SearchResults(),
                    isSearching = false,
                    hasSubmittedSearch = false
                )
            }
            return
        }

        val trimmed = newQuery.trim()
        val cacheKey = trimmed.lowercase()
        _uiState.update {
            val reusable = SearchQueryMatcher.partitionResults(it.liveSongRecommendations, trimmed).first
            it.copy(
                query = newQuery,
                isSearching = false,
                searchFailed = false,
                hasSubmittedSearch = false,
                suggestions = emptyList(),
                liveSongRecommendations = liveSongCache[cacheKey] ?: reusable.take(8),
                searchResults = SearchResults()
            )
        }

        // 1. Fast text autocomplete suggestions (top 3)
        suggestionsJob = viewModelScope.launch {
            delay(120)
            val suggestions = try {
                searchRepository.getSuggestions(trimmed).take(3)
            } catch (_: Exception) {
                emptyList()
            }
            if (isActive) {
                _uiState.update { it.copy(suggestions = suggestions) }
            }
        }

        // 2. Direct song recommendations (ranked by popularity & views)
        liveSongsJob = viewModelScope.launch {
            // Let a short burst of typing settle, then show either provider as soon as it arrives.
            delay(120)
            try {
                searchRepository.searchLiveSongs(trimmed) { songs ->
                    if (isActive && _uiState.value.query.trim() == trimmed && songs.isNotEmpty()) {
                        val visible = songs.take(8)
                        if (liveSongCache.size >= 24) liveSongCache.remove(liveSongCache.keys.first())
                        liveSongCache[cacheKey] = visible
                        _uiState.update { it.copy(liveSongRecommendations = visible) }
                    }
                }
                if (isActive) preloadSearchArtists(SearchResults(songs = _uiState.value.liveSongRecommendations.take(1)))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Keep already received songs if the optional second request fails.
            }
        }
    }

    init {
        viewModelScope.launch {
            // Purge any residual bot/background queries from previous runs
            val botQueries = listOf(
                "Global Top Music Charts",
                "Trending community playlists",
                "Trending Hits 2026",
                "YouTube Music Playlists",
                "Global Top Liked Songs"
            )
            botQueries.forEach { searchRepository.removeSearchQuery(it) }

            searchRepository.getRecentSearchQueries().collect { queries ->
                val userOnlyQueries = queries.filterNot { q -> botQueries.any { b -> b.equals(q, ignoreCase = true) } }
                _uiState.update { it.copy(recentQueries = userOnlyQueries) }
            }
        }
    }

    fun openRecognitionModal(mode: RecognitionMode = RecognitionMode.VOICE_SEARCH) {
        recognitionManager?.setMode(mode)
        recognitionManager?.startListening()
        _uiState.update { it.copy(isRecognitionOpen = true) }
    }

    fun closeRecognitionModal() {
        recognitionManager?.stopListening()
        _uiState.update { it.copy(isRecognitionOpen = false) }
    }

    fun setRecognitionMode(mode: RecognitionMode) {
        recognitionManager?.setMode(mode)
        recognitionManager?.startListening()
    }

    fun startListening() {
        recognitionManager?.startListening()
    }

    fun stopListening() {
        recognitionManager?.stopListening()
    }

    fun performSearch(query: String) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return

        suggestionsJob?.cancel()
        liveSongsJob?.cancel()
        searchJob?.cancel()

        val cachedSongs = liveSongCache[trimmed.lowercase()]
            ?: _uiState.value.liveSongRecommendations.takeIf { _uiState.value.query.trim().equals(trimmed, ignoreCase = true) }
            ?: emptyList()
        val immediate = com.auralis.music.data.repository.searchPreview(trimmed, listOf(SearchResults(songs = cachedSongs)))
        _uiState.update {
            it.copy(
                query = trimmed,
                isSearching = immediate.isEmpty(),
                searchFailed = false,
                hasSubmittedSearch = true,
                suggestions = emptyList(),
                liveSongRecommendations = emptyList(),
                searchResults = immediate,
                detailStack = emptyList(),
                selectedArtistPage = null,
                selectedAlbum = null
            )
        }

        searchJob = viewModelScope.launch {
            val isPaused = context?.let { ctx ->
                com.auralis.music.data.datastore.PrivacyDataStore(ctx).settingsFlow.first().pauseSearchHistory
            } ?: false
            if (!isPaused) launch {
                try { searchRepository.recordSearchQuery(trimmed) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { }
            }
            val results = try {
                searchRepository.search(trimmed) { received ->
                    if (isActive && _uiState.value.hasSubmittedSearch && trimmed.equals(_uiState.value.query.trim(), ignoreCase = true)) {
                        _uiState.update { it.copy(searchResults = received, isSearching = false, searchFailed = false) }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (isActive && trimmed.equals(_uiState.value.query.trim(), ignoreCase = true)) {
                    _uiState.update { it.copy(isSearching = false, searchFailed = true) }
                }
                return@launch
            }
            if (isActive && trimmed.equals(_uiState.value.query.trim(), ignoreCase = true)) {
                _uiState.update { it.copy(searchResults = results, isSearching = false, hasSubmittedSearch = true,
                    searchFailed = results.requestFailed) }
                preloadSearchArtists(results)
            }
        }
    }

    /**
     * Pull-to-refresh: reloads what is on screen — the open artist page, the open album, or the
     * current search results — in place, without resetting scroll or the detail stack.
     */
    suspend fun refresh() {
        val state = _uiState.value
        when (val top = state.detailStack.lastOrNull()) {
            is ExploreDetail.Artist -> {
                val loaded = try {
                    withTimeout(12_000L) { com.auralis.music.data.network.LocalizedContent.run {
                        searchRepository.getArtistPage(top.artistPage.artist)
                    } }
                } catch (e: Exception) {
                    if (e is CancellationException && e !is TimeoutCancellationException) throw e
                    null
                }
                val page = loaded?.let {
                    if (it.topSongs.isEmpty()) it.copy(topSongs = top.artistPage.topSongs) else it
                } ?: top.artistPage
                _uiState.update { cur ->
                    val stack = cur.detailStack.map { if (it === top) top.copy(artistPage = page, isLoading = false,
                        loadFailed = page.topSongs.isEmpty()) else it }
                    cur.copy(
                        detailStack = stack,
                        selectedArtistPage = if (stack.lastOrNull() is ExploreDetail.Artist) page else cur.selectedArtistPage
                    )
                }
            }
            is ExploreDetail.Album -> {
                val tracks = com.auralis.music.data.network.LocalizedContent.run {
                    searchRepository.getAlbumTracks(top.album)
                }
                if (tracks.isEmpty()) return
                _uiState.update { cur ->
                    val updated = top.copy(tracks = tracks, isLoading = false)
                    val stack = cur.detailStack.map { if (it === top) updated else it }
                    cur.copy(
                        detailStack = stack,
                        selectedAlbumTracks = if (stack.lastOrNull() === updated) tracks else cur.selectedAlbumTracks
                    )
                }
            }
            else -> {
                val query = state.query.trim()
                if (!state.hasSubmittedSearch || query.isBlank()) return
                val results = try {
                    withTimeout(12_000L) {
                        com.auralis.music.data.network.LocalizedContent.run { searchRepository.search(query) }
                    }
                } catch (e: Exception) {
                    if (e is CancellationException && e !is TimeoutCancellationException) throw e
                    _uiState.update { cur ->
                        if (cur.query.trim().equals(query, ignoreCase = true)) cur.copy(searchFailed = true) else cur
                    }
                    return
                }
                _uiState.update { cur ->
                    if (cur.query.trim().equals(query, ignoreCase = true)) cur.copy(searchResults = results, searchFailed = false) else cur
                }
            }
        }
    }

    fun clearSearch() {
        suggestionsJob?.cancel()
        liveSongsJob?.cancel()
        searchJob?.cancel()
        _uiState.update {
            it.copy(
                query = "",
                suggestions = emptyList(),
                liveSongRecommendations = emptyList(),
                searchResults = SearchResults(),
                isSearching = false,
                hasSubmittedSearch = false,
                searchFailed = false,
                detailStack = emptyList(),
                selectedArtistPage = null,
                isLoadingArtist = false,
                selectedAlbum = null,
                selectedAlbumTracks = emptyList(),
                isLoadingAlbum = false
            )
        }
    }

    fun removeRecentQuery(query: String) {
        viewModelScope.launch {
            searchRepository.removeSearchQuery(query)
        }
    }

    fun openArtist(artist: Artist, resetStack: Boolean = false) {
        val isKanye = artist.name.equals("Kanye West", ignoreCase = true) || artist.name.equals("Ye", ignoreCase = true)
        val defaultKanyeThumb = "https://upload.wikimedia.org/wikipedia/commons/thumb/5/5c/Kanye_West_at_the_2009_Tribeca_Film_Festival_%28crop_2%29.jpg/1280px-Kanye_West_at_the_2009_Tribeca_Film_Festival_%28crop_2%29.jpg?utm_source=en.wikipedia.org&utm_campaign=api&utm_content=thumbnail"
        val verifiedBanner = when {
            isKanye -> defaultKanyeThumb
            artist.id.startsWith("UC") && !artist.thumbnail.isNullOrBlank() && !artist.thumbnail!!.contains("i.ytimg.com") && !artist.thumbnail!!.contains("IFlc3sf6sHV3TAZ_5vhyHQiKb9D4AdSlDkiTSgsRiicnzLASXwVr1n22EEg6Vtd2XBlyJslm8xlYiA") -> artist.thumbnail
            else -> null
        }
        val cachedPage = artistPages[artist.name.lowercase()]
        val initialPage = cachedPage ?: ArtistPage(
            artist = artist.copy(thumbnail = verifiedBanner ?: artist.thumbnail), bannerUrl = verifiedBanner)
        val newEntry = ExploreDetail.Artist(artistPage = initialPage, isLoading = cachedPage == null)

        _uiState.update { current ->
            val updatedStack = if (resetStack) listOf(newEntry) else current.detailStack + newEntry
            current.copy(
                detailStack = updatedStack,
                selectedArtistPage = initialPage,
                isLoadingArtist = cachedPage == null,
                selectedAlbum = if (resetStack) null else current.selectedAlbum,
                selectedAlbumTracks = if (resetStack) emptyList() else current.selectedAlbumTracks,
                isLoadingAlbum = if (resetStack) false else current.isLoadingAlbum
            )
        }

        if (cachedPage != null) return
        val request = artistPageRequest(artist)
        viewModelScope.launch {
            val page = request.await() ?: initialPage
            _uiState.update { current ->
                val updatedStack = current.detailStack.map { detail ->
                    if (detail is ExploreDetail.Artist && (detail.artistPage.artist.id == artist.id || detail.artistPage.artist.name.equals(artist.name, ignoreCase = true))) {
                        detail.copy(artistPage = page, isLoading = false, loadFailed = page.topSongs.isEmpty())
                    } else {
                        detail
                    }
                }
                val topArtist = updatedStack.lastOrNull() as? ExploreDetail.Artist
                current.copy(
                    detailStack = updatedStack,
                    selectedArtistPage = topArtist?.artistPage ?: if (updatedStack.isEmpty()) null else current.selectedArtistPage,
                    isLoadingArtist = topArtist?.isLoading ?: false
                )
            }
        }
    }

    fun openAlbum(album: com.auralis.music.domain.model.PlaylistResult, resetStack: Boolean = false) {
        val initialTracks = if (album.id.startsWith("artist_top_songs:")) {
            _uiState.value.selectedArtistPage?.topSongs ?: emptyList()
        } else {
            emptyList()
        }
        val newEntry = ExploreDetail.Album(album = album, tracks = initialTracks, isLoading = true)

        _uiState.update { current ->
            val updatedStack = if (resetStack) listOf(newEntry) else current.detailStack + newEntry
            current.copy(
                detailStack = updatedStack,
                selectedAlbum = album,
                selectedAlbumTracks = initialTracks,
                isLoadingAlbum = true,
                selectedArtistPage = if (resetStack) null else current.selectedArtistPage,
                isLoadingArtist = if (resetStack) false else current.isLoadingArtist
            )
        }

        viewModelScope.launch {
            val tracks = com.auralis.music.data.network.LocalizedContent.run { searchRepository.getAlbumTracks(album) }
            val finalTracks = if (tracks.isNotEmpty()) tracks else initialTracks
            _uiState.update { current ->
                val updatedStack = current.detailStack.map { detail ->
                    if (detail is ExploreDetail.Album && (detail.album.id == album.id || detail.album.title.equals(album.title, ignoreCase = true))) {
                        val firstTrk = finalTracks.firstOrNull()
                        val enrichedAlbum = if (firstTrk != null && (detail.album.thumbnail.isNullOrBlank() || detail.album.thumbnail!!.contains("default"))) {
                            detail.album.copy(
                                thumbnail = firstTrk.thumbnail.takeIf { it.isNotBlank() } ?: detail.album.thumbnail
                            )
                        } else {
                            detail.album
                        }
                        detail.copy(album = enrichedAlbum, tracks = finalTracks, isLoading = false)
                    } else {
                        detail
                    }
                }
                val topAlbum = updatedStack.lastOrNull() as? ExploreDetail.Album
                current.copy(
                    detailStack = updatedStack,
                    selectedAlbum = topAlbum?.album ?: if (updatedStack.isEmpty()) null else current.selectedAlbum,
                    selectedAlbumTracks = topAlbum?.tracks ?: if (updatedStack.isEmpty()) emptyList() else current.selectedAlbumTracks,
                    isLoadingAlbum = topAlbum?.isLoading ?: false
                )
            }
        }
    }

    suspend fun getAlbumTracks(album: com.auralis.music.domain.model.PlaylistResult): List<Track> {
        return com.auralis.music.data.network.LocalizedContent.run { searchRepository.getAlbumTracks(album) }
    }

    fun popDetail(): Boolean {
        var popped = false
        _uiState.update { current ->
            if (current.detailStack.isNotEmpty()) {
                popped = true
                val updatedStack = current.detailStack.dropLast(1)
                val topDetail = updatedStack.lastOrNull()
                val topArtist = topDetail as? ExploreDetail.Artist
                val topAlbum = topDetail as? ExploreDetail.Album
                current.copy(
                    detailStack = updatedStack,
                    selectedArtistPage = topArtist?.artistPage,
                    isLoadingArtist = topArtist?.isLoading ?: false,
                    selectedAlbum = topAlbum?.album,
                    selectedAlbumTracks = topAlbum?.tracks ?: emptyList(),
                    isLoadingAlbum = topAlbum?.isLoading ?: false
                )
            } else {
                current.copy(
                    detailStack = emptyList(),
                    selectedArtistPage = null,
                    isLoadingArtist = false,
                    selectedAlbum = null,
                    selectedAlbumTracks = emptyList(),
                    isLoadingAlbum = false
                )
            }
        }
        return popped
    }

    fun closeArtist() {
        popDetail()
    }

    fun closeAlbum() {
        popDetail()
    }
}
