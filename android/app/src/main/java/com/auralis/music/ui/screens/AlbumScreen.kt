package com.auralis.music.ui.screens

import com.auralis.music.ui.components.contextMenuAnchor
import com.auralis.music.R
import com.auralis.music.ui.i18n.str

import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.draw.shadow
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
import com.auralis.music.ui.glass.LocalLiquidGlass
import com.auralis.music.ui.components.specularHighlight
import com.auralis.music.ui.components.tactileBounce
import com.auralis.music.ui.theme.dynamicBackground
import com.auralis.music.ui.theme.dynamicPrimary
import com.auralis.music.ui.components.bottomChromePadding
import androidx.compose.ui.graphics.luminance

private val LIME_ACCENT: Color
    @Composable get() = MaterialTheme.dynamicPrimary
private val DARK_BG: Color
    @Composable get() = MaterialTheme.dynamicBackground

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
    savedAlbums: List<com.auralis.music.domain.model.SavedAlbum> = emptyList(),
    onToggleSaveAlbum: ((com.auralis.music.domain.model.SavedAlbum) -> Unit)? = null,
    onPlayNextAlbum: ((PlaylistResult) -> Unit)? = null,
    onAddToQueueAlbum: ((PlaylistResult) -> Unit)? = null,
    onShuffleAlbum: ((PlaylistResult) -> Unit)? = null,
    onDownloadAlbum: ((PlaylistResult) -> Unit)? = null,
    onAddAlbumToPlaylist: ((String, PlaylistResult) -> Unit)? = null,
    onCreatePlaylistAndAddAlbum: ((String, PlaylistResult) -> Unit)? = null,
    isAlbumPinned: ((String) -> Boolean)? = null,
    pinnedSpeedDialIds: Set<String> = emptySet(),
    onPinAlbumToSpeedDial: ((PlaylistResult) -> Unit)? = null,
    isTrackPinned: ((String) -> Boolean)? = null,
    onPinTrackToSpeedDial: ((Track) -> Unit)? = null,
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
    var showAlbumOptionsMenu by remember { mutableStateOf(false) }

    BackHandler {
        onBack()
    }

    val totalDurationSeconds = tracks.sumOf { it.duration }
    val totalMinutes = totalDurationSeconds / 60
    val durationText = when {
        totalMinutes >= 60 -> str(R.string.x_hr_x_min, totalMinutes / 60, totalMinutes % 60)
        totalMinutes > 0 -> "$totalMinutes min"
        else -> ""
    }

    val isLiquidGlass = LocalLiquidGlass.current != null
    val isDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val themePrimary = MaterialTheme.dynamicPrimary
    val glassSpecularRim = remember(isDark) {
        Brush.verticalGradient(
            if (isDark) listOf(
                Color.White.copy(alpha = 0.45f),
                Color.White.copy(alpha = 0.15f),
                Color.White.copy(alpha = 0.05f)
            ) else listOf(
                Color.White.copy(alpha = 0.95f),
                Color.White.copy(alpha = 0.50f),
                Color.Black.copy(alpha = 0.08f)
            )
        )
    }
    val glassActiveRim = remember(themePrimary) {
        Brush.verticalGradient(
            listOf(
                Color.White.copy(alpha = 0.80f),
                themePrimary.copy(alpha = 0.40f),
                Color.White.copy(alpha = 0.20f)
            )
        )
    }
    val glassPillBg = if (isDark) Color(0xFF1E1E22).copy(alpha = 0.65f) else Color.White.copy(alpha = 0.70f)
    val glassActiveBg = remember(themePrimary) {
        Brush.verticalGradient(
            listOf(
                themePrimary.copy(alpha = 0.95f),
                themePrimary.copy(alpha = 0.72f)
            )
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(DARK_BG)
    ) {

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = bottomChromePadding()
        ) {
            // ================================================================
            // 1. TOP APP BAR (Back Button + Title + Share Button)
            // ================================================================
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val topButtonBaseModifier = if (isLiquidGlass) {
                        Modifier
                            .size(40.dp)
                            .shadow(
                                elevation = 8.dp,
                                shape = CircleShape,
                                ambientColor = Color.Black.copy(alpha = 0.45f),
                                spotColor = Color.Black.copy(alpha = 0.35f)
                            )
                            .clip(CircleShape)
                            .background(glassPillBg)
                            .border(
                                width = 1.dp,
                                brush = glassSpecularRim,
                                shape = CircleShape
                            )
                            .specularHighlight(CircleShape, highlightAlpha = 0.30f)
                    } else {
                        Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.35f))
                    }

                    Box(
                        modifier = topButtonBaseModifier.tactileBounce(scaleDown = 0.88f) { onBack() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = str(R.string.back),
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = topButtonBaseModifier.tactileBounce(scaleDown = 0.88f) {
                                val shareText = str(R.string.listen_to_x_by_x_on_auralis_music_downlo, album.title, album.author ?: "Various Artists")
                                val sendIntent = Intent().apply {
                                    action = Intent.ACTION_SEND
                                    putExtra(Intent.EXTRA_TEXT, shareText)
                                    type = "text/plain"
                                }
                                context.startActivity(Intent.createChooser(sendIntent, str(R.string.share_album)))
                            },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Share,
                                contentDescription = str(R.string.share),
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        Box(
                            modifier = topButtonBaseModifier.tactileBounce(scaleDown = 0.88f) { showAlbumOptionsMenu = true },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = str(R.string.album_options),
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }
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
                        contentDescription = album.title,
                        highRes = true
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
                                    onOpenArtist(Artist(id = "", name = album.author!!))
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
                                text = album.author!!,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = LIME_ACCENT
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    // Subtitle (Tracks count + Duration)
                    val infoParts = buildList {
                        add(str(R.string.album))
                        if (tracks.isNotEmpty()) {
                            add(str(R.string.x_songs, tracks.size))
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
                    val buttonShape = RoundedCornerShape(24.dp)
                    val playAction = {
                        if (tracks.isNotEmpty()) {
                            onTrackClick(tracks.first(), tracks)
                        }
                    }
                    val shuffleAction = {
                        if (tracks.isNotEmpty()) {
                            val shuffled = tracks.shuffled()
                            onTrackClick(shuffled.first(), shuffled)
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (isLiquidGlass) {
                            // Liquid Glass: Play All (Vibrant glass active capsule)
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .shadow(
                                        elevation = 8.dp,
                                        shape = buttonShape,
                                        ambientColor = themePrimary.copy(alpha = 0.45f),
                                        spotColor = Color.Black.copy(alpha = 0.30f)
                                    )
                                    .clip(buttonShape)
                                    .background(glassActiveBg)
                                    .border(width = 1.2.dp, brush = glassActiveRim, shape = buttonShape)
                                    .specularHighlight(buttonShape, highlightAlpha = 0.38f)
                                    .tactileBounce(scaleDown = 0.94f) { playAction() },
                                contentAlignment = Alignment.Center
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.PlayArrow,
                                        contentDescription = str(R.string.play),
                                        tint = MaterialTheme.colorScheme.onPrimary,
                                        modifier = Modifier.size(22.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = str(R.string.play),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 15.sp,
                                        color = MaterialTheme.colorScheme.onPrimary
                                    )
                                }
                            }

                            // Liquid Glass: Shuffle (Frosted glass specular capsule)
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .shadow(
                                        elevation = 6.dp,
                                        shape = buttonShape,
                                        ambientColor = Color.Black.copy(alpha = 0.35f),
                                        spotColor = Color.Black.copy(alpha = 0.25f)
                                    )
                                    .clip(buttonShape)
                                    .background(glassPillBg)
                                    .border(width = 1.dp, brush = glassSpecularRim, shape = buttonShape)
                                    .specularHighlight(buttonShape, highlightAlpha = 0.25f)
                                    .tactileBounce(scaleDown = 0.94f) { shuffleAction() },
                                contentAlignment = Alignment.Center
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Shuffle,
                                        contentDescription = str(R.string.shuffle),
                                        tint = if (isDark) Color.White else MaterialTheme.colorScheme.onBackground,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = str(R.string.shuffle),
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 15.sp,
                                        color = if (isDark) Color.White else MaterialTheme.colorScheme.onBackground
                                    )
                                }
                            }
                        } else {
                            // Standard Fallback Buttons
                            Button(
                                onClick = playAction,
                                enabled = tracks.isNotEmpty(),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .tactileBounce(scaleDown = 0.94f),
                                shape = buttonShape,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = LIME_ACCENT,
                                    contentColor = MaterialTheme.colorScheme.onPrimary
                                )
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = str(R.string.play),
                                    modifier = Modifier.size(22.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = str(R.string.play),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp
                                )
                            }

                            OutlinedButton(
                                onClick = shuffleAction,
                                enabled = tracks.isNotEmpty(),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .tactileBounce(scaleDown = 0.94f),
                                shape = buttonShape,
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = MaterialTheme.colorScheme.onBackground
                                )
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Shuffle,
                                    contentDescription = str(R.string.shuffle),
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = str(R.string.shuffle),
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 15.sp
                                )
                            }
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
                        text = str(R.string.tracks),
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
                                .contextMenuAnchor { onTrackClick(track, tracks) }
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
                                    contentDescription = str(R.string.options),
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
                            text = str(R.string.no_tracks_found_for_this_album),
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
            val isPinned = isTrackPinned?.invoke(track.id) ?: pinnedSpeedDialIds.contains(track.id)
            TrackOptionsMenu(
                track = track,
                isFavorite = isFav,
                userPlaylists = userPlaylists,
                onToggleFavorite = { onFavoriteToggle(track) },
                isPinned = isPinned,
                onPinToSpeedDial = { onPinTrackToSpeedDial?.invoke(track) },
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
                onGoToArtist = { artistName ->
                    onOpenArtist(Artist(id = "", name = artistName))
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

        // ================================================================
        // 5. ALBUM OPTIONS MENU SHEET
        // ================================================================
        if (showAlbumOptionsMenu) {
            val isSaved = savedAlbums.any { it.id == album.id || it.title.equals(album.title, ignoreCase = true) }
            val cleanAlbumId = album.id.removePrefix("album-").removePrefix("VL")
            val isPinned = pinnedSpeedDialIds.any {
                val id = it.removePrefix("album-").removePrefix("VL")
                id == cleanAlbumId
            } || (isAlbumPinned?.invoke(album.id) == true)
            com.auralis.music.ui.components.AlbumOptionsMenu(
                album = album,
                isSingleOrEp = false,
                isFavorite = isSaved,
                userPlaylists = userPlaylists,
                isPinned = isPinned,
                onToggleFavorite = {
                    val savedAlbum = com.auralis.music.domain.model.SavedAlbum(
                        id = album.id,
                        title = album.title,
                        artist = album.author ?: "Various Artists",
                        thumbnail = album.thumbnail
                    )
                    onToggleSaveAlbum?.invoke(savedAlbum)
                },
                onShuffle = { onShuffleAlbum?.invoke(album) },
                onShare = {
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, album.title)
                        putExtra(
                            Intent.EXTRA_TEXT,
                            str(R.string.check_out_the_album_x_by_x_on_auralis_mu, album.title, album.author ?: "Various Artists", album.id.removePrefix("VL"))
                        )
                    }
                    context.startActivity(Intent.createChooser(shareIntent, str(R.string.share_album)))
                },
                onPlayNext = { onPlayNextAlbum?.invoke(album) },
                onAddToQueue = { onAddToQueueAlbum?.invoke(album) },
                onAddToPlaylist = { playlist -> onAddAlbumToPlaylist?.invoke(playlist.id, album) },
                onCreatePlaylistAndAdd = { title -> onCreatePlaylistAndAddAlbum?.invoke(title, album) },
                onPinToSpeedDial = { onPinAlbumToSpeedDial?.invoke(album) },
                onDownload = { onDownloadAlbum?.invoke(album) },
                onViewArtist = {
                    album.author?.let { onOpenArtist(Artist(id = "", name = it)) }
                },
                onDismiss = { showAlbumOptionsMenu = false }
            )
        }
    }
}
