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
    val trackName: String? = null,
    val artistName: String? = null,
    val cachedAt: Long = System.currentTimeMillis(),
    /**
     * Whether the cached rows carry genuine per-word timing, decided once on write
     * by `WordTiming` rather than re-guessed from "are there any words" on read.
     */
    val hasWordTiming: Boolean = false,
    /**
     * Semantics version of the parser pipeline that produced [linesJson]. Rows
     * written by an older pipeline are purged once on first launch after an
     * upgrade, instead of the whole table being wiped on every process start.
     */
    val pipelineVersion: Int = 0
)
