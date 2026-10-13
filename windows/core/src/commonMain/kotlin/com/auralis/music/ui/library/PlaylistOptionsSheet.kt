package com.auralis.music.ui.library

import com.auralis.music.ui.components.Button
import com.auralis.music.ui.components.TextButton

import com.auralis.music.ui.i18n.Strings

import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Brush
import androidx.compose.material.icons.filled.LockOpen
import com.auralis.music.ui.components.contextMenuAnchor
import com.auralis.music.ui.components.tapMenuAnchor
import com.auralis.music.ui.components.captureForTap
import com.auralis.music.ui.components.GlassContextMenu
import com.auralis.music.ui.components.GlassMenuItem
import com.auralis.music.ui.components.AuralisRefreshBox
import com.auralis.music.ui.i18n.str
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import com.auralis.music.ui.components.tactileBounce
import com.auralis.music.ui.components.smoothScrollToTop
import com.auralis.music.ui.theme.AuralisDuration
import com.auralis.music.ui.theme.AuralisEasing
import com.auralis.music.ui.theme.LocalReducedMotion
import com.auralis.music.ui.theme.auralisDetailBackwardEnter
import com.auralis.music.ui.theme.auralisDetailBackwardExit
import com.auralis.music.ui.theme.auralisDetailForwardEnter
import com.auralis.music.ui.theme.auralisDetailForwardExit
import com.auralis.music.ui.theme.auralisNavigationEnter
import com.auralis.music.ui.theme.auralisNavigationExit
import com.auralis.music.ui.theme.dynamicOnBackground
import com.auralis.music.ui.theme.motionTween
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.ui.unit.Dp
import com.auralis.music.ui.components.LiquidGlassPageHeader
import com.auralis.music.ui.components.LiquidGlassPageTitle
import com.auralis.music.ui.components.rememberPageHeaderGlass
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.zIndex
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import kotlinx.coroutines.isActive
import androidx.compose.runtime.toMutableStateList
import androidx.compose.runtime.withFrameNanos
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Leaderboard
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.layout.layout
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auralis.music.domain.model.Artist
import com.auralis.music.domain.model.Playlist
import com.auralis.music.domain.model.SavedAlbum
import com.auralis.music.domain.model.SavedArtist
import com.auralis.music.domain.model.Track
import com.auralis.music.ui.components.ArtworkCard
import com.auralis.music.ui.components.EqualizerBars
import com.auralis.music.ui.components.TrackOptionsMenu
import com.auralis.music.ui.components.tactileBounce
import com.auralis.music.ui.theme.AuralisPrimary
import com.auralis.music.ui.theme.AuralisSurfaceElevated
import com.auralis.music.ui.theme.dynamicBackground
import com.auralis.music.ui.theme.dynamicPrimary
import com.auralis.music.ui.theme.dynamicSurface
import com.auralis.music.ui.components.bottomChromePadding
import java.util.Locale



