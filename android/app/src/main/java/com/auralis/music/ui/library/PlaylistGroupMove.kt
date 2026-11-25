package com.auralis.music.ui.library

import com.auralis.music.domain.model.Track

/**
 * Moving several selected playlist songs at once. Selected songs always travel as one block and
 * keep their relative order.
 */
internal object PlaylistGroupMove {

    /** Moves the selected [items] (matched by [isSelected]) to the top, in their current order. */
    fun <T> toTop(items: List<T>, isSelected: (T) -> Boolean): List<T> {
        val (picked, rest) = items.partition(isSelected)
        return picked + rest
    }

    /** Moves the selected [items] to the bottom, in their current order. */
    fun <T> toBottom(items: List<T>, isSelected: (T) -> Boolean): List<T> {
        val (picked, rest) = items.partition(isSelected)
        return rest + picked
    }

    /**
     * Splits the selected rows other than [anchor] into the ones above it and below it, so that
     * after a drag they can be put back around the anchor in the same relative order.
     */
    fun <T> splitAroundAnchor(items: List<T>, anchor: T, isSelected: (T) -> Boolean): Pair<List<T>, List<T>> {
        val anchorIndex = items.indexOf(anchor)
        if (anchorIndex < 0) return emptyList<T>() to emptyList()
        val before = items.subList(0, anchorIndex).filter(isSelected)
        val after = items.subList(anchorIndex + 1, items.size).filter { it != anchor && isSelected(it) }
        return before to after
    }

    /** Puts [before] and [after] back on either side of [anchor] in a list that no longer holds them. */
    fun <T> insertAroundAnchor(items: List<T>, anchor: T, before: List<T>, after: List<T>): List<T> {
        val anchorIndex = items.indexOf(anchor)
        if (anchorIndex < 0) return items + before + after
        return items.subList(0, anchorIndex) + before + anchor + after +
            items.subList(anchorIndex + 1, items.size)
    }

    /**
     * Writes a new order of the songs the screen shows back into the full playlist. The playlist
     * screen hides some rows (placeholder "Track N" entries, blank titles); those keep their slots
     * and the shown songs fill the remaining slots in [newVisible] order. Returns null when
     * [newVisible] is not a reordering of the shown songs (the playlist changed underneath).
     */
    fun applyVisibleOrder(all: List<Track>, newVisible: List<Track>, isVisible: (Track) -> Boolean): List<Track>? {
        val visibleSlots = all.indices.filter { isVisible(all[it]) }
        if (visibleSlots.size != newVisible.size) return null
        val currentIds = visibleSlots.map { all[it].id }.sorted()
        if (currentIds != newVisible.map { it.id }.sorted()) return null
        val result = all.toMutableList()
        visibleSlots.forEachIndexed { i, slot -> result[slot] = newVisible[i] }
        return result
    }

    /**
     * Rebuilds the screen's rows for [tracks], giving each song the id its row already had (the
     * first unused row of the same song, so duplicates keep theirs too). Only songs new to the
     * list get a new id. Ticks and list keys therefore survive a reload of the playlist.
     */
    fun reconcile(current: List<PlaylistTrackItem>, tracks: List<Track>): List<PlaylistTrackItem> {
        val pool = HashMap<String, ArrayDeque<PlaylistTrackItem>>()
        current.forEach { pool.getOrPut(it.track.id) { ArrayDeque() }.addLast(it) }
        val used = current.mapTo(HashSet()) { it.instanceId }
        return tracks.mapIndexed { index, track ->
            val reused = pool[track.id]?.removeFirstOrNull()
            when {
                reused == null -> {
                    var id = "${track.id}_$index"
                    var n = 0
                    while (!used.add(id)) id = "${track.id}_${index}_${++n}"
                    PlaylistTrackItem(instanceId = id, track = track)
                }
                reused.track == track -> reused
                else -> reused.copy(track = track)
            }
        }
    }

    /** The same filter the playlist screen uses to decide which rows it shows. */
    fun isShownInPlaylist(track: Track): Boolean =
        !track.title.startsWith("Track ") && track.title.isNotBlank()
}
