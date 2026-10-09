package com.auralis.music.ui.screens

import com.auralis.music.R
import com.auralis.music.ui.i18n.str

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Pin
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import com.auralis.music.ui.theme.dynamicBackground
import com.auralis.music.ui.theme.dynamicOnBackground
import com.auralis.music.ui.theme.dynamicPrimary
import com.auralis.music.ui.theme.dynamicSurface
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auralis.music.data.sync.RoomRecommendation
import com.auralis.music.data.sync.RoomSettings
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import com.auralis.music.domain.model.Track
import com.auralis.music.ui.components.ArtworkCard
import com.auralis.music.ui.components.EqualizerBars
import com.auralis.music.ui.viewmodel.ListenTogetherUiState
import com.auralis.music.ui.components.bottomChromePadding

private val LISTEN_LIME = Color(0xFFD4E157)
private val LISTEN_CARD_BG = Color(0xFF1B1D16)
private val LISTEN_OLIVE_DARK = Color(0xFF282C1C)

/**
 * Pixel-Perfect Fullscreen Listen Together Sheet.
 * Includes real-time synced playback, participant list, and dynamic song recommendations.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListenTogetherSheet(
    uiState: ListenTogetherUiState,
    currentTrack: Track?,
    isPlaying: Boolean,
    queue: List<Track>,
    playbackPositionMs: Long,
    onNameChange: (String) -> Unit,
    onCreateRoom: (Track?, List<Track>, Boolean, Long) -> Unit,
    onJoinRoom: (String) -> Unit,
    onLeaveRoom: () -> Unit,
    onSearchRecommendations: (String) -> Unit = {},
    onClearRecommendationSearch: () -> Unit = {},
    onRecommendSong: (Track, String) -> Unit = { _, _ -> },
    onUpvoteRecommendation: (String) -> Unit = {},
    onDismissRecommendation: (String) -> Unit = {},
    onPlayRecommendationNow: (RoomRecommendation) -> Unit = {},
    onAddRecommendationToQueue: (RoomRecommendation) -> Unit = {},
    onDeclineRecommendation: (RoomRecommendation) -> Unit = {},
    onHostSettingsChange: (RoomSettings) -> Unit = {},
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    isPopup: Boolean = false
) {
    val context = LocalContext.current
    var joinCodeInput by remember { mutableStateOf("") }
    var selectedTab by remember { mutableIntStateOf(0) } // 0 = Join, 1 = Host

    val primaryColor = MaterialTheme.dynamicPrimary
    val onBackground = MaterialTheme.dynamicOnBackground
    val surfaceColor = MaterialTheme.dynamicSurface
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    val cardBorder = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    val backgroundColor = MaterialTheme.dynamicBackground

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(backgroundColor)
            .then(if (isPopup) Modifier else Modifier.statusBarsPadding())
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 18.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // ── TOP BAR: TITLE & CLOSE ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp, bottom = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(primaryColor.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Groups,
                            contentDescription = null,
                            tint = primaryColor,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Text(
                        text = str(R.string.listen_together),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = onBackground,
                        fontSize = 22.sp
                    )
                }

                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = str(R.string.close),
                        tint = onBackground,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // ── ACTIVE ROOM VIEW ──
            if (uiState.activeRoom != null) {
                val room = uiState.activeRoom

                // Room Status Banner
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(surfaceColor)
                        .border(1.dp, primaryColor.copy(alpha = 0.35f), RoundedCornerShape(20.dp))
                        .padding(20.dp)
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Radio,
                                contentDescription = null,
                                tint = primaryColor,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (uiState.isHost) str(R.string.broadcasting_as_host) else str(R.string.synced_with_host),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = primaryColor,
                                letterSpacing = 1.sp
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Big Room Code Badge
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(16.dp))
                                .background(primaryColor.copy(alpha = 0.15f))
                                .border(1.dp, primaryColor, RoundedCornerShape(16.dp))
                                .clickable {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(ClipData.newPlainText("Room Code", room.code))
                                    Toast.makeText(context, str(R.string.room_code_copied_x, room.code), Toast.LENGTH_SHORT).show()
                                }
                                .padding(horizontal = 24.dp, vertical = 12.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = room.code,
                                    fontSize = 28.sp,
                                    fontWeight = FontWeight.Black,
                                    letterSpacing = 4.sp,
                                    color = primaryColor
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Icon(
                                    imageVector = Icons.Default.ContentCopy,
                                    contentDescription = str(R.string.copy),
                                    modifier = Modifier.size(20.dp),
                                    tint = primaryColor
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = str(R.string.tap_code_to_copy_and_invite_friends),
                            style = MaterialTheme.typography.labelSmall,
                            color = onSurfaceVariant
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        // Currently Synced Track Card
                        val syncedTrack = room.currentTrack
                        if (syncedTrack != null) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(primaryColor.copy(alpha = 0.08f))
                                    .border(1.dp, onBackground.copy(alpha = 0.08f), RoundedCornerShape(14.dp))
                                    .padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                ArtworkCard(
                                    url = syncedTrack.thumbnail,
                                    modifier = Modifier.size(48.dp),
                                    cornerRadius = 8.dp,
                                    contentDescription = syncedTrack.title
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = syncedTrack.title,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = onBackground,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = syncedTrack.artist,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                EqualizerBars(
                                    isPlaying = room.isPlaying,
                                    modifier = Modifier.size(16.dp),
                                    color = primaryColor
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                if (uiState.isHost) {
                    RoomRulesCard(
                        settings = uiState.hostSettings,
                        onChange = onHostSettingsChange,
                        surfaceColor = surfaceColor,
                        borderColor = cardBorder,
                        primaryColor = primaryColor,
                        textColor = onBackground,
                        secondaryTextColor = onSurfaceVariant
                    )
                    val requests = uiState.recommendations.filter { it.status == "pending" }
                    if (uiState.hostSettings.requireApproval && requests.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(16.dp))
                        SongRequestsCard(
                            requests = requests,
                            onApprove = onAddRecommendationToQueue,
                            onDecline = onDeclineRecommendation,
                            surfaceColor = surfaceColor,
                            borderColor = cardBorder,
                            primaryColor = primaryColor,
                            textColor = onBackground,
                            secondaryTextColor = onSurfaceVariant
                        )
                    }
                } else {
                    GuestPermissionsCard(
                        settings = room.settings,
                        surfaceColor = surfaceColor,
                        borderColor = cardBorder,
                        textColor = onBackground,
                        secondaryTextColor = onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Connected Participants List
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(surfaceColor)
                        .border(1.dp, cardBorder, RoundedCornerShape(20.dp))
                        .padding(18.dp)
                ) {
                    Column {
                        Text(
                            text = str(R.string.connected_listeners_x, uiState.members.size),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = onBackground
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 160.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(uiState.members) { member ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(primaryColor.copy(alpha = 0.08f))
                                        .padding(horizontal = 14.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(36.dp)
                                            .clip(CircleShape)
                                            .background(if (member.isHost) primaryColor else primaryColor.copy(alpha = 0.25f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = member.name.take(1).uppercase(),
                                            fontWeight = FontWeight.Bold,
                                            color = if (member.isHost) Color.Black else onBackground
                                        )
                                    }

                                    Spacer(modifier = Modifier.width(12.dp))

                                    Text(
                                        text = member.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = onBackground,
                                        modifier = Modifier.weight(1f)
                                    )

                                    if (member.isHost) {
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(primaryColor)
                                                .padding(horizontal = 8.dp, vertical = 3.dp)
                                        ) {
                                            Text(
                                                text = "HOST",
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.Bold,
                                                color = Color.Black
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Leave / End Room Button
                OutlinedButton(
                    onClick = onLeaveRoom,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (uiState.isHost) str(R.string.end_broadcast_session) else str(R.string.leave_room), fontWeight = FontWeight.Bold)
                }

            } else {
                // ── NOT IN ROOM: NICKNAME & CAPSULE MODE SWITCHER ──
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(surfaceColor)
                        .border(1.dp, cardBorder, RoundedCornerShape(20.dp))
                        .padding(18.dp)
                ) {
                    Column {
                        Text(
                            text = str(R.string.your_identity),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = onBackground
                        )
                        Spacer(modifier = Modifier.height(10.dp))

                        OutlinedTextField(
                            value = uiState.myDisplayName,
                            onValueChange = onNameChange,
                            label = { Text(str(R.string.display_name), color = onSurfaceVariant) },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = primaryColor,
                                unfocusedBorderColor = onBackground.copy(alpha = 0.15f),
                                focusedTextColor = onBackground,
                                unfocusedTextColor = onBackground
                            ),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth(),
                            leadingIcon = { Icon(Icons.Default.Person, contentDescription = null, tint = primaryColor) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Mode Capsule Switcher (Join Room vs Host Room)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(CircleShape)
                        .background(surfaceColor)
                        .border(1.dp, cardBorder, CircleShape)
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(CircleShape)
                            .background(if (selectedTab == 0) primaryColor else Color.Transparent)
                            .clickable { selectedTab = 0 }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = str(R.string.join_room),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (selectedTab == 0) Color.Black else onBackground
                        )
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(CircleShape)
                            .background(if (selectedTab == 1) primaryColor else Color.Transparent)
                            .clickable { selectedTab = 1 }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = str(R.string.host_room),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (selectedTab == 1) Color.Black else onBackground
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // ── TAB 0: JOIN ROOM ──
                if (selectedTab == 0) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(20.dp))
                            .background(surfaceColor)
                            .border(1.dp, cardBorder, RoundedCornerShape(20.dp))
                            .padding(18.dp)
                    ) {
                        Column {
                            Text(
                                text = str(R.string.enter_room_code),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = onBackground
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = str(R.string.enter_the_6_character_room_code_provided),
                                style = MaterialTheme.typography.bodySmall,
                                color = onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(12.dp))

                            OutlinedTextField(
                                value = joinCodeInput,
                                onValueChange = { if (it.length <= 6) joinCodeInput = it.uppercase() },
                                placeholder = { Text("AUR921", color = onSurfaceVariant.copy(alpha = 0.5f)) },
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = primaryColor,
                                    unfocusedBorderColor = onBackground.copy(alpha = 0.15f),
                                    focusedTextColor = onBackground,
                                    unfocusedTextColor = onBackground
                                ),
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier.fillMaxWidth(),
                                leadingIcon = { Icon(Icons.Default.Pin, contentDescription = null, tint = primaryColor) }
                            )

                            Spacer(modifier = Modifier.height(16.dp))

                            Button(
                                onClick = { onJoinRoom(joinCodeInput) },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = joinCodeInput.length >= 4 && !uiState.isConnecting,
                                colors = ButtonDefaults.buttonColors(containerColor = primaryColor),
                                shape = RoundedCornerShape(14.dp)
                            ) {
                                if (uiState.isConnecting) {
                                    CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.Black, strokeWidth = 2.dp)
                                } else {
                                    Icon(Icons.AutoMirrored.Filled.Login, contentDescription = null, tint = Color.Black, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(str(R.string.connect_sync_now), color = Color.Black, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                } else {
                    // ── TAB 1: HOST ROOM ──
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(20.dp))
                            .background(surfaceColor)
                            .border(1.dp, cardBorder, RoundedCornerShape(20.dp))
                            .padding(18.dp)
                    ) {
                        Column {
                            Text(
                                text = str(R.string.start_live_broadcast),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = onBackground
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = str(R.string.start_a_room_and_listen_together_everyon),
                                style = MaterialTheme.typography.bodySmall,
                                color = onSurfaceVariant
                            )

                            Spacer(modifier = Modifier.height(16.dp))

                            RoomRuleRows(
                                settings = uiState.hostSettings,
                                onChange = onHostSettingsChange,
                                primaryColor = primaryColor,
                                textColor = onBackground,
                                secondaryTextColor = onSurfaceVariant
                            )

                            Spacer(modifier = Modifier.height(16.dp))

                            Button(
                                onClick = { onCreateRoom(currentTrack, queue, isPlaying, playbackPositionMs) },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !uiState.isConnecting,
                                colors = ButtonDefaults.buttonColors(containerColor = primaryColor),
                                shape = RoundedCornerShape(14.dp)
                            ) {
                                if (uiState.isConnecting) {
                                    CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.Black, strokeWidth = 2.dp)
                                } else {
                                    Icon(Icons.Default.Add, contentDescription = null, tint = Color.Black, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(str(R.string.create_broadcast_room), color = Color.Black, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }

                if (uiState.errorMessage != null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = uiState.errorMessage,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            Spacer(modifier = if (isPopup) Modifier.height(20.dp) else Modifier.padding(bottomChromePadding()))
        }
    }
}

/** The three host rules, as switches. "Require approval" only applies while guests can add songs. */
@Composable
private fun RoomRuleRows(
    settings: RoomSettings,
    onChange: (RoomSettings) -> Unit,
    primaryColor: Color,
    textColor: Color,
    secondaryTextColor: Color
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        RoomRuleRow(
            icon = Icons.AutoMirrored.Filled.PlaylistAdd,
            title = str(R.string.guests_can_add_songs),
            subtitle = str(R.string.guests_manage_queue_description),
            checked = settings.guestsCanAddSongs,
            enabled = true,
            onCheckedChange = { onChange(settings.copy(guestsCanAddSongs = it)) },
            primaryColor = primaryColor, textColor = textColor, secondaryTextColor = secondaryTextColor
        )
        RoomRuleRow(
            icon = Icons.Default.PlayArrow,
            title = str(R.string.guests_can_control_playback),
            subtitle = str(R.string.play_pause_and_seek_for_everyone),
            checked = settings.guestsCanControlPlayback,
            enabled = true,
            onCheckedChange = { onChange(settings.copy(guestsCanControlPlayback = it)) },
            primaryColor = primaryColor, textColor = textColor, secondaryTextColor = secondaryTextColor
        )
        RoomRuleRow(
            icon = Icons.Default.MusicNote,
            title = str(R.string.guests_can_play_songs),
            subtitle = str(R.string.pick_any_song_or_skip_next_previous_for),
            checked = settings.guestsCanPlaySongs,
            enabled = true,
            onCheckedChange = { onChange(settings.copy(guestsCanPlaySongs = it)) },
            primaryColor = primaryColor, textColor = textColor, secondaryTextColor = secondaryTextColor
        )
        RoomRuleRow(
            icon = Icons.Default.Lock,
            title = str(R.string.require_approval),
            subtitle = str(R.string.guests_can_ask_to_add_or_play_any_song_y),
            checked = settings.requireApproval,
            enabled = true,
            onCheckedChange = { onChange(settings.copy(requireApproval = it)) },
            primaryColor = primaryColor, textColor = textColor, secondaryTextColor = secondaryTextColor
        )
    }
}

