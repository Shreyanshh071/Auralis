package com.auralis.music.data.network

import com.auralis.music.domain.model.ContentSettings

/**
 * Settings → Content as the app currently holds it, read live by network code (hl/gl, proxy).
 * Each app points [current] at its settings store first thing at startup.
 */
object ContentSettingsSource {
    @Volatile var current: () -> ContentSettings = { ContentSettings() }
}
