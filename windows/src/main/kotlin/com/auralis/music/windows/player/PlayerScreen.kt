package com.auralis.music.windows.player

import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.Backdrop

import com.auralis.music.ui.i18n.desktopText

import androidx.compose.animation.Crossfade
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.background
import com.auralis.music.ui.components.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import com.auralis.music.ui.components.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.auralis.music.desktop.lyrics.DesktopLyricsController
import com.auralis.music.desktop.playback.DesktopPlaybackController
import com.auralis.music.desktop.playback.VlcAudioPlayer
import com.auralis.music.domain.model.AppearanceSettings
import com.auralis.music.ui.components.getHighResArtworkUrl
import com.auralis.music.ui.lyrics.ExperimentalLyricsView
import com.auralis.music.ui.lyrics.LocalLyricsUi
import com.auralis.music.ui.player.ClassicPauseGlyph
import com.auralis.music.ui.player.ClassicPlayGlyph
import com.auralis.music.ui.player.ClassicSkipGlyph
import com.auralis.music.ui.theme.LocalAppearanceSettings
import com.auralis.music.windows.search.DesktopArtwork

/**
 * The full player: the phone's Blur background, artwork and controls on the left, and the
 * phone's MetroLyrics view (its default lyrics style) on the right.
 */
@Composable
internal fun PlayerScreen(
    playback: DesktopPlaybackController,
    lyrics: DesktopLyricsController,
    panel: PlayerPanel,
    onPanelChange: (PlayerPanel) -> Unit,
    onClose: () -> Unit,
    palette: com.auralis.music.ui.theme.ArtworkPalette = com.auralis.music.ui.theme.DefaultArtworkPalette,
    onImmersive: () -> Unit = {},
    stageChoice: StagePlaybackChoice,
    playerVisible: Boolean,
    drawControls: Boolean,
    onOpenArtist: (com.auralis.music.domain.model.Artist) -> Unit = {},
    onOpenAlbum: (com.auralis.music.domain.model.PlaylistResult) -> Unit = {}
) {
    val queue by playback.queue.collectAsState()
    val track = queue.current
    val appearance = LocalAppearanceSettings.current
    val prefs = com.auralis.music.windows.settings.DesktopPreferences.store
    val preferenceRevision by prefs.changes.collectAsState()
    val artworkUrl = track?.thumbnail?.let { getHighResArtworkUrl(it) ?: it }

    val lyricsState by lyrics.state.collectAsState()
    val lyricsSpotifyId = com.auralis.music.data.network.SpicyRecordingMatcher.spotifyId(track?.id)
        ?: lyricsState.takeIf { it.trackId == track?.id }?.lyrics?.spotifyTrackId
    // Only some lyrics sources carry the Spotify id: keep the one seen for this song, so switching
    // the lyrics source (or restarting) doesn't turn the Canvas off.
    var knownSpotifyId by remember(track?.id) { mutableStateOf(SpotifyCanvasLookup.songId(track?.id)) }
    LaunchedEffect(track?.id, lyricsSpotifyId) {
        val id = track?.id ?: return@LaunchedEffect
        if (lyricsSpotifyId != null) {
            knownSpotifyId = lyricsSpotifyId
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { SpotifyCanvasLookup.rememberSongId(id, lyricsSpotifyId) }
        }
    }
    val spotifyCanvasId = lyricsSpotifyId ?: knownSpotifyId
    val canvasPrefs by CanvasSettings.prefs.collectAsState()
    val songVisualRevision by CanvasSettings.songVisualChanges.collectAsState()
    val songVisual = remember(track, songVisualRevision) { track?.let(CanvasSettings::songVisual) }
    val glassBackdrop = rememberLayerBackdrop()
    val seekArtwork = rememberLayerBackdrop()
    val lyricsBackdrop = rememberLayerBackdrop()
    val seekBackdrop = rememberCombinedBackdrop(glassBackdrop, seekArtwork)
    val combinedBackdrop = rememberCombinedBackdrop(glassBackdrop, lyricsBackdrop)
    val resyncSource = remember(combinedBackdrop, lyricsBackdrop) { ResyncBackdrop(combinedBackdrop, lyricsBackdrop) }
    val visualsActive = com.auralis.music.ui.player.LocalPlaybackVisualsActive.current && playerVisible
    CompositionLocalProvider(
        LocalResyncBackdrop provides resyncSource,
        com.auralis.music.ui.player.LocalPlaybackVisualsActive provides visualsActive
    ) {
    // Opaque: the main window must never show or receive pointer events through the player.
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF050505))
            .blockUnderlyingPointerEvents()
    ) {
        val stageAllowed = track != null && shouldShowCanvasStage(
            canvasPrefs = canvasPrefs,
            maxWidth = Dp.Infinity,
            stagePlayer = prefs.bool("stagePlayer", true),
            tabbedPlayer = prefs.bool("tabbedPlayer", true)
        )
        var stageMounted by remember(stageChoice) { mutableStateOf(false) }
        val canvasStage = stageAllowed && (maxWidth > CanvasStageMinWidth || (!playerVisible && stageMounted))
        if (canvasStage && playerVisible && !stageMounted) SideEffect { stageMounted = true }
        val backdropHeight = (STAGE_BACKDROP_WIDTH * maxHeight.value / maxWidth.value.coerceAtLeast(1f))
            .toInt().coerceIn(1, STAGE_BACKDROP_WIDTH * 2)
        val motionAura = rememberMotionAura(track?.id, backdropHeight)
        val canvasReady by remember(motionAura) {
            derivedStateOf { motionAura.texture.value != null }
        }
        val stageVisible = canvasStage && canvasReady
        val stageTransition = updateTransition(stageVisible, label = "Canvas stage")
        val fade = tween<Float>(620, easing = FastOutSlowInEasing)
        val stageOpacity = stageTransition.animateFloat(transitionSpec = { fade }, label = "Canvas opacity") {
            if (it) 1f else 0f
        }
        if (stageVisible && playerVisible) {
            CanvasIntro(playback)
        }
        Box(Modifier.fillMaxSize().then(if (track != null || resyncSource.users.intValue > 0)
            Modifier.layerBackdrop(glassBackdrop) else Modifier)) {
            if (!stageTransition.currentState || !stageTransition.targetState) {
                val backgroundArtwork = if (appearance.playerBackgroundStyle.equals("Dynamic", ignoreCase = true))
                    track?.thumbnail ?: artworkUrl else artworkUrl
                PlayerBackdrop(appearance.playerBackgroundStyle, backgroundArtwork, palette,
                    playbackClock = playback.clock, spotifyTrackId = spotifyCanvasId,
                    trackDurationSeconds = track?.duration ?: 0L)
            }
            // Fade the sharp and blurred versions together so neither jumps ahead of the other.
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = stageOpacity.value }) {
                CanvasStageBackdrop(motionAura)
                if (stageAllowed && (canvasStage || stageMounted)) {
                    CompositionLocalProvider(com.auralis.music.ui.player.LocalPlaybackVisualsActive provides
                        (visualsActive && canvasStage)) {
                        AnimatedCover(track, Modifier.fillMaxWidth(.58f).fillMaxHeight(),
                            motionAura = motionAura, spotifyCanvasId = spotifyCanvasId, stage = true,
                            playbackClock = playback.clock,
                            songVisual = songVisual,
                            stageChoice = stageChoice) {}
                    }
                }
            }
        }

        if (drawControls) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 32.dp)) {
            val wide = maxWidth > 900.dp && prefs.bool("stagePlayer",true)
            val leftWidth = if (wide) maxWidth * 0.44f else maxWidth
            // Spicy Lyrics' layout: the cover with room around it, the seek bar under it, then
            // the title block; playback controls and volume appear on the cover while hovered.
            val coverSize = minOf(leftWidth - 24.dp, maxHeight - 180.dp, 640.dp).coerceAtLeast(160.dp)
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxHeight()
                        .then(if (wide) Modifier.width(leftWidth) else Modifier.weight(1f))
                ) {
                    // Keep the tile solid for the start of the dissolve, while the stage underneath fills in.
                    stageTransition.AnimatedVisibility(visible = { !it }, enter = fadeIn(fade),
                        exit = fadeOut(tween(500, delayMillis = 120, easing = FastOutSlowInEasing))) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(Modifier.offset(y = 12.dp).layerBackdrop(seekArtwork)) {
                                CoverWithControls(
                                    playback = playback,
                                    track = track,
                                    artworkUrl = artworkUrl,
                                    size = coverSize,
                                    scale = ContentScale.Crop,
                                    animateCover = !canvasStage
                                )
                            }
                            Spacer(Modifier.height(22.dp))
                            PlayerSeekBar(playback, seekBackdrop, Modifier.width(coverSize))
                            Spacer(Modifier.height(18.dp))
                            // Title opens the album, each artist their page (as in the bar below).
                            val titleStyle = MaterialTheme.typography.titleLarge.copy(fontSize = 34.sp, fontWeight = FontWeight.Bold)
                            val artistStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 22.sp)
                            if (track == null) {
                                Text("", style = titleStyle, modifier = Modifier.width(coverSize))
                            } else {
                                val album = rememberTrackAlbum(track)
                                Box(Modifier.width(coverSize), contentAlignment = Alignment.Center) {
                                    BarLink(track.title, album?.let { { onOpenAlbum(it) } }, titleStyle, Color.White,
                                        textAlign = TextAlign.Center)
                                }
                                Spacer(Modifier.height(4.dp))
                                TrackArtistLinks(track, onOpenArtist, artistStyle, Color.White.copy(alpha = 0.75f),
                                    Modifier.width(coverSize), Arrangement.Center)
                            }
                            PlayerConnectRow()
                        }
                    }
                    stageTransition.AnimatedVisibility(visible = { it }, modifier = Modifier.matchParentSize(),
                        enter = fadeIn(fade), exit = fadeOut(fade)) {
                        if (track != null) CanvasPlayerDock(playback, track, glassBackdrop,
                            Modifier.fillMaxSize().padding(bottom = 48.dp), onOpenArtist, onOpenAlbum)
                    }
                }
                if (wide) {
                    Column(Modifier.weight(0.58f).fillMaxHeight()) {
                        if(prefs.bool("tabbedPlayer",true)) PlayerPanelTabs(panel, onSelect = onPanelChange)
                        Spacer(Modifier.height(12.dp))
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            if(!prefs.bool("tabbedPlayer",true)) Column(Modifier.fillMaxSize()) {
                                Box(Modifier.weight(.65f)) {LyricsPanel(playback,lyrics)}
                                HorizontalDivider()
                                Box(Modifier.weight(.35f)) {QueueView(playback)}
                            } else AnimatedContent(
                                targetState = panel,
                                modifier = Modifier.fillMaxSize(),
                                transitionSpec = { fadeIn(tween(145)) togetherWith fadeOut(tween(95)) },
                                label = "Player panel"
                            ) { visiblePanel ->
                                when (visiblePanel) {
                                    PlayerPanel.LYRICS -> LyricsPanel(playback, lyrics)
                                    PlayerPanel.QUEUE -> QueueView(playback, Modifier.padding(horizontal = 16.dp),
                                        animateEntrance = true)
                                }
                            }
                        }
                    }
                }
            }
        }

        IconButton(onClick = onClose, modifier = Modifier.padding(12.dp).align(Alignment.TopStart)) {
            Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = desktopText("Close player"), tint = Color.White, modifier = Modifier.size(32.dp))
        }
        Row(Modifier.padding(12.dp).align(Alignment.TopEnd)) {
            if (canvasStage && track != null && canvasPrefs.spotifyCanvas) {
                SongVisualMenu(track, songVisual)
            }
            IconButton(onClick = onImmersive) {
                Icon(Icons.Rounded.Fullscreen, contentDescription = desktopText("Immersive mode"), tint = Color.White, modifier = Modifier.size(28.dp))
            }
        }
        }
    }
}

}

