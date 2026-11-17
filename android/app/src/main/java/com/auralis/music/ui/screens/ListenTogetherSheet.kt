package com.auralis.music.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.scaleIn
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.unit.IntOffset
import com.kyant.backdrop.backdrops.layerBackdrop
import com.auralis.music.ui.glass.OptionalGlassPage
import com.auralis.music.ui.glass.pageCardSurface
import com.auralis.music.ui.glass.pageGlassSelection
import com.auralis.music.ui.glass.pageGlassActionColor
import com.auralis.music.ui.glass.pageGlassButtonFill
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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Share
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
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
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


/**
 * Listen Together: a lobby to join a friend or start a room, and the live room itself.
 * Both are built around [TogetherOrbit], the shared song in a ring with everyone riding it.
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
    val accent = MaterialTheme.dynamicPrimary
    val foreground = MaterialTheme.dynamicOnBackground
    OptionalGlassPage(
        MaterialTheme.dynamicBackground, accent,
        modifier.fillMaxSize().background(MaterialTheme.dynamicBackground),
        glowWithoutGlass = true, glowBehindStatusBar = !isPopup
    ) {
        val backdrop = com.kyant.backdrop.backdrops.rememberLayerBackdrop()
        val palette = TogetherPalette(
            accent = accent,
            onAccent = pageGlassActionColor(MaterialTheme.colorScheme.onPrimary),
            foreground = foreground,
            muted = foreground.copy(alpha = .62f),
            card = foreground.copy(alpha = .07f)
        )
        Column(
            Modifier
                .fillMaxSize()
                .then(if (isPopup) Modifier else Modifier.statusBarsPadding())
                .layerBackdrop(backdrop)
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header: title centred between the floating back button and the live pill.
            Box(Modifier.fillMaxWidth().height(46.dp), contentAlignment = Alignment.Center) {
                Text(str(R.string.listen_together), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = foreground)
                if (uiState.activeRoom != null) {
                    Box(Modifier.align(Alignment.CenterEnd)) { LivePill(str(R.string.lt_live), accent) }
                }
            }
            Spacer(Modifier.height(18.dp))

            AnimatedContent(
                targetState = uiState.activeRoom != null,
                transitionSpec = {
                    (fadeIn(tween(320, delayMillis = 80)) + scaleIn(tween(320), initialScale = .96f))
                        .togetherWith(fadeOut(tween(160)))
                        .using(SizeTransform(clip = false))
                },
                label = "togetherMode"
            ) { inRoom ->
                if (inRoom && uiState.activeRoom != null) {
                    TogetherRoom(
                        uiState = uiState,
                        palette = palette,
                        onLeaveRoom = onLeaveRoom,
                        onHostSettingsChange = onHostSettingsChange,
                        onAddRecommendationToQueue = onAddRecommendationToQueue,
                        onDeclineRecommendation = onDeclineRecommendation
                    )
                } else {
                    TogetherLobby(
                        state = uiState,
                        track = currentTrack,
                        playing = isPlaying,
                        queue = queue,
                        position = playbackPositionMs,
                        palette = palette,
                        onName = onNameChange,
                        onCreate = onCreateRoom,
                        onJoin = onJoinRoom,
                        onSettings = onHostSettingsChange
                    )
                }
            }
            Spacer(if (isPopup) Modifier.height(20.dp) else Modifier.padding(bottomChromePadding()))
        }
        com.auralis.music.ui.glass.FloatingPageButton(
            Icons.AutoMirrored.Filled.ArrowBack, str(R.string.back), backdrop,
            Modifier.align(Alignment.TopStart)
                .then(if (isPopup) Modifier else Modifier.statusBarsPadding())
                .padding(start = 14.dp, top = 12.dp),
            onClick = onDismiss
        )
    }
}

/** Colours read once inside the page, where its glass is known. */
private data class TogetherPalette(
    val accent: Color,
    val onAccent: Color,
    val foreground: Color,
    val muted: Color,
    val card: Color
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TogetherLobby(
    state: ListenTogetherUiState,
    track: Track?,
    playing: Boolean,
    queue: List<Track>,
    position: Long,
    palette: TogetherPalette,
    onName: (String) -> Unit,
    onCreate: (Track?, List<Track>, Boolean, Long) -> Unit,
    onJoin: (String) -> Unit,
    onSettings: (RoomSettings) -> Unit
) {
    var code by remember { mutableStateOf("") }
    // Which button started the current attempt, so only that one spins.
    var joining by remember { mutableStateOf(false) }
    // The view model clears isConnecting as soon as the room is written, but the room only shows
    // once its first update arrives. Keep spinning across that gap instead of flashing the lobby
    // back as if nothing happened; give up after 20s so a stuck listener can't lock the page.
    var awaitingRoom by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(state.isConnecting, state.errorMessage, awaitingRoom) {
        if (state.errorMessage != null) awaitingRoom = false
        if (awaitingRoom && !state.isConnecting) {
            kotlinx.coroutines.delay(20_000)
            awaitingRoom = false
        }
    }
    val busy = state.isConnecting || (awaitingRoom && state.errorMessage == null)
    val accent = palette.accent
    val foreground = palette.foreground
    val muted = palette.muted
    val card = palette.card
    val orbitState = when {
        busy -> OrbitState.CONNECTING
        state.errorMessage != null -> OrbitState.ERROR
        else -> OrbitState.IDLE
    }

    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        TogetherOrbit(
            artworkUrl = track?.thumbnail,
            contentDescription = track?.title,
            state = orbitState,
            isPlaying = playing,
            accent = accent,
            trackColor = card,
            size = 210.dp
        )
        Spacer(Modifier.height(18.dp))
        AnimatedContent(targetState = orbitState, label = "lobbyStatus") { s ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = when (s) {
                        OrbitState.CONNECTING -> str(R.string.lt_connecting)
                        OrbitState.ERROR -> state.errorMessage.orEmpty()
                        else -> if (track != null) str(R.string.lt_ready_to_share) else str(R.string.lt_nothing_playing)
                    },
                    color = if (s == OrbitState.ERROR) MaterialTheme.colorScheme.error else foreground,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 24.dp)
                )
                if (s != OrbitState.ERROR && track != null) {
                    Text(
                        "${track.title} · ${track.artist}",
                        color = muted,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp, start = 24.dp, end = 24.dp)
                    )
                }
            }
        }
        Spacer(Modifier.height(24.dp))

        // Who you are: avatar initial + name, in one pill.
        Row(
            Modifier
                .fillMaxWidth()
                .pageCardSurface(RoundedCornerShape(26.dp), card, Color.Transparent)
                .padding(start = 14.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(accent.copy(alpha = .18f)),
                contentAlignment = Alignment.Center
            ) {
                val initial = state.myDisplayName.trim().take(1).uppercase()
                if (initial.isNotEmpty()) Text(initial, color = accent, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                else Icon(Icons.Default.Person, null, tint = accent)
            }
            TextField(
                value = state.myDisplayName,
                onValueChange = onName,
                singleLine = true,
                label = { Text(str(R.string.display_name)) },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                    focusedTextColor = foreground, unfocusedTextColor = foreground
                ),
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(14.dp))

        // Join a friend
        Column(
            Modifier
                .fillMaxWidth()
                .pageCardSurface(RoundedCornerShape(28.dp), card, Color.Transparent)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.AutoMirrored.Filled.Login, null, tint = accent, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text(str(R.string.lt_join_friend), fontSize = 19.sp, fontWeight = FontWeight.Bold, color = foreground)
            }
            RoomCodeBoxes(
                code = code,
                accent = accent,
                foreground = foreground,
                card = card,
                onCodeChange = { code = it },
                onComplete = {
                    if (!busy) {
                        joining = true
                        awaitingRoom = true
                        onJoin(it)
                    }
                },
                enabled = !busy
            )
            Text(str(R.string.lt_code_hint), color = muted, style = MaterialTheme.typography.bodySmall)
            TogetherButton(
                label = str(R.string.lt_join),
                icon = Icons.AutoMirrored.Filled.Login,
                enabled = !busy && code.length >= 4,
                busy = busy && joining,
                palette = palette,
                onClick = {
                    joining = true
                    awaitingRoom = true
                    onJoin(code)
                }
            )
        }
        Spacer(Modifier.height(14.dp))

        // Start a room
        Column(
            Modifier
                .fillMaxWidth()
                .pageCardSurface(RoundedCornerShape(28.dp), card, Color.Transparent)
                .padding(20.dp)
                .animateContentSize(spring(dampingRatio = .85f, stiffness = 500f)),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Radio, null, tint = accent, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(str(R.string.lt_start_room), fontSize = 19.sp, fontWeight = FontWeight.Bold, color = foreground)
                    Text(str(R.string.lt_start_room_hint), color = muted, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (track != null) {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(accent.copy(alpha = .08f)).padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ArtworkCard(url = track.thumbnail, modifier = Modifier.size(44.dp), cornerRadius = 10.dp, contentDescription = track.title)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(track.title, color = foreground, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val more = (queue.size - 1).coerceAtLeast(0)
                        Text(
                            if (more > 0) str(R.string.lt_more_from_queue, more) else track.artist,
                            color = muted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                    EqualizerBars(isPlaying = playing, modifier = Modifier.size(16.dp), color = accent)
                }
            }
            // The room's rules, as switches, right here (as on the old screen).
            Text(str(R.string.room_rules), color = foreground, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            RoomRuleRows(state.hostSettings, onSettings, accent, foreground, muted)
            TogetherButton(
                label = str(R.string.lt_create_room),
                icon = Icons.Default.Add,
                enabled = !busy,
                busy = busy && !joining,
                palette = palette,
                onClick = {
                    joining = false
                    awaitingRoom = true
                    onCreate(track, queue, playing, position)
                }
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** Plain-language summary of a room's rules, for the chips. */
@Composable
private fun ruleSummary(settings: RoomSettings): List<String> {
    val chips = buildList {
        if (settings.guestsCanAddSongs) add(str(R.string.lt_chip_add_songs))
        if (settings.guestsCanControlPlayback) add(str(R.string.lt_chip_control))
        if (settings.guestsCanPlaySongs) add(str(R.string.lt_chip_pick))
        if (settings.requireApproval) add(str(R.string.lt_chip_approval))
    }
    return chips.ifEmpty { listOf(str(R.string.lt_chip_host_only)) }
}

@Composable
private fun TogetherButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean,
    busy: Boolean,
    palette: TogetherPalette,
    modifier: Modifier = Modifier,
    filled: Boolean = true,
    onClick: () -> Unit
) {
    val content = when {
        !enabled -> palette.foreground.copy(alpha = .5f)
        filled -> palette.onAccent
        else -> palette.accent
    }
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().height(54.dp)
            .pageGlassSelection(
                CircleShape, filled,
                when {
                    enabled && filled -> palette.accent
                    filled -> palette.card
                    else -> palette.accent.copy(alpha = .12f)
                },
                palette.accent, enabled
            ),
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.Transparent,
            disabledContainerColor = Color.Transparent,
            disabledContentColor = palette.foreground.copy(alpha = .5f)
        )
    ) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(20.dp), color = content, strokeWidth = 2.dp)
        } else {
            Icon(icon, null, tint = content, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text(label, color = content, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun TogetherRoom(
    uiState: ListenTogetherUiState,
    palette: TogetherPalette,
    onLeaveRoom: () -> Unit,
    onHostSettingsChange: (RoomSettings) -> Unit,
    onAddRecommendationToQueue: (RoomRecommendation) -> Unit,
    onDeclineRecommendation: (RoomRecommendation) -> Unit
) {
    val room = uiState.activeRoom ?: return
    val context = LocalContext.current
    val accent = palette.accent
    val foreground = palette.foreground
    val muted = palette.muted
    val card = palette.card
    val syncedTrack = room.currentTrack
    val orbitMembers = uiState.members.map {
        OrbitMember(it.name.trim().take(1).uppercase().ifEmpty { "?" }, parseAvatarColor(it.avatarColorHex, accent), it.isHost)
    }
    val guests = uiState.members.filterNot { it.isHost }
    val slow = uiState.members.any { it.hasSlowConnection }
    val inSync = syncedTrack == null || guests.all { it.readyForTrackId == null || it.readyForTrackId == syncedTrack.id }
    val warn = Color(0xFFFFB74D)

    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        TogetherOrbit(
            artworkUrl = syncedTrack?.thumbnail,
            contentDescription = syncedTrack?.title,
            state = OrbitState.LIVE,
            isPlaying = room.isPlaying,
            accent = accent,
            trackColor = card,
            members = orbitMembers,
            size = 236.dp
        )
        Spacer(Modifier.height(18.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 24.dp)) {
            Text(
                syncedTrack?.title ?: str(R.string.lt_waiting_for_song),
                color = foreground,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f, fill = false)
            )
            if (syncedTrack != null) {
                Spacer(Modifier.width(8.dp))
                EqualizerBars(isPlaying = room.isPlaying, modifier = Modifier.size(16.dp), color = accent)
            }
        }
        Text(
            syncedTrack?.artist ?: str(R.string.lt_hosted_by_x, room.hostName),
            color = muted,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp, start = 24.dp, end = 24.dp)
        )
        Spacer(Modifier.height(20.dp))

        // At a glance
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TogetherStatusTile(
                label = str(R.string.lt_role),
                value = str(if (uiState.isHost) R.string.lt_host else R.string.lt_guest),
                icon = if (uiState.isHost) Icons.Default.Radio else Icons.Default.Person,
                emphasis = if (uiState.isHost) accent else null,
                foreground = foreground, card = card,
                modifier = Modifier.weight(1f)
            )
            TogetherStatusTile(
                label = str(R.string.lt_listening),
                value = uiState.members.size.toString(),
                icon = Icons.Default.Groups,
                emphasis = null,
                foreground = foreground, card = card,
                modifier = Modifier.weight(1f)
            )
            TogetherStatusTile(
                label = str(R.string.lt_sync),
                value = str(
                    when {
                        slow -> R.string.lt_slow_link
                        inSync -> R.string.lt_in_sync
                        else -> R.string.lt_syncing
                    }
                ),
                icon = Icons.Default.Check,
                emphasis = if (!slow && inSync) accent else warn,
                foreground = foreground, card = card,
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(14.dp))

        // Room code + invite
        Column(
            Modifier
                .fillMaxWidth()
                .pageCardSurface(RoundedCornerShape(28.dp), card, Color.Transparent)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(str(R.string.lt_room_code), color = muted, fontWeight = FontWeight.SemiBold)
            RoomCodeBoxes(code = room.code, accent = accent, foreground = foreground, card = card)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TogetherButton(
                    label = str(R.string.copy),
                    icon = Icons.Default.ContentCopy,
                    enabled = true, busy = false, palette = palette, filled = false,
                    modifier = Modifier.weight(1f)
                ) {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Room Code", room.code))
                    Toast.makeText(context, str(R.string.room_code_copied_x, room.code), Toast.LENGTH_SHORT).show()
                }
                TogetherButton(
                    label = str(R.string.lt_invite),
                    icon = Icons.Default.Share,
                    enabled = true, busy = false, palette = palette,
                    modifier = Modifier.weight(1f)
                ) {
                    val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(android.content.Intent.EXTRA_TEXT, str(R.string.lt_invite_message, room.code))
                    }
                    context.startActivity(
                        android.content.Intent.createChooser(send, str(R.string.lt_invite))
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }
        }
        Spacer(Modifier.height(14.dp))

        val cardBorder = Color.Transparent
        if (uiState.isHost) {
            RoomRulesCard(
                settings = uiState.hostSettings,
                onChange = onHostSettingsChange,
                surfaceColor = card,
                borderColor = cardBorder,
                primaryColor = accent,
                textColor = foreground,
                secondaryTextColor = muted
            )
            val requests = uiState.recommendations.filter { it.status == "pending" }
            if (uiState.hostSettings.requireApproval && requests.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                SongRequestsCard(
                    requests = requests,
                    onApprove = onAddRecommendationToQueue,
                    onDecline = onDeclineRecommendation,
                    surfaceColor = card,
                    borderColor = cardBorder,
                    primaryColor = accent,
                    textColor = foreground,
                    secondaryTextColor = muted
                )
            }
        } else {
            GuestPermissionsCard(
                settings = room.settings,
                surfaceColor = card,
                borderColor = cardBorder,
                textColor = foreground,
                secondaryTextColor = muted
            )
        }
        Spacer(Modifier.height(14.dp))

        // Everyone in the room
        Column(
            Modifier
                .fillMaxWidth()
                .pageCardSurface(RoundedCornerShape(28.dp), card, Color.Transparent)
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("${str(R.string.lt_listeners)} · ${uiState.members.size}", fontWeight = FontWeight.Bold, color = foreground, fontSize = 17.sp)
            uiState.members.sortedByDescending { it.isHost }.forEach { member ->
                val color = parseAvatarColor(member.avatarColorHex, accent)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(38.dp).clip(CircleShape).background(color),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(member.name.trim().take(1).uppercase().ifEmpty { "?" }, color = Color.White, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(member.name, color = foreground, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val ready = syncedTrack != null && member.readyForTrackId == syncedTrack.id
                        Text(
                            when {
                                member.hasSlowConnection -> str(R.string.lt_slow_link)
                                member.isHost -> str(R.string.lt_host)
                                ready -> str(R.string.lt_in_sync)
                                else -> str(R.string.lt_guest)
                            },
                            color = if (member.hasSlowConnection) warn else muted,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    if (member.id == uiState.currentUserId) {
                        Box(
                            Modifier.clip(CircleShape).background(foreground.copy(alpha = .1f)).padding(horizontal = 10.dp, vertical = 4.dp)
                        ) { Text(str(R.string.lt_you), color = foreground, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
                        Spacer(Modifier.width(6.dp))
                    }
                    if (member.isHost) {
                        Box(
                            Modifier.clip(CircleShape).background(accent).padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text(
                                str(R.string.lt_host).uppercase(),
                                color = MaterialTheme.colorScheme.onPrimary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(18.dp))

        OutlinedButton(
            onClick = onLeaveRoom,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = CircleShape,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
        ) {
            Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (uiState.isHost) str(R.string.end_broadcast_session) else str(R.string.leave_room), fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(24.dp))
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
            .pageCardSurface(RoundedCornerShape(20.dp), surfaceColor, borderColor)
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
            .pageCardSurface(RoundedCornerShape(20.dp), surfaceColor, primaryColor.copy(alpha = 0.35f))
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
                        colors = ButtonDefaults.buttonColors(containerColor = pageGlassButtonFill(primaryColor)),
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
            .pageCardSurface(RoundedCornerShape(20.dp), surfaceColor, borderColor)
            .padding(18.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(str(R.string.what_you_can_do), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = textColor)
            lines.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = secondaryTextColor) }
        }
    }
}
