package com.auralis.music.data.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.preferencesDataStore
import com.auralis.music.domain.model.AppearanceSettings
import com.auralis.music.domain.model.LyricsAnimationMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

val Context.appearanceSettingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "auralis_appearance_settings",
    corruptionHandler = ReplaceFileCorruptionHandler {
        emptyPreferences()
    }
)

class AppearanceSettingsDataStore(
    private val context: Context
) {
    private val dataStore = context.appearanceSettingsDataStore

    /**
     * Instantly returns default AppearanceSettings for Frame 0 to guarantee non-blocking startup.
     * Asynchronous updates flow through settingsFlow via StateFlow / collectAsState.
     */
    fun getInitialSettings(): AppearanceSettings {
        return AppearanceSettings()
    }

    companion object {
        // Theme
        val HIGH_REFRESH_RATE = booleanPreferencesKey("high_refresh_rate")
        val LANDSCAPE_SCALING = booleanPreferencesKey("landscape_scaling")
        val DYNAMIC_THEME = booleanPreferencesKey("dynamic_theme")
        val DYNAMIC_ICON_COLORS = booleanPreferencesKey("dynamic_icon_colors")
        val APP_THEME = stringPreferencesKey("app_theme")
        val COLOR_PALETTE = stringPreferencesKey("color_palette")

        // Mini-player
        val MINI_PLAYER_DESIGN = stringPreferencesKey("mini_player_design")
        val PURE_BLACK_MINI_PLAYER = booleanPreferencesKey("pure_black_mini_player")
        val NEW_MINI_PLAYER_DESIGN = booleanPreferencesKey("new_mini_player_design")
        val MINI_PLAYER_BG_STYLE = stringPreferencesKey("mini_player_bg_style")

        // Player
        val NEW_PLAYER_DESIGN = booleanPreferencesKey("new_player_design")
        val PLAYER_BG_STYLE = stringPreferencesKey("player_bg_style")
        val HIDE_PLAYER_THUMBNAIL = booleanPreferencesKey("hide_player_thumbnail")
        val CROP_ALBUM_ART = booleanPreferencesKey("crop_album_art")
        val PLAYER_BUTTON_COLORS = stringPreferencesKey("player_button_colors")
        val PLAYER_SLIDER_STYLE = stringPreferencesKey("player_slider_style")
        val SHOW_DOWNLOAD_BUTTON = booleanPreferencesKey("show_download_button")
        val ENABLE_SWIPE_TO_CHANGE_SONG = booleanPreferencesKey("enable_swipe_to_change_song")
        val MINI_PLAYER_SWIPE_SENSITIVITY = intPreferencesKey("mini_player_swipe_sensitivity")

        // Lyrics
        val EXPERIMENTAL_LYRICS = booleanPreferencesKey("experimental_lyrics")
        val LYRICS_TEXT_POSITION = stringPreferencesKey("lyrics_text_position")
        val RESPECT_AGENT_POSITIONING = booleanPreferencesKey("respect_agent_positioning")
        val CHANGE_LYRICS_ON_TAP = booleanPreferencesKey("change_lyrics_on_tap")
        val AUTO_SCROLL_LYRICS = booleanPreferencesKey("auto_scroll_lyrics")
        val HIDE_STATUS_BAR_ON_FULLSCREEN = booleanPreferencesKey("hide_status_bar_on_fullscreen")
        val LYRICS_ANIMATION = stringPreferencesKey("lyrics_animation")
        val ENABLE_GLOWING_LYRICS = booleanPreferencesKey("enable_glowing_lyrics")
        val STANDARD_LYRICS_BLUR = booleanPreferencesKey("standard_lyrics_blur")
        val LYRICS_TEXT_SIZE = floatPreferencesKey("lyrics_text_size")
        val LYRICS_LINE_SPACING = floatPreferencesKey("lyrics_line_spacing")

        // Misc
        val DEFAULT_OPEN_TAB = stringPreferencesKey("default_open_tab")
        val DEFAULT_LIBRARY_CHIP = stringPreferencesKey("default_library_chip")
        val SWIPE_LEFT_QUEUE_RIGHT_PLAY_NEXT = booleanPreferencesKey("swipe_left_queue_right_play_next")
        val SWIPE_TO_REMOVE_SONG_FROM_PLAYLIST = booleanPreferencesKey("swipe_to_remove_song_from_playlist")
        val SLIM_BOTTOM_NAV_BAR = booleanPreferencesKey("slim_bottom_nav_bar")
        val LISTEN_TOGETHER_IN_TOP_BAR = booleanPreferencesKey("listen_together_in_top_bar")
        val GRID_CELL_SIZE = stringPreferencesKey("grid_cell_size")
        val DISPLAY_DENSITY = stringPreferencesKey("display_density")

        // Auto playlists
        val SHOW_LIKED_PLAYLIST = booleanPreferencesKey("show_liked_playlist")
        val SHOW_DOWNLOADED_PLAYLIST = booleanPreferencesKey("show_downloaded_playlist")
        val SHOW_TOP_PLAYLIST = booleanPreferencesKey("show_top_playlist")
        val SHOW_CACHED_PLAYLIST = booleanPreferencesKey("show_cached_playlist")
        val SHOW_UPLOADED_PLAYLIST = booleanPreferencesKey("show_uploaded_playlist")
    }

    val settingsFlow: Flow<AppearanceSettings> = dataStore.data
        .catch { exception ->
            if (exception is IOException || exception is androidx.datastore.core.CorruptionException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            val legacyExpLyrics = preferences[EXPERIMENTAL_LYRICS] ?: false
            val storedAnimation = preferences[LYRICS_ANIMATION]
            val resolvedAnimation = if (legacyExpLyrics && (storedAnimation == null || storedAnimation == LyricsAnimationMode.AURALIS.displayName)) {
                LyricsAnimationMode.METRO_LYRICS.displayName
            } else {
                storedAnimation ?: LyricsAnimationMode.AURALIS.displayName
            }

            AppearanceSettings(
                highRefreshRate = preferences[HIGH_REFRESH_RATE] ?: true,
                landscapeScaling = preferences[LANDSCAPE_SCALING] ?: false,
                dynamicTheme = preferences[DYNAMIC_THEME] ?: true,
                dynamicIconColors = preferences[DYNAMIC_ICON_COLORS] ?: true,
                appTheme = preferences[APP_THEME] ?: "Follow system",
                colorPalette = preferences[COLOR_PALETTE] ?: "Dynamic",

                miniPlayerDesign = preferences[MINI_PLAYER_DESIGN] ?: run {
                    if (preferences[NEW_MINI_PLAYER_DESIGN] == false) "Classic mini player" else "New mini player"
                },
                pureBlackMiniPlayer = preferences[PURE_BLACK_MINI_PLAYER] ?: false,
                newMiniPlayerDesign = preferences[NEW_MINI_PLAYER_DESIGN] ?: true,
                miniPlayerBackgroundStyle = preferences[MINI_PLAYER_BG_STYLE] ?: "Blur",

                newPlayerDesign = preferences[NEW_PLAYER_DESIGN] ?: true,
                playerBackgroundStyle = preferences[PLAYER_BG_STYLE] ?: "Gradient",
                hidePlayerThumbnail = preferences[HIDE_PLAYER_THUMBNAIL] ?: false,
                cropAlbumArt = preferences[CROP_ALBUM_ART] ?: true,
                playerButtonColors = preferences[PLAYER_BUTTON_COLORS] ?: "Default",
                playerSliderStyle = when (preferences[PLAYER_SLIDER_STYLE] ?: "Wavy") {
                    "Squiggly Waveform", "Squiggly" -> "Squiggly"
                    "Thin Line", "Slim" -> "Slim"
                    "Default" -> "Default"
                    "Wavy", "Neon Glow" -> "Wavy"
                    else -> "Wavy"
                },
                showDownloadButton = preferences[SHOW_DOWNLOAD_BUTTON] ?: true,
                enableSwipeToChangeSong = preferences[ENABLE_SWIPE_TO_CHANGE_SONG] ?: true,
                miniPlayerSwipeSensitivity = preferences[MINI_PLAYER_SWIPE_SENSITIVITY] ?: 73,

                experimentalLyrics = legacyExpLyrics,
                lyricsTextPosition = preferences[LYRICS_TEXT_POSITION] ?: "Centre",
                respectAgentPositioning = preferences[RESPECT_AGENT_POSITIONING] ?: true,
                changeLyricsOnTap = preferences[CHANGE_LYRICS_ON_TAP] ?: true,
                autoScrollLyrics = preferences[AUTO_SCROLL_LYRICS] ?: true,
                hideStatusBarOnFullscreen = preferences[HIDE_STATUS_BAR_ON_FULLSCREEN] ?: false,
                lyricsAnimation = resolvedAnimation,
                enableGlowingLyricsEffect = preferences[ENABLE_GLOWING_LYRICS] ?: false,
                standardLyricsBlur = preferences[STANDARD_LYRICS_BLUR] ?: false,
                lyricsTextSize = preferences[LYRICS_TEXT_SIZE] ?: 22f,
                lyricsLineSpacing = preferences[LYRICS_LINE_SPACING] ?: 1.3f,

                defaultOpenTab = preferences[DEFAULT_OPEN_TAB] ?: "Home",
                defaultLibraryChip = preferences[DEFAULT_LIBRARY_CHIP] ?: "Library",
                swipeLeftQueueRightPlayNext = preferences[SWIPE_LEFT_QUEUE_RIGHT_PLAY_NEXT] ?: false,
                swipeToRemoveSongFromPlaylist = preferences[SWIPE_TO_REMOVE_SONG_FROM_PLAYLIST] ?: false,
                slimBottomNavigationBar = preferences[SLIM_BOTTOM_NAV_BAR] ?: false,
                listenTogetherInTopBar = preferences[LISTEN_TOGETHER_IN_TOP_BAR] ?: true,
                gridCellSize = preferences[GRID_CELL_SIZE] ?: "Small",
                displayDensity = preferences[DISPLAY_DENSITY] ?: "Native (100%)",

                showLikedPlaylist = preferences[SHOW_LIKED_PLAYLIST] ?: true,
                showDownloadedPlaylist = preferences[SHOW_DOWNLOADED_PLAYLIST] ?: true,
                showTopPlaylist = preferences[SHOW_TOP_PLAYLIST] ?: true,
                showCachedPlaylist = preferences[SHOW_CACHED_PLAYLIST] ?: true,
                showUploadedPlaylist = preferences[SHOW_UPLOADED_PLAYLIST] ?: true
            )
        }

    suspend fun updateSettings(settings: AppearanceSettings) {
        try {
            dataStore.edit { preferences ->
                preferences[HIGH_REFRESH_RATE] = settings.highRefreshRate
                preferences[LANDSCAPE_SCALING] = settings.landscapeScaling
                preferences[DYNAMIC_THEME] = settings.dynamicTheme
                preferences[DYNAMIC_ICON_COLORS] = settings.dynamicIconColors
                preferences[APP_THEME] = settings.appTheme
                preferences[COLOR_PALETTE] = settings.colorPalette

                preferences[MINI_PLAYER_DESIGN] = settings.miniPlayerDesign
                preferences[PURE_BLACK_MINI_PLAYER] = settings.pureBlackMiniPlayer
                preferences[NEW_MINI_PLAYER_DESIGN] = (settings.miniPlayerDesign != "Classic mini player")
                preferences[MINI_PLAYER_BG_STYLE] = settings.miniPlayerBackgroundStyle

                preferences[NEW_PLAYER_DESIGN] = settings.newPlayerDesign
                preferences[PLAYER_BG_STYLE] = settings.playerBackgroundStyle
                preferences[HIDE_PLAYER_THUMBNAIL] = settings.hidePlayerThumbnail
                preferences[CROP_ALBUM_ART] = settings.cropAlbumArt
                preferences[PLAYER_BUTTON_COLORS] = settings.playerButtonColors
                preferences[PLAYER_SLIDER_STYLE] = settings.playerSliderStyle
                preferences[SHOW_DOWNLOAD_BUTTON] = settings.showDownloadButton
                preferences[ENABLE_SWIPE_TO_CHANGE_SONG] = settings.enableSwipeToChangeSong
                preferences[MINI_PLAYER_SWIPE_SENSITIVITY] = settings.miniPlayerSwipeSensitivity

                preferences[EXPERIMENTAL_LYRICS] = settings.experimentalLyrics
                preferences[LYRICS_TEXT_POSITION] = settings.lyricsTextPosition
                preferences[RESPECT_AGENT_POSITIONING] = settings.respectAgentPositioning
                preferences[CHANGE_LYRICS_ON_TAP] = settings.changeLyricsOnTap
                preferences[AUTO_SCROLL_LYRICS] = settings.autoScrollLyrics
                preferences[HIDE_STATUS_BAR_ON_FULLSCREEN] = settings.hideStatusBarOnFullscreen
                preferences[LYRICS_ANIMATION] = settings.lyricsAnimation
                preferences[ENABLE_GLOWING_LYRICS] = settings.enableGlowingLyricsEffect
                preferences[STANDARD_LYRICS_BLUR] = settings.standardLyricsBlur
                preferences[LYRICS_TEXT_SIZE] = settings.lyricsTextSize
                preferences[LYRICS_LINE_SPACING] = settings.lyricsLineSpacing

                preferences[DEFAULT_OPEN_TAB] = settings.defaultOpenTab
                preferences[DEFAULT_LIBRARY_CHIP] = settings.defaultLibraryChip
                preferences[SWIPE_LEFT_QUEUE_RIGHT_PLAY_NEXT] = settings.swipeLeftQueueRightPlayNext
                preferences[SWIPE_TO_REMOVE_SONG_FROM_PLAYLIST] = settings.swipeToRemoveSongFromPlaylist
                preferences[SLIM_BOTTOM_NAV_BAR] = settings.slimBottomNavigationBar
                preferences[LISTEN_TOGETHER_IN_TOP_BAR] = settings.listenTogetherInTopBar
                preferences[GRID_CELL_SIZE] = settings.gridCellSize
                preferences[DISPLAY_DENSITY] = settings.displayDensity

                preferences[SHOW_LIKED_PLAYLIST] = settings.showLikedPlaylist
                preferences[SHOW_DOWNLOADED_PLAYLIST] = settings.showDownloadedPlaylist
                preferences[SHOW_TOP_PLAYLIST] = settings.showTopPlaylist
                preferences[SHOW_CACHED_PLAYLIST] = settings.showCachedPlaylist
                preferences[SHOW_UPLOADED_PLAYLIST] = settings.showUploadedPlaylist
            }
        } catch (e: Exception) {
            android.util.Log.e("AppearanceSettings", "Failed to update appearance settings", e)
        }
    }
}
