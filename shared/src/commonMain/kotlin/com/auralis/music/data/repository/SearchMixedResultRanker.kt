package com.auralis.music.data.repository

import com.auralis.music.domain.model.PlaylistResult
import com.auralis.music.domain.model.SearchTopResult
import com.auralis.music.domain.model.Track
import com.auralis.music.domain.search.SearchQueryMatcher

/** Rank songs and albums together. Popularity compares only results with similar query relevance. */
fun rankMixedSearchResults(
    query: String,
    songs: List<Track>,
    albums: List<PlaylistResult>,
    albumPlayCounts: Map<String, Long>
): List<SearchTopResult> {
    data class Match(val result: SearchTopResult, val tier: Int, val plays: Long, val index: Int)

    val songMatches = songs.mapIndexedNotNull { index, song ->
        val match = SearchQueryMatcher.evaluateMatch(song, query) ?: return@mapIndexedNotNull null
        Match(SearchTopResult.SongResult(song), match.tier.priority, SearchQueryMatcher.parsePlayCount(song.views), index)
    }
    val albumMatches = albums.mapIndexedNotNull { index, album ->
        val match = SearchQueryMatcher.evaluateAlbumMatch(album, query) ?: return@mapIndexedNotNull null
        Match(SearchTopResult.AlbumResult(album), match.tier.priority, albumPlayCounts[album.id] ?: 0L, songs.size + index)
    }

    return (songMatches + albumMatches)
        .sortedWith(compareBy<Match> { it.tier }.thenByDescending { it.plays }.thenBy { it.index })
        .map { it.result }
}
