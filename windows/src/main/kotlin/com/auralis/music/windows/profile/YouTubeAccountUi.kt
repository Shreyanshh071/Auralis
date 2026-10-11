package com.auralis.music.windows.profile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.unit.IntOffset
import coil3.compose.AsyncImage
import com.auralis.music.domain.model.PlaylistResult

@Composable
internal fun YouTubeAccountAvatar(url: String?, modifier: Modifier = Modifier.size(28.dp)) {
    Box(modifier.clip(CircleShape), contentAlignment = Alignment.Center) {
        Icon(Icons.Outlined.AccountCircle, "Account", Modifier.fillMaxSize())
        if (!url.isNullOrBlank()) AsyncImage(url, "YouTube account photo", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}

@Composable
internal fun YouTubeAccountMenu(state: YouTubeLibraryState, onSelect: (String) -> Unit,
    onRemove: (String) -> Unit, onAdd: () -> Unit, onSettings: () -> Unit, onRefresh: () -> Unit, onDismiss: () -> Unit) {
    var manage by remember { mutableStateOf(false) }
    Popup(alignment = Alignment.TopEnd, offset = IntOffset(0, 36), onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true)) {
        SignInGlassSurface {
            Column(Modifier.width(380.dp).heightIn(max = 600.dp).verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (manage) "Manage accounts" else "Switch account", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, "Close") }
                }
                state.accounts.forEach { item ->
                    Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable { onSelect(item.id) }.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        YouTubeAccountAvatar(item.identity.avatar, Modifier.size(40.dp))
                        Column(Modifier.weight(1f)) {
                            Text(item.identity.name, fontWeight = FontWeight.SemiBold)
                            Text(item.identity.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (manage) IconButton(onClick = { onRemove(item.id) }) { Icon(Icons.Outlined.DeleteOutline, "Remove ${item.identity.name}") }
                        else if (item.id == state.activeId) Icon(Icons.Outlined.Check, "Selected account")
                    }
                }
                if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = onRefresh) { Text("Try again") }
                }
                HorizontalDivider()
                TextButton(onClick = onAdd, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.Add, null); Spacer(Modifier.width(12.dp)); Text("Add account") }
                TextButton(onClick = { manage = !manage }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.ManageAccounts, null); Spacer(Modifier.width(12.dp)); Text(if (manage) "Back to accounts" else "Manage accounts") }
                TextButton(onClick = onSettings, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.Settings, null); Spacer(Modifier.width(12.dp)); Text("Settings") }
            }
        }
    }
}

@Composable
internal fun YouTubeSidebarPlaylists(state: YouTubeLibraryState, collapsed: Boolean,
    onOpen: (PlaylistResult) -> Unit, onRetry: () -> Unit) {
    HorizontalDivider(Modifier.padding(vertical = 10.dp))
    if (!collapsed) Text("YouTube Music", Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 8.dp))
    state.playlists.forEach { playlist ->
        Row(Modifier.fillMaxWidth().height(36.dp).clip(MaterialTheme.shapes.small).clickable { onOpen(playlist) }
            .padding(horizontal = if (collapsed) 8.dp else 12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(if (playlist.id == "VLLM") Icons.Outlined.FavoriteBorder else Icons.Outlined.QueueMusic,
                playlist.title, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!collapsed) Text(playlist.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium)
        }
    }
    if (!collapsed && state.error != null) {
        Text(state.error, Modifier.padding(10.dp), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = onRetry) { Text("Try again") }
    }
    if (!collapsed && !state.loading && state.error == null && state.playlists.isEmpty()) {
        Text("No YouTube playlists yet", Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
    }
}
