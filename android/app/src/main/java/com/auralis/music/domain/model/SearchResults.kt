package com.auralis.music.domain.model

data class SearchResults(
    val recommendations: List<Track> = emptyList(), // Maximum 3 supplementary recommendations
    val songs: List<Track> = emptyList(),          // Actual query-matched songs ranked by relevance
    val artists: List<Artist> = emptyList(),
    val playlists: List<PlaylistResult> = emptyList()
) {
    fun isEmpty(): Boolean = songs.isEmpty() && recommendations.isEmpty() && artists.isEmpty() && playlists.isEmpty()
    fun isNotEmpty(): Boolean = !isEmpty()
}
