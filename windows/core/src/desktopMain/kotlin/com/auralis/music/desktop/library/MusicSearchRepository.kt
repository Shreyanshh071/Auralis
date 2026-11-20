package com.auralis.music.desktop.library

import com.auralis.music.data.network.YouTubeShortsFilter
import com.auralis.music.domain.model.*
import com.auralis.music.domain.repository.SearchRepository
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** Apply the same admission rule to every search, artist and browse result. */
class MusicSearchRepository(
    private val source: SearchRepository,
    private val filter: suspend (List<Track>) -> List<Track> = YouTubeShortsFilter::filter,
    private val cachedFilter: (List<Track>) -> List<Track> = YouTubeShortsFilter::filterCached
) : SearchRepository by source {
    private fun tracks(results: SearchResults) = (results.songs + results.recommendations +
        (listOfNotNull(results.topResult, results.runnerUp) + results.rankedMatches)
            .filterIsInstance<SearchTopResult.SongResult>().map { it.track }).distinctBy { it.id }

    private fun project(results: SearchResults, allowed: List<Track>): SearchResults {
        val ids = allowed.map { it.id }.toSet()
        fun keep(result: SearchTopResult?) = result !is SearchTopResult.SongResult || result.track.id in ids
        return results.copy(songs = results.songs.filter { it.id in ids },
            recommendations = results.recommendations.filter { it.id in ids },
            topResult = results.topResult?.takeIf(::keep), runnerUp = results.runnerUp?.takeIf(::keep),
            rankedMatches = results.rankedMatches.filter(::keep))
    }

    private suspend fun filtered(results: SearchResults) = project(results, filter(tracks(results)))
    override fun cachedSearch(query: String) = source.cachedSearch(query)?.let { project(it, cachedFilter(tracks(it))) }
    override suspend fun search(query: String) = filtered(source.search(query))

    override suspend fun search(query: String, onResults: (SearchResults) -> Unit): SearchResults = coroutineScope {
        val pages = Channel<SearchResults>(Channel.UNLIMITED)
        val publisher = launch { for (page in pages) onResults(filtered(page)) }
        try {
            val final = source.search(query) { pages.trySend(it) }
            pages.close(); publisher.join()
            filtered(final)
        } finally { pages.cancel(); publisher.cancel() }
    }

    override suspend fun searchSongs(query: String) = filter(source.searchSongs(query))
    override suspend fun searchLiveSongs(query: String, onResults: (List<Track>) -> Unit) = coroutineScope {
        val pages = Channel<List<Track>>(Channel.UNLIMITED)
        val publisher = launch { for (page in pages) onResults(filter(page)) }
        try {
            source.searchLiveSongs(query) { pages.trySend(it) }
            pages.close(); publisher.join()
        } finally { pages.cancel(); publisher.cancel() }
    }

    private suspend fun filtered(page: ArtistPage): ArtistPage {
        val ids = filter((page.topSongs + page.videos).distinctBy { it.id }).map { it.id }.toSet()
        return page.copy(topSongs = page.topSongs.filter { it.id in ids }, videos = page.videos.filter { it.id in ids })
    }
    override suspend fun getArtistPage(artist: Artist) = source.getArtistPage(artist)?.let { filtered(it) }
    override suspend fun getArtistPage(artist: Artist, onPreview: suspend (ArtistPage) -> Unit) =
        source.getArtistPage(artist) { onPreview(filtered(it)) }?.let { filtered(it) }
    override suspend fun getAlbumTracks(album: PlaylistResult) = filter(source.getAlbumTracks(album))
}
