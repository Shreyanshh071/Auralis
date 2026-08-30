package com.auralis.music.data.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.auralis.music.domain.model.DiscordRpcSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.discordDataStore: DataStore<Preferences> by preferencesDataStore(name = "discord_rpc_preferences")

class DiscordRpcDataStore(private val context: Context) {

    companion object {
        val IS_LOGGED_IN = booleanPreferencesKey("discord_is_logged_in")
        val DISCORD_USERNAME = stringPreferencesKey("discord_username")
        val DISCORD_DISCRIMINATOR = stringPreferencesKey("discord_discriminator")
        val DISCORD_AVATAR_URL = stringPreferencesKey("discord_avatar_url")
        val DISCORD_TOKEN = stringPreferencesKey("discord_token")
        val ENABLE_RICH_PRESENCE = booleanPreferencesKey("discord_enable_rich_presence")
        val ACTIVITY_STATUS = stringPreferencesKey("discord_activity_status")
        val UPDATE_INTERVAL = intPreferencesKey("discord_update_interval")
        val PLATFORM = stringPreferencesKey("discord_platform")
        val ACTIVITY_NAME = stringPreferencesKey("discord_activity_name")
        val ACTIVITY_DETAILS = stringPreferencesKey("discord_activity_details")
        val ACTIVITY_STATE = stringPreferencesKey("discord_activity_state")
        val SHOW_RPC_WHEN_PAUSED = booleanPreferencesKey("discord_show_rpc_when_paused")
        val ACTIVITY_TYPE = stringPreferencesKey("discord_activity_type")
        val LARGE_IMAGE = stringPreferencesKey("discord_large_image")
        val LARGE_TEXT = stringPreferencesKey("discord_large_text")
        val SMALL_IMAGE = stringPreferencesKey("discord_small_image")
    }

    val settingsFlow: Flow<DiscordRpcSettings> = context.discordDataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { prefs ->
            DiscordRpcSettings(
                isLoggedIn = prefs[IS_LOGGED_IN] ?: false,
                discordUsername = prefs[DISCORD_USERNAME] ?: "",
                discordDiscriminator = prefs[DISCORD_DISCRIMINATOR] ?: "",
                discordAvatarUrl = prefs[DISCORD_AVATAR_URL] ?: "",
                discordToken = prefs[DISCORD_TOKEN] ?: "",
                enableRichPresence = prefs[ENABLE_RICH_PRESENCE] ?: false,
                activityStatus = prefs[ACTIVITY_STATUS] ?: "Online",
                updateIntervalSeconds = prefs[UPDATE_INTERVAL] ?: 20,
                platform = prefs[PLATFORM] ?: "Android",
                activityName = prefs[ACTIVITY_NAME] ?: "Auralis",
                activityDetails = prefs[ACTIVITY_DETAILS] ?: "Song title",
                activityState = prefs[ACTIVITY_STATE] ?: "Artist name",
                showRpcWhenPaused = prefs[SHOW_RPC_WHEN_PAUSED] ?: false,
                activityType = prefs[ACTIVITY_TYPE] ?: "Listening",
                largeImage = prefs[LARGE_IMAGE] ?: "Album artwork",
                largeText = prefs[LARGE_TEXT] ?: "Album name",
                smallImage = prefs[SMALL_IMAGE] ?: "Artist artwork"
            )
        }

