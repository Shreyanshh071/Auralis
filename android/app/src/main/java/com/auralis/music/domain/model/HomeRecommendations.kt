package com.auralis.music.domain.model

import kotlinx.serialization.Serializable

/**
 * Model definitions for Home recommendations matching Metrolist architecture.
 */
@Serializable
data class DailyDiscoverItem(
    val seed: Track,
    val recommendation: Track,
    val browseId: String? = null,
    val params: String? = null
)

@Serializable
data class SimilarRecommendation(
    val seedTitle: String,
    val seedThumbnail: String? = null,
    val seedType: RecommendationSeedType = RecommendationSeedType.ARTIST,
    val items: List<Track> = emptyList(),
    val artistId: String? = null,
    val artistName: String? = null
)

@Serializable
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
    val albums: List<PlaylistResult> = emptyList(),
    val continuationToken: String? = null
)
