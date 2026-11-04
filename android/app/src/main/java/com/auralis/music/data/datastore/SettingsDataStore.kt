package com.auralis.music.data.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.preferencesDataStore
import com.auralis.music.domain.model.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "auralis_settings",
    corruptionHandler = ReplaceFileCorruptionHandler {
        emptyPreferences()
    }
)

class SettingsDataStore internal constructor(private val dataStore: DataStore<Preferences>) {
    constructor(context: Context) : this(context.settingsDataStore)

    companion object {
        val VOLUME = floatPreferencesKey("volume")
        val IS_MUTED = booleanPreferencesKey("is_muted")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val AUDIO_QUALITY = stringPreferencesKey("audio_quality")
        val GAPLESS_PLAYBACK = booleanPreferencesKey("gapless_playback")
        val SKIP_SILENCE = booleanPreferencesKey("skip_silence")
        val CROSSFADE_ENABLED = booleanPreferencesKey("crossfade_enabled")
        val CROSSFADE_SECONDS = floatPreferencesKey("crossfade_seconds")
        val AUTOMIX_ENABLED = booleanPreferencesKey("automix_enabled")
        val SKIP_GAPLESS_ALBUMS = booleanPreferencesKey("skip_gapless_albums")
        val PERSISTENT_QUEUE = booleanPreferencesKey("persistent_queue")
        val PERMANENT_SHUFFLE = booleanPreferencesKey("permanent_shuffle")
        val AUTO_LOAD_MORE = booleanPreferencesKey("auto_load_more")
        val STOP_MUSIC_ON_TASK_CLEAR = booleanPreferencesKey("stop_music_on_task_clear")
        val PAUSE_ON_MEDIA_MUTE = booleanPreferencesKey("pause_on_media_mute")
        val RESUME_ON_BLUETOOTH_CONNECT = booleanPreferencesKey("resume_on_bluetooth_connect")
        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        val SPATIAL_AUDIO = booleanPreferencesKey("spatial_audio")
        val LYRICS_FONT_SIZE = stringPreferencesKey("lyrics_font_size")
        val LYRICS_MODE = stringPreferencesKey("lyrics_mode")
        val LYRICS_ALIGNMENT = stringPreferencesKey("lyrics_alignment")
        val LYRICS_DEPTH_BLUR = booleanPreferencesKey("lyrics_depth_blur")
        val CLOUD_SYNC_ENABLED = booleanPreferencesKey("cloud_sync_enabled")
        val PLAYBACK_RATE = floatPreferencesKey("playback_rate")
    }

