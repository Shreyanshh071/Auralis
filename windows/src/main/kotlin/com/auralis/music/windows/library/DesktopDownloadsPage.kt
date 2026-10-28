package com.auralis.music.windows.library

import com.auralis.music.ui.i18n.desktopText

import com.auralis.music.ui.components.IconButton
import com.auralis.music.ui.components.Button
import com.auralis.music.ui.components.TextButton
import com.auralis.music.ui.components.FilledTonalButton
import com.auralis.music.windows.frostedListSurface
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import com.auralis.music.ui.components.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.text.style.TextOverflow
import com.auralis.music.desktop.library.DesktopDownloads
import com.auralis.music.desktop.playback.DesktopPlaybackController

@Composable
internal fun DesktopDownloadsPage(playback: DesktopPlaybackController?, back: () -> Unit,
    downloads: DesktopDownloads = DesktopDownloads.instance, playlists: List<com.auralis.music.domain.model.Playlist> = emptyList()) {
    val entries by downloads.entries.collectAsState()
    val directory by downloads.rootDirectory.collectAsState()
    val scope = rememberCoroutineScope()
    var choosingDirectory by remember { mutableStateOf(false) }
    var isTransferring by remember { mutableStateOf(false) }
    var pendingDirectoryChange by remember { mutableStateOf<File?>(null) }
    var directoryMessage by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<String?>(null) }
    com.auralis.music.ui.components.PlatformBackHandler(enabled = selected != null) { selected = null }
    val groups = entries.groupBy { it.groupId }
    val externalFilesCount = remember(entries, directory) { downloads.countFilesOutsideCurrentRoot() }

    pendingDirectoryChange?.let { targetDir ->
        AlertDialog(
            onDismissRequest = { pendingDirectoryChange = null },
            title = { Text(desktopText("Transfer existing downloads?")) },
            text = {
                Text(desktopText("You have downloaded songs in your previous folder. Would you like to transfer them to \"%1\$s\" and remove them from the previous location?", targetDir.name))
            },
            confirmButton = {
                Button(
                    enabled = !isTransferring,
                    onClick = {
                        val dest = pendingDirectoryChange ?: return@Button
                        pendingDirectoryChange = null
                        scope.launch {
                            isTransferring = true
                            try {
                                val moved = withContext(Dispatchers.IO) {
                                    downloads.changeDirectory(dest, transferExisting = true)
                                }
                                directoryMessage = "Moved $moved ${if (moved == 1) "song" else "songs"} to ${dest.absolutePath} and removed from old location."
                            } catch (e: Exception) {
                                directoryMessage = "Failed to transfer songs: ${e.message}"
                            } finally {
                                isTransferring = false
                            }
                        }
                    }
                ) {
                    Icon(Icons.AutoMirrored.Outlined.DriveFileMove, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(desktopText("Transfer files"))
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = {
                            val dest = pendingDirectoryChange ?: return@TextButton
                            pendingDirectoryChange = null
                            scope.launch {
                                try {
                                    withContext(Dispatchers.IO) {
                                        downloads.changeDirectory(dest, transferExisting = false)
                                    }
                                    directoryMessage = "New downloads will use ${dest.absolutePath}. Existing downloads remain available in their original folders."
                                } catch (e: Exception) {
                                    directoryMessage = e.message ?: "Couldn't change download folder."
                                }
                            }
                        }
                    ) {
                        Text(desktopText("Keep in old folder"))
                    }
                    TextButton(onClick = { pendingDirectoryChange = null }) {
                        Text(desktopText("Cancel"))
                    }
                }
            }
        )
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        com.auralis.music.ui.components.PageHeader(
            if (selected == null) desktopText("Downloads") else groups[selected]?.firstOrNull()?.groupName ?: desktopText("Downloads"),
            { if (selected == null) back() else selected = null }
        )
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.55f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    Icons.Outlined.Folder,
                    contentDescription = desktopText("Save location"),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        desktopText("Save location"),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        directory.absolutePath,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                FilledTonalButton(
                    enabled = !choosingDirectory && !isTransferring,
                    onClick = {
                        choosingDirectory = true
                        scope.launch {
                            try {
                                val picked = withContext(Dispatchers.IO) {
                                    NativeFolderPicker.chooseFolder(directory, "Choose download folder")
                                }
                                if (picked != null) {
                                    val resolved = DesktopDownloads.resolveDownloadDirectory(picked)
                                    if (resolved.canonicalFile != directory.canonicalFile) {
                                        val hasCompletedDownloads = entries.any(downloads::available)
                                        if (hasCompletedDownloads) {
                                            pendingDirectoryChange = resolved
                                        } else {
                                            withContext(Dispatchers.IO) { downloads.changeDirectory(resolved) }
                                            directoryMessage = "New downloads will use ${resolved.absolutePath}."
                                        }
                                    }
                                }
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                directoryMessage = e.message ?: "Couldn't change the download folder."
                            } finally {
                                choosingDirectory = false
                            }
                        }
                    },
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.AutoMirrored.Outlined.DriveFileMove, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(desktopText(if (choosingDirectory) "Choosing…" else "Change folder"))
                }
            }
        }
        if (externalFilesCount > 0) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.22f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Box(
                        Modifier.size(38.dp).clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.AutoMirrored.Outlined.DriveFileMove,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(
                            text = if (externalFilesCount == 1) desktopText("1 song stored in previous folder")
                            else desktopText("%1\$d songs stored in previous folder", externalFilesCount),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = desktopText("Transfer them to %1\$s and remove them from the previous folder.", directory.name),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Button(
                        enabled = !isTransferring,
                        onClick = {
                            scope.launch {
                                isTransferring = true
                                try {
                                    val moved = withContext(Dispatchers.IO) {
                                        downloads.moveExistingDownloadsToCurrentRoot()
                                    }
                                    directoryMessage = if (moved > 0) {
                                        "Moved $moved ${if (moved == 1) "song" else "songs"} to ${directory.absolutePath} and removed from old location."
                                    } else {
                                        "No songs needed to be moved."
                                    }
                                } catch (e: Exception) {
                                    directoryMessage = "Failed to transfer files: ${e.message}"
                                } finally {
                                    isTransferring = false
                                }
                            }
                        },
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        if (isTransferring) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(desktopText("Transferring…"))
                        } else {
                            Icon(Icons.AutoMirrored.Outlined.DriveFileMove, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(desktopText("Transfer files"))
                        }
                    }
                }
            }
        }
        directoryMessage?.let {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Outlined.Info, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (entries.isEmpty()) {
            Text(desktopText("No downloads yet"), style = MaterialTheme.typography.titleLarge)
            Text(desktopText("Download a song from its menu, or download a playlist from Playlist options."))
        } else if (selected == null) {
            Text(
                "Downloaded Folders (${groups.size})",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(groups.keys.sortedBy { if (it == "individual") 0 else 1 }, key = { it }) { id ->
                    val values = groups.getValue(id)
                    val playlistCover = values.firstNotNullOfOrNull { it.coverUrl } ?: playlists.firstOrNull { it.id == id }?.coverUrl
                    val groupExternalCount = remember(values, directory) { downloads.countGroupFilesOutsideCurrentRoot(id) }
                    DownloadFolderCard(
                        id = id,
                        values = values,
                        playlistCover = playlistCover,
                        groupExternalCount = groupExternalCount,
                        directory = directory,
                        downloads = downloads,
                        onSelect = { selected = id },
                        onTransfer = {
                            scope.launch {
                                isTransferring = true
                                try {
                                    val moved = withContext(Dispatchers.IO) { downloads.moveGroupToCurrentRoot(id) }
                                    directoryMessage = "Transferred $moved ${if (moved == 1) "song" else "songs"} to ${directory.absolutePath}."
                                } catch (e: Exception) {
                                    directoryMessage = "Failed: ${e.message}"
                                } finally {
                                    isTransferring = false
                                }
                            }
                        },
                        onRetryFailed = {
                            values.filter { it.status == "FAILED" }.forEach { downloads.enqueue(listOf(it.track), it.groupId, it.groupName, it.coverUrl) }
                        },
                        onRemove = {
                            runCatching { downloads.removeGroup(id) }.onFailure { directoryMessage = it.message }
                        }
                    )
                }
            }
        } else {
            val values = groups[selected].orEmpty().sortedBy { it.index }
            val selectedGroupExternalCount = remember(values, directory) { downloads.countGroupFilesOutsideCurrentRoot(selected ?: "") }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    enabled = values.any(downloads::available),
                    onClick = {
                        val tracks = values.filter(downloads::available).map { it.track }
                        tracks.firstOrNull()?.let { playback?.playFrom(it, tracks, automatic = false) }
                    },
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Filled.PlayArrow, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(desktopText("Play downloaded"))
                }
                if (selectedGroupExternalCount > 0) {
                    FilledTonalButton(
                        enabled = !isTransferring,
                        onClick = {
                            val gid = selected ?: return@FilledTonalButton
                            scope.launch {
                                isTransferring = true
                                try {
                                    val count = withContext(Dispatchers.IO) { downloads.moveGroupToCurrentRoot(gid) }
                                    directoryMessage = "Transferred $count ${if (count == 1) "song" else "songs"} to ${directory.absolutePath}."
                                } catch (e: Exception) {
                                    directoryMessage = "Failed: ${e.message}"
                                } finally {
                                    isTransferring = false
                                }
                            }
                        },
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.AutoMirrored.Outlined.DriveFileMove, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (isTransferring) "Transferring…" else "Transfer to ${directory.name}")
                    }
                }
                if (values.any { it.status == "FAILED" }) {
                    FilledTonalButton(
                        onClick = {
                            values.filter { it.status == "FAILED" }.forEach { downloads.enqueue(listOf(it.track), it.groupId, it.groupName, it.coverUrl) }
                        },
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Outlined.Refresh, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(desktopText("Retry failed"))
                    }
                }
                TextButton(
                    onClick = { runCatching { selected?.let(downloads::removeGroup); selected = null }.onFailure { directoryMessage = it.message } }
                ) {
                    Text(desktopText("Remove folder downloads"))
                }
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(values, key = { it.key }) { e ->
                    val available = downloads.available(e)
                    DownloadSongCard(
                        entry = e,
                        available = available,
                        onPlay = {
                            playback?.playFrom(e.track, values.filter(downloads::available).map { it.track }, automatic = false)
                        },
                        onRetry = {
                            downloads.enqueue(listOf(e.track), e.groupId, e.groupName, e.coverUrl)
                        },
                        onRemove = {
                            runCatching { downloads.remove(e.key) }.onFailure { directoryMessage = it.message }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun DownloadFolderCard(
    id: String,
    values: List<DesktopDownloads.Entry>,
    playlistCover: String?,
    groupExternalCount: Int,
    directory: File,
    downloads: DesktopDownloads,
    onSelect: () -> Unit,
    onTransfer: () -> Unit,
    onRetryFailed: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interaction = remember(id) { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val hoverProgress by animateFloatAsState(
        targetValue = if (hovered) 1f else 0f,
        animationSpec = tween(160),
        label = "downloadFolderHover"
    )
    val shape = RoundedCornerShape(12.dp)
    var menu by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .frostedListSurface(shape, hoverProgress)
            .hoverable(interaction)
            .clickable(onClick = onSelect)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(52.dp)) {
                DownloadFolderArtwork(values, if (id == "individual") null else playlistCover, cornerRadius = 9.dp)
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(19.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.92f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Outlined.Folder,
                        contentDescription = null,
                        modifier = Modifier.size(12.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = values.first().groupName,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val saved = values.count(downloads::available)
                val busy = values.count { it.status in setOf("QUEUED", "DOWNLOADING") }
                val failed = values.count { it.status == "FAILED" }
                Text(
                    text = buildString {
                        append("$saved songs · Offline")
                        if (groupExternalCount > 0) append(" · In previous folder")
                        if (busy > 0) append(" · $busy downloading")
                        if (failed > 0) append(" · $failed failed")
                    },
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Box {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = if (hovered) 0.08f else 0.03f))
                        .border(1.dp, Color.White.copy(alpha = if (hovered) 0.18f else 0.06f), CircleShape)
                        .clickable { menu = true },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Outlined.MoreVert,
                        contentDescription = desktopText("Folder options"),
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                com.auralis.music.windows.GlassDropdownMenu(menu, onDismissRequest = { menu = false }) {
                    com.auralis.music.windows.GlassDropdownMenuItem(
                        text = { Text(desktopText("Open folder"), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingIcon = { Icon(Icons.Outlined.FolderOpen, null, Modifier.size(18.dp)) },
                        onClick = { menu = false; onSelect() }
                    )
                    if (groupExternalCount > 0) {
                        com.auralis.music.windows.GlassDropdownMenuItem(
                            text = { Text(desktopText("Transfer to %1\$s", directory.name), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            leadingIcon = { Icon(Icons.AutoMirrored.Outlined.DriveFileMove, null, Modifier.size(18.dp)) },
                            onClick = { menu = false; onTransfer() }
                        )
                    }
                    val failed = values.count { it.status == "FAILED" }
                    if (failed > 0) {
                        com.auralis.music.windows.GlassDropdownMenuItem(
                            text = { Text(desktopText("Retry failed downloads"), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            leadingIcon = { Icon(Icons.Outlined.Refresh, null, Modifier.size(18.dp)) },
                            onClick = { menu = false; onRetryFailed() }
                        )
                    }
                    com.auralis.music.windows.GlassDropdownMenuItem(
                        text = { Text(desktopText("Remove downloads"), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null, Modifier.size(18.dp)) },
                        onClick = { menu = false; onRemove() }
                    )
                }
            }
        }
    }
}

@Composable
private fun DownloadSongCard(
    entry: DesktopDownloads.Entry,
    available: Boolean,
    onPlay: () -> Unit,
    onRetry: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interaction = remember(entry.key) { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val hoverProgress by animateFloatAsState(
        targetValue = if (hovered) 1f else 0f,
        animationSpec = tween(160),
        label = "downloadSongHover"
    )
    val shape = RoundedCornerShape(10.dp)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .frostedListSurface(shape, hoverProgress)
            .hoverable(interaction)
            .clickable(enabled = available, onClick = onPlay)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                com.auralis.music.ui.components.ArtworkCard(
                    url = entry.track.thumbnail.takeIf { it.isNotBlank() } ?: entry.coverUrl,
                    fallbackTrack = entry.track,
                    modifier = Modifier.size(42.dp),
                    cornerRadius = 7.dp,
                    contentDescription = "Cover for ${entry.track.title}"
                )

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(1.dp)
                ) {
                    Text(
                        text = entry.track.title,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = entry.track.artist,
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Text(
                            text = if (available) "· Saved" else "· ${entry.error ?: entry.status.lowercase().replaceFirstChar { it.titlecase() }}",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                            color = if (available) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                            maxLines = 1
                        )
                    }
                }

                if (!available && entry.status == "FAILED") {
                    TextButton(
                        onClick = onRetry,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.height(30.dp)
                    ) {
                        Text(desktopText("Retry"), style = MaterialTheme.typography.labelMedium)
                    }
                }

                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = if (hovered) 0.08f else 0.02f))
                        .border(1.dp, Color.White.copy(alpha = if (hovered) 0.16f else 0.04f), CircleShape)
                        .clickable(onClick = onRemove),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Outlined.DeleteOutline,
                        contentDescription = desktopText("Remove download"),
                        modifier = Modifier.size(15.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (entry.status == "DOWNLOADING") {
                if (entry.total > 0) {
                    LinearProgressIndicator(
                        progress = { (entry.bytes.toFloat() / entry.total).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(2.dp)
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().height(2.dp)
                    )
                }
            }
        }
    }
}

@Composable
internal fun DownloadFolderArtwork(entries: List<DesktopDownloads.Entry>, cover: String?, cornerRadius: Dp = 9.dp) {
    Box(Modifier.fillMaxSize().clip(RoundedCornerShape(cornerRadius))) {
        if (!cover.isNullOrBlank()) com.auralis.music.ui.components.ArtworkCard(
            url = cover, fallbackTrack = entries.firstOrNull()?.track, modifier = Modifier.fillMaxSize(),
            cornerRadius = cornerRadius, contentDescription = desktopText("Playlist cover"))
        else com.auralis.music.ui.library.PlaylistCoverArt(
            com.auralis.music.domain.model.Playlist("download-collage", "Downloaded songs", tracks = entries.sortedBy { it.index }.map { it.track }),
            emptyList(), cornerRadius)
    }
}
