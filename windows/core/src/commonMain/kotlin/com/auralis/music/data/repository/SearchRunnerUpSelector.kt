package com.auralis.music.data.repository

import com.auralis.music.domain.model.PlaylistResult
import com.auralis.music.domain.model.SearchTopResult
import com.auralis.music.domain.model.Track
import com.auralis.music.domain.search.SearchQueryMatcher

/** A same-name release is a song match when its title track accounts for its listens. */
fun isTitleTrackRelease(
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
fun selectAlsoMatchingResult(
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

/** Below this many plays a second card is not a meaningful alternative, however well it matches. */
private const val ALSO_MATCHING_MIN_PLAYS = 1_000_000L

/** A second card needs 1/50 of the top result's plays, unless it is hugely popular on its own. */
private const val ALSO_MATCHING_MIN_SHARE_DIVISOR = 50L
private const val ALSO_MATCHING_POPULAR_ON_ITS_OWN = 100_000_000L

/**
 * The optional "Also matching" card under Top result: the best-ranked other song or album that
 * names the query exactly, is a released song (counted in plays, not a video upload counted in
 * views), is not a version the query didn't ask for (remix, cover, karaoke, instrumental, sped
 * up...), is not another upload or version of the top song, and is listened to enough to be a
 * real alternative: 1M+ plays, and 1/50 of the top result's or 100M+ on its own (a cover of a
 * huge hit rarely has either). Null when nothing qualifies; weaker matches stay in the normal list.
 */
fun selectAlsoMatching(
    query: String,
    topResult: SearchTopResult?,
    ranking: List<SearchTopResult>,
    albumPlayCounts: Map<String, Long>
): SearchTopResult? {
    if (topResult == null || topResult is SearchTopResult.ArtistResult) return null
    fun plays(result: SearchTopResult): Long = when (result) {
        is SearchTopResult.SongResult -> SearchQueryMatcher.parsePlayCount(result.track.views)
        is SearchTopResult.AlbumResult -> albumPlayCounts[result.album.id] ?: 0L
        is SearchTopResult.ArtistResult -> 0L
    }
    val topPlays = plays(topResult)
    val topSong = (topResult as? SearchTopResult.SongResult)?.track
    val topAlbumId = (topResult as? SearchTopResult.AlbumResult)?.album?.id
    fun popularEnough(candidate: SearchTopResult): Boolean {
        val candidatePlays = plays(candidate)
        return candidatePlays >= ALSO_MATCHING_MIN_PLAYS &&
            (candidatePlays * ALSO_MATCHING_MIN_SHARE_DIVISOR >= topPlays || candidatePlays >= ALSO_MATCHING_POPULAR_ON_ITS_OWN)
    }
    return ranking.firstOrNull { candidate ->
        when (candidate) {
            is SearchTopResult.SongResult -> {
                val song = candidate.track
                song.id != topSong?.id &&
                    song.views?.contains("view", ignoreCase = true) != true &&
                    SearchQueryMatcher.evaluateMatch(song, query)?.tier == SearchQueryMatcher.MatchTier.EXACT_TITLE &&
                    SearchQueryMatcher.unrequestedVersionTags(song, query).isEmpty() &&
                    !SearchQueryMatcher.isUnwantedDerivative(song, query) &&
                    (topSong == null || !SearchQueryMatcher.isSameSong(song, topSong)) &&
                    popularEnough(candidate)
            }
            is SearchTopResult.AlbumResult ->
                // The top song's own single/EP is that song again, not an alternative.
                candidate.album.id != topAlbumId && candidate.album.id != topSong?.albumId &&
                    SearchQueryMatcher.evaluateAlbumMatch(candidate.album, query)?.tier == SearchQueryMatcher.MatchTier.EXACT_TITLE &&
                    popularEnough(candidate)
            is SearchTopResult.ArtistResult -> false
        }
    }
}
