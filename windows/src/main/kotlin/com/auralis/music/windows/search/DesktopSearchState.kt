package com.auralis.music.windows.search

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.auralis.music.domain.model.SearchResults
import com.auralis.music.domain.repository.SearchRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The Windows search screen's state; the same flow as the phone's SearchViewModel.performSearch. */
class DesktopSearchState(private val repository: SearchRepository, private val scope: CoroutineScope, private val mayRecordHistory: suspend () -> Boolean = { true }) {
    var query by mutableStateOf("")
        private set
    /** The committed route remains stable while the title-bar field is being edited. */
    var submittedQuery by mutableStateOf<String?>(null)
        private set
    var results by mutableStateOf(SearchResults())
        private set
    var isSearching by mutableStateOf(false)
        private set
    var searchFailed by mutableStateOf(false)
        private set
    var hasSubmitted by mutableStateOf(false)
        private set
    /** While typing: up to three text suggestions and the songs that already match, as on the phone. */
    var suggestions by mutableStateOf(emptyList<String>())
        private set
    var liveSongs by mutableStateOf(emptyList<com.auralis.music.domain.model.Track>())
        private set

    val recentQueries = repository.getRecentSearchQueries()

    private var searchJob: Job? = null
    private var suggestionsJob: Job? = null
    private var liveSongsJob: Job? = null
    private val liveSongCache = LinkedHashMap<String, List<com.auralis.music.domain.model.Track>>()

    /** The phone's SearchViewModel.onQueryChange: typing drops the submitted results and looks up suggestions. */
    fun onQueryChange(newQuery: String) {
        suggestionsJob?.cancel()
        liveSongsJob?.cancel()
        searchJob?.cancel()
        if (newQuery.isBlank()) {
            clear()
            return
        }
        val trimmed = newQuery.trim()
        val cacheKey = trimmed.lowercase()
        val reusable = com.auralis.music.domain.search.SearchQueryMatcher.partitionResults(liveSongs, trimmed).first
        query = newQuery
        isSearching = false
        searchFailed = false
        hasSubmitted = false
        suggestions = emptyList()
        liveSongs = liveSongCache[cacheKey] ?: reusable.take(8)
        results = SearchResults()

        suggestionsJob = scope.launch {
            delay(120)
            val found = try {
                repository.getSuggestions(trimmed).take(3)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
            if (query.trim() == trimmed) suggestions = found
        }
        liveSongsJob = scope.launch {
            // Let a short burst of typing settle, then show either provider as soon as it arrives.
            delay(120)
            try {
                repository.searchLiveSongs(trimmed) { songs ->
                    scope.launch {
                        if (query.trim() == trimmed && !hasSubmitted && songs.isNotEmpty()) {
                            val visible = songs.take(8)
                            if (liveSongCache.size >= 24) liveSongCache.remove(liveSongCache.keys.first())
                            liveSongCache[cacheKey] = visible
                            liveSongs = visible
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Keep already received songs if the optional second request fails.
            }
        }
    }

    /** [recordHistory] false keeps the search out of Recent searches (the self-test's own). */
    fun search(text: String = query, recordHistory: Boolean = true) {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return
        searchJob?.cancel()
        suggestionsJob?.cancel()
        liveSongsJob?.cancel()
        query = trimmed
        submittedQuery = trimmed
        hasSubmitted = true
        searchFailed = false
        // A recent identical search shows complete at once while it refreshes.
        val cached = repository.cachedSearch(trimmed)?.takeIf { it.isNotEmpty() }
        results = cached ?: SearchResults()
        isSearching = cached == null
        searchJob = scope.launch {
            if (recordHistory) launch { runCatching { if (mayRecordHistory()) repository.recordSearchQuery(trimmed) } }
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
        suggestionsJob?.cancel()
        liveSongsJob?.cancel()
        suggestions = emptyList()
        liveSongs = emptyList()
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