    suspend fun updateSettings(transform: (DiscordRpcSettings) -> DiscordRpcSettings) {
        context.discordDataStore.edit { prefs ->
            val current = DiscordRpcSettings(
                isLoggedIn = prefs[IS_LOGGED_IN] ?: false,
                discordUsername = prefs[DISCORD_USERNAME] ?: "",
                discordDiscriminator = prefs[DISCORD_DISCRIMINATOR] ?: "",
                discordAvatarUrl = prefs[DISCORD_AVATAR_URL] ?: "",
                discordToken = prefs[DISCORD_TOKEN] ?: "",
                enableRichPresence = prefs[ENABLE_RICH_PRESENCE] ?: false,
                activityStatus = prefs[ACTIVITY_STATUS] ?: "Online",
                updateIntervalSeconds = prefs[UPDATE_INTERVAL] ?: 20,
                platform = prefs[PLATFORM] ?: "Android",
                activityName = prefs[ACTIVITY_NAME] ?: "Auralis",
                activityDetails = prefs[ACTIVITY_DETAILS] ?: "Song title",
                activityState = prefs[ACTIVITY_STATE] ?: "Artist name",
                showRpcWhenPaused = prefs[SHOW_RPC_WHEN_PAUSED] ?: false,
                activityType = prefs[ACTIVITY_TYPE] ?: "Listening",
                largeImage = prefs[LARGE_IMAGE] ?: "Album artwork",
                largeText = prefs[LARGE_TEXT] ?: "Album name",
                smallImage = prefs[SMALL_IMAGE] ?: "Artist artwork"
            )
            val updated = transform(current)
            prefs[IS_LOGGED_IN] = updated.isLoggedIn
            prefs[DISCORD_USERNAME] = updated.discordUsername
            prefs[DISCORD_DISCRIMINATOR] = updated.discordDiscriminator
            prefs[DISCORD_AVATAR_URL] = updated.discordAvatarUrl
            prefs[DISCORD_TOKEN] = updated.discordToken
            prefs[ENABLE_RICH_PRESENCE] = updated.enableRichPresence
            prefs[ACTIVITY_STATUS] = updated.activityStatus
            prefs[UPDATE_INTERVAL] = updated.updateIntervalSeconds
            prefs[PLATFORM] = updated.platform
            prefs[ACTIVITY_NAME] = updated.activityName
            prefs[ACTIVITY_DETAILS] = updated.activityDetails
            prefs[ACTIVITY_STATE] = updated.activityState
            prefs[SHOW_RPC_WHEN_PAUSED] = updated.showRpcWhenPaused
            prefs[ACTIVITY_TYPE] = updated.activityType
            prefs[LARGE_IMAGE] = updated.largeImage
            prefs[LARGE_TEXT] = updated.largeText
            prefs[SMALL_IMAGE] = updated.smallImage
        }
    }

    suspend fun setLoggedIn(isLoggedIn: Boolean, username: String = "", avatarUrl: String = "", token: String = "") {
        context.discordDataStore.edit { prefs ->
            prefs[IS_LOGGED_IN] = isLoggedIn
            prefs[DISCORD_USERNAME] = username
            prefs[DISCORD_AVATAR_URL] = avatarUrl
            prefs[DISCORD_TOKEN] = token
        }
    }

    suspend fun setLoginState(isLoggedIn: Boolean, username: String, discriminator: String = "0", avatarUrl: String = "", token: String = "") {
        context.discordDataStore.edit { prefs ->
            prefs[IS_LOGGED_IN] = isLoggedIn
            prefs[DISCORD_USERNAME] = username
            prefs[DISCORD_DISCRIMINATOR] = discriminator
            prefs[DISCORD_AVATAR_URL] = avatarUrl
            prefs[DISCORD_TOKEN] = token
        }
    }

    suspend fun setEnableRichPresence(enabled: Boolean) {
        context.discordDataStore.edit { it[ENABLE_RICH_PRESENCE] = enabled }
    }

    suspend fun setActivityStatus(status: String) {
        context.discordDataStore.edit { it[ACTIVITY_STATUS] = status }
    }

    suspend fun setUpdateInterval(interval: Int) {
        context.discordDataStore.edit { it[UPDATE_INTERVAL] = interval }
    }

    suspend fun setPlatform(platform: String) {
        context.discordDataStore.edit { it[PLATFORM] = platform }
    }

    suspend fun setActivityName(name: String) {
        context.discordDataStore.edit { it[ACTIVITY_NAME] = name }
    }

    suspend fun setActivityDetails(details: String) {
        context.discordDataStore.edit { it[ACTIVITY_DETAILS] = details }
    }

    suspend fun setActivityState(state: String) {
        context.discordDataStore.edit { it[ACTIVITY_STATE] = state }
    }

    suspend fun setShowRpcWhenPaused(show: Boolean) {
        context.discordDataStore.edit { it[SHOW_RPC_WHEN_PAUSED] = show }
    }

    suspend fun setActivityType(type: String) {
        context.discordDataStore.edit { it[ACTIVITY_TYPE] = type }
    }

    suspend fun setLargeImage(image: String) {
        context.discordDataStore.edit { it[LARGE_IMAGE] = image }
    }

    suspend fun setLargeText(text: String) {
        context.discordDataStore.edit { it[LARGE_TEXT] = text }
    }

    suspend fun setSmallImage(image: String) {
        context.discordDataStore.edit { it[SMALL_IMAGE] = image }
    }
}
