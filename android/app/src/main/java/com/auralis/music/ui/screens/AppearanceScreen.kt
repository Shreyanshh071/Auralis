package com.auralis.music.ui.screens

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
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

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

    LaunchedEffect(settings.playerBackgroundStyle) {
        if (PlayerBackgroundStyle.fromKey(settings.playerBackgroundStyle) == PlayerBackgroundStyle.APPLE_MUSIC) {
            update { copy(playerBackgroundStyle = PlayerBackgroundStyle.BLUR.displayName) }
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

    val primaryColor = MaterialTheme.colorScheme.primary
    val backgroundColor = MaterialTheme.colorScheme.background
    val onBackground = MaterialTheme.colorScheme.onBackground
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant

    if (showThemeAndColors) {
        ThemeAndColorsScreen(
            settings = settings,
            onUpdateSettings = { newS -> update { newS } },
            onBack = { showThemeAndColors = false }
        )
        return
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(backgroundColor)
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
                        contentDescription = "Back",
                        tint = onBackground
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Appearance",
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
                contentPadding = PaddingValues(bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // ════ 1. THEME ════
                item { AppearanceSectionHeader(title = "Theme") }
                item {
                    AppearanceClickableItem(
                        icon = Icons.Default.Palette,
                        title = "Theme & Colors",
                        subtitle = "${settings.appTheme} • ${settings.colorPalette}",
                        onClick = { showThemeAndColors = true }
                    )
                }
                item {
                    AppearanceSwitchItem(
                        icon = Icons.Default.Speed,
                        title = "Enable high refresh rate",
                        subtitle = "Forces the display to run at its highest supported refresh rate (e.g. 120Hz)",
                        isChecked = settings.highRefreshRate,
                        onCheckedChange = { update { copy(highRefreshRate = it) } }
                    )
                }
                item {
                    AppearanceSwitchItem(
                        icon = Icons.Default.AspectRatio,
                        title = "Landscape Scaling",
                        subtitle = "Scale UI in landscape mode for larger screens",
                        isChecked = settings.landscapeScaling,
                        onCheckedChange = { update { copy(landscapeScaling = it) } }
                    )
                }
                item {
                    AppearanceSwitchItem(
                        icon = Icons.Default.ColorLens,
                        title = "Dynamic icon colors",
                        subtitle = "Use dynamic theme colors for the app icon. When disabled, the icon uses solid colors.",
                        isChecked = settings.dynamicIconColors,
                        onCheckedChange = { update { copy(dynamicIconColors = it) } }
                    )
                }

                // ════ 2. MINI-PLAYER ════
                item { AppearanceSectionHeader(title = "Mini-player") }
                item {
                    AppearanceClickableItem(
                        icon = Icons.Default.PictureInPictureAlt,
                        title = "Mini-player design",
                        subtitle = settings.miniPlayerDesign,
                        onClick = { activeDialog = AppearanceDialogType.MINI_PLAYER_DESIGN }
                    )
                }
                item {
                    AppearanceSwitchItem(
                        icon = Icons.Default.DarkMode,
                        title = "Pure black mini player",
                        subtitle = "Force deep AMOLED black background on mini-player",
                        isChecked = settings.pureBlackMiniPlayer,
                        onCheckedChange = { update { copy(pureBlackMiniPlayer = it) } }
                    )
                }
                item {
                    val isPureBlackActive = settings.pureBlackMiniPlayer && settings.miniPlayerDesign != "Expanded mini player"
                    AppearanceClickableItem(
                        icon = Icons.Default.GridView,
                        title = "Mini-player background style",
                        subtitle = if (isPureBlackActive) "Unavailable when pure black is enabled" else PlayerBackgroundStyle.fromKey(settings.miniPlayerBackgroundStyle).displayName,
                        onClick = {
                            if (!isPureBlackActive) {
                                activeDialog = AppearanceDialogType.MINI_PLAYER_BG
                            }
                        }
                    )
                }

                // ════ 3. PLAYER ════
                item { AppearanceSectionHeader(title = "Player") }
                item {
                    AppearanceSwitchItem(
                        icon = Icons.Default.ColorLens,
                        title = "New player design",
                        subtitle = "Modern expanded now-playing screen with rich gestures",
                        isChecked = settings.newPlayerDesign,
                        onCheckedChange = { update { copy(newPlayerDesign = it) } }
                    )
                }
                item {
                    val resolvedPlayerBg = PlayerBackgroundStyle.fromKey(settings.playerBackgroundStyle).let {
                        if (it == PlayerBackgroundStyle.APPLE_MUSIC) PlayerBackgroundStyle.BLUR else it
                    }
                    AppearanceClickableItem(
                        icon = Icons.Default.GridView,
                        title = "Player background style",
                        subtitle = resolvedPlayerBg.displayName,
                        onClick = { activeDialog = AppearanceDialogType.PLAYER_BG }
                    )
                }


                item {
                    AppearanceClickableItem(
                        icon = Icons.Default.LinearScale,
                        title = "Player slider style",
                        subtitle = settings.playerSliderStyle,
                        onClick = { activeDialog = AppearanceDialogType.PLAYER_SLIDER_STYLE }
                    )
                }
                item {
                    AppearanceSwitchItem(
                        icon = Icons.Default.Swipe,
                        title = "Enable swipe to change song",
                        subtitle = "Swipe horizontally across player to skip or rewind",
                        isChecked = settings.enableSwipeToChangeSong,
                        onCheckedChange = { update { copy(enableSwipeToChangeSong = it) } }
                    )
                }

                // ════ 4. LYRICS ════
                item { AppearanceSectionHeader(title = "Lyrics") }
                item {
                    AppearanceClickableItem(
                        icon = Icons.Default.FormatAlignCenter,
                        title = "Lyrics text position",
                        subtitle = settings.lyricsTextPosition,
                        onClick = { activeDialog = AppearanceDialogType.LYRICS_TEXT_POSITION }
                    )
                }
                item {
                    AppearanceClickableItem(
                        icon = Icons.Default.GraphicEq,
                        title = "Lyrics animation",
                        subtitle = com.auralis.music.domain.model.LyricsAnimationMode.fromDisplayName(settings.lyricsAnimation).displayName,
                        onClick = { activeDialog = AppearanceDialogType.LYRICS_ANIMATION }
                    )
                }
                item {
                    AppearanceSwitchItem(
                        icon = Icons.Default.ColorLens,
                        title = "Enable glowing lyrics effect",
                        subtitle = "Apply glowing animation and bounce effects to lyrics",
                        isChecked = settings.enableGlowingLyricsEffect,
                        onCheckedChange = { update { copy(enableGlowingLyricsEffect = it) } }
                    )
                }
                item {
                    AppearanceSwitchItem(
                        icon = Icons.Default.HideImage,
                        title = "Standard lyrics blur",
                        subtitle = "Apply soft blur focus to inactive lyrics",
                        isChecked = settings.standardLyricsBlur,
                        onCheckedChange = { update { copy(standardLyricsBlur = it) } }
                    )
                }
                item {
                    AppearanceClickableItem(
                        icon = Icons.Default.AspectRatio,
                        title = "Lyrics text size",
                        subtitle = "${settings.lyricsTextSize.roundToInt()} sp",
                        onClick = { activeDialog = AppearanceDialogType.LYRICS_TEXT_SIZE }
                    )
                }
                item {
                    AppearanceClickableItem(
                        icon = Icons.Default.LinearScale,
                        title = "Lyrics line spacing",
                        subtitle = "${String.format(java.util.Locale.US, "%.1f", settings.lyricsLineSpacing)}x",
                        onClick = { activeDialog = AppearanceDialogType.LYRICS_LINE_SPACING }
                    )
                }
                item {
                    AppearanceSwitchItem(
                        icon = Icons.Default.TouchApp,
                        title = "Change lyrics on click",
                        subtitle = "Seek track playback to the clicked lyric timestamp",
                        isChecked = settings.changeLyricsOnTap,
                        onCheckedChange = { update { copy(changeLyricsOnTap = it) } }
                    )
                }
                item {
                    AppearanceSwitchItem(
                        icon = Icons.Default.VerticalAlignBottom,
                        title = "Auto scroll lyrics",
                        subtitle = "Automatically keep the active lyric centered in view",
                        isChecked = settings.autoScrollLyrics,
                        onCheckedChange = { update { copy(autoScrollLyrics = it) } }
                    )
                }

                // ════ 5. MISC ════
                item { AppearanceSectionHeader(title = "Misc") }
                item {
                    AppearanceSwitchItem(
                        icon = Icons.Default.Swipe,
                        title = "Swipe left to add the song to the queue, or right to play it next",
                        subtitle = null,
                        isChecked = settings.swipeLeftQueueRightPlayNext,
                        onCheckedChange = { update { copy(swipeLeftQueueRightPlayNext = it) } }
                    )
                }
                item {
                    AppearanceSwitchItem(
                        icon = Icons.Default.Swipe,
                        title = "Swipe to remove the song from the playlist",
                        subtitle = null,
                        isChecked = settings.swipeToRemoveSongFromPlaylist,
                        onCheckedChange = { update { copy(swipeToRemoveSongFromPlaylist = it) } }
                    )
                }
                item {
                    AppearanceClickableItem(
                        icon = Icons.Default.ViewModule,
                        title = "Display density",
                        subtitle = settings.displayDensity,
                        onClick = { activeDialog = AppearanceDialogType.DISPLAY_DENSITY }
                    )
                }

                // ════ 6. AUTO PLAYLISTS ════
                item { AppearanceSectionHeader(title = "Auto playlists") }
                item {
                    AppearanceSwitchItem(
                        icon = Icons.Default.Favorite,
                        title = "Show Liked playlist",
                        subtitle = null,
                        isChecked = settings.showLikedPlaylist,
                        onCheckedChange = { update { copy(showLikedPlaylist = it) } }
                    )
                }
                item {
                    AppearanceSwitchItem(
                        icon = Icons.Default.CheckCircle,
                        title = "Show Downloaded playlist",
                        subtitle = null,
                        isChecked = settings.showDownloadedPlaylist,
                        onCheckedChange = { update { copy(showDownloadedPlaylist = it) } }
                    )
                }

                item { Spacer(modifier = Modifier.height(32.dp)) }
            }
        }
    }

    // ── OPTION SELECTION DIALOGS ──
    when (activeDialog) {
        AppearanceDialogType.THEME -> {
            AppearanceOptionsDialog(
                title = "Theme",
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
                title = "Mini-player design",
                options = listOf("Expanded mini player", "New mini player", "Classic mini player"),
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
                title = "Mini-player background style",
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
                if (it == PlayerBackgroundStyle.APPLE_MUSIC) PlayerBackgroundStyle.BLUR else it
            }
            AppearanceOptionsDialog(
                title = "Player background style",
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
                title = "Player button colors",
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
                title = { Text("Mini-player swipe sensitivity", fontWeight = FontWeight.Bold, color = onBackground) },
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
                        Text("Save", color = primaryColor, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { activeDialog = null }) {
                        Text("Cancel", color = onBackground.copy(alpha = 0.7f))
                    }
                }
            )
        }
        AppearanceDialogType.LYRICS_TEXT_POSITION -> {
            AppearanceOptionsDialog(
                title = "Lyrics text position",
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
                title = "Lyrics animation",
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
                title = { Text("Lyrics text size", fontWeight = FontWeight.Bold, color = onBackground) },
                text = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        Text("${tempSize.roundToInt()} sp", color = primaryColor, fontWeight = FontWeight.Bold, fontSize = 20.sp)
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
                        Text("Save", color = primaryColor, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    Row {
                        TextButton(onClick = { tempSize = 22f }) {
                            Text("Reset", color = onBackground.copy(alpha = 0.7f))
                        }
                        TextButton(onClick = { activeDialog = null }) {
                            Text("Cancel", color = onBackground.copy(alpha = 0.7f))
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
                title = { Text("Lyrics line spacing", fontWeight = FontWeight.Bold, color = onBackground) },
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
                        Text("Save", color = primaryColor, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    Row {
                        TextButton(onClick = { tempSpacing = 1.3f }) {
                            Text("Reset", color = onBackground.copy(alpha = 0.7f))
                        }
                        TextButton(onClick = { activeDialog = null }) {
                            Text("Cancel", color = onBackground.copy(alpha = 0.7f))
                        }
                    }
                }
            )
        }
        AppearanceDialogType.DEFAULT_OPEN_TAB -> {
            AppearanceOptionsDialog(
                title = "Default open tab",
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
                title = "Change default library chip",
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
                title = "Grid cell size",
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
                title = "Display density",
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
private fun AppearanceSectionHeader(title: String) {
    Text(
        text = title,
        color = MaterialTheme.colorScheme.primary,
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
    onCheckedChange: (Boolean) -> Unit
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val surfaceColor = MaterialTheme.colorScheme.surface
    val onSurface = MaterialTheme.colorScheme.onSurface
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = surfaceColor,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
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
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(12.dp)
                        )
                    }
                } else null,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = primaryColor,
                    checkedTrackColor = primaryColor.copy(alpha = 0.45f),
                    checkedBorderColor = primaryColor,
                    uncheckedThumbColor = MaterialTheme.colorScheme.outline,
                    uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                    uncheckedBorderColor = MaterialTheme.colorScheme.outlineVariant
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
    onClick: () -> Unit
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val surfaceColor = MaterialTheme.colorScheme.surface
    val onSurface = MaterialTheme.colorScheme.onSurface
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = surfaceColor,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
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
    val primaryColor = MaterialTheme.colorScheme.primary
    val surfaceColor = MaterialTheme.colorScheme.surface
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
                            text = option,
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
                Text("Cancel", color = onSurface.copy(alpha = 0.7f))
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

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(28.dp),
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                styles.forEach { rowStyles ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        rowStyles.forEach { style ->
                            val isSelected = style.equals(normalizedSelected, ignoreCase = true)
                            SliderStylePreviewCard(
                                style = style,
                                isSelected = isSelected,
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
            }
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(
                    text = "Cancel",
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp
                )
            }
        }
    )
}

@Composable
private fun SliderStylePreviewCard(
    style: String,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val cardBg = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.22f)
    val borderColor = if (isSelected) primaryColor else Color.White.copy(alpha = 0.12f)
    val borderWidth = if (isSelected) 1.5.dp else 1.dp
    val activeTrackColor = primaryColor
    val inactiveTrackColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)

    // Gentle wave phase animation for live moving preview
    val infiniteTransition = rememberInfiniteTransition(label = "previewWaveTransition")
    val wavePhaseFraction by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "previewWavePhase"
    )

    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(20.dp))
            .background(cardBg)
            .border(BorderStroke(borderWidth, borderColor), RoundedCornerShape(20.dp))
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Miniature Slider Preview Canvas
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                val width = size.width
                val height = size.height
                val centerY = height / 2f
                val startX = 6.dp.toPx()
                val endX = width - 6.dp.toPx()

                when (style) {
                    "Default" -> {
                        val trackH = 13.dp.toPx()
                        val r = trackH / 2f
                        val gapPx = 5.dp.toPx()
                        val thumbX = startX + (endX - startX) * 0.44f
                        val pillW = 4.5.dp.toPx()
                        val pillH = 32.dp.toPx()
                        val pillR = pillW / 2f

                        // Active Track (thick pill on the left)
                        val activeRight = (thumbX - gapPx).coerceAtLeast(startX)
                        if (activeRight > startX) {
                            drawRoundRect(
                                color = activeTrackColor,
                                topLeft = Offset(startX, centerY - r),
                                size = Size(activeRight - startX, trackH),
                                cornerRadius = CornerRadius(r, r)
                            )
                        }

                        // Vertical Playhead Pill Thumb
                        drawRoundRect(
                            color = activeTrackColor,
                            topLeft = Offset(thumbX - pillR, centerY - pillH / 2f),
                            size = Size(pillW, pillH),
                            cornerRadius = CornerRadius(pillR, pillR)
                        )

                        // Inactive Track (thick pill on the right)
                        val inactiveLeft = (thumbX + gapPx).coerceAtMost(endX)
                        if (inactiveLeft < endX) {
                            drawRoundRect(
                                color = inactiveTrackColor,
                                topLeft = Offset(inactiveLeft, centerY - r),
                                size = Size(endX - inactiveLeft, trackH),
                                cornerRadius = CornerRadius(r, r)
                            )
                            // Endpoint Dot centered inside right cap
                            drawCircle(
                                color = activeTrackColor,
                                radius = 2.dp.toPx(),
                                center = Offset(endX - r, centerY)
                            )
                        }
                    }
                    "Wavy" -> {
                        val thumbRadius = 7.dp.toPx()
                        val thumbX = startX + (endX - startX) * 0.48f
                        val stroke = 3.2.dp.toPx()
                        val wavelength = 46.dp.toPx()
                        val amp = 5.dp.toPx()
                        val startAngle = -wavePhaseFraction * (2 * PI).toFloat()
                        val waveEndX = thumbX - thumbRadius + 1.dp.toPx()
                        val totalSpan = (waveEndX - startX).coerceAtLeast(1f)

                        // Inactive track (straight line with gap from thumb)
                        val inactiveStartX = thumbX + thumbRadius + 4.dp.toPx()
                        if (inactiveStartX < endX) {
                            drawLine(
                                color = inactiveTrackColor,
                                start = Offset(inactiveStartX, centerY),
                                end = Offset(endX, centerY),
                                strokeWidth = 2.8.dp.toPx(),
                                cap = StrokeCap.Round
                            )
                            // Endpoint Dot
                            drawCircle(
                                color = activeTrackColor,
                                radius = 2.dp.toPx(),
                                center = Offset(endX, centerY)
                            )
                        }

                        // Active track (Smooth gentle sine wave ~1.2 cycles)
                        val wavePath = Path().apply {
                            var x = startX
                            val step = 1.0f
                            var first = true
                            while (x <= waveEndX) {
                                val progress = (x - startX) / totalSpan
                                val endEnvelope = if (progress > 0.7f) {
                                    0.5f * (1f + kotlin.math.cos(((progress - 0.7f) / 0.3f) * PI.toFloat()))
                                } else 1.0f
                                val angle = ((x - startX) / wavelength) * (2 * PI).toFloat() + startAngle
                                val y = centerY + sin(angle) * amp * endEnvelope
                                if (first) {
                                    moveTo(x, y)
                                    first = false
                                } else {
                                    lineTo(x, y)
                                }
                                x += step
                            }
                            lineTo(waveEndX, centerY)
                        }
                        drawPath(
                            path = wavePath,
                            color = activeTrackColor,
                            style = Stroke(
                                width = stroke,
                                cap = StrokeCap.Round,
                                join = StrokeJoin.Round
                            )
                        )

                        // Circle Thumb
                        drawCircle(
                            color = activeTrackColor,
                            radius = thumbRadius,
                            center = Offset(thumbX, centerY)
                        )
                    }
                    "Slim" -> {
                        val trackH = 7.dp.toPx()
                        val r = trackH / 2f
                        val splitX = startX + (endX - startX) * 0.60f
                        val trackRect = Rect(startX, centerY - r, endX, centerY + r)
                        val trackPath = Path().apply {
                            addRoundRect(RoundRect(trackRect, CornerRadius(r, r)))
                        }

                        // Inactive full pill
                        drawPath(trackPath, inactiveTrackColor)

                        // Active portion clipped to rounded pill shape
                        clipPath(trackPath) {
                            drawRect(
                                color = activeTrackColor,
                                topLeft = Offset(startX, centerY - r),
                                size = Size((splitX - startX).coerceAtLeast(0f), trackH)
                            )
                        }
                    }
                    "Squiggly" -> {
                        val pillW = 4.dp.toPx()
                        val pillH = 22.dp.toPx()
                        val pillR = pillW / 2f
                        val thumbX = startX + (endX - startX) * 0.48f
                        val stroke = 3.dp.toPx()
                        val waveEndX = thumbX - pillR - 1.dp.toPx()
                        val inactiveStartX = thumbX + pillR + 1.dp.toPx()
                        val totalSpan = (waveEndX - startX).coerceAtLeast(1f)
                        val wavelength = totalSpan / 2.0f // exactly 2 wave cycles
                        val amp = 4.2.dp.toPx()
                        val startAngle = -wavePhaseFraction * (2 * PI).toFloat()

                        // Inactive track (straight line with rounded cap)
                        if (inactiveStartX < endX) {
                            drawLine(
                                color = inactiveTrackColor,
                                start = Offset(inactiveStartX, centerY),
                                end = Offset(endX, centerY),
                                strokeWidth = stroke,
                                cap = StrokeCap.Round
                            )
                        }

                        // Active track (Smooth animated squiggly wave - 2 cycles)
                        val wavePath = Path().apply {
                            var x = startX
                            val step = 1.0f
                            var first = true
                            while (x <= waveEndX) {
                                val progress = (x - startX) / totalSpan
                                val endEnvelope = if (progress > 0.75f) {
                                    0.5f * (1f + kotlin.math.cos(((progress - 0.75f) / 0.25f) * PI.toFloat()))
                                } else 1.0f
                                val angle = ((x - startX) / wavelength) * (2 * PI).toFloat() + startAngle
                                val y = centerY + sin(angle) * amp * endEnvelope
                                if (first) {
                                    moveTo(x, y)
                                    first = false
                                } else {
                                    lineTo(x, y)
                                }
                                x += step
                            }
                            lineTo(waveEndX, centerY)
                        }
                        drawPath(
                            path = wavePath,
                            color = activeTrackColor,
                            style = Stroke(
                                width = stroke,
                                cap = StrokeCap.Round,
                                join = StrokeJoin.Round
                            )
                        )

                        // Pill Thumb
                        drawRoundRect(
                            color = activeTrackColor,
                            topLeft = Offset(thumbX - pillR, centerY - pillH / 2f),
                            size = Size(pillW, pillH),
                            cornerRadius = CornerRadius(pillR, pillR)
                        )
                    }
                }
            }

            Text(
                text = style,
                color = if (isSelected) Color.White else Color.White.copy(alpha = 0.85f),
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                fontSize = 13.sp
            )
        }
    }
}

