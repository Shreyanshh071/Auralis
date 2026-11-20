package com.auralis.music.desktop.home

import com.auralis.music.data.network.YouTubeShortsFilter
import com.auralis.music.domain.model.*
import com.auralis.music.ui.viewmodel.HomePlatform

/** Old cached recommendations and pins must pass the same checks as fresh lists. */
class MusicHomePlatform(private val source: HomePlatform,
    private val filter: suspend (List<Track>) -> List<Track> = YouTubeShortsFilter::filter
) : HomePlatform by source {
    private suspend fun items(items: List<SpeedDialItem>): List<SpeedDialItem> {
        val ids = filter(items.mapNotNull { it.track }).map { it.id }.toSet()
        return items.filter { it.track == null || it.track.id in ids }
    }
    override suspend fun getCachedSpeedDial() = source.getCachedSpeedDial().map { items(it) }
    override suspend fun getPinnedSpeedDialItems() = items(source.getPinnedSpeedDialItems())
    override suspend fun getCachedSimilarRecommendations() = source.getCachedSimilarRecommendations().map {
        it.copy(items = filter(it.items))
    }.filter { it.items.isNotEmpty() }
    override suspend fun getCachedDailyDiscover() = source.getCachedDailyDiscover().let { items ->
        val ids = filter(items.flatMap { listOf(it.seed, it.recommendation) }).map { it.id }.toSet()
        items.filter { it.seed.id in ids && it.recommendation.id in ids }
    }
}

suspend fun musicHomeState(state: com.auralis.music.ui.viewmodel.HomeUiState,
    filter: suspend (List<Track>) -> List<Track> = YouTubeShortsFilter::filter
): com.auralis.music.ui.viewmodel.HomeUiState {
    val tracks = state.recentTracks.map { it.track } + state.topPlayedTracks.map { it.track } +
        state.speedDialPages.flatten().mapNotNull { it.track } + state.pinnedItems.mapNotNull { it.track } +
        state.forgottenFavorites + state.keepListening + state.quickPicks + state.similarArtistTracks +
        state.communitySections.flatMap { it.songs } + state.dynamicSections.flatMap { it.items } +
        state.legacySections.flatMap { it.tracks } + state.dailyDiscover.flatMap { listOf(it.seed, it.recommendation) } +
        state.similarRecommendations.flatMap { it.items }
    val ids = filter(tracks.distinctBy { it.id }).map { it.id }.toSet()
    fun items(items: List<SpeedDialItem>) = items.filter { it.track == null || it.track.id in ids }
    return state.copy(recentTracks = state.recentTracks.filter { it.track.id in ids },
        topPlayedTracks = state.topPlayedTracks.filter { it.track.id in ids },
        speedDialPages = state.speedDialPages.map(::items), pinnedItems = items(state.pinnedItems),
        forgottenFavorites = state.forgottenFavorites.filter { it.id in ids },
        keepListening = state.keepListening.filter { it.id in ids }, quickPicks = state.quickPicks.filter { it.id in ids },
        similarArtistTracks = state.similarArtistTracks.filter { it.id in ids },
        communitySections = state.communitySections.map { it.copy(songs = it.songs.filter { t -> t.id in ids }) },
        dynamicSections = state.dynamicSections.map { it.copy(items = it.items.filter { t -> t.id in ids }) },
        legacySections = state.legacySections.map { it.copy(tracks = it.tracks.filter { t -> t.id in ids }) },
        dailyDiscover = state.dailyDiscover.filter { it.seed.id in ids && it.recommendation.id in ids },
        similarRecommendations = state.similarRecommendations.map { it.copy(items = it.items.filter { t -> t.id in ids }) })
}
