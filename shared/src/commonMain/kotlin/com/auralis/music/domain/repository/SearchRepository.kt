package com.auralis.music.domain.repository

import com.auralis.music.domain.model.Artist
import com.auralis.music.domain.model.ArtistPage
import com.auralis.music.domain.model.PlaylistResult
import com.auralis.music.domain.model.SearchResults
import com.auralis.music.domain.model.Track
import kotlinx.coroutines.flow.Flow

interface SearchRepository {
    suspend fun search(query: String): SearchResults
    suspend fun search(query: String, onResults: (SearchResults) -> Unit): SearchResults {
        return search(query).also(onResults)
    }
    suspend fun searchSongs(query: String): List<Track>
    /** Live autocomplete can publish each provider response without waiting for enrichment. */
    suspend fun searchLiveSongs(query: String, onResults: (List<Track>) -> Unit) {
        onResults(searchSongs(query))
    }
    suspend fun searchAlbums(query: String): List<PlaylistResult>
    suspend fun searchArtists(query: String): List<Artist>
    suspend fun searchPlaylists(query: String): List<PlaylistResult>
    suspend fun getSuggestions(query: String): List<String>
    suspend fun getArtistPage(artist: Artist): ArtistPage?
    suspend fun getAlbumTracks(album: PlaylistResult): List<Track>
    fun getRecentSearchQueries(): Flow<List<String>>
    suspend fun recordSearchQuery(query: String)
    suspend fun removeSearchQuery(query: String)
    suspend fun clearSearchHistory()
}
