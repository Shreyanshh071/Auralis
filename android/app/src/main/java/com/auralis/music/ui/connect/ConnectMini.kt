package com.auralis.music.ui.connect

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
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
 * [accent]. Tapping it opens the device picker (play/pause stays on its own button).
 */
@Composable
fun ConnectOutputSubtitle(connect: MiniConnect, accent: Color, fontSize: TextUnit, style: TextStyle = TextStyle.Default) {
    val line = connect.outputLine ?: return
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.clickable(role = Role.Button, onClickLabel = str(R.string.connect_to_a_device), onClick = connect.onOpen)
    ) {
        Icon(connectDeviceIcon(connect.outputType), null, tint = accent, modifier = Modifier.size((fontSize.value + 1).dp))
        Spacer(Modifier.width(4.dp))
        Text(line, style = style, color = accent, fontSize = fontSize, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * The full player's device bar: shown while another device has the audio (or a handoff is under
 * way), "Playing on Laptop" / "Connecting to Laptop…". Tapping it opens the device picker.
 */
@Composable
fun ConnectPlayerBar(accent: Color, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val ui by AuralisConnect.ui.collectAsState()
    if (!ui.enabled) return
    val t = ui.transfer
    val output = ui.output
    val text = when {
        t != null && !t.failed -> str(R.string.connect_connecting_to, t.label)
        t != null -> str(R.string.connect_failed_to, t.label)
        else -> connectOutputLine(output)
    } ?: return
    val type = ui.devices.firstOrNull { it.device.id == (t?.deviceId ?: output?.deviceId) }?.device?.type ?: output?.type ?: ConnectDeviceType.PHONE
    val tint = if (t?.failed == true) Color(0xFFFF8A80) else connectAccent(accent)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(Color.Black.copy(alpha = 0.35f))
            .clickable(role = Role.Button, onClickLabel = str(R.string.connect_to_a_device), onClick = onOpen)
            .padding(horizontal = 14.dp, vertical = 7.dp)
    ) {
        Icon(connectDeviceIcon(type), null, tint = tint, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, color = tint, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
