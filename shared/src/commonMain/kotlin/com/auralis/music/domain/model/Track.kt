package com.auralis.music.domain.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

@Serializable
enum class TrackSource {
    YOUTUBE,
    LOCAL,
    CURATED
}

/**
 * One credited artist as YouTube Music lists it: each artist is its own linked piece of the
 * subtitle, so "Tyler, The Creator" is one artist and "Pritam, Mohit Chauhan" is two.
 */
@Immutable
@Serializable
data class TrackArtist(
    val name: String,
    /** The artist's YouTube channel ("UC..."), when YouTube linked it. */
    val channelId: String? = null
)

@Immutable
@Serializable
data class Track(
    val id: String = "",
    val title: String = "",
    val artist: String = "",
    val album: String? = null,
    val albumId: String? = null,
    val duration: Long = 0L,
    val thumbnail: String = "",
    val source: TrackSource = TrackSource.YOUTUBE,
    val channelTitle: String? = null,
    val views: String? = null,
    val dominantColor: Int? = null, // Extracted ARGB Color integer
    /**
     * The individual artists behind [artist], when the source listed them separately (YouTube
     * Music does). Empty when unknown; [artist] stays the text shown everywhere. Used by Stats
     * and Wrapped to credit each artist on a collab.
     */
    val artists: List<TrackArtist> = emptyList()
) {
    // [artists] is extra detail, not identity: the same song with and without it (a queue copy
    // and a library row, say) must stay equal, as it was before the field existed.
    // Keep these in step with the constructor when adding a field.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Track) return false
        return id == other.id && title == other.title && artist == other.artist && album == other.album &&
            albumId == other.albumId && duration == other.duration && thumbnail == other.thumbnail &&
            source == other.source && channelTitle == other.channelTitle && views == other.views &&
            dominantColor == other.dominantColor
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + title.hashCode()
        result = 31 * result + artist.hashCode()
        result = 31 * result + (album?.hashCode() ?: 0)
        result = 31 * result + (albumId?.hashCode() ?: 0)
        result = 31 * result + duration.hashCode()
        result = 31 * result + thumbnail.hashCode()
        result = 31 * result + source.hashCode()
        result = 31 * result + (channelTitle?.hashCode() ?: 0)
        result = 31 * result + (views?.hashCode() ?: 0)
        result = 31 * result + (dominantColor ?: 0)
        return result
    }
}

/** [Track.artists] as one compact text value, for databases, files, sync and Connect. */
object TrackArtists {
    private const val MAX_ARTISTS = 12

    fun encode(artists: List<TrackArtist>): String? {
        if (artists.isEmpty()) return null
        val array = org.json.JSONArray()
        artists.take(MAX_ARTISTS).forEach { a ->
            array.put(org.json.JSONObject().put("n", a.name.take(120)).apply { a.channelId?.let { put("c", it.take(64)) } })
        }
        return array.toString()
    }

    fun decode(raw: String?): List<TrackArtist> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val array = org.json.JSONArray(raw)
            List(array.length()) { i -> array.optJSONObject(i) }.mapNotNull { o ->
                val name = o?.optString("n")?.trim().orEmpty()
                if (name.isBlank()) null
                else TrackArtist(name, o?.optString("c")?.takeIf { it.isNotBlank() })
            }.take(MAX_ARTISTS).also(com.auralis.music.data.network.ArtistChannels::learn)
        } catch (_: Exception) {
            emptyList()
        }
    }
}
