package com.auralis.music.ui.player

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.staticCompositionLocalOf

/** Only the onboarding host provides this: real playback keeps its usual controls. */
internal class PlayerDemoControls(val volume: MutableState<Float>, val notice: (String) -> Unit)
internal val LocalPlayerDemoControls = staticCompositionLocalOf<PlayerDemoControls?> { null }
