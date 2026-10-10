package com.auralis.music.desktop.settings

import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.auralis.music.data.datastore.AiTranslationDataStore
import com.auralis.music.data.datastore.AppearanceSettingsDataStore
import com.auralis.music.data.datastore.ContentSettingsDataStore
import com.auralis.music.data.datastore.DiscordRpcDataStore
import com.auralis.music.data.datastore.PrivacyDataStore
import com.auralis.music.data.datastore.SettingsDataStore
import com.auralis.music.data.datastore.StorageDataStore
import com.auralis.music.data.datastore.UpdaterDataStore
import java.io.File

/**
 * The phone's settings stores on desktop: the same DataStore Preferences files (one per store,
 * same names) in [dir]. Each file has exactly one DataStore, as DataStore requires.
 */
class DesktopDataStores(private val dir: File) : com.auralis.music.data.datastore.SettingsStores {
    private fun file(name: String): DataStore<Preferences> = PreferenceDataStoreFactory.create(
        corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
        produceFile = { File(dir.apply { mkdirs() }, "$name.preferences_pb") }
    )

    override val settings by lazy { SettingsDataStore(file("auralis_settings")) }
    override val appearance by lazy { AppearanceSettingsDataStore(file("auralis_appearance_settings")) }
    override val content by lazy { ContentSettingsDataStore(file("auralis_content")) }
    override val privacy by lazy { PrivacyDataStore(file("auralis_privacy")) }
    override val storage by lazy { StorageDataStore(file("auralis_storage")) }
    override val updater by lazy { UpdaterDataStore(file("auralis_updater")) }
    override val aiTranslation by lazy { AiTranslationDataStore(file("auralis_ai_translation")) }
    override val discord by lazy { DiscordRpcDataStore(file("discord_rpc_preferences")) }
}
