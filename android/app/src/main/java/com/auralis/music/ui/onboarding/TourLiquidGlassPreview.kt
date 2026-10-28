package com.auralis.music.ui.onboarding



import androidx.compose.animation.core.animateFloatAsState

import androidx.compose.animation.core.snap

import androidx.compose.animation.core.spring

import androidx.compose.foundation.background

import androidx.compose.foundation.layout.*

import androidx.compose.foundation.shape.RoundedCornerShape

import androidx.compose.material3.MaterialTheme

import androidx.compose.runtime.*

import androidx.compose.ui.Alignment

import androidx.compose.ui.Modifier

import androidx.compose.ui.draw.clip

import androidx.compose.ui.graphics.luminance

import androidx.compose.ui.graphics.Color

import androidx.compose.ui.layout.onSizeChanged

import androidx.compose.ui.layout.Layout

import androidx.compose.ui.input.nestedscroll.nestedScroll

import androidx.compose.ui.semantics.semantics

import androidx.compose.ui.semantics.stateDescription

import kotlin.math.roundToInt

import androidx.compose.ui.platform.LocalDensity

import androidx.compose.ui.platform.testTag

import androidx.compose.ui.unit.Density

import androidx.compose.ui.unit.IntSize

import androidx.compose.ui.unit.dp

import com.auralis.music.domain.model.AppearanceSettings

import com.auralis.music.domain.model.Track

import com.auralis.music.domain.model.HomeSection

import com.auralis.music.ui.viewmodel.HomeUiState

import com.auralis.music.ui.home.HomeScreen

import com.auralis.music.ui.components.BottomChrome

import com.auralis.music.ui.components.LocalBottomChrome

import com.auralis.music.ui.AppDestination

import com.auralis.music.ui.components.AuralisFloatingDock

import com.auralis.music.ui.glass.DockMinimizeState

import com.auralis.music.ui.glass.LiquidGlassContext

import com.auralis.music.ui.glass.LocalDockCollapse

import com.auralis.music.ui.glass.LocalLiquidGlass

import com.auralis.music.ui.glass.LocalMiniPlayerGlass

import com.auralis.music.ui.player.MiniPlayer

import com.auralis.music.ui.theme.dynamicSurface

import com.auralis.music.ui.theme.LocalAppearanceSettings

import com.auralis.music.ui.theme.LocalReducedMotion

import com.kyant.backdrop.backdrops.layerBackdrop

import com.kyant.backdrop.backdrops.rememberLayerBackdrop



/** The production Home screen and glass chrome; every playback action stays inside the silent demo. */

@OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)

@Composable

