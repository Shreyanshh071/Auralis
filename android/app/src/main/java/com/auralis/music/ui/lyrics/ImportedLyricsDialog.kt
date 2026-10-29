package com.auralis.music.ui.lyrics

import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.auralis.music.ui.glass.*
import com.auralis.music.ui.theme.dynamicSurface
import com.auralis.music.ui.theme.dynamicOnSurface
import com.auralis.music.ui.theme.dynamicPrimary
import com.auralis.music.ui.player.LocalPlayerModalVisualState
import coil.compose.AsyncImage
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.auralis.music.data.local.ImportedLyricsStore
import com.auralis.music.domain.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportedLyricsDialog(track: Track? = null, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val revision by ImportedLyricsStore.changes.collectAsState()
    var entries by remember { mutableStateOf(emptyList<ImportedLyricsStore.Entry>()) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var search by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf<ImportedLyricsStore.Entry?>(null) }
    var removal by remember { mutableStateOf<ImportedLyricsStore.Entry?>(null) }
    LaunchedEffect(revision) { entries = withContext(Dispatchers.IO) { ImportedLyricsStore.entries() } }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val target = pending
        if (uri != null && target != null) scope.launch {
            busy = true
            message = try {
                withContext(Dispatchers.IO) {
                    val filename = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                        if (it.moveToFirst()) it.getString(0) else null
                    } ?: "lyrics.ttml"
                    val bytes = requireNotNull(context.contentResolver.openInputStream(uri)) { "Cannot open file" }.use {
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = it.read(buffer)
                            if (count < 0) break
                            require(output.size() + count <= 2 * 1024 * 1024) { "TTML file must be smaller than 2 MB" }
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    }
                    val resolved = track?.takeIf { it.id == target.id }?.let {
                        com.auralis.music.data.network.AudioStreamResolver.getMatchedVideoId(it.id)
                    }
                    ImportedLyricsStore.save(target.copy(filename = filename), bytes.toString(Charsets.UTF_8), listOfNotNull(resolved))
                }
                "Lyrics saved. They will be used whenever this song plays."
            } catch (e: Exception) { e.message ?: "Could not import TTML" }
            busy = false
        }
    }
    fun import(entry: ImportedLyricsStore.Entry) {
        pending = entry
        picker.launch(arrayOf("*/*"))
    }
    val glass = LocalLiquidGlass.current
    val pageView = LocalView.current
    val modalVisual = LocalPlayerModalVisualState.current
    DisposableEffect(modalVisual) {
        modalVisual?.opened()
        onDispose { modalVisual?.closed() }
    }
    val foreground = if (glass != null) glassContentColor(glass.isDark) else MaterialTheme.dynamicOnSurface
    val accent = if (glass != null) foreground else MaterialTheme.dynamicPrimary
    val secondary = foreground.copy(alpha = .65f)
    val tile = foreground.copy(alpha = if (glass != null) .07f else .045f)
    val shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)
    val maxHeight = (LocalConfiguration.current.screenHeightDp * .85f).dp
    val shown = entries.filter { entry ->
        if (track != null) entry.id == track.id
        else "${entry.title} ${entry.artist} ${entry.filename}".contains(search, true)
    }
    ModalBottomSheet(
        onDismissRequest = { if (!busy) onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = if (glass != null) Color.Transparent else MaterialTheme.dynamicSurface,
        contentColor = foreground, shape = shape, dragHandle = null
    ) {
        Box(Modifier.fillMaxWidth().heightIn(max = maxHeight).clip(shape).testTag("custom-lyrics-sheet")) {
            if (glass != null) Box(Modifier.matchParentSize().graphicsLayer()
                .liquidGlassDialog(glass, shape, pageView))
            Column(Modifier.fillMaxWidth().graphicsLayer().navigationBarsPadding().padding(horizontal = 24.dp)) {
                Box(Modifier.align(Alignment.CenterHorizontally).padding(top = 12.dp, bottom = 20.dp)
                    .size(36.dp, 4.dp).clip(RoundedCornerShape(50)).background(foreground.copy(alpha = .25f)))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(if (track == null) "Imported lyrics" else "Custom lyrics",
                            style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text(if (track == null) "Your saved lyric files" else "Your words. Your timing.",
                            style = MaterialTheme.typography.bodyMedium, color = secondary,
                            modifier = Modifier.padding(top = 4.dp))
                    }
                    IconButton(enabled = !busy, onClick = onDismiss,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(tile)) {
                        Icon(Icons.Rounded.Close, contentDescription = "Close")
                    }
                }
                Spacer(Modifier.height(24.dp))
                if (track != null) {
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(tile).padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        AsyncImage(model = track.thumbnail, contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)).background(tile))
                        Column(Modifier.weight(1f).padding(start = 14.dp)) {
                            Text(track.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(track.artist, color = secondary, style = MaterialTheme.typography.bodySmall,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    OutlinedCard(onClick = { import(ImportedLyricsStore.Entry(track.id, track.title, track.artist, "lyrics.ttml")) },
                        enabled = !busy, shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.outlinedCardColors(containerColor = tile, contentColor = foreground),
                        border = BorderStroke(1.dp, foreground.copy(alpha = .12f))) {
                        Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(48.dp).clip(RoundedCornerShape(16.dp)).background(accent.copy(alpha = .12f)),
                                contentAlignment = Alignment.Center) {
                                Icon(Icons.Rounded.UploadFile, null, tint = accent, modifier = Modifier.size(26.dp))
                            }
                            Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                                Text(if (shown.isEmpty()) "Import lyrics" else "Replace lyrics", fontWeight = FontWeight.SemiBold)
                                Text("Choose a TTML file", color = secondary, style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(top = 4.dp))
                            }
                            Icon(Icons.Rounded.ChevronRight, null, tint = secondary)
                        }
                    }
                    Row(Modifier.padding(horizontal = 4.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.CheckCircle, null, tint = secondary, modifier = Modifier.size(16.dp))
                        Text("Saved offline. Used automatically for this song.", color = secondary,
                            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 8.dp))
                    }
                } else {
                    OutlinedTextField(value = search, onValueChange = { search = it },
                        placeholder = { Text("Search songs or files") }, leadingIcon = { Icon(Icons.Rounded.Search, null) },
                        shape = RoundedCornerShape(20.dp), singleLine = true, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(16.dp))
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(bottom = 12.dp), color = accent)
                message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = foreground,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(tile).padding(16.dp)) }
                if (shown.isNotEmpty()) {
                    Text("SAVED LYRICS", style = MaterialTheme.typography.labelSmall, color = secondary,
                        modifier = Modifier.padding(top = 8.dp, bottom = 12.dp))
                } else if (track == null) {
                    Column(Modifier.fillMaxWidth().padding(vertical = 36.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Rounded.Lyrics, null, tint = secondary, modifier = Modifier.size(36.dp))
                        Text("Your lyric collection starts here", fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 16.dp))
                        Text("Import a file from a song's three-dot menu.", color = secondary,
                            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                    }
                }
                LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(shown, key = { it.id }) { entry ->
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(tile).padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Description, null, tint = secondary, modifier = Modifier.size(24.dp))
                            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                Text(if (track == null) entry.title else entry.filename, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                                Text(if (track == null) entry.artist else "Active for this song", color = secondary,
                                    style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            if (track == null) IconButton(enabled = !busy, onClick = { import(entry) }) {
                                Icon(Icons.Rounded.Edit, "Replace lyrics")
                            }
                            IconButton(enabled = !busy, onClick = { removal = entry }) {
                                Icon(Icons.Rounded.DeleteOutline, "Remove lyrics", tint = secondary)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
    removal?.let { entry ->
        AlertDialog(onDismissRequest = { removal = null },
            modifier = if (glass != null) Modifier.liquidGlassDialog(glass, RoundedCornerShape(28.dp), pageView) else Modifier,
            containerColor = if (glass != null) Color.Transparent else MaterialTheme.dynamicSurface,
            titleContentColor = foreground, textContentColor = foreground,
            title = { Text("Remove custom lyrics?") },
            text = { Text("${entry.title} will use online lyrics again.") },
            confirmButton = { TextButton(onClick = {
                removal = null
                scope.launch {
                    busy = true
                    message = try { withContext(Dispatchers.IO) { ImportedLyricsStore.remove(entry.id) }; "Custom lyrics removed." }
                    catch (e: Exception) { e.message ?: "Could not remove lyrics" }
                    busy = false
                }
            }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { removal = null }) { Text("Cancel") } })
    }
}
