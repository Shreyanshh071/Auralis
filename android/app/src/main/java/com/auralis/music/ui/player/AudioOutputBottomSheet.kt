package com.auralis.music.ui.player

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.auralis.music.data.service.AuralisAudioPlayer
import com.auralis.music.domain.model.AudioQuality
import com.auralis.music.ui.components.tactileBounce
import kotlin.math.roundToInt

// ── VIVI-EXACT COLOR PALETTE ──
private val ViviSheetBg = Color(0xFF191517)
private val ViviCardBg = Color(0xFF251F23)
private val ViviCardBorder = Color.White.copy(alpha = 0.05f)
private val ViviAccentPink = Color(0xFFF7B5BE)
private val ViviAccentDarkText = Color(0xFF2B141B)
private val ViviBadgeBg = Color(0xFF392D32)
private val ViviVolumeTrackBg = Color(0xFF4C3039)
private val ViviVolumeFillGradientStart = Color(0xFF7A4A57)
private val ViviVolumeFillGradientEnd = Color(0xFF8D5564)

/**
 * Audio Output & Device Switcher Bottom Sheet with 100% ViVi Parity.
 *
 * Matches ViVi reference video:
 * 1. Connected audio card:
 *    - Bluetooth: Circular battery gauge (100%), Bluetooth icon + device name (e.g. "OnePlus Buds 4"), "Connected" badge, expandable chevron.
 *    - Phone speaker: Flower squircle speaker badge, "Phone speaker", "Connected" badge.
 *    - Interactive 2-way routing: tapping "This phone" switches audio to phone speaker and updates the card.
 *      Tapping the Bluetooth device in the dropdown routes audio back to Bluetooth.
 * 2. Volume card: Speaker icon + "Volume" + percentage badge ("26%"), thick pill track + pastel pink scalloped thumb.
 * 3. Audio Quality card: "Audio Quality" header + 3-segment pill selector (Auto | High | Low) with solid pink active pill.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioOutputBottomSheet(
    currentQuality: AudioQuality,
    onAudioQualityChange: (AudioQuality) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val audioPlayer = remember { AuralisAudioPlayer.getInstance(context) }
    val isSpeakerForced by audioPlayer.isSpeakerForced.collectAsState()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // ── 1. AUDIO DEVICE & BATTERY DETECTION ──
    var connectedDeviceName by remember { mutableStateOf<String?>(null) }
    var isBluetoothConnected by remember { mutableStateOf(false) }
    var batteryPercentage by remember { mutableStateOf<Int?>(null) }
    var isDropdownExpanded by remember { mutableStateOf(false) }

    fun queryBluetoothBattery(): Int? {
        try {
            val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager
            val adapter = btManager?.adapter ?: return null
            if (!adapter.isEnabled) return null

            val getBatteryMethod = try {
                BluetoothDevice::class.java.getMethod("getBatteryLevel")
            } catch (_: Throwable) { null }

            if (getBatteryMethod != null) {
                for (device in adapter.bondedDevices) {
                    try {
                        val level = getBatteryMethod.invoke(device) as? Int
                        if (level != null && level in 0..100) {
                            return level
                        }
                    } catch (_: Throwable) {}
                }
            }
        } catch (_: Throwable) {}
        return null
    }

    fun refreshAudioDevices() {
        try {
            val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            val btOutput = outputs.firstOrNull { device ->
                device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                device.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                device.type == AudioDeviceInfo.TYPE_BLE_SPEAKER ||
                device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            }

            if (btOutput != null) {
                isBluetoothConnected = true
                val name = btOutput.productName?.toString()?.trim()
                connectedDeviceName = if (!name.isNullOrBlank()) name else "Bluetooth Audio"
                if (batteryPercentage == null) {
                    batteryPercentage = queryBluetoothBattery()
                }
            } else {
                val wired = outputs.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                    it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                    it.type == AudioDeviceInfo.TYPE_USB_HEADSET
                }
                if (wired != null) {
                    isBluetoothConnected = false
                    connectedDeviceName = wired.productName?.toString()?.takeIf { it.isNotBlank() } ?: "Wired Headphones"
                    batteryPercentage = null
                } else {
                    isBluetoothConnected = false
                    connectedDeviceName = null
                    batteryPercentage = null
                }
            }
        } catch (_: Throwable) {
            isBluetoothConnected = false
            connectedDeviceName = null
            batteryPercentage = null
        }
    }

    fun switchAudioRoute(toSpeaker: Boolean) {
        audioPlayer.routeAudio(toSpeaker)
    }

    DisposableEffect(context) {
        refreshAudioDevices()

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                when (intent?.action) {
                    "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED" -> {
                        val level = intent.getIntExtra("android.bluetooth.device.extra.BATTERY_LEVEL", -1)
                        if (level in 0..100) {
                            batteryPercentage = level
                        }
                    }
                    AudioManager.ACTION_AUDIO_BECOMING_NOISY,
                    "android.media.STREAM_DEVICES_CHANGED_ACTION",
                    BluetoothDevice.ACTION_ACL_CONNECTED,
                    BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                        refreshAudioDevices()
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction("android.bluetooth.device.action.BATTERY_LEVEL_CHANGED")
            addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
            addAction("android.media.STREAM_DEVICES_CHANGED_ACTION")
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        context.registerReceiver(receiver, filter)

        onDispose {
            try {
                context.unregisterReceiver(receiver)
            } catch (_: Throwable) {}
        }
    }

    // ── 2. SYSTEM VOLUME DETECTION & SYNC ──
    val maxVolume = remember {
        audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).toFloat().coerceAtLeast(1f)
    }
    var systemVolumeFraction by remember {
        mutableFloatStateOf(
            (audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maxVolume).coerceIn(0f, 1f)
        )
    }

    DisposableEffect(context) {
        val volumeReceiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                if (intent?.action == "android.media.VOLUME_CHANGED_ACTION") {
                    val cur = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()
                    systemVolumeFraction = (cur / maxVolume).coerceIn(0f, 1f)
                }
            }
        }
        val filter = IntentFilter("android.media.VOLUME_CHANGED_ACTION")
        context.registerReceiver(volumeReceiver, filter)
        onDispose {
            try {
                context.unregisterReceiver(volumeReceiver)
            } catch (_: Throwable) {}
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = ViviSheetBg,
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(top = 12.dp, bottom = 10.dp)
                    .size(width = 36.dp, height = 4.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.28f))
            )
        },
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 6.dp)
        ) {
            // ── SECTION 1: CONNECTED DEVICE & EXPANDABLE ROUTE SELECTOR ──
            val isBtActive = isBluetoothConnected && !isSpeakerForced
            val deviceTitle = if (isBtActive) {
                connectedDeviceName ?: "Bluetooth Audio"
            } else if (!isBluetoothConnected && connectedDeviceName != null) {
                connectedDeviceName!!
            } else {
                "Phone speaker"
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(22.dp))
                    .background(ViviCardBg)
                    .border(1.dp, ViviCardBorder, RoundedCornerShape(22.dp))
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .tactileBounce(scaleDown = 0.98f, onClick = { isDropdownExpanded = !isDropdownExpanded }),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Left Badge / Indicator:
                    // If Bluetooth is active and battery level is known -> Circular pink battery gauge
                    // If Bluetooth is active but battery is unknown -> Headphones icon in pink badge
                    // If Phone speaker is active -> Speaker icon badge
                    if (isBtActive && batteryPercentage != null) {
                        CircularBatteryGauge(
                            percentage = batteryPercentage!!,
                            accentColor = ViviAccentPink,
                            modifier = Modifier.size(48.dp)
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(ViviBadgeBg),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (isBtActive || (!isBluetoothConnected && connectedDeviceName != null)) Icons.Default.Headphones else Icons.Default.Speaker,
                                contentDescription = null,
                                tint = if (isBtActive) ViviAccentPink else Color.White,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(14.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (isBtActive) {
                                Icon(
                                    imageVector = Icons.Default.Bluetooth,
                                    contentDescription = null,
                                    tint = ViviAccentPink,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                            }
                            Text(
                                text = deviceTitle,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontSize = 16.sp
                            )
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        // "Connected" Pill Badge
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(ViviBadgeBg)
                                .padding(horizontal = 10.dp, vertical = 3.dp)
                        ) {
                            Text(
                                text = "Connected",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White.copy(alpha = 0.85f),
                                fontSize = 11.sp
                            )
                        }
                    }

                    Icon(
                        imageVector = if (isDropdownExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = "Expand devices",
                        tint = Color.White.copy(alpha = 0.70f),
                        modifier = Modifier.size(24.dp)
                    )
                }

                // Expandable audio route switcher list
                AnimatedVisibility(
                    visible = isDropdownExpanded,
                    enter = fadeIn(tween(180)) + expandVertically(tween(220)),
                    exit = fadeOut(tween(120)) + shrinkVertically(tween(180))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 14.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(Color.White.copy(alpha = 0.08f))
                        )
                        Spacer(modifier = Modifier.height(10.dp))

                        // Route 1: Connected Bluetooth device (if available)
                        if (isBluetoothConnected) {
                            AudioRouteRow(
                                title = connectedDeviceName ?: "Bluetooth Device",
                                subtitle = "Active bluetooth route",
                                icon = Icons.Default.Bluetooth,
                                isSelected = !isSpeakerForced,
                                onClick = {
                                    switchAudioRoute(toSpeaker = false)
                                    isDropdownExpanded = false
                                }
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                        }

                        // Route 2: This Phone
                        AudioRouteRow(
                            title = "This phone",
                            subtitle = "Internal device speaker",
                            icon = Icons.Default.PhoneAndroid,
                            isSelected = isSpeakerForced || !isBluetoothConnected,
                            onClick = {
                                switchAudioRoute(toSpeaker = true)
                                isDropdownExpanded = false
                            }
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        // Route 3: System Media Output Switcher panel
                        AudioRouteRow(
                            title = "System audio output",
                            subtitle = "Switch output via Android system panel",
                            icon = Icons.Default.Settings,
                            isSelected = false,
                            onClick = {
                                isDropdownExpanded = false
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                    try {
                                        val panelIntent = Intent("com.android.settings.panel.action.MEDIA_OUTPUT").apply {
                                            putExtra("com.android.settings.panel.extra.PACKAGE_NAME", context.packageName)
                                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                        }
                                        context.startActivity(panelIntent)
                                        return@AudioRouteRow
                                    } catch (_: Throwable) {}
                                }
                                try {
                                    context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                    })
                                } catch (_: Throwable) {}
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // ── SECTION 2: VOLUME CONTROL CARD ──
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(22.dp))
                    .background(ViviCardBg)
                    .border(1.dp, ViviCardBorder, RoundedCornerShape(22.dp))
                    .padding(16.dp)
            ) {
                val volumePercent = (systemVolumeFraction * 100f).roundToInt()

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                            contentDescription = "Volume",
                            tint = Color.White.copy(alpha = 0.85f),
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Volume",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            fontSize = 15.sp
                        )
                    }

                    // Percentage Badge Pill
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(ViviBadgeBg)
                            .padding(horizontal = 10.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = "$volumePercent%",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White.copy(alpha = 0.85f),
                            fontSize = 12.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Custom thick tactile volume pill slider with pastel pink thumb
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(36.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(ViviVolumeTrackBg)
                        .pointerInput(maxVolume) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                down.consume()
                                val fraction = (down.position.x / size.width).coerceIn(0f, 1f)
                                systemVolumeFraction = fraction
                                val targetStep = (fraction * maxVolume).roundToInt()
                                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetStep, 0)

                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull() ?: break
                                    if (!change.pressed) break
                                    change.consume()
                                    val dragFraction = (change.position.x / size.width).coerceIn(0f, 1f)
                                    systemVolumeFraction = dragFraction
                                    val dragStep = (dragFraction * maxVolume).roundToInt()
                                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, dragStep, 0)
                                }
                            }
                        },
                    contentAlignment = Alignment.CenterStart
                ) {
                    val trackWidth = maxWidth
                    val fillWidth = trackWidth * systemVolumeFraction

                    // Filled active bar with warm gradient
                    Box(
                        modifier = Modifier
                            .width(fillWidth)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(18.dp))
                            .background(
                                Brush.horizontalGradient(
                                    listOf(
                                        ViviVolumeFillGradientStart,
                                        ViviVolumeFillGradientEnd
                                    )
                                )
                            )
                    )

                    // Scalloped / Tactile pastel pink thumb handle
                    if (fillWidth > 18.dp) {
                        Box(
                            modifier = Modifier
                                .offset(x = (fillWidth - 26.dp).coerceAtLeast(4.dp))
                                .size(24.dp)
                                .shadow(4.dp, CircleShape)
                                .clip(CircleShape)
                                .background(ViviAccentPink)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // ── SECTION 3: AUDIO QUALITY CARD ──
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(22.dp))
                    .background(ViviCardBg)
                    .border(1.dp, ViviCardBorder, RoundedCornerShape(22.dp))
                    .padding(16.dp)
            ) {
                Text(
                    text = "Audio Quality",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    fontSize = 15.sp
                )

                Spacer(modifier = Modifier.height(12.dp))

                // 3-Option Segmented Pill (Auto | High | Low) with solid pink active pill
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .clip(RoundedCornerShape(22.dp))
                        .background(Color.White.copy(alpha = 0.05f))
                        .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(22.dp))
                        .padding(3.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AudioQualitySegment(
                        label = "Auto",
                        isSelected = currentQuality == AudioQuality.AUTO,
                        onClick = { onAudioQualityChange(AudioQuality.AUTO) },
                        modifier = Modifier.weight(1f)
                    )

                    AudioQualitySegment(
                        label = "High",
                        isSelected = currentQuality == AudioQuality.HIGH,
                        onClick = { onAudioQualityChange(AudioQuality.HIGH) },
                        modifier = Modifier.weight(1f)
                    )

                    AudioQualitySegment(
                        label = "Low",
                        isSelected = currentQuality == AudioQuality.LOW,
                        onClick = { onAudioQualityChange(AudioQuality.LOW) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

/**
 * Circular Battery Gauge with pink progress ring and percentage text matching ViVi.
 */
