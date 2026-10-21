package com.auralis.music.windows.together

import com.auralis.music.ui.i18n.desktopText

import com.auralis.music.ui.components.IconButton
import com.auralis.music.ui.components.Button
import com.auralis.music.ui.components.TextButton
import com.auralis.music.ui.components.OutlinedButton
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.luminance
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Radio
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.VerifiedUser
import com.auralis.music.ui.components.clickable
import com.auralis.music.windows.home.HomeArtwork
import androidx.compose.ui.unit.sp
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
        onDecide = controller::decide, onLeave = controller::leave, defaultName = defaultName,
        onSettings = controller::updateHostSettings, onRequest = controller::decideRequest)
}

@Composable
internal fun ListenTogetherContent(
    state: DesktopTogether.State, onDismiss: () -> Unit,
    onCreate: (String) -> Unit = {}, onJoin: (String, String) -> Unit = { _, _ -> },
    onDecide: (DesktopTogether.Recommendation, Boolean) -> Unit = { _, _ -> }, onLeave: () -> Unit = {},
    defaultName: String? = null,
    onSettings: (RoomSettings) -> Unit = {},
    onRequest: (DesktopTogether.SongRequest, Boolean) -> Unit = { _, _ -> }
) {
    var host by rememberSaveable { mutableStateOf(false) }
    val suggestedName = defaultName?.trim()?.takeIf { it.isNotEmpty() }?.take(80) ?: "Listener"
    var name by rememberSaveable { mutableStateOf(suggestedName) }
    var nameEdited by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(suggestedName) { if (!nameEdited) name = suggestedName }
    var code by rememberSaveable { mutableStateOf("") }
    val colors = MaterialTheme.colorScheme
    val muted = LocalContentColor.current.copy(alpha = .65f)
    val pill = RoundedCornerShape(50)
    Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Groups, null, tint = colors.primary, modifier = Modifier.size(20.dp))
                Text(desktopText("Listen Together"), Modifier.weight(1f).padding(start = 10.dp),
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                IconButton(onDismiss, Modifier.size(28.dp)) {
                    Icon(Icons.Outlined.Close, desktopText("Close Listen Together"), Modifier.size(18.dp))
                }
            }
            Text(desktopText("Play the same music, at the same time, with friends."),
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
                Text(desktopText("Your name"), style = MaterialTheme.typography.labelMedium, color = muted)
                OutlinedTextField(name, { name = it.take(80); nameEdited = true }, Modifier.fillMaxWidth(),
                    enabled = !state.busy, singleLine = true, shape = pill, placeholder = { Text(desktopText("Your name")) })
            }
            if (!host) Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(desktopText("Room code"), style = MaterialTheme.typography.labelMedium, color = muted)
                OutlinedTextField(code, { code = it.uppercase(java.util.Locale.ROOT).filter(Char::isLetterOrDigit).take(6) },
                    Modifier.fillMaxWidth(), enabled = !state.busy, singleLine = true, shape = pill,
                    placeholder = { Text(desktopText("Enter the six-character code")) })
                Text(desktopText("Ask your friend for their room code."), style = MaterialTheme.typography.bodySmall, color = muted)
            } else {
                Text(desktopText("Create a room and share its code with your friends."), style = MaterialTheme.typography.bodySmall, color = muted)
                RoomRuleControls(state.settings, !state.busy, onSettings)
            }
            Button(onClick = { if (host) onCreate(name) else onJoin(code, name) },
                enabled = !state.busy && (host || code.length == 6),
                modifier = Modifier.fillMaxWidth().height(40.dp), shape = pill) {
                if (state.busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Text(desktopText(if (host) "Create room" else "Join room"))
            }
        } else {
            RoomStatusCard(state)
            if (state.waitingForGuests) Text(desktopText("Waiting for listeners to load the song…"), style = MaterialTheme.typography.bodySmall)
            if (state.poorConnection) Text(desktopText("Your connection is slow. Playback may take longer to catch up."), style = MaterialTheme.typography.bodySmall)
            if (state.closingAtMs != null) Text(desktopText("This room will close soon because ") +
                if (state.closingReason == "empty") "no guests are connected." else "playback has been idle.", style = MaterialTheme.typography.bodySmall)
            if (state.host) RoomRuleControls(state.settings, !state.busy, onSettings)
            else GuestPermissionsCard(state.settings)
            if (state.host && state.settings.requireApproval) {
                val recommendations = state.recommendations.filter { it.status == "pending" }
                val requests = state.requests.filter { it.command != null }
                if (recommendations.isNotEmpty() || requests.isNotEmpty()) RoomSection("Song requests") {
                    recommendations.forEach { rec ->
                        RoomRequestRow(rec.track, rec.name, { onDecide(rec, true) }, { onDecide(rec, false) })
                    }
                    requests.forEach { request ->
                        RoomRequestRow(request.track, request.name, { onRequest(request, true) }, { onRequest(request, false) })
                    }
                }
            }
            ConnectedListenersCard(state)
            Text(desktopText("Use Search, Library and song menus to choose music for the room."),
                style = MaterialTheme.typography.bodySmall, color = muted)
            OutlinedButton(onLeave, Modifier.fillMaxWidth().height(48.dp), enabled = !state.busy,
                shape = RoundedCornerShape(14.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFFF8A91).copy(alpha = .6f)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFF8A91))) {
                Text(desktopText(if (state.host) "End broadcast session" else "Leave room"), fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun RoomSection(title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    val foreground = LocalContentColor.current
    val shape = RoundedCornerShape(18.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(Color(0xFF202024).copy(alpha = .50f))
        .border(1.dp, foreground.copy(alpha = .12f), shape).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (title != null) Text(desktopText(title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        content()
    }
}

@Composable
private fun RoomStatusCard(state: DesktopTogether.State) {
    val primary = MaterialTheme.colorScheme.primary
    val foreground = LocalContentColor.current
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    RoomSection {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Outlined.Radio, null, Modifier.size(16.dp), tint = primary)
                Text(desktopText(if (state.host) "BROADCASTING AS HOST" else "SYNCED WITH HOST"),
                    style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = primary)
            }
            Row(Modifier.clip(RoundedCornerShape(14.dp)).background(primary.copy(alpha = .12f))
                .border(1.dp, primary.copy(alpha = .55f), RoundedCornerShape(14.dp))
                .clickable { clipboard.setText(androidx.compose.ui.text.AnnotatedString(state.code)) }
                .padding(horizontal = 22.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(state.code, fontSize = 28.sp, letterSpacing = 4.sp, fontWeight = FontWeight.Black, color = primary)
                Icon(Icons.Outlined.ContentCopy, desktopText("Copy room code"), Modifier.size(20.dp), tint = primary)
            }
            Text(desktopText("Click the code to copy and invite friends"), style = MaterialTheme.typography.labelSmall,
                color = foreground.copy(alpha = .65f))
        }
        val track = state.currentTrack ?: state.track.takeIf { it.isNotBlank() }?.let { Track("room-current", it, "") }
        if (track != null) Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(primary.copy(alpha = .08f)).padding(10.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            HomeArtwork(track.thumbnail, Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)), track.id, track.title)
            Column(Modifier.weight(1f)) {
                Text(track.title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (track.artist.isNotBlank()) Text(track.artist, style = MaterialTheme.typography.bodySmall,
                    color = foreground.copy(alpha = .65f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            com.auralis.music.ui.components.EqualizerBars(state.isPlaying, Modifier.size(18.dp), primary)
        } else Text(desktopText("Play a song to share it with everyone."), style = MaterialTheme.typography.bodySmall,
            color = foreground.copy(alpha = .65f))
    }
}

@Composable
private fun RoomRuleControls(settings: RoomSettings, enabled: Boolean, onChange: (RoomSettings) -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    val foreground = LocalContentColor.current
    val rules = listOf(
        Triple("Guests can add songs", "Manage the room queue", settings.guestsCanAddSongs),
        Triple("Guests can control playback", "Play, pause and seek for everyone", settings.guestsCanControlPlayback),
        Triple("Guests can play songs", "Choose songs and skip next/previous", settings.guestsCanPlaySongs),
        Triple("Require approval", "The host approves song requests", settings.requireApproval))
    val icons = listOf(Icons.AutoMirrored.Outlined.PlaylistAdd, Icons.Outlined.TouchApp, Icons.Outlined.PlayArrow, Icons.Outlined.VerifiedUser)
    RoomSection("Room rules") {
        rules.forEachIndexed { index, (title, description, checked) ->
            val change = { value: Boolean -> onChange(when (index) {
                0 -> settings.copy(guestsCanAddSongs = value)
                1 -> settings.copy(guestsCanControlPlayback = value)
                2 -> settings.copy(guestsCanPlaySongs = value)
                else -> settings.copy(requireApproval = value)
            }) }
            Row(Modifier.fillMaxWidth().clickable(enabled = enabled) { change(!checked) }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(primary.copy(alpha = .12f)),
                    contentAlignment = Alignment.Center) {
                    Icon(icons[index], null, Modifier.size(20.dp), tint = primary)
                }
                Column(Modifier.weight(1f)) {
                    Text(desktopText(title), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Text(desktopText(description), style = MaterialTheme.typography.bodySmall, color = foreground.copy(alpha = .65f))
                }
                Switch(checked, change, enabled = enabled, colors = SwitchDefaults.colors(
                    checkedTrackColor = primary, checkedThumbColor = if (primary.luminance() > .5f) Color.Black else Color.White))
            }
        }
    }
}

@Composable
private fun GuestPermissionsCard(settings: RoomSettings) {
    RoomSection("Room permissions") {
        Text(when {
            settings.requireApproval -> "You can request songs to add or play. The host approves each request."
            settings.guestsCanAddSongs -> "You can add songs and manage the room queue."
            else -> "Only the host can add songs and manage the room queue."
        }, style = MaterialTheme.typography.bodySmall)
        if (settings.guestsCanControlPlayback) Text(desktopText("You can play, pause and seek for everyone."), style = MaterialTheme.typography.bodySmall)
        if (settings.guestsCanPlaySongs && !settings.requireApproval) Text(desktopText("You can choose songs and skip for everyone."), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ConnectedListenersCard(state: DesktopTogether.State) {
    val primary = MaterialTheme.colorScheme.primary
    val foreground = LocalContentColor.current
    val members = state.memberDetails.ifEmpty { state.members.mapIndexed { index, name ->
        com.auralis.music.data.sync.RoomMember("preview-$index", name,
            name == state.hostName || (state.hostName.isBlank() && state.host && index == 0), 0L)
    } }
    RoomSection("Connected listeners (${members.size})") {
        Column(Modifier.fillMaxWidth().heightIn(max = 160.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            members.forEach { member ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(primary.copy(alpha = .08f))
                    .padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.size(36.dp).clip(CircleShape).background(primary.copy(alpha = .16f)), contentAlignment = Alignment.Center) {
                        Text(member.name.take(1).uppercase(), color = primary, fontWeight = FontWeight.Bold)
                    }
                    Column(Modifier.weight(1f)) {
                        Text(member.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (member.hasSlowConnection) Text(desktopText("Slow connection"), style = MaterialTheme.typography.bodySmall,
                            color = foreground.copy(alpha = .65f))
                    }
                    if (member.isHost) Text(desktopText("HOST"), color = primary, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun RoomRequestRow(track: Track, name: String, onApprove: () -> Unit, onDecline: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        HomeArtwork(track.thumbnail, Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)), track.id)
        Column(Modifier.weight(1f)) {
            Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
            Text("${track.artist} · $name", style = MaterialTheme.typography.bodySmall,
                color = LocalContentColor.current.copy(alpha = .65f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onApprove) { Text(desktopText("Approve")) }
        TextButton(onDecline) { Text(desktopText("Decline")) }
    }
}
