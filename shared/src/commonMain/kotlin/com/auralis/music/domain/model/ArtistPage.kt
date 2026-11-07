package com.auralis.music.domain.model

import kotlinx.serialization.Serializable

/**
 * Full artist details page data model matching YouTube Music.
 */
@Serializable
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
    /** Every other shelf the artist has (Videos, Featured on, Playlists...), titled as the source titles it. */
    val shelves: List<ArtistShelf> = emptyList(),
    /** The newest album or single that carries a year. */
    val latestRelease: ArtistLatestRelease? = null,
    val radioPlaylistId: String? = null,
    val radioBrowseId: String? = null,
    val radioParams: String? = null,
    /** The full discography behind the Albums row's "more" link, when the row is a preview. */
    val albumsMore: ArtistReleasesEndpoint? = null,
    /** The same for Singles & EPs. */
    val singlesMore: ArtistReleasesEndpoint? = null
)

/** A YouTube Music browse request that lists every release of one kind for an artist. */
@Serializable
data class ArtistReleasesEndpoint(
    val browseId: String,
    val params: String? = null
)

@Serializable
data class ArtistLatestRelease(
    val release: PlaylistResult,
    val year: Int,
    val isAlbum: Boolean
)

@Serializable
data class ArtistShelf(
    val title: String,
    val tracks: List<Track> = emptyList(),
    val playlists: List<PlaylistResult> = emptyList()
)
