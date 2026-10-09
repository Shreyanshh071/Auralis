package com.auralis.music.data.repository

import com.auralis.music.domain.model.PlaylistResult
import com.auralis.music.domain.model.SearchTopResult
import com.auralis.music.domain.model.Track
import com.auralis.music.domain.search.SearchQueryMatcher

/**
 * Rank songs and albums together. [songs] arrive already ranked by
 * [SearchQueryMatcher.partitionResults] (originals above slowed / cover uploads, a hugely popular
 * partial title above obscure exact ones), and that order is kept: re-sorting them here put a
 * 3.4M-view "Chogada Tara (slowed+reverb)" on top of the 1.3B-play "Chogada". Albums are slotted
 * in by plays against the song they would displace.
 */
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
    // Clean and explicit releases can have different browse IDs for the same album.
    // Feature that album once, retaining the release with the strongest play count.
    val distinctAlbums = albums.groupBy {
        SearchQueryMatcher.normalize(it.title) to SearchQueryMatcher.normalize(it.author.orEmpty())
    }.values.map { releases -> releases.maxBy { albumPlayCounts[it.id] ?: 0L } }
    val albumMatches = distinctAlbums.mapIndexedNotNull { index, album ->
        val match = SearchQueryMatcher.evaluateAlbumMatch(album, query) ?: return@mapIndexedNotNull null
        Match(SearchTopResult.AlbumResult(album), match.tier.priority, albumPlayCounts[album.id] ?: 0L, songs.size + index)
    }.sortedWith(compareBy<Match> { it.tier }.thenByDescending { it.plays }.thenBy { it.index })

    val titleTiers = SearchQueryMatcher.MatchTier.CLOSE_TITLE.priority
    val pendingSongs = ArrayDeque(songMatches)
    val pendingAlbums = ArrayDeque(albumMatches)
    val ranked = mutableListOf<SearchTopResult>()
    while (pendingSongs.isNotEmpty() || pendingAlbums.isNotEmpty()) {
        val song = pendingSongs.firstOrNull()
        val album = pendingAlbums.firstOrNull()
        // Strictly more plays: on a tie the song keeps its place (a single is its song).
        val albumFirst = song == null || (album != null && (
            if (song.tier <= titleTiers && album.tier <= titleTiers) album.plays > song.plays
            else album.tier < song.tier || (album.tier == song.tier && album.plays > song.plays)))
        ranked += if (albumFirst) pendingAlbums.removeFirst().result else pendingSongs.removeFirst().result
    }
    return ranked
}
