package com.auralis.music.windows.together

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun ListenTogetherPage(controller: DesktopTogether, onBack: () -> Unit) {
    val state by controller.state.collectAsState()
    var name by remember { mutableStateOf("Listener") }
    var code by remember { mutableStateOf("") }
    LazyColumn(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { TextButton(onClick = onBack) { Text("Back") } }
        item { com.auralis.music.ui.components.PageHeader("Listen Together", null) }
        item { Text("Listen in the same room as your friends on Auralis. Windows checks room updates every few seconds.") }
        state.message?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
        if (state.code.isEmpty()) {
            item { OutlinedTextField(value = name, onValueChange = { name = it.take(80) }, label = { Text("Your name") }, singleLine = true) }
            item { Button(onClick = { controller.create(name) }, enabled = !state.busy) { Text("Create room") } }
            item { HorizontalDivider() }
            item { OutlinedTextField(value = code, onValueChange = { code = it.uppercase().filter(Char::isLetterOrDigit).take(6) }, label = { Text("Room code") }, singleLine = true) }
            item { Button(onClick = { controller.join(code, name) }, enabled = !state.busy && code.length == 6) { Text("Join room") } }
        } else {
            item {
                Text("Room ${state.code}", style = MaterialTheme.typography.headlineMedium)
                val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
                TextButton(onClick = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(state.code)) }) { Text("Copy room code") }
            }
            item { Text(if (state.host) "You're the host. Play songs anywhere in Auralis to share them." else "Following the host. Playback and seeking will follow the room.") }
            if (state.track.isNotEmpty()) item { Text("Now playing: ${state.track}", style = MaterialTheme.typography.titleLarge) }
            item { Text("In this room", style = MaterialTheme.typography.titleMedium) }
            state.members.forEach { member -> item { Text(member) } }
            if (state.host) item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Allow guests to play, pause and seek")
                    Switch(checked = state.guestControls, onCheckedChange = controller::allowGuestControls)
                }
            }
            if (state.host || state.guestControls) item { Button(onClick = controller::togglePlayback) { Text("Play / pause for everyone") } }
            state.recommendations.filter { it.status == "pending" }.forEach { recommendation -> item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${recommendation.track.title} — ${recommendation.track.artist}")
                    Text("Recommended by ${recommendation.name}")
                    if (state.host) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = { controller.decide(recommendation, true) }) { Text("Add to queue") }
                        OutlinedButton(onClick = { controller.decide(recommendation, false) }) { Text("Decline") }
                    } else Text("Waiting for the host")
                }
            } }
            item { OutlinedButton(onClick = controller::leave, enabled = !state.busy) { Text(if (state.host) "End room" else "Leave room") } }
        }
        if (state.busy) item { CircularProgressIndicator() }
    }
}
