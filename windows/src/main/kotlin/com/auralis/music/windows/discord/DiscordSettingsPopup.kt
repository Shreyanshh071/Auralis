package com.auralis.music.windows.discord

import com.auralis.music.ui.i18n.desktopText

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.UnfoldMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.auralis.music.domain.model.Track
import com.auralis.music.ui.components.IconButton
import com.auralis.music.ui.components.OutlinedButton
import com.auralis.music.ui.components.Switch
import com.auralis.music.ui.components.TextButton
import com.auralis.music.ui.components.clickable
import com.auralis.music.windows.LiquidPopupSurface
import com.auralis.music.windows.settings.settingJumpTarget
import com.auralis.music.windows.home.HomeArtwork

/**
 * In-app Discord settings modal dialog with liquid glass theme.
 * Sits directly inside the application without opening an OS window.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun DiscordSettingsPopup(controller: WindowsDiscordPresence?, onDismiss: () -> Unit) {
    val settings by DiscordCardPreferences.store.settings.collectAsState()
    val now = controller?.now?.collectAsState()?.value ?: DiscordNow()
    val status = controller?.status?.collectAsState()?.value ?: "Connected to Discord desktop"

    val windowSize = LocalWindowInfo.current.containerSize
    val density = LocalDensity.current
    val screenWidth = with(density) { windowSize.width.toDp() }
    val screenHeight = with(density) { windowSize.height.toDp() }

    val position = remember {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect, windowSize: IntSize,
                layoutDirection: LayoutDirection, popupContentSize: IntSize
            ) = IntOffset.Zero
        }
    }

    Popup(
        popupPositionProvider = position,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true)
    ) {
        Box(
            modifier = Modifier.size(screenWidth, screenHeight),
            contentAlignment = Alignment.Center
        ) {
            // Translucent backdrop scrim dismissing on click
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(Color.Black.copy(alpha = 0.58f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onDismiss
                    )
            )

            val dialogWidth = minOf(940.dp, screenWidth - 32.dp)
            val dialogHeight = minOf(700.dp, screenHeight - 32.dp)

            LiquidPopupSurface(
                modifier = Modifier
                    .width(dialogWidth)
                    .height(dialogHeight)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {}
                    )
                    .onPreviewKeyEvent { event ->
                        if (event.key == Key.Escape && event.type == KeyEventType.KeyUp) {
                            onDismiss()
                            true
                        } else false
                    },
                tint = Color(0xFF141216).copy(alpha = 0.82f),
                frosted = true
            ) {
                DiscordSettingsContent(
                    cfg = settings,
                    now = now,
                    status = status,
                    update = { change -> DiscordCardPreferences.store.update(change) },
                    reset = { DiscordCardPreferences.store.reset() },
                    dismiss = onDismiss
                )
            }
        }
    }
}

@Composable
internal fun DiscordSettingsContent(
    cfg: DiscordCardSettings,
    now: DiscordNow,
    status: String,
    update: ((DiscordCardSettings) -> DiscordCardSettings) -> Unit,
    reset: () -> Unit,
    dismiss: () -> Unit,
    embedded: Boolean = false
) {
    val labels = mapOf(
        "title" to "Song title",
        "artist" to "Artist",
        "album" to "Album",
        "artist_album" to "Artist and album",
        "off" to "Off",
        "app" to "Auralis",
        "listen" to "Listen on YouTube Music",
        "line1" to "Line 1",
        "line2" to "Line 2"
    )

    Column(Modifier.fillMaxSize()) {
        if (!embedded) {
        // Window Title & Close Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = desktopText("Discord"),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = desktopText("How your Rich Presence card looks."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = dismiss) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = desktopText("Close"),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
        }

        // Main 2-Column Content Area
        BoxWithConstraints(Modifier.weight(1f)) {
            val wide = maxWidth >= 820.dp
            Row(Modifier.fillMaxSize()) {
                // Left Column: Interactive Settings
                // A plain scrolling column (about twenty rows), so Settings search can scroll to any of them.
                Column(
                    modifier = Modifier
                        .weight(1.15f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 22.dp, vertical = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    run {
                        DiscordToggleCard(
                            title = "Rich Presence",
                            description = "Show the currently playing song on your Discord profile.",
                            checked = cfg.enabled
                        ) { update { s -> s.copy(enabled = it) } }
                    }

                    run {
                        Text(
                            text = status,
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                    }

                    run {
                        DiscordToggleCard(
                            title = "Show when paused",
                            description = "Keep the card up while the track is paused. Discord has no paused state, so the progress bar is dropped rather than left running.",
                            checked = cfg.showPaused
                        ) { update { s -> s.copy(showPaused = it) } }
                    }

                    run {
                        DiscordToggleCard(
                            title = "Hide details",
                            description = "Say only that music is playing. No title, artist, album, artwork, progress bar or buttons leave your machine.",
                            checked = cfg.hideDetails
                        ) { update { s -> s.copy(hideDetails = it) } }
                    }

                    if (!cfg.hideDetails) {
                        run { SectionHeader("STATUS") }

                        run {
                            DiscordDropdownRow(
                                title = "Status text",
                                description = "What Discord shows next to your name in the member list. The profile card below always shows the app name.",
                                value = cfg.statusLine,
                                options = listOf("app", "line1", "line2"),
                                labels = labels + mapOf(
                                    "app" to "App name",
                                    "line1" to "Line 1 · ${labels[cfg.line1]}",
                                    "line2" to "Line 2 · ${labels[cfg.line2]}"
                                )
                            ) { update { s -> s.copy(statusLine = it) } }
                        }

                        run {
                            OutlinedTextField(
                                value = cfg.appName,
                                onValueChange = { value -> update { it.copy(appName = value.take(128)) } },
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                label = { Text(desktopText("App name")) },
                                placeholder = { Text(desktopText("Auralis")) },
                                supportingText = { Text(desktopText("Replaces \"Auralis\" wherever the app name appears. Leave empty to keep it.")) },
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp)
                            )
                        }

                        run { SectionHeader("ACTIVITY") }

                        run {
                            DiscordDropdownRow(
                                title = "Line 1",
                                description = null,
                                value = cfg.line1,
                                options = listOf("title", "artist", "album"),
                                labels = labels
                            ) { update { s -> s.copy(line1 = it) } }
                        }

                        run {
                            DiscordDropdownRow(
                                title = "Line 2",
                                description = null,
                                value = cfg.line2,
                                options = listOf("artist", "artist_album", "album", "title", "off"),
                                labels = labels
                            ) { update { s -> s.copy(line2 = it) } }
                        }

                        run { SectionHeader("ARTWORK") }

                        run {
                            DiscordToggleCard(
                                title = "Album artwork",
                                description = "Show the current song's cover.",
                                checked = cfg.cover
                            ) { update { s -> s.copy(cover = it) } }
                        }

                        if (cfg.cover) {
                            run {
                                DiscordToggleCard(
                                    title = "Provider badge",
                                    description = "Add a small Auralis icon to the artwork.",
                                    checked = cfg.badge
                                ) { update { s -> s.copy(badge = it) } }
                            }

                            run {
                                DiscordDropdownRow(
                                    title = "Line 3 / artwork hover text",
                                    description = null,
                                    value = cfg.line3,
                                    options = listOf("album", "artist", "title", "off"),
                                    labels = labels
                                ) { update { s -> s.copy(line3 = it) } }
                            }
                        }

                        run {
                            DiscordToggleCard(
                                title = "Progress bar",
                                description = "Show elapsed and remaining time while playing.",
                                checked = cfg.progress
                            ) { update { s -> s.copy(progress = it) } }
                        }

                        run { SectionHeader("CLICKABLE") }

                        run {
                            Text(
                                text = desktopText("Links follow the selected content. Album lines open the album, artist lines open the artist. Links are omitted for local files or missing catalogue IDs."),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 4.dp)
                            )
                        }

                        run {
                            DiscordToggleCard(
                                title = "Line 1 is clickable",
                                description = "",
                                checked = cfg.linkLine1
                            ) { update { s -> s.copy(linkLine1 = it) } }
                        }

                        run {
                            DiscordToggleCard(
                                title = "Line 2 is clickable",
                                description = "",
                                checked = cfg.linkLine2
                            ) { update { s -> s.copy(linkLine2 = it) } }
                        }

                        if (cfg.cover) {
                            run {
                                DiscordToggleCard(
                                    title = "Album art is clickable",
                                    description = "Opens the album, or the song when no album link is available.",
                                    checked = cfg.linkCover
                                ) { update { s -> s.copy(linkCover = it) } }
                            }
                        }

                        run { SectionHeader("BUTTONS") }

                        run {
                            DiscordDropdownRow(
                                title = "Button 1",
                                description = null,
                                value = cfg.button1,
                                options = listOf("listen", "album", "artist", "app", "off"),
                                labels = labels + ("app" to "Get Auralis")
                            ) { update { s -> s.copy(button1 = it) } }
                        }

                        run {
                            DiscordDropdownRow(
                                title = "Button 2",
                                description = null,
                                value = cfg.button2,
                                options = listOf("listen", "album", "artist", "app", "off"),
                                labels = labels + ("app" to "Get Auralis")
                            ) { update { s -> s.copy(button2 = it) } }
                        }
                    }

                    if (!wide) {
                        run {
                            Spacer(Modifier.height(12.dp))
                            DiscordCardPreviewPanel(cfg, now, status)
                        }
                    }

                    run {
                        TextButton(
                            onClick = reset,
                            modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
                        ) {
                            Icon(Icons.Outlined.Refresh, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(desktopText("Reset card to defaults"))
                        }
                    }
                }

                if (wide) {
                    VerticalDivider(color = Color.White.copy(alpha = 0.08f))

                    // Right Column: Live Interactive Discord Preview Panel
                    Column(
                        modifier = Modifier
                            .width(360.dp)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState())
                            .padding(22.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        DiscordCardPreviewPanel(cfg, now, status)

                        Spacer(Modifier.weight(1f))

                        TextButton(
                            onClick = reset,
                            modifier = Modifier.align(Alignment.Start)
                        ) {
                            Icon(Icons.Outlined.Refresh, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(desktopText("Reset to defaults"))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = desktopText(text),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
        modifier = Modifier.padding(top = 14.dp, bottom = 4.dp)
    )
}

@Composable
private fun DiscordToggleCard(
    title: String,
    description: String,
    checked: Boolean,
    change: (Boolean) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = Color.White.copy(alpha = 0.05f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.06f)),
        modifier = Modifier.fillMaxWidth().settingJumpTarget(title)
    ) {
        Column(
            Modifier
                .clickable { change(!checked) }
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = desktopText(title),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                Switch(checked = checked, onCheckedChange = change)
            }
            if (description.isNotEmpty()) {
                Text(
                    text = desktopText(description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, end = 40.dp)
                )
            }
        }
    }
}

@Composable
private fun DiscordDropdownRow(
    title: String,
    description: String?,
    value: String,
    options: List<String>,
    labels: Map<String, String>,
    change: (String) -> Unit
) {
    var open by remember { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = Color.White.copy(alpha = 0.05f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.06f)),
        modifier = Modifier.fillMaxWidth().settingJumpTarget(title)
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = desktopText(title),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Box {
                    OutlinedButton(
                        onClick = { open = true },
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(
                            text = desktopText(labels[value] ?: value),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.Outlined.UnfoldMore, null, Modifier.size(16.dp))
                    }

                    com.auralis.music.windows.GlassDropdownMenu(open, { open = false }, frosted = true) {
                        options.forEach { option ->
                            com.auralis.music.windows.GlassDropdownMenuItem(
                                { Text(desktopText(labels[option] ?: option)) },
                                { open = false; change(option) },
                                selected = option == value
                            )
                        }
                    }
                }
            }

            if (!description.isNullOrBlank()) {
                Text(
                    text = desktopText(description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

/**
 * Live Discord Rich Presence card preview accurately recreating Discord's card & member list.
 */
