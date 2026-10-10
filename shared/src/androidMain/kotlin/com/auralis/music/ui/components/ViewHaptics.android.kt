package com.auralis.music.ui.components

import android.view.HapticFeedbackConstants
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView

@Composable
actual fun rememberViewHaptics(): ViewHaptics {
    val view = LocalView.current
    return remember(view) {
        object : ViewHaptics {
            override fun tick() { view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK) }
            override fun longPress() { view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) }
        }
    }
}
