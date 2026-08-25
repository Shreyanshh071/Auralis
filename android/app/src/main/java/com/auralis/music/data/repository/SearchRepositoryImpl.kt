package com.auralis.music.data.repository

import com.auralis.music.data.local.dao.SearchHistoryDao
import com.auralis.music.data.local.entity.SearchHistoryEntity
import com.auralis.music.data.network.InnerTubeClient
import com.auralis.music.data.network.SearchSuggestionsClient
import com.auralis.music.data.remote.InvidiousApi
import com.auralis.music.data.remote.PipedApi
import com.auralis.music.domain.model.Artist
import com.auralis.music.domain.model.ArtistPage
import com.auralis.music.domain.model.PlaylistResult
import com.auralis.music.domain.model.SearchResults
import com.auralis.music.domain.model.Track
import com.auralis.music.domain.repository.SearchRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * Dedicated YouTube Music search repository powered by InnerTube WEB_REMIX API
 * and live autocomplete suggestions.
 */
class SearchRepositoryImpl(
    private val innerTubeClient: InnerTubeClient,
    private val suggestionsClient: SearchSuggestionsClient,
    private val searchHistoryDao: SearchHistoryDao
) : SearchRepository {

    override suspend fun search(query: String): SearchResults = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return@withContext SearchResults()

        // Pure YouTube Music search
        innerTubeClient.search(trimmed)
    }

    override suspend fun searchSongs(query: String): List<Track> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return@withContext emptyList()

        // 1. Try YouTube Music filtered song search
        val filtered = innerTubeClient.search(trimmed, InnerTubeClient.FILTER_SONGS).songs
        if (filtered.isNotEmpty()) return@withContext filtered

        // 2. Fall back to general YouTube Music search songs
        innerTubeClient.search(trimmed).songs
    }

    override suspend fun searchArtists(query: String): List<Artist> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return@withContext emptyList()

        // 1. Try YouTube Music filtered artist search
        val filtered = innerTubeClient.search(trimmed, InnerTubeClient.FILTER_ARTISTS).artists
        if (filtered.isNotEmpty()) return@withContext filtered

        // 2. Fall back to general YouTube Music search artists
        innerTubeClient.search(trimmed).artists
    }

    override suspend fun searchPlaylists(query: String): List<PlaylistResult> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return@withContext emptyList()

        // 1. Try YouTube Music filtered playlist search
        val filtered = innerTubeClient.search(trimmed, InnerTubeClient.FILTER_PLAYLISTS).playlists
        if (filtered.isNotEmpty()) return@withContext filtered

        // 2. Fall back to general YouTube Music search playlists
        innerTubeClient.search(trimmed).playlists
    }

    override suspend fun getSuggestions(query: String): List<String> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return@withContext emptyList()
        suggestionsClient.getSuggestions(trimmed)
    }

    override suspend fun getArtistPage(artist: Artist): ArtistPage? = withContext(Dispatchers.IO) {
        innerTubeClient.getArtistPage(artist)
    }

    override fun getRecentSearchQueries(): Flow<List<String>> {
        return searchHistoryDao.getRecentQueriesFlow()
    }

    override suspend fun recordSearchQuery(query: String) = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isNotBlank()) {
            searchHistoryDao.insertSearchQuery(
                SearchHistoryEntity(
                    query = trimmed,
                    timestamp = System.currentTimeMillis()
                )
            )
        }
    }

    override suspend fun removeSearchQuery(query: String) = withContext(Dispatchers.IO) {
        searchHistoryDao.deleteSearchQuery(query)
    }

    override suspend fun clearSearchHistory() = withContext(Dispatchers.IO) {
        searchHistoryDao.clearSearchHistory()
    }
}
