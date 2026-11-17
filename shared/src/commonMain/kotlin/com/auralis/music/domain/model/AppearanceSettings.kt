package com.auralis.music.domain.model

data class AppearanceSettings(
    // ── Theme ──
    val highRefreshRate: Boolean = true,
    val landscapeScaling: Boolean = true,
    val dynamicTheme: Boolean = true,
    val dynamicIconColors: Boolean = true,
    val appTheme: String = "Follow system",
    val colorPalette: String = "Dynamic",
    /** Liquid glass dock and mini player, with scroll-to-minimize. */
    val liquidGlass: Boolean = false,

    // ── Mini-player ──
    val miniPlayerDesign: String = "New mini player",
    val pureBlackMiniPlayer: Boolean = false,
    val newMiniPlayerDesign: Boolean = true,
    val miniPlayerBackgroundStyle: String = "Live Mesh",

    // ── Player ──
    val playerDesign: String = "Old player",
    val newPlayerDesign: Boolean = false,
    val playerBackgroundStyle: String = "Blur",
    val hidePlayerThumbnail: Boolean = false,
    val cropAlbumArt: Boolean = true,
    val animatedCovers: Boolean = true,
    val animatedCoverSource: String = "AUTO",
    val animatedCoversWifiOnly: Boolean = false,
    /** Lyrics under the cover, chosen from the song menu for each player design. */
    val inlineLyricsNewPlayer: Boolean = false,
    val inlineLyricsOldPlayer: Boolean = true,
    val inlineLyricsImmersive: Boolean = true,
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
    val lyricsAnimation: String = LyricsAnimationMode.SPICY_LYRICS.displayName,
    val enableGlowingLyricsEffect: Boolean = false,
    val standardLyricsBlur: Boolean = true,
    val lyricsTextSize: Float = 22f,
    val lyricsLineSpacing: Float = 1.3f,

    // ── Misc ──
    val defaultOpenTab: String = "Home",
    val defaultLibraryChip: String = "Library",
    val swipeLeftQueueRightPlayNext: Boolean = true,
    val swipeToRemoveSongFromPlaylist: Boolean = false,
    val slimBottomNavigationBar: Boolean = false,
    val listenTogetherInTopBar: Boolean = true,
    val gridCellSize: String = "Small",
    val displayDensity: String = "Native (100%)",

    // ── Auto playlists ──
    val showLikedPlaylist: Boolean = true,
    val showDownloadedPlaylist: Boolean = true,
    val showTopPlaylist: Boolean = false,
    val showWeeklyMostPlaylist: Boolean = false,
    val showMonthlyMostPlaylist: Boolean = false,
    val showCachedPlaylist: Boolean = true,
    val showUploadedPlaylist: Boolean = true
) {
    /** Only Spicy Lyrics keeps the experimental view; every other style uses the Vivi Music port. */
    val shouldUseExperimentalLyrics: Boolean
        get() = LyricsAnimationMode.fromDisplayName(lyricsAnimation) == LyricsAnimationMode.SPICY_LYRICS
}

/** Spicy Lyrics plus the ten lyric styles ported from Vivi Music. */
enum class LyricsAnimationMode(val displayName: String) {
    NONE("None"),
    FADE("Fade"),
    GLOW("Glow"),
    SLIDE("Slide"),
    KARAOKE("Karaoke"),
    APPLE_MUSIC("Apple Music"),
    APPLE_MUSIC_V2("Apple Music (Letter by Letter)"),
    VIVIMUSIC("ViviMusic"),
    LYRICS_V2_FLUID("Lyrics V2 (Fluid)"),
    METRO_LYRICS("MetroLyrics"),
    SPICY_LYRICS("Spicy Lyrics");

    companion object {
        fun fromDisplayName(name: String?): LyricsAnimationMode {
            if (name == null) return VIVIMUSIC
            if (name.equals("Beautiful Lyrics", ignoreCase = true) || name.equals("BEAUTIFUL_LYRICS", ignoreCase = true)) return SPICY_LYRICS
            return entries.firstOrNull { it.displayName.equals(name, ignoreCase = true) || it.name.equals(name, ignoreCase = true) }
                ?: when {
                    // Styles removed with the Vivi port map to their nearest Vivi style.
                    name.contains("auralis (fluid)", ignoreCase = true) -> LYRICS_V2_FLUID
                    name.contains("auralis", ignoreCase = true) || name.contains("default", ignoreCase = true) -> VIVIMUSIC
                    name.contains("letter", ignoreCase = true) || name.contains("apple_v2", ignoreCase = true) -> APPLE_MUSIC_V2
                    name.contains("lyrics_v2", ignoreCase = true) || name.contains("fluid", ignoreCase = true) -> LYRICS_V2_FLUID
                    name.contains("vivi", ignoreCase = true) -> VIVIMUSIC
                    name.contains("spicy", ignoreCase = true) -> SPICY_LYRICS
                    name.contains("metro", ignoreCase = true) -> METRO_LYRICS
                    name.contains("glow", ignoreCase = true) -> GLOW
                    name.contains("apple", ignoreCase = true) -> APPLE_MUSIC
                    name.contains("fade", ignoreCase = true) -> FADE
                    name.contains("none", ignoreCase = true) -> NONE
                    name.contains("karaoke", ignoreCase = true) -> KARAOKE
                    name.contains("slide", ignoreCase = true) -> SLIDE
                    else -> VIVIMUSIC
                }
        }
    }
}

enum class MiniPlayerDesign(val displayName: String) {
    EXPANDED("Expanded mini player"),
    NEW("New mini player"),
    CLASSIC("Classic mini player"),
    MATERIAL3("Material3");

    companion object {
        fun fromDisplayName(name: String?): MiniPlayerDesign {
            if (name == null) return NEW
            return entries.firstOrNull { it.displayName.equals(name, ignoreCase = true) || it.name.equals(name, ignoreCase = true) }
                ?: when {
                    name.contains("expanded", ignoreCase = true) -> EXPANDED
                    name.contains("classic", ignoreCase = true) -> CLASSIC
                    name.contains("material", ignoreCase = true) -> MATERIAL3
                    else -> NEW
                }
        }
    }
}

enum class PlayerDesign(val displayName: String) {
    NEW("New player"),
    OLD("Old player"),
    IMMERSIVE("Immersive player");

    companion object {
        fun fromDisplayName(name: String?): PlayerDesign {
            if (name == null) return NEW
            return entries.firstOrNull { it.displayName.equals(name, ignoreCase = true) || it.name.equals(name, ignoreCase = true) }
                ?: when {
                    name.contains("old", ignoreCase = true) || name.contains("classic", ignoreCase = true) -> OLD
                    name.contains("immersive", ignoreCase = true) -> IMMERSIVE
                    else -> NEW
                }
        }
    }
}

fun AppearanceSettings.inlineLyricsFor(design: PlayerDesign): Boolean = when (design) {
    PlayerDesign.NEW -> inlineLyricsNewPlayer
    PlayerDesign.OLD -> inlineLyricsOldPlayer
    PlayerDesign.IMMERSIVE -> inlineLyricsImmersive
}

fun AppearanceSettings.withInlineLyrics(design: PlayerDesign, shown: Boolean): AppearanceSettings = when (design) {
    PlayerDesign.NEW -> copy(inlineLyricsNewPlayer = shown)
    PlayerDesign.OLD -> copy(inlineLyricsOldPlayer = shown)
    PlayerDesign.IMMERSIVE -> copy(inlineLyricsImmersive = shown)
}