internal fun TourLiquidGlassPreview(

    appearance: AppearanceSettings,

    active: Boolean,

    viewport: IntSize,

    modifier: Modifier = Modifier,

    homeUiState: HomeUiState = HomeUiState()

) {

    val reduced = LocalReducedMotion.current


    val minimize = remember { DockMinimizeState() }
    val backdrop = rememberLayerBackdrop()

    val collapse = animateFloatAsState(if (minimize.isMinimized) 1f else 0f,

        animationSpec = if (reduced) snap() else spring(dampingRatio = 1f, stiffness = 400f), label = "tourChromeCollapse")

    val isDark = MaterialTheme.dynamicSurface.luminance() < .5f

    val glass = remember(backdrop, isDark) { LiquidGlassContext(backdrop, isDark = isDark, collapse = collapse) }

    val demoAppearance = appearance.copy(

        miniPlayerDesign = "New mini player",

        newMiniPlayerDesign = true,

        pureBlackMiniPlayer = false,

        enableSwipeToChangeSong = false,

        slimBottomNavigationBar = false

    )

    val fallbackTracks = remember { listOf(

        Track("PvM79DJ2PmM", "The Less I Know the Better", "Tame Impala", album = "Currents", duration = 217,

            thumbnail = "https://i.ytimg.com/vi/PvM79DJ2PmM/hqdefault.jpg"),

        Track("rymYToIEL9o", "Borderline", "Tame Impala", album = "The Slow Rush", duration = 238,

            thumbnail = "https://i.ytimg.com/vi/rymYToIEL9o/hqdefault.jpg"),

        Track("NMRhx71bGo4", "Let It Happen", "Tame Impala", album = "Currents", duration = 468,

            thumbnail = "https://i.ytimg.com/vi/NMRhx71bGo4/hqdefault.jpg")

    ) + listOf(

        "Feels Like We Only Go Backwards", "New Person, Same Old Mistakes", "Eventually",

        "Yes I’m Changing", "Reality in Motion", "Love/Paranoia", "Is It True", "Lost in Yesterday", "Breathe Deeper"

    ).mapIndexed { index, title -> Track("tour-extra-$index", title, "Tame Impala", duration = 240) } }

    val previewHome = remember(homeUiState) {

        val hasFeed = homeUiState.speedDialPages.isNotEmpty() || homeUiState.quickPicks.isNotEmpty() ||

            homeUiState.keepListening.isNotEmpty() || homeUiState.recentTracks.isNotEmpty() ||

            homeUiState.dynamicSections.isNotEmpty() || homeUiState.similarRecommendations.isNotEmpty()

        val demoShelves = listOf("Recommended for you", "A little more to explore", "Your next favorites", "Keep the music going")

            .mapIndexed { index, title -> HomeSection("tour-extra-shelf-$index", title,

                items = fallbackTracks.drop(index * 3) + fallbackTracks.take(index * 3)) }

        if (hasFeed) homeUiState.copy(isLoading = false, isRefreshing = false, isOffline = false, error = null,

            dynamicSections = homeUiState.dynamicSections + demoShelves)

        else HomeUiState(speedDialReady = true, quickPicks = fallbackTracks, keepListening = fallbackTracks,

            dynamicSections = demoShelves)

    }

    val initialTracks = remember(previewHome) {

        (previewHome.quickPicks + previewHome.keepListening + previewHome.recentTracks.map { it.track } +

            previewHome.dynamicSections.flatMap { it.items } + previewHome.similarRecommendations.flatMap { it.items })

            .distinctBy { it.id }.ifEmpty { fallbackTracks }

    }

    var tracks by remember(initialTracks) { mutableStateOf(initialTracks) }

    var currentIndex by remember(initialTracks) { mutableIntStateOf(0) }

    var playing by remember { mutableStateOf(true) }

    var favorites by remember { mutableStateOf(emptySet<String>()) }

    var selectedChip by remember(previewHome) { mutableStateOf(previewHome.selectedChip) }

    var chromeHeight by remember { mutableStateOf(156.dp) }

    val bottomChrome = remember { BottomChrome { chromeHeight } }

    val currentTrack = tracks[currentIndex]

    val durationMs = (currentTrack.duration * 1000).coerceAtLeast(180_000)

    val position = rememberTourClock(active && playing && !reduced, currentTrack.id, durationMs, 30_000)

    fun toggleFavorite(track: Track) {

        favorites = if (track.id in favorites) favorites - track.id else favorites + track.id

    }

    BoxWithConstraints(modifier.testTag("tour-glass-preview"), contentAlignment = Alignment.Center) {

        val density = LocalDensity.current

        val (width, height, scale) = fitPlayerPreview(viewport, IntSize(constraints.maxWidth, constraints.maxHeight))

        if (scale <= 0f) return@BoxWithConstraints

        Box(Modifier.size(with(density) { width.toDp() }, with(density) { height.toDp() })) {

            CompositionLocalProvider(

                LocalDensity provides Density(density.density * scale, density.fontScale),

                LocalAppearanceSettings provides demoAppearance,

                LocalDockCollapse provides collapse,

                LocalBottomChrome provides bottomChrome,

                LocalLiquidGlass provides if (appearance.liquidGlass) glass else null,

                LocalMiniPlayerGlass provides if (com.auralis.music.ui.player.MiniPlayerBackgrounds.surface(demoAppearance) ==
                    com.auralis.music.ui.player.MiniPlayerBackgrounds.Surface.LIQUID_GLASS) glass else null,
                com.auralis.music.ui.player.LocalFrostedMiniPlayer provides
                    (com.auralis.music.ui.player.MiniPlayerBackgrounds.surface(demoAppearance) ==
                        com.auralis.music.ui.player.MiniPlayerBackgrounds.Surface.FROSTED)

            ) {

                Box(Modifier.fillMaxSize().clip(RoundedCornerShape(28.dp)).background(Color(0xFF11121A))) {

                    HomeScreen(

                        uiState = previewHome.copy(selectedChip = selectedChip),

                        currentTrack = currentTrack, currentTrackId = currentTrack.id,

                        isPlaying = playing,

                        favoriteTracks = tracks.filter { it.id in favorites },

                        onTrackClick = { track, queue ->

                            tracks = queue.ifEmpty { listOf(track) }

                            currentIndex = tracks.indexOfFirst { it.id == track.id }.coerceAtLeast(0)

                            playing = true

                        },

                        onFavoriteToggle = ::toggleFavorite,

                        onChipToggle = { selectedChip = it },

                        onSurpriseMe = { currentIndex = (currentIndex + 1) % tracks.size },

                        modifier = Modifier.fillMaxSize().nestedScroll(minimize.connection).layerBackdrop(backdrop).testTag("tour-glass-feed")

                    )

                    val previewDensity = LocalDensity.current

                    TourPreviewChrome(collapse = { collapse.value }, modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()

                        .testTag("tour-preview-chrome").semantics {

                            stateDescription = if (minimize.isMinimized) "Collapsed" else "Expanded"

                        }.onSizeChanged {

                        chromeHeight = with(previewDensity) { it.height.toDp() }

                    }.padding(bottom = 8.dp), miniPlayer = {

                        MiniPlayer(

                            track = currentTrack, queue = tracks, currentIndex = currentIndex,

                            isFavorite = currentTrack.id in favorites, onFavoriteToggle = { toggleFavorite(currentTrack) },

                            isPlaying = playing,

                            progressProvider = { position.value.toFloat() / durationMs },

                            userScrollEnabled = false,

                            onPlayPauseClick = { playing = !playing },

                            onNextClick = { currentIndex = (currentIndex + 1) % tracks.size },

                            onClick = { if (minimize.isMinimized) minimize.expand() else playing = !playing },

                            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)

                        )

                    }, dock = {

                        AuralisFloatingDock(

                            currentDestination = AppDestination.HOME,

                            onDestinationClick = {},

                            onToggleHomeMenu = {},

                            onCreatePlaylist = {},

                            onExpandRequest = minimize::expand

                        )

                    })

                }

            }

        }

    }

}



