package com.auralis.music.domain.model

data class SearchResults(
    val songs: List<Track> = emptyList(),
    val artists: List<Artist> = emptyList(),
    val playlists: List<PlaylistResult> = emptyList()
)
