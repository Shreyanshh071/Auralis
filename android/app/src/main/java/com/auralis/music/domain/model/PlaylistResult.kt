package com.auralis.music.domain.model

import androidx.compose.runtime.Immutable

/**
 * Domain model representing a music playlist or album discovered via search or import.
 */
@Immutable
data class PlaylistResult(
    val id: String,              // YouTube / Invidious Playlist ID (PL..., OLAK...)
    val title: String,           // Playlist title
    val thumbnail: String? = null,
    val author: String? = null,  // Curating channel or artist
    val trackCount: Int? = null  // Total number of songs
)
