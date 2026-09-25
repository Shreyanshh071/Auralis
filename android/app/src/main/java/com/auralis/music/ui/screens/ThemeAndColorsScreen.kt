package com.auralis.music.ui.screens

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auralis.music.domain.model.AppearanceSettings
import com.auralis.music.ui.components.tactileBounce
import com.auralis.music.ui.theme.ArtworkPaletteCache
import com.auralis.music.ui.theme.CuratedPalette
import com.auralis.music.ui.theme.CuratedPalettes
import com.auralis.music.ui.theme.dynamicBackground
import com.auralis.music.ui.theme.dynamicColorSchemeFromSeed
import com.auralis.music.ui.theme.dynamicOnBackground
import com.auralis.music.ui.theme.dynamicPrimary
import com.auralis.music.ui.theme.dynamicSurface
import com.auralis.music.ui.theme.getPaletteById
import com.auralis.music.ui.theme.isLightColor

/**
 * Polished Theme & Colors Customization Screen.
 *
 * Features:
 * 1. Live Responsive Preview Card:
 *    A large, rounded app mockup displaying real-time responsive color previews
 *    (Header, Hero now-playing block, split content cards, docked mini-player bar).
 *    Reflects the currently playing song's dynamic artwork palette when Dynamic is selected.
 * 2. Visual Theme Mode Selector:
 *    Distinct visual cards for System, Light, Dark, and AMOLED with clear labels
 *    and animated checkmark selection indicators.
 * 3. Horizontally Scrollable Color Palette Selector:
 *    Visual multi-tone swatches for Dynamic (artwork-derived) and Curated Palettes with container
 *    highlights, animated checkmarks, and palette names.
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

    BackHandler(enabled = true) {
        onBack()
    }

    val currentPalette = remember(settings.colorPalette) {
        getPaletteById(settings.colorPalette)
    }

    val isDark = when (settings.appTheme) {
        "Light Mode", "Light" -> false
        "Pure AMOLED Black", "AMOLED", "Midnight Velvet Dark", "Dark Mode", "Dark" -> true
        else -> systemInDark
    }
    val isAmoled = settings.appTheme == "Pure AMOLED Black" || settings.appTheme == "AMOLED"

    val isDynamic = settings.colorPalette == "Dynamic" ||
            settings.colorPalette == "Dynamic (Material You)" ||
            (settings.dynamicTheme && CuratedPalettes.none { it.id == settings.colorPalette })

    // Observe shared extracted artwork palette
    val sharedArtworkPalette by ArtworkPaletteCache.currentPalette.collectAsState()
    val hasSongArtwork = sharedArtworkPalette != ArtworkPaletteCache.defaultPalette

    val dynamicSysScheme = remember(context, isDark) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        } else null
    }

    // 1. Determine active seed color from the selected palette or dynamic source
    // The top color of the swatch circle is the primary seed color that dictates the background & UI scheme!
    val activeSeedColor = if (isDynamic) {
        if (hasSongArtwork) {
            sharedArtworkPalette.seedColor.takeIf { it != Color.Unspecified } ?: sharedArtworkPalette.primary
        } else {
            dynamicSysScheme?.primary ?: Color(0xFF6750A4)
        }
    } else {
        if (isDark) currentPalette.primaryDark else currentPalette.primaryLight
    }

    val activeSecondaryColor = if (isDynamic) {
        if (hasSongArtwork) sharedArtworkPalette.secondary else dynamicSysScheme?.secondary
    } else {
        if (isDark) currentPalette.secondaryDark else currentPalette.secondaryLight
    }

    val activeTertiaryColor = if (isDynamic) {
        if (hasSongArtwork) sharedArtworkPalette.tertiary else dynamicSysScheme?.tertiary
    } else {
        if (isDark) currentPalette.tertiaryDark else currentPalette.tertiaryLight
    }

    // Coherent Material 3 scheme dynamically generated from the active palette's top color
    val activePreviewScheme = remember(
        activeSeedColor, activeSecondaryColor, activeTertiaryColor,
        isDark, isAmoled, settings.appTheme, isDynamic, hasSongArtwork, sharedArtworkPalette, currentPalette
    ) {
        if (isDynamic && !hasSongArtwork && dynamicSysScheme != null) {
            if (isAmoled) {
                dynamicSysScheme.copy(
                    background = Color.Black,
                    surface = Color.Black,
                    surfaceVariant = Color(0xFF121214)
                )
            } else if (settings.appTheme == "Midnight Velvet Dark") {
                dynamicSysScheme.copy(
                    background = Color(0xFF0A0A0C),
                    surface = Color(0xFF121215)
                )
            } else {
                dynamicSysScheme
            }
        } else {
            val scheme = dynamicColorSchemeFromSeed(
                seedColor = activeSeedColor,
                isDark = isDark,
                isAmoled = isAmoled,
                appTheme = settings.appTheme,
                secondaryColor = activeSecondaryColor,
                tertiaryColor = activeTertiaryColor,
                isMonochrome = if (isDynamic && hasSongArtwork) sharedArtworkPalette.isMonochrome else false
            )

            if (!isDynamic) {
                if (isDark) {
                    val onPri = if (isLightColor(currentPalette.primaryDark)) Color(0xFF1C2000) else Color.White
                    val onSec = if (isLightColor(currentPalette.secondaryDark)) Color(0xFF1C2000) else Color.White
                    scheme.copy(
                        primary = currentPalette.primaryDark,
                        onPrimary = onPri,
                        secondary = currentPalette.secondaryDark,
                        onSecondary = onSec,
                        tertiary = currentPalette.tertiaryDark,
                        primaryContainer = currentPalette.primaryDark.copy(alpha = 0.28f),
                        onPrimaryContainer = currentPalette.primaryDark,
                        secondaryContainer = currentPalette.secondaryDark.copy(alpha = 0.24f),
                        onSecondaryContainer = currentPalette.secondaryDark
                    )
                } else {
                    scheme.copy(
                        primary = currentPalette.primaryLight,
                        onPrimary = Color.White,
                        secondary = currentPalette.secondaryLight,
                        onSecondary = Color.White,
                        tertiary = currentPalette.tertiaryLight,
                        primaryContainer = currentPalette.primaryLight.copy(alpha = 0.16f),
                        onPrimaryContainer = currentPalette.primaryLight,
                        secondaryContainer = currentPalette.secondaryLight.copy(alpha = 0.14f),
                        onSecondaryContainer = currentPalette.secondaryLight
                    )
                }
            } else {
                scheme
            }
        }
    }

    val targetPreviewPrimary = activePreviewScheme.primary
    val targetPreviewSecondary = activePreviewScheme.secondary
    val targetPreviewTertiary = activePreviewScheme.tertiary
    val targetPreviewBg = activePreviewScheme.background
    val targetPreviewSurfaceContainer = activePreviewScheme.surfaceContainer

    // Smooth color transitions in the live preview card when the user switches themes or palettes
    val colorTween = remember { tween<Color>(durationMillis = 350, easing = FastOutSlowInEasing) }
    val animPreviewBg by animateColorAsState(targetPreviewBg, colorTween, label = "animPreviewBg")
    val animPreviewPrimary by animateColorAsState(targetPreviewPrimary, colorTween, label = "animPreviewPrimary")
    val animPreviewSecondary by animateColorAsState(targetPreviewSecondary, colorTween, label = "animPreviewSecondary")
    val animPreviewTertiary by animateColorAsState(targetPreviewTertiary, colorTween, label = "animPreviewTertiary")
    val animPreviewSurfaceContainer by animateColorAsState(targetPreviewSurfaceContainer, colorTween, label = "animPreviewSurfaceContainer")

    val backgroundColor = MaterialTheme.dynamicBackground
    val surfaceColor = MaterialTheme.dynamicSurface
    val onBackground = MaterialTheme.dynamicOnBackground

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
            // ── TOP APP BAR ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(surfaceColor)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f), CircleShape)
                        .tactileBounce()
                        .clickable { onBack() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = onBackground,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.width(16.dp))

                Column {
                    Text(
                        text = "Theme & Colors",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = onBackground,
                        fontSize = 22.sp
                    )
                    Text(
                        text = "Customize player & interface palette",
                        style = MaterialTheme.typography.bodySmall,
                        color = onBackground.copy(alpha = 0.60f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // ── 1. LARGE LIVE PREVIEW CARD (APP MOCKUP) ──
            val isPreviewDark = isDark
            Box(
                modifier = Modifier
                    .width(220.dp)
                    .height(290.dp)
                    .shadow(
                        elevation = 16.dp,
                        shape = RoundedCornerShape(26.dp),
                        ambientColor = Color.Black.copy(alpha = 0.40f),
                        spotColor = animPreviewPrimary.copy(alpha = 0.30f)
                    )
                    .clip(RoundedCornerShape(26.dp))
                    .background(animPreviewBg)
                    .border(
                        BorderStroke(
                            1.5.dp,
                            if (isPreviewDark) Color.White.copy(alpha = 0.12f)
                            else Color.Black.copy(alpha = 0.10f)
                        ),
                        RoundedCornerShape(26.dp)
                    )
                    .padding(14.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    // Mockup Header: Pill Search Bar + Action Dot
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .width(80.dp)
                                .height(12.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(animPreviewSecondary.copy(alpha = 0.35f))
                        )
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .clip(CircleShape)
                                .background(animPreviewPrimary)
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Mockup Hero Now Playing Block
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(82.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(
                                Brush.verticalGradient(
                                    listOf(
                                        animPreviewPrimary,
                                        animPreviewPrimary.copy(alpha = 0.85f)
                                    )
                                )
                            )
                            .padding(10.dp)
                    ) {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .width(55.dp)
                                        .height(8.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(Color.White.copy(alpha = 0.50f))
                                )
                                Box(
                                    modifier = Modifier
                                        .size(20.dp)
                                        .clip(CircleShape)
                                        .background(Color.White.copy(alpha = 0.25f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.PlayArrow,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(12.dp)
                                    )
                                }
                            }
                            Box(
                                modifier = Modifier
                                    .width(90.dp)
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(Color.White.copy(alpha = 0.35f))
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Mockup Middle Split Cards (Secondary & Tertiary)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Left Card (Secondary)
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(64.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(animPreviewSecondary.copy(alpha = 0.85f))
                                .padding(8.dp)
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Box(
                                    modifier = Modifier
                                        .width(36.dp)
                                        .height(6.dp)
                                        .clip(RoundedCornerShape(3.dp))
                                        .background(Color.White.copy(alpha = 0.45f))
                                )
                                Box(
                                    modifier = Modifier
                                        .width(22.dp)
                                        .height(5.dp)
                                        .clip(RoundedCornerShape(3.dp))
                                        .background(Color.White.copy(alpha = 0.30f))
                                )
                            }
                        }

                        // Right Card (Tertiary)
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(64.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(animPreviewTertiary.copy(alpha = 0.85f))
                                .padding(8.dp)
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Box(
                                    modifier = Modifier
                                        .width(36.dp)
                                        .height(6.dp)
                                        .clip(RoundedCornerShape(3.dp))
                                        .background(Color.White.copy(alpha = 0.45f))
                                )
                                Box(
                                    modifier = Modifier
                                        .width(22.dp)
                                        .height(5.dp)
                                        .clip(RoundedCornerShape(3.dp))
                                        .background(Color.White.copy(alpha = 0.30f))
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Mockup Docked Mini-Player Bar
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(34.dp)
                            .clip(RoundedCornerShape(17.dp))
                            .background(
                                if (isPreviewDark) animPreviewSurfaceContainer
                                else Color.White
                            )
                            .border(
                                1.dp,
                                if (isPreviewDark) Color.White.copy(alpha = 0.08f)
                                else Color.Black.copy(alpha = 0.08f),
                                RoundedCornerShape(17.dp)
                            )
                            .padding(horizontal = 7.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(20.dp)
                                        .clip(RoundedCornerShape(5.dp))
                                        .background(animPreviewPrimary)
                                )
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Box(
                                        modifier = Modifier
                                            .width(42.dp)
                                            .height(5.dp)
                                            .clip(RoundedCornerShape(2.dp))
                                            .background(
                                                if (isPreviewDark) Color.White.copy(alpha = 0.70f)
                                                else Color.Black.copy(alpha = 0.70f)
                                            )
                                    )
                                    Box(
                                        modifier = Modifier
                                            .width(26.dp)
                                            .height(4.dp)
                                            .clip(RoundedCornerShape(2.dp))
                                            .background(animPreviewSecondary.copy(alpha = 0.60f))
                                    )
                                }
                            }
                            Box(
                                modifier = Modifier
                                    .size(18.dp)
                                    .clip(CircleShape)
                                    .background(animPreviewPrimary),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = null,
                                    tint = if (isLightColor(animPreviewPrimary)) Color(0xFF1C1B1F) else Color.White,
                                    modifier = Modifier.size(11.dp)
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // ── 2. THEME MODE SELECTOR ──
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp)
                ) {
                    Text(
                        text = "Theme Mode",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = onBackground,
                        fontSize = 17.sp
                    )
                    Text(
                        text = "Appearance and lightness",
                        style = MaterialTheme.typography.bodySmall,
                        color = onBackground.copy(alpha = 0.60f)
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 1. System
                        val isSystemSelected = settings.appTheme == "Follow system"
                        ThemeModeCard(
                            title = "System",
                            selected = isSystemSelected,
                            activeColor = animPreviewPrimary,
                            onClick = { onUpdateSettings(settings.copy(appTheme = "Follow system")) }
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Sync,
                                    contentDescription = "System Theme",
                                    tint = onBackground,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        // 2. Light
                        val isLightSelected = settings.appTheme == "Light Mode"
                        ThemeModeCard(
                            title = "Light",
                            selected = isLightSelected,
                            activeColor = animPreviewPrimary,
                            onClick = { onUpdateSettings(settings.copy(appTheme = "Light Mode")) }
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(Color.White)
                                    .border(1.dp, Color(0x33000000), CircleShape)
                            )
                        }

                        // 3. Dark
                        val isDarkSelected = settings.appTheme == "Dark Mode" || settings.appTheme == "Midnight Velvet Dark"
                        ThemeModeCard(
                            title = "Dark",
                            selected = isDarkSelected,
                            activeColor = animPreviewPrimary,
                            onClick = { onUpdateSettings(settings.copy(appTheme = "Dark Mode")) }
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF22242B))
                                    .border(1.dp, Color(0x22FFFFFF), CircleShape)
                            )
                        }

                        // 4. AMOLED
                        val isAmoledSelected = settings.appTheme == "Pure AMOLED Black"
                        ThemeModeCard(
                            title = "AMOLED",
                            selected = isAmoledSelected,
                            activeColor = animPreviewPrimary,
                            onClick = { onUpdateSettings(settings.copy(appTheme = "Pure AMOLED Black")) }
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(Color.Black)
                                    .border(1.dp, Color(0x33FFFFFF), CircleShape)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // ── 3. HORIZONTALLY SCROLLABLE COLOR PALETTE SELECTOR ──
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 18.dp)
                ) {
                    Column(modifier = Modifier.padding(horizontal = 18.dp)) {
                        Text(
                            text = "Color Palette",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = onBackground,
                            fontSize = 17.sp
                        )
                        Text(
                            text = "Accent harmony and button styling",
                            style = MaterialTheme.typography.bodySmall,
                            color = onBackground.copy(alpha = 0.60f)
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    val paletteScrollState = rememberScrollState()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(paletteScrollState)
                            .padding(horizontal = 18.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 1. Dynamic Wallpaper / Playing Song Artwork Swatch
                        val dynamicTop = if (hasSongArtwork) sharedArtworkPalette.primary else (dynamicSysScheme?.primary ?: Color(0xFF6750A4))
                        val dynamicBottomLeft = if (hasSongArtwork) sharedArtworkPalette.secondary else (dynamicSysScheme?.secondary ?: Color(0xFFD0BCFF))
                        val dynamicBottomRight = if (hasSongArtwork) sharedArtworkPalette.tertiary else (dynamicSysScheme?.tertiary ?: Color(0xFFCCC2DC))

                        PaletteOptionItem(
                            title = "Dynamic",
                            selected = isDynamic,
                            activeColor = animPreviewPrimary,
                            onClick = {
                                onUpdateSettings(
                                    settings.copy(
                                        colorPalette = "Dynamic",
                                        dynamicTheme = true
                                    )
                                )
                            }
                        ) {
                            TripleTonePaletteCircle(
                                topColor = dynamicTop,
                                bottomLeftColor = dynamicBottomLeft,
                                bottomRightColor = dynamicBottomRight,
                                modifier = Modifier.size(52.dp)
                            )
                        }

                        // Curated Palettes (Triple-Tone Split Circles)
                        CuratedPalettes.forEach { palette ->
                            val isSelected = !isDynamic && settings.colorPalette == palette.id
                            PaletteOptionItem(
                                title = palette.name,
                                selected = isSelected,
                                activeColor = if (isDark) palette.primaryDark else palette.primaryLight,
                                onClick = {
                                    onUpdateSettings(
                                        settings.copy(
                                            colorPalette = palette.id,
                                            dynamicTheme = false
                                        )
                                    )
                                }
                            ) {
                                TripleTonePaletteCircle(
                                    topColor = if (isDark) palette.primaryDark else palette.primaryLight,
                                    bottomLeftColor = if (isDark) palette.secondaryDark else palette.secondaryLight,
                                    bottomRightColor = if (isDark) palette.tertiaryDark else palette.tertiaryLight,
                                    modifier = Modifier.size(52.dp)
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

/**
 * Visual Theme Mode Card Item with distinct container highlight,
 * checkmark indicator, and clear typography.
 */
