package com.auralis.music.ui.player

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/** Only the onboarding host provides this: real playback keeps its usual controls. */
internal class PlayerDemoControls(val volume: MutableState<Float>, val notice: (String) -> Unit)
internal val LocalPlayerDemoControls = staticCompositionLocalOf<PlayerDemoControls?> { null }

@Composable
internal fun DemoVolumeSlider(demo: PlayerDemoControls, modifier: Modifier = Modifier, alpha: Float = 1f) {
    Row(modifier.graphicsLayer { this.alpha = alpha }, verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.AutoMirrored.Rounded.VolumeDown, null, tint = Color.White.copy(alpha = .6f), modifier = Modifier.size(20.dp))
        Slider(value = demo.volume.value, onValueChange = { demo.volume.value = it }, modifier = Modifier.weight(1f),
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color.White.copy(alpha = .25f)))
        Icon(Icons.AutoMirrored.Rounded.VolumeUp, null, tint = Color.White.copy(alpha = .6f), modifier = Modifier.size(20.dp))
    }
}
