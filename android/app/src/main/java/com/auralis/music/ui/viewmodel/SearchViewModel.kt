package com.auralis.music.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.auralis.music.domain.model.Artist
import com.auralis.music.domain.model.ArtistPage
import com.auralis.music.domain.model.SearchResults
import com.auralis.music.domain.model.Track
import com.auralis.music.domain.recognition.AudioRecognitionManager
import com.auralis.music.domain.recognition.RecognitionHistoryItem
import com.auralis.music.domain.recognition.RecognitionMode
import com.auralis.music.domain.recognition.RecognitionState
import com.auralis.music.domain.repository.SearchRepository
import kotlinx.coroutines.Job
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
    data class Artist(val artistPage: ArtistPage, val isLoading: Boolean = false) : ExploreDetail
    data class Album(val album: com.auralis.music.domain.model.PlaylistResult, val tracks: List<Track> = emptyList(), val isLoading: Boolean = false) : ExploreDetail
}

data class SearchUiState(
    val query: String = "",
    val suggestions: List<String> = emptyList(),
    val liveSongRecommendations: List<Track> = emptyList(),
    val searchResults: SearchResults = SearchResults(),
    val recentQueries: List<String> = emptyList(),
    val isSearching: Boolean = false,
    val hasSubmittedSearch: Boolean = false,
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

    fun onQueryChange(newQuery: String) {
        _uiState.update { it.copy(query = newQuery, hasSubmittedSearch = false) }

        suggestionsJob?.cancel()
        liveSongsJob?.cancel()

        if (newQuery.isBlank()) {
            _uiState.update {
                it.copy(
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

        // 1. Fast text autocomplete suggestions (top 3)
        suggestionsJob = viewModelScope.launch {
            delay(20)
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
            delay(30)
            val songs = try {
                searchRepository.searchSongs(trimmed)
            } catch (_: Exception) {
                emptyList()
            }
            if (isActive) {
                _uiState.update { it.copy(liveSongRecommendations = songs.take(8)) }
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

        _uiState.update { it.copy(query = trimmed, isSearching = true, hasSubmittedSearch = true, suggestions = emptyList(), detailStack = emptyList(), selectedArtistPage = null, selectedAlbum = null) }

        viewModelScope.launch {
            val isPaused = context?.let { ctx ->
                com.auralis.music.data.datastore.PrivacyDataStore(ctx).settingsFlow.first().pauseSearchHistory
            } ?: false
            if (!isPaused) {
                searchRepository.recordSearchQuery(trimmed)
            }
            val results = searchRepository.search(trimmed)
            _uiState.update { it.copy(searchResults = results, isSearching = false, hasSubmittedSearch = true) }
        }
    }

    fun clearSearch() {
        suggestionsJob?.cancel()
        liveSongsJob?.cancel()
        _uiState.update {
            it.copy(
                query = "",
                suggestions = emptyList(),
                liveSongRecommendations = emptyList(),
                searchResults = SearchResults(),
                isSearching = false,
                hasSubmittedSearch = false,
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

    fun openArtist(artist: Artist) {
        val isKanye = artist.name.equals("Kanye West", ignoreCase = true) || artist.name.equals("Ye", ignoreCase = true)
        val defaultKanyeThumb = "https://upload.wikimedia.org/wikipedia/commons/thumb/5/5c/Kanye_West_at_the_2009_Tribeca_Film_Festival_%28crop_2%29.jpg/1280px-Kanye_West_at_the_2009_Tribeca_Film_Festival_%28crop_2%29.jpg?utm_source=en.wikipedia.org&utm_campaign=api&utm_content=thumbnail"
        val verifiedBanner = when {
            isKanye -> defaultKanyeThumb
            artist.id.startsWith("UC") && !artist.thumbnail.isNullOrBlank() && !artist.thumbnail.contains("i.ytimg.com") && !artist.thumbnail.contains("IFlc3sf6sHV3TAZ_5vhyHQiKb9D4AdSlDkiTSgsRiicnzLASXwVr1n22EEg6Vtd2XBlyJslm8xlYiA") -> artist.thumbnail
            else -> null
        }
        val initialPage = ArtistPage(artist = artist.copy(thumbnail = verifiedBanner ?: artist.thumbnail), bannerUrl = verifiedBanner)
        val newEntry = ExploreDetail.Artist(artistPage = initialPage, isLoading = true)

        _uiState.update { current ->
            val updatedStack = current.detailStack + newEntry
            current.copy(
                detailStack = updatedStack,
                selectedArtistPage = initialPage,
                isLoadingArtist = true
            )
        }

        viewModelScope.launch {
            val page = searchRepository.getArtistPage(artist) ?: initialPage
            _uiState.update { current ->
                val updatedStack = current.detailStack.map { detail ->
                    if (detail is ExploreDetail.Artist && (detail.artistPage.artist.id == artist.id || detail.artistPage.artist.name.equals(artist.name, ignoreCase = true))) {
                        detail.copy(artistPage = page, isLoading = false)
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

    fun openAlbum(album: com.auralis.music.domain.model.PlaylistResult) {
        val newEntry = ExploreDetail.Album(album = album, tracks = emptyList(), isLoading = true)

        _uiState.update { current ->
            val updatedStack = current.detailStack + newEntry
            current.copy(
                detailStack = updatedStack,
                selectedAlbum = album,
                selectedAlbumTracks = emptyList(),
                isLoadingAlbum = true
            )
        }

        viewModelScope.launch {
            val tracks = searchRepository.getAlbumTracks(album)
            _uiState.update { current ->
                val updatedStack = current.detailStack.map { detail ->
                    if (detail is ExploreDetail.Album && (detail.album.id == album.id || detail.album.title.equals(album.title, ignoreCase = true))) {
                        detail.copy(tracks = tracks, isLoading = false)
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
