package com.auralis.music.windows.library

import com.auralis.music.ui.i18n.desktopText

import com.auralis.music.ui.components.Button
import com.auralis.music.ui.components.TextButton
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun CreatePlaylistDialog(youtubeAvailable: Boolean, accountName: String?, onDismiss: () -> Unit,
    onCreate: suspend (String, Boolean) -> Unit, onSyncRequested: (() -> Unit)? = null) {
    var name by remember { mutableStateOf("") }
    var remote by remember { mutableStateOf(youtubeAvailable) }
    var busy by remember { mutableStateOf(false) }
    var setupSync by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    com.auralis.music.ui.components.GlassAlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text(desktopText("New playlist")) },
        text = {
            Column(Modifier.width(440.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; error = null },
                    enabled = !busy,
                    singleLine = true,
                    placeholder = {
                        Text(
                            desktopText("Playlist name"),
                            color = Color.White.copy(alpha = 0.65f),
                            fontSize = 14.sp
                        )
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        disabledTextColor = Color.White.copy(alpha = 0.38f),
                        focusedContainerColor = Color.White.copy(alpha = 0.12f),
                        unfocusedContainerColor = Color.White.copy(alpha = 0.08f),
                        disabledContainerColor = Color.White.copy(alpha = 0.03f),
                        focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                        unfocusedBorderColor = Color.White.copy(alpha = 0.28f),
                        disabledBorderColor = Color.White.copy(alpha = 0.08f),
                        cursorColor = Color.White
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                Text(desktopText("Save to"), style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.9f))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    DestinationCard("YouTube Music", accountName ?: "Connect an account first", remote,
                        youtubeAvailable && !busy, Modifier.weight(1f), true) { remote = true }
                    DestinationCard("This device", "Saved in Auralis", !remote, !busy, Modifier.weight(1f), false) { remote = false }
                }
                Text(desktopText(if (remote) "Created privately in your YouTube Music account, available on your other devices."
                    else "Stored on this computer in your Auralis library."), style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.70f))
                if (onSyncRequested != null) Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Checkbox(setupSync, { setupSync = it }, enabled = !busy)
                    Text("Set up two-way playlist sync after creating", color = Color.White, style = MaterialTheme.typography.bodySmall)
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy, colors = ButtonDefaults.textButtonColors(contentColor = Color.White)) { Text(desktopText("Cancel")) } },
        confirmButton = { Button(enabled = name.isNotBlank() && !busy && (!remote || youtubeAvailable), onClick = {
            busy = true; error = null
            scope.launch {
                try { onCreate(name.trim(), remote); onDismiss(); if (setupSync) onSyncRequested?.invoke() }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { error = e.message ?: "Could not create the playlist. Check your library before trying again." }
                finally { busy = false }
            }
        }, colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            disabledContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f),
            disabledContentColor = Color.White.copy(alpha = 0.4f)
        )) { Text(desktopText(if (busy) "Creating…" else "Create playlist")) } })
}

@Composable
private fun DestinationCard(title: String, subtitle: String, selected: Boolean, enabled: Boolean,
    modifier: Modifier, cloud: Boolean, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val shape = RoundedCornerShape(16.dp)
    val primary = MaterialTheme.colorScheme.primary

    val bgAlpha by animateFloatAsState(
        targetValue = when {
            !enabled -> 0.04f
            selected && hovered -> 0.26f
            selected -> 0.20f
            hovered -> 0.14f
            else -> 0.08f
        },
        animationSpec = tween(120),
        label = "destCardBgAlpha"
    )

    val borderStroke = when {
        !enabled -> BorderStroke(1.dp, Color.White.copy(alpha = 0.06f))
        selected -> BorderStroke(1.5.dp, primary.copy(alpha = if (hovered) 0.95f else 0.75f))
        hovered -> BorderStroke(1.dp, Color.White.copy(alpha = 0.38f))
        else -> BorderStroke(1.dp, Color.White.copy(alpha = 0.18f))
    }

    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = shape,
        color = Color.Transparent,
        border = borderStroke,
        interactionSource = interactionSource
    ) {
        Box(
            Modifier
                .background(Color(0xFF101015).copy(alpha = if (enabled) 0.38f else 0.18f))
                .background(
                    if (selected && enabled) primary.copy(alpha = bgAlpha)
                    else Color.White.copy(alpha = bgAlpha)
                )
        ) {
            Box(
                Modifier
                    .matchParentSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.White.copy(alpha = if (selected) 0.20f else if (hovered) 0.12f else 0.08f),
                            0.50f to Color.White.copy(alpha = 0.02f),
                            1f to Color.Transparent
                        )
                    )
            )
            Column(
                Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = if (cloud) Icons.Outlined.Cloud else Icons.Outlined.Computer,
                    contentDescription = null,
                    tint = when {
                        !enabled -> Color.White.copy(alpha = 0.35f)
                        selected -> primary
                        hovered -> Color.White
                        else -> Color.White.copy(alpha = 0.85f)
                    }
                )
                Text(
                    text = desktopText(title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (enabled) Color.White else Color.White.copy(alpha = 0.4f)
                )
                Text(
                    text = desktopText(subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = when {
                        !enabled -> Color.White.copy(alpha = 0.3f)
                        selected -> Color.White.copy(alpha = 0.85f)
                        else -> Color.White.copy(alpha = 0.65f)
                    }
                )
            }
        }
    }
}