@Composable
private fun CircularBatteryGauge(
    percentage: Int,
    modifier: Modifier = Modifier,
    accentColor: Color = ViviAccentPink
) {
    val progress = (percentage / 100f).coerceIn(0f, 1f)
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(400, easing = FastOutSlowInEasing),
        label = "batteryProgress"
    )

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize().padding(3.dp)) {
            val strokeWidthPx = 3.dp.toPx()
            val diameter = size.minDimension - strokeWidthPx
            val topLeft = Offset(strokeWidthPx / 2f, strokeWidthPx / 2f)
            val arcSize = Size(diameter, diameter)

            // Background track
            drawArc(
                color = accentColor.copy(alpha = 0.22f),
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokeWidthPx, cap = StrokeCap.Round)
            )

            // Filled progress arc
            drawArc(
                color = accentColor,
                startAngle = -90f,
                sweepAngle = 360f * animatedProgress,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokeWidthPx, cap = StrokeCap.Round)
            )
        }

        Text(
            text = "$percentage%",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.ExtraBold,
            color = Color.White,
            fontSize = 11.sp
        )
    }
}

/**
 * Single Segment in the Audio Quality Picker with ViVi's solid pastel pink pill.
 */
@Composable
private fun AudioQualitySegment(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bgAnim by animateColorAsState(
        targetValue = if (isSelected) ViviAccentPink else Color.Transparent,
        animationSpec = tween(180),
        label = "segmentBg"
    )
    val textAnim by animateColorAsState(
        targetValue = if (isSelected) ViviAccentDarkText else Color.White.copy(alpha = 0.70f),
        animationSpec = tween(180),
        label = "segmentText"
    )

    Box(
        modifier = modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(19.dp))
            .background(bgAnim)
            .tactileBounce(scaleDown = 0.94f, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = textAnim,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
            fontSize = 14.sp
        )
    }
}

/**
 * Route row item inside expandable device dropdown.
 */
@Composable
private fun AudioRouteRow(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (isSelected) ViviAccentPink else Color.White.copy(alpha = 0.65f),
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                color = if (isSelected) ViviAccentPink else Color.White,
                fontSize = 14.sp
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.50f),
                fontSize = 12.sp
            )
        }
        if (isSelected) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "Selected",
                tint = ViviAccentPink,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}
