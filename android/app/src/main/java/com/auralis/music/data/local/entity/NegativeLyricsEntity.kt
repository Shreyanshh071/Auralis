package com.auralis.music.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "negative_lyrics_cache")
data class NegativeLyricsEntity(
    @PrimaryKey
    val trackKey: String,
    val cachedAt: Long = System.currentTimeMillis()
)
