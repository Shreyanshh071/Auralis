package com.auralis.music.domain.model

enum class TrackSource {
    YOUTUBE,
    LOCAL,
    CURATED
}

data class Track(
    val id: String = "",
    val title: String = "",
    val artist: String = "",
    val album: String? = null,
    val duration: Long = 0L,
    val thumbnail: String = "",
    val source: TrackSource = TrackSource.YOUTUBE,
    val channelTitle: String? = null,
    val views: String? = null,
    val dominantColor: Int? = null // Extracted ARGB Color integer
)

