package com.auralis.music.windows

import androidx.compose.runtime.*
import com.auralis.music.desktop.browse.BrowsePage

internal data class DesktopRoute(val destination: DesktopDestination, val pages: List<BrowsePage>, val searchQuery: String? = null) {
    val key get() = destination.name + (if (destination == DesktopDestination.SEARCH) ":query:${searchQuery.orEmpty()}" else "") + pages.joinToString { page ->
        when (page) {
            is BrowsePage.Album -> "album:${page.album.id}"
            is BrowsePage.Artist -> "artist:${page.page.artist.id}:${page.page.artist.name}"
        }
    }
}

/** Loading metadata updates the current entry, rather than creating another navigation step. */
internal class DesktopNavigation {
    private val entries = mutableStateListOf<DesktopRoute>()
    private var index by mutableStateOf(-1)
    val canGoBack get() = index > 0
    val canGoForward get() = index >= 0 && index < entries.lastIndex
    fun record(route: DesktopRoute) {
        if (entries.getOrNull(index)?.key == route.key) { entries[index] = route; return }
        while (entries.lastIndex > index) entries.removeAt(entries.lastIndex)
        entries += route
        index = entries.lastIndex
    }
    fun back(): DesktopRoute? = if (canGoBack) entries[--index] else null
    fun forward(): DesktopRoute? = if (canGoForward) entries[++index] else null
}