@Composable
private fun ThemeModeCard(
    title: String,
    selected: Boolean,
    activeColor: Color,
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    val scale by animateFloatAsState(
        targetValue = if (selected) 1.05f else 1.0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "modeScale"
    )

    Box(
        modifier = Modifier
            .size(62.dp)
            .scale(scale)
            .clip(CircleShape)
            .background(
                if (selected) activeColor.copy(alpha = 0.12f)
                else Color.Transparent
            )
            .border(
                BorderStroke(
                    width = if (selected) 2.5.dp else 1.dp,
                    color = if (selected) activeColor else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                ),
                shape = CircleShape
            )
            .tactileBounce()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        content()

        // Animated Selection Checkmark Overlay
        androidx.compose.animation.AnimatedVisibility(
            visible = selected,
            enter = fadeIn() + scaleIn(),
            exit = fadeOut() + scaleOut()
        ) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(activeColor),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "$title Selected",
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(15.dp)
                )
            }
        }
    }
}

/**
 * Visual Palette Option Item with triple-tone preview,
 * active ring highlight, and animated center checkmark.
 */
@Composable
private fun PaletteOptionItem(
    title: String,
    selected: Boolean,
    activeColor: Color,
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    val scale by animateFloatAsState(
        targetValue = if (selected) 1.06f else 1.0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "paletteScale"
    )

    Box(
        modifier = Modifier
            .size(62.dp)
            .scale(scale)
            .clip(CircleShape)
            .border(
                BorderStroke(
                    width = if (selected) 2.5.dp else 1.dp,
                    color = if (selected) activeColor else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                ),
                shape = CircleShape
            )
            .tactileBounce()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        content()

        // Animated Selection Checkmark Overlay
        androidx.compose.animation.AnimatedVisibility(
            visible = selected,
            enter = fadeIn() + scaleIn(),
            exit = fadeOut() + scaleOut()
        ) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "$title Selected",
                tint = if (isLightColor(activeColor)) Color(0xFF1B1D22) else Color.White,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/**
 * Triple-Tone Palette Circle:
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
