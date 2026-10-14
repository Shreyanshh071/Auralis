package com.auralis.music.windows.together

import com.auralis.music.ui.components.IconButton
import com.auralis.music.ui.components.Button
import com.auralis.music.ui.components.TextButton
import com.auralis.music.ui.components.OutlinedButton
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
import com.auralis.music.data.sync.RoomSettings
import com.auralis.music.domain.model.Track

@Composable
internal fun ListenTogetherDialog(controller: DesktopTogether, defaultName: String? = null, onDismiss: () -> Unit) {
    com.auralis.music.windows.GlassDialog(onDismiss) { ListenTogetherPage(controller, onDismiss, defaultName) }
}

/** Compact content is also used by the development screen renderer. */
@Composable
fun ListenTogetherPage(controller: DesktopTogether, onBack: () -> Unit, defaultName: String? = null) {
    val state by controller.state.collectAsState()
    ListenTogetherContent(state, onBack, controller::create, controller::join,
        controller::allowGuestControls, controller::togglePlayback, controller::decide, controller::leave, defaultName,
        onSettings = controller::updateHostSettings, onSearch = controller::searchRecommendations,
        onRecommend = controller::recommend, onUpvote = { controller.upvote(it) },
        onPlayNow = controller::playNow, onRemove = controller::dismissRecommendation,
        onRequest = controller::decideRequest)
}