@Composable
private fun SongVisualMenu(track: com.auralis.music.domain.model.Track, selected: StageVisual?) {
    var open by remember(track.id) { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Rounded.MoreVert, contentDescription = desktopText("Visual for this song"), tint = Color.White)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for ((visual, label) in listOf(null to "Use Appearance setting",
                StageVisual.SPOTIFY to "Use Spotify Canvas", StageVisual.ALBUM to "Use animated cover")) {
                DropdownMenuItem(
                    text = { Text(desktopText(label), fontWeight = if (selected == visual) FontWeight.Bold else FontWeight.Normal) },
                    onClick = {
                        CanvasSettings.setSongVisual(track, visual)
                        open = false
                    }
                )
            }
        }
    }
}

/**
 * Stops pointer events on non-interactive regions of an overlay (clicks, hovers, drags, wheel
 * scrolls) from passing through to siblings drawn underneath in the same [Box].
 * The pointer modifier itself makes the overlay hit-testable. Consuming events here
 * would cancel child clicks when the pointer moves between press and release.
 */
internal fun Modifier.blockUnderlyingPointerEvents(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            awaitPointerEvent()
        }
    }
}

/**
 * Whether the full player's stage canvas layout (full-height animated video + bottom-left dock)
 * should be active; persists across Lyrics and Queue tabs so switching tabs never tears down or
 * restarts the canvas playback.
 */
