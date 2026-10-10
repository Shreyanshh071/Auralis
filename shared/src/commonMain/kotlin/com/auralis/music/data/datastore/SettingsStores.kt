package com.auralis.music.data.datastore

import androidx.compose.runtime.staticCompositionLocalOf

/** The app's settings stores, one instance each, for settings screens (the phone's DataStore files; files on desktop). */
interface SettingsStores {
    val settings: SettingsDataStore
    val appearance: AppearanceSettingsDataStore
    val content: ContentSettingsDataStore
    val privacy: PrivacyDataStore
    val storage: StorageDataStore
    val updater: UpdaterDataStore
    val aiTranslation: AiTranslationDataStore
    val discord: DiscordRpcDataStore
}

val LocalSettingsStores = staticCompositionLocalOf<SettingsStores> { error("No SettingsStores provided") }