/** Same two-row to tucked layout as the app, measured together to keep touch bounds aligned. */

@Composable

private fun TourPreviewChrome(

    collapse: () -> Float,

    modifier: Modifier = Modifier,

    miniPlayer: @Composable () -> Unit,

    dock: @Composable () -> Unit

) {

    Layout(content = { Box { miniPlayer() }; Box { dock() } }, modifier = modifier) { children, constraints ->

        val progress = collapse().coerceIn(0f, 1f)

        val inset = (68.dp.toPx() * progress).roundToInt()

        val miniWidth = (constraints.maxWidth - inset * 2).coerceAtLeast(0)

        val mini = children[0].measure(constraints.copy(minWidth = miniWidth, maxWidth = miniWidth, minHeight = 0))

        val dockPlaceable = children[1].measure(constraints.copy(minHeight = 0))

        val expandedHeight = mini.height + 10.dp.roundToPx() + dockPlaceable.height

        val height = (expandedHeight + (dockPlaceable.height - expandedHeight) * progress).roundToInt()

        layout(constraints.maxWidth, height) {

            dockPlaceable.placeRelative(0, height - dockPlaceable.height)

            val tuckedY = height - dockPlaceable.height / 2 - mini.height / 2

            mini.placeRelative(inset, (tuckedY * progress).roundToInt())

        }

    }

}