internal fun shouldShowCanvasStage(
    canvasPrefs: CanvasPrefs,
    maxWidth: Dp,
    stagePlayer: Boolean,
    tabbedPlayer: Boolean
): Boolean = (canvasPrefs.spotifyCanvas || canvasPrefs.enabled) &&
    maxWidth > CanvasStageMinWidth &&
    stagePlayer &&
    tabbedPlayer

private val CanvasStageMinWidth = 964.dp

/** Auralis Connect in the full player: the device button and, while another device plays, where. */
@Composable
private fun PlayerConnectRow(compact: Boolean = false, iconOnly: Boolean = false) {
    val connect = com.auralis.music.windows.connect.DesktopConnect.current ?: return
    val ui by connect.ui.collectAsState()
    if (!ui.enabled) return
    var menu by remember { mutableStateOf(false) }
    val elsewhere = ui.output?.takeIf { !it.isThis }
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val focused by interaction.collectIsFocusedAsState()
    val highlight by androidx.compose.animation.core.animateFloatAsState(
        if (hovered || focused || menu) .12f else 0f, tween(140), label = "connectRowHover")
    if (!compact) Spacer(Modifier.height(10.dp))
    Box {
        Row(verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clip(RoundedCornerShape(12.dp))
                .background(Color.White.copy(alpha = highlight))
                .hoverable(interaction)
                .clickable { menu = true }
                .padding(horizontal = if (compact) 2.dp else 12.dp, vertical = if (compact) 2.dp else 10.dp)) {
                Icon(com.auralis.music.windows.connect.connectIcon(elsewhere?.type ?: com.auralis.music.data.connect.ConnectDeviceType.COMPUTER),
                    desktopText("Connect to a device"), modifier = Modifier.size(if (compact) 16.dp else 24.dp),
                    tint = if (elsewhere != null) com.auralis.music.windows.connect.ConnectAccent else Color.White.copy(alpha = .8f))
            if (!iconOnly) {
                if (elsewhere != null || ui.transfer != null) Spacer(Modifier.width(8.dp))
                com.auralis.music.windows.connect.ConnectOutputLabel(showIcon = false, interactive = false, compact = compact) { menu = true }
            }
        }
        com.auralis.music.windows.connect.ConnectDeviceMenu(expanded = menu, onDismiss = { menu = false })
    }
}

