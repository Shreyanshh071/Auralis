package com.auralis.music.domain.model

import androidx.compose.runtime.Immutable

/**
 * Domain model representing a music artist / channel.
 */
@Immutable
data class Artist(
    val id: String,              // Channel ID (e.g. UC...) or stable canonical slug
    val name: String,            // Artist name
    val thumbnail: String? = null,
    val subscribers: String? = null,
    val query: String = name     // Search query used to discover artist's top tracks
)
