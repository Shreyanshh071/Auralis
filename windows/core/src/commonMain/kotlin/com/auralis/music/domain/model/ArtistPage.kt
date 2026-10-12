package com.auralis.music.domain.model

/**
 * Full artist details page data model matching YouTube Music.
 */
data class ArtistPage(
    val artist: Artist,
    val bannerUrl: String? = null,
    val description: String? = null,
    val subscribers: String? = null,
    val monthlyAudience: String? = null,
    val topSongs: List<Track> = emptyList(),
    val albums: List<PlaylistResult> = emptyList(),
    val singles: List<PlaylistResult> = emptyList(),
    val similarArtists: List<Artist> = emptyList(),
    val radioPlaylistId: String? = null,
    val radioBrowseId: String? = null,
    val radioParams: String? = null,
    val videos: List<Track> = emptyList(),
    val playlists: List<PlaylistResult> = emptyList(),
    /** Search-only recovery is not a complete artist page. */
    val isFallback: Boolean = false,
    val topSongsBrowseId: String? = null,
    val catalogueIncomplete: Boolean = false
)
