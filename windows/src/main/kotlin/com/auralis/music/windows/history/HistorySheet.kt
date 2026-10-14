package com.auralis.music.windows.history

import com.auralis.music.ui.i18n.Strings
import com.auralis.music.ui.i18n.str

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import com.auralis.music.ui.components.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import com.auralis.music.ui.components.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import com.auralis.music.ui.components.IconButton
import androidx.compose.material3.MaterialTheme
import com.auralis.music.ui.components.OutlinedButton
import androidx.compose.material3.Text
import com.auralis.music.ui.components.TextButton
import androidx.compose.material3.Surface
import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.filled.PauseCircle
import com.auralis.music.data.datastore.LocalSettingsStores
import com.auralis.music.domain.model.PrivacySettings
import kotlinx.coroutines.launch
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import com.auralis.music.ui.components.SwipeableTrackContainer
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import com.auralis.music.ui.components.secondaryClick
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auralis.music.domain.model.HistoryEntry
import com.auralis.music.domain.model.Track
import com.auralis.music.ui.components.ArtworkCard
import com.auralis.music.ui.components.EqualizerBars
import com.auralis.music.ui.components.tactileBounce
import com.auralis.music.ui.theme.dynamicBackground
import com.auralis.music.ui.theme.dynamicPrimary
import com.auralis.music.ui.theme.dynamicSurface
import com.auralis.music.domain.recommendations.TrackDeduplicator
import com.auralis.music.ui.components.bottomChromePadding

val HISTORY_LIME: Color
    @Composable get() = MaterialTheme.dynamicPrimary
val HISTORY_CARD_BG: Color
    @Composable get() = MaterialTheme.dynamicSurface

