package com.auralis.music.windows.home

import com.auralis.music.data.network.YouTubeBackgroundGate
import com.auralis.music.domain.model.Artist
import com.auralis.music.domain.model.PlaylistResult
import com.auralis.music.domain.model.SearchResults
import com.auralis.music.domain.model.Track
import com.auralis.music.domain.repository.SearchRepository

/**
 * The search repository for work nobody is waiting on (Home's recommendations, Albums & singles,
 * Familiar Artists, stats photos): every YouTube lookup queues behind [YouTubeBackgroundGate], so
 * launch can't fire dozens at once and get the network blocked. What the listener does directly
 * (typing a search, starting radio, opening a page) keeps the unpaced repository.
 */
internal class PacedSearchRepository(private val delegate: SearchRepository) : SearchRepository by delegate {
    private companion object { const val FULL_SEARCH_MS = 2_500L }
    override suspend fun search(query: String): SearchResults = YouTubeBackgroundGate.run(FULL_SEARCH_MS) { delegate.search(query) }
    override suspend fun search(query: String, onResults: (SearchResults) -> Unit): SearchResults =
        YouTubeBackgroundGate.run(FULL_SEARCH_MS) { delegate.search(query, onResults) }
    override suspend fun searchSongs(query: String): List<Track> = YouTubeBackgroundGate.run { delegate.searchSongs(query) }
    override suspend fun searchAlbums(query: String): List<PlaylistResult> =
        YouTubeBackgroundGate.run(YouTubeBackgroundGate.SPACING_MS * 2) { delegate.searchAlbums(query) }
    override suspend fun searchArtists(query: String): List<Artist> = YouTubeBackgroundGate.run { delegate.searchArtists(query) }
    override suspend fun searchPlaylists(query: String): List<PlaylistResult> = YouTubeBackgroundGate.run { delegate.searchPlaylists(query) }
    override suspend fun getArtistPage(artist: Artist) = YouTubeBackgroundGate.run { delegate.getArtistPage(artist) }
    override suspend fun getAlbumTracks(album: PlaylistResult): List<Track> = YouTubeBackgroundGate.run { delegate.getAlbumTracks(album) }
}
