package com.auralis.music.ui.connect

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Speaker
import androidx.compose.material.icons.rounded.TabletAndroid
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auralis.music.R
import com.auralis.music.data.connect.AuralisConnect
import com.auralis.music.data.connect.ConnectDeviceRow
import com.auralis.music.data.connect.ConnectDeviceType
import com.auralis.music.data.connect.ConnectOutput
import com.auralis.music.data.connect.ConnectRowState
import com.auralis.music.data.connect.ConnectUi
import com.auralis.music.ui.i18n.str

fun connectDeviceIcon(type: ConnectDeviceType): ImageVector = when (type) {
    ConnectDeviceType.PHONE -> Icons.Rounded.PhoneAndroid
    ConnectDeviceType.TABLET -> Icons.Rounded.TabletAndroid
    ConnectDeviceType.COMPUTER -> Icons.Rounded.Computer
    ConnectDeviceType.SPEAKER -> Icons.Rounded.Speaker
    ConnectDeviceType.BROWSER -> Icons.Rounded.Language
}

/** "This phone" / "This tablet" for this device, the device's own name for others. */
fun connectLabel(row: ConnectDeviceRow): String = if (row.isThis) thisDeviceLabel(row.device.type) else row.label

private fun thisDeviceLabel(type: ConnectDeviceType) =
    if (type == ConnectDeviceType.TABLET) str(R.string.connect_this_tablet) else str(R.string.connect_this_phone)

/** "Playing on Laptop" / "Paused on Laptop" / "Last played on Laptop"; null when this phone is the output. */
fun connectOutputLine(output: ConnectOutput?): String? {
    if (output == null || output.isThis) return null
    return when {
        !output.active -> str(R.string.connect_last_played_on, output.label)
        output.playing -> str(R.string.connect_playing_on, output.label)
        else -> str(R.string.connect_paused_on, output.label)
    }
}

/**
 * "Connect to a device": every device signed in to this account, this phone first, the current
 * output ticked. Picking one moves the audio there; opening and closing the sheet changes nothing.
 */
@Composable
fun ConnectDevicePickerSheet(
    onDismiss: () -> Unit,
    accentColor: Color = MaterialTheme.colorScheme.primary,
    onOpenPhoneOutput: (() -> Unit)? = null
) {
    val ui by AuralisConnect.ui.collectAsState()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val accent by animateColorAsState(liftedAccent(accentColor), label = "connectAccent")
    val sheetBg = remember(accent) { tinted(accent, 0.08f) }
    val cardBg = remember(accent) { tinted(accent, 0.13f) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = sheetBg,
        dragHandle = {
            Box(
                Modifier.padding(top = 12.dp, bottom = 10.dp).size(width = 36.dp, height = 4.dp)
                    .clip(CircleShape).background(Color.White.copy(alpha = 0.28f))
            )
        },
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 6.dp)) {
            Text(
                str(R.string.connect_to_a_device),
                color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            ConnectStatusLine(ui, accent)
            Spacer(Modifier.height(14.dp))
            if (!ui.enabled) {
                Text(str(R.string.connect_sign_in), color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp,
                    modifier = Modifier.padding(vertical = 12.dp))
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().heightIn(max = 460.dp)
                ) {
                    items(ui.devices.filter { it.isThis || it.state != ConnectRowState.UNAVAILABLE }, key = { it.device.id }) { row ->
                        DeviceRow(row, ui, accent, cardBg) { AuralisConnect.transferTo(row.device.id) }
                    }
                }
                if (!ui.otherDevicesOnline) {
                    Text(str(R.string.connect_no_other_devices), color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp,
                        modifier = Modifier.padding(top = 12.dp))
                }
            }
            if (onOpenPhoneOutput != null) {
                Spacer(Modifier.height(12.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                        .clickable(role = Role.Button, onClick = onOpenPhoneOutput)
                        .padding(horizontal = 6.dp, vertical = 12.dp)
                ) {
                    Icon(Icons.AutoMirrored.Rounded.VolumeUp, null, tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(str(R.string.connect_phone_sound_output), color = Color.White.copy(alpha = 0.85f), fontSize = 14.sp)
                }
            }
            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun ConnectStatusLine(ui: ConnectUi, accent: Color) {
    val t = ui.transfer
    val text = when {
        t != null && !t.failed -> str(R.string.connect_connecting_to, t.label)
        t != null -> str(R.string.connect_failed_to, t.label)
        ui.output?.isThis == true -> if (ui.output?.playing == true) str(R.string.connect_playing_on_this_device)
            else str(R.string.connect_current_output)
        else -> connectOutputLine(ui.output)
    } ?: return
    Text(text, color = if (t?.failed == true) Color(0xFFFF8A80) else accent, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun DeviceRow(row: ConnectDeviceRow, ui: ConnectUi, accent: Color, cardBg: Color, onSelect: () -> Unit) {
    val enabled = row.selectable && !row.isOutput
    val subtitle = when {
        row.state == ConnectRowState.CONNECTING -> str(R.string.connect_connecting)
        row.state == ConnectRowState.FAILED -> str(R.string.connect_couldnt_connect)
        row.isThis && !row.device.canPlay -> str(R.string.connect_cant_play)
        row.isThis -> row.label
        row.state == ConnectRowState.UNAVAILABLE && !row.device.canPlay -> str(R.string.connect_cant_play)
        row.state == ConnectRowState.UNAVAILABLE -> if (row.device.lastSeenMs > 0L)
            str(R.string.connect_offline_seen, android.text.format.DateUtils.getRelativeTimeSpanString(
                row.device.lastSeenMs, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS).toString())
            else str(R.string.connect_unavailable)
        row.isOutput && ui.output?.active == true -> if (ui.output?.playing == true) str(R.string.connect_playing) else str(R.string.connect_paused)
        else -> null
    }
    val contentAlpha = if (row.state == ConnectRowState.UNAVAILABLE) 0.45f else 1f
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(cardBg)
            .border(1.dp, if (row.isOutput) accent.copy(alpha = 0.45f) else Color.White.copy(alpha = 0.06f), RoundedCornerShape(18.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onSelect)
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(if (row.isOutput) accent.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.08f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(connectDeviceIcon(row.device.type), null, tint = (if (row.isOutput) accent else Color.White).copy(alpha = contentAlpha), modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(connectLabel(row), color = (if (row.isOutput) accent else Color.White).copy(alpha = contentAlpha),
                fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, color = Color.White.copy(alpha = 0.6f * contentAlpha), fontSize = 12.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        when {
            row.state == ConnectRowState.CONNECTING -> CircularProgressIndicator(Modifier.size(20.dp), color = accent, strokeWidth = 2.dp)
            row.isOutput -> Icon(Icons.Rounded.Check, str(R.string.connect_current_output), tint = accent, modifier = Modifier.size(22.dp))
        }
    }
}

/** Keeps the artwork's hue but bright enough to read on the dark sheet (as the sound output sheet does). */
private fun liftedAccent(raw: Color): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(raw.toArgb(), hsv)
    return if (hsv[1] < 0.08f) Color.hsv(0f, 0f, hsv[2].coerceAtLeast(0.88f))
    else Color.hsv(hsv[0], hsv[1].coerceIn(0.35f, 0.80f), hsv[2].coerceAtLeast(0.88f))
}

private fun tinted(accent: Color, value: Float): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(accent.toArgb(), hsv)
    return if (hsv[1] < 0.08f) Color.hsv(0f, 0f, value) else Color.hsv(hsv[0], (hsv[1] * 0.16f).coerceIn(0.04f, 0.12f), value)
}
