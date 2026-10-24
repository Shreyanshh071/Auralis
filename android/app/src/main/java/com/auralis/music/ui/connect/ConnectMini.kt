package com.auralis.music.ui.connect

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.luminance
import androidx.compose.material3.MaterialTheme
import com.auralis.music.ui.theme.dynamicSurface
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
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
fun ConnectPlayerBar(accent: Color, onOpen: () -> Unit, modifier: Modifier = Modifier, hazeState: HazeState? = null) {
    val ui by AuralisConnect.ui.collectAsState()
    if (!shouldShowPlayerDevicePill(ui)) return
    val t = ui.transfer?.takeUnless { it.failed }
    val output = ui.output
    val text = when {
        t != null && !t.failed -> str(R.string.connect_connecting_to, t.label)
        t != null -> str(R.string.connect_failed_to, t.label)
        else -> connectOutputLine(output) ?: str(R.string.connect_to_a_device)
    }
    val type = ui.devices.firstOrNull { it.device.id == (t?.deviceId ?: output?.deviceId) }?.device?.type ?: output?.type ?: ConnectDeviceType.PHONE
    val tint = if (t?.failed == true) Color(0xFFFF8A80) else connectAccent(accent)
    val shape = RoundedCornerShape(32.dp)
    val surface = MaterialTheme.dynamicSurface
    val dark = surface.luminance() < 0.5f
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .shadow(10.dp, shape, ambientColor = Color.Black.copy(alpha = if (dark) .35f else .12f),
                spotColor = Color.Black.copy(alpha = if (dark) .45f else .18f))
            .clip(shape)
            .then(if (hazeState != null) Modifier.hazeEffect(
                state = hazeState,
                style = HazeStyle(backgroundColor = Color.Transparent,
                    tint = HazeTint(surface.copy(alpha = if (dark) .38f else .48f)),
                    blurRadius = 24.dp, noiseFactor = .02f)
            ) { blurEnabled = true } else Modifier.background(surface.copy(alpha = .48f)))
            .border(1.dp, Brush.verticalGradient(listOf(tint.copy(alpha = .50f),
                tint.copy(alpha = .20f), tint.copy(alpha = .08f))), shape)
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
