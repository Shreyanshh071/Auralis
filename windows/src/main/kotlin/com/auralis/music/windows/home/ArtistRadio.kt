package com.auralis.music.windows.home

import com.auralis.music.data.network.InnerTubeClient
import com.auralis.music.desktop.library.DesktopBlockedArtists
import com.auralis.music.domain.model.Artist
import com.auralis.music.domain.model.Track
import com.auralis.music.domain.repository.SearchRepository
import com.auralis.music.domain.search.SearchQueryMatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * An artist's radio: seeded from the artist's top song (from their page, else their best song
 * match), extended by the radio engine, with blocked artists left out (the seed always stays).
 */
internal suspend fun artistRadioTracks(searchRepository: SearchRepository, artist: Artist): List<Track> = withContext(Dispatchers.IO) {
    val seed = runCatching { searchRepository.getArtistPage(artist)?.topSongs?.firstOrNull() }.getOrNull()
        ?: runCatching { searchRepository.searchSongs(artist.name) }.getOrDefault(emptyList())
            .firstOrNull { SearchQueryMatcher.isAuthorMatch(it.artist, artist.name) }
        ?: return@withContext emptyList()
    val radio = runCatching { InnerTubeClient().getRadioTracks(seed.id, seed.artist, seed.title) }.getOrDefault(emptyList())
    (listOf(seed) + radio.filterNot { DesktopBlockedArtists.blocksTrack(it) }).distinctBy { it.id }
}
