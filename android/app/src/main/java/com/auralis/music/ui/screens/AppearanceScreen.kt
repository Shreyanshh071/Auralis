package com.auralis.music.ui.screens

import com.auralis.music.R
import com.auralis.music.ui.i18n.str

import android.view.HapticFeedbackConstants
import android.widget.Toast
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalView
import kotlin.math.PI
import kotlin.math.sin
import androidx.compose.material.icons.filled.AlignHorizontalLeft
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Cached
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.ColorLens
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FormatAlignCenter
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.HideImage
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LinearScale
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Swipe
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VerticalAlignBottom
import androidx.compose.material.icons.filled.ViewCompact
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auralis.music.data.datastore.AppearanceSettingsDataStore
import com.auralis.music.domain.model.AppearanceSettings
import com.auralis.music.ui.player.PlayerBackgroundStyle
import com.auralis.music.ui.theme.AuralisPushedPage
import com.auralis.music.ui.theme.auralisPushParent
import com.auralis.music.ui.theme.dynamicBackground
import com.auralis.music.ui.theme.rememberPushProgress
import com.auralis.music.ui.theme.dynamicPrimary
import com.auralis.music.ui.theme.dynamicSurface
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import com.auralis.music.ui.components.bottomChromePadding

private val APPEARANCE_BG = Color(0xFF13110E)
private val CARD_BG = Color(0xFF201B17)
private val ICON_BOX_BG = Color(0xFF2C251F)
private val ACCENT_TAN = Color(0xFFEBA671)
private val TEXT_SUBTITLE = Color(0xFFA59B90)

