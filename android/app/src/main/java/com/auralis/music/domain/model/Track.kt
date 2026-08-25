package com.auralis.music.domain.model

enum class TrackSource {
    YOUTUBE,
    LOCAL,
    CURATED
}

data class Track(
    val id: String, // Stream / Video identifier (e.g. YouTube ID)
    val title: String,
    val artist: String,
    val album: String? = null,
    val duration: Long, // in seconds
    val thumbnail: String,
    val source: TrackSource = TrackSource.YOUTUBE,
    val channelTitle: String? = null,
    val views: String? = null,
    val dominantColor: Int? = null // Extracted ARGB Color integer
)
