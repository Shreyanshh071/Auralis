package com.auralis.music.data.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.auralis.music.domain.model.AppearanceSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

val Context.appearanceSettingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "auralis_appearance_settings")

class AppearanceSettingsDataStore(
    private val context: Context
) {
    private val dataStore = context.appearanceSettingsDataStore

    companion object {
        // Theme
        val HIGH_REFRESH_RATE = booleanPreferencesKey("high_refresh_rate")
        val LANDSCAPE_SCALING = booleanPreferencesKey("landscape_scaling")
        val DYNAMIC_THEME = booleanPreferencesKey("dynamic_theme")
        val DYNAMIC_ICON_COLORS = booleanPreferencesKey("dynamic_icon_colors")
        val APP_THEME = stringPreferencesKey("app_theme")
        val COLOR_PALETTE = stringPreferencesKey("color_palette")

        // Mini-player
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
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            AppearanceSettings(
                highRefreshRate = preferences[HIGH_REFRESH_RATE] ?: true,
                landscapeScaling = preferences[LANDSCAPE_SCALING] ?: false,
                dynamicTheme = preferences[DYNAMIC_THEME] ?: false,
                dynamicIconColors = preferences[DYNAMIC_ICON_COLORS] ?: true,
                appTheme = preferences[APP_THEME] ?: "Follow system",
                colorPalette = preferences[COLOR_PALETTE] ?: "Auralis Lime",

                newMiniPlayerDesign = preferences[NEW_MINI_PLAYER_DESIGN] ?: true,
                miniPlayerBackgroundStyle = preferences[MINI_PLAYER_BG_STYLE] ?: "Blur",

                newPlayerDesign = preferences[NEW_PLAYER_DESIGN] ?: true,
                playerBackgroundStyle = preferences[PLAYER_BG_STYLE] ?: "Blur",
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

                experimentalLyrics = preferences[EXPERIMENTAL_LYRICS] ?: true,
                lyricsTextPosition = preferences[LYRICS_TEXT_POSITION] ?: "Centre",
                respectAgentPositioning = preferences[RESPECT_AGENT_POSITIONING] ?: true,
                changeLyricsOnTap = preferences[CHANGE_LYRICS_ON_TAP] ?: true,
                autoScrollLyrics = preferences[AUTO_SCROLL_LYRICS] ?: true,
                hideStatusBarOnFullscreen = preferences[HIDE_STATUS_BAR_ON_FULLSCREEN] ?: false,

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
        dataStore.edit { preferences ->
            preferences[HIGH_REFRESH_RATE] = settings.highRefreshRate
            preferences[LANDSCAPE_SCALING] = settings.landscapeScaling
            preferences[DYNAMIC_THEME] = settings.dynamicTheme
            preferences[DYNAMIC_ICON_COLORS] = settings.dynamicIconColors
            preferences[APP_THEME] = settings.appTheme
            preferences[COLOR_PALETTE] = settings.colorPalette

            preferences[NEW_MINI_PLAYER_DESIGN] = settings.newMiniPlayerDesign
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
    }
}
