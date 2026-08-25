package com.auralis.music.domain.repository

import com.auralis.music.domain.model.Artist
import com.auralis.music.domain.model.PlaylistResult
import com.auralis.music.domain.model.SearchResults
import com.auralis.music.domain.model.Track
import kotlinx.coroutines.flow.Flow

interface SearchRepository {
    suspend fun search(query: String): SearchResults
    suspend fun searchSongs(query: String): List<Track>
    suspend fun searchArtists(query: String): List<Artist>
    suspend fun searchPlaylists(query: String): List<PlaylistResult>
    suspend fun getSuggestions(query: String): List<String>
    fun getRecentSearchQueries(): Flow<List<String>>
    suspend fun recordSearchQuery(query: String)
    suspend fun removeSearchQuery(query: String)
    suspend fun clearSearchHistory()
}
