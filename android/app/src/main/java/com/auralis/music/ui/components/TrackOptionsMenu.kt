package com.auralis.music.ui.components

import com.auralis.music.R
import com.auralis.music.ui.i18n.str

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auralis.music.data.download.AuralisDownloadManager
import com.auralis.music.data.network.AlbumMetadataResolver
import com.auralis.music.domain.model.Playlist
import com.auralis.music.domain.model.Track
import kotlinx.coroutines.launch

data class SongPresentationActions(
    val openAmbient: (Track) -> Unit,
    val openLyrics: (Track) -> Unit,
    /** Lyrics for this song are on screen right now, so the menu offers to hide them. */
    val isLyricsShown: (Track) -> Boolean = { false },
    val hideLyrics: (Track) -> Unit = {},
    /** Ambient mode and lyrics only make sense for the song that is playing. */
    val isNowPlaying: (Track) -> Boolean = { false },
    val openListenTogether: ((Track) -> Unit)? = null
)

val LocalSongPresentationActions = staticCompositionLocalOf<SongPresentationActions?> { null }

/**
 * YouTube Music style Modal Bottom Sheet for Track Options:
 * - Compact drag handle
 * - Artwork, Title, Subtitle (Artist), Favorite heart
 * - Quick Action Buttons: [ Play next ], [ Add ], [ Share ]
 * - Action Group 1: Add to queue
 * - Action Group 2: Pin to Speed dial
 * - Action Group 3: Add to library / Remove from library
 * - Action Group 4: Download
 * - Action Group 5: View artist, View album (with authentic album metadata)
 * - Action Group 6: Recommend to room (Listen Together, if in room)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackOptionsMenu(
    track: Track,
    isFavorite: Boolean,
    userPlaylists: List<Playlist>,
    onToggleFavorite: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    onRemoveFromQueue: (() -> Unit)? = null,
    onRemoveFromPlaylist: (() -> Unit)? = null,
    onStartRadio: (() -> Unit)? = null,
    onPinToSpeedDial: (() -> Unit)? = null,
    isPinned: Boolean = false,
    onGoToArtist: (() -> Unit)? = null,
    onGoToAlbum: ((albumId: String?, albumTitle: String, albumArtist: String?, albumArt: String?) -> Unit)? = null,
    onAddToPlaylist: (Playlist) -> Unit,
    onCreatePlaylistAndAdd: (String) -> Unit,
    isInListenTogetherRoom: Boolean = false,
    onRecommendToRoom: ((Track) -> Unit)? = null,
    onDismiss: () -> Unit,
    queueReferenceStyle: Boolean = false,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
    sheetBackground: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val presentationActions = LocalSongPresentationActions.current
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val dynamicSurface = MaterialTheme.colorScheme.surface
    val dynamicPrimary = MaterialTheme.colorScheme.primary
    // The sheet always sits on the song's own colour field (the immersive player's backdrop,
    // built from this song's cover); the cards are translucent so it reads through them.
    val actionCardColor = Color.White.copy(alpha = 0.08f)
    val background: @Composable () -> Unit = sheetBackground ?: { CoverSheetBackground(track.thumbnail) }
    // Long-pressed in liquid glass mode: the small glass menu pops from the song instead.
    val glassAnchor = rememberContextMenuAnchor()
    var useGlassMenu by remember { mutableStateOf(glassAnchor != null) }

    var showPlaylistPicker by remember { mutableStateOf(false) }
    var showCreatePlaylistDialog by remember { mutableStateOf(false) }
    var newPlaylistName by remember { mutableStateOf("") }
    var localIsFavorite by remember(isFavorite) { mutableStateOf(isFavorite) }
    var localIsPinned by remember(track.id, isPinned) { mutableStateOf(isPinned) }

    // Authentic Album Resolution (Apple Music / iTunes query for true parent album)
    val cachedAlbum = remember(track.id, track.title, track.artist) {
                AlbumMetadataResolver.getCached(track.title, track.artist, track.album)
    }
    val isRedundantTrackAlbum = remember(track.album, track.title) {
        AlbumMetadataResolver.needsResolving(track.album, track.title)
    }

    val initialAlbumName = when {
        !isRedundantTrackAlbum && !track.album.isNullOrBlank() -> track.album
        cachedAlbum != null && !cachedAlbum.albumTitle.isNullOrBlank() && !cachedAlbum.isSingle -> cachedAlbum.albumTitle
        else -> null
    }
    val initialAlbumId = when {
        !isRedundantTrackAlbum && !track.albumId.isNullOrBlank() -> track.albumId
        cachedAlbum != null && !cachedAlbum.albumId.isNullOrBlank() && !cachedAlbum.isSingle -> cachedAlbum.albumId
        else -> null
    }

    var resolvedAlbumName by remember(track.id) { mutableStateOf(initialAlbumName) }
    var resolvedAlbumId by remember(track.id) { mutableStateOf(initialAlbumId) }
    var resolvedArtistName by remember(track.id) { mutableStateOf(cachedAlbum?.artistName) }
    var resolvedAlbumArt by remember(track.id) { mutableStateOf(cachedAlbum?.albumArt) }
    // Whether the album lookup has finished (or wasn't needed), and whether it found a single.
    var albumLookupDone by remember(track.id) { mutableStateOf(initialAlbumName != null && initialAlbumId != null) }
    var isKnownSingle by remember(track.id) { mutableStateOf(cachedAlbum?.isSingle == true) }

    LaunchedEffect(track.id, track.title, track.artist, track.album) {
        if (resolvedAlbumName.isNullOrBlank() || resolvedAlbumId.isNullOrBlank() ||
            AlbumMetadataResolver.isRedundantOrSingle(resolvedAlbumName, track.title)) {
            val resolved = AlbumMetadataResolver.resolveAlbum(
                trackTitle = track.title,
                artistName = track.artist,
                knownAlbum = track.album
            )
            if (resolved?.isSingle == true) isKnownSingle = true
            if (resolved != null && !resolved.albumTitle.isNullOrBlank() && !resolved.isSingle) {
                val keepExistingTitle = !isRedundantTrackAlbum && !track.album.isNullOrBlank()
                if (!keepExistingTitle || resolved.albumTitle.contains(track.album!!, ignoreCase = true) || track.album!!.contains(resolved.albumTitle, ignoreCase = true)) {
                    resolvedAlbumName = resolved.albumTitle
                }
                resolvedAlbumId = resolved.albumId
                resolvedArtistName = resolved.artistName
                resolvedAlbumArt = resolved.albumArt
            }
        }
        if (resolvedAlbumName.isNullOrBlank() && AlbumMetadataResolver.needsResolving(track.album, track.title)) {
            // No parent album found. Ask YouTube Music which release this exact video belongs to
            // when the track arrived without one (radio, queue restore); iTunes often doesn't list
            // the song at all, so "nothing found" there proves nothing.
            val release = if (track.album.isNullOrBlank()) runCatching {
                com.auralis.music.data.network.InnerTubeClient().getQueue(listOf(track.id)).firstOrNull()
            }.getOrNull() else null
            val releaseTitle = release?.album ?: track.album
            when {
                releaseTitle.isNullOrBlank() -> Unit
                // A release named after the song is the song's own single.
                AlbumMetadataResolver.isRedundantOrSingle(releaseTitle, track.title) -> isKnownSingle = true
                !AlbumMetadataResolver.isCompilation(releaseTitle) && release?.albumId != null -> {
                    resolvedAlbumName = releaseTitle
                    resolvedAlbumId = release.albumId
                }
            }
        }
        albumLookupDone = true
    }

    // null = no real album. Never a placeholder: "Album" used to be opened as a search for an
    // album literally called "Album", showing some unrelated record.
    val displayAlbum: String? = resolvedAlbumName
        ?: track.album.takeIf { !it.isNullOrBlank() && !AlbumMetadataResolver.isRedundantOrSingle(it, track.title) }

    fun shareTrack() {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, track.title)
            putExtra(
                Intent.EXTRA_TEXT,
                "Listen to '${track.title}' by ${track.artist} on Auralis Music\nhttps://music.youtube.com/watch?v=${track.id}\n\nDownload Auralis App: https://auralis-self-nu.vercel.app/"
            )
        }
        context.startActivity(Intent.createChooser(shareIntent, str(R.string.share_track)))
    }

    fun toggleFavorite() {
        val newFav = !localIsFavorite
        localIsFavorite = newFav
        onToggleFavorite()
        Toast.makeText(
            context,
            if (newFav) str(R.string.saved_to_library) else str(R.string.removed_from_library),
            Toast.LENGTH_SHORT
        ).show()
    }

    fun togglePin() {
        val nextPinned = !localIsPinned
        localIsPinned = nextPinned
        val msg = if (nextPinned) str(R.string.pinned_to_speed_dial) else str(R.string.unpinned_from_speed_dial)
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        onPinToSpeedDial?.invoke()
    }

    fun openAlbum() {
        val albumTitle = displayAlbum ?: return
        val targetAlbumId = resolvedAlbumId
            ?: track.albumId.takeIf { !AlbumMetadataResolver.isRedundantOrSingle(track.album, track.title) }
        onGoToAlbum?.invoke(
            targetAlbumId,
            albumTitle,
            resolvedArtistName ?: track.artist,
            resolvedAlbumArt ?: track.thumbnail
        )
    }

    if (useGlassMenu && glassAnchor != null) {
        val isDownloaded = AuralisDownloadManager.isDownloaded(track.id)
        val isDownloading = AuralisDownloadManager.isDownloading(track.id)
        val glassItems = buildList {
            add(GlassMenuItem(str(R.string.play_next), Icons.AutoMirrored.Filled.PlaylistPlay) { onPlayNext() })
            add(GlassMenuItem(str(R.string.add_to_queue), Icons.AutoMirrored.Filled.QueueMusic) { onAddToQueue() })
            if (onRemoveFromQueue != null) {
                add(GlassMenuItem(str(R.string.remove_from_queue), Icons.Default.RemoveCircleOutline) { onRemoveFromQueue() })
            }
            if (onRemoveFromPlaylist != null) {
                add(GlassMenuItem(
                    str(R.string.remove_from_playlist),
                    Icons.Default.DeleteOutline,
                    tint = MaterialTheme.colorScheme.error
                ) { onRemoveFromPlaylist() })
            }
            // Hands over to the regular sheet's playlist picker.
            add(GlassMenuItem(str(R.string.add_to_playlist), Icons.AutoMirrored.Filled.PlaylistAdd, dismisses = false) {
                showPlaylistPicker = true
                useGlassMenu = false
            })
            add(GlassMenuItem(
                if (localIsFavorite) str(R.string.remove_from_library) else str(R.string.add_to_library),
                if (localIsFavorite) Icons.Default.LibraryAddCheck else Icons.Default.LibraryAdd,
                tint = if (localIsFavorite) Color(0xFFFF4081) else null
            ) { toggleFavorite() })
            add(GlassMenuItem(
                if (isDownloaded) str(R.string.remove_download) else if (isDownloading) str(R.string.downloading_2) else str(R.string.download),
                if (isDownloaded) Icons.Default.DownloadDone else if (isDownloading) Icons.Default.CloudDownload else Icons.Default.Download,
                tint = if (isDownloaded) Color(0xFF4CAF50) else null
            ) {
                if (isDownloaded) AuralisDownloadManager.removeDownload(track.id) else AuralisDownloadManager.downloadTrack(track)
            })
            add(GlassMenuItem(str(R.string.view_album), Icons.Default.Album, enabled = displayAlbum != null) { openAlbum() })
            add(GlassMenuItem(str(R.string.view_artist), Icons.Default.Person) { onGoToArtist?.invoke() })
            if (!queueReferenceStyle) {
                add(GlassMenuItem(
                    if (localIsPinned) str(R.string.unpin_from_speed_dial) else str(R.string.pin_to_speed_dial),
                    if (localIsPinned) Icons.Default.PushPin else Icons.Default.Add
                ) { togglePin() })
            }
            presentationActions?.takeIf { it.isNowPlaying(track) }?.let { actions ->
                actions.openListenTogether?.let { open ->
                    add(GlassMenuItem(str(R.string.listen_together), Icons.Default.Groups) { open(track) })
                }
                add(GlassMenuItem(str(R.string.ambient_mode), Icons.Default.Fullscreen) { actions.openAmbient(track) })
                val lyricsShown = actions.isLyricsShown(track)
                add(GlassMenuItem(str(if (lyricsShown) R.string.hide_lyrics else R.string.show_lyrics), Icons.Default.Lyrics) {
                    if (lyricsShown) actions.hideLyrics(track) else actions.openLyrics(track)
                })
            }
            if (isInListenTogetherRoom && onRecommendToRoom != null) {
                add(GlassMenuItem(str(R.string.add_to_room_queue), Icons.Default.Group) { onRecommendToRoom(track) })
            }
            add(GlassMenuItem(str(R.string.share), Icons.Default.Share) { shareTrack() })
        }
        GlassContextMenu(anchor = glassAnchor, items = glassItems, onDismiss = onDismiss)
    } else CoverSheetTheme {

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = CoverSheetFloor,
        contentColor = MaterialTheme.colorScheme.onSurface,
        dragHandle = null,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        modifier = modifier
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
        // The colour field fills the sheet behind the content; the sheet's shape clips it.
        Box(modifier = Modifier.matchParentSize()) { background() }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
        ) {
            // ── DRAG HANDLE ──
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp, bottom = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .width(36.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f))
                )
            }

            if (!showPlaylistPicker) {
                // ── HEADER ROW (Artwork, Title, Subtitle: Artist, Favorite Heart) ──
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ArtworkCard(
                        url = track.thumbnail,
                        modifier = Modifier
                            .size(54.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f), RoundedCornerShape(10.dp)),
                        cornerRadius = 10.dp,
                        contentDescription = track.title
                    )

                    Spacer(modifier = Modifier.width(14.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = track.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = track.artist.ifBlank { str(R.string.unknown_artist) },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    IconButton(
                        onClick = { toggleFavorite() },
                        modifier = Modifier.size(42.dp)
                    ) {
                        Icon(
                            imageVector = if (localIsFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            contentDescription = if (localIsFavorite) str(R.string.saved) else str(R.string.save),
                            tint = if (localIsFavorite) Color(0xFFFF4081) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                HorizontalDivider(
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                    modifier = Modifier.padding(top = 8.dp, bottom = 14.dp)
                )

                // ── SCROLLABLE ACTIONS CONTENT ──
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Play next / Add / Share action row.
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Pill 1: Play next
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(actionCardColor)
                                .clickable {
                                    onPlayNext()
                                    onDismiss()
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.PlaylistPlay,
                                    contentDescription = str(R.string.play_next),
                                    tint = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = str(R.string.play_next),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 13.5.sp
                                )
                            }
                        }

                        // Pill 2: Add
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(actionCardColor)
                                .clickable {
                                    showPlaylistPicker = true
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.PlaylistAdd,
                                    contentDescription = str(R.string.add),
                                    tint = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = str(R.string.add),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 13.5.sp
                                )
                            }
                        }

                        // Pill 3: Share
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(actionCardColor)
                                .clickable {
                                    shareTrack()
                                    onDismiss()
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Share,
                                    contentDescription = str(R.string.share),
                                    tint = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = str(R.string.share),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 13.5.sp
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(2.dp))

                    // ── GROUP 1: ADD TO QUEUE ──
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(actionCardColor)
                    ) {
                        TrackOptionRow(
                            icon = Icons.AutoMirrored.Filled.QueueMusic,
                            title = str(R.string.add_to_queue),
                            subtitle = str(R.string.add_to_the_bottom_of_your_queue),
                            onClick = {
                                onAddToQueue()
                                onDismiss()
                            }
                        )

                        if (onRemoveFromQueue != null) {
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f),
                                modifier = Modifier.padding(horizontal = 16.dp)
                            )
                            TrackOptionRow(
                                icon = Icons.Default.RemoveCircleOutline,
                                title = str(R.string.remove_from_queue),
                                subtitle = str(R.string.take_this_song_out_of_up_next),
                                onClick = {
                                    onRemoveFromQueue()
                                    onDismiss()
                                }
                            )
                        }
                    }

                    // ── GROUP 2: PIN TO SPEED DIAL ──
                    if (!queueReferenceStyle) Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(actionCardColor)
                    ) {
                        TrackOptionRow(
                            icon = if (localIsPinned) Icons.Default.PushPin else Icons.Default.Add,
                            title = if (localIsPinned) str(R.string.unpin_from_speed_dial) else str(R.string.pin_to_speed_dial),
                            subtitle = null,
                            onClick = {
                                onDismiss()
                                togglePin()
                            }
                        )
                    }

                    // ── GROUP 3: ADD TO LIBRARY ──
                    if (!queueReferenceStyle) Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(actionCardColor)
                    ) {
                        TrackOptionRow(
                            icon = if (localIsFavorite) Icons.Default.LibraryAddCheck else Icons.Default.LibraryAdd,
                            title = if (localIsFavorite) str(R.string.remove_from_library) else str(R.string.add_to_library),
                            subtitle = if (localIsFavorite) str(R.string.remove_from_your_library) else str(R.string.save_to_your_library),
                            iconTint = if (localIsFavorite) Color(0xFFFF4081) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f),
                            titleColor = if (localIsFavorite) Color(0xFFFF4081) else MaterialTheme.colorScheme.onSurface,
                            onClick = {
                                toggleFavorite()
                                onDismiss()
                            }
                        )
                    }

                    // ── GROUP 3B: REMOVE FROM PLAYLIST (WHEN IN PLAYLIST CONTEXT) ──
                    if (onRemoveFromPlaylist != null) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(actionCardColor)
                        ) {
                            TrackOptionRow(
                                icon = Icons.Default.DeleteOutline,
                                title = str(R.string.remove_from_playlist),
                                subtitle = str(R.string.take_this_song_out_of_this_playlist),
                                iconTint = MaterialTheme.colorScheme.error,
                                titleColor = MaterialTheme.colorScheme.error,
                                onClick = {
                                    onRemoveFromPlaylist()
                                    onDismiss()
                                }
                            )
                        }
                    }

                    // ── GROUP 4: DOWNLOAD ──
                    presentationActions?.takeIf { it.isNowPlaying(track) }?.let { actions ->
                        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(actionCardColor)) {
                            actions.openListenTogether?.let { open ->
                                TrackOptionRow(icon = Icons.Default.Groups, title = str(R.string.listen_together), subtitle = null,
                                    onClick = { onDismiss(); open(track) })
                            }
                            TrackOptionRow(icon = Icons.Default.Fullscreen, title = str(R.string.ambient_mode), subtitle = null,
                                onClick = { onDismiss(); actions.openAmbient(track) })
                            val lyricsShown = actions.isLyricsShown(track)
                            TrackOptionRow(icon = Icons.Default.Lyrics, title = str(if (lyricsShown) R.string.hide_lyrics else R.string.show_lyrics), subtitle = null,
                                onClick = { onDismiss(); if (lyricsShown) actions.hideLyrics(track) else actions.openLyrics(track) })
                        }
                    }

                    val isDownloaded = AuralisDownloadManager.isDownloaded(track.id)
                    val isDownloading = AuralisDownloadManager.isDownloading(track.id)

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(actionCardColor)
                    ) {
                        TrackOptionRow(
                            icon = if (isDownloaded) Icons.Default.DownloadDone else if (isDownloading) Icons.Default.CloudDownload else Icons.Default.Download,
                            title = if (isDownloaded) str(R.string.remove_download) else if (isDownloading) str(R.string.downloading_2) else str(R.string.download),
                            subtitle = if (isDownloaded) str(R.string.downloaded_to_device) else if (isDownloading) str(R.string.saving_for_offline_playback) else str(R.string.make_available_for_offline_playback),
                            iconTint = if (isDownloaded) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f),
                            titleColor = if (isDownloaded) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurface,
                            onClick = {
                                if (isDownloaded) {
                                    AuralisDownloadManager.removeDownload(track.id)
                                } else {
                                    AuralisDownloadManager.downloadTrack(track)
                                }
                                onDismiss()
                            }
                        )
                    }

                    // ── GROUP 5: VIEW ARTIST, VIEW ALBUM ──
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(actionCardColor)
                    ) {
                        TrackOptionRow(
                            icon = Icons.Default.Person,
                            title = str(R.string.view_artist),
                            subtitle = track.artist.ifBlank { str(R.string.unknown_artist) },
                            onClick = {
                                onGoToArtist?.invoke()
                                onDismiss()
                            }
                        )

                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f),
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )

                        TrackOptionRow(
                            icon = Icons.Default.Album,
                            title = str(R.string.view_album),
                            subtitle = displayAlbum ?: when {
                                !albumLookupDone -> str(R.string.finding_album)
                                isKnownSingle -> str(R.string.single_not_part_of_an_album)
                                else -> str(R.string.no_album_for_this_song)
                            },
                            enabled = displayAlbum != null,
                            onClick = {
                                if (displayAlbum == null) return@TrackOptionRow
                                openAlbum()
                                onDismiss()
                            }
                        )
                    }

                    // ── GROUP 6: LISTEN TOGETHER (IF IN ROOM) ──
                    if (isInListenTogetherRoom && onRecommendToRoom != null) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(actionCardColor)
                        ) {
                            TrackOptionRow(
                                icon = Icons.Default.Group,
                                title = str(R.string.add_to_room_queue),
                                subtitle = str(R.string.adds_it_for_everyone_or_asks_the_host_fi),
                                onClick = {
                                    onRecommendToRoom(track)
                                    onDismiss()
                                }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(18.dp))
                }
            } else {
                // ── PLAYLIST PICKER VIEW ──
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    IconButton(onClick = { showPlaylistPicker = false }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = str(R.string.back),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Text(
                        text = str(R.string.save_to_playlist),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    IconButton(onClick = { showCreatePlaylistDialog = true }) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = str(R.string.new_playlist),
                            tint = dynamicPrimary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                if (userPlaylists.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(28.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = str(R.string.no_custom_playlists_yet),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(
                                onClick = { showCreatePlaylistDialog = true },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = dynamicPrimary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary
                                ),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text(str(R.string.create_playlist), fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 380.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(userPlaylists, key = { it.id }) { playlist ->
                            val alreadyInPlaylist = playlist.tracks.any { it.id == track.id }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable {
                                        if (alreadyInPlaylist) {
                                            com.auralis.music.ui.components.AppPillManager.showPill(str(R.string.already_in_x, playlist.title))
                                        } else {
                                            onAddToPlaylist(playlist)
                                            showPlaylistPicker = false
                                            onDismiss()
                                        }
                                    }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                ArtworkCard(
                                    url = playlist.coverUrl ?: playlist.tracks.firstOrNull()?.thumbnail,
                                    modifier = Modifier.size(42.dp),
                                    cornerRadius = 8.dp,
                                    contentDescription = playlist.title
                                )
                                Spacer(modifier = Modifier.width(14.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = playlist.title,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = str(R.string.x_songs, playlist.tracks.size),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                    )
                                }
                                Icon(
                                    imageVector = if (alreadyInPlaylist) Icons.Default.Check else Icons.Default.Add,
                                    contentDescription = if (alreadyInPlaylist) str(R.string.already_in_playlist) else str(R.string.add),
                                    tint = if (alreadyInPlaylist) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
        }
    }
    }

    // ── CREATE PLAYLIST DIALOG ──
    if (showCreatePlaylistDialog) {
        AlertDialog(
            onDismissRequest = {
                showCreatePlaylistDialog = false
                newPlaylistName = ""
            },
            title = {
                Text(str(R.string.new_playlist), fontWeight = FontWeight.Bold)
            },
            text = {
                OutlinedTextField(
                    value = newPlaylistName,
                    onValueChange = { newPlaylistName = it },
                    label = { Text(str(R.string.playlist_name)) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = dynamicPrimary,
                        focusedLabelColor = dynamicPrimary
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (newPlaylistName.isNotBlank()) {
                            onCreatePlaylistAndAdd(newPlaylistName.trim())
                            newPlaylistName = ""
                            showCreatePlaylistDialog = false
                            showPlaylistPicker = false
                            onDismiss()
                        }
                    },
                    enabled = newPlaylistName.isNotBlank()
                ) {
                    Text(str(R.string.create_add), fontWeight = FontWeight.Bold, color = dynamicPrimary)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showCreatePlaylistDialog = false
                    newPlaylistName = ""
                }) {
                    Text(str(R.string.cancel))
                }
            },
            containerColor = dynamicSurface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            textContentColor = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun TrackOptionRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    iconTint: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f),
    titleColor: Color = MaterialTheme.colorScheme.onSurface,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.5f)
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = title,
            tint = iconTint,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = titleColor,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!subtitle.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(1.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    fontSize = 12.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
