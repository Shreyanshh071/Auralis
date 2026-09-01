package com.auralis.music.ui.screens

import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.auralis.music.domain.model.Artist
import com.auralis.music.domain.model.Playlist
import com.auralis.music.domain.model.PlaylistResult
import com.auralis.music.domain.model.Track
import com.auralis.music.ui.components.ArtworkCard
import com.auralis.music.ui.components.EqualizerBars
import com.auralis.music.ui.components.SwipeableTrackContainer
import com.auralis.music.ui.components.TrackOptionsMenu
import com.auralis.music.ui.components.getHighResArtworkUrl
import com.auralis.music.ui.components.tactileBounce

private val LIME_ACCENT: Color
    @Composable get() = MaterialTheme.colorScheme.primary
private val DARK_BG: Color
    @Composable get() = MaterialTheme.colorScheme.background

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun AlbumScreen(
    album: PlaylistResult,
    tracks: List<Track>,
    isLoading: Boolean,
    currentTrackId: String?,
    isPlaying: Boolean,
    userPlaylists: List<Playlist> = emptyList(),
    favoriteTracks: List<Track> = emptyList(),
    onTrackClick: (Track, List<Track>) -> Unit,
    onFavoriteToggle: (Track) -> Unit,
    onAddToPlaylist: (String, Track) -> Unit = { _, _ -> },
    onCreatePlaylistAndAdd: (String, Track) -> Unit = { _, _ -> },
    onPlayNext: (Track) -> Unit = {},
    onAddToQueue: (Track) -> Unit = {},
    onStartRadio: (Track) -> Unit = {},
    onOpenArtist: (Artist) -> Unit = {},
    onBack: () -> Unit,
    isInListenTogetherRoom: Boolean = false,
    onRecommendToRoom: ((Track) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var selectedTrackForMenu by remember { mutableStateOf<Track?>(null) }

    BackHandler {
        onBack()
    }

    val totalDurationSeconds = tracks.sumOf { it.duration }
    val totalMinutes = totalDurationSeconds / 60
    val durationText = when {
        totalMinutes >= 60 -> "${totalMinutes / 60} hr ${totalMinutes % 60} min"
        totalMinutes > 0 -> "$totalMinutes min"
        else -> ""
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(DARK_BG)
    ) {
        val bottomPadding = if (currentTrackId != null) 180.dp else 100.dp

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = bottomPadding)
        ) {
            // ================================================================
            // 1. TOP APP BAR (Back Button + Title + Share Button)
            // ================================================================
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.40f))
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White
                        )
                    }

                    IconButton(
                        onClick = {
                            val shareText = "Listen to ${album.title} by ${album.author ?: "Various Artists"} on Auralis Music\n\nDownload Auralis: https://auralis-self-nu.vercel.app/"
                            val sendIntent = Intent().apply {
                                action = Intent.ACTION_SEND
                                putExtra(Intent.EXTRA_TEXT, shareText)
                                type = "text/plain"
                            }
                            context.startActivity(Intent.createChooser(sendIntent, "Share Album"))
                        },
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.40f))
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = "Share",
                            tint = Color.White
                        )
                    }
                }
            }

            // ================================================================
            // 2. ALBUM HERO HEADER (Artwork, Title, Artist, Play & Shuffle)
            // ================================================================
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Large High-Res Artwork
                    ArtworkCard(
                        url = album.thumbnail ?: "",
                        modifier = Modifier
                            .size(220.dp)
                            .clip(RoundedCornerShape(20.dp)),
                        cornerRadius = 20.dp,
                        contentDescription = album.title
                    )

                    Spacer(modifier = Modifier.height(18.dp))

                    // Album Title
                    Text(
                        text = album.title,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    // Artist Name (Clickable)
                    if (!album.author.isNullOrBlank()) {
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    onOpenArtist(Artist(id = "", name = album.author))
                                }
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Person,
                                contentDescription = null,
                                tint = LIME_ACCENT,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = album.author,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = LIME_ACCENT
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    // Subtitle (Tracks count + Duration)
                    val infoParts = buildList {
                        add("Album")
                        if (tracks.isNotEmpty()) {
                            add("${tracks.size} songs")
                        }
                        if (durationText.isNotBlank()) {
                            add(durationText)
                        }
                    }
                    Text(
                        text = infoParts.joinToString(" • "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    // Action Buttons: Play All & Shuffle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = {
                                if (tracks.isNotEmpty()) {
                                    onTrackClick(tracks.first(), tracks)
                                }
                            },
                            enabled = tracks.isNotEmpty(),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .tactileBounce(scaleDown = 0.94f),
                            shape = RoundedCornerShape(24.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = LIME_ACCENT,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = "Play",
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Play",
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            )
                        }

                        OutlinedButton(
                            onClick = {
                                if (tracks.isNotEmpty()) {
                                    val shuffled = tracks.shuffled()
                                    onTrackClick(shuffled.first(), shuffled)
                                }
                            },
                            enabled = tracks.isNotEmpty(),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .tactileBounce(scaleDown = 0.94f),
                            shape = RoundedCornerShape(24.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.onBackground
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Default.Shuffle,
                                contentDescription = "Shuffle",
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Shuffle",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 15.sp
                            )
                        }
                    }
                }
            }

            // ================================================================
            // 3. TRACKLIST SECTION
            // ================================================================
            if (isLoading && tracks.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            color = LIME_ACCENT,
                            modifier = Modifier.size(36.dp)
                        )
                    }
                }
            } else if (tracks.isNotEmpty()) {
                item {
                    Text(
                        text = "Tracks",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                    )
                }

                itemsIndexed(
                    items = tracks,
                    key = { index, track -> "${track.id}_$index" }
                ) { index, track ->
                    val isCurrent = track.id == currentTrackId

                    SwipeableTrackContainer(
                        onPlayNext = { onPlayNext(track) },
                        onAddToQueue = { onAddToQueue(track) }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .combinedClickable(
                                    onClick = { onTrackClick(track, tracks) },
                                    onLongClick = { selectedTrackForMenu = track }
                                )
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Track Number or Equalizer
                            Box(
                                modifier = Modifier.width(32.dp),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                if (isCurrent) {
                                    EqualizerBars(
                                        isPlaying = isPlaying,
                                        modifier = Modifier.size(16.dp),
                                        color = LIME_ACCENT
                                    )
                                } else {
                                    Text(
                                        text = "${index + 1}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            // Track Title & Artist
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = track.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isCurrent) LIME_ACCENT else MaterialTheme.colorScheme.onBackground,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = track.artist.ifBlank { album.author ?: "" },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            // Duration
                            if (track.duration > 0) {
                                val mins = track.duration / 60
                                val secs = track.duration % 60
                                Text(
                                    text = String.format("%d:%02d", mins, secs),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 8.dp)
                                )
                            }

                            // 3-Dots Menu
                            IconButton(
                                onClick = { selectedTrackForMenu = track },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.MoreVert,
                                    contentDescription = "Options",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            } else {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 40.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No tracks found for this album.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // ================================================================
        // 4. TRACK OPTIONS MENU SHEET
        // ================================================================
        selectedTrackForMenu?.let { track ->
            val isFav = favoriteTracks.any { it.id == track.id }
            TrackOptionsMenu(
                track = track,
                isFavorite = isFav,
                userPlaylists = userPlaylists,
                onToggleFavorite = { onFavoriteToggle(track) },
                onPlayNext = {
                    onPlayNext(track)
                    selectedTrackForMenu = null
                },
                onAddToQueue = {
                    onAddToQueue(track)
                    selectedTrackForMenu = null
                },
                onStartRadio = {
                    onStartRadio(track)
                    selectedTrackForMenu = null
                },
                onGoToArtist = {
                    onOpenArtist(Artist(id = "", name = track.artist))
                    selectedTrackForMenu = null
                },
                onAddToPlaylist = { playlist ->
                    onAddToPlaylist(playlist.id, track)
                    selectedTrackForMenu = null
                },
                onCreatePlaylistAndAdd = { title ->
                    onCreatePlaylistAndAdd(title, track)
                    selectedTrackForMenu = null
                },
                isInListenTogetherRoom = isInListenTogetherRoom,
                onRecommendToRoom = { trk ->
                    onRecommendToRoom?.invoke(trk)
                    selectedTrackForMenu = null
                },
                onDismiss = { selectedTrackForMenu = null }
            )
        }
    }
}