@Composable
fun AppearanceScreen(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dataStore = remember { AppearanceSettingsDataStore(context.applicationContext) }
    val initialSettings = com.auralis.music.ui.theme.LocalAppearanceSettings.current
    val settings by dataStore.settingsFlow.collectAsState(initial = initialSettings)

    fun update(transform: AppearanceSettings.() -> AppearanceSettings) {
        scope.launch {
            val newSettings = settings.transform()
            dataStore.updateSettings(newSettings)
        }
    }

    var activeDialog by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<AppearanceDialogType?>(null) }
    var showThemeAndColors by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }

    androidx.activity.compose.BackHandler(enabled = true) {
        if (showThemeAndColors) {
            showThemeAndColors = false
        } else if (activeDialog != null) {
            activeDialog = null
        } else {
            onDismiss()
        }
    }

    LaunchedEffect(settings.playerBackgroundStyle) {
        if (PlayerBackgroundStyle.fromKey(settings.playerBackgroundStyle) == PlayerBackgroundStyle.APPLE_MUSIC) {
            update { copy(playerBackgroundStyle = PlayerBackgroundStyle.GRADIENT.displayName) }
        }
    }

    LaunchedEffect(settings.experimentalLyrics) {
        if (settings.experimentalLyrics) {
            update {
                copy(
                    experimentalLyrics = false,
                    lyricsAnimation = com.auralis.music.domain.model.LyricsAnimationMode.METRO_LYRICS.displayName
                )
            }
        }
    }

    val primaryColor = MaterialTheme.dynamicPrimary
    val backgroundColor = MaterialTheme.dynamicBackground
    val surfaceColor = MaterialTheme.dynamicSurface
    val onBackground = MaterialTheme.colorScheme.onBackground
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant
    val onSurface = MaterialTheme.colorScheme.onSurface
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    val outlineVariant = MaterialTheme.colorScheme.outlineVariant
    val outline = MaterialTheme.colorScheme.outline
    val onPrimary = MaterialTheme.colorScheme.onPrimary

    // Theme & colours pushes over this page instead of replacing it on one frame.
    val themePush = rememberPushProgress(showThemeAndColors)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(backgroundColor)
            .auralisPushParent(themePush)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ── TOP APP BAR ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = str(R.string.back),
                        tint = onBackground
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = str(R.string.appearance),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = onBackground,
                    fontSize = 22.sp
                )
            }

            // ── SETTINGS SCROLLABLE LIST ──
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                contentPadding = bottomChromePadding(includeNavigationBar = false),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // ════ 1. THEME ════
                item(key = "hdr_theme") { AppearanceSectionHeader(title = str(R.string.theme), color = primaryColor) }
                item(key = "item_theme_colors") {
                    AppearanceClickableItem(
                        icon = Icons.Default.Palette,
                        title = str(R.string.theme_colors),
                        subtitle = "${com.auralis.music.ui.i18n.UiLabels.of(settings.appTheme)} • ${com.auralis.music.ui.i18n.UiLabels.of(settings.colorPalette)}",
                        primaryColor = primaryColor,
                        surfaceColor = surfaceColor,
                        onSurface = onSurface,
                        onSurfaceVariant = onSurfaceVariant,
                        outlineVariant = outlineVariant,
                        onClick = { showThemeAndColors = true }
                    )
                }
                item(key = "item_high_refresh") {
                    AppearanceSwitchItem(
                        icon = Icons.Default.Speed,
                        title = str(R.string.enable_high_refresh_rate),
                        subtitle = str(R.string.forces_the_display_to_run_at_its_highest),
                        isChecked = settings.highRefreshRate,
                        primaryColor = primaryColor,
                        surfaceColor = surfaceColor,
                        onSurface = onSurface,
                        onSurfaceVariant = onSurfaceVariant,
                        outlineVariant = outlineVariant,
                        onPrimary = onPrimary,
                        outline = outline,
                        surfaceVariant = surfaceVariant,
                        onCheckedChange = { update { copy(highRefreshRate = it) } }
                    )
                }
                item(key = "item_landscape_scaling") {
                    AppearanceSwitchItem(
                        icon = Icons.Default.AspectRatio,
                        title = str(R.string.landscape_scaling),
                        subtitle = str(R.string.scale_ui_in_landscape_mode_for_larger_sc),
                        isChecked = settings.landscapeScaling,
                        primaryColor = primaryColor,
                        surfaceColor = surfaceColor,
                        onSurface = onSurface,
                        onSurfaceVariant = onSurfaceVariant,
                        outlineVariant = outlineVariant,
                        onPrimary = onPrimary,
                        outline = outline,
                        surfaceVariant = surfaceVariant,
                        onCheckedChange = { update { copy(landscapeScaling = it) } }
                    )
                }
                item(key = "item_dynamic_icons") {
                    AppearanceSwitchItem(
                        icon = Icons.Default.ColorLens,
                        title = str(R.string.dynamic_icon_colors),
                        subtitle = str(R.string.use_dynamic_theme_colors_for_the_app_ico),
                        isChecked = settings.dynamicIconColors,
                        primaryColor = primaryColor,
                        surfaceColor = surfaceColor,
                        onSurface = onSurface,
                        onSurfaceVariant = onSurfaceVariant,
                        outlineVariant = outlineVariant,
                        onPrimary = onPrimary,
                        outline = outline,
                        surfaceVariant = surfaceVariant,
                        onCheckedChange = { update { copy(dynamicIconColors = it) } }
                    )
                }

                // ════ 2. MINI-PLAYER ════
                item(key = "hdr_mini_player") { AppearanceSectionHeader(title = str(R.string.mini_player), color = primaryColor) }
                item(key = "item_mini_design") {
                    AppearanceClickableItem(
                        icon = Icons.Default.PictureInPictureAlt,
                        title = str(R.string.mini_player_design),
                        subtitle = com.auralis.music.ui.i18n.UiLabels.of(settings.miniPlayerDesign),
                        primaryColor = primaryColor,
                        surfaceColor = surfaceColor,
                        onSurface = onSurface,
                        onSurfaceVariant = onSurfaceVariant,
                        outlineVariant = outlineVariant,
                        onClick = { activeDialog = AppearanceDialogType.MINI_PLAYER_DESIGN }
                    )
                }
                item(key = "item_pure_black_mini") {
                    AppearanceSwitchItem(
                        icon = Icons.Default.DarkMode,
                        title = str(R.string.pure_black_mini_player),
                        subtitle = str(R.string.force_deep_amoled_black_background_on_mi),
                        isChecked = settings.pureBlackMiniPlayer,
                        primaryColor = primaryColor,
                        surfaceColor = surfaceColor,
                        onSurface = onSurface,
                        onSurfaceVariant = onSurfaceVariant,
                        outlineVariant = outlineVariant,
                        onPrimary = onPrimary,
                        outline = outline,
                        surfaceVariant = surfaceVariant,
                        onCheckedChange = { update { copy(pureBlackMiniPlayer = it) } }
                    )
                }
                item(key = "item_mini_player_bg") {
                    val isPureBlackActive = settings.pureBlackMiniPlayer && settings.miniPlayerDesign != "Expanded mini player"
                    AppearanceClickableItem(
                        icon = Icons.Default.GridView,
                        title = str(R.string.mini_player_background_style),
                        subtitle = if (isPureBlackActive) str(R.string.unavailable_when_pure_black_is_enabled) else PlayerBackgroundStyle.fromKey(settings.miniPlayerBackgroundStyle).displayName,
                        primaryColor = primaryColor,
                        surfaceColor = surfaceColor,
                        onSurface = onSurface,
                        onSurfaceVariant = onSurfaceVariant,
                        outlineVariant = outlineVariant,
                        onClick = {
                            if (!isPureBlackActive) {
                                activeDialog = AppearanceDialogType.MINI_PLAYER_BG
                            }
                        }
                    )
                }

                // ════ 3. PLAYER ════
                item(key = "hdr_player") { AppearanceSectionHeader(title = str(R.string.player), color = primaryColor) }
                item(key = "item_new_player") {
                    AppearanceSwitchItem(
                        icon = Icons.Default.ColorLens,
                        title = str(R.string.new_player_design),
                        subtitle = str(R.string.modern_expanded_now_playing_screen_with),
                        isChecked = settings.newPlayerDesign,
                        primaryColor = primaryColor,
                        surfaceColor = surfaceColor,
                        onSurface = onSurface,
                        onSurfaceVariant = onSurfaceVariant,
                        outlineVariant = outlineVariant,
                        onPrimary = onPrimary,
                        outline = outline,
                        surfaceVariant = surfaceVariant,
                        onCheckedChange = { update { copy(newPlayerDesign = it) } }
                    )
                }
                item(key = "item_player_bg") {
                    val resolvedPlayerBg = PlayerBackgroundStyle.fromKey(settings.playerBackgroundStyle).let {
                        if (it == PlayerBackgroundStyle.APPLE_MUSIC) PlayerBackgroundStyle.GRADIENT else it
                    }
                    AppearanceClickableItem(
                        icon = Icons.Default.GridView,
                        title = str(R.string.player_background_style),
                        subtitle = com.auralis.music.ui.i18n.UiLabels.of(resolvedPlayerBg.displayName),
                        primaryColor = primaryColor,
                        surfaceColor = surfaceColor,
                        onSurface = onSurface,
                        onSurfaceVariant = onSurfaceVariant,
                        outlineVariant = outlineVariant,
                        onClick = { activeDialog = AppearanceDialogType.PLAYER_BG }
                    )
                }
                item(key = "item_player_slider_style") {
                    AppearanceClickableItem(
                        icon = Icons.Default.LinearScale,
                        title = str(R.string.player_slider_style),
                        subtitle = com.auralis.music.ui.i18n.UiLabels.of(settings.playerSliderStyle),
                        primaryColor = primaryColor,
                        surfaceColor = surfaceColor,
                        onSurface = onSurface,
                        onSurfaceVariant = onSurfaceVariant,
                        outlineVariant = outlineVariant,
                        onClick = { activeDialog = AppearanceDialogType.PLAYER_SLIDER_STYLE }
                    )
                }
                item(key = "item_swipe_change_song") {
                    AppearanceSwitchItem(
                        icon = Icons.Default.Swipe,
                        title = str(R.string.enable_swipe_to_change_song),
                        subtitle = str(R.string.swipe_horizontally_across_player_to_skip),
                        isChecked = settings.enableSwipeToChangeSong,
                        primaryColor = primaryColor,
                        surfaceColor = surfaceColor,
                        onSurface = onSurface,
                        onSurfaceVariant = onSurfaceVariant,
                        outlineVariant = outlineVariant,
                        onPrimary = onPrimary,
                        outline = outline,
                        surfaceVariant = surfaceVariant,
                        onCheckedChange = { update { copy(enableSwipeToChangeSong = it) } }
                    )
                }

                // ════ 4. LYRICS ════
                item(key = "hdr_lyrics") { AppearanceSectionHeader(title = str(R.string.lyrics), color = primaryColor) }
                item(key = "item_lyrics_text_position") {
                    AppearanceClickableItem(
                        icon = Icons.Default.FormatAlignCenter,
                        title = str(R.string.lyrics_text_position),
                        subtitle = com.auralis.music.ui.i18n.UiLabels.of(settings.lyricsTextPosition),
                        primaryColor = primaryColor,
                        surfaceColor = surfaceColor,
                        onSurface = onSurface,
                        onSurfaceVariant = onSurfaceVariant,
                        outlineVariant = outlineVariant,
                        onClick = { activeDialog = AppearanceDialogType.LYRICS_TEXT_POSITION }
                    )
                }
                item(key = "item_lyrics_animation") {
                    AppearanceClickableItem(
                        icon = Icons.Default.GraphicEq,
                        title = str(R.string.lyrics_animation),
                        subtitle = com.auralis.music.ui.i18n.UiLabels.of(com.auralis.music.domain.model.LyricsAnimationMode.fromDisplayName(settings.lyricsAnimation).displayName),
                        primaryColor = primaryColor,
                        surfaceColor = surfaceColor,
                        onSurface = onSurface,
                        onSurfaceVariant = onSurfaceVariant,
                        outlineVariant = outlineVariant,
                        onClick = { activeDialog = AppearanceDialogType.LYRICS_ANIMATION }
                    )
                }
                item(key = "item_lyrics_blur") {
                    AppearanceSwitchItem(
                        icon = Icons.Default.HideImage,
                        title = str(R.string.standard_lyrics_blur),
                        subtitle = str(R.string.apply_soft_blur_focus_to_inactive_lyrics),
                        isChecked = settings.standardLyricsBlur,
                        primaryColor = primaryColor,
                        surfaceColor = surfaceColor,
                        onSurface = onSurface,
                        onSurfaceVariant = onSurfaceVariant,
                        outlineVariant = outlineVariant,
                        onPrimary = onPrimary,
                        outline = outline,
                        surfaceVariant = surfaceVariant,
                        onCheckedChange = { update { copy(standardLyricsBlur = it) } }
                    )
                }
                item(key = "item_lyrics_size") {
                    AppearanceClickableItem(
                        icon = Icons.Default.AspectRatio,
                        title = str(R.string.lyrics_text_size),
                        subtitle = str(R.string.x_sp, settings.lyricsTextSize.roundToInt()),
                        primaryColor = primaryColor,
                        surfaceColor = surfaceColor,
                        onSurface = onSurface,
                        onSurfaceVariant = onSurfaceVariant,
                        outlineVariant = outlineVariant,
                        onClick = { activeDialog = AppearanceDialogType.LYRICS_TEXT_SIZE }
                    )
                }
                item(key = "item_lyrics_spacing") {
                    AppearanceClickableItem(
                        icon = Icons.Default.LinearScale,
                        title = str(R.string.lyrics_line_spacing),
                        subtitle = "${String.format(java.util.Locale.US, "%.1f", settings.lyricsLineSpacing)}x",
                        primaryColor = primaryColor,
                        surfaceColor = surfaceColor,
                        onSurface = onSurface,
                        onSurfaceVariant = onSurfaceVariant,
                        outlineVariant = outlineVariant,
                        onClick = { activeDialog = AppearanceDialogType.LYRICS_LINE_SPACING }
                    )
                }
                item(key = "item_lyrics_tap") {
                    AppearanceSwitchItem(
                        icon = Icons.Default.TouchApp,
                        title = str(R.string.change_lyrics_on_click),
                        subtitle = str(R.string.seek_track_playback_to_the_clicked_lyric),
                        isChecked = settings.changeLyricsOnTap,
                        primaryColor = primaryColor,
                        surfaceColor = surfaceColor,
                        onSurface = onSurface,
                        onSurfaceVariant = onSurfaceVariant,
                        outlineVariant = outlineVariant,
                        onPrimary = onPrimary,
                        outline = outline,
                        surfaceVariant = surfaceVariant,
                        onCheckedChange = { update { copy(changeLyricsOnTap = it) } }
                    )
                }
                item(key = "item_lyrics_autoscroll") {
                    AppearanceSwitchItem(
                        icon = Icons.Default.VerticalAlignBottom,
                        title = str(R.string.auto_scroll_lyrics),
                        subtitle = str(R.string.automatically_keep_the_active_lyric_cent),
                        isChecked = settings.autoScrollLyrics,
                        primaryColor = primaryColor,
                        surfaceColor = surfaceColor,
                        onSurface = onSurface,
                        onSurfaceVariant = onSurfaceVariant,
                        outlineVariant = outlineVariant,
                        onPrimary = onPrimary,
                        outline = outline,
                        surfaceVariant = surfaceVariant,
                        onCheckedChange = { update { copy(autoScrollLyrics = it) } }
                    )
                }

                // ════ 5. MISC ════
                item(key = "hdr_misc") { AppearanceSectionHeader(title = str(R.string.misc), color = primaryColor) }
                item(key = "item_swipe_queue") {
                    AppearanceSwitchItem(
                        icon = Icons.Default.Swipe,
                        title = str(R.string.swipe_left_to_add_the_song_to_the_queue),
                        subtitle = null,
                        isChecked = settings.swipeLeftQueueRightPlayNext,
                        primaryColor = primaryColor,
                        surfaceColor = surfaceColor,
                        onSurface = onSurface,
                        onSurfaceVariant = onSurfaceVariant,
                        outlineVariant = outlineVariant,
                        onPrimary = onPrimary,
                        outline = outline,
                        surfaceVariant = surfaceVariant,
                        onCheckedChange = { update { copy(swipeLeftQueueRightPlayNext = it) } }
                    )
                }
                item(key = "item_swipe_remove") {
                    AppearanceSwitchItem(
                        icon = Icons.Default.Swipe,
                        title = str(R.string.swipe_to_remove_the_song_from_the_playli),
                        subtitle = null,
                        isChecked = settings.swipeToRemoveSongFromPlaylist,
                        primaryColor = primaryColor,
                        surfaceColor = surfaceColor,
                        onSurface = onSurface,
                        onSurfaceVariant = onSurfaceVariant,
                        outlineVariant = outlineVariant,
                        onPrimary = onPrimary,
                        outline = outline,
                        surfaceVariant = surfaceVariant,
                        onCheckedChange = { update { copy(swipeToRemoveSongFromPlaylist = it) } }
                    )
                }
                item(key = "item_display_density") {
                    AppearanceClickableItem(
                        icon = Icons.Default.ViewModule,
                        title = str(R.string.display_density),
                        subtitle = com.auralis.music.ui.i18n.UiLabels.of(settings.displayDensity),
                        primaryColor = primaryColor,
                        surfaceColor = surfaceColor,
                        onSurface = onSurface,
                        onSurfaceVariant = onSurfaceVariant,
                        outlineVariant = outlineVariant,
                        onClick = { activeDialog = AppearanceDialogType.DISPLAY_DENSITY }
                    )
                }

                // ════ 6. AUTO PLAYLISTS ════
                item(key = "hdr_auto_playlists") { AppearanceSectionHeader(title = str(R.string.auto_playlists), color = primaryColor) }
                item(key = "item_show_liked") {
                    AppearanceSwitchItem(
                        icon = Icons.Default.Favorite,
                        title = str(R.string.show_liked_playlist),
                        subtitle = null,
                        isChecked = settings.showLikedPlaylist,
                        primaryColor = primaryColor,
                        surfaceColor = surfaceColor,
                        onSurface = onSurface,
                        onSurfaceVariant = onSurfaceVariant,
                        outlineVariant = outlineVariant,
                        onPrimary = onPrimary,
                        outline = outline,
                        surfaceVariant = surfaceVariant,
                        onCheckedChange = { update { copy(showLikedPlaylist = it) } }
                    )
                }
                item(key = "item_show_downloaded") {
                    AppearanceSwitchItem(
                        icon = Icons.Default.CheckCircle,
                        title = str(R.string.show_downloaded_playlist),
                        subtitle = null,
                        isChecked = settings.showDownloadedPlaylist,
                        primaryColor = primaryColor,
                        surfaceColor = surfaceColor,
                        onSurface = onSurface,
                        onSurfaceVariant = onSurfaceVariant,
                        outlineVariant = outlineVariant,
                        onPrimary = onPrimary,
                        outline = outline,
                        surfaceVariant = surfaceVariant,
                        onCheckedChange = { update { copy(showDownloadedPlaylist = it) } }
                    )
                }
                item(key = "item_show_top_most_played") {
                    AppearanceSwitchItem(
                        icon = Icons.Default.TrendingUp,
                        title = str(R.string.show_top_most_played_playlist),
                        subtitle = null,
                        isChecked = settings.showTopPlaylist,
                        primaryColor = primaryColor,
                        surfaceColor = surfaceColor,
                        onSurface = onSurface,
                        onSurfaceVariant = onSurfaceVariant,
                        outlineVariant = outlineVariant,
                        onPrimary = onPrimary,
                        outline = outline,
                        surfaceVariant = surfaceVariant,
                        onCheckedChange = { update { copy(showTopPlaylist = it) } }
                    )
                }
                item(key = "item_show_weekly_most_played") {
                    AppearanceSwitchItem(
                        icon = Icons.Default.TrendingUp,
                        title = str(R.string.show_weekly_most_played_playlist),
                        subtitle = null,
                        isChecked = settings.showWeeklyMostPlaylist,
                        primaryColor = primaryColor,
                        surfaceColor = surfaceColor,
                        onSurface = onSurface,
                        onSurfaceVariant = onSurfaceVariant,
                        outlineVariant = outlineVariant,
                        onPrimary = onPrimary,
                        outline = outline,
                        surfaceVariant = surfaceVariant,
                        onCheckedChange = { update { copy(showWeeklyMostPlaylist = it) } }
                    )
                }
                item(key = "item_show_monthly_most_played") {
                    AppearanceSwitchItem(
                        icon = Icons.Default.TrendingUp,
                        title = str(R.string.show_monthly_most_played_playlist),
                        subtitle = null,
                        isChecked = settings.showMonthlyMostPlaylist,
                        primaryColor = primaryColor,
                        surfaceColor = surfaceColor,
                        onSurface = onSurface,
                        onSurfaceVariant = onSurfaceVariant,
                        outlineVariant = outlineVariant,
                        onPrimary = onPrimary,
                        outline = outline,
                        surfaceVariant = surfaceVariant,
                        onCheckedChange = { update { copy(showMonthlyMostPlaylist = it) } }
                    )
                }

            }
        }
    }

    AuralisPushedPage(page = if (showThemeAndColors) Unit else null) {
        ThemeAndColorsScreen(
            settings = settings,
            onUpdateSettings = { newS -> update { newS } },
            onBack = { showThemeAndColors = false }
        )
    }

    // ── OPTION SELECTION DIALOGS ──
    when (activeDialog) {
        AppearanceDialogType.THEME -> {
            AppearanceOptionsDialog(
                title = str(R.string.theme),
                options = listOf("Follow system", "Pure AMOLED Black", "Midnight Velvet Dark", "Light Mode", "Dynamic Material You"),
                selectedOption = settings.appTheme,
                onSelect = {
                    update { copy(appTheme = it) }
                    activeDialog = null
                },
                onDismiss = { activeDialog = null }
            )
        }
        AppearanceDialogType.MINI_PLAYER_DESIGN -> {
            AppearanceOptionsDialog(
                title = str(R.string.mini_player_design),
                options = listOf("Expanded mini player", "New mini player", "Classic mini player", "Material3"),
                selectedOption = settings.miniPlayerDesign,
                onSelect = {
                    update { copy(miniPlayerDesign = it) }
                    activeDialog = null
                },
                onDismiss = { activeDialog = null }
            )
        }
        AppearanceDialogType.MINI_PLAYER_BG -> {
            val bgOptions = PlayerBackgroundStyle.entries.map { it.displayName }
            AppearanceOptionsDialog(
                title = str(R.string.mini_player_background_style),
                options = bgOptions,
                selectedOption = PlayerBackgroundStyle.fromKey(settings.miniPlayerBackgroundStyle).displayName,
                onSelect = {
                    update { copy(miniPlayerBackgroundStyle = it) }
                    activeDialog = null
                },
                onDismiss = { activeDialog = null }
            )
        }
        AppearanceDialogType.PLAYER_BG -> {
            val bgOptions = PlayerBackgroundStyle.entries
                .filter { it != PlayerBackgroundStyle.APPLE_MUSIC }
                .map { it.displayName }
            val currentStyle = PlayerBackgroundStyle.fromKey(settings.playerBackgroundStyle).let {
                if (it == PlayerBackgroundStyle.APPLE_MUSIC) PlayerBackgroundStyle.GRADIENT else it
            }
            AppearanceOptionsDialog(
                title = str(R.string.player_background_style),
                options = bgOptions,
                selectedOption = currentStyle.displayName,
                onSelect = {
                    update { copy(playerBackgroundStyle = it) }
                    activeDialog = null
                },
                onDismiss = { activeDialog = null }
            )
        }
        AppearanceDialogType.PLAYER_BUTTON_COLORS -> {
            AppearanceOptionsDialog(
                title = str(R.string.player_button_colors),
                options = listOf("Default", "Accent Color", "Dynamic Artwork Vibrant", "Monochrome"),
                selectedOption = settings.playerButtonColors,
                onSelect = {
                    update { copy(playerButtonColors = it) }
                    activeDialog = null
                },
                onDismiss = { activeDialog = null }
            )
        }
        AppearanceDialogType.PLAYER_SLIDER_STYLE -> {
            PlayerSliderStyleChooserDialog(
                selectedStyle = settings.playerSliderStyle,
                onSelect = {
                    update { copy(playerSliderStyle = it) }
                    activeDialog = null
                },
                onDismiss = { activeDialog = null }
            )
        }
        AppearanceDialogType.MINI_PLAYER_SENSITIVITY -> {
            var tempSensitivity by remember { mutableStateOf(settings.miniPlayerSwipeSensitivity.toFloat()) }
            AlertDialog(
                onDismissRequest = { activeDialog = null },
                containerColor = MaterialTheme.colorScheme.surface,
                title = { Text(str(R.string.mini_player_swipe_sensitivity), fontWeight = FontWeight.Bold, color = onBackground) },
                text = {
                    Column {
                        Text("${tempSensitivity.roundToInt()}%", color = primaryColor, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                        Spacer(modifier = Modifier.height(12.dp))
                        Slider(
                            value = tempSensitivity,
                            onValueChange = { tempSensitivity = it },
                            valueRange = 10f..100f,
                            colors = SliderDefaults.colors(
                                thumbColor = primaryColor,
                                activeTrackColor = primaryColor,
                                inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        update { copy(miniPlayerSwipeSensitivity = tempSensitivity.roundToInt()) }
                        activeDialog = null
                    }) {
                        Text(str(R.string.save), color = primaryColor, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { activeDialog = null }) {
                        Text(str(R.string.cancel), color = onBackground.copy(alpha = 0.7f))
                    }
                }
            )
        }
        AppearanceDialogType.LYRICS_TEXT_POSITION -> {
            AppearanceOptionsDialog(
                title = str(R.string.lyrics_text_position),
                options = listOf("Left", "Center", "Right"),
                selectedOption = when (settings.lyricsTextPosition.lowercase()) {
                    "left", "start" -> "Left"
                    "right", "end" -> "Right"
                    else -> "Center"
                },
                onSelect = {
                    update { copy(lyricsTextPosition = it) }
                    activeDialog = null
                },
                onDismiss = { activeDialog = null }
            )
        }
        AppearanceDialogType.LYRICS_ANIMATION -> {
            AppearanceOptionsDialog(
                title = str(R.string.lyrics_animation),
                options = com.auralis.music.domain.model.LyricsAnimationMode.entries.map { it.displayName },
                selectedOption = com.auralis.music.domain.model.LyricsAnimationMode.fromDisplayName(settings.lyricsAnimation).displayName,
                onSelect = {
                    update { copy(lyricsAnimation = it) }
                    activeDialog = null
                },
                onDismiss = { activeDialog = null }
            )
        }
        AppearanceDialogType.LYRICS_TEXT_SIZE -> {
            var tempSize by remember { mutableFloatStateOf(settings.lyricsTextSize) }
            AlertDialog(
                onDismissRequest = { activeDialog = null },
                containerColor = MaterialTheme.colorScheme.surface,
                title = { Text(str(R.string.lyrics_text_size), fontWeight = FontWeight.Bold, color = onBackground) },
                text = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        Text(str(R.string.x_sp, tempSize.roundToInt()), color = primaryColor, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                        Spacer(modifier = Modifier.height(12.dp))
                        Slider(
                            value = tempSize,
                            onValueChange = { tempSize = it },
                            valueRange = 16f..36f,
                            steps = 19,
                            colors = SliderDefaults.colors(
                                thumbColor = primaryColor,
                                activeTrackColor = primaryColor,
                                inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        update { copy(lyricsTextSize = tempSize) }
                        activeDialog = null
                    }) {
                        Text(str(R.string.save), color = primaryColor, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    Row {
                        TextButton(onClick = { tempSize = 22f }) {
                            Text(str(R.string.reset), color = onBackground.copy(alpha = 0.7f))
                        }
                        TextButton(onClick = { activeDialog = null }) {
                            Text(str(R.string.cancel), color = onBackground.copy(alpha = 0.7f))
                        }
                    }
                }
            )
        }
        AppearanceDialogType.LYRICS_LINE_SPACING -> {
            var tempSpacing by remember { mutableFloatStateOf(settings.lyricsLineSpacing) }
            AlertDialog(
                onDismissRequest = { activeDialog = null },
                containerColor = MaterialTheme.colorScheme.surface,
                title = { Text(str(R.string.lyrics_line_spacing), fontWeight = FontWeight.Bold, color = onBackground) },
                text = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        Text("${String.format(java.util.Locale.US, "%.1f", tempSpacing)}x", color = primaryColor, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                        Spacer(modifier = Modifier.height(12.dp))
                        Slider(
                            value = tempSpacing,
                            onValueChange = { tempSpacing = it },
                            valueRange = 1.0f..4.0f,
                            steps = 29,
                            colors = SliderDefaults.colors(
                                thumbColor = primaryColor,
                                activeTrackColor = primaryColor,
                                inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        update { copy(lyricsLineSpacing = tempSpacing) }
                        activeDialog = null
                    }) {
                        Text(str(R.string.save), color = primaryColor, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    Row {
                        TextButton(onClick = { tempSpacing = 1.3f }) {
                            Text(str(R.string.reset), color = onBackground.copy(alpha = 0.7f))
                        }
                        TextButton(onClick = { activeDialog = null }) {
                            Text(str(R.string.cancel), color = onBackground.copy(alpha = 0.7f))
                        }
                    }
                }
            )
        }
        AppearanceDialogType.DEFAULT_OPEN_TAB -> {
            AppearanceOptionsDialog(
                title = str(R.string.default_open_tab),
                options = listOf("Home", "Explore", "Library"),
                selectedOption = settings.defaultOpenTab,
                onSelect = {
                    update { copy(defaultOpenTab = it) }
                    activeDialog = null
                },
                onDismiss = { activeDialog = null }
            )
        }
        AppearanceDialogType.DEFAULT_LIBRARY_CHIP -> {
            AppearanceOptionsDialog(
                title = str(R.string.change_default_library_chip),
                options = listOf("Library", "Playlists", "Songs", "Artists", "Albums"),
                selectedOption = settings.defaultLibraryChip,
                onSelect = {
                    update { copy(defaultLibraryChip = it) }
                    activeDialog = null
                },
                onDismiss = { activeDialog = null }
            )
        }
        AppearanceDialogType.GRID_CELL_SIZE -> {
            AppearanceOptionsDialog(
                title = str(R.string.grid_cell_size),
                options = listOf("Small", "Medium", "Large"),
                selectedOption = settings.gridCellSize,
                onSelect = {
                    update { copy(gridCellSize = it) }
                    activeDialog = null
                },
                onDismiss = { activeDialog = null }
            )
        }
        AppearanceDialogType.DISPLAY_DENSITY -> {
            AppearanceOptionsDialog(
                title = str(R.string.display_density),
                options = listOf("Compact (85%)", "Native (100%)", "Large (115%)"),
                selectedOption = settings.displayDensity,
                onSelect = {
                    update { copy(displayDensity = it) }
                    activeDialog = null
                },
                onDismiss = { activeDialog = null }
            )
        }
        null -> {}
    }
}

private enum class AppearanceDialogType {
    THEME,
    MINI_PLAYER_DESIGN,
    MINI_PLAYER_BG,
    PLAYER_BG,
    PLAYER_BUTTON_COLORS,
    PLAYER_SLIDER_STYLE,
    MINI_PLAYER_SENSITIVITY,
    LYRICS_TEXT_POSITION,
    LYRICS_ANIMATION,
    LYRICS_TEXT_SIZE,
    LYRICS_LINE_SPACING,
    DEFAULT_OPEN_TAB,
    DEFAULT_LIBRARY_CHIP,
    GRID_CELL_SIZE,
    DISPLAY_DENSITY
}

@Composable
private fun AppearanceSectionHeader(
    title: String,
    color: Color = MaterialTheme.dynamicPrimary
) {
    Text(
        text = title,
        color = color,
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 10.dp)
    )
}

@Composable
private fun AppearanceSwitchItem(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    isChecked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    primaryColor: Color = MaterialTheme.dynamicPrimary,
    surfaceColor: Color = MaterialTheme.dynamicSurface,
    onSurface: Color = MaterialTheme.colorScheme.onSurface,
    onSurfaceVariant: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    outlineVariant: Color = MaterialTheme.colorScheme.outlineVariant,
    onPrimary: Color = MaterialTheme.colorScheme.onPrimary,
    outline: Color = MaterialTheme.colorScheme.outline,
    surfaceVariant: Color = MaterialTheme.colorScheme.surfaceVariant
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = surfaceColor,
        border = BorderStroke(1.dp, outlineVariant.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onCheckedChange(!isChecked) }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(primaryColor.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = primaryColor,
                    modifier = Modifier.size(22.dp)
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = onSurface,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.5.sp
                )
                if (!subtitle.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = subtitle,
                        color = onSurfaceVariant,
                        fontSize = 12.5.sp,
                        lineHeight = 16.sp
                    )
                }
            }

            Spacer(modifier = Modifier.width(10.dp))

            Switch(
                checked = isChecked,
                onCheckedChange = onCheckedChange,
                thumbContent = if (isChecked) {
                    {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            tint = onPrimary,
                            modifier = Modifier.size(12.dp)
                        )
                    }
                } else null,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = primaryColor,
                    checkedTrackColor = primaryColor.copy(alpha = 0.45f),
                    checkedBorderColor = primaryColor,
                    uncheckedThumbColor = outline,
                    uncheckedTrackColor = surfaceVariant,
                    uncheckedBorderColor = outlineVariant
                )
            )
        }
    }
}

