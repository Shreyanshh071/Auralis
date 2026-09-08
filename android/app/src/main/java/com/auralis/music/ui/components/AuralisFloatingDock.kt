package com.auralis.music.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material3.*
import androidx.compose.ui.graphics.luminance
import com.auralis.music.ui.theme.dynamicPrimary
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.auralis.music.ui.AppDestination

import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.hazeEffect

val MoodGenreNavIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "MoodGenreNav",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        // Back cards lines
        path(
            stroke = SolidColor(Color.White),
            strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round
        ) {
            moveTo(4.5f, 17f)
            lineTo(7f, 10f)
        }
        path(
            stroke = SolidColor(Color.White),
            strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round
        ) {
            moveTo(7.5f, 19f)
            lineTo(10f, 12.5f)
        }
        // Front tilted card
        path(
            stroke = SolidColor(Color.White),
            strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        ) {
            moveTo(11.5f, 6.5f)
            lineTo(18.5f, 9.5f)
            lineTo(15.5f, 19.5f)
            lineTo(8.5f, 16.5f)
            close()
        }
        // Small dot inside front card
        path(
            stroke = SolidColor(Color.White),
            strokeLineWidth = 1.5f,
            strokeLineCap = StrokeCap.Round
        ) {
            moveTo(12.5f, 10f)
            lineTo(12.6f, 10f)
        }
    }.build()
}

