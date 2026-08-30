package com.auralis.music.ui.screens

import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auralis.music.domain.model.AppearanceSettings
import com.auralis.music.ui.components.tactileBounce
import com.auralis.music.ui.theme.CuratedPalette
import com.auralis.music.ui.theme.CuratedPalettes
import com.auralis.music.ui.theme.getPaletteById

/**
 * Metrolist-Grade Theme & Colors Customization Screen.
 * Displays interactive live phone UI mockup synchronized with selected Theme Mode & Color Palette.
 */
@Composable
fun ThemeAndColorsScreen(
    settings: AppearanceSettings,
    onUpdateSettings: (AppearanceSettings) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val systemInDark = isSystemInDarkTheme()
    val scrollState = rememberScrollState()

    androidx.activity.compose.BackHandler(enabled = true) {
        onBack()
    }

    val currentPalette = remember(settings.colorPalette) {
        getPaletteById(settings.colorPalette)
    }

    val isDark = when (settings.appTheme) {
        "Light Mode" -> false
        "Pure AMOLED Black", "Midnight Velvet Dark", "Dark Mode" -> true
        else -> systemInDark
    }

    val isDynamicMonet = settings.colorPalette == "Dynamic (Material You)"

    val dynamicSysScheme = remember(context, isDark) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        } else null
    }

    val previewPrimary = if (isDynamicMonet && dynamicSysScheme != null) {
        dynamicSysScheme.primary
    } else {
        currentPalette.previewPrimary
    }

    val previewSecondary = if (isDynamicMonet && dynamicSysScheme != null) {
        dynamicSysScheme.secondary
    } else {
        currentPalette.previewSecondary
    }

    val previewTertiary = if (isDynamicMonet && dynamicSysScheme != null) {
        dynamicSysScheme.tertiary
    } else {
        currentPalette.previewTertiary
    }

    val primaryColor = previewPrimary
    val backgroundColor = MaterialTheme.colorScheme.background
    val surfaceColor = MaterialTheme.colorScheme.surface
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant
    val onBackground = MaterialTheme.colorScheme.onBackground

    // Preview phone background color dynamically derived from theme mode & dynamic scheme
    val previewBg = if (isDynamicMonet && dynamicSysScheme != null) {
        dynamicSysScheme.background
    } else when (settings.appTheme) {
        "Pure AMOLED Black" -> Color.Black
        "Midnight Velvet Dark" -> Color(0xFF100E0C)
        "Light Mode" -> Color(0xFFF9FAFB)
        "Dark Mode" -> Color(0xFF0F1210)
        else -> if (systemInDark) Color(0xFF0F1210) else Color(0xFFF9FAFB)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(backgroundColor)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // ── TOP BAR ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(surfaceColor)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), CircleShape)
                        .clickable { onBack() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = onBackground,
                        modifier = Modifier.size(22.dp)
                    )
                }

                Spacer(modifier = Modifier.width(16.dp))

                Text(
                    text = "Theme & Colors",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = onBackground,
                    fontSize = 22.sp
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // ── INTERACTIVE PHONE MOCKUP PREVIEW ──
            Box(
                modifier = Modifier
                    .width(190.dp)
                    .height(290.dp)
                    .shadow(elevation = 16.dp, shape = RoundedCornerShape(28.dp), ambientColor = Color.Black.copy(alpha = 0.4f), spotColor = Color.Black.copy(alpha = 0.4f))
                    .clip(RoundedCornerShape(28.dp))
                    .background(previewBg)
                    .border(2.dp, onBackground.copy(alpha = 0.15f), RoundedCornerShape(28.dp))
                    .padding(14.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    // Top header row: Left dot + Right dot
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .clip(CircleShape)
                                .background(previewPrimary)
                        )
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .clip(CircleShape)
                                .background(previewSecondary)
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Hero big card
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(84.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(previewPrimary)
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    // Two split lower cards
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(95.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(previewSecondary)
                        )
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(95.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(previewTertiary)
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Bottom bar with floating accent button
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(20.dp)
                                .clip(CircleShape)
                                .background(previewPrimary)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(36.dp))

            // ── BOTTOM CONFIGURATION CARD (THEME MODE + COLOR PALETTE) ──
            Surface(
                shape = RoundedCornerShape(26.dp),
                color = surfaceColor,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(22.dp)
                ) {
                    // SECTION 1: THEME MODE
                    Text(
                        text = "Theme Mode",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = onBackground,
                        fontSize = 17.sp
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 1. Follow System / Auto
                        ThemeModeSelector(
                            selected = settings.appTheme == "Follow system",
                            onClick = { onUpdateSettings(settings.copy(appTheme = "Follow system")) },
                            activeColor = primaryColor
                        ) {
                            Icon(
                                imageVector = Icons.Default.Sync,
                                contentDescription = "Follow system",
                                tint = onBackground,
                                modifier = Modifier.size(22.dp)
                            )
                        }

                        // 2. Light Mode
                        ThemeModeSelector(
                            selected = settings.appTheme == "Light Mode",
                            onClick = { onUpdateSettings(settings.copy(appTheme = "Light Mode")) },
                            activeColor = primaryColor
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .background(Color.White)
                                    .border(1.dp, Color(0x33000000), CircleShape)
                            )
                        }

                        // 3. Dark Mode / Midnight
                        ThemeModeSelector(
                            selected = settings.appTheme == "Dark Mode" || settings.appTheme == "Midnight Velvet Dark",
                            onClick = { onUpdateSettings(settings.copy(appTheme = "Dark Mode")) },
                            activeColor = primaryColor
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF221F1C))
                            )
                        }

                        // 4. Pure AMOLED Black
                        ThemeModeSelector(
                            selected = settings.appTheme == "Pure AMOLED Black",
                            onClick = { onUpdateSettings(settings.copy(appTheme = "Pure AMOLED Black")) },
                            activeColor = primaryColor
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .background(Color.Black)
                                    .border(1.dp, Color(0x33FFFFFF), CircleShape)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(28.dp))

                    // SECTION 2: COLOR PALETTE
                    Text(
                        text = "Color Palette",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = onBackground,
                        fontSize = 17.sp
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    val paletteScrollState = rememberScrollState()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(paletteScrollState),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 1. Dynamic Wallpaper (Monet / Phone System Theme - 3 Tones)
                        val dynamicTop = dynamicSysScheme?.primary ?: Color(0xFF6750A4)
                        val dynamicBottomLeft = dynamicSysScheme?.secondary ?: Color(0xFFD0BCFF)
                        val dynamicBottomRight = dynamicSysScheme?.tertiary ?: Color(0xFFCCC2DC)

                        PaletteSelectorItem(
                            selected = isDynamicMonet,
                            onClick = {
                                onUpdateSettings(
                                    settings.copy(
                                        colorPalette = "Dynamic (Material You)",
                                        dynamicTheme = true
                                    )
                                )
                            },
                            activeColor = primaryColor
                        ) {
                            Box(
                                modifier = Modifier.size(46.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                TripleTonePaletteCircle(
                                    topColor = dynamicTop,
                                    bottomLeftColor = dynamicBottomLeft,
                                    bottomRightColor = dynamicBottomRight,
                                    modifier = Modifier.size(46.dp)
                                )
                                Box(
                                    modifier = Modifier
                                        .size(26.dp)
                                        .clip(CircleShape)
                                        .background(Color.Black.copy(alpha = 0.45f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Palette,
                                        contentDescription = "Dynamic Palette",
                                        tint = Color.White,
                                        modifier = Modifier.size(15.dp)
                                    )
                                }
                            }
                        }

                        // Curated Palettes (Triple-Tone Split Circles: Top, Bottom-Left, Bottom-Right)
                        CuratedPalettes.forEach { palette ->
                            val isSelected = !isDynamicMonet && settings.colorPalette == palette.id
                            PaletteSelectorItem(
                                selected = isSelected,
                                onClick = {
                                    onUpdateSettings(
                                        settings.copy(
                                            colorPalette = palette.id,
                                            dynamicTheme = false
                                        )
                                    )
                                },
                                activeColor = primaryColor
                            ) {
                                TripleTonePaletteCircle(
                                    topColor = palette.previewPrimary,
                                    bottomLeftColor = palette.previewSecondary,
                                    bottomRightColor = palette.previewTertiary,
                                    modifier = Modifier.size(46.dp)
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(28.dp))
        }
    }
}

@Composable
private fun ThemeModeSelector(
    selected: Boolean,
    activeColor: Color,
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    val scale by animateFloatAsState(
        targetValue = if (selected) 1.06f else 1.0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "modeScale"
    )

    Box(
        modifier = Modifier
            .size(54.dp)
            .scale(scale)
            .clip(CircleShape)
            .then(
                if (selected) Modifier.border(2.5.dp, activeColor, CircleShape)
                else Modifier.border(1.dp, MaterialTheme.colorScheme.onBackground.copy(alpha = 0.15f), CircleShape)
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

@Composable
private fun PaletteSelectorItem(
    selected: Boolean,
    activeColor: Color,
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    val scale by animateFloatAsState(
        targetValue = if (selected) 1.08f else 1.0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "paletteScale"
    )

    Box(
        modifier = Modifier
            .size(54.dp)
            .scale(scale)
            .clip(CircleShape)
            .then(
                if (selected) Modifier.border(2.5.dp, activeColor, CircleShape)
                else Modifier.border(1.dp, MaterialTheme.colorScheme.onBackground.copy(alpha = 0.15f), CircleShape)
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

/**
 * Metrolist-Grade Triple-Tone Palette Circle:
 * - Top half (180° sweep from 180° to 360°): Primary shade
 * - Bottom-left quadrant (90° sweep from 90° to 180°): Secondary shade
 * - Bottom-right quadrant (90° sweep from 0° to 90°): Tertiary shade
 */
@Composable
private fun TripleTonePaletteCircle(
    topColor: Color,
    bottomLeftColor: Color,
    bottomRightColor: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier.clip(CircleShape)) {
        // Top half
        drawArc(
            color = topColor,
            startAngle = 180f,
            sweepAngle = 180f,
            useCenter = true
        )

        // Bottom-left quadrant
        drawArc(
            color = bottomLeftColor,
            startAngle = 90f,
            sweepAngle = 90f,
            useCenter = true
        )

        // Bottom-right quadrant
        drawArc(
            color = bottomRightColor,
            startAngle = 0f,
            sweepAngle = 90f,
            useCenter = true
        )
    }
}
