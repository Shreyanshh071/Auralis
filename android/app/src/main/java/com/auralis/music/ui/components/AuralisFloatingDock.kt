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
                        ambientColor = Color.Black.copy(alpha = 0.40f),
                        spotColor = Color.Black.copy(alpha = 0.50f)
                    )
                    .clip(pillShape)
                    .then(
                        if (hazeState != null) {
                            Modifier.hazeEffect(
                                state = hazeState,
                                style = HazeStyle(
                                    backgroundColor = Color(0xFF141416),
                                    tint = dev.chrisbanes.haze.HazeTint(Color(0xFF141416).copy(alpha = 0.52f)),
                                    blurRadius = 28.dp,
                                    noiseFactor = 0.03f
                                )
                            )
                        } else {
                            Modifier.background(
                                Brush.verticalGradient(
                                    colors = listOf(
                                        Color.White.copy(alpha = 0.16f),
                                        Color(0xFF202024).copy(alpha = 0.58f),
                                        Color(0xFF0E0E10).copy(alpha = 0.72f)
                                    )
                                )
                            )
                        }
                    )
                    .border(
                        width = 1.dp,
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                Color.White.copy(alpha = 0.28f),
                                Color.White.copy(alpha = 0.10f),
                                Color.White.copy(alpha = 0.04f)
                            )
                        ),
                        shape = pillShape
                    )
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
                        onClick = { onDestinationClick(AppDestination.HOME) }
                    )

                    DockTabItem(
                        destination = AppDestination.EXPLORE,
                        icon = Icons.Default.Search,
                        isSelected = currentDestination == AppDestination.EXPLORE,
                        onClick = { onDestinationClick(AppDestination.EXPLORE) }
                    )

                    DockTabItem(
                        destination = AppDestination.LIBRARY,
                        icon = Icons.Default.GridView,
                        isSelected = currentDestination == AppDestination.LIBRARY,
                        onClick = { onDestinationClick(AppDestination.LIBRARY) }
                    )
                }
            }

            // Right Action Button (••• on HOME, + on LIBRARY) - Matching Frosted Glass Theme
            val showRightButton = currentDestination == AppDestination.HOME || currentDestination == AppDestination.LIBRARY
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
                                ambientColor = Color.Black.copy(alpha = 0.40f),
                                spotColor = Color.Black.copy(alpha = 0.50f)
                            )
                            .clip(CircleShape)
                            .then(
                                if (hazeState != null) {
                                    Modifier.hazeEffect(
                                        state = hazeState,
                                        style = HazeStyle(
                                            backgroundColor = Color(0xFF141416),
                                            tint = dev.chrisbanes.haze.HazeTint(Color(0xFF141416).copy(alpha = 0.52f)),
                                            blurRadius = 28.dp,
                                            noiseFactor = 0.03f
                                        )
                                    )
                                } else {
                                    Modifier.background(
                                        Brush.verticalGradient(
                                            colors = listOf(
                                                Color.White.copy(alpha = 0.16f),
                                                Color(0xFF202024).copy(alpha = 0.58f),
                                                Color(0xFF0E0E10).copy(alpha = 0.72f)
                                            )
                                        )
                                    )
                                }
                            )
                            .border(
                                width = 1.dp,
                                brush = Brush.verticalGradient(
                                    colors = listOf(
                                        Color.White.copy(alpha = 0.28f),
                                        Color.White.copy(alpha = 0.10f),
                                        Color.White.copy(alpha = 0.04f)
                                    )
                                ),
                                shape = CircleShape
                            )
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = ripple(color = Color.White)
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
                                tint = Color.White,
                                modifier = Modifier.size(24.dp)
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = "Create",
                                tint = Color.White,
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
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val primaryColor = MaterialTheme.colorScheme.primary
    val tabShape = RoundedCornerShape(22.dp)

    Box(
        modifier = Modifier
            .fillMaxHeight()
            .clip(tabShape)
            .then(
                if (isSelected) {
                    Modifier
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    Color.White.copy(alpha = 0.16f),
                                    Color.White.copy(alpha = 0.06f)
                                )
                            ),
                            shape = tabShape
                        )
                        .border(
                            width = 1.dp,
                            brush = Brush.verticalGradient(
                                listOf(
                                    Color.White.copy(alpha = 0.22f),
                                    Color.White.copy(alpha = 0.06f)
                                )
                            ),
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
                tint = if (isSelected) primaryColor else Color.White.copy(alpha = 0.65f),
                modifier = Modifier.size(22.dp)
            )

            if (isSelected) {
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = destination.label,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.5.sp,
                    maxLines = 1
                )
            }
        }
    }
}
