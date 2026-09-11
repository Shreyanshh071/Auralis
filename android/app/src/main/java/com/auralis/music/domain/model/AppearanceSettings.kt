package com.auralis.music.domain.model

data class AppearanceSettings(
    // ── Theme ──
    val highRefreshRate: Boolean = true,
    val landscapeScaling: Boolean = false,
    val dynamicTheme: Boolean = true,
    val dynamicIconColors: Boolean = true,
    val appTheme: String = "Follow system",
    val colorPalette: String = "Dynamic",

    // ── Mini-player ──
    val miniPlayerDesign: String = "New mini player",
    val pureBlackMiniPlayer: Boolean = false,
    val newMiniPlayerDesign: Boolean = true,
    val miniPlayerBackgroundStyle: String = "Blur",

    // ── Player ──
    val newPlayerDesign: Boolean = true,
    val playerBackgroundStyle: String = "Blur",
    val hidePlayerThumbnail: Boolean = false,
    val cropAlbumArt: Boolean = true,
    val playerButtonColors: String = "Default",
    val playerSliderStyle: String = "Wavy",
    val showDownloadButton: Boolean = true,
    val enableSwipeToChangeSong: Boolean = true,
    val miniPlayerSwipeSensitivity: Int = 73,

    // ── Lyrics ──
    val experimentalLyrics: Boolean = false,
    val lyricsTextPosition: String = "Centre",
    val respectAgentPositioning: Boolean = true,
    val changeLyricsOnTap: Boolean = true,
    val autoScrollLyrics: Boolean = true,
    val hideStatusBarOnFullscreen: Boolean = false,
    val lyricsAnimation: String = LyricsAnimationMode.AURALIS.displayName,
    val enableGlowingLyricsEffect: Boolean = false,
    val standardLyricsBlur: Boolean = false,
    val lyricsTextSize: Float = 22f,
    val lyricsLineSpacing: Float = 1.3f,

    // ── Misc ──
    val defaultOpenTab: String = "Home",
    val defaultLibraryChip: String = "Library",
    val swipeLeftQueueRightPlayNext: Boolean = false,
    val swipeToRemoveSongFromPlaylist: Boolean = false,
    val slimBottomNavigationBar: Boolean = false,
    val listenTogetherInTopBar: Boolean = true,
    val gridCellSize: String = "Small",
    val displayDensity: String = "Native (100%)",

    // ── Auto playlists ──
    val showLikedPlaylist: Boolean = true,
    val showDownloadedPlaylist: Boolean = true,
    val showTopPlaylist: Boolean = true,
    val showCachedPlaylist: Boolean = true,
    val showUploadedPlaylist: Boolean = true
)

enum class LyricsAnimationMode(val displayName: String) {
    AURALIS("Auralis (Default)"),
    KARAOKE("Karaoke"),
    FADE("Fade"),
    SLIDE("Slide"),
    GLOW("Glow"),
    APPLE_MUSIC_V2("Apple Music (Letter by Letter)"),
    LYRICS_V2_FLUID("Lyrics V2 (Fluid)"),
    METRO_LYRICS("MetroLyrics");

    companion object {
        fun fromDisplayName(name: String?): LyricsAnimationMode {
            if (name == null) return AURALIS
            return entries.firstOrNull { it.displayName.equals(name, ignoreCase = true) || it.name.equals(name, ignoreCase = true) }
                ?: when {
                    name.contains("auralis", ignoreCase = true) || name.contains("default", ignoreCase = true) -> AURALIS
                    name.contains("letter", ignoreCase = true) || name.contains("apple_v2", ignoreCase = true) -> APPLE_MUSIC_V2
                    name.contains("lyrics_v2", ignoreCase = true) -> LYRICS_V2_FLUID
                    name.contains("vivi", ignoreCase = true) -> LYRICS_V2_FLUID
                    name.contains("metro", ignoreCase = true) -> METRO_LYRICS
                    name.contains("karaoke", ignoreCase = true) -> KARAOKE
                    name.contains("slide", ignoreCase = true) -> SLIDE
                    name.contains("glow", ignoreCase = true) -> GLOW
                    name.contains("apple", ignoreCase = true) -> GLOW
                    name.contains("fade", ignoreCase = true) -> FADE
                    name.contains("none", ignoreCase = true) -> AURALIS
                    else -> AURALIS
                }
        }
    }
}

enum class MiniPlayerDesign(val displayName: String) {
    EXPANDED("Expanded mini player"),
    NEW("New mini player"),
    CLASSIC("Classic mini player");

    companion object {
        fun fromDisplayName(name: String?): MiniPlayerDesign {
            if (name == null) return NEW
            return entries.firstOrNull { it.displayName.equals(name, ignoreCase = true) || it.name.equals(name, ignoreCase = true) }
                ?: when {
                    name.contains("expanded", ignoreCase = true) -> EXPANDED
                    name.contains("classic", ignoreCase = true) -> CLASSIC
                    else -> NEW
                }
        }
    }
}
