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

enum class AudioQuality(
    val displayName: String,
    val description: String,
    val targetBitrate: Int
) {
    AUTO("Auto (Adaptive)", "High on Wi-Fi, Standard on Mobile Data", 0),
    HIGH("High Quality", "~160 kbps (High-bitrate Opus)", 160_000),
    STANDARD("Standard", "~128 kbps (AAC / Opus)", 128_000),
    LOW("Data Saver (Low)", "~48–64 kbps (Opus / AAC)", 55_000);

    companion object {
        val MEDIUM = STANDARD // backwards compatibility
    }
}

enum class LyricsMode { SPICY, CINEMA, CLASSIC }
enum class LyricsAlignment { LEFT, CENTER }
enum class FontSize { SMALL, MEDIUM, LARGE }
enum class RepeatMode { OFF, ALL, ONE }

data class PlayerSettings(
    val volume: Float = 1.0f,
    val isMuted: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val audioQuality: AudioQuality = AudioQuality.AUTO,
    val gaplessPlayback: Boolean = true,
    val skipSilence: Boolean = false,
    val spatialAudio: Boolean = false,
    val lyricsFontSize: FontSize = FontSize.MEDIUM,
    val lyricsMode: LyricsMode = LyricsMode.SPICY,
    val lyricsAlignment: LyricsAlignment = LyricsAlignment.LEFT,
    val lyricsDepthBlur: Boolean = true,
    val cloudSyncEnabled: Boolean = true,
    val playbackRate: Float = 1.0f
)