/** What the player shows beside the artwork. */
enum class PlayerPanel(val label: String) { LYRICS("Lyrics"), QUEUE("Queue") }

/** Quiet text navigation on the player's backdrop; no separate settings-style capsule. */
@Composable
internal fun PlayerPanelTabs(selected: PlayerPanel, onSelect: (PlayerPanel) -> Unit, modifier: Modifier = Modifier) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier.padding(horizontal = 12.dp).selectableGroup()
    ) {
        PlayerPanel.entries.forEach { item ->
            val isSelected = item == selected
            val interactions = remember { MutableInteractionSource() }
            val hovered by interactions.collectIsHoveredAsState()
            val focused by interactions.collectIsFocusedAsState()
            val emphasis by androidx.compose.animation.core.animateFloatAsState(
                if (isSelected) 1f else 0f, tween(180), label = "playerPanelSelection")
            Box(Modifier.height(48.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color.White.copy(alpha = if (focused) .09f else if (hovered) .05f else 0f))
                .pointerHoverIcon(PointerIcon.Hand)
                .selectable(selected = isSelected, interactionSource = interactions, indication = null,
                    role = Role.Tab, onClick = { if (!isSelected) onSelect(item) })
                .padding(horizontal = 12.dp)) {
                Text(
                    text = desktopText(item.label),
                    style = MaterialTheme.typography.labelLarge,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White.copy(alpha = .54f + .46f * emphasis),
                    modifier = Modifier.align(Alignment.Center)
                )
                Box(Modifier.align(Alignment.BottomCenter).size(width = 24.dp, height = 2.dp)
                    .graphicsLayer { alpha = emphasis; scaleX = .65f + .35f * emphasis }
                    .background(Color.White.copy(alpha = .9f), RoundedCornerShape(1.dp)))
            }
        }
    }
}

