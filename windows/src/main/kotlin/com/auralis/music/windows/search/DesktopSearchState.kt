package com.auralis.music.windows.search

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.auralis.music.domain.model.SearchResults
import com.auralis.music.domain.repository.SearchRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** The Windows search screen's state; the same flow as the phone's SearchViewModel.performSearch. */
class DesktopSearchState(private val repository: SearchRepository, private val scope: CoroutineScope) {
    var query by mutableStateOf("")
        private set
    var results by mutableStateOf(SearchResults())
        private set
    var isSearching by mutableStateOf(false)
        private set
    var searchFailed by mutableStateOf(false)
        private set
    var hasSubmitted by mutableStateOf(false)
        private set

    val recentQueries = repository.getRecentSearchQueries()

    private var searchJob: Job? = null

    fun onQueryChange(newQuery: String) {
        query = newQuery
        if (newQuery.isBlank()) clear()
    }

    /** [recordHistory] false keeps the search out of Recent searches (the self-test's own). */
    fun search(text: String = query, recordHistory: Boolean = true) {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return
        searchJob?.cancel()
        query = trimmed
        hasSubmitted = true
        searchFailed = false
        // A recent identical search shows complete at once while it refreshes.
        val cached = repository.cachedSearch(trimmed)?.takeIf { it.isNotEmpty() }
        results = cached ?: SearchResults()
        isSearching = cached == null
        searchJob = scope.launch {
            if (recordHistory) launch { runCatching { repository.recordSearchQuery(trimmed) } }
            try {
                val finished = repository.search(trimmed) { received ->
                    scope.launch {
                        if (trimmed.equals(query, ignoreCase = true)) {
                            results = received
                            isSearching = false
                        }
                    }
                }
                if (trimmed.equals(query, ignoreCase = true)) {
                    results = finished
                    searchFailed = finished.requestFailed
                    isSearching = false
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (trimmed.equals(query, ignoreCase = true)) {
                    isSearching = false
                    searchFailed = true
                }
            }
        }
    }

    fun clear() {
        searchJob?.cancel()
        query = ""
        results = SearchResults()
        isSearching = false
        searchFailed = false
        hasSubmitted = false
    }

    fun removeRecent(query: String) {
        scope.launch { repository.removeSearchQuery(query) }
    }
}