    val settingsFlow: Flow<PlayerSettings> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            PlayerSettings(
                volume = preferences[VOLUME] ?: 1.0f,
                isMuted = preferences[IS_MUTED] ?: false,
                themeMode = parseEnum(preferences[THEME_MODE], ThemeMode.SYSTEM),
                audioQuality = parseEnum(preferences[AUDIO_QUALITY], AudioQuality.HIGH),
                gaplessPlayback = preferences[GAPLESS_PLAYBACK] ?: true,
                skipSilence = preferences[SKIP_SILENCE] ?: false,
                crossfadeEnabled = preferences[CROSSFADE_ENABLED] ?: false,
                crossfadeSeconds = (preferences[CROSSFADE_SECONDS] ?: 5f).let { if (it.isFinite()) it.coerceIn(1f, 12f) else 5f },
                automixEnabled = preferences[AUTOMIX_ENABLED] ?: false,
                skipGaplessAlbums = preferences[SKIP_GAPLESS_ALBUMS] ?: true,
                persistentQueue = preferences[PERSISTENT_QUEUE] ?: true,
                permanentShuffle = preferences[PERMANENT_SHUFFLE] ?: false,
                autoLoadMore = preferences[AUTO_LOAD_MORE] ?: true,
                stopMusicOnTaskClear = preferences[STOP_MUSIC_ON_TASK_CLEAR] ?: true,
                pauseOnMediaMute = preferences[PAUSE_ON_MEDIA_MUTE] ?: false,
                resumeOnBluetoothConnect = preferences[RESUME_ON_BLUETOOTH_CONNECT] ?: false,
                keepScreenOn = preferences[KEEP_SCREEN_ON] ?: false,
                spatialAudio = preferences[SPATIAL_AUDIO] ?: false,
                lyricsFontSize = parseEnum(preferences[LYRICS_FONT_SIZE], FontSize.MEDIUM),
                lyricsMode = parseEnum(preferences[LYRICS_MODE], LyricsMode.SPICY),
                lyricsAlignment = parseEnum(preferences[LYRICS_ALIGNMENT], LyricsAlignment.LEFT),
                lyricsDepthBlur = preferences[LYRICS_DEPTH_BLUR] ?: true,
                cloudSyncEnabled = preferences[CLOUD_SYNC_ENABLED] ?: true,
                playbackRate = preferences[PLAYBACK_RATE] ?: 1.0f
            )
        }

    suspend fun updateSettings(settings: PlayerSettings) {
        dataStore.edit { preferences ->
            preferences[VOLUME] = settings.volume
            preferences[IS_MUTED] = settings.isMuted
            preferences[THEME_MODE] = settings.themeMode.name
            preferences[AUDIO_QUALITY] = settings.audioQuality.name
            preferences[GAPLESS_PLAYBACK] = settings.gaplessPlayback
            preferences[SKIP_SILENCE] = settings.skipSilence
            preferences[CROSSFADE_ENABLED] = settings.crossfadeEnabled && !settings.automixEnabled
            preferences[CROSSFADE_SECONDS] = settings.crossfadeSeconds.let { if (it.isFinite()) it.coerceIn(1f, 12f) else 5f }
            preferences[AUTOMIX_ENABLED] = settings.automixEnabled
            preferences[SKIP_GAPLESS_ALBUMS] = settings.skipGaplessAlbums
            preferences[PERSISTENT_QUEUE] = settings.persistentQueue
            preferences[PERMANENT_SHUFFLE] = settings.permanentShuffle
            preferences[AUTO_LOAD_MORE] = settings.autoLoadMore
            preferences[STOP_MUSIC_ON_TASK_CLEAR] = settings.stopMusicOnTaskClear
            preferences[PAUSE_ON_MEDIA_MUTE] = settings.pauseOnMediaMute
            preferences[RESUME_ON_BLUETOOTH_CONNECT] = settings.resumeOnBluetoothConnect
            preferences[KEEP_SCREEN_ON] = settings.keepScreenOn
            preferences[SPATIAL_AUDIO] = settings.spatialAudio
            preferences[LYRICS_FONT_SIZE] = settings.lyricsFontSize.name
            preferences[LYRICS_MODE] = settings.lyricsMode.name
            preferences[LYRICS_ALIGNMENT] = settings.lyricsAlignment.name
            preferences[LYRICS_DEPTH_BLUR] = settings.lyricsDepthBlur
            preferences[CLOUD_SYNC_ENABLED] = settings.cloudSyncEnabled
            preferences[PLAYBACK_RATE] = settings.playbackRate
        }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { it[THEME_MODE] = mode.name }
    }

    suspend fun setAudioQuality(quality: AudioQuality) {
        dataStore.edit { it[AUDIO_QUALITY] = quality.name }
    }

    suspend fun setGaplessPlayback(enabled: Boolean) {
        dataStore.edit { it[GAPLESS_PLAYBACK] = enabled }
    }

    suspend fun setSkipSilence(enabled: Boolean) {
        dataStore.edit { it[SKIP_SILENCE] = enabled }
    }

    suspend fun setCrossfadeEnabled(enabled: Boolean) {
        dataStore.edit {
            it[CROSSFADE_ENABLED] = enabled
            if (enabled) it[AUTOMIX_ENABLED] = false
        }
    }

    suspend fun setAutomixEnabled(enabled: Boolean) {
        dataStore.edit {
            it[AUTOMIX_ENABLED] = enabled
            if (enabled) it[CROSSFADE_ENABLED] = false
        }
    }

    suspend fun setCrossfadeSeconds(seconds: Float) {
        if (seconds.isFinite()) dataStore.edit { it[CROSSFADE_SECONDS] = seconds.coerceIn(1f, 12f) }
    }

    suspend fun setSkipGaplessAlbums(enabled: Boolean) {
        dataStore.edit { it[SKIP_GAPLESS_ALBUMS] = enabled }
    }

    suspend fun setSpatialAudio(enabled: Boolean) {
        dataStore.edit { it[SPATIAL_AUDIO] = enabled }
    }

    suspend fun setPersistentQueue(enabled: Boolean) {
        dataStore.edit { it[PERSISTENT_QUEUE] = enabled }
    }

    suspend fun setPermanentShuffle(enabled: Boolean) {
        dataStore.edit { it[PERMANENT_SHUFFLE] = enabled }
    }

    suspend fun setAutoLoadMore(enabled: Boolean) {
        dataStore.edit { it[AUTO_LOAD_MORE] = enabled }
    }

    suspend fun setStopMusicOnTaskClear(enabled: Boolean) {
        dataStore.edit { it[STOP_MUSIC_ON_TASK_CLEAR] = enabled }
    }

    suspend fun setPauseOnMediaMute(enabled: Boolean) {
        dataStore.edit { it[PAUSE_ON_MEDIA_MUTE] = enabled }
    }

    suspend fun setResumeOnBluetoothConnect(enabled: Boolean) {
        dataStore.edit { it[RESUME_ON_BLUETOOTH_CONNECT] = enabled }
    }

    suspend fun setKeepScreenOn(enabled: Boolean) {
        dataStore.edit { it[KEEP_SCREEN_ON] = enabled }
    }

    suspend fun setVolume(volume: Float) {
        dataStore.edit { it[VOLUME] = volume.coerceIn(0f, 1f) }
    }

    suspend fun setPlaybackRate(rate: Float) {
        dataStore.edit { it[PLAYBACK_RATE] = rate.coerceIn(0.25f, 2.0f) }
    }

    private inline fun <reified T : Enum<T>> parseEnum(value: String?, default: T): T {
        if (value == null) return default
        return try {
            enumValueOf<T>(value)
        } catch (e: Exception) {
            default
        }
    }
}
