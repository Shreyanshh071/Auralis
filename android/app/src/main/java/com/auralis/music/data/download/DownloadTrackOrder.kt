package com.auralis.music.data.download

import com.auralis.music.domain.model.Track
import org.json.JSONArray
import org.json.JSONObject

/** Device-local ordering for offline collections, independent of the source playlist. */
object DownloadTrackOrder {
    fun supports(playlistId: String): Boolean =
        playlistId == "smart_downloaded_all" || playlistId.startsWith("smart_downloaded_folder_")

    /** Keep saved songs in order; append new downloads in their original order. */
    fun apply(tracks: List<Track>, savedIds: List<String>?): List<Track> {
        if (savedIds.isNullOrEmpty()) return tracks
        val rank = savedIds.withIndex().associate { it.value to it.index }
        return tracks.sortedBy { rank[it.id] ?: Int.MAX_VALUE }
    }

    fun encode(orders: Map<String, List<String>>): String = JSONObject().apply {
        orders.forEach { (id, tracks) -> put(id, JSONArray(tracks)) }
    }.toString()

    fun decode(value: String?): Map<String, List<String>> = runCatching {
        val json = JSONObject(value ?: "{}")
        json.keys().asSequence().filter(::supports).associateWith { id ->
            val ids = json.getJSONArray(id)
            (0 until ids.length()).map { ids.getString(it) }.distinct()
        }
    }.getOrDefault(emptyMap())
}