@Composable
private fun RoomRuleRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    primaryColor: Color,
    textColor: Color,
    secondaryTextColor: Color
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(primaryColor.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = primaryColor.copy(alpha = if (enabled) 1f else 0.4f), modifier = Modifier.size(20.dp))
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = textColor.copy(alpha = if (enabled) 1f else 0.45f)
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = secondaryTextColor.copy(alpha = if (enabled) 1f else 0.6f)
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(checkedTrackColor = primaryColor, checkedThumbColor = Color.Black)
        )
    }
}

@Composable
private fun RoomRulesCard(
    settings: RoomSettings,
    onChange: (RoomSettings) -> Unit,
    surfaceColor: Color,
    borderColor: Color,
    primaryColor: Color,
    textColor: Color,
    secondaryTextColor: Color
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(surfaceColor)
            .border(1.dp, borderColor, RoundedCornerShape(20.dp))
            .padding(horizontal = 18.dp, vertical = 14.dp)
    ) {
        Column {
            Text(str(R.string.room_rules), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = textColor)
            Spacer(modifier = Modifier.height(6.dp))
            RoomRuleRows(settings, onChange, primaryColor, textColor, secondaryTextColor)
        }
    }
}

@Composable
private fun SongRequestsCard(
    requests: List<RoomRecommendation>,
    onApprove: (RoomRecommendation) -> Unit,
    onDecline: (RoomRecommendation) -> Unit,
    surfaceColor: Color,
    borderColor: Color,
    primaryColor: Color,
    textColor: Color,
    secondaryTextColor: Color
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(surfaceColor)
            .border(1.dp, primaryColor.copy(alpha = 0.35f), RoundedCornerShape(20.dp))
            .padding(18.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = str(R.string.song_requests_x, requests.size),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = textColor
            )
            requests.forEach { rec ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ArtworkCard(
                        url = rec.track.thumbnail,
                        modifier = Modifier.size(44.dp),
                        cornerRadius = 8.dp,
                        contentDescription = rec.track.title
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(rec.track.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = textColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(str(R.string.x_from_x, rec.track.artist, rec.recommendedByName), style = MaterialTheme.typography.bodySmall, color = secondaryTextColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    TextButton(onClick = { onDecline(rec) }) {
                        Text(str(R.string.decline), color = secondaryTextColor, fontWeight = FontWeight.SemiBold)
                    }
                    Button(
                        onClick = { onApprove(rec) },
                        colors = ButtonDefaults.buttonColors(containerColor = primaryColor),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        Text(str(R.string.allow), color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun GuestPermissionsCard(
    settings: RoomSettings,
    surfaceColor: Color,
    borderColor: Color,
    textColor: Color,
    secondaryTextColor: Color
) {
    val lines = listOf(
        when {
            settings.requireApproval && settings.guestsCanAddSongs -> str(R.string.room_queue_manage_with_approval)
            settings.requireApproval -> str(R.string.you_can_ask_to_add_songs_the_host_allows)
            settings.guestsCanAddSongs -> str(R.string.room_queue_manage_allowed)
            else -> str(R.string.only_the_host_adds_songs)
        },
        if (settings.guestsCanControlPlayback) str(R.string.you_can_play_pause_and_seek_for_everyone)
        else str(R.string.only_the_host_controls_playback),
        if (settings.requireApproval) "You can ask to play any song; the host allows it"
        else if (settings.guestsCanPlaySongs) str(R.string.you_can_play_any_song_or_skip_for_everyo)
        else str(R.string.only_the_host_picks_what_plays_next)
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(surfaceColor)
            .border(1.dp, borderColor, RoundedCornerShape(20.dp))
            .padding(18.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(str(R.string.what_you_can_do), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = textColor)
            lines.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = secondaryTextColor) }
        }
    }
}
