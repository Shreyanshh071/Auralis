package com.auralis.music.domain.model

data class AppearanceSettings(
    // ── Theme ──
    val highRefreshRate: Boolean = true,
    val landscapeScaling: Boolean = false,
    val dynamicTheme: Boolean = false,
    val dynamicIconColors: Boolean = true,
    val appTheme: String = "Follow system",
    val colorPalette: String = "Auralis Lime",

    // ── Mini-player ──
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
    val experimentalLyrics: Boolean = true,
    val lyricsTextPosition: String = "Centre",
    val respectAgentPositioning: Boolean = true,
    val changeLyricsOnTap: Boolean = true,
    val autoScrollLyrics: Boolean = true,
    val hideStatusBarOnFullscreen: Boolean = false,

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
