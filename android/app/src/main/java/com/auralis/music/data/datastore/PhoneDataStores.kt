package com.auralis.music.data.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore

// The phone's settings files. The store classes are shared; these are the same DataStore files
// (names, corruption handling) the app always used, and the factories keep `XDataStore(context)`.

val Context.aiTranslationDataStore: DataStore<Preferences> by preferencesDataStore(name = "auralis_ai_translation")

val Context.appearanceSettingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "auralis_appearance_settings",
    corruptionHandler = ReplaceFileCorruptionHandler {
        emptyPreferences()
    }
)

val Context.contentSettingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "auralis_content",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() }
)

private val Context.discordDataStore: DataStore<Preferences> by preferencesDataStore(name = "discord_rpc_preferences")

private val Context.recognitionHistoryDataStore: DataStore<Preferences> by preferencesDataStore(name = "recognition_history")

val Context.privacyDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "auralis_privacy",
    corruptionHandler = ReplaceFileCorruptionHandler {
        emptyPreferences()
    }
)

val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "auralis_settings",
    corruptionHandler = ReplaceFileCorruptionHandler {
        emptyPreferences()
    }
)

val Context.storageDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "auralis_storage",
    corruptionHandler = ReplaceFileCorruptionHandler {
        emptyPreferences()
    }
)

val Context.updaterDataStore: DataStore<Preferences> by preferencesDataStore(name = "auralis_updater")

fun AiTranslationDataStore(context: Context): AiTranslationDataStore = AiTranslationDataStore(context.applicationContext.aiTranslationDataStore)
fun AppearanceSettingsDataStore(context: Context): AppearanceSettingsDataStore = AppearanceSettingsDataStore(context.applicationContext.appearanceSettingsDataStore)
fun ContentSettingsDataStore(context: Context): ContentSettingsDataStore = ContentSettingsDataStore(context.applicationContext.contentSettingsDataStore)
fun DiscordRpcDataStore(context: Context): DiscordRpcDataStore = DiscordRpcDataStore(context.applicationContext.discordDataStore)
fun MusicRecognitionHistoryDataStore(context: Context): MusicRecognitionHistoryDataStore = MusicRecognitionHistoryDataStore(context.applicationContext.recognitionHistoryDataStore)
fun PrivacyDataStore(context: Context): PrivacyDataStore = PrivacyDataStore(context.applicationContext.privacyDataStore)
fun SettingsDataStore(context: Context): SettingsDataStore = SettingsDataStore(context.applicationContext.settingsDataStore)
fun StorageDataStore(context: Context): StorageDataStore = StorageDataStore(context.applicationContext.storageDataStore)
fun UpdaterDataStore(context: Context): UpdaterDataStore = UpdaterDataStore(context.applicationContext.updaterDataStore)

/** [SettingsStores] on the phone: the factories above, each made once. */
class PhoneSettingsStores(context: Context) : SettingsStores {
    private val app = context.applicationContext
    override val settings by lazy { SettingsDataStore(app) }
    override val appearance by lazy { AppearanceSettingsDataStore(app) }
    override val content by lazy { ContentSettingsDataStore(app) }
    override val privacy by lazy { PrivacyDataStore(app) }
    override val storage by lazy { StorageDataStore(app) }
    override val updater by lazy { UpdaterDataStore(app) }
    override val aiTranslation by lazy { AiTranslationDataStore(app) }
    override val discord by lazy { DiscordRpcDataStore(app) }
}