@Composable
fun PlaylistOptionsBottomSheet(
    playlist: Playlist,
    initialDialog: PlaylistDialogType? = null,
    onDismiss: () -> Unit,
    onEditPlaylist: ((String, String, String?, String?) -> Unit)? = null,
    onAddToQueue: ((List<Track>) -> Unit)? = null,
    onDeletePlaylist: (() -> Unit)? = null,
    onClearAllDownloads: (() -> Unit)? = null
) {
    val platformActions = com.auralis.music.ui.components.LocalPlatformActions.current
    val coroutineScope = rememberCoroutineScope()
    var activeDialog by remember { mutableStateOf(initialDialog) }

    var editTitle by remember(playlist.title) { mutableStateOf(playlist.title) }
    var editDesc by remember(playlist.description) { mutableStateOf(playlist.description ?: "") }
    var editCoverUrl by remember(playlist.coverUrl) { mutableStateOf(playlist.coverUrl ?: "") }
    var selectedExportFormat by remember { mutableStateOf("CSV") }

    val pickCoverImage = platformActions.rememberImagePicker { picked -> editCoverUrl = picked }

    val isSmartPlaylistMenu = playlist.id.startsWith("smart_")
    val coverUrl = playlist.coverUrl ?: playlist.tracks.firstOrNull()?.thumbnail
    fun addPlaylistToQueue() {
        onAddToQueue?.invoke(playlist.tracks)
        platformActions.showMessage(str(Strings.added_x_tracks_to_queue, playlist.tracks.size))
    }
    fun sharePlaylist() {
        platformActions.shareText(
            subject = playlist.title,
            text = str(Strings.listen_to_x_on_auralis_music_x_songs_dow, playlist.title, playlist.tracks.size),
            chooserTitle = str(Strings.share_playlist)
        )
    }
    fun downloadPlaylist() {
        platformActions.downloadPlaylist(
            playlistId = playlist.id,
            playlistName = playlist.title,
            tracks = playlist.tracks, coverUrl = playlist.coverUrl
        )
    }

    // Long-pressed in liquid glass mode: a small glass menu pops from the playlist instead.
    val glassAnchor = com.auralis.music.ui.components.rememberContextMenuAnchor()
    if (activeDialog == null && glassAnchor != null) {
        val isAllDownloaded = playlist.tracks.isNotEmpty() && playlist.tracks.all { platformActions.isDownloaded(it.id) }
        val items = buildList {
            add(com.auralis.music.ui.components.GlassMenuItem(str(Strings.add_to_queue), Icons.Default.PlaylistAdd) { addPlaylistToQueue() })
            if (!isSmartPlaylistMenu) {
                add(com.auralis.music.ui.components.GlassMenuItem(str(Strings.edit), Icons.Default.Edit, dismisses = false) {
                    editTitle = playlist.title
                    editDesc = playlist.description ?: ""
                    editCoverUrl = playlist.coverUrl ?: ""
                    activeDialog = PlaylistDialogType.EDIT
                })
            }
            add(com.auralis.music.ui.components.GlassMenuItem(str(Strings.export_playlist), Icons.Default.Share, dismisses = false) {
                activeDialog = PlaylistDialogType.EXPORT
            })
            if (playlist.id != "smart_downloaded") {
                add(com.auralis.music.ui.components.GlassMenuItem(
                    if (isAllDownloaded) str(Strings.downloaded) else str(Strings.download_playlist),
                    if (isAllDownloaded) Icons.Default.DownloadDone else Icons.Default.Download,
                    tint = if (isAllDownloaded) Color(0xFF4CAF50) else null
                ) { downloadPlaylist() })
            }
            add(com.auralis.music.ui.components.GlassMenuItem(str(Strings.share), Icons.Default.Share) { sharePlaylist() })
            if (playlist.id == "smart_downloaded") {
                add(com.auralis.music.ui.components.GlassMenuItem(str(Strings.delete_all_downloads), Icons.Default.Delete, tint = Color(0xFFFF5252), dismisses = false) {
                    activeDialog = PlaylistDialogType.DELETE_ALL_DOWNLOADS
                })
            } else if (!isSmartPlaylistMenu || isRemovableMostPlayedPlaylist(playlist.id)) {
                add(com.auralis.music.ui.components.GlassMenuItem(str(Strings.delete), Icons.Default.Delete, tint = Color(0xFFFF5252), dismisses = false) {
                    activeDialog = PlaylistDialogType.DELETE
                })
            }
        }
        com.auralis.music.ui.components.GlassContextMenu(anchor = glassAnchor, items = items, onDismiss = onDismiss)
    } else if (activeDialog == null) com.auralis.music.ui.components.CoverSheetTheme {
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            containerColor = com.auralis.music.ui.components.CoverSheetFloor,
            contentColor = Color.White,
            dragHandle = null,
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
        ) {
            Box(modifier = Modifier.fillMaxWidth()) {
            // The playlist's own cover colours fill the sheet behind its content.
            Box(modifier = Modifier.matchParentSize()) {
                com.auralis.music.ui.components.CoverSheetBackground(coverUrl)
            }
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
                            .background(Color.White.copy(alpha = 0.35f))
                    )
                }

                // ── HEADER: PLAYLIST INFO ──
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ArtworkCard(
                        url = coverUrl,
                        modifier = Modifier
                            .size(54.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(10.dp)),
                        cornerRadius = 10.dp,
                        contentDescription = playlist.title
                    )

                    Spacer(modifier = Modifier.width(14.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = playlist.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = str(Strings.x_songs, playlist.tracks.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.65f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                HorizontalDivider(
                    color = Color.White.copy(alpha = 0.08f),
                    modifier = Modifier.padding(top = 8.dp, bottom = 14.dp)
                )

                val isSmartPlaylist = playlist.id.startsWith("smart_")

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // ── QUICK ACTIONS ROW: ADD TO QUEUE & SHARE PILLS ──
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Add to queue Pill
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(Color.White.copy(alpha = 0.08f))
                                .clickable {
                                    onDismiss()
                                    addPlaylistToQueue()
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PlaylistAdd,
                                    contentDescription = str(Strings.add_to_queue),
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = str(Strings.add_to_queue),
                                    color = Color.White,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 15.sp
                                )
                            }
                        }

                        // Share Pill
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(Color.White.copy(alpha = 0.08f))
                                .clickable {
                                    onDismiss()
                                    platformActions.shareText(
                                        subject = playlist.title,
                                        text = str(Strings.listen_to_x_on_auralis_music_x_songs_dow, playlist.title, playlist.tracks.size),
                                        chooserTitle = str(Strings.share_playlist)
                                    )
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Share,
                                    contentDescription = str(Strings.share),
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = str(Strings.share),
                                    color = Color.White,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 15.sp
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(2.dp))

                    // ── GROUP 1: EDIT & EXPORT ──
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color.White.copy(alpha = 0.08f))
                    ) {
                        if (!isSmartPlaylist) {
                            PlaylistActionRow(
                                icon = Icons.Default.Edit,
                                title = str(Strings.edit),
                                subtitle = str(Strings.edit_playlist_name_and_cover),
                                onClick = {
                                    editTitle = playlist.title
                                    editDesc = playlist.description ?: ""
                                    editCoverUrl = playlist.coverUrl ?: ""
                                    activeDialog = PlaylistDialogType.EDIT
                                }
                            )

                            HorizontalDivider(
                                color = Color.White.copy(alpha = 0.05f),
                                modifier = Modifier.padding(horizontal = 16.dp)
                            )
                        }

                        PlaylistActionRow(
                            icon = Icons.Default.Share,
                            title = str(Strings.export_playlist),
                            subtitle = str(Strings.export_tracks_to_file),
                            onClick = {
                                activeDialog = PlaylistDialogType.EXPORT
                            }
                        )
                    }

                    // ── GROUP 2: DOWNLOAD ──
                    if (playlist.id != "smart_downloaded") {
                        val isAllDownloaded = playlist.tracks.isNotEmpty() && playlist.tracks.all { platformActions.isDownloaded(it.id) }
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(Color.White.copy(alpha = 0.08f))
                        ) {
                            PlaylistActionRow(
                                icon = if (isAllDownloaded) Icons.Default.DownloadDone else Icons.Default.Download,
                                title = if (isAllDownloaded) str(Strings.downloaded) else str(Strings.download_playlist),
                                subtitle = if (isAllDownloaded) str(Strings.all_x_songs_are_available_offline, playlist.tracks.size) else str(Strings.download_all_songs_for_offline_playback),
                                iconTint = if (isAllDownloaded) Color(0xFF4CAF50) else Color.White.copy(alpha = 0.9f),
                                titleColor = if (isAllDownloaded) Color(0xFF4CAF50) else Color.White,
                                onClick = {
                                    onDismiss()
                                    platformActions.downloadPlaylist(
                                        playlistId = playlist.id,
                                        playlistName = playlist.title,
                                        tracks = playlist.tracks, coverUrl = playlist.coverUrl
                                    )
                                }
                            )
                        }
                    }

                    // ── GROUP 3: DELETE ──
                    if (playlist.id == "smart_downloaded") {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(Color.White.copy(alpha = 0.08f))
                        ) {
                            PlaylistActionRow(
                                icon = Icons.Default.Delete,
                                title = str(Strings.delete_all_downloads),
                                subtitle = str(Strings.remove_all_x_downloaded_songs_from_devic, playlist.tracks.size),
                                iconTint = Color(0xFFFF5252),
                                titleColor = Color(0xFFFF5252),
                                onClick = {
                                    activeDialog = PlaylistDialogType.DELETE_ALL_DOWNLOADS
                                }
                            )
                        }
                    } else if (!isSmartPlaylist || isRemovableMostPlayedPlaylist(playlist.id)) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(Color.White.copy(alpha = 0.08f))
                        ) {
                            PlaylistActionRow(
                                icon = Icons.Default.Delete,
                                title = str(Strings.delete),
                                subtitle = if (isSmartPlaylist) str(Strings.hide_generated_playlist_from_library)
                                    else str(Strings.remove_this_playlist_permanently),
                                iconTint = Color(0xFFFF5252),
                                titleColor = Color(0xFFFF5252),
                                onClick = {
                                    activeDialog = PlaylistDialogType.DELETE
                                }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(18.dp))
                }
            }
        }
        }
    }

    when (activeDialog) {
        PlaylistDialogType.EDIT -> {
            AlertDialog(
                onDismissRequest = {
                    activeDialog = null
                    onDismiss()
                },
                containerColor = CARD_DARK_BG,
                shape = RoundedCornerShape(24.dp),
                title = {
                    Text(
                        text = str(Strings.edit_playlist),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground,
                        fontSize = 20.sp
                    )
                },
                text = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        // Cover Photo Preview with Tap-to-Change badge
                        Box(
                            modifier = Modifier
                                .size(120.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(MaterialTheme.colorScheme.surface)
                                .border(1.5.dp, if (editCoverUrl.isNotBlank()) LIME_TEXT else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.2f), RoundedCornerShape(16.dp))
                                .clickable { pickCoverImage() },
                            contentAlignment = Alignment.Center
                        ) {
                            // What the page will show once saved (a reset cover falls back to the
                            // collage when the playlist has four different covers, not the first song's art).
                            PlaylistCoverArt(
                                playlist = playlist.copy(coverUrl = editCoverUrl.ifBlank { null }),
                                savedAlbums = emptyList(),
                                cornerRadius = 16.dp
                            )

                            // Camera overlay badge
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color.Black.copy(alpha = 0.40f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.PhotoCamera,
                                        contentDescription = str(Strings.change_photo),
                                        tint = Color.White,
                                        modifier = Modifier.size(28.dp)
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = str(Strings.change_photo_2),
                                        color = Color.White,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }

                        // Action buttons for Photo: Choose Photo or Reset
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(
                                onClick = { pickCoverImage() }
                            ) {
                                Icon(Icons.Default.AddPhotoAlternate, contentDescription = null, modifier = Modifier.size(16.dp), tint = LIME_TEXT)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(str(Strings.choose_photo), color = LIME_TEXT, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            }

                            if (editCoverUrl.isNotBlank()) {
                                TextButton(
                                    onClick = { editCoverUrl = "" }
                                ) {
                                    Text(str(Strings.reset_photo), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                                }
                            }
                        }

                        // Playlist Name TextField
                        OutlinedTextField(
                            value = editTitle,
                            onValueChange = { editTitle = it },
                            label = { Text(str(Strings.playlist_name), color = MaterialTheme.colorScheme.onSurfaceVariant) },
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = MaterialTheme.colorScheme.surface,
                                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                                focusedTextColor = MaterialTheme.colorScheme.onBackground,
                                unfocusedTextColor = MaterialTheme.colorScheme.onBackground,
                                focusedIndicatorColor = LIME_TEXT,
                                unfocusedIndicatorColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.15f)
                            ),
                            shape = RoundedCornerShape(12.dp),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        // Playlist Description TextField
                        OutlinedTextField(
                            value = editDesc,
                            onValueChange = { editDesc = it },
                            label = { Text(str(Strings.description_optional), color = MaterialTheme.colorScheme.onSurfaceVariant) },
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = MaterialTheme.colorScheme.surface,
                                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                                focusedTextColor = MaterialTheme.colorScheme.onBackground,
                                unfocusedTextColor = MaterialTheme.colorScheme.onBackground,
                                focusedIndicatorColor = LIME_TEXT,
                                unfocusedIndicatorColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.15f)
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (editTitle.isNotBlank()) {
                                onEditPlaylist?.invoke(playlist.id, editTitle, editDesc, editCoverUrl.ifBlank { null })
                                platformActions.showMessage(str(Strings.playlist_updated))
                            }
                            activeDialog = null
                            onDismiss()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = LIME_TEXT),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(str(Strings.save), color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        activeDialog = null
                        onDismiss()
                    }) {
                        Text(str(Strings.cancel), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            )
        }
        PlaylistDialogType.EXPORT -> {
            AlertDialog(
                onDismissRequest = {
                    activeDialog = null
                    onDismiss()
                },
                containerColor = CARD_DARK_BG,
                shape = RoundedCornerShape(24.dp),
                title = {
                    Text(
                        text = str(Strings.export_playlist),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground,
                        fontSize = 20.sp
                    )
                },
                text = {
                    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        // Radio Button 1: Export as CSV
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { selectedExportFormat = "CSV" }
                                .padding(vertical = 10.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (selectedExportFormat == "CSV") Icons.Default.RadioButtonChecked else Icons.Default.RadioButtonUnchecked,
                                contentDescription = null,
                                tint = if (selectedExportFormat == "CSV") LIME_TEXT else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(14.dp))
                            Text(
                                text = str(Strings.export_as_csv),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                        }

                        // Radio Button 2: Export as M3U
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { selectedExportFormat = "M3U" }
                                .padding(vertical = 10.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (selectedExportFormat == "M3U") Icons.Default.RadioButtonChecked else Icons.Default.RadioButtonUnchecked,
                                contentDescription = null,
                                tint = if (selectedExportFormat == "M3U") LIME_TEXT else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(14.dp))
                            Text(
                                text = str(Strings.export_as_m3u),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                        }
                    }
                },
                confirmButton = {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = {
                            activeDialog = null
                            onDismiss()
                        }) {
                            Text(str(Strings.cancel), color = LIME_TEXT, fontWeight = FontWeight.SemiBold)
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        TextButton(onClick = {
                            val cleanTitle = playlist.title.replace(Regex("[^a-zA-Z0-9_-]"), "_")
                            val fileName = if (selectedExportFormat == "CSV") "$cleanTitle.csv" else "$cleanTitle.m3u"
                            val content = if (selectedExportFormat == "CSV") {
                                buildString {
                                    appendLine("Title,Artist,Album,Duration")
                                    playlist.tracks.forEach { t ->
                                        val albumStr = (t.album ?: "").replace("\"", "\"\"")
                                        appendLine("\"${t.title.replace("\"", "\"\"")}\",\"${t.artist.replace("\"", "\"\"")}\",\"$albumStr\",${t.duration}")
                                    }
                                }
                            } else {
                                buildString {
                                    appendLine("#EXTM3U")
                                    appendLine("#PLAYLIST:${playlist.title}")
                                    playlist.tracks.forEach { t ->
                                        appendLine("#EXTINF:${t.duration},${t.artist} - ${t.title}")
                                        appendLine("https://music.youtube.com/watch?v=${t.id}")
                                    }
                                }
                            }

                            platformActions.showMessage(platformActions.saveTextFile(fileName, content))
                            activeDialog = null
                            onDismiss()
                        }) {
                            Text(str(Strings.save_to_documents), color = LIME_TEXT, fontWeight = FontWeight.SemiBold)
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        TextButton(onClick = {
                            val content = if (selectedExportFormat == "CSV") {
                                buildString {
                                    appendLine("Title,Artist,Album,Duration")
                                    playlist.tracks.forEach { t ->
                                        val albumStr = (t.album ?: "").replace("\"", "\"\"")
                                        appendLine("\"${t.title.replace("\"", "\"\"")}\",\"${t.artist.replace("\"", "\"\"")}\",\"$albumStr\",${t.duration}")
                                    }
                                }
                            } else {
                                buildString {
                                    appendLine("#EXTM3U")
                                    appendLine("#PLAYLIST:${playlist.title}")
                                    playlist.tracks.forEach { t ->
                                        appendLine("#EXTINF:${t.duration},${t.artist} - ${t.title}")
                                        appendLine("https://music.youtube.com/watch?v=${t.id}")
                                    }
                                }
                            }
                            platformActions.shareText(
                                subject = "Playlist Export: ${playlist.title}",
                                text = content,
                                chooserTitle = str(Strings.share_playlist_export)
                            )
                            activeDialog = null
                            onDismiss()
                        }) {
                            Text(str(Strings.share), color = LIME_TEXT, fontWeight = FontWeight.Bold)
                        }
                    }
                },
                dismissButton = {}
            )
        }
        PlaylistDialogType.DELETE -> {
            AlertDialog(
                onDismissRequest = {
                    activeDialog = null
                    onDismiss()
                },
                containerColor = CARD_DARK_BG,
                shape = RoundedCornerShape(24.dp),
                title = {
                    Text(
                        text = if (isRemovableMostPlayedPlaylist(playlist.id)) str(Strings.remove_playlist_from_library)
                            else str(Strings.delete_playlist),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground,
                        fontSize = 20.sp
                    )
                },
                text = {
                    Text(
                        text = if (isRemovableMostPlayedPlaylist(playlist.id))
                            str(Strings.hide_generated_playlist_confirmation, playlist.title)
                        else str(Strings.are_you_sure_you_want_to_delete_x, playlist.title),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 15.sp
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            activeDialog = null
                            onDismiss()
                            onDeletePlaylist?.invoke()
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFEF4444),
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            if (isRemovableMostPlayedPlaylist(playlist.id)) str(Strings.remove) else str(Strings.delete),
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            activeDialog = null
                            onDismiss()
                        },
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(str(Strings.cancel), color = LIME_TEXT, fontWeight = FontWeight.SemiBold)
                    }
                }
            )
        }
        PlaylistDialogType.DELETE_ALL_DOWNLOADS -> {
            AlertDialog(
                onDismissRequest = {
                    activeDialog = null
                    onDismiss()
                },
                containerColor = CARD_DARK_BG,
                shape = RoundedCornerShape(24.dp),
                title = {
                    Text(
                        text = str(Strings.delete_all_downloads_2),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground,
                        fontSize = 20.sp
                    )
                },
                text = {
                    Text(
                        text = str(Strings.are_you_sure_you_want_to_delete_all_x_do, playlist.tracks.size),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 15.sp
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            activeDialog = null
                            onDismiss()
                            onClearAllDownloads?.invoke()
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFEF4444),
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(str(Strings.delete_all), fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            activeDialog = null
                            onDismiss()
                        },
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(str(Strings.cancel), color = LIME_TEXT, fontWeight = FontWeight.SemiBold)
                    }
                }
            )
        }
        null -> {}
    }
}

// ============================================================================
// 🔘 PLAYLIST ACTION ROW (For Playlist Options Bottom Sheet)
// ============================================================================

@Composable
private fun PlaylistActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    iconTint: Color = Color.White.copy(alpha = 0.9f),
    titleColor: Color = Color.White,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
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
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 12.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

// ============================================================================
// 🎵 PLAYLIST TRACK ROW (Isolated Composable with Skip-table for 120fps scroll)
// ============================================================================

