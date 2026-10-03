package com.auralis.music.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.derivedStateOf
import com.auralis.music.ui.glass.liquidGlass
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
import com.auralis.music.ui.theme.dynamicBackground
import com.auralis.music.ui.theme.dynamicOnSurface
import com.auralis.music.ui.theme.dynamicPrimary
import com.auralis.music.ui.theme.dynamicSurface
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.offset
import kotlin.math.roundToInt
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
    onExpandRequest: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    // Liquid glass theme: glass surfaces, and a minimized form while a page scrolls down.
    val glass = com.auralis.music.ui.glass.LocalLiquidGlass.current
    val collapse = com.auralis.music.ui.glass.LocalDockCollapse.current
    // Only decides what a tap does (expand vs. navigate). The minimize motion itself is
    // drawn straight from collapseProgress, see DockTabItem.
    val minimized by remember(collapse) { derivedStateOf { (collapse?.value ?: 0f) > 0.5f } }
    val collapseProgress: () -> Float = { collapse?.value ?: 0f }
    val appearance = com.auralis.music.ui.theme.LocalAppearanceSettings.current
    val isSlim = appearance.slimBottomNavigationBar
    val dockHeight = if (isSlim) 46.dp else 56.dp
    val buttonSize = if (isSlim) 46.dp else 56.dp
    val pillShape = RoundedCornerShape(30.dp)

    val surfaceColor = MaterialTheme.dynamicSurface
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant
    val backgroundColor = MaterialTheme.dynamicBackground
    val primaryColor = MaterialTheme.dynamicPrimary
    val isDark = surfaceColor.luminance() < 0.5f

    val contentColor = if (isDark) Color.White else MaterialTheme.dynamicOnSurface
    val secondaryContentColor = if (isDark) Color.White.copy(alpha = 0.65f) else MaterialTheme.dynamicOnSurface.copy(alpha = 0.60f)

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
            Color.White.copy(alpha = 0.16f),
            surfaceVariant.copy(alpha = 0.35f),
            surfaceColor.copy(alpha = 0.45f)
        )
    } else {
        listOf(
            Color.White.copy(alpha = 0.55f),
            surfaceVariant.copy(alpha = 0.40f),
            surfaceColor.copy(alpha = 0.50f)
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
        DockRowLayout(
            collapseProgress = collapseProgress,
            modifier = Modifier.fillMaxWidth()
        ) {
            // Main Frosted Glass Capsule (Matching Photo 2 Real Backdrop Blur)
            Box(
                modifier = Modifier
                    // No animateContentSize here: the tabs inside already animate their own
                    // widths, and a second size animation chasing them made the capsule settle late.
                    .height(dockHeight)
                    .then(
                        if (glass != null) {
                            Modifier.liquidGlass(glass, pillShape).clip(pillShape)
                        } else Modifier
                            .shadow(
                                elevation = 16.dp,
                                shape = pillShape,
                                ambientColor = shadowAmbient,
                                spotColor = shadowSpot
                            )
                            .clip(pillShape)
                    )
                    .then(
                        if (glass != null) {
                            Modifier
                        } else if (hazeState != null) {
                            Modifier.hazeEffect(
                                state = hazeState,
                                style = HazeStyle(
                                    backgroundColor = Color.Transparent,
                                    tint = dev.chrisbanes.haze.HazeTint(surfaceColor.copy(alpha = if (isDark) 0.40f else 0.50f)),
                                    blurRadius = 24.dp,
                                    noiseFactor = 0.02f
                                )
                            )
                        } else {
                            Modifier.background(Brush.verticalGradient(fallbackGradient))
                        }
                    )
                    .then(
                        if (glass != null) Modifier
                        else Modifier.border(width = 1.dp, brush = dockBorderBrush, shape = pillShape)
                    )
                    .then(
                        // Minimized, the capsule is a single tab: tapping it (or the tab itself)
                        // brings the full dock back so another tab can be picked.
                        if (minimized) Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onExpandRequest
                        ) else Modifier
                    )
                    .padding(horizontal = 4.dp, vertical = 4.dp)
            ) {
                // Inner Navigation Tabs Row
                Row(
                    modifier = Modifier
                        .fillMaxHeight()
                        .padding(horizontal = if (isSlim) 4.dp else 6.dp, vertical = if (isSlim) 3.dp else 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    DockTabItem(
                        destination = AppDestination.HOME,
                        icon = Icons.Outlined.Explore,
                        isSelected = currentDestination == AppDestination.HOME,
                        collapseProgress = collapseProgress,
                        isDark = isDark,
                        contentColor = contentColor,
                        secondaryContentColor = secondaryContentColor,
                        primaryColor = primaryColor,
                        onClick = { if (minimized) onExpandRequest() else onDestinationClick(AppDestination.HOME) }
                    )

                    // Gaps close with the collapse too: minimized, only one tab remains.
                    DockGap(collapseProgress)
                    DockTabItem(
                        destination = AppDestination.EXPLORE,
                        icon = Icons.Default.Search,
                        isSelected = currentDestination == AppDestination.EXPLORE,
                        collapseProgress = collapseProgress,
                        isDark = isDark,
                        contentColor = contentColor,
                        secondaryContentColor = secondaryContentColor,
                        primaryColor = primaryColor,
                        onClick = { if (minimized) onExpandRequest() else onDestinationClick(AppDestination.EXPLORE) }
                    )

                    // Tab gaps close with the collapse too: minimized, only one tab remains.
                    DockGap(collapseProgress)
                    DockTabItem(
                        destination = AppDestination.LIBRARY,
                        icon = Icons.Default.GridView,
                        isSelected = currentDestination == AppDestination.LIBRARY,
                        collapseProgress = collapseProgress,
                        isDark = isDark,
                        contentColor = contentColor,
                        secondaryContentColor = secondaryContentColor,
                        primaryColor = primaryColor,
                        onClick = { if (minimized) onExpandRequest() else onDestinationClick(AppDestination.LIBRARY) }
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
                            .then(
                                if (glass != null) {
                                    Modifier.liquidGlass(glass, CircleShape).clip(CircleShape)
                                } else Modifier
                                    .shadow(
                                        elevation = 16.dp,
                                        shape = CircleShape,
                                        ambientColor = shadowAmbient,
                                        spotColor = shadowSpot
                                    )
                                    .clip(CircleShape)
                            )
                            .then(
                                if (glass != null) {
                                    Modifier
                                } else if (hazeState != null) {
                                    Modifier.hazeEffect(
                                        state = hazeState,
                                        style = HazeStyle(
                                            backgroundColor = Color.Transparent,
                                            tint = dev.chrisbanes.haze.HazeTint(surfaceColor.copy(alpha = if (isDark) 0.40f else 0.50f)),
                                            blurRadius = 24.dp,
                                            noiseFactor = 0.02f
                                        )
                                    )
                                } else {
                                    Modifier.background(Brush.verticalGradient(fallbackGradient))
                                }
                            )
                            .then(
                                if (glass != null) Modifier
                                else Modifier.border(width = 1.dp, brush = dockBorderBrush, shape = CircleShape)
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
    collapseProgress: () -> Float,
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

    val selectedFraction by animateFloatAsState(
        targetValue = if (isSelected) 1f else 0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "dockTabSelected"
    )
    // The dock spring overshoots past 1; sizes must not go negative.
    val collapse = { collapseProgress().coerceIn(0f, 1f) }

    // Minimized dock: only the selected tab stays, as a bare icon in the circle. Everything
    // the minimize changes (other tabs, gaps, label, padding, highlight) is read from the same
    // collapse progress that slides the dock, at layout/draw time. Separate size animations
    // here (AnimatedVisibility + animateContentSize) used to finish out of step with the
    // slide, so the capsule paused, then shrank and crept into place after the round button.
    Box(
        modifier = Modifier
            .fillMaxHeight()
            .then(
                if (isSelected) Modifier
                else Modifier
                    .horizontalReveal { 1f - collapse() }
                    .graphicsLayer { alpha = 1f - collapse() }
            )
            .clip(tabShape)
            .drawBehind {
                val a = (selectedFraction * (1f - collapse())).coerceIn(0f, 1f)
                if (a > 0f) {
                    val stroke = 1.dp.toPx()
                    val radius = minOf(22.dp.toPx(), size.height / 2f)
                    drawRoundRect(brush = tabBgBrush, cornerRadius = CornerRadius(radius), alpha = a)
                    // Inset by half the stroke so the 1dp border sits fully inside, as border() drew it.
                    drawRoundRect(
                        brush = tabBorderBrush,
                        topLeft = Offset(stroke / 2f, stroke / 2f),
                        size = Size(size.width - stroke, size.height - stroke),
                        cornerRadius = CornerRadius(radius - stroke / 2f),
                        alpha = a,
                        style = Stroke(stroke)
                    )
                }
            }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .layout { measurable, constraints ->
                val padding = lerp(lerp(12.dp, 14.dp, selectedFraction), 7.dp, collapse()).roundToPx()
                val placeable = measurable.measure(constraints.offset(horizontal = -2 * padding))
                layout(placeable.width + 2 * padding, placeable.height) {
                    placeable.placeRelative(padding, 0)
                }
            },
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

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .horizontalReveal { selectedFraction * (1f - collapse()) }
                    .graphicsLayer { alpha = (selectedFraction * (1f - collapse())).coerceIn(0f, 1f) }
                    // The icon already announces the tab.
                    .clearAndSetSemantics { }
            ) {
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

/** The gap between two tabs; closes with the collapse, since minimized only one tab remains. */
@Composable
private fun DockGap(collapseProgress: () -> Float) {
    Spacer(Modifier.horizontalReveal { 1f - collapseProgress() }.width(4.dp))
}

/**
 * Shows [fraction] (0..1) of the content's natural width and clips the rest. The fraction is
 * read at layout, so a per-frame value re-lays out without recomposing.
 */
private fun Modifier.horizontalReveal(fraction: () -> Float): Modifier = this
    .clipToBounds()
    .layout { measurable, constraints ->
        val placeable = measurable.measure(constraints.copy(minWidth = 0, maxWidth = Constraints.Infinity))
        val width = (placeable.width * fraction().coerceIn(0f, 1f)).roundToInt()
        layout(width, placeable.height) { placeable.placeRelative(0, 0) }
    }

/**
 * Places the dock capsule and its round button. Expanded (progress 0) they sit centred side by
 * side; minimized (progress 1) the capsule moves to the left edge and the button to the right,
 * leaving the middle for the mini player. Progress is read at placement, so the slide re-places
 * without recomposing.
 */
@Composable
private fun DockRowLayout(
    collapseProgress: () -> Float,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    androidx.compose.ui.layout.Layout(content = content, modifier = modifier) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val placeables = measurables.map { it.measure(loose) }
        val width = constraints.maxWidth
        val height = placeables.maxOfOrNull { it.height } ?: 0
        layout(width, height) {
            val p = collapseProgress().coerceIn(0f, 1.1f)
            val groupWidth = placeables.sumOf { it.width }
            var expandedX = (width - groupWidth) / 2
            placeables.forEachIndexed { index, placeable ->
                val collapsedX = if (index == 0) 0 else width - placeable.width
                val x = expandedX + ((collapsedX - expandedX) * p).toInt()
                placeable.placeRelative(x, (height - placeable.height) / 2)
                expandedX += placeable.width
            }
        }
    }
}
