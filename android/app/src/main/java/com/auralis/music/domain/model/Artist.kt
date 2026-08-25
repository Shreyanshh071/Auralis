package com.auralis.music.domain.model

/**
 * Domain model representing a music artist / channel.
 */
data class Artist(
    val id: String,              // Channel ID (e.g. UC...) or stable canonical slug
    val name: String,            // Artist name
    val thumbnail: String? = null,
    val subscribers: String? = null,
    val query: String = name     // Search query used to discover artist's top tracks
)
