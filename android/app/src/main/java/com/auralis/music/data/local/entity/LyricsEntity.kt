package com.auralis.music.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "lyrics_cache")
data class LyricsEntity(
    @PrimaryKey
    val trackId: String,
    val syncType: String,
    val linesJson: String,
    val plainLyrics: String? = null,
    val provider: String,
    val cachedAt: Long = System.currentTimeMillis()
)
