package com.auralis.music.domain.model

data class PlayCountEntry(
    val trackId: String,
    val count: Int,
    val lastPlayed: Long, // Epoch timestamp ms
    val track: Track
)

data class HistoryEntry(
    val track: Track,
    val playedAt: Long = System.currentTimeMillis()
)

enum class ThemeMode { SYSTEM, LIGHT, DARK, AMOLED }
enum class AudioQuality { AUTO, HIGH, MEDIUM, LOW }
enum class LyricsMode { SPICY, CINEMA, CLASSIC }
enum class LyricsAlignment { LEFT, CENTER }
enum class FontSize { SMALL, MEDIUM, LARGE }
enum class RepeatMode { OFF, ALL, ONE }

data class PlayerSettings(
    val volume: Float = 1.0f,
    val isMuted: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val audioQuality: AudioQuality = AudioQuality.HIGH,
    val lyricsFontSize: FontSize = FontSize.MEDIUM,
    val lyricsMode: LyricsMode = LyricsMode.SPICY,
    val lyricsAlignment: LyricsAlignment = LyricsAlignment.LEFT,
    val lyricsDepthBlur: Boolean = true,
    val cloudSyncEnabled: Boolean = true,
    val playbackRate: Float = 1.0f
)
