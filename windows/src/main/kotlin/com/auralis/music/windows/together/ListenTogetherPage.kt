package com.auralis.music.windows.together

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
internal fun ListenTogetherDialog(controller: DesktopTogether, onDismiss: () -> Unit) {
    com.auralis.music.windows.GlassDialog(onDismiss) { ListenTogetherPage(controller, onDismiss) }
}

/** Compact content is also used by the development screen renderer. */
@Composable
fun ListenTogetherPage(controller: DesktopTogether, onBack: () -> Unit) {
    val state by controller.state.collectAsState()
    ListenTogetherContent(state, onBack, controller::create, controller::join,
        controller::allowGuestControls, controller::togglePlayback, controller::decide, controller::leave)
}

@Composable
internal fun ListenTogetherContent(
    state: DesktopTogether.State, onDismiss: () -> Unit,
    onCreate: (String) -> Unit = {}, onJoin: (String, String) -> Unit = { _, _ -> },
    onGuestControls: (Boolean) -> Unit = {}, onPlayback: () -> Unit = {},
    onDecide: (DesktopTogether.Recommendation, Boolean) -> Unit = { _, _ -> }, onLeave: () -> Unit = {}
) {
    var host by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("Listener") }
    var code by rememberSaveable { mutableStateOf("") }
    val colors = MaterialTheme.colorScheme
    val muted = LocalContentColor.current.copy(alpha = .65f)
    val pill = RoundedCornerShape(50)
    Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Groups, null, tint = colors.primary, modifier = Modifier.size(20.dp))
                Text("Listen Together", Modifier.weight(1f).padding(start = 10.dp),
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                IconButton(onDismiss, Modifier.size(28.dp)) {
                    Icon(Icons.Outlined.Close, "Close Listen Together", Modifier.size(18.dp))
                }
            }
            Text("Play the same music, at the same time, with friends.",
                style = MaterialTheme.typography.bodySmall, color = muted)
        }
        state.message?.let { Text(it, color = colors.error, style = MaterialTheme.typography.bodySmall) }
        if (state.code.isEmpty()) {
            Row(Modifier.fillMaxWidth().background(LocalContentColor.current.copy(alpha = .08f), pill).padding(4.dp)) {
                listOf(false to "Join", true to "Host").forEach { (isHost, label) ->
                    TextButton(onClick = { host = isHost }, enabled = !state.busy,
                        modifier = Modifier.weight(1f).height(36.dp), shape = pill,
                        colors = ButtonDefaults.textButtonColors(
                            containerColor = if (host == isHost) colors.primary.copy(alpha = .22f) else Color.Transparent,
                            contentColor = if (host == isHost) LocalContentColor.current else muted)) { Text(label) }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Your name", style = MaterialTheme.typography.labelMedium, color = muted)
                OutlinedTextField(name, { name = it.take(80) }, Modifier.fillMaxWidth(),
                    enabled = !state.busy, singleLine = true, shape = pill, placeholder = { Text("Your name") })
            }
            if (!host) Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Room code", style = MaterialTheme.typography.labelMedium, color = muted)
                OutlinedTextField(code, { code = it.uppercase(java.util.Locale.ROOT).filter(Char::isLetterOrDigit).take(6) },
                    Modifier.fillMaxWidth(), enabled = !state.busy, singleLine = true, shape = pill,
                    placeholder = { Text("Enter the six-character code") })
                Text("Ask your friend for their room code.", style = MaterialTheme.typography.bodySmall, color = muted)
            } else Text("Create a room and share its code with your friends.", style = MaterialTheme.typography.bodySmall, color = muted)
            Button(onClick = { if (host) onCreate(name) else onJoin(code, name) },
                enabled = !state.busy && (host || code.length == 6),
                modifier = Modifier.fillMaxWidth().height(40.dp), shape = pill) {
                if (state.busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text(if (host) "Create room" else "Join room")
            }
        } else {
            val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (state.host) "Your room" else "Connected to room", color = muted, style = MaterialTheme.typography.labelMedium)
                    Text(state.code, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                }
                TextButton({ clipboard.setText(androidx.compose.ui.text.AnnotatedString(state.code)) }) { Text("Copy code") }
            }
            Text(if (state.host) "You're the host. Play a song to share it with everyone."
                else "Following the host's music and playback.", style = MaterialTheme.typography.bodySmall, color = muted)
            if (state.track.isNotEmpty()) Text("Now playing: ${state.track}", style = MaterialTheme.typography.titleSmall)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("In this room", style = MaterialTheme.typography.titleSmall)
                state.members.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }
            if (state.host) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Let guests control playback", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Switch(state.guestControls, onGuestControls)
            }
            if (state.host || state.guestControls) Button(onPlayback, Modifier.fillMaxWidth()) { Text("Play / pause for everyone") }
            state.recommendations.filter { it.status == "pending" }.forEach { recommendation ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${recommendation.track.title} — ${recommendation.track.artist}")
                    Text("Recommended by ${recommendation.name}", style = MaterialTheme.typography.bodySmall, color = muted)
                    if (state.host) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button({ onDecide(recommendation, true) }) { Text("Add to queue") }
                        OutlinedButton({ onDecide(recommendation, false) }) { Text("Decline") }
                    } else Text("Waiting for the host", color = muted)
                }
            }
            OutlinedButton(onLeave, Modifier.fillMaxWidth(), enabled = !state.busy) { Text(if (state.host) "End room" else "Leave room") }
        }
    }
}
