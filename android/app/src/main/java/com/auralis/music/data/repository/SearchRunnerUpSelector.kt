package com.auralis.music.data.repository

import com.auralis.music.domain.model.PlaylistResult
import com.auralis.music.domain.model.SearchTopResult
import com.auralis.music.domain.model.Track
import com.auralis.music.domain.search.SearchQueryMatcher

/** A same-name release is a song match when its title track accounts for its listens. */
internal fun isTitleTrackRelease(
    album: PlaylistResult,
    songs: List<Track>,
    albumPlays: Long,
    pageTrackCount: Int?
): Boolean {
    val title = SearchQueryMatcher.normalize(album.title)
    val titleTrackPlays = songs.asSequence()
        .filter { SearchQueryMatcher.normalize(it.title) == title }
        .filter { SearchQueryMatcher.isAuthorMatch(it.artist, album.author.orEmpty()) }
        .map { SearchQueryMatcher.parsePlayCount(it.views) }
        .maxOrNull() ?: 0L
    if (titleTrackPlays == 0L) return false
    if (pageTrackCount != null && pageTrackCount in 1..4) return true
    return albumPlays > 0L && albumPlays <= titleTrackPlays + titleTrackPlays / 10L
}

/** Compare a different exact-title song with a genuinely separate album. */
internal fun selectAlsoMatchingResult(
    query: String,
    topResult: SearchTopResult?,
    matchedSongs: List<Track>,
    queriedAlbum: PlaylistResult?,
    queriedAlbumPlays: Long,
    albumIsTitleTrackRelease: Boolean
): SearchTopResult? {
    if (topResult == null || topResult is SearchTopResult.ArtistResult) return null
    val normalizedQuery = SearchQueryMatcher.normalize(query)
    val topSong = (topResult as? SearchTopResult.SongResult)?.track
    val otherSong = matchedSongs.firstOrNull { candidate ->
        SearchQueryMatcher.normalize(candidate.title) == normalizedQuery &&
            SearchQueryMatcher.parsePlayCount(candidate.views) >= 1_000_000L &&
            (topSong == null || candidate.id != topSong.id &&
                !SearchQueryMatcher.isAuthorMatch(candidate.artist, topSong.artist))
    }
    val genuineAlbum = topResult !is SearchTopResult.AlbumResult && queriedAlbum != null &&
        queriedAlbumPlays >= 1_000_000L && !albumIsTitleTrackRelease
    if (genuineAlbum && queriedAlbumPlays > SearchQueryMatcher.parsePlayCount(otherSong?.views)) {
        return SearchTopResult.AlbumResult(queriedAlbum!!)
    }
    if (otherSong != null) return SearchTopResult.SongResult(otherSong)
    return null
}