@Composable
internal fun ListenTogetherContent(
    state: DesktopTogether.State, onDismiss: () -> Unit,
    onCreate: (String) -> Unit = {}, onJoin: (String, String) -> Unit = { _, _ -> },
    onGuestControls: (Boolean) -> Unit = {}, onPlayback: () -> Unit = {},
    onDecide: (DesktopTogether.Recommendation, Boolean) -> Unit = { _, _ -> }, onLeave: () -> Unit = {},
    defaultName: String? = null,
    onSettings: (RoomSettings) -> Unit = {}, onSearch: (String) -> Unit = {},
    onRecommend: (Track, String) -> Unit = { _, _ -> },
    onUpvote: (DesktopTogether.Recommendation) -> Unit = {},
    onPlayNow: (DesktopTogether.Recommendation) -> Unit = {},
    onRemove: (DesktopTogether.Recommendation) -> Unit = {},
    onRequest: (DesktopTogether.SongRequest, Boolean) -> Unit = { _, _ -> }
) {
    var host by rememberSaveable { mutableStateOf(false) }
    val suggestedName = defaultName?.trim()?.takeIf { it.isNotEmpty() }?.take(80) ?: "Listener"
    var name by rememberSaveable { mutableStateOf(suggestedName) }
    var nameEdited by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(suggestedName) { if (!nameEdited) name = suggestedName }
    var code by rememberSaveable { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf("") }
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
                OutlinedTextField(name, { name = it.take(80); nameEdited = true }, Modifier.fillMaxWidth(),
                    enabled = !state.busy, singleLine = true, shape = pill, placeholder = { Text("Your name") })
            }
            if (!host) Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Room code", style = MaterialTheme.typography.labelMedium, color = muted)
                OutlinedTextField(code, { code = it.uppercase(java.util.Locale.ROOT).filter(Char::isLetterOrDigit).take(6) },
                    Modifier.fillMaxWidth(), enabled = !state.busy, singleLine = true, shape = pill,
                    placeholder = { Text("Enter the six-character code") })
                Text("Ask your friend for their room code.", style = MaterialTheme.typography.bodySmall, color = muted)
            } else {
                Text("Create a room and share its code with your friends.", style = MaterialTheme.typography.bodySmall, color = muted)
                RoomRuleControls(state.settings, !state.busy, onSettings)
            }
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
                if (state.memberDetails.isEmpty()) state.members.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                else state.memberDetails.forEach {
                    Text(it.name + if (it.isHost) " · Host" else " · Guest", style = MaterialTheme.typography.bodyMedium)
                    if (it.hasSlowConnection) Text("Slow connection", color = muted, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (state.waitingForGuests) Text("Waiting for listeners to load the song…", style = MaterialTheme.typography.bodySmall)
            if (state.poorConnection) Text("Your connection is slow. Playback may take longer to catch up.", style = MaterialTheme.typography.bodySmall)
            if (state.closingAtMs != null) Text("This room will close soon because " +
                if (state.closingReason == "empty") "no guests are connected." else "playback has been idle.", style = MaterialTheme.typography.bodySmall)
            if (state.host) RoomRuleControls(state.settings, !state.busy, onSettings)
            else Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Room permissions", style = MaterialTheme.typography.titleSmall)
                Text(buildList {
                    if (state.settings.guestsCanControlPlayback) add("Play, pause and seek")
                    if (state.settings.guestsCanAddSongs) add("Manage the queue")
                    if (state.settings.guestsCanPlaySongs) add("Choose songs and skip")
                    if (state.settings.requireApproval) add("Song requests need host approval")
                }.joinToString(" · ").ifEmpty { "Only the host can change playback and the queue." },
                    style = MaterialTheme.typography.bodySmall, color = muted)
            }
            if (state.host || state.guestControls) Button(onPlayback, Modifier.fillMaxWidth()) { Text("Play / pause for everyone") }
            state.requests.filter { it.command != null }.forEach { request ->
                Text("${request.name} wants to play ${request.track.title}", style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button({ onRequest(request, true) }) { Text("Allow") }
                    OutlinedButton({ onRequest(request, false) }) { Text("Decline") }
                }
            }
            if (state.host || state.settings.guestsMayAddSongs) {
                Text("Add a song", style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(query, { query = it; onSearch(it) }, Modifier.fillMaxWidth(),
                    singleLine = true, shape = pill, placeholder = { Text("Search songs") })
                if (query.isNotBlank()) {
                    OutlinedTextField(note, { note = it.take(500) }, Modifier.fillMaxWidth(),
                        shape = pill, placeholder = { Text("Add a note (optional)") })
                    if (state.searching) LinearProgressIndicator(Modifier.fillMaxWidth())
                    state.searchResults.forEach { track ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(track.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                                Text(track.artist, style = MaterialTheme.typography.bodySmall, color = muted)
                            }
                            TextButton({ onRecommend(track, note); query = ""; note = ""; onSearch("") }) {
                                Text(if (!state.host && state.settings.requireApproval) "Request" else "Add")
                            }
                        }
                    }
                    if (!state.searching && state.searchResults.isEmpty()) Text("No songs found", style = MaterialTheme.typography.bodySmall)
                }
            }
            state.recommendations.filter { it.status == "pending" }.forEach { recommendation ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${recommendation.track.title} — ${recommendation.track.artist}")
                    Text("Recommended by ${recommendation.name}", style = MaterialTheme.typography.bodySmall, color = muted)
                    if (recommendation.note.isNotBlank()) Text(recommendation.note, style = MaterialTheme.typography.bodySmall)
                    if (state.host) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button({ onDecide(recommendation, true) }) { Text("Add to queue") }
                        TextButton({ onPlayNow(recommendation) }) { Text("Play now") }
                        OutlinedButton({ onDecide(recommendation, false) }) { Text("Decline") }
                    } else Text("Waiting for the host", color = muted)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton({ onUpvote(recommendation) }) { Text("${if (state.uid in recommendation.upvotes) "Voted" else "Vote"} · ${recommendation.upvotes.size}") }
                        if (state.host || recommendation.uid == state.uid) TextButton({ onRemove(recommendation) }) { Text("Remove") }
                    }
                }
            }
            state.activity.takeLast(3).forEach { Text(it.message, style = MaterialTheme.typography.bodySmall, color = muted) }
            OutlinedButton(onLeave, Modifier.fillMaxWidth(), enabled = !state.busy) { Text(if (state.host) "End room" else "Leave room") }
        }
    }
}

@Composable
private fun RoomRuleControls(settings: RoomSettings, enabled: Boolean, onChange: (RoomSettings) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Room rules", style = MaterialTheme.typography.titleSmall)
        val rules = listOf(
            Triple("Guests can add songs", "Manage the room queue", settings.guestsCanAddSongs),
            Triple("Guests can control playback", "Play, pause and seek for everyone", settings.guestsCanControlPlayback),
            Triple("Guests can play songs", "Pick any song or skip next/previous", settings.guestsCanPlaySongs),
            Triple("Require approval", "Ask the host to approve adding or playing songs", settings.requireApproval))
        rules.forEachIndexed { index, (title, description, checked) ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.bodyMedium)
                    Text(description, style = MaterialTheme.typography.bodySmall, color = LocalContentColor.current.copy(alpha = .65f))
                }
                Switch(checked, { value -> onChange(when(index) {
                    0 -> settings.copy(guestsCanAddSongs = value)
                    1 -> settings.copy(guestsCanControlPlayback = value)
                    2 -> settings.copy(guestsCanPlaySongs = value)
                    else -> settings.copy(requireApproval = value)
                }) }, enabled = enabled)
            }
        }
    }
}