@Composable
internal fun rememberPlayerLyricsAppearance(): AppearanceSettings {
    val appearanceStore = com.auralis.music.ui.theme.LocalAppearanceSettingsStore.current
    val appearance = appearanceStore?.settingsFlow?.collectAsState(appearanceStore.getInitialSettings())?.value
        ?: LocalAppearanceSettings.current
    return appearance.copy(lyricsTextSize = appearance.lyricsTextSize * 1.35f)
}

@Composable
private fun CanvasIntro(playback: DesktopPlaybackController) {
    val session by playback.session.collectAsState()
    val state by playback.state.collectAsState()
    val remote by playback.connectRemote.collectAsState()
    val playing = remote?.playing ?: (state == VlcAudioPlayer.State.PLAYING)
    var show by remember { mutableStateOf(false) }
    LaunchedEffect(session, playing) {
        if (playing) show = CanvasSettings.shouldOfferIntro(session)
    }
    if (show) {
        val keepLoops = {
            CanvasSettings.finishIntro(useCovers = false)
            show = false
        }
        com.auralis.music.ui.components.GlassAlertDialog(
            onDismissRequest = keepLoops,
            title = { Text(desktopText("Keep video loops in Lyrics?")) },
            text = {
                Text(desktopText("You can keep video loops or switch to cover art. The sidebar shows an animated cover when available."))
            },
            confirmButton = {
                com.auralis.music.ui.components.TextButton(onClick = keepLoops) {
                    Text(desktopText("Keep video loops"))
                }
            },
            dismissButton = {
                com.auralis.music.ui.components.TextButton(onClick = {
                    CanvasSettings.finishIntro(useCovers = true)
                    show = false
                }) {
                    Text(desktopText("Use cover art"))
                }
            }
        )
    }
}

@Composable
internal fun LyricsPanel(
    playback: DesktopPlaybackController,
    lyrics: DesktopLyricsController,
    showCredits: Boolean = true,
    textScale: Float = 1f
) {
    val playerAppearance = rememberPlayerLyricsAppearance()
    val appearance = playerAppearance.copy(lyricsTextSize = playerAppearance.lyricsTextSize * textScale)
    val state by lyrics.state.collectAsState()
    val queue by playback.queue.collectAsState()
    val playerState by playback.state.collectAsState()
    val remote by playback.connectRemote.collectAsState()
    val coarsePosition = rememberShownPosition(playback, remote)
    val playing = remote?.playing ?: (playerState == VlcAudioPlayer.State.PLAYING)
    // As on the phone: the view gets the position sampled every frame and interpolated between
    // libVLC's reports (rememberLyricsClock), not the raw reports (~4 a second), which made the
    // word sweep and the intro circle advance in steps.
    val positionState = com.auralis.music.ui.lyrics.rememberLyricsClock(
        source = playback.clock,
        enabled = playing,
        fallbackPositionMs = coarsePosition
    )
    val data = state.lyrics
    when {
        data == null && state.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Color.White.copy(alpha = 0.7f), strokeWidth = 2.dp, modifier = Modifier.size(32.dp))
        }
        else -> CompositionLocalProvider(
            LocalLyricsUi provides DesktopLyricsUi,
            // Larger desktop presentation; retain the user's chosen renderer and size preference.
            LocalAppearanceSettings provides appearance
        ) {
            ExperimentalLyricsView(
                lyrics = data,
                positionState = positionState,
                onSeekTo = playback::seekTo,
                isPlaying = playing,
                isBuffering = remote?.buffering ?: (playerState == VlcAudioPlayer.State.BUFFERING),
                lyricsClockSource = playback.clock,
                track = queue.current,
                modifier = Modifier.fillMaxSize(),
                showCredits = showCredits
            )
        }
    }
}