@Composable
private fun AppearanceClickableItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    primaryColor: Color = MaterialTheme.dynamicPrimary,
    surfaceColor: Color = MaterialTheme.dynamicSurface,
    onSurface: Color = MaterialTheme.colorScheme.onSurface,
    onSurfaceVariant: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    outlineVariant: Color = MaterialTheme.colorScheme.outlineVariant
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = surfaceColor,
        border = BorderStroke(1.dp, outlineVariant.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onClick() }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(primaryColor.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = primaryColor,
                    modifier = Modifier.size(22.dp)
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = onSurface,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.5.sp
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    color = onSurfaceVariant,
                    fontSize = 12.5.sp
                )
            }
        }
    }
}

@Composable
private fun AppearanceOptionsDialog(
    title: String,
    options: List<String>,
    selectedOption: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val primaryColor = MaterialTheme.dynamicPrimary
    val surfaceColor = MaterialTheme.dynamicSurface
    val onSurface = MaterialTheme.colorScheme.onSurface

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = surfaceColor,
        title = {
            Text(
                text = title,
                fontWeight = FontWeight.Bold,
                color = onSurface
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                options.forEach { option ->
                    val isSelected = option.equals(selectedOption, ignoreCase = true)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (isSelected) primaryColor.copy(alpha = 0.20f) else Color.Transparent)
                            .clickable { onSelect(option) }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = com.auralis.music.ui.i18n.UiLabels.of(option),
                            color = if (isSelected) primaryColor else onSurface,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            fontSize = 15.sp,
                            modifier = Modifier.weight(1f)
                        )
                        if (isSelected) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                tint = primaryColor,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(str(R.string.cancel), color = onSurface.copy(alpha = 0.7f))
            }
        }
    )
}

