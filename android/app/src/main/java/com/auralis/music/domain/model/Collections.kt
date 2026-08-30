package com.auralis.music.domain.model

import androidx.compose.runtime.Immutable

@Immutable
data class Playlist(
    val id: String,
    val title: String,
    val description: String? = null,
    val coverUrl: String? = null,
    val tracks: List<Track> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val isCustom: Boolean = true
)

@Immutable
data class SavedArtist(
    val id: String,
    val name: String,
    val thumbnail: String? = null,
    val subscribers: String? = null,
    val query: String? = null,
    val savedAt: Long = System.currentTimeMillis()
)

@Immutable
data class SavedAlbum(
    val id: String,
    val title: String,
    val artist: String? = null,
    val thumbnail: String? = null,
    val trackCount: Int? = null,
    val savedAt: Long = System.currentTimeMillis()
)
