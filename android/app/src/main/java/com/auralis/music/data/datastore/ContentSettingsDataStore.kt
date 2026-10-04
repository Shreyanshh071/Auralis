package com.auralis.music.data.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.auralis.music.domain.model.AddToPlaylistPosition
import com.auralis.music.domain.model.ContentSettings
import com.auralis.music.domain.model.LyricsProviderId
import com.auralis.music.domain.model.ProxyType
import com.auralis.music.domain.model.QuickPicksMode
import com.auralis.music.domain.model.RomanizationScript
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.IOException

val Context.contentSettingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "auralis_content",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() }
)

class ContentSettingsDataStore(context: Context) {
    private val dataStore = context.applicationContext.contentSettingsDataStore

    private companion object {
        val CONTENT_LANGUAGE = stringPreferencesKey("content_language")
        val CONTENT_COUNTRY = stringPreferencesKey("content_country")
        val ADD_TO_PLAYLIST_POSITION = stringPreferencesKey("add_to_playlist_position")
        val SHOW_ARTIST_DESCRIPTION = booleanPreferencesKey("show_artist_description")
        val SHOW_ARTIST_SUBSCRIBERS = booleanPreferencesKey("show_artist_subscriber_count")
        val SHOW_MONTHLY_LISTENERS = booleanPreferencesKey("show_monthly_listeners")
        val PROXY_ENABLED = booleanPreferencesKey("proxy_enabled")
        val PROXY_TYPE = stringPreferencesKey("proxy_type")
        val PROXY_URL = stringPreferencesKey("proxy_url")
        val PROXY_USERNAME = stringPreferencesKey("proxy_username")
        val PROXY_PASSWORD = stringPreferencesKey("proxy_password")
        val DISABLED_LYRICS_PROVIDERS = stringPreferencesKey("disabled_lyrics_providers")
        val ROMANIZATION_ENABLED = booleanPreferencesKey("romanization_enabled")
        val ROMANIZED_SCRIPTS = stringPreferencesKey("romanized_scripts")
        val SHOW_MOST_STATS_PLAYLISTS = booleanPreferencesKey("show_most_stats_playlists")
        val SHOW_WRAPPED_CARD = booleanPreferencesKey("show_wrapped_card")
        val RANDOMIZE_HOME_ORDER = booleanPreferencesKey("randomize_home_order")
        val TOP_LENGTH = intPreferencesKey("top_length")
        val QUICK_PICKS_MODE = stringPreferencesKey("quick_picks_mode")
    }