@Composable
private fun PlayerSeekBar(
    playback: DesktopPlaybackController, backdrop: Backdrop, modifier: Modifier = Modifier, compact: Boolean = false
) {
    val length by playback.lengthMs.collectAsState()
    val state by playback.state.collectAsState()
    val remote by playback.connectRemote.collectAsState()
    val playing = remote?.playing ?: (state == VlcAudioPlayer.State.PLAYING)
    // The same per-frame clock as the lyrics, so the bar glides instead of jumping ~4 times a second.
    val position by com.auralis.music.ui.lyrics.rememberLyricsClock(
        source = playback.clock,
        enabled = playing,
        fallbackPositionMs = rememberShownPosition(playback, remote)
    )
    val queue by playback.queue.collectAsState()
    val duration = remote?.durationMs?.takeIf { it > 0 } ?: length.takeIf { it > 0 } ?: ((queue.current?.duration ?: 0L) * 1000L)
    var dragFraction by remember(queue.current?.id) { mutableStateOf<Float?>(null) }
    val fraction = dragFraction ?: if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val shown = dragFraction?.let { (it * duration).toLong() } ?: position
    val updateSeek: (Float) -> Unit = { if (duration > 0) dragFraction = it }
    val finishSeek: () -> Unit = {
        dragFraction?.let { playback.seekTo((it * duration).toLong()) }
        dragFraction = null
    }
    val seekTrack: @Composable (Modifier) -> Unit = { trackModifier ->
        GlassSeekTrack(fraction, dragFraction, duration, backdrop, compact, updateSeek, finishSeek, trackModifier)
    }
    if (compact) {
        Column(modifier) {
            seekTrack(Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                PlayerTime(shown, 10)
                PlayerTime(duration, 10)
            }
        }
    } else {
        val timestampStyle = LocalTextStyle.current.copy(fontSize = 17.sp, fontFeatureSettings = "tnum")
        val timestampColor = Color.White.copy(alpha = .8f)
        val durationText = playerTimeText(duration)
        val timestampWidthPx = rememberTextMeasurer().measure(durationText, style = timestampStyle).size.width
        val timestampWidth = with(LocalDensity.current) { timestampWidthPx.toDp() }
        Row(modifier, verticalAlignment = Alignment.CenterVertically) {
            Text(playerTimeText(shown), modifier = Modifier.width(timestampWidth),
                style = timestampStyle, color = timestampColor, maxLines = 1, softWrap = false)
            seekTrack(Modifier.weight(1f).padding(horizontal = 12.dp))
            Text(durationText, style = timestampStyle, color = timestampColor, maxLines = 1, softWrap = false)
        }
    }
}

/** This PC's position, or the other device's while it is the one playing (Auralis Connect). */
@Composable
internal fun rememberShownPosition(
    playback: DesktopPlaybackController,
    remote: com.auralis.music.data.connect.ConnectRemoteView?
): androidx.compose.runtime.State<Long> {
    val local = playback.positionMs.collectAsState()
    return remember(playback, remote) { androidx.compose.runtime.derivedStateOf {
        // Subscribe to local reports, but read the controller clock's held resume/seek position
        // while the native player is loading instead of exposing its temporary zero.
        local.value
        remote?.positionAt(System.currentTimeMillis()) ?: playback.clock.rawPositionMs()
    } }
}

@Composable
private fun PlayerTime(ms: Long, fontSize: Int = 17) {
    Text(playerTimeText(ms), fontSize = fontSize.sp, color = Color.White.copy(alpha = .8f))
}

internal fun playerTimeText(ms: Long): String {
    val seconds = (ms / 1000).coerceAtLeast(0)
    return desktopText("%d:%02d").format(seconds / 60, seconds % 60)
}

