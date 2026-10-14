package com.auralis.music.windows.home

import com.auralis.music.domain.model.*
import com.auralis.music.ui.viewmodel.HomeUiState

internal data class DesktopHomeShelf(
    val id: String, val title: String, val subtitle: String? = null,
    val tracks: List<Track> = emptyList(), val albums: List<PlaylistResult> = emptyList(),
    val order: List<String> = emptyList()
)

internal data class DesktopHomeContent(
    val personalized: Boolean,
    val shortcuts: List<SpeedDialItem>,
    val picks: List<Track>,
    val shelves: List<DesktopHomeShelf>,
    val recents: List<Track> = emptyList()
)

/** Keep starter content out of personal rotation and avoid repeating the same song on every shelf. */
internal fun desktopHomeContent(
    state: HomeUiState,
    moreSections: List<HomeSection> = emptyList(),
    tasteShelf: DesktopHomeShelf? = null
): DesktopHomeContent {
    val personalized = state.recentTracks.isNotEmpty() || state.topPlayedTracks.isNotEmpty()
    val filtered = state.selectedChip != null
    val dial = state.speedDialPages.flatten().filter {
        it.type in setOf(SpeedDialType.TRACK, SpeedDialType.ALBUM, SpeedDialType.PLAYLIST, SpeedDialType.ARTIST)
    }
    val pins = (state.pinnedItems + dial).filter { it.isPinned || it.id in state.pinnedSpeedDialIds || it.track?.id in state.pinnedSpeedDialIds }
    val recents = if (filtered) emptyList() else com.auralis.music.domain.recommendations.TrackDeduplicator.deduplicateTracks(
        state.recentTracks.map { it.track }.filter { it.id.isNotBlank() }
    ).take(24)
    val shortcuts = (if (filtered) emptyList() else if (personalized) pins else dial).distinctBy {
        when (it.type) {
            SpeedDialType.TRACK -> "track:${it.track?.id ?: it.id}"
            SpeedDialType.ALBUM -> "album:${it.album?.id ?: it.id.removePrefix("album-")}"
            else -> "${it.type}:${it.id}"
        }
    }.take(8)
    val used = (shortcuts.mapNotNull { it.track?.id } + recents.map { it.id }).toMutableSet()
    fun fresh(tracks: List<Track>, limit: Int): List<Track> = tracks.asSequence()
        .filter { it.id.isNotBlank() && it.id !in used }.distinctBy { it.id }.take(limit).toList()
        .also { tracks -> used.addAll(tracks.map { it.id }) }
    val picks = fresh(if (filtered) emptyList() else state.quickPicks, 9)
    val shelves = mutableListOf<DesktopHomeShelf>()
    val usedAlbums = shortcuts.mapNotNull { it.album?.id }.toMutableSet()
    var tasteInserted = false
    fun feed(values: List<HomeSection>, prefix: String) = values.forEachIndexed { index, section ->
        val isReleases = section.title.equals("Albums & singles", ignoreCase = true) ||
            section.title.equals("New releases", ignoreCase = true) ||
            section.title.equals("New albums & singles", ignoreCase = true)
        if (isReleases && tasteShelf != null && tasteShelf.albums.isNotEmpty()) {
            if (!tasteInserted) {
                shelves += tasteShelf
                tasteInserted = true
            }
            return@forEachIndexed
        }
        val tracks = fresh(section.items, 24)
        val albums = section.albums.filter { it.id.isNotBlank() && it.id !in usedAlbums }.distinctBy { it.id }.take(24)
        usedAlbums.addAll(albums.map { it.id })
        if (tracks.isNotEmpty() || albums.isNotEmpty()) shelves += DesktopHomeShelf(
            "$prefix-$index", section.title, section.subtitle, tracks, albums)
    }
    feed(state.dynamicSections, "feed")
    if (!tasteInserted && tasteShelf != null && tasteShelf.albums.isNotEmpty() && !filtered) {
        shelves.add(0, tasteShelf)
        tasteInserted = true
    }
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
    fun albums(id: String, title: String, values: List<PlaylistResult>, subtitle: String? = null) {
        val unique = values.filter { it.id.isNotBlank() && it.id !in usedAlbums }.distinctBy { it.id }.take(12)
        usedAlbums.addAll(unique.map { it.id })
        if (unique.isNotEmpty()) shelves += DesktopHomeShelf(id, title, subtitle, albums = unique)
    }
    if (!filtered) albums("community", "Mixes to explore", state.communityPlaylists + state.communitySections.map { it.playlist })
    // Append below existing shelves so LazyColumn retains the reading position instead of
    // anchoring the user at the bottom and eagerly requesting every remaining page.
    feed(moreSections, "more")
    return DesktopHomeContent(personalized, shortcuts, picks, shelves, recents)
}
