package com.auralis.music.ui.screens

import com.auralis.music.ui.components.contextMenuAnchor
import com.auralis.music.R
import com.auralis.music.ui.i18n.str
import com.kyant.backdrop.backdrops.layerBackdrop
import androidx.compose.runtime.derivedStateOf

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

    val accent = MaterialTheme.dynamicPrimary
    val pageBackground = MaterialTheme.dynamicBackground
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    // Records the list alone, for the frost under the pinned bar.
    val listBackdrop = com.kyant.backdrop.backdrops.rememberLayerBackdrop()
    val isSaved = savedAlbums.any { it.id == album.id || it.title.equals(album.title, ignoreCase = true) }

    com.auralis.music.ui.glass.OptionalGlassPage(
        background = pageBackground,
        accent = accent,
        modifier = modifier.fillMaxSize().background(pageBackground),
        glowWithoutGlass = true
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().layerBackdrop(listBackdrop),
            contentPadding = bottomChromePadding(),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            item(key = "album_hero", contentType = "hero") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(start = 20.dp, end = 20.dp, top = 68.dp, bottom = 18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    ArtworkCard(
                        url = album.thumbnail ?: "",
                        modifier = Modifier.size(272.dp),
                        cornerRadius = 26.dp,
                        elevation = 16.dp,
                        contentDescription = album.title,
                        highRes = true
                    )
                    Spacer(modifier = Modifier.height(22.dp))
                    Text(
                        text = album.title,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onBackground,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (!album.author.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = album.author!!,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = accent,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onOpenArtist(Artist(id = "", name = album.author!!)) }
                                .padding(horizontal = 10.dp, vertical = 2.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = buildList {
                            add(str(R.string.album))
                            if (tracks.isNotEmpty()) add(str(R.string.x_songs, tracks.size))
                            if (durationText.isNotBlank()) add(durationText)
                        }.joinToString(" • "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    com.auralis.music.ui.screens.ArtistActions(
                        isSubscribed = isSaved,
                        enabled = tracks.isNotEmpty(),
                        accent = accent,
                        onPlay = { tracks.firstOrNull()?.let { onTrackClick(it, tracks) } },
                        onShuffle = {
                            if (tracks.isNotEmpty()) {
                                val shuffled = tracks.shuffled()
                                onTrackClick(shuffled.first(), shuffled)
                            }
                        },
                        onRadio = { tracks.firstOrNull()?.let(onStartRadio) },
                        onToggleSubscribe = {
                            onToggleSaveAlbum?.invoke(
                                com.auralis.music.domain.model.SavedAlbum(
                                    id = album.id,
                                    title = album.title,
                                    artist = album.author ?: "",
                                    thumbnail = album.thumbnail
                                )
                            )
                        },
                        horizontalPadding = 0.dp
                    )
                }
            }

            if (isLoading && tracks.isEmpty()) {
                item(key = "album_loading", contentType = "loading") {
                    Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = accent, modifier = Modifier.size(36.dp))
                    }
                }
            } else if (tracks.isNotEmpty()) {
                itemsIndexed(
                    items = tracks,
                    key = { index, track -> "${track.id}_$index" },
                    contentType = { _, _ -> "track" }
                ) { index, track ->
                    val isCurrent = track.id == currentTrackId
                    val shape = RoundedCornerShape(
                        topStart = if (index == 0) 22.dp else 6.dp,
                        topEnd = if (index == 0) 22.dp else 6.dp,
                        bottomStart = if (index == tracks.lastIndex) 22.dp else 6.dp,
                        bottomEnd = if (index == tracks.lastIndex) 22.dp else 6.dp
                    )
                    Box(Modifier.padding(horizontal = 16.dp)) {
                        SwipeableTrackContainer(
                            onPlayNext = { onPlayNext(track) },
                            onAddToQueue = { onAddToQueue(track) }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(shape)
                                    .background(
                                        if (isCurrent) accent.copy(alpha = 0.14f)
                                        else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.07f)
                                    )
                                    .contextMenuAnchor { onTrackClick(track, tracks) }
                                    .combinedClickable(
                                        onClick = { onTrackClick(track, tracks) },
                                        onLongClick = { selectedTrackForMenu = track }
                                    )
                                    .padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(Modifier.width(32.dp), contentAlignment = Alignment.CenterStart) {
                                    if (isCurrent) {
                                        EqualizerBars(isPlaying = isPlaying, modifier = Modifier.size(16.dp), color = accent)
                                    } else {
                                        Text(
                                            text = "${index + 1}",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = track.title,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isCurrent) accent else MaterialTheme.colorScheme.onBackground,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    val duration = if (track.duration > 0) " • %d:%02d".format(track.duration / 60, track.duration % 60) else ""
                                    Text(
                                        text = "${track.artist.ifBlank { album.author ?: "" }}$duration",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                IconButton(onClick = { selectedTrackForMenu = track }) {
                                    Icon(
                                        imageVector = Icons.Default.MoreVert,
                                        contentDescription = str(R.string.options),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                item(key = "album_empty", contentType = "empty") {
                    Box(Modifier.fillMaxWidth().padding(top = 40.dp), contentAlignment = Alignment.Center) {
                        Text(
                            text = str(R.string.no_tracks_found_for_this_album),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        ArtistFloatingButtons(
            backdrop = listBackdrop,
            onBack = onBack,
            onShare = {
                val shareText = str(R.string.listen_to_x_by_x_on_auralis_music_downlo, album.title, album.author ?: "Various Artists")
                val sendIntent = Intent().apply {
                    action = Intent.ACTION_SEND
                    putExtra(Intent.EXTRA_TEXT, shareText)
                    type = "text/plain"
                }
                context.startActivity(Intent.createChooser(sendIntent, str(R.string.share_album)))
            },
            onMore = { showAlbumOptionsMenu = true }
        )

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
