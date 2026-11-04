package com.auralis.music.ui.connect

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import com.auralis.music.R
import com.auralis.music.data.connect.AuralisConnect
import com.auralis.music.data.connect.ConnectDeviceType
import com.auralis.music.ui.i18n.str

/** What the mini player shows of Auralis Connect. */
data class MiniConnect(
    /** "Playing on Laptop" while another device is the output; null when this phone is. */
    val outputLine: String?,
    val outputType: ConnectDeviceType,
    /** Whether to show the device button (signed in, and something to connect to or from). */
    val showButton: Boolean,
    val onOpen: () -> Unit
)

/** The artwork colour, lifted so "Playing on …" stands out from the artist line on any mini player. */
fun connectAccent(artwork: Color): Color = androidx.compose.ui.graphics.lerp(artwork, Color.White, 0.45f)

@Composable
fun rememberMiniConnect(onOpen: () -> Unit): MiniConnect? {
    val ui by AuralisConnect.ui.collectAsState()
    if (!ui.enabled) return null
    val output = ui.output
    val line = connectOutputLine(output)
    val showButton = line != null || ui.otherDevicesOnline
    if (!showButton) return null
    return MiniConnect(line, output?.takeIf { !it.isThis }?.type ?: ui.me?.type ?: ConnectDeviceType.PHONE, showButton, onOpen)
}

/**
 * The mini player's second line while another device plays: its icon and "Playing on …", in
 * [accent]. This is informational; the mini player's device button opens the picker.
 */
@Composable
fun ConnectOutputSubtitle(connect: MiniConnect, accent: Color, fontSize: TextUnit, style: TextStyle = TextStyle.Default) {
    val line = connect.outputLine ?: return
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
    ) {
        Icon(connectDeviceIcon(connect.outputType), null, tint = accent, modifier = Modifier.size((fontSize.value + 1).dp))
        Spacer(Modifier.width(4.dp))
        Text(line, style = style, color = accent, fontSize = fontSize, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * Shows an active remote output or an in-progress connection, rather than an idle prompt.
 */
@Composable
fun ConnectPlayerBar(accent: Color, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val ui by AuralisConnect.ui.collectAsState()
    if (!shouldShowPlayerDevicePill(ui)) return
    val transfer = ui.transfer?.takeUnless { it.failed }
    val output = ui.output
    val label = if (transfer != null) str(R.string.connect_connecting_to, transfer.label)
        else connectOutputLine(output) ?: str(R.string.connect_to_a_device)
    val type = ui.devices.firstOrNull { it.device.id == (transfer?.deviceId ?: output?.deviceId) }
        ?.device?.type ?: output?.type ?: ConnectDeviceType.PHONE
    val tint = connectAccent(accent)
    val shape = RoundedCornerShape(18.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.padding(horizontal = 24.dp).widthIn(max = 320.dp)
            .clip(shape)
            .background(Color.Black.copy(alpha = .24f))
            .border(1.dp, tint.copy(alpha = .16f), shape)
            .padding(horizontal = 4.dp, vertical = 2.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f, fill = false).clip(shape)
                .clickable(role = Role.Button, onClickLabel = str(R.string.connect_to_a_device), onClick = onOpen)
                .heightIn(min = 32.dp).padding(horizontal = 8.dp)
        ) {
            if (transfer != null) {
                CircularProgressIndicator(Modifier.size(14.dp), color = tint, strokeWidth = 2.dp)
            } else {
                Icon(connectDeviceIcon(type), null, tint = tint, modifier = Modifier.size(16.dp))
            }
            Spacer(Modifier.width(6.dp))
            Text(label, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        val me = ui.me
        if (ui.canPlayHere && me != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clip(shape)
                    .background(tint.copy(alpha = .12f))
                    .clickable(role = Role.Button) { AuralisConnect.transferTo(me.id) }
                    .heightIn(min = 32.dp).padding(horizontal = 8.dp)
            ) {
                Icon(connectDeviceIcon(me.type), null, tint = tint, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text(str(if (me.type == ConnectDeviceType.TABLET) R.string.connect_this_tablet else R.string.connect_this_phone),
                    color = tint, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 104.dp))
            }
        }
    }
}

/** The device button: the output's icon (accented while another device plays). */
@Composable
fun ConnectDeviceButtonIcon(connect: MiniConnect, tint: Color, accent: Color, modifier: Modifier = Modifier) {
    Icon(
        connectDeviceIcon(connect.outputType),
        contentDescription = str(R.string.connect_to_a_device),
        tint = if (connect.outputLine != null) accent else tint,
        modifier = modifier
    )
}