@Composable
private fun PlayerTransport(
    playback: DesktopPlaybackController,
    modifier: Modifier = Modifier,
    compact: Boolean = false
) {
    val scale = if (compact) .45f else 1f
    val state by playback.state.collectAsState()
    val queue by playback.queue.collectAsState()
    val repeat by playback.repeatMode.collectAsState()
    val roomActive by playback.roomActive.collectAsState()
    val remote by playback.connectRemote.collectAsState()
    Row(modifier, horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically) {
        val shuffleOn by playback.shuffleEnabled.collectAsState()
        IconButton(onClick = playback::toggleShuffle, enabled = queue.current != null && !roomActive,
            modifier = if (compact) Modifier.size(28.dp) else Modifier) {
            Icon(Icons.Rounded.Shuffle, desktopText(if (remote?.shuffle ?: shuffleOn) "Turn shuffle off" else "Turn shuffle on"), tint = if (remote?.shuffle ?: shuffleOn) MaterialTheme.colorScheme.primary else Color.White, modifier = Modifier.size(28.dp * scale))
        }
        IconButton(onClick = playback::previous, modifier = Modifier.size(56.dp * scale)) {
            ClassicSkipGlyph(forward = false, color = Color.White, modifier = Modifier.size(width = 46.dp * scale, height = 28.dp * scale))
        }
        IconButton(onClick = playback::togglePlayPause, modifier = Modifier.size(64.dp * scale)) {
            if (remote?.playing ?: (state == VlcAudioPlayer.State.PLAYING || state == VlcAudioPlayer.State.BUFFERING))
                ClassicPauseGlyph(color = Color.White, modifier = Modifier.size(width = 34.dp * scale, height = 40.dp * scale))
            else ClassicPlayGlyph(color = Color.White, modifier = Modifier.size(width = 36.dp * scale, height = 42.dp * scale))
        }
        IconButton(onClick = playback::next, enabled = queue.hasNext, modifier = Modifier.size(56.dp * scale)) {
            ClassicSkipGlyph(forward = true, color = Color.White.copy(alpha = if (queue.hasNext) 1f else .4f),
                modifier = Modifier.size(width = 46.dp * scale, height = 28.dp * scale))
        }
        IconButton(onClick = playback::cycleRepeatMode, enabled = !roomActive,
            modifier = if (compact) Modifier.size(28.dp) else Modifier) {
            Icon(if (repeat == DesktopPlaybackController.RepeatMode.ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                "Repeat", tint = if (repeat == DesktopPlaybackController.RepeatMode.OFF) Color.White.copy(alpha = .7f) else Color.White,
                modifier = Modifier.size(28.dp * scale))
        }
    }
}

@Composable
private fun CanvasPlayerDock(
    playback: DesktopPlaybackController,
    track: com.auralis.music.domain.model.Track,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    onOpenArtist: (com.auralis.music.domain.model.Artist) -> Unit = {},
    onOpenAlbum: (com.auralis.music.domain.model.PlaylistResult) -> Unit = {}
) {
    val volume by playback.volume.collectAsState()
    Column(modifier, verticalArrangement = Arrangement.Bottom) {
        Column(Modifier.widthIn(max = 300.dp).fillMaxWidth()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                val album = rememberTrackAlbum(track)
                BarLink(track.title, album?.let { { onOpenAlbum(it) } },
                    androidx.compose.ui.text.TextStyle(fontSize = 15.sp, lineHeight = 18.sp, fontWeight = FontWeight.Bold),
                    Color.White, Modifier.weight(1f, fill = false))
                Spacer(Modifier.width(4.dp))
                PlayerPlaylistButton(track)
            }
            TrackArtistLinks(track, onOpenArtist, androidx.compose.ui.text.TextStyle(fontSize = 11.sp, lineHeight = 14.sp),
                Color.White.copy(alpha = .75f))
            PlayerSeekBar(playback, backdrop, Modifier.fillMaxWidth(), compact = true)
            Row(Modifier.fillMaxWidth().height(32.dp), verticalAlignment = Alignment.CenterVertically) {
                PlayerConnectRow(compact = true, iconOnly = true)
                PlayerTransport(playback, Modifier.weight(1f), compact = true)
                IconButton(onClick = playback::toggleMute, modifier = Modifier.size(24.dp)) {
                    Icon(if (volume > 0f) Icons.AutoMirrored.Rounded.VolumeUp else Icons.AutoMirrored.Rounded.VolumeOff,
                        desktopText(if (volume > 0f) "Mute" else "Unmute"),
                        tint = Color.White, modifier = Modifier.size(12.dp))
                }
                HoverSlider(value = volume, onValueChange = playback::setVolume,
                    thin = 2.dp, thick = 3.dp, hitSize = 20.dp, modifier = Modifier.width(36.dp))
            }
        }
    }
}

/** How long a still mouse rests on the cover before its controls and the cursor hide. */
private const val COVER_IDLE_MS = 2_500L

/** An empty cursor, for a mouse resting on the cover. */
private val BlankCursor: PointerIcon by lazy {
    PointerIcon(java.awt.Toolkit.getDefaultToolkit().createCustomCursor(
        java.awt.image.BufferedImage(16, 16, java.awt.image.BufferedImage.TYPE_INT_ARGB), java.awt.Point(0, 0), "blank"))
}

