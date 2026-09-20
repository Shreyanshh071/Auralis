package com.auralis.music.ui.library

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auralis.music.data.download.AuralisDownloadManager
import com.auralis.music.data.download.PlaylistDownloadCoordinator
import com.auralis.music.data.download.PlaylistDownloadJobEntity
import com.auralis.music.domain.model.Playlist
import com.auralis.music.domain.model.Track
import com.auralis.music.ui.components.ArtworkCard
import com.auralis.music.ui.components.tactileBounce
import com.auralis.music.ui.theme.dynamicBackground
import com.auralis.music.ui.theme.dynamicOnBackground
import com.auralis.music.ui.theme.dynamicOnSurface
import com.auralis.music.ui.theme.dynamicPrimary
import com.auralis.music.ui.theme.dynamicSurface

private data class DownloadFolderItem(
    val id: String,
    val title: String,
    val subtitle: String,
    val totalCount: Int,
    val downloadedTracks: List<Track>,
    val status: String,
    val completedCount: Int,
    val failedCount: Int,
    val jobId: String? = null,
    val coverUrl: String? = null,
    val isAllSongs: Boolean = false,
    val isIndividualSongs: Boolean = false
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadedHubView(
    downloadedTracks: List<Track>,
    downloadedJobs: List<PlaylistDownloadJobEntity>,
    userPlaylists: List<Playlist>,
    currentTrackId: String?,
    isPlaying: Boolean,
    onBack: () -> Unit,
    onOpenFolder: (Playlist) -> Unit,
    onPlayTracks: (Track, List<Track>) -> Unit,
    onDeleteJob: (String) -> Unit,
    onRetryJob: (String) -> Unit,
    onClearAllDownloads: () -> Unit,
    modifier: Modifier = Modifier
) {
    var searchQuery by remember { mutableStateOf("") }
    var isSearchActive by remember { mutableStateOf(false) }
    var showClearDialog by remember { mutableStateOf(false) }
    var jobToDelete by remember { mutableStateOf<String?>(null) }

    // Build the list of folders
    val folders = remember(downloadedTracks, downloadedJobs, userPlaylists) {
        val list = mutableListOf<DownloadFolderItem>()
        val accountedTrackIds = mutableSetOf<String>()

        // 1. Jobs from PlaylistDownloadCoordinator
        for (job in downloadedJobs) {
            val allTracks = PlaylistDownloadCoordinator.tracks(job)
            val downloadedForJob = allTracks.filter { AuralisDownloadManager.isDownloaded(it.id) }
            downloadedForJob.forEach { accountedTrackIds.add(it.id) }

            if (job.status == "DOWNLOADING" || downloadedForJob.isNotEmpty() || job.completedCount > 0) {
                list.add(
                    DownloadFolderItem(
                        id = job.jobId,
                        title = job.playlistName,
                        subtitle = when (job.status) {
                            "DOWNLOADING" -> "Downloading · ${job.completedCount} of ${allTracks.size} songs"
                            "PARTIAL_FAILURE" -> "${downloadedForJob.size} of ${allTracks.size} downloaded · ${job.failedCount} failed"
                            "FAILED" -> "Failed to download · ${job.failedCount} errors"
                            else -> "${downloadedForJob.size} songs • Offline"
                        },
                        totalCount = allTracks.size,
                        downloadedTracks = downloadedForJob,
                        status = job.status,
                        completedCount = job.completedCount,
                        failedCount = job.failedCount,
                        jobId = job.jobId,
                        coverUrl = downloadedForJob.firstOrNull { !it.thumbnail.isNullOrBlank() }?.thumbnail
                            ?: allTracks.firstOrNull { !it.thumbnail.isNullOrBlank() }?.thumbnail
                    )
                )
            }
        }

        // 2. Individual downloads (tracks not belonging to any downloaded playlist folder)
        val individualTracks = downloadedTracks.filter { it.id !in accountedTrackIds }
        if (individualTracks.isNotEmpty()) {
            list.add(
                DownloadFolderItem(
                    id = "individual_downloads",
                    title = "Individual Songs",
                    subtitle = "${individualTracks.size} songs downloaded directly",
                    totalCount = individualTracks.size,
                    downloadedTracks = individualTracks,
                    status = "COMPLETE",
                    completedCount = individualTracks.size,
                    failedCount = 0,
                    jobId = null,
                    coverUrl = individualTracks.firstOrNull { !it.thumbnail.isNullOrBlank() }?.thumbnail,
                    isIndividualSongs = true
                )
            )
        }

        list
    }

    val filteredFolders = remember(folders, searchQuery) {
        if (searchQuery.isBlank()) folders
        else folders.filter {
            it.title.contains(searchQuery, ignoreCase = true) ||
                it.downloadedTracks.any { track ->
                    track.title.contains(searchQuery, ignoreCase = true) ||
                        track.artist.contains(searchQuery, ignoreCase = true)
                }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.dynamicBackground)
            .statusBarsPadding()
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ── TOP APP BAR ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack, modifier = Modifier.tactileBounce()) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = MaterialTheme.dynamicOnBackground
                    )
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 4.dp)
                ) {
                    Text(
                        text = "Downloaded",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.dynamicOnBackground
                    )
                    Text(
                        text = "${downloadedTracks.size} offline songs · ${folders.size} folders",
                        fontSize = 12.sp,
                        color = MaterialTheme.dynamicOnBackground.copy(alpha = 0.6f)
                    )
                }

                // Search Toggle
                IconButton(onClick = {
                    isSearchActive = !isSearchActive
                    if (!isSearchActive) searchQuery = ""
                }) {
                    Icon(
                        imageVector = if (isSearchActive) Icons.Default.Close else Icons.Default.Search,
                        contentDescription = "Search",
                        tint = MaterialTheme.dynamicOnBackground
                    )
                }

                // Overflow Menu (Clear All)
                var showMenu by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "Options",
                            tint = MaterialTheme.dynamicOnBackground
                        )
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Clear all downloads", color = MaterialTheme.colorScheme.error) },
                            leadingIcon = {
                                Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                            },
                            onClick = {
                                showMenu = false
                                showClearDialog = true
                            }
                        )
                    }
                }
            }

            // ── SEARCH BAR (if active) ──
            if (isSearchActive) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search folders or tracks...", color = MaterialTheme.dynamicOnSurface.copy(alpha = 0.6f)) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.dynamicPrimary,
                        unfocusedBorderColor = MaterialTheme.dynamicPrimary.copy(alpha = 0.3f),
                        focusedContainerColor = MaterialTheme.dynamicSurface,
                        unfocusedContainerColor = MaterialTheme.dynamicSurface,
                        focusedTextColor = MaterialTheme.dynamicOnSurface,
                        unfocusedTextColor = MaterialTheme.dynamicOnSurface
                    ),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                )
            }

            // ── EMPTY STATE ──
            if (downloadedTracks.isEmpty() && downloadedJobs.none { it.status == "DOWNLOADING" }) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CloudDownload,
                            contentDescription = null,
                            tint = MaterialTheme.dynamicPrimary.copy(alpha = 0.7f),
                            modifier = Modifier.size(64.dp)
                        )
                        Text(
                            text = "No offline downloads yet",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.dynamicOnBackground
                        )
                        Text(
                            text = "Download your favorite playlists and songs to listen offline anytime.",
                            fontSize = 13.sp,
                            color = MaterialTheme.dynamicOnBackground.copy(alpha = 0.6f),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 120.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    // ── QUICK PLAY BANNER: ALL DOWNLOADED SONGS ──
                    if (searchQuery.isBlank() && downloadedTracks.isNotEmpty()) {
                        item(key = "all_downloaded_banner") {
                            AllDownloadedSongsCard(
                                totalTracks = downloadedTracks.size,
                                onPlayAll = {
                                    downloadedTracks.firstOrNull()?.let { first ->
                                        onPlayTracks(first, downloadedTracks)
                                    }
                                },
                                onShuffle = {
                                    val shuffled = downloadedTracks.shuffled()
                                    shuffled.firstOrNull()?.let { first ->
                                        onPlayTracks(first, shuffled)
                                    }
                                },
                                onClick = {
                                    onOpenFolder(
                                        Playlist(
                                            id = "smart_downloaded_all",
                                            title = "All Downloaded Songs",
                                            description = "${downloadedTracks.size} offline songs",
                                            coverUrl = downloadedTracks.firstOrNull()?.thumbnail,
                                            tracks = downloadedTracks
                                        )
                                    )
                                }
                            )
                        }
                    }

                    // ── SECTION HEADER: FOLDERS & PLAYLISTS ──
                    if (filteredFolders.isNotEmpty()) {
                        item(key = "folders_header") {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp, bottom = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Folder,
                                    contentDescription = null,
                                    tint = MaterialTheme.dynamicPrimary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Downloaded Folders (${filteredFolders.size})",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.dynamicOnBackground.copy(alpha = 0.85f)
                                )
                            }
                        }

                        // ── FOLDER ITEMS ──
                        items(filteredFolders, key = { it.id }) { folder ->
                            FolderCardItem(
                                folder = folder,
                                onClick = {
                                    onOpenFolder(
                                        Playlist(
                                            id = "smart_downloaded_folder_${folder.id}",
                                            title = folder.title,
                                            description = "${folder.downloadedTracks.size} of ${folder.totalCount} offline songs",
                                            coverUrl = folder.coverUrl,
                                            tracks = folder.downloadedTracks
                                        )
                                    )
                                },
                                onPlayAll = {
                                    folder.downloadedTracks.firstOrNull()?.let { first ->
                                        onPlayTracks(first, folder.downloadedTracks)
                                    }
                                },
                                onShuffle = {
                                    val shuffled = folder.downloadedTracks.shuffled()
                                    shuffled.firstOrNull()?.let { first ->
                                        onPlayTracks(first, shuffled)
                                    }
                                },
                                onRetry = {
                                    folder.jobId?.let { onRetryJob(it) }
                                },
                                onDelete = {
                                    folder.jobId?.let { jobToDelete = it }
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    // ── CLEAR ALL DOWNLOADS DIALOG ──
    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("Clear All Downloads?") },
            text = { Text("This will remove all downloaded offline tracks and folders from your device.") },
            confirmButton = {
                Button(
                    onClick = {
                        showClearDialog = false
                        onClearAllDownloads()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Clear All")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // ── DELETE SINGLE PLAYLIST FOLDER DIALOG ──
    jobToDelete?.let { jobId ->
        AlertDialog(
            onDismissRequest = { jobToDelete = null },
            title = { Text("Delete Playlist Downloads?") },
            text = { Text("Remove downloaded tracks for this playlist from your device?") },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteJob(jobId)
                        jobToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { jobToDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun AllDownloadedSongsCard(
    totalTracks: Int,
    onPlayAll: () -> Unit,
    onShuffle: () -> Unit,
    onClick: () -> Unit
) {
    val gradientBrush = Brush.horizontalGradient(
        colors = listOf(
            MaterialTheme.dynamicPrimary.copy(alpha = 0.22f),
            MaterialTheme.dynamicSurface.copy(alpha = 0.85f)
        )
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, MaterialTheme.dynamicPrimary.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
            .clickable { onClick() },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.dynamicSurface)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(gradientBrush)
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.dynamicPrimary.copy(alpha = 0.25f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.CloudDone,
                        contentDescription = null,
                        tint = MaterialTheme.dynamicPrimary,
                        modifier = Modifier.size(28.dp)
                    )
                }

                Spacer(modifier = Modifier.width(14.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "All Downloaded Songs",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.dynamicOnBackground
                    )
                    Text(
                        text = "$totalTracks offline songs available",
                        fontSize = 12.sp,
                        color = MaterialTheme.dynamicOnBackground.copy(alpha = 0.65f)
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    IconButton(
                        onClick = onShuffle,
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.dynamicPrimary.copy(alpha = 0.15f))
                    ) {
                        Icon(
                            imageVector = Icons.Default.Shuffle,
                            contentDescription = "Shuffle",
                            tint = MaterialTheme.dynamicPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    IconButton(
                        onClick = onPlayAll,
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.dynamicPrimary)
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = "Play all",
                            tint = MaterialTheme.dynamicSurface,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FolderCardItem(
    folder: DownloadFolderItem,
    onClick: () -> Unit,
    onPlayAll: () -> Unit,
    onShuffle: () -> Unit,
    onRetry: () -> Unit,
    onDelete: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    val isDownloading = folder.status == "DOWNLOADING"
    val isFailedOrPartial = folder.status in setOf("PARTIAL_FAILURE", "FAILED")

    val infiniteTransition = rememberInfiniteTransition(label = "downloadingSpin")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )

    val gradientBrush = Brush.horizontalGradient(
        colors = listOf(
            MaterialTheme.dynamicPrimary.copy(alpha = 0.12f),
            MaterialTheme.dynamicSurface.copy(alpha = 0.85f)
        )
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .border(
                width = 1.dp,
                color = if (isDownloading) MaterialTheme.dynamicPrimary.copy(alpha = 0.55f)
                else MaterialTheme.dynamicPrimary.copy(alpha = 0.20f),
                shape = RoundedCornerShape(14.dp)
            )
            .clickable { onClick() },
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.dynamicSurface)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(gradientBrush)
                .padding(12.dp)
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // ── FOLDER ARTWORK / ICON ──
                    Box(
                        modifier = Modifier
                            .size(54.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.dynamicPrimary.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        if (!folder.coverUrl.isNullOrBlank()) {
                            ArtworkCard(
                                url = folder.coverUrl,
                                contentDescription = folder.title,
                                modifier = Modifier.fillMaxSize()
                            )
                            // Folder Badge Overlay
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .size(20.dp)
                                    .clip(RoundedCornerShape(topStart = 6.dp))
                                    .background(MaterialTheme.dynamicSurface.copy(alpha = 0.90f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Folder,
                                    contentDescription = null,
                                    tint = MaterialTheme.dynamicPrimary,
                                    modifier = Modifier.size(13.dp)
                                )
                            }
                        } else {
                            Icon(
                                imageVector = if (folder.isIndividualSongs) Icons.Default.MusicNote else Icons.Default.Folder,
                                contentDescription = null,
                                tint = MaterialTheme.dynamicPrimary,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    // ── FOLDER TITLE & SUBTITLE ──
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = folder.title,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.dynamicOnBackground,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = folder.subtitle,
                            fontSize = 12.sp,
                            color = if (isFailedOrPartial) MaterialTheme.colorScheme.error
                            else if (isDownloading) MaterialTheme.dynamicPrimary
                            else MaterialTheme.dynamicOnBackground.copy(alpha = 0.65f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    // ── STATUS INDICATOR OR RETRY ──
                    if (isDownloading) {
                        Icon(
                            imageVector = Icons.Default.Sync,
                            contentDescription = "Downloading",
                            tint = MaterialTheme.dynamicPrimary,
                            modifier = Modifier
                                .size(22.dp)
                                .rotate(rotation)
                        )
                    } else if (isFailedOrPartial && folder.jobId != null) {
                        IconButton(onClick = onRetry, modifier = Modifier.size(32.dp)) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Retry",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    // ── OVERFLOW MENU ──
                    Box {
                        IconButton(onClick = { showMenu = true }, modifier = Modifier.size(32.dp)) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = "Folder options",
                                tint = MaterialTheme.dynamicOnBackground.copy(alpha = 0.7f),
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Play all") },
                                leadingIcon = { Icon(Icons.Default.PlayArrow, contentDescription = null) },
                                onClick = {
                                    showMenu = false
                                    onPlayAll()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Shuffle") },
                                leadingIcon = { Icon(Icons.Default.Shuffle, contentDescription = null) },
                                onClick = {
                                    showMenu = false
                                    onShuffle()
                                }
                            )
                            if (isFailedOrPartial && folder.jobId != null) {
                                DropdownMenuItem(
                                    text = { Text("Retry download") },
                                    leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null) },
                                    onClick = {
                                        showMenu = false
                                        onRetry()
                                    }
                                )
                            }
                            if (folder.jobId != null) {
                                DropdownMenuItem(
                                    text = { Text("Delete downloads", color = MaterialTheme.colorScheme.error) },
                                    leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                                    onClick = {
                                        showMenu = false
                                        onDelete()
                                    }
                                )
                            }
                        }
                    }
                }

                // ── LIVE PROGRESS BAR (if currently downloading) ──
                if (isDownloading && folder.totalCount > 0) {
                    Spacer(modifier = Modifier.height(8.dp))
                    val progress = (folder.completedCount.toFloat() / folder.totalCount.toFloat()).coerceIn(0.02f, 1f)
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp)),
                        color = MaterialTheme.dynamicPrimary,
                        trackColor = MaterialTheme.dynamicPrimary.copy(alpha = 0.15f)
                    )
                }
            }
        }
    }
}
