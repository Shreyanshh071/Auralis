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
    val primaryAlbum: PlaylistResult? = null       // Resolved primary album related to the query/top song
) {
    fun isEmpty(): Boolean = topResult == null && songs.isEmpty() && albums.isEmpty() && recommendations.isEmpty() && artists.isEmpty() && playlists.isEmpty() && primaryArtist == null && primaryAlbum == null
    fun isNotEmpty(): Boolean = !isEmpty()
}
