package com.auralis.music.windows.home

import com.auralis.music.domain.model.*
import com.auralis.music.ui.viewmodel.HomeUiState

internal data class DesktopHomeShelf(
    val id: String, val title: String, val subtitle: String? = null,
    val tracks: List<Track> = emptyList(), val albums: List<PlaylistResult> = emptyList()
)

internal data class DesktopHomeContent(
    val personalized: Boolean,
    val shortcuts: List<SpeedDialItem>,
    val picks: List<Track>,
    val shelves: List<DesktopHomeShelf>
)

/** Keep starter content out of personal rotation and avoid repeating the same song on every shelf. */
internal fun desktopHomeContent(state: HomeUiState): DesktopHomeContent {
    val personalized = state.recentTracks.isNotEmpty() || state.topPlayedTracks.isNotEmpty()
    val filtered = state.selectedChip != null
    val dial = state.speedDialPages.flatten().filter {
        it.type in setOf(SpeedDialType.TRACK, SpeedDialType.ALBUM, SpeedDialType.PLAYLIST, SpeedDialType.ARTIST)
    }
    val pins = dial.filter { it.isPinned || it.id in state.pinnedSpeedDialIds || it.track?.id in state.pinnedSpeedDialIds }
    fun shortcut(track: Track) = SpeedDialItem("track-${track.id}", track.title, SpeedDialType.TRACK, track.thumbnail, track)
    val shortcuts = (if (filtered) emptyList() else pins + if (personalized) {
        state.recentTracks.map { shortcut(it.track) } + state.topPlayedTracks.map { shortcut(it.track) }
    } else dial).distinctBy {
        when (it.type) {
            SpeedDialType.TRACK -> "track:${it.track?.id ?: it.id}"
            SpeedDialType.ALBUM -> "album:${it.album?.id ?: it.id.removePrefix("album-")}"
            else -> "${it.type}:${it.id}"
        }
    }.take(8)
    val used = shortcuts.mapNotNull { it.track?.id }.toMutableSet()
    fun fresh(tracks: List<Track>, limit: Int): List<Track> = tracks.asSequence()
        .filter { it.id.isNotBlank() && it.id !in used }.distinctBy { it.id }.take(limit).toList()
        .also { tracks -> used.addAll(tracks.map { it.id }) }
    val picks = fresh(if (filtered) emptyList() else state.quickPicks, 9)
    val shelves = mutableListOf<DesktopHomeShelf>()
    fun songs(id: String, title: String, tracks: List<Track>, subtitle: String? = null) {
        fresh(tracks, 12).takeIf { it.isNotEmpty() }?.let { shelves += DesktopHomeShelf(id, title, subtitle, tracks = it) }
    }
    if (!filtered) {
        songs("discover", "Discover something new", state.dailyDiscover.map { it.recommendation }, "Fresh finds for your next listen")
        songs("rotation", "Keep listening", state.keepListening, "Songs you come back to")
        state.similarRecommendations.forEachIndexed { index, recommendation ->
            songs("similar-$index", "Explore ${recommendation.seedTitle}", recommendation.items,
                if (recommendation.seedType == RecommendationSeedType.ARTIST) "More from this artist" else "Similar sounds")
        }
        songs("forgotten", "Worth another listen", state.forgottenFavorites)
    }
    val usedAlbums = shortcuts.mapNotNull { it.album?.id }.toMutableSet()
    fun albums(id: String, title: String, values: List<PlaylistResult>, subtitle: String? = null) {
        val unique = values.filter { it.id.isNotBlank() && it.id !in usedAlbums }.distinctBy { it.id }.take(12)
        usedAlbums.addAll(unique.map { it.id })
        if (unique.isNotEmpty()) shelves += DesktopHomeShelf(id, title, subtitle, albums = unique)
    }
    if (!filtered) albums("community", "Mixes to explore", state.communityPlaylists + state.communitySections.map { it.playlist })
    state.dynamicSections.forEachIndexed { index, section ->
        songs("feed-songs-$index", section.title, section.items, section.subtitle)
        albums("feed-albums-$index", section.title, section.albums, section.subtitle)
    }
    return DesktopHomeContent(personalized, shortcuts, picks, shelves)
}
