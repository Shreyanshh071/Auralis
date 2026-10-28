package com.auralis.music.windows.home

import com.auralis.music.domain.model.Track
import com.auralis.music.domain.recommendations.NewUserSeedProvider

/** Guest discovery never pretends starter songs are the listener's history. */
internal fun guestHomeShelves(
    local: List<DesktopHomeShelf>,
    public: List<DesktopHomeShelf>,
    heard: List<Track>,
    related: List<DesktopHomeShelf> = emptyList()
): List<DesktopHomeShelf> {
    val personalized = heard.isNotEmpty()
    val shelves = if (personalized) related + local + public else public + local
    val visible = shelves.filter { it.tracks.isNotEmpty() || it.albums.isNotEmpty() }.distinctBy { it.id }
    if (personalized || visible.sumOf { it.tracks.size + it.albums.size } >= 18) return visible
    val used = visible.flatMap { it.tracks }.map { it.id }.toSet()
    val starters = NewUserSeedProvider.getInitialSeedTracks().filter { it.id !in used }
    val fallback = starters.chunked(12).take(2).mapIndexed { index, tracks ->
        DesktopHomeShelf("guest-starter-$index", if (index == 0) "Start listening" else "More to explore",
            if (index == 0) "Pick a song. Your recommendations grow as you listen." else "Find your next favourite", tracks)
    }
    return fallback + visible
}
