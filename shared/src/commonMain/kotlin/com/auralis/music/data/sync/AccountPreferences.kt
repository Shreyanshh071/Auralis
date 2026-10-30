package com.auralis.music.data.sync

import com.auralis.music.domain.model.AppearanceSettings
import com.auralis.music.domain.model.PlayerSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Portable user choices only. Never include sessions, service credentials or device resources. */
object AccountPreferenceSchema {
    private fun definitions(a: AppearanceSettings, p: PlayerSettings): Map<String, Any> = mapOf(
        "a.dynamic_theme" to a.dynamicTheme, "a.dynamic_icon_colors" to a.dynamicIconColors,
        "a.liquid_glass" to a.liquidGlass, "a.app_theme" to a.appTheme, "a.color_palette" to a.colorPalette,
        "a.mini_player_design" to a.miniPlayerDesign, "a.pure_black_mini_player" to a.pureBlackMiniPlayer,
        "a.mini_player_bg_style" to a.miniPlayerBackgroundStyle, "a.player_design" to a.playerDesign,
        "a.inline_lyrics_new_player" to a.inlineLyricsNewPlayer, "a.inline_lyrics_old_player" to a.inlineLyricsOldPlayer,
        "a.inline_lyrics_immersive" to a.inlineLyricsImmersive,
        "a.player_bg_style" to a.playerBackgroundStyle, "a.hide_player_thumbnail" to a.hidePlayerThumbnail,
        "a.crop_album_art" to a.cropAlbumArt, "a.player_button_colors" to a.playerButtonColors,
        "a.player_slider_style" to a.playerSliderStyle, "a.show_download_button" to a.showDownloadButton,
        "a.enable_swipe_to_change_song" to a.enableSwipeToChangeSong,
        "a.mini_player_swipe_sensitivity" to a.miniPlayerSwipeSensitivity,
        "a.experimental_lyrics" to a.experimentalLyrics, "a.lyrics_text_position" to a.lyricsTextPosition,
        "a.respect_agent_positioning" to a.respectAgentPositioning, "a.change_lyrics_on_tap" to a.changeLyricsOnTap,
        "a.auto_scroll_lyrics" to a.autoScrollLyrics, "a.lyrics_animation" to a.lyricsAnimation,
        "a.enable_glowing_lyrics" to a.enableGlowingLyricsEffect, "a.standard_lyrics_blur" to a.standardLyricsBlur,
        "a.lyrics_text_size" to a.lyricsTextSize, "a.lyrics_line_spacing" to a.lyricsLineSpacing,
        "a.default_open_tab" to a.defaultOpenTab, "a.default_library_chip" to a.defaultLibraryChip,
        "a.swipe_left_queue_right_play_next" to a.swipeLeftQueueRightPlayNext,
        "a.swipe_to_remove_song_from_playlist" to a.swipeToRemoveSongFromPlaylist,
        "a.slim_bottom_nav_bar" to a.slimBottomNavigationBar, "a.listen_together_in_top_bar" to a.listenTogetherInTopBar,
        "a.grid_cell_size" to a.gridCellSize, "a.show_liked_playlist" to a.showLikedPlaylist,
        "a.show_downloaded_playlist" to a.showDownloadedPlaylist, "a.show_top_playlist" to a.showTopPlaylist,
        "a.show_weekly_most_playlist" to a.showWeeklyMostPlaylist, "a.show_monthly_most_playlist" to a.showMonthlyMostPlaylist,
        "a.show_cached_playlist" to a.showCachedPlaylist, "a.show_uploaded_playlist" to a.showUploadedPlaylist,
        "p.theme_mode" to p.themeMode.name, "p.audio_quality" to p.audioQuality.name,
        "p.gapless_playback" to p.gaplessPlayback, "p.skip_silence" to p.skipSilence,
        "p.persistent_queue" to p.persistentQueue, "p.auto_load_more" to p.autoLoadMore,
        "p.lyrics_font_size" to p.lyricsFontSize.name, "p.lyrics_mode" to p.lyricsMode.name,
        "p.lyrics_alignment" to p.lyricsAlignment.name, "p.lyrics_depth_blur" to p.lyricsDepthBlur,
        "p.playback_rate" to p.playbackRate)
    val defaults = definitions(AppearanceSettings(), PlayerSettings())
    val defaultValues = defaults.mapValues { it.value.toString() }
    fun values(appearance: AppearanceSettings, playback: PlayerSettings) =
        definitions(appearance, playback).mapValues { it.value.toString() }
    fun valid(key: String, value: String): Boolean = when (defaults[key]) {
        is Boolean -> value == "true" || value == "false"
        is Int -> value.toIntOrNull()?.let { it in 10..100 } == true
        is Float -> value.toFloatOrNull()?.let { it.isFinite() && when (key) {
            "a.lyrics_text_size" -> it in 8f..100f
            "a.lyrics_line_spacing" -> it in .6f..4f
            "p.playback_rate" -> it in .25f..4f
            else -> false
        } } == true
        is String -> value.isNotBlank() && value.length <= 120 && value.none { it.code < 32 }
        else -> false
    }
}