/** The cover; hovering shows shuffle/previous/play/next/repeat along its bottom and volume on its right. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun CoverWithControls(
    playback: DesktopPlaybackController, track: com.auralis.music.domain.model.Track?,
    artworkUrl: String?, size: Dp, scale: ContentScale, animateCover: Boolean = true
) {
    val hover = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val volumeHover = remember { MutableInteractionSource() }
    val volumeHovered by volumeHover.collectIsHoveredAsState()
    var adjustingVolume by remember { mutableStateOf(false) }
    var pointerDown by remember { mutableStateOf(false) }
    // A mouse left resting on the cover: after a moment the controls and the cursor get out of the
    // way, as in a video player; the slightest movement brings both back.
    var lastMove by remember { mutableLongStateOf(0L) }
    val pointerActivity = remember { CoverPointerActivity() }
    var idle by remember { mutableStateOf(false) }
    LaunchedEffect(lastMove, hovered) {
        idle = false
        if (hovered) { kotlinx.coroutines.delay(COVER_IDLE_MS); idle = true }
    }
    val showControls = adjustingVolume || pointerDown || (hovered && (!idle || volumeHovered))
    val reveal by androidx.compose.animation.core.animateFloatAsState(if (showControls) 1f else 0f,
        tween(if (idle) 450 else 220), label = "coverControls")
    val volume by playback.volume.collectAsState()
    Box(Modifier.size(size).shadow(24.dp, RoundedCornerShape(16.dp))
        .clip(RoundedCornerShape(16.dp)).hoverable(hover)
        .onPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Move) { event ->
            event.changes.firstOrNull()?.let { if (pointerActivity.moved(it.position)) lastMove = System.nanoTime() }
        }
        .onPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Press) {
            pointerDown = true
            lastMove = System.nanoTime()
        }
        .onPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Release) {
            pointerDown = it.changes.any { change -> change.pressed }
            lastMove = System.nanoTime()
        }
        .onPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Exit) {
            pointerActivity.exited()
            pointerDown = false
        }
        .then(if (hovered && !showControls) Modifier.pointerHoverIcon(BlankCursor, overrideDescendants = true) else Modifier)) {
        AnimatedCover(
            track = track,
            modifier = Modifier.fillMaxSize(),
            playbackClock = playback.clock,
            enabled = animateCover
        ) {
            DesktopArtwork(highRes = true, url = artworkUrl, modifier = Modifier.fillMaxSize(), cornerRadius = 16.dp,
                contentDescription = track?.title, contentScale = scale)
        }
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = reveal }) {
            Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.verticalGradient(
                0f to Color.Black.copy(alpha = .15f), .6f to Color.Black.copy(alpha = .25f), 1f to Color.Black.copy(alpha = .6f))))
            if (hovered || showControls || reveal > 0f) {
                PlayerTransport(playback,
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp))
                val volumeHeight = size * .34f
                Box(Modifier.align(Alignment.CenterEnd).padding(end = 14.dp).height(volumeHeight)
                    .hoverable(volumeHover), contentAlignment = Alignment.BottomCenter) {
                    HoverSlider(value = volume, onValueChange = playback::setVolume, vertical = true,
                        thin = 20.dp, thick = 24.dp, hitSize = 30.dp,
                        inactiveColor = Color.Black.copy(alpha = .22f),
                        onDraggingChanged = { adjustingVolume = it })
                    IconButton(onClick = playback::toggleMute, modifier = Modifier.size(26.dp)) {
                        Icon(if (volume > 0f) Icons.AutoMirrored.Rounded.VolumeUp else Icons.AutoMirrored.Rounded.VolumeOff,
                            desktopText(if (volume > 0f) "Mute" else "Unmute"),
                            tint = if (volume * volumeHeight.value >= 20f) Color(0xFF252525) else Color.White,
                            modifier = Modifier.size(14.dp))
                    }
                }
            }
        }
    }
}

/**
 * The phone's "Blur" player background: the artwork blurred 120dp (drawn at half size and scaled
 * up, as the phone does), scaled 1.10, crossfading between songs, under a 30% black scrim.
 * Browsing overrides blur/brightness/scale to match Liquify without affecting the player defaults.
 */
@Composable
internal fun BlurredArtworkBackground(url: String?, blurRadius: Dp = 120.dp, brightness: Float = .70f, artworkScale: Float = 1.10f) {
    // Pre-blurred once (see BlurredArtwork.kt): a full-window blur every animated frame made
    // resizing and maximizing lag. The fraction matches a 1280dp-wide window, scaled by artworkScale.
    val blurred by rememberBlurredArtwork(url, blurRadius.value / (1280f * artworkScale))
    Crossfade(targetState = blurred, animationSpec = tween(700), label = "playerBackground") { picture ->
        Box(Modifier.fillMaxSize().clipToBounds(), contentAlignment = Alignment.Center) {
            if (picture != null) {
                androidx.compose.foundation.Image(picture, contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = artworkScale; scaleY = artworkScale })
            }
        }
    }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 1f - brightness.coerceIn(0f, 1f))))
}
