package com.auralis.music.domain.model

/**
 * Model definitions for Home recommendations matching Metrolist architecture.
 */
data class DailyDiscoverItem(
    val seed: Track,
    val recommendation: Track,
    val browseId: String? = null,
    val params: String? = null
)

data class SimilarRecommendation(
    val seedTitle: String,
    val seedThumbnail: String? = null,
    val seedType: RecommendationSeedType = RecommendationSeedType.ARTIST,
    val items: List<Track> = emptyList(),
    val artistId: String? = null,
    val artistName: String? = null
)

enum class RecommendationSeedType {
    ARTIST, SONG, ALBUM
}

data class HomeChip(
    val title: String,
    val endpointBrowseId: String? = null,
    val params: String? = null
)

data class CommunityPlaylistItem(
    val playlist: PlaylistResult,
    val songs: List<Track> = emptyList()
)

data class HomeSection(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val thumbnail: String? = null,
    val items: List<Track> = emptyList(),
    val continuationToken: String? = null
)
