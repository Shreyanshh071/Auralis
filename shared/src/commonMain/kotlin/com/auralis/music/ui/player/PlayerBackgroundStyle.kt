package com.auralis.music.ui.player

/**
 * Player Background Style Options for both Mini-Player and Full Player.
 */
enum class PlayerBackgroundStyle(val displayName: String) {
    FOLLOW_THEME("Follow theme"),
    GRADIENT("Gradient"),
    BLUR("Blur"),
    GLOW_MOTION("Glow motion"),
    // Mini-player only: real liquid glass on the mini player alone (stored key unchanged).
    APPLE_MUSIC("Liquid glass"),
    LIVE_MESH("Live Mesh");

    companion object {
        fun fromKey(key: String?): PlayerBackgroundStyle {
            return when (key?.trim()?.lowercase()) {
                "follow theme", "follow_theme", "theme" -> FOLLOW_THEME
                "gradient", "dynamic artwork gradient", "artwork tinted", "dynamic artwork tint" -> GRADIENT
                "blur", "frosted glass / blur", "dynamic blurred artwork" -> BLUR
                "glow motion", "glow_motion", "glow" -> GLOW_MOTION
                "apple music", "apple_music", "apple liquid glass", "liquid glass", "apple glass", "glass" -> APPLE_MUSIC
                "live mesh", "live_mesh", "mesh" -> LIVE_MESH
                "pure black", "solid amoled black", "dark black" -> FOLLOW_THEME
                else -> GRADIENT
            }
        }
    }
}
