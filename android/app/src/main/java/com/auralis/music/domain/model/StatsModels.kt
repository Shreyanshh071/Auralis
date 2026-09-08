package com.auralis.music.domain.model

enum class OptionStats {
    CONTINUOUS,
    WEEKS,
    MONTHS,
    YEARS
}

enum class StatPeriod {
    WEEK_1,
    MONTH_1,
    MONTH_3,
    MONTH_6,
    YEAR_1,
    ALL
}

data class SongStat(
    val track: Track,
    val playCount: Int,
    val timeListenedMs: Long
)

data class ArtistStat(
    val name: String,
    val thumbnailUrl: String?,
    val songsPlayedCount: Int,
    val timeListenedMs: Long
)

data class StatsOverview(
    val totalPlayTimeMs: Long,
    val songsCount: Int,
    val artistsCount: Int,
    val albumsCount: Int
)