@Serializable
data class AccountPreference(val value: String, val revision: Long, val device: String) : Comparable<AccountPreference> {
    override fun compareTo(other: AccountPreference): Int = compareValuesBy(this, other, { it.revision }, { it.device })
}

/** A backend must merge inside a transaction, preserving unrelated account data and unknown fields. */
fun interface AccountPreferencesBackend {
    suspend fun exchange(uid: String, edits: Map<String, AccountPreference>, seed: Map<String, String>): Map<String, AccountPreference>
}
interface AccountPreferencesLocal {
    suspend fun read(): Map<String, String>
    suspend fun apply(values: Map<String, String>)
    suspend fun enabled(): Boolean = true
}
interface AccountPreferencesJournal {
    suspend fun read(): String?
    suspend fun write(value: String)
}

object AccountPreferencesMerge {
    fun decode(raw: Any?): Map<String, AccountPreference> = (raw as? Map<*, *>)?.mapNotNull { (key, rawValue) ->
        val name = key as? String ?: return@mapNotNull null
        val fields = rawValue as? Map<*, *> ?: return@mapNotNull null
        val value = fields["value"] as? String ?: return@mapNotNull null
        val revision = (fields["revision"] as? Number)?.toLong() ?: return@mapNotNull null
        val device = fields["device"] as? String ?: return@mapNotNull null
        if (!AccountPreferenceSchema.valid(name, value) || revision !in 0..1_000_000_000L || device.length !in 1..80) null
        else name to AccountPreference(value, revision, device)
    }?.toMap().orEmpty()
    fun encode(values: Map<String, AccountPreference>): Map<String, Map<String, Any>> = values.mapValues { (_, pref) ->
        mapOf("value" to pref.value, "revision" to pref.revision, "device" to pref.device)
    }

    fun merge(remote: Map<String, AccountPreference>, edits: Map<String, AccountPreference>, seed: Map<String, String>): Map<String, AccountPreference> {
        val result = remote.toMutableMap()
        if (remote.isEmpty()) seed.filter { AccountPreferenceSchema.valid(it.key, it.value) }.forEach { (key, value) ->
            result[key] = AccountPreference(value, 0, "initial")
        }
        edits.filter { AccountPreferenceSchema.valid(it.key, it.value.value) && it.value.revision in 1..1_000_000_000L &&
            it.value.device.length in 1..80 }.forEach { (key, value) ->
            if (result[key]?.let { value > it } != false) result[key] = value
        }
        return result
    }
}