@Composable
private fun DiscordCardPreviewPanel(cfg: DiscordCardSettings, now: DiscordNow, status: String) {
    // Default preview fallback track when nothing is actively playing
    val fallbackTrack = remember {
        DiscordTrack(
            Track(
                id = "sBzrzS1Ag_g",
                title = "New Person, Same Old Mistakes",
                artist = "Tame Impala",
                album = "Currents",
                thumbnail = "https://lh3.googleusercontent.com/uG5HkU2YtL1200",
                duration = 364L
            )
        )
    }

    val activeTrack = now.track ?: fallbackTrack
    val isPlaying = if (now.track != null) now.playing else true
    val positionMs = if (now.track != null) now.position else 142000L
    val durationMs = if (now.track != null) now.duration else 364000L

    // Preview Header with "Now playing" or "Paused" pill badge
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = desktopText("PREVIEW"),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Surface(
            shape = RoundedCornerShape(50),
            color = if (isPlaying) MaterialTheme.colorScheme.primary.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.12f),
            border = BorderStroke(1.dp, if (isPlaying) MaterialTheme.colorScheme.primary.copy(alpha = 0.4f) else Color.White.copy(alpha = 0.18f))
        ) {
            Text(
                text = desktopText(if (isPlaying) "Now playing" else "Paused"),
                style = MaterialTheme.typography.labelSmall,
                color = if (isPlaying) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.75f),
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
            )
        }
    }

    // Profile Card Preview
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = desktopText("Profile card"),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        val appDisplayName = cfg.appName.trim().ifBlank { "Auralis" }.take(128)
        val payload = discordActivity(activeTrack, cfg.copy(enabled = true, showPaused = true), isPlaying, true, positionMs, durationMs)

        Surface(
            shape = RoundedCornerShape(14.dp),
            color = Color(0xFF1E1F22), // Official Discord dark card color
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // Header with "Listening to Auralis" and "•••"
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Listening to $appDisplayName",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFFDBDEE1)
                    )
                    Text(
                        text = "•••",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFF949BA4)
                    )
                }

                // Album Art & Metadata Rows
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (cfg.cover) {
                        Box(Modifier.size(64.dp)) {
                            HomeArtwork(
                                url = activeTrack.track.thumbnail,
                                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp))
                            )
                            if (cfg.badge) {
                                Surface(
                                    modifier = Modifier.size(20.dp).align(Alignment.BottomEnd),
                                    shape = CircleShape,
                                    color = Color(0xFF1E1F22),
                                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.2f))
                                ) {
                                    HomeArtwork(
                                        url = "https://auralis-self-nu.vercel.app/logo.png",
                                        modifier = Modifier.fillMaxSize().clip(CircleShape)
                                    )
                                }
                            }
                        }
                    }

                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        val line1Val = activeTrack.text(cfg.line1) ?: activeTrack.track.title
                        Text(
                            text = line1Val,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )

                        if (cfg.line2 != "off") {
                            val line2Val = activeTrack.text(cfg.line2) ?: activeTrack.track.artist
                            Text(
                                text = line2Val,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFDBDEE1),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        if (cfg.line3 != "off") {
                            val line3Val = activeTrack.text(cfg.line3) ?: activeTrack.track.album.orEmpty()
                            if (line3Val.isNotBlank()) {
                                Text(
                                    text = line3Val,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFF949BA4),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }

                // Progress Bar
                if (cfg.progress) {
                    val progressRatio = (positionMs.toFloat() / durationMs.coerceAtLeast(1L)).coerceIn(0f, 1f)
                    LinearProgressIndicator(
                        progress = { progressRatio },
                        modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                        color = Color.White,
                        trackColor = Color(0xFF383A40)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = formatDiscordTime(positionMs),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF949BA4)
                        )
                        Text(
                            text = formatDiscordTime(durationMs),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF949BA4)
                        )
                    }
                }

                // Discord Action Buttons
                val buttons = listOfNotNull(
                    if (cfg.button1 != "off") resolveButtonLabel(cfg.button1) else null,
                    if (cfg.button2 != "off") resolveButtonLabel(cfg.button2) else null
                ).distinct()

                buttons.forEach { label ->
                    Surface(
                        modifier = Modifier.fillMaxWidth().height(32.dp),
                        shape = RoundedCornerShape(4.dp),
                        color = Color(0xFF4E5058)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                color = Color.White
                            )
                        }
                    }
                }
            }
        }
    }

    // Member List Preview
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = desktopText("Member list"),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        val memberStatusText = when (cfg.statusLine) {
            "app" -> cfg.appName.trim().ifBlank { "Auralis" }.take(128)
            "line1" -> activeTrack.text(cfg.line1) ?: activeTrack.track.title
            else -> activeTrack.text(cfg.line2) ?: activeTrack.track.artist
        }

        Surface(
            shape = RoundedCornerShape(10.dp),
            color = Color(0xFF1E1F22),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Member Avatar
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(
                                listOf(Color(0xFF5865F2), Color(0xFFEB459E))
                            )
                        )
                )

                Column {
                    Text(
                        text = desktopText("You"),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("🎵", fontSize = 11.sp)
                        Text(
                            text = memberStatusText,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF949BA4),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }

    // Footnote Notes & Status
    if (!cfg.enabled) {
        Text(
            text = desktopText("Rich Presence is off, so this is not on your profile. The preview still follows your changes."),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    } else if (!now.playing && !cfg.showPaused) {
        Text(
            text = desktopText("Paused: hidden from your Discord profile. Enable Show when paused to keep it visible."),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun resolveButtonLabel(kind: String): String = when (kind) {
    "listen" -> "Listen on YouTube Music"
    "album" -> "View album"
    "artist" -> "View artist"
    "app" -> "Get Auralis"
    else -> "Auralis"
}

private fun formatDiscordTime(ms: Long): String {
    val totalSeconds = (ms.coerceAtLeast(0L) / 1000L)
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return "$minutes:${seconds.toString().padStart(2, '0')}"
}
