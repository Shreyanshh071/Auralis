package com.auralis.music.domain.recommendations

import com.auralis.music.domain.model.SpeedDialItem
import com.auralis.music.domain.model.SpeedDialType

/** Which pinned Speed Dial tiles are the same item: one song, one album, or the same ID otherwise. */
object SpeedDialPins {
    /** [pinned] with [item] pinned first, replacing any earlier pin of the same item. */
    fun pin(pinned: List<SpeedDialItem>, item: SpeedDialItem): List<SpeedDialItem> {
        val current = pinned.toMutableList()
        current.removeAll { existing ->
            if (item.type == SpeedDialType.TRACK && existing.type == SpeedDialType.TRACK) {
                SpeedDialIdHelper.isSameTrack(existing.id, item.id) ||
                    (item.track != null && existing.track != null && item.track!!.id == existing.track!!.id)
            } else if (item.type == SpeedDialType.ALBUM && existing.type == SpeedDialType.ALBUM) {
                val clean1 = existing.id.removePrefix("album-").removePrefix("VL")
                val clean2 = item.id.removePrefix("album-").removePrefix("VL")
                clean1 == clean2
            } else {
                existing.id == item.id
            }
        }
        current.add(0, item.copy(isPinned = true))
        return current
    }

    /** [pinned] without the pin for [itemId]. */
    fun unpin(pinned: List<SpeedDialItem>, itemId: String): List<SpeedDialItem> =
        pinned.filterNot { matches(it, itemId) }

    fun isPinned(pinned: List<SpeedDialItem>, itemId: String): Boolean = pinned.any { matches(it, itemId) }

    private fun matches(existing: SpeedDialItem, itemId: String): Boolean =
        if (existing.type == SpeedDialType.TRACK) {
            SpeedDialIdHelper.isSameTrack(existing.id, itemId) ||
                (existing.track != null && SpeedDialIdHelper.matchesTrack(itemId, existing.track!!.id))
        } else if (existing.type == SpeedDialType.ALBUM) {
            val cleanId = itemId.removePrefix("album-").removePrefix("VL")
            val existingClean = existing.id.removePrefix("album-").removePrefix("VL")
            existingClean == cleanId || existing.id == itemId || existing.id == "album-$itemId"
        } else if (existing.type == SpeedDialType.PLAYLIST) {
            val cleanId = itemId.removePrefix("playlist-")
            val existingClean = existing.id.removePrefix("playlist-")
            existingClean == cleanId || existing.id == itemId || existing.id == "playlist-$itemId"
        } else {
            existing.id == itemId
        }
}
