package com.auralis.music.windows.library

import com.auralis.music.ui.components.Surface

import com.auralis.music.ui.components.Button
import com.auralis.music.ui.components.TextButton

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun CreatePlaylistDialog(youtubeAvailable: Boolean, accountName: String?, onDismiss: () -> Unit,
    onCreate: suspend (String, Boolean) -> Unit) {
    var name by remember { mutableStateOf("") }
    var remote by remember { mutableStateOf(youtubeAvailable) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    com.auralis.music.ui.components.GlassAlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text("New playlist") },
        text = {
            Column(Modifier.width(440.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedTextField(name, { name = it; error = null }, enabled = !busy, singleLine = true,
                    label = { Text("Playlist name") }, placeholder = { Text("Give it a name") }, modifier = Modifier.fillMaxWidth())
                Text("Save to", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    DestinationCard("YouTube Music", accountName ?: "Connect an account first", remote,
                        youtubeAvailable && !busy, Modifier.weight(1f), true) { remote = true }
                    DestinationCard("This device", "Saved in Auralis", !remote, !busy, Modifier.weight(1f), false) { remote = false }
                }
                Text(if (remote) "Created privately in your YouTube Music account, available on your other devices."
                    else "Stored on this computer in your Auralis library.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } },
        confirmButton = { Button(enabled = name.isNotBlank() && !busy && (!remote || youtubeAvailable), onClick = {
            busy = true; error = null
            scope.launch {
                try { onCreate(name.trim(), remote); onDismiss() }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { error = e.message ?: "Could not create the playlist. Check your library before trying again." }
                finally { busy = false }
            }
        }) { Text(if (busy) "Creating…" else "Create playlist") } })
}

@Composable
private fun DestinationCard(title: String, subtitle: String, selected: Boolean, enabled: Boolean,
    modifier: Modifier, cloud: Boolean, onClick: () -> Unit) {
    Surface(onClick = onClick, enabled = enabled, modifier = modifier, shape = RoundedCornerShape(16.dp),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(if (cloud) Icons.Outlined.Cloud else Icons.Outlined.Computer, null)
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
    }
}
