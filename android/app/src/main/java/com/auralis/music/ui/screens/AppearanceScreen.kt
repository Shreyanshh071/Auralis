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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
                    AppearanceSwitchItem(
                        icon = Icons.Default.PictureInPictureAlt,
                        title = "New mini-player design",
                        subtitle = "Modern floating pill with dynamic playback progress",
                        isChecked = settings.newMiniPlayerDesign,
                        onCheckedChange = { update { copy(newMiniPlayerDesign = it) } }
                    )
                }
                item {
                    AppearanceClickableItem(
                        icon = Icons.Default.GridView,
                        title = "Mini-player background style",
                        subtitle = settings.miniPlayerBackgroundStyle,
                        onClick = { activeDialog = AppearanceDialogType.MINI_PLAYER_BG }
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
                    AppearanceClickableItem(
                        icon = Icons.Default.GridView,
                        title = "Player background style",
                        subtitle = when (settings.playerBackgroundStyle) {
                            "Blur" -> "Dynamic Blurred Artwork"
                            else -> settings.playerBackgroundStyle
                        },
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
                        icon = Icons.Default.Download,
                        title = "Show download button",
                        subtitle = "Display download button in player controls",
                        isChecked = settings.showDownloadButton,
                        onCheckedChange = { update { copy(showDownloadButton = it) } }
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
                    AppearanceSwitchItem(
                        icon = Icons.Default.TouchApp,
                        title = "Change lyrics on tap",
                        subtitle = "Seek track playback to the tapped lyric timestamp",
                        isChecked = settings.changeLyricsOnTap,
                        onCheckedChange = { update { copy(changeLyricsOnTap = it) } }
                    )
                }
                item {
                    AppearanceSwitchItem(
                        icon = Icons.Default.VerticalAlignBottom,
                        title = "Auto-scroll lyrics",
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
        AppearanceDialogType.MINI_PLAYER_BG -> {
            AppearanceOptionsDialog(
                title = "Mini-player background style",
                options = listOf("Gradient", "Apple Liquid Glass", "Blur", "Dark Black"),
                selectedOption = when (settings.miniPlayerBackgroundStyle) {
                    "Frosted Glass / Blur", "Dynamic Blurred Artwork" -> "Blur"
                    "Artwork Tinted", "Dynamic Artwork Tint", "Follow theme" -> "Gradient"
                    "Pure black", "Solid AMOLED Black" -> "Dark Black"
                    "Liquid Glass", "Apple Liquid Glass", "Apple Glass", "Glass" -> "Apple Liquid Glass"
                    else -> settings.miniPlayerBackgroundStyle
                },
                onSelect = {
                    update { copy(miniPlayerBackgroundStyle = it) }
                    activeDialog = null
                },
                onDismiss = { activeDialog = null }
            )
        }
        AppearanceDialogType.PLAYER_BG -> {
            AppearanceOptionsDialog(
                title = "Player background style",
                options = listOf("Follow theme", "Gradient", "Dynamic Blurred Artwork"),
                selectedOption = when (settings.playerBackgroundStyle) {
                    "Follow theme" -> "Follow theme"
                    "Blur", "Frosted Glass / Blur", "Dynamic Blurred Artwork" -> "Dynamic Blurred Artwork"
                    else -> "Gradient"
                },
                onSelect = {
                    val savedVal = if (it == "Dynamic Blurred Artwork") "Blur" else it
                    update { copy(playerBackgroundStyle = savedVal) }
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
                options = listOf("Left", "Centre", "Right"),
                selectedOption = settings.lyricsTextPosition,
                onSelect = {
                    update { copy(lyricsTextPosition = it) }
                    activeDialog = null
                },
                onDismiss = { activeDialog = null }
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
    MINI_PLAYER_BG,
    PLAYER_BG,
    PLAYER_BUTTON_COLORS,
    PLAYER_SLIDER_STYLE,
    MINI_PLAYER_SENSITIVITY,
    LYRICS_TEXT_POSITION,
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
        shape = RoundedCornerShape(24.dp),
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
            TextButton(onClick = onDismiss) {
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
    val cardBg = if (isSelected) primaryColor.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant
    val borderColor = if (isSelected) primaryColor else MaterialTheme.colorScheme.outlineVariant
    val borderWidth = if (isSelected) 2.dp else 1.dp
    val activeTrackColor = primaryColor
    val inactiveTrackColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)

    // Infinite wave phase animation for live moving preview
    val infiniteTransition = rememberInfiniteTransition(label = "previewWaveTransition")
    val wavePhaseFraction by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "previewWavePhase"
    )

    Box(
        modifier = modifier
            .height(116.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(cardBg)
            .border(BorderStroke(borderWidth, borderColor), RoundedCornerShape(16.dp))
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 10.dp),
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
                    .height(48.dp)
            ) {
                val width = size.width
                val height = size.height
                val centerY = height / 2f

                when (style) {
                    "Default" -> {
                        val trackHeightPx = 8.dp.toPx()
                        val r = trackHeightPx / 2f
                        val gapPx = 3.5.dp.toPx()
                        val startX = r + 2.dp.toPx()
                        val endX = width - r - 2.dp.toPx()
                        val thumbX = startX + (endX - startX) * 0.48f

                        // Inactive Track with straight cut on left and rounded right cap
                        val inactiveLeft = (thumbX + gapPx).coerceAtMost(endX + r)
                        if (inactiveLeft < endX) {
                            val inactivePath = Path().apply {
                                moveTo(inactiveLeft, centerY - r)
                                lineTo(endX, centerY - r)
                                arcTo(
                                    rect = Rect(endX - r, centerY - r, endX + r, centerY + r),
                                    startAngleDegrees = -90f,
                                    sweepAngleDegrees = 180f,
                                    forceMoveTo = false
                                )
                                lineTo(inactiveLeft, centerY + r)
                                close()
                            }
                            drawPath(inactivePath, inactiveTrackColor)
                        }

                        // Endpoint Dot
                        drawCircle(
                            color = activeTrackColor,
                            radius = 1.6.dp.toPx(),
                            center = Offset(endX, centerY)
                        )

                        // Active Track with rounded left cap and straight cut on right
                        val activeRight = (thumbX - gapPx).coerceAtLeast(startX - r)
                        if (activeRight > startX) {
                            val activePath = Path().apply {
                                moveTo(activeRight, centerY - r)
                                lineTo(startX, centerY - r)
                                arcTo(
                                    rect = Rect(startX - r, centerY - r, startX + r, centerY + r),
                                    startAngleDegrees = -90f,
                                    sweepAngleDegrees = -180f,
                                    forceMoveTo = false
                                )
                                lineTo(activeRight, centerY + r)
                                close()
                            }
                            drawPath(activePath, activeTrackColor)
                        }

                        // Vertical Playhead Line
                        val pillW = 2.5.dp.toPx()
                        val pillH = 22.dp.toPx()
                        val pillR = pillW / 2f
                        drawRoundRect(
                            color = activeTrackColor,
                            topLeft = Offset(thumbX - pillR, centerY - pillH / 2f),
                            size = Size(pillW, pillH),
                            cornerRadius = CornerRadius(pillR, pillR)
                        )
                    }
                    "Wavy" -> {
                        val startX = 6.dp.toPx()
                        val endX = width - 6.dp.toPx()
                        val thumbX = startX + (endX - startX) * 0.48f
                        val stroke = 2.5.dp.toPx()
                        val wavelength = 16.dp.toPx()
                        val amp = 3.5.dp.toPx()
                        val startAngle = -wavePhaseFraction * (2 * PI).toFloat()
                        val totalSpan = (thumbX - startX).coerceAtLeast(1f)
                        val endTransitionLength = (wavelength * 0.9f).coerceAtMost(totalSpan * 0.6f).coerceAtLeast(1f)
                        val startY = centerY + sin(startAngle) * amp

                        // Inactive track
                        drawLine(
                            color = inactiveTrackColor,
                            start = Offset(thumbX, centerY),
                            end = Offset(endX, centerY),
                            strokeWidth = stroke,
                            cap = StrokeCap.Round
                        )
                        // Endpoint Dot
                        drawCircle(
                            color = activeTrackColor,
                            radius = 1.6.dp.toPx(),
                            center = Offset(endX, centerY)
                        )

                        // Active track (Smooth animated sine wave)
                        val wavePath = Path().apply {
                            moveTo(startX, startY)
                            var x = startX
                            val step = 1.0f
                            while (x <= thumbX) {
                                val distFromStart = x - startX
                                val distFromEnd = thumbX - x
                                val endEnvelope = if (distFromEnd < endTransitionLength) {
                                    val v = (distFromEnd / endTransitionLength).coerceIn(0f, 1f)
                                    0.5f * (1f - kotlin.math.cos(v * PI.toFloat()))
                                } else 1.0f
                                val angle = (distFromStart / wavelength) * (2 * PI).toFloat() + startAngle
                                val y = centerY + sin(angle) * amp * endEnvelope
                                lineTo(x, y)
                                x += step
                            }
                            lineTo(thumbX, centerY)
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
                            radius = 5.dp.toPx(),
                            center = Offset(thumbX, centerY)
                        )
                    }
                    "Slim" -> {
                        val stroke = 3.5.dp.toPx()
                        val r = stroke / 2f
                        val startX = r + 4.dp.toPx()
                        val endX = width - r - 4.dp.toPx()
                        val thumbX = startX + (endX - startX) * 0.48f

                        // Inactive track
                        drawLine(
                            color = inactiveTrackColor,
                            start = Offset(thumbX, centerY),
                            end = Offset(endX, centerY),
                            strokeWidth = stroke,
                            cap = StrokeCap.Round
                        )
                        // Active track
                        drawLine(
                            color = activeTrackColor,
                            start = Offset(startX, centerY),
                            end = Offset(thumbX, centerY),
                            strokeWidth = stroke,
                            cap = StrokeCap.Round
                        )
                    }
                    "Squiggly" -> {
                        val startX = 6.dp.toPx()
                        val endX = width - 6.dp.toPx()
                        val thumbX = startX + (endX - startX) * 0.48f
                        val stroke = 2.5.dp.toPx()
                        val pillW = 4.dp.toPx()
                        val pillH = 16.dp.toPx()
                        val pillR = pillW / 2f
                        val waveEndX = thumbX - stroke / 2f
                        val inactiveStartX = thumbX + stroke / 2f
                        val wavelength = 10.dp.toPx()
                        val amp = 3.5.dp.toPx()
                        val startAngle = -wavePhaseFraction * (2 * PI).toFloat()
                        val totalSpan = (waveEndX - startX).coerceAtLeast(1f)
                        val endTransitionLength = (wavelength * 0.9f).coerceAtMost(totalSpan * 0.6f).coerceAtLeast(1f)
                        val startY = centerY + sin(startAngle) * amp

                        // Inactive track
                        drawLine(
                            color = inactiveTrackColor,
                            start = Offset(inactiveStartX, centerY),
                            end = Offset(endX, centerY),
                            strokeWidth = stroke,
                            cap = StrokeCap.Round
                        )
                        // Endpoint Dot
                        drawCircle(
                            color = activeTrackColor,
                            radius = 1.6.dp.toPx(),
                            center = Offset(endX, centerY)
                        )

                        // Active track (Smooth animated squiggly wave)
                        val wavePath = Path().apply {
                            moveTo(startX, startY)
                            var x = startX
                            val step = 1.0f
                            while (x <= waveEndX) {
                                val distFromStart = x - startX
                                val distFromEnd = waveEndX - x
                                val endEnvelope = if (distFromEnd < endTransitionLength) {
                                    val v = (distFromEnd / endTransitionLength).coerceIn(0f, 1f)
                                    0.5f * (1f - kotlin.math.cos(v * PI.toFloat()))
                                } else 1.0f
                                val angle = (distFromStart / wavelength) * (2 * PI).toFloat() + startAngle
                                val y = centerY + sin(angle) * amp * endEnvelope
                                lineTo(x, y)
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
                        // Pill Thumb (Rendered on top)
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
                color = if (isSelected) ACCENT_TAN else Color.White,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                fontSize = 13.sp
            )
        }
    }
}

