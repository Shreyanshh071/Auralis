package com.auralis.music.domain.model

sealed interface SearchTopResult {
    data class SongResult(val track: Track) : SearchTopResult
    data class AlbumResult(val album: PlaylistResult) : SearchTopResult
    data class ArtistResult(val artist: Artist) : SearchTopResult
}

data class SearchResults(
    val topResult: SearchTopResult? = null,
    val recommendations: List<Track> = emptyList(), // Maximum 3 supplementary recommendations
    val songs: List<Track> = emptyList(),          // Actual query-matched songs ranked by relevance
    val albums: List<PlaylistResult> = emptyList(), // Actual query-matched albums ranked by relevance
    val artists: List<Artist> = emptyList(),
    val playlists: List<PlaylistResult> = emptyList(),
    val primaryArtist: Artist? = null,             // Resolved primary artist for the query/top song
    val primaryAlbum: PlaylistResult? = null,      // Resolved primary album related to the query/top song
    // The same-named album or song that lost the top spot on plays, shown right below it.
    val runnerUp: SearchTopResult? = null,
    // Total plays of the same-named album (top result or runner-up), for its subtitle.
    val albumPlays: Long = 0L,
    val rankedMatches: List<SearchTopResult> = emptyList(),
    val albumPlayCounts: Map<String, Long> = emptyMap(),
    val requestFailed: Boolean = false,
    // Release id -> "single" / "ep" / "album" as YouTube Music labels it, so a song row can say
    // "Single" or name its album even when the album shares the song's title.
    val releaseTypes: Map<String, String> = emptyMap(),
    val isComplete: Boolean = true
) {
    fun isEmpty(): Boolean = topResult == null && rankedMatches.isEmpty() && songs.isEmpty() && albums.isEmpty() && recommendations.isEmpty() && artists.isEmpty() && playlists.isEmpty() && primaryArtist == null && primaryAlbum == null
    fun isNotEmpty(): Boolean = !isEmpty()

    /** The list below the featured cards contains only results not already shown above it. */
    fun remainingRankedMatches(displayedAlbumId: String? = null): List<SearchTopResult> {
        val featured = listOfNotNull(topResult, runnerUp)
        val shownSongIds = featured.filterIsInstance<SearchTopResult.SongResult>().map { it.track.id }.toSet()
        val shownAlbumIds = featured.filterIsInstance<SearchTopResult.AlbumResult>().map { it.album.id }.toSet() +
            listOfNotNull(displayedAlbumId)
        val shownArtistIds = featured.filterIsInstance<SearchTopResult.ArtistResult>().map { it.artist.id }.toSet()
        val matches = rankedMatches.ifEmpty { songs.map { SearchTopResult.SongResult(it) } }
        return matches.filterNot { match ->
            when (match) {
                is SearchTopResult.SongResult -> match.track.id in shownSongIds
                is SearchTopResult.AlbumResult -> match.album.id in shownAlbumIds
                is SearchTopResult.ArtistResult -> match.artist.id in shownArtistIds
            }
        }
    }
}