/** Per-field logical revisions avoid clock skew; journaled edits survive offline restarts. */
class AccountPreferencesSync(private val local: AccountPreferencesLocal, private val backend: AccountPreferencesBackend,
    private val storage: AccountPreferencesJournal, private val device: String,
    private val now: () -> Long = System::currentTimeMillis, private val pollMs: Long = 30_000L) {
    enum class Status { OFF, SYNCING, SYNCED, RETRYING }
    private val mutableStatus = MutableStateFlow(Status.OFF)
    val status = mutableStatus.asStateFlow()
    @Serializable private data class Account(val observed: Map<String, String> = emptyMap(),
        val known: Map<String, AccountPreference> = emptyMap(), val pending: Map<String, AccountPreference> = emptyMap())
    @Serializable private data class Journal(val owner: String? = null, val accounts: Map<String, Account> = emptyMap())
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var journal: Journal? = null
    private var active: String? = null
    private var lastPull = Long.MIN_VALUE
    private var lastEdit = 0L
    private var retryAt = 0L
    private val lock = Mutex()
    private suspend fun save() { storage.write(json.encodeToString(Journal.serializer(), journal!!)) }
    private fun snapshot(values: Map<String, String>) = values.filter { AccountPreferenceSchema.valid(it.key, it.value) }
    private fun capture(state: Account, values: Map<String, String>): Account {
        val pending = state.pending.toMutableMap()
        values.forEach { (key, value) -> if (state.observed[key] != value) {
            pending[key] = AccountPreference(value, maxOf(state.known[key]?.revision ?: 0L, pending[key]?.revision ?: 0L) + 1, device)
        } }
        return state.copy(observed = values, pending = pending)
    }
    private fun put(uid: String, state: Account) { journal = journal!!.copy(accounts = journal!!.accounts + (uid to state)) }

    suspend fun step(uid: String?) = lock.withLock {
        if (uid.isNullOrBlank() || !local.enabled()) { active = null; mutableStatus.value = Status.OFF; return@withLock }
        try {
            if (journal == null) journal = storage.read()?.let { runCatching { json.decodeFromString(Journal.serializer(), it) }.getOrNull() } ?: Journal()
            val before = journal
            var state = journal!!.accounts[uid] ?: Account()
            var values = snapshot(local.read())
            if (active != uid) {
                if (journal!!.owner != null && journal!!.owner != uid) {
                    // A different account gets its own cloud choices, never the previous user's settings.
                    local.apply(AccountPreferenceSchema.defaultValues)
                    values = snapshot(local.read())
                    state = state.copy(observed = values)
                } else if (state.observed.isEmpty()) state = state.copy(observed = values)
                active = uid; lastPull = Long.MIN_VALUE; retryAt = 0L
                journal = journal!!.copy(owner = uid)
            }
            val captured = capture(state, values)
            if (captured.pending != state.pending) lastEdit = now()
            state = captured; put(uid, state)
            if (before != journal) save()
            if (now() < retryAt) return@withLock
            val initial = lastPull == Long.MIN_VALUE
            if (!initial && !(state.pending.isNotEmpty() && now() - lastEdit >= 800L) && now() - lastPull < pollMs) return@withLock
            mutableStatus.value = Status.SYNCING
            val remote = backend.exchange(uid, state.pending, values).filter {
                AccountPreferenceSchema.valid(it.key, it.value.value) && it.value.revision in 0..1_000_000_000L
            }
            currentCoroutineContext().ensureActive()
            // A user can change a preference while the network request is in flight.
            val acknowledged = state.pending.filter { (key, edit) -> remote[key]?.let { it >= edit } != true }
            state = capture(state.copy(known = remote, pending = acknowledged), snapshot(local.read()))
            val desired = remote.mapValues { it.value.value } + state.pending.mapValues { it.value.value }
            local.apply(desired.filter { state.observed[it.key] != it.value })
            state = state.copy(observed = snapshot(local.read()))
            put(uid, state); save(); lastPull = now()
            mutableStatus.value = Status.SYNCED
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            com.auralis.music.util.Log.w("AccountPreferences", "Settings sync will retry (${e.javaClass.simpleName})")
            retryAt = now() + 15_000L; mutableStatus.value = Status.RETRYING
        }
    }
}
