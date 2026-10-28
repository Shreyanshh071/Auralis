package com.auralis.music.ui.player

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import com.auralis.music.ui.theme.dynamicSurface
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect

/** Mini-player-only style; kept separate from full-player artwork backgrounds. */
internal object MiniPlayerBackgrounds {
    enum class Surface { PURE_BLACK, LIQUID_GLASS, FROSTED, ARTWORK }
    const val FROSTED = "Frosted blur"
    val options: List<String> get() = PlayerBackgroundStyle.entries.map { it.displayName } + FROSTED
    fun isFrosted(key: String): Boolean = key.trim().equals(FROSTED, ignoreCase = true)
    fun label(key: String): String = if (isFrosted(key)) FROSTED else PlayerBackgroundStyle.fromKey(key).displayName
    /** The global glass theme controls app chrome, never the user's mini-player choice. */
    fun surface(settings: com.auralis.music.domain.model.AppearanceSettings): Surface = when {
        settings.pureBlackMiniPlayer -> Surface.PURE_BLACK
        isFrosted(settings.miniPlayerBackgroundStyle) -> Surface.FROSTED
        PlayerBackgroundStyle.fromKey(settings.miniPlayerBackgroundStyle) == PlayerBackgroundStyle.APPLE_MUSIC -> Surface.LIQUID_GLASS
        else -> Surface.ARTWORK
    }
}

internal val LocalFrostedMiniPlayer = staticCompositionLocalOf { false }

@Composable
internal fun frostedMiniPlayerContentColor(): Color =
    if (MaterialTheme.dynamicSurface.luminance() < .5f) Color.White else Color(0xFF1B1B1F)

/** Real page backdrop, with a quiet tint and grain; no artwork layer or animated shader. */
@Composable
internal fun Modifier.frostedMiniPlayer(state: HazeState?, shape: Shape): Modifier {
    val dark = MaterialTheme.dynamicSurface.luminance() < .5f
    val surface = if (dark) Color(0xFF242326) else Color(0xFFF2F1F4)
    val frost = if (state != null && Build.VERSION.SDK_INT >= 31) {
        Modifier.hazeEffect(state, style = HazeStyle(
            backgroundColor = surface,
            tint = HazeTint(surface.copy(alpha = .66f)),
            blurRadius = 28.dp,
            noiseFactor = .045f
        ))
    } else Modifier.background(surface)
    return clip(shape).then(frost).border(.75.dp,
        if (dark) Color.White.copy(alpha = .15f) else Color.Black.copy(alpha = .10f), shape)
}
