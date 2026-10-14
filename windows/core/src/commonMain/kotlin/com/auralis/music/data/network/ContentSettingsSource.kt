package com.auralis.music.data.network

import com.auralis.music.domain.model.ContentSettings

/**
 * Settings → Content as the app currently holds it, read live by network code (hl/gl, proxy).
 * Each app points [current] at its settings store first thing at startup.
 */
object ContentSettingsSource {
    @Volatile var current: () -> ContentSettings = { ContentSettings() }

    /** The same settings as a flow, for UI that redraws when they change. */
    @Volatile var updates: kotlinx.coroutines.flow.StateFlow<ContentSettings> = kotlinx.coroutines.flow.MutableStateFlow(ContentSettings())

    private val _languageChanges = kotlinx.coroutines.flow.MutableStateFlow(0)
    /** Bumped whenever the app language changes, so screens that cache network content can reload. */
    var languageChanges: kotlinx.coroutines.flow.StateFlow<Int> = _languageChanges

    fun notifyLanguageChanged() {
        _languageChanges.value += 1
    }
}