/**
 * Fullscreen Listening History Sheet opened from the top header history button.
 * Lists all played songs without timestamps, clear history, and instant play.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistorySheet(
    history: List<HistoryEntry>,
    currentTrack: Track? = null,
    currentTrackId: String? = currentTrack?.id,
    isPlaying: Boolean,
    onTrackClick: (Track, List<Track>) -> Unit,
    onRemoveFromHistory: (String) -> Unit,
    onClearHistory: () -> Unit,
    onDismiss: () -> Unit,
    onPlayNext: ((Track) -> Unit)? = null,
    onAddToQueue: ((Track) -> Unit)? = null,
    onTrackMenu: ((Track) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var showClearDialog by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.dynamicBackground)
            .statusBarsPadding()
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ── TOP BAR (WITH SAFE INSETS & PROPER SPACING) ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = str(Strings.close),
                            tint = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Text(
                        text = str(Strings.listening_history),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground,
                        fontSize = 22.sp
                    )
                }

                if (history.isNotEmpty()) {
                    IconButton(
                        onClick = { showClearDialog = true },
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteOutline,
                            contentDescription = str(Strings.clear_history),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }

            val coroutineScope = rememberCoroutineScope()
            val privacyDataStore = LocalSettingsStores.current.privacy
            val privacySettings by privacyDataStore.settingsFlow.collectAsState(initial = PrivacySettings())

            if (privacySettings.pauseListenHistory) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.4f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                        .clickable {
                            coroutineScope.launch {
                                privacyDataStore.setPauseListenHistory(false)
                            }
                        }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.PauseCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = str(Strings.history_is_paused_in_privacy_settings_ta),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }

            if (history.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = 80.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            modifier = Modifier
                                .size(72.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.History,
                                contentDescription = null,
                                tint = HISTORY_LIME,
                                modifier = Modifier.size(36.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = str(Strings.no_listening_history_yet),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = str(Strings.tracks_you_listen_to_will_appear_here),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                val trackList = history.map { it.track }
                val currentTrackFp = remember(currentTrack) { currentTrack?.let { TrackDeduplicator.getSongFingerprint(it) } }

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = bottomChromePadding(start = 16.dp, end = 16.dp, top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        Text(
                            text = str(Strings.x_recently_played_songs, history.size),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
                        )
                    }

                    items(history, key = { "${it.track.id}_${it.playedAt}" }) { entry ->
                        val track = entry.track
                        val isCurrent = track.id == currentTrackId ||
                                (currentTrackFp != null && TrackDeduplicator.isDuplicateSong(TrackDeduplicator.getSongFingerprint(track), currentTrackFp, matchAlternateVersions = true))

                        SwipeableTrackContainer(
                            onPlayNext = onPlayNext?.let { { it(track) } },
                            onAddToQueue = onAddToQueue?.let { { it(track) } }
                        ) {
                            HistorySongCard(
                                track = track,
                                isCurrent = isCurrent,
                                isPlaying = isPlaying,
                                onPlay = { onTrackClick(track, trackList) },
                                onMenu = onTrackMenu?.let { menu -> { menu(track) } },
                                onRemove = { onRemoveFromHistory(track.id) }
                            )
                        }
                    }
                }
            }
        }
    }

    // ── CLEAR HISTORY CONFIRMATION DIALOG ──
    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(24.dp),
            title = {
                Text(
                    text = str(Strings.clear_listening_history),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 20.sp
                )
            },
            text = {
                Text(
                    text = str(Strings.this_will_remove_all_played_tracks_from),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 15.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onClearHistory()
                        showClearDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFEF4444),
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(str(Strings.clear_history), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showClearDialog = false },
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(str(Strings.cancel), color = HISTORY_LIME, fontWeight = FontWeight.SemiBold)
                }
            }
        )
    }
}

@Composable
private fun HistorySongCard(
    track: Track,
    isCurrent: Boolean,
    isPlaying: Boolean,
    onPlay: () -> Unit,
    onMenu: (() -> Unit)?,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interaction = remember(track.id) { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val hoverProgress by animateFloatAsState(
        targetValue = if (hovered) 1f else 0f,
        animationSpec = tween(160),
        label = "historyCardHover"
    )

    val shape = RoundedCornerShape(14.dp)
    val accentColor = MaterialTheme.colorScheme.primary

    // Specular liquid glass border: crisp top highlight tapering down to subtle bottom
    val borderBrush = if (isCurrent) {
        Brush.verticalGradient(
            listOf(
                accentColor.copy(alpha = 0.90f),
                accentColor.copy(alpha = 0.50f),
                accentColor.copy(alpha = 0.25f)
            )
        )
    } else {
        Brush.verticalGradient(
            listOf(
                Color.White.copy(alpha = 0.16f + 0.14f * hoverProgress),
                Color.White.copy(alpha = 0.07f + 0.05f * hoverProgress),
                Color.White.copy(alpha = 0.03f + 0.02f * hoverProgress)
            )
        )
    }

    // Liquid glass background: deep translucent dark glass with subtle gradient depth
    val glassBg = Brush.verticalGradient(
        listOf(
            Color.White.copy(alpha = if (isCurrent) 0.10f + 0.04f * hoverProgress else 0.06f + 0.04f * hoverProgress),
            Color(0xFF14131A).copy(alpha = if (isCurrent) 0.65f else 0.55f),
            Color(0xFF0F0E14).copy(alpha = if (isCurrent) 0.80f else 0.70f)
        )
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(58.dp)
            .clip(shape)
            .background(glassBg)
            .border(
                width = if (isCurrent) 1.5.dp else 1.dp,
                brush = borderBrush,
                shape = shape
            )
            .hoverable(interaction)
            .secondaryClick(onMenu)
            .clickable(onClick = onPlay)
    ) {
        // Liquid glass top specular sheen (gloss reflection line)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            Color.Transparent,
                            Color.White.copy(alpha = 0.25f + 0.15f * hoverProgress),
                            Color.Transparent
                        )
                    )
                )
        )

        // Active song liquid glass accent glow
        if (isCurrent) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                accentColor.copy(alpha = 0.14f),
                                accentColor.copy(alpha = 0.04f),
                                Color.Transparent
                            )
                        )
                    )
            )
        }

        // Hover sheen
        if (hoverProgress > 0.01f) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(Color.White.copy(alpha = 0.04f * hoverProgress))
            )
        }

        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Clean artwork card with its own subtle liquid glass border
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
            ) {
                ArtworkCard(
                    sizeToConstraints = true,
                    url = track.thumbnail,
                    fallbackTrack = track,
                    modifier = Modifier.fillMaxSize(),
                    cornerRadius = 8.dp,
                    contentDescription = track.title
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.SemiBold,
                    color = if (isCurrent) accentColor else Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = track.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.65f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (isCurrent) {
                EqualizerBars(
                    isPlaying = isPlaying,
                    modifier = Modifier.size(18.dp),
                    color = accentColor
                )
                Spacer(modifier = Modifier.width(6.dp))
            }

            if (onMenu != null) {
                val menuInteraction = remember { MutableInteractionSource() }
                val menuHovered by menuInteraction.collectIsHoveredAsState()
                IconButton(
                    onClick = onMenu,
                    modifier = Modifier
                        .size(32.dp)
                        .hoverable(menuInteraction)
                        .clip(CircleShape)
                        .background(if (menuHovered) Color.White.copy(alpha = 0.12f) else Color.Transparent)
                ) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "Options for ${track.title}",
                        tint = Color.White.copy(alpha = if (menuHovered) 1f else 0.75f),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            val removeInteraction = remember { MutableInteractionSource() }
            val removeHovered by removeInteraction.collectIsHoveredAsState()
            IconButton(
                onClick = onRemove,
                modifier = Modifier
                    .size(32.dp)
                    .hoverable(removeInteraction)
                    .clip(CircleShape)
                    .background(if (removeHovered) Color.White.copy(alpha = 0.12f) else Color.Transparent)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = str(Strings.remove),
                    tint = Color.White.copy(alpha = if (removeHovered) 1f else 0.65f),
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}
