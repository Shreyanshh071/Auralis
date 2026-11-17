package com.auralis.music.ui.theme

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.auralis.music.domain.model.AppearanceSettings
import com.auralis.music.ui.player.PlayerBackgroundStyle

/**
 * "Reduce effects": the appearance the app actually draws with, when the user asked for lighter
 * visuals (always, or only while battery saver is on).
 *
 * It only swaps the expensive parts for their cheapest look-alikes; layout, colours, fonts and
 * every other choice stay the same. The saved settings are never touched: settings screens read
 * the store directly, so switching this off brings every original choice straight back.
 */
fun AppearanceSettings.withReducedEffects(): AppearanceSettings {
    val calmPlayer = when (PlayerBackgroundStyle.fromKey(playerBackgroundStyle)) {
        // Already cheap: keep as chosen.
        PlayerBackgroundStyle.FOLLOW_THEME, PlayerBackgroundStyle.GRADIENT -> playerBackgroundStyle
        // Full-screen blurs, shaders and per-frame animations become the static artwork gradient.
        else -> PlayerBackgroundStyle.GRADIENT.displayName
    }
    val miniKey = miniPlayerBackgroundStyle
    val calmMini = when {
        miniKey.trim().equals("Frosted blur", ignoreCase = true) -> PlayerBackgroundStyle.GRADIENT.displayName
        else -> when (PlayerBackgroundStyle.fromKey(miniKey)) {
            PlayerBackgroundStyle.FOLLOW_THEME, PlayerBackgroundStyle.GRADIENT -> miniKey
            else -> PlayerBackgroundStyle.GRADIENT.displayName
        }
    }
    return copy(
        liquidGlass = false,
        standardLyricsBlur = false,
        enableGlowingLyricsEffect = false,
        animatedCovers = false,
        playerBackgroundStyle = calmPlayer,
        miniPlayerBackgroundStyle = calmMini
    )
}

/** True while Android's battery saver is on; follows changes live. */
@Composable
fun rememberBatterySaverOn(): Boolean {
    val context = LocalContext.current.applicationContext
    val power = remember(context) { context.getSystemService(Context.POWER_SERVICE) as? PowerManager }
    var on by remember { mutableStateOf(power?.isPowerSaveMode == true) }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                on = power?.isPowerSaveMode == true
            }
        }
        ContextCompat.registerReceiver(
            context, receiver, IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }
    return on
}

/** The settings to draw with: reduced when the user asked for it, otherwise exactly as saved. */
@Composable
fun AppearanceSettings.effectiveForDrawing(): AppearanceSettings {
    val batterySaver = if (reduceEffectsOnBatterySaver && !reduceEffects) rememberBatterySaverOn() else false
    val reduce = reduceEffects || batterySaver
    return remember(this, reduce) { if (reduce) withReducedEffects() else this }
}
