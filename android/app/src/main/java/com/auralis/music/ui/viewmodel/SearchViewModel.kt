package com.auralis.music.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.auralis.music.domain.model.SearchResults
import com.auralis.music.domain.model.Track
import com.auralis.music.domain.recognition.AudioRecognitionManager
import com.auralis.music.domain.recognition.RecognitionMode
import com.auralis.music.domain.recognition.RecognitionState
import com.auralis.music.domain.repository.SearchRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SearchUiState(
    val query: String = "",
    val suggestions: List<String> = emptyList(),
    val searchResults: SearchResults = SearchResults(),
    val recentQueries: List<String> = emptyList(),
    val isSearching: Boolean = false,
    val isRecognitionOpen: Boolean = false
)

class SearchViewModel(
    private val searchRepository: SearchRepository,
    context: Context? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    val recognitionManager: AudioRecognitionManager? = context?.let {
        AudioRecognitionManager(it, searchRepository, viewModelScope)
    }

    val recognitionState: StateFlow<RecognitionState> =
        recognitionManager?.state ?: MutableStateFlow(RecognitionState()).asStateFlow()

    private var debounceJob: Job? = null

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
        _uiState.update { it.copy(isRecognitionOpen = true) }
    }

    fun closeRecognitionModal() {
        recognitionManager?.stop()
        _uiState.update { it.copy(isRecognitionOpen = false) }
    }

    fun setRecognitionMode(mode: RecognitionMode) {
        recognitionManager?.setMode(mode)
    }

    fun startListening() {
        val currentMode = recognitionState.value.mode
        if (currentMode == RecognitionMode.VOICE_SEARCH) {
            recognitionManager?.startVoiceSearch()
        } else {
            recognitionManager?.startMusicIdentification()
        }
    }

    fun stopListening() {
        recognitionManager?.stop()
    }

    fun onQueryChange(newQuery: String) {
        _uiState.update { it.copy(query = newQuery) }

        debounceJob?.cancel()
        if (newQuery.isBlank()) {
            _uiState.update { it.copy(suggestions = emptyList()) }
            return
        }

        debounceJob = viewModelScope.launch {
            delay(200) // 200ms debounce
            val suggestions = searchRepository.getSuggestions(newQuery)
            _uiState.update { it.copy(suggestions = suggestions) }
        }
    }

    fun performSearch(query: String) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return
        _uiState.update { it.copy(query = trimmed, isSearching = true, suggestions = emptyList(), isRecognitionOpen = false) }

        viewModelScope.launch {
            searchRepository.recordSearchQuery(trimmed)
            val results = searchRepository.search(trimmed)
            _uiState.update { it.copy(searchResults = results, isSearching = false) }
        }
    }

    fun clearSearch() {
        _uiState.update { it.copy(query = "", suggestions = emptyList(), searchResults = SearchResults()) }
    }

    fun removeRecentQuery(query: String) {
        viewModelScope.launch {
            searchRepository.removeSearchQuery(query)
        }
    }

    fun clearRecentQueries() {
        viewModelScope.launch {
            searchRepository.clearSearchHistory()
        }
    }
}
