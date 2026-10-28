package com.auralis.music.windows.connect

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Speaker
import androidx.compose.material.icons.rounded.TabletAndroid
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.auralis.music.data.connect.ConnectDeviceRow
import com.auralis.music.data.connect.ConnectDeviceType
import com.auralis.music.data.connect.ConnectOutput
import com.auralis.music.data.connect.ConnectRowState
import com.auralis.music.ui.components.clickable
import com.auralis.music.ui.i18n.desktopText

fun connectIcon(type: ConnectDeviceType): ImageVector = when (type) {
    ConnectDeviceType.PHONE -> Icons.Rounded.PhoneAndroid
    ConnectDeviceType.TABLET -> Icons.Rounded.TabletAndroid
    ConnectDeviceType.COMPUTER -> Icons.Rounded.Computer
    ConnectDeviceType.SPEAKER -> Icons.Rounded.Speaker
    ConnectDeviceType.BROWSER -> Icons.Rounded.Language
}

/** The colour that marks audio playing on another device. */
val ConnectAccent = Color(0xFF8BE3B0)

/** "Playing on Pixel 8" / "Paused on …" / "Last played on …"; null when this PC is the output. */
fun connectOutputLine(output: ConnectOutput?): String? {
    if (output == null || output.isThis) return null
    return when {
        !output.active -> desktopText("Last played on %1\$s", output.label)
        output.playing -> desktopText("Playing on %1\$s", output.label)
        else -> desktopText("Paused on %1\$s", output.label)
    }
}

private fun rowLabel(row: ConnectDeviceRow) = if (row.isThis) desktopText("This computer") else row.label

/**
 * "Connect to a device": every device signed in to this account, this PC first, the current
 * output ticked. Choosing one moves the audio there; opening or closing it changes nothing.
 */
@Composable
fun ConnectDeviceMenu(expanded: Boolean, onDismiss: () -> Unit) {
    val connect = DesktopConnect.current
    DisposableEffect(expanded, connect) {
        connect?.pickerOpen = expanded
        onDispose { connect?.pickerOpen = false }
    }
    // Opens on top of the device button it belongs to, not off to one side over the title.
    com.auralis.music.windows.GlassDropdownMenu(expanded = expanded, onDismissRequest = onDismiss, frosted = true, aboveAnchor = true) {
        com.auralis.music.windows.GlassDropdownMenuHeader(desktopText("Connect to a device"))
        val ui = connect?.ui?.collectAsState()?.value
        if (ui == null || !ui.enabled) {
            com.auralis.music.windows.GlassDropdownMenuItem(text = { Text(desktopText("Sign in to listen across your devices.")) }, onClick = onDismiss, enabled = false)
            return@GlassDropdownMenu
        }
        val t = ui.transfer
        val status = when {
            t != null && !t.failed -> desktopText("Connecting to %1\$s…", t.label)
            t != null -> desktopText("Couldn't connect to %1\$s", t.label)
            ui.output?.isThis == true -> desktopText("Playing on this device")
            else -> connectOutputLine(ui.output)
        }
        if (status != null) Text(status, style = MaterialTheme.typography.bodySmall,
            color = if (t?.failed == true) Color(0xFFFF8A80) else ConnectAccent,
            modifier = Modifier.widthIn(max = 300.dp).padding16(), maxLines = 1, overflow = TextOverflow.Ellipsis)
        ui.devices.filter { it.isThis || it.state != ConnectRowState.UNAVAILABLE }.forEach { row ->
            val statusLine = when {
                row.state == ConnectRowState.CONNECTING -> desktopText("Connecting…")
                row.state == ConnectRowState.FAILED -> desktopText("Couldn't connect. Tap to try again.")
                !row.device.canPlay -> desktopText("Can't play audio")
                row.isThis -> row.label
                row.state == ConnectRowState.UNAVAILABLE -> desktopText("Unavailable")
                row.isOutput && ui.output?.active == true -> if (ui.output?.playing == true) desktopText("Playing") else desktopText("Paused")
                else -> null
            }
            val subtitle = statusLine
            val dim = row.state == ConnectRowState.UNAVAILABLE
            com.auralis.music.windows.GlassDropdownMenuItem(
                text = {
                    Column(Modifier.widthIn(min = 180.dp, max = 260.dp)) {
                        Text(rowLabel(row), maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = if (row.isOutput) ConnectAccent else Color.White.copy(alpha = if (dim) 0.45f else 1f))
                        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, color = Color.White.copy(alpha = if (dim) 0.35f else 0.6f))
                    }
                },
                leadingIcon = {
                    Icon(connectIcon(row.device.type), null, Modifier.size(20.dp),
                        tint = if (row.isOutput) ConnectAccent else Color.White.copy(alpha = if (dim) 0.45f else 0.9f))
                },
                trailingIcon = {
                    when {
                        row.state == ConnectRowState.CONNECTING -> CircularProgressIndicator(Modifier.size(16.dp), color = ConnectAccent, strokeWidth = 2.dp)
                        row.isOutput -> Icon(Icons.Rounded.Check, desktopText("Current device"), Modifier.size(18.dp), tint = ConnectAccent)
                    }
                },
                selected = row.isOutput,
                enabled = row.selectable && !row.isOutput,
                onClick = { connect?.transferTo(row.device.id) }
            )
        }
        if (!ui.otherDevicesOnline) Text(
            desktopText("Open Auralis on another device signed in to this account to see it here."),
            style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.55f),
            modifier = Modifier.widthIn(max = 300.dp).padding16()
        )
    }
}

private fun Modifier.padding16() = this.padding(horizontal = 16.dp, vertical = 4.dp)

/** The bar's "Playing on …" line (opens the picker), shown while another device has the audio. */
@Composable
fun ConnectOutputLabel(showIcon: Boolean = true, interactive: Boolean = true, onOpen: () -> Unit) {
    val ui = DesktopConnect.current?.ui?.collectAsState()?.value ?: return
    val t = ui.transfer
    val text = when {
        t != null && !t.failed -> desktopText("Connecting to %1\$s…", t.label)
        else -> connectOutputLine(ui.output)
    } ?: return
    val type = ui.output?.takeIf { t == null }?.type ?: ui.devices.firstOrNull { it.device.id == t?.deviceId }?.device?.type ?: ConnectDeviceType.COMPUTER
    Row(verticalAlignment = Alignment.CenterVertically,
        modifier = if (interactive) Modifier.clickable(onClick = onOpen) else Modifier) {
        if (showIcon) {
            Icon(connectIcon(type), null, Modifier.size(14.dp), tint = ConnectAccent)
            Spacer(Modifier.width(5.dp))
        }
        Text(text, style = MaterialTheme.typography.bodySmall, color = ConnectAccent, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
