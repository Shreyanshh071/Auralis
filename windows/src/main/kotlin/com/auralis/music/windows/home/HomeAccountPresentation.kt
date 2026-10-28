package com.auralis.music.windows.home

import com.auralis.music.domain.model.Track
import com.auralis.music.domain.recommendations.TrackDeduplicator

internal data class AccountHomeFeed(val personal: Boolean, val recents: List<Track>, val shelves: List<DesktopHomeShelf>)

/** Account connections supply content; neither connection changes Home's layout. */
internal class HomeAccountPresentation {
    private var lastRecents = emptyList<Track>()
    private var lastShelves = emptyList<DesktopHomeShelf>()

    fun present(auralis: Boolean, youtube: Boolean, recents: List<Track>, shelves: List<DesktopHomeShelf>): AccountHomeFeed {
        if (!auralis && !youtube) {
            lastRecents = emptyList()
            lastShelves = emptyList()
            return AccountHomeFeed(false, emptyList(), emptyList())
        }
        lastRecents = TrackDeduplicator.deduplicateTracks(recents + lastRecents).take(40)
        lastShelves = (shelves + lastShelves).distinctBy { it.title.lowercase() }
        return AccountHomeFeed(true, lastRecents, lastShelves)
    }
}
