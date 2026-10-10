package com.auralis.music.ui.components

import android.view.HapticFeedbackConstants
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView

/** The player slider (shared [AuralisSlider]) with the phone's haptic tick. */
@Composable
fun AuralisPlayerSlider(
    value: Float, // 0f..1f
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    isPlaying: Boolean,
    currentPosMs: Long,
    totalDurationMs: Long,
    modifier: Modifier = Modifier,
    sliderStyle: String = "Wavy",
    activeTrackColor: Color = Color.White,
    inactiveTrackColor: Color = Color.White.copy(alpha = 0.28f),
    thumbColor: Color = Color.White,
    textColor: Color = Color.White.copy(alpha = 0.65f),
    /** Timestamps under the track; off for decorative previews (e.g. the style picker). */
    showTimestamps: Boolean = true
) {
    val view = LocalView.current
    AuralisSlider(
        value = value,
        onValueChange = onValueChange,
        onValueChangeFinished = onValueChangeFinished,
        isPlaying = isPlaying,
        currentPosMs = currentPosMs,
        totalDurationMs = totalDurationMs,
        modifier = modifier,
        sliderStyle = sliderStyle,
        activeTrackColor = activeTrackColor,
        inactiveTrackColor = inactiveTrackColor,
        thumbColor = thumbColor,
        textColor = textColor,
        showTimestamps = showTimestamps,
        onTick = { view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }
    )
}