@Composable
private fun PlayerSliderStyleChooserDialog(
    selectedStyle: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val view = LocalView.current
    val normalizedSelected = when (selectedStyle) {
        "Squiggly Waveform", "Squiggly" -> "Squiggly"
        "Thin Line", "Slim" -> "Slim"
        "Wavy", "Neon Glow" -> "Wavy"
        else -> "Default"
    }

    val styles = listOf(
        listOf("Default", "Wavy"),
        listOf("Slim", "Squiggly")
    )

    // Wide sheet-like dialog (16dp side margins) with large outlined tiles, each showing the
    // real, live-animating seekbar so the preview is exactly what the player will draw.
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(MaterialTheme.dynamicSurface)
                .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            styles.forEach { rowStyles ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    rowStyles.forEach { style ->
                        SliderStylePreviewCard(
                            style = style,
                            isSelected = style.equals(normalizedSelected, ignoreCase = true),
                            modifier = Modifier.weight(1f),
                            onClick = {
                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                onSelect(style)
                                onDismiss()
                            }
                        )
                    }
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) {
                    Text(
                        text = str(R.string.cancel),
                        color = MaterialTheme.dynamicPrimary,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun SliderStylePreviewCard(
    style: String,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val primaryColor = MaterialTheme.dynamicPrimary
    val borderColor by androidx.compose.animation.animateColorAsState(
        targetValue = if (isSelected) primaryColor else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.22f),
        label = "sliderStyleBorder"
    )
    val shape = RoundedCornerShape(20.dp)

    Column(
        modifier = modifier
            .aspectRatio(1f)
            .clip(shape)
            .border(BorderStroke(1.dp, borderColor), shape)
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            // The real slider, playing, at a fixed position. Touch is blocked so tapping the
            // preview selects the style instead of seeking.
            Box(modifier = Modifier.fillMaxWidth()) {
                com.auralis.music.ui.components.AuralisPlayerSlider(
                    value = 0.45f,
                    onValueChange = {},
                    onValueChangeFinished = {},
                    isPlaying = true,
                    currentPosMs = 0L,
                    totalDurationMs = 1L,
                    sliderStyle = style,
                    activeTrackColor = primaryColor,
                    inactiveTrackColor = primaryColor.copy(alpha = 0.28f),
                    thumbColor = primaryColor,
                    showTimestamps = false,
                    modifier = Modifier.fillMaxWidth()
                )
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .clickable(
                            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                            indication = null,
                            onClick = onClick
                        )
                )
            }
        }
        Text(
            text = com.auralis.music.ui.i18n.UiLabels.of(style),
            fontSize = 12.sp,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (isSelected) 1f else 0.8f)
        )
    }
}