@Composable
fun AuralisFloatingDock(
    currentDestination: AppDestination,
    hazeState: HazeState? = null,
    artworkUrl: String? = null,
    isPlaylistDetailOpen: Boolean = false,
    onDestinationClick: (AppDestination) -> Unit,
    onToggleHomeMenu: () -> Unit,
    onCreatePlaylist: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val appearance = com.auralis.music.ui.theme.LocalAppearanceSettings.current
    val isSlim = appearance.slimBottomNavigationBar
    val dockHeight = if (isSlim) 46.dp else 56.dp
    val buttonSize = if (isSlim) 46.dp else 56.dp
    val pillShape = RoundedCornerShape(30.dp)

    val surfaceColor = MaterialTheme.colorScheme.surface
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant
    val backgroundColor = MaterialTheme.colorScheme.background
    val primaryColor = MaterialTheme.dynamicPrimary
    val isDark = surfaceColor.luminance() < 0.5f

    val contentColor = if (isDark) Color.White else MaterialTheme.colorScheme.onSurface
    val secondaryContentColor = if (isDark) Color.White.copy(alpha = 0.65f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.60f)

    val dockBorderBrush = if (isDark) {
        Brush.verticalGradient(
            listOf(
                Color.White.copy(alpha = 0.28f),
                Color.White.copy(alpha = 0.10f),
                Color.White.copy(alpha = 0.04f)
            )
        )
    } else {
        Brush.verticalGradient(
            listOf(
                Color.Black.copy(alpha = 0.14f),
                Color.Black.copy(alpha = 0.07f),
                Color.Black.copy(alpha = 0.02f)
            )
        )
    }

    val fallbackGradient = if (isDark) {
        listOf(
            Color.White.copy(alpha = 0.14f),
            surfaceVariant.copy(alpha = 0.65f),
            backgroundColor.copy(alpha = 0.78f)
        )
    } else {
        listOf(
            surfaceColor.copy(alpha = 0.90f),
            surfaceVariant.copy(alpha = 0.80f),
            backgroundColor.copy(alpha = 0.88f)
        )
    }

    val shadowAmbient = Color.Black.copy(alpha = if (isDark) 0.40f else 0.08f)
    val shadowSpot = Color.Black.copy(alpha = if (isDark) 0.50f else 0.14f)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = if (isSlim) 3.dp else 6.dp),
        contentAlignment = Alignment.BottomCenter
    ) {
        // ── DOCK ROW: REAL-TIME FROSTED BACKDROP BLUR CAPSULE + MATCHING FROSTED BUTTON ──
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth()
        ) {
            // Main Frosted Glass Capsule (Matching Photo 2 Real Backdrop Blur)
            Box(
                modifier = Modifier
                    .height(dockHeight)
                    .shadow(
                        elevation = 16.dp,
                        shape = pillShape,
                        ambientColor = shadowAmbient,
                        spotColor = shadowSpot
                    )
                    .clip(pillShape)
                    .then(
                        if (hazeState != null) {
                            Modifier.hazeEffect(
                                state = hazeState,
                                style = HazeStyle(
                                    backgroundColor = surfaceColor,
                                    tint = dev.chrisbanes.haze.HazeTint(surfaceColor.copy(alpha = if (isDark) 0.58f else 0.72f)),
                                    blurRadius = 24.dp,
                                    noiseFactor = 0.02f
                                )
                            )
                        } else {
                            Modifier.background(Brush.verticalGradient(fallbackGradient))
                        }
                    )
                    .border(
                        width = 1.dp,
                        brush = dockBorderBrush,
                        shape = pillShape
                    )
                    .padding(horizontal = 4.dp, vertical = 4.dp)
            ) {
                // Inner Navigation Tabs Row
                Row(
                    modifier = Modifier
                        .fillMaxHeight()
                        .padding(horizontal = if (isSlim) 4.dp else 6.dp, vertical = if (isSlim) 3.dp else 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    DockTabItem(
                        destination = AppDestination.HOME,
                        icon = Icons.Outlined.Explore,
                        isSelected = currentDestination == AppDestination.HOME,
                        isDark = isDark,
                        contentColor = contentColor,
                        secondaryContentColor = secondaryContentColor,
                        primaryColor = primaryColor,
                        onClick = { onDestinationClick(AppDestination.HOME) }
                    )

                    DockTabItem(
                        destination = AppDestination.EXPLORE,
                        icon = Icons.Default.Search,
                        isSelected = currentDestination == AppDestination.EXPLORE,
                        isDark = isDark,
                        contentColor = contentColor,
                        secondaryContentColor = secondaryContentColor,
                        primaryColor = primaryColor,
                        onClick = { onDestinationClick(AppDestination.EXPLORE) }
                    )

                    DockTabItem(
                        destination = AppDestination.LIBRARY,
                        icon = Icons.Default.GridView,
                        isSelected = currentDestination == AppDestination.LIBRARY,
                        isDark = isDark,
                        contentColor = contentColor,
                        secondaryContentColor = secondaryContentColor,
                        primaryColor = primaryColor,
                        onClick = { onDestinationClick(AppDestination.LIBRARY) }
                    )
                }
            }

            // Right Action Button (••• on HOME, + on LIBRARY when not inside a playlist)
            val showRightButton = (currentDestination == AppDestination.HOME) ||
                    (currentDestination == AppDestination.LIBRARY && !isPlaylistDetailOpen)
            AnimatedVisibility(
                visible = showRightButton,
                enter = fadeIn(tween(160)) + expandHorizontally(tween(200)),
                exit = fadeOut(tween(140)) + shrinkHorizontally(tween(180))
            ) {
                Row {
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .size(buttonSize)
                            .shadow(
                                elevation = 16.dp,
                                shape = CircleShape,
                                ambientColor = shadowAmbient,
                                spotColor = shadowSpot
                            )
                            .clip(CircleShape)
                            .then(
                                if (hazeState != null) {
                                    Modifier.hazeEffect(
                                        state = hazeState,
                                        style = HazeStyle(
                                            backgroundColor = surfaceColor,
                                            tint = dev.chrisbanes.haze.HazeTint(surfaceColor.copy(alpha = if (isDark) 0.58f else 0.72f)),
                                            blurRadius = 24.dp,
                                            noiseFactor = 0.02f
                                        )
                                    )
                                } else {
                                    Modifier.background(Brush.verticalGradient(fallbackGradient))
                                }
                            )
                            .border(
                                width = 1.dp,
                                brush = dockBorderBrush,
                                shape = CircleShape
                            )
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = ripple(color = if (isDark) Color.White else primaryColor)
                            ) {
                                if (currentDestination == AppDestination.HOME) {
                                    onToggleHomeMenu()
                                } else if (currentDestination == AppDestination.LIBRARY) {
                                    onCreatePlaylist()
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        if (currentDestination == AppDestination.HOME) {
                            Icon(
                                imageVector = Icons.Default.MoreHoriz,
                                contentDescription = "Menu",
                                tint = contentColor,
                                modifier = Modifier.size(24.dp)
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = "Create",
                                tint = contentColor,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DockTabItem(
    destination: AppDestination,
    icon: ImageVector,
    isSelected: Boolean,
    isDark: Boolean,
    contentColor: Color,
    secondaryContentColor: Color,
    primaryColor: Color,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val tabShape = RoundedCornerShape(22.dp)

    val tabBgBrush = if (isDark) {
        Brush.verticalGradient(
            listOf(
                Color.White.copy(alpha = 0.16f),
                Color.White.copy(alpha = 0.06f)
            )
        )
    } else {
        Brush.verticalGradient(
            listOf(
                primaryColor.copy(alpha = 0.18f),
                primaryColor.copy(alpha = 0.06f)
            )
        )
    }

    val tabBorderBrush = if (isDark) {
        Brush.verticalGradient(
            listOf(
                Color.White.copy(alpha = 0.22f),
                Color.White.copy(alpha = 0.06f)
            )
        )
    } else {
        Brush.verticalGradient(
            listOf(
                primaryColor.copy(alpha = 0.32f),
                primaryColor.copy(alpha = 0.10f)
            )
        )
    }

    Box(
        modifier = Modifier
            .fillMaxHeight()
            .clip(tabShape)
            .then(
                if (isSelected) {
                    Modifier
                        .background(
                            tabBgBrush,
                            shape = tabShape
                        )
                        .border(
                            width = 1.dp,
                            brush = tabBorderBrush,
                            shape = tabShape
                        )
                } else Modifier
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .animateContentSize(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioLowBouncy,
                    stiffness = Spring.StiffnessMediumLow
                )
            )
            .padding(horizontal = if (isSelected) 14.dp else 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = destination.label,
                tint = if (isSelected) primaryColor else secondaryContentColor,
                modifier = Modifier.size(22.dp)
            )

            if (isSelected) {
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = destination.label,
                    color = contentColor,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.5.sp,
                    maxLines = 1
                )
            }
        }
    }
}
