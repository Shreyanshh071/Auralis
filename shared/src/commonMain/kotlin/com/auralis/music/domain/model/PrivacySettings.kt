package com.auralis.music.domain.model

data class PrivacySettings(
    val pauseListenHistory: Boolean = false,
    val pauseSearchHistory: Boolean = false,
    val disableScreenshot: Boolean = false
)
