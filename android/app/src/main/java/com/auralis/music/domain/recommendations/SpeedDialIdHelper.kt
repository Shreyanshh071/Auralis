package com.auralis.music.domain.recommendations

import com.auralis.music.domain.model.SpeedDialItem

/**
 * Canonical identity and representation helper for Speed Dial items.
 *
 * Distinguishes and reconciles representations of the same underlying track:
 * - raw track.id (e.g., "dQw4w9WgXcQ", "top_1")
 * - track-${id} (e.g., "track-dQw4w9WgXcQ")
 * - track-${id}-${index} (e.g., "track-dQw4w9WgXcQ-0")
 *
 * Ensures track identities never collide with album, artist, or system tile identities.
 */
object SpeedDialIdHelper {

    fun isAlbumId(id: String): Boolean {
        return id.startsWith("album-") || id.startsWith("VL") || id.startsWith("MPREb_") || id.startsWith("OLAK5uy_")
    }

    fun isSystemOrNonTrackId(id: String): Boolean {
        return isAlbumId(id) || id.startsWith("artist-") || id.startsWith("surprise-") || id.startsWith("placeholder-")
    }

    /**
     * Extracts the canonical raw track ID from any track representation.
     * Returns null if the ID is an album, artist, surprise tile, or placeholder.
     */
    fun getCanonicalTrackId(id: String): String? {
        if (isSystemOrNonTrackId(id)) return null

        if (id.startsWith("track-")) {
            val withoutPrefix = id.removePrefix("track-")
            val lastDash = withoutPrefix.lastIndexOf('-')
            if (lastDash > 0) {
                val suffix = withoutPrefix.substring(lastDash + 1)
                if (suffix.isNotEmpty() && suffix.all { it.isDigit() }) {
                    return withoutPrefix.substring(0, lastDash)
                }
            }
            return withoutPrefix
        }

        return id
    }

    /**
     * Checks if a Speed Dial item ID corresponds to a specific raw track ID.
     */
    fun matchesTrack(speedDialId: String, rawTrackId: String): Boolean {
        if (isSystemOrNonTrackId(speedDialId)) return false
        if (speedDialId == rawTrackId) return true
        if (speedDialId == "track-$rawTrackId") return true
        if (speedDialId.startsWith("track-$rawTrackId-")) {
            val suffix = speedDialId.removePrefix("track-$rawTrackId-")
            if (suffix.isNotEmpty() && suffix.all { it.isDigit() }) {
                return true
            }
        }
        val canonical = getCanonicalTrackId(speedDialId)
        val rawCanonical = getCanonicalTrackId(rawTrackId)
        return canonical != null && rawCanonical != null && canonical == rawCanonical
    }

    /**
     * Checks if two IDs refer to the same underlying track.
     * Guaranteed to return false if either ID represents an album or non-track item.
     */
    fun isSameTrack(idA: String, idB: String): Boolean {
        if (isSystemOrNonTrackId(idA) || isSystemOrNonTrackId(idB)) return false
        if (idA == idB) return true
        val canA = getCanonicalTrackId(idA) ?: return false
        val canB = getCanonicalTrackId(idB) ?: return false
        return canA == canB
    }

    /**
     * Formats a canonical track item ID for Speed Dial pinning.
     */
    fun formatTrackItemId(rawTrackId: String): String {
        val canonical = getCanonicalTrackId(rawTrackId) ?: rawTrackId
        return "track-$canonical"
    }

    /**
     * Formats a canonical album item ID for Speed Dial pinning.
     */
    fun formatAlbumItemId(rawAlbumId: String): String {
        val clean = rawAlbumId.removePrefix("album-").removePrefix("VL")
        return "album-$clean"
    }

    /**
     * Computes a deterministic, content-aware key for a Speed Dial page in HorizontalPager.
     * Incorporates pageIndex, ordered item IDs, and their pinned states.
     * Changes whenever page contents or pin states mutate, forcing Compose's LazyLayout
     * to immediately invalidate and recompose the active page subcomposition.
     *
     * Stable across recompositions when items are identical.
     * Free from timestamps, random values, hashCodes, and artwork URLs.
     */
    fun computePageContentKey(pageIndex: Int, pageItems: List<SpeedDialItem>?): String {
        if (pageItems.isNullOrEmpty()) return "speed_dial_page_$pageIndex"
        val itemsSignature = pageItems.joinToString(separator = ",") { item ->
            "${item.id}:${if (item.isPinned) "P" else "U"}"
        }
        return "speed_dial_page_${pageIndex}_$itemsSignature"
    }
}
