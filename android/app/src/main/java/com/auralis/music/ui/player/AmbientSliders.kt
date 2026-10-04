package com.auralis.music.ui.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextAlign
import androidx.core.content.ContextCompat
import com.auralis.music.R
import com.auralis.music.ui.i18n.str
import com.auralis.music.ui.theme.LocalReducedMotion
import kotlin.math.roundToInt

/** Stable 48dp touch target; only the drawn bar thickens while the finger is down. */
@Composable
private fun AmbientBar(
    value: () -> Float,
    label: String,
    vertical: Boolean = false,
    enabled: Boolean = true,
    onChange: (Float) -> Unit,
    onFinish: () -> Unit,
    onCancel: () -> Unit = onFinish,
    modifier: Modifier = Modifier
) {
    var touching by remember { mutableStateOf(false) }
    val reducedMotion = LocalReducedMotion.current
    val thickness by animateDpAsState(
        if (vertical) (if (touching) 28.dp else 18.dp) else (if (touching) 16.dp else 10.dp),
        tween(if (reducedMotion) 0 else 180), label = "ambientBarThickness"
    )
    val change by rememberUpdatedState(onChange)
    val finish by rememberUpdatedState(onFinish)
    val cancel by rememberUpdatedState(onCancel)
    Canvas(modifier.semantics {
        contentDescription = label
        progressBarRangeInfo = ProgressBarRangeInfo(value().coerceIn(0f, 1f), 0f..1f)
        if (enabled) setProgress { change(it.coerceIn(0f, 1f)); finish(); true } else disabled()
    }.pointerInput(vertical, enabled) {
        if (!enabled) return@pointerInput
        awaitEachGesture {
            val down = awaitFirstDown()
            touching = true
            down.consume()
            val gestureSize = size
            fun fraction(point: Offset): Float = if (vertical) {
                (1f - point.y / gestureSize.height.coerceAtLeast(1)).coerceIn(0f, 1f)
            } else (point.x / gestureSize.width.coerceAtLeast(1)).coerceIn(0f, 1f)
            change(fraction(down.position))
            var released = false
            try {
                while (true) {
                    val event = awaitPointerEvent()
                    val pointer = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (pointer.isConsumed) break
                    change(fraction(pointer.position))
                    pointer.consume()
                    if (!pointer.pressed) { released = true; break }
                }
            } finally {
                touching = false
                if (released) finish() else cancel()
            }
        }
    }) {
        val width = thickness.toPx()
        val fraction = value().coerceIn(0f, 1f)
        val trackSize = if (vertical) Size(width, size.height) else Size(size.width, width)
        val origin = if (vertical) Offset((size.width - width) / 2f, 0f) else Offset(0f, (size.height - width) / 2f)
        val radius = CornerRadius(width / 2f)
        drawRoundRect(Color.White.copy(alpha = 0.32f), origin, trackSize, radius)
        drawRoundRect(Color.White.copy(alpha = 0.5f), origin, trackSize, radius, style = Stroke(0.8.dp.toPx()))
        if (fraction > 0f) {
            // Keep the cap circular even when the played portion is shorter than the bar thickness.
            val fillLength = if (vertical) {
                (size.height * fraction).coerceAtLeast(width).coerceAtMost(size.height)
            } else {
                (size.width * fraction).coerceAtLeast(width).coerceAtMost(size.width)
            }
            val fillSize = if (vertical) Size(width, fillLength) else Size(fillLength, width)
            val fillOrigin = if (vertical) origin.copy(y = size.height - fillLength) else origin
            drawRoundRect(Color.White.copy(alpha = 0.95f), fillOrigin, fillSize, radius)
        }
    }
}

private fun ambientTime(ms: Long): String {
    val seconds = ms.coerceAtLeast(0L) / 1000L
    return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
}

@Composable
internal fun AmbientSeekBar(position: State<Long>, duration: Long, trackId: String?, onSeek: (Long) -> Unit) {
    var preview by remember(trackId) { mutableStateOf<Float?>(null) }
    val length = duration.coerceAtLeast(0L)
    val progress = { preview ?: if (length > 0) (position.value.toFloat() / length).coerceIn(0f, 1f) else 0f }
    Row(Modifier.fillMaxWidth().height(32.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(ambientTime(preview?.let { (it * length).toLong() } ?: position.value), modifier = Modifier.width(44.dp), textAlign = TextAlign.End, maxLines = 1, color = Color.White.copy(alpha = 0.75f), fontSize = 11.sp)
        Spacer(Modifier.width(8.dp))
        AmbientBar(progress, str(R.string.ambient_seek), enabled = length > 0,
            onChange = { preview = it },
            onFinish = { preview?.let { onSeek((it * length).toLong()) }; preview = null },
            onCancel = { preview = null }, modifier = Modifier.weight(1f).fillMaxHeight())
        Spacer(Modifier.width(8.dp))
        Text(ambientTime(length), modifier = Modifier.width(44.dp), maxLines = 1, color = Color.White.copy(alpha = 0.75f), fontSize = 11.sp)
    }
}

@Composable
internal fun AmbientVolumeBar(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val audio = remember(context) { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val max = remember(audio) { audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1) }
    var volume by remember { mutableFloatStateOf(audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max) }
    DisposableEffect(context, audio) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max
            }
        }
        val filter = IntentFilter("android.media.VOLUME_CHANGED_ACTION").apply { addAction("android.media.STREAM_DEVICES_CHANGED_ACTION") }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { context.unregisterReceiver(receiver) }
    }
    AmbientBar({ volume }, str(R.string.volume), vertical = true,
        onChange = { volume = it; audio.setStreamVolume(AudioManager.STREAM_MUSIC, (it * max).roundToInt(), 0) },
        onFinish = {}, modifier = modifier)
}
