package com.auralis.music.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.UnstableApi
import com.auralis.music.R
import com.auralis.music.data.service.AuralisAudioPlayer
import com.auralis.music.data.service.PlaybackSignal
import com.auralis.music.data.service.SignalSource
import com.auralis.music.ui.glass.*
import com.auralis.music.ui.i18n.str
import com.auralis.music.ui.theme.*
import kotlinx.coroutines.delay
import java.util.Locale

// The player's maxBufferMs: a full meter is everything it will hold ahead.
private const val BufferCapacityMs = 50_000L
private const val BufferTicks = 28

/** Stats for nerds: a live readout of the audio on its way from the source to the speaker. */
@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlaybackStatsSheet(onDismiss: () -> Unit) {
    val visualState = LocalPlayerModalVisualState.current
    DisposableEffect(visualState) {
        visualState?.opened()
        onDispose { visualState?.closed() }
    }
    val context = LocalContext.current
    val player = remember { AuralisAudioPlayer.getInstance(context) }
    var signal by remember { mutableStateOf<PlaybackSignal?>(null) }
    LaunchedEffect(player) {
        while (true) {
            signal = player.playbackSignal()
            delay(500L)
        }
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val glass = LocalLiquidGlass.current
    val pageView = LocalView.current
    val foreground = if (glass != null) glassContentColor(glass.isDark) else MaterialTheme.dynamicOnSurface
    val accent = if (glass != null) foreground else MaterialTheme.dynamicPrimary
    val shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    val maxHeight = (LocalConfiguration.current.screenHeightDp * .72f).dp
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = if (glass != null) Color.Transparent else MaterialTheme.dynamicSurface,
        contentColor = foreground,
        shape = shape,
        dragHandle = null
    ) {
        Box(Modifier.fillMaxWidth().heightIn(max = maxHeight).clip(shape)) {
            if (glass != null) Box(Modifier.matchParentSize().graphicsLayer()
                .liquidGlassDialog(glass, shape, pageView, showEdgeHighlight = false))
            Column(Modifier.fillMaxWidth().graphicsLayer().navigationBarsPadding()
                .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
                Box(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 20.dp), contentAlignment = Alignment.Center) {
                    Box(Modifier.width(36.dp).height(4.dp).clip(CircleShape).background(foreground.copy(alpha = .35f)))
                }
                Text(str(R.string.stats_for_nerds), color = foreground, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(16.dp))
                signal?.let { current ->
                    SignalHeadline(current.kbps?.toString() ?: "--", "kbps", foreground)
                    Text(
                        listOfNotNull(sourceName(current.source), current.codec).joinToString(" · "),
                        color = accent, fontSize = 14.sp, fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(18.dp))
                    BufferMeter(current.bufferedMs, foreground, accent)
                    Spacer(Modifier.height(18.dp))
                    ReadoutGrid(readouts(current), foreground)
                }
                Spacer(Modifier.height(20.dp))
            }
        }
    }
}

@Composable
private fun SignalHeadline(value: String, unit: String, color: Color) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(value, color = color, fontSize = 46.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        Text(unit, color = color.copy(alpha = .6f), fontSize = 14.sp,
            fontFamily = FontFamily.Monospace, modifier = Modifier.padding(start = 6.dp, bottom = 10.dp))
    }
}

/** Ticks rather than a bar, so it reads as the seconds held ahead of the playhead. */
@Composable
private fun BufferMeter(bufferedMs: Long, foreground: Color, accent: Color) {
    val filled = (bufferedMs.coerceIn(0L, BufferCapacityMs) * BufferTicks / BufferCapacityMs).toInt()
    Column {
        Row(Modifier.fillMaxWidth()) {
            Text(str(R.string.stats_buffered), color = foreground.copy(alpha = .6f), fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text(String.format(Locale.US, "%.1f s", bufferedMs / 1000f), color = foreground, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            repeat(BufferTicks) { tick ->
                Box(Modifier.weight(1f).height(14.dp).clip(RoundedCornerShape(2.dp))
                    .background(if (tick < filled) accent else foreground.copy(alpha = .1f)))
            }
        }
    }
}

@Composable
private fun ReadoutGrid(rows: List<Pair<String, String>>, foreground: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { (label, value) ->
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(14.dp))
                        .background(foreground.copy(alpha = .06f)).padding(horizontal = 12.dp, vertical = 10.dp)) {
                        Text(label, color = foreground.copy(alpha = .6f), fontSize = 11.sp, maxLines = 1)
                        Spacer(Modifier.height(2.dp))
                        Text(value, color = foreground, fontSize = 13.sp, fontFamily = FontFamily.Monospace,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun sourceName(source: SignalSource): String? = when (source) {
    SignalSource.NOT_STARTED -> null
    SignalSource.YOUTUBE -> "YouTube"
    SignalSource.JIOSAAVN -> "JioSaavn"
    SignalSource.DOWNLOAD -> str(R.string.stats_source_download)
    SignalSource.OTHER -> str(R.string.stats_source_other)
}

@Composable
private fun readouts(signal: PlaybackSignal): List<Pair<String, String>> = listOfNotNull(
    signal.sampleRateHz?.let { str(R.string.stats_sample_rate) to String.format(Locale.US, "%.1f kHz", it / 1000f) },
    signal.channels?.let { str(R.string.stats_channels) to it.toString() },
    signal.outputBits?.let { str(R.string.stats_output) to it },
    signal.outputDevice?.let { str(R.string.stats_device) to it },
    signal.networkKbps?.let { str(R.string.stats_network) to "$it kbps" },
    signal.codec?.let { str(R.string.stats_underruns) to signal.underruns.toString() },
    signal.speed.takeIf { it != 1f }?.let { str(R.string.stats_speed) to String.format(Locale.US, "%.2fx", it) },
    signal.itag?.let { str(R.string.stats_itag) to it.toString() },
    signal.decoder?.let { str(R.string.stats_decoder) to it },
    signal.mediaId?.let { str(R.string.stats_media_id) to it }
)