    val settingsFlow: Flow<ContentSettings> = dataStore.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it.toContentSettings() }

    suspend fun update(transform: (ContentSettings) -> ContentSettings) {
        dataStore.edit { prefs ->
            val next = transform(prefs.toContentSettings())
            prefs[CONTENT_LANGUAGE] = next.contentLanguage
            prefs[CONTENT_COUNTRY] = next.contentCountry
            prefs[ADD_TO_PLAYLIST_POSITION] = next.addToPlaylistPosition.name
            prefs[SHOW_ARTIST_DESCRIPTION] = next.showArtistDescription
            prefs[SHOW_ARTIST_SUBSCRIBERS] = next.showArtistSubscriberCount
            prefs[SHOW_MONTHLY_LISTENERS] = next.showMonthlyListeners
            prefs[PROXY_ENABLED] = next.proxyEnabled
            prefs[PROXY_TYPE] = next.proxyType.name
            prefs[PROXY_URL] = next.proxyUrl
            prefs[PROXY_USERNAME] = next.proxyUsername
            prefs[PROXY_PASSWORD] = next.proxyPassword
            prefs[DISABLED_LYRICS_PROVIDERS] = next.disabledLyricsProviders.joinToString(",") { it.name }
            prefs[ROMANIZATION_ENABLED] = next.romanizationEnabled
            prefs[ROMANIZED_SCRIPTS] = next.romanizedScripts.joinToString(",") { it.name }
            prefs[SHOW_MOST_STATS_PLAYLISTS] = next.showMostStatsPlaylists
            prefs[SHOW_WRAPPED_CARD] = next.showWrappedCard
            prefs[RANDOMIZE_HOME_ORDER] = next.randomizeHomeOrder
            prefs[TOP_LENGTH] = next.topLength
            prefs[QUICK_PICKS_MODE] = next.quickPicksMode.name
        }
    }

    private fun Preferences.toContentSettings(): ContentSettings {
        val d = ContentSettings()
        return ContentSettings(
            contentLanguage = this[CONTENT_LANGUAGE] ?: d.contentLanguage,
            contentCountry = this[CONTENT_COUNTRY] ?: d.contentCountry,
            addToPlaylistPosition = enumOr(this[ADD_TO_PLAYLIST_POSITION], d.addToPlaylistPosition),
            showArtistDescription = this[SHOW_ARTIST_DESCRIPTION] ?: d.showArtistDescription,
            showArtistSubscriberCount = this[SHOW_ARTIST_SUBSCRIBERS] ?: d.showArtistSubscriberCount,
            showMonthlyListeners = this[SHOW_MONTHLY_LISTENERS] ?: d.showMonthlyListeners,
            proxyEnabled = this[PROXY_ENABLED] ?: d.proxyEnabled,
            proxyType = enumOr(this[PROXY_TYPE], d.proxyType),
            proxyUrl = this[PROXY_URL] ?: d.proxyUrl,
            proxyUsername = this[PROXY_USERNAME] ?: d.proxyUsername,
            proxyPassword = this[PROXY_PASSWORD] ?: d.proxyPassword,
            disabledLyricsProviders = enumSet<LyricsProviderId>(this[DISABLED_LYRICS_PROVIDERS]) ?: d.disabledLyricsProviders,
            romanizationEnabled = this[ROMANIZATION_ENABLED] ?: d.romanizationEnabled,
            romanizedScripts = enumSet<RomanizationScript>(this[ROMANIZED_SCRIPTS]) ?: d.romanizedScripts,
            showMostStatsPlaylists = this[SHOW_MOST_STATS_PLAYLISTS] ?: d.showMostStatsPlaylists,
            showWrappedCard = this[SHOW_WRAPPED_CARD] ?: d.showWrappedCard,
            randomizeHomeOrder = this[RANDOMIZE_HOME_ORDER] ?: d.randomizeHomeOrder,
            topLength = this[TOP_LENGTH] ?: d.topLength,
            quickPicksMode = enumOr(this[QUICK_PICKS_MODE], d.quickPicksMode)
        )
    }

    private inline fun <reified E : Enum<E>> enumOr(raw: String?, default: E): E =
        enumValues<E>().firstOrNull { it.name == raw } ?: default

    private inline fun <reified E : Enum<E>> enumSet(raw: String?): Set<E>? =
        raw?.split(",")?.mapNotNull { name -> enumValues<E>().firstOrNull { it.name == name } }?.toSet()
}

/**
 * Process-wide snapshot of [ContentSettings] for code that isn't Compose and can't suspend on
 * DataStore per request: InnerTube context (hl/gl), result filters, lyrics providers, the proxy.
 * The first read blocks app start (one small file, a few ms) so the very first request already
 * honours the proxy and filters instead of going out with defaults.
 */
object ContentSettingsStore {
    private val _current = MutableStateFlow(ContentSettings())
    val current: StateFlow<ContentSettings> = _current.asStateFlow()
    val value: ContentSettings get() = _current.value

    private var started = false

    fun init(context: Context) {
        if (started) return
        started = true
        val store = ContentSettingsDataStore(context)
        kotlinx.coroutines.runBlocking(Dispatchers.IO) {
            kotlinx.coroutines.withTimeoutOrNull(500L) { store.settingsFlow.first() }?.let { _current.value = it }
        }
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            store.settingsFlow.collect { _current.value = it }
        }
    }
}
