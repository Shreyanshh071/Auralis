package com.auralis.music.data.network.discord

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.auralis.music.data.datastore.DiscordRpcDataStore
import com.auralis.music.domain.model.DiscordRpcSettings
import com.auralis.music.domain.model.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Rich presence backed by Discord's Social SDK.
 *
 * This deliberately does not open Discord in a WebView or handle a Discord user token. The SDK
 * talks to the installed Discord app and owns the OAuth authorization flow.
 */
class DiscordGatewayManager private constructor(private val context: Context) {
    companion object {
        private const val TAG = "AuralisDiscord"
        private const val AURALIS_LOGO_URL = "https://auralis-self-nu.vercel.app/logo.png"
        private const val SETTLE_MS = 150L
        private const val RATE_LIMIT_COUNT = 5
        private const val RATE_LIMIT_WINDOW_MS = 20_000L

        @Volatile private var instance: DiscordGatewayManager? = null

        fun getInstance(context: Context): DiscordGatewayManager = instance ?: synchronized(this) {
            instance ?: DiscordGatewayManager(context.applicationContext).also { instance = it }
        }
    }

    private val dataStore = DiscordRpcDataStore(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile private var settings = DiscordRpcSettings()
    @Volatile private var track: Track? = null
    @Volatile private var isPlaying = false
    @Volatile private var isBuffering = false
    @Volatile private var positionMs = 0L
    @Volatile private var durationMs = 0L
    @Volatile private var sdkInitialized = false
    private val presenceLock = Any()
    private var lastPresenceKey = ""
    private var lastPresenceAtMs = 0L
    private var lastPresencePositionMs = 0L
    private var lastPresencePlaying = false
    @Volatile private var lastAppliedStatus: Int? = null
    private val artistLookups = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    init {
        DiscordSocialClient.profileListener = { profile ->
            scope.launch {
                dataStore.updateAvatarForConnectedUser(profile.username, profile.avatarUrl)
            }
        }
        DiscordSocialClient.readyListener = {
            lastAppliedStatus = null
            applyOnlineStatus()
            pushPresence(force = true)
        }
        scope.launch {
            while (isActive) {
                if (sdkInitialized) runCatching { DiscordSocialClient.pumpCallbacks() }
                delay(50L)
            }
        }
        scope.launch {
            dataStore.migrateLegacyAuthentication()
            dataStore.settingsFlow.collectLatest { updated ->
                settings = updated
                if (updated.isLoggedIn && updated.enableRichPresence) {
                    initializeSdk()
                    applyOnlineStatus()
                    // A changed option shows on Discord right away, not at the next song.
                    pushPresence(force = true)
                } else {
                    clearPresence()
                }
            }
        }
    }

    fun onPlaybackStateChanged(
        track: Track?,
        isPlaying: Boolean,
        isBuffering: Boolean,
        positionMs: Long,
        durationMs: Long
    ) {
        this.track = track
        this.isPlaying = isPlaying
        this.isBuffering = isBuffering
        this.positionMs = positionMs
        this.durationMs = durationMs
        pushPresence()
    }

    fun beginAuthorization(onResult: (success: Boolean, username: String, detail: String) -> Unit) {
        if (!initializeSdk()) {
            onResult(false, "", "Discord could not start on this device.")
            return
        }
        DiscordSocialClient.authorize { success, username, detail ->
            scope.launch {
                if (success) {
                    // OAuth credentials stay in Discord's SDK. Explicitly wipe any legacy user token.
                    dataStore.setLoginState(
                        isLoggedIn = true,
                        username = username.ifBlank { "Discord user" },
                        avatarUrl = DiscordSocialClient.currentProfile
                            ?.takeIf { it.username == username }?.avatarUrl.orEmpty(),
                        token = ""
                    )
                    dataStore.setEnableRichPresence(true)
                    pushPresence()
                }
                onResult(success, username, detail)
            }
        }
    }

    fun pushPresence(force: Boolean = false) {
        val activeTrack = track ?: run {
            clearPresence()
            return
        }
        val activeSettings = settings
        if (!activeSettings.isLoggedIn || !activeSettings.enableRichPresence || !initializeSdk()) return

        val playing = isPlaying || isBuffering
        val presence = buildPresence(activeTrack, activeSettings, playing)
        val now = SystemClock.elapsedRealtime()
        // Length only counts when it really changes: it arrives a moment after the song does.
        val key = presence.copy(positionMs = 0L, durationMs = presence.durationMs / 5_000L).toString()
        // "Update Interval": how often the progress bar is re-sent while nothing else changes.
        // 0 ("Disabled") re-sends only on a song, play/pause, seek or settings change.
        val refreshMs = activeSettings.updateIntervalSeconds.coerceAtLeast(0) * 1000L
        synchronized(presenceLock) {
            val elapsed = now - lastPresenceAtMs
            val expectedPosition = lastPresencePositionMs + if (lastPresencePlaying) elapsed else 0L
            val seeked = abs(positionMs - expectedPosition) > 3_000L
            // A refresh only goes out when nothing else was sent in Discord's whole rate window.
            // Refreshes used to hold up to 3 of the 5 slots, so a seek right after a song change
            // waited up to 20s for a free one and Discord showed the old position meanwhile.
            val refreshDue = refreshMs > 0L && elapsed >= refreshMs &&
                rateLimitWaitMs(now) == 0L && recentSendsMs.isEmpty()
            if (!force && key == lastPresenceKey && !seeked && !refreshDue) return
            lastPresenceKey = key
            lastPresenceAtMs = now
            lastPresencePositionMs = positionMs
            lastPresencePlaying = playing
        }
        schedulePresence(presence)
    }

    private class PendingPresence(val presence: DiscordPresence, val capturedAtMs: Long)

    @Volatile private var pendingPresence: PendingPresence? = null
    private var sendJob: kotlinx.coroutines.Job? = null
    private val recentSendsMs = ArrayDeque<Long>()

    /**
     * Sends [presence] as soon as Discord will take it. A song change arrives as several quick
     * state changes (song, then its length, then position 0), and Discord only accepts about
     * [RATE_LIMIT_COUNT] updates per [RATE_LIMIT_WINDOW_MS]; sent one by one they queued up and
     * the newest state showed ~10 s late. Now a burst settles into one update, and when the limit
     * is reached only the newest state is kept for the next free slot.
     */
    private fun schedulePresence(presence: DiscordPresence) {
        synchronized(presenceLock) {
            pendingPresence = PendingPresence(presence, SystemClock.elapsedRealtime())
            if (sendJob?.isActive == true) return
            sendJob = scope.launch {
                delay(SETTLE_MS)
                while (true) {
                    val waitMs = synchronized(presenceLock) { rateLimitWaitMs(SystemClock.elapsedRealtime()) }
                    if (waitMs > 0L) {
                        delay(waitMs)
                        continue
                    }
                    val next = synchronized(presenceLock) {
                        val p = pendingPresence ?: return@launch
                        pendingPresence = null
                        recentSendsMs.addLast(SystemClock.elapsedRealtime())
                        p
                    }
                    val now = SystemClock.elapsedRealtime()
                    val waited = now - next.capturedAtMs
                    // The position was read when the state changed; move it on by the time spent waiting.
                    val presenceNow = if (next.presence.isPlaying) {
                        next.presence.copy(positionMs = next.presence.positionMs + waited)
                    } else next.presence
                    Log.i(TAG, "Presence sent ${waited}ms after the change: \"${presenceNow.details}\" playing=${presenceNow.isPlaying}")
                    DiscordSocialClient.updatePresence(presenceNow)
                }
            }
        }
    }

    private fun rateLimitWaitMs(nowMs: Long): Long {
        while (recentSendsMs.isNotEmpty() && nowMs - recentSendsMs.first() >= RATE_LIMIT_WINDOW_MS) recentSendsMs.removeFirst()
        if (recentSendsMs.size < RATE_LIMIT_COUNT) return 0L
        return recentSendsMs.first() + RATE_LIMIT_WINDOW_MS - nowMs
    }

    private fun buildPresence(t: Track, s: DiscordRpcSettings, playing: Boolean): DiscordPresence {
        val album = t.album.orEmpty()
        fun text(choice: String): String = when (choice) {
            "Artist name" -> t.artist
            "Album name" -> album
            "Song title" -> t.title
            "Auralis" -> "Auralis"
            else -> ""
        }
        val artwork = t.thumbnail.trim().let { thumbnail ->
            when {
                thumbnail.startsWith("https://") -> thumbnail
                thumbnail.startsWith("http://") -> thumbnail.replaceFirst("http://", "https://")
                thumbnail.startsWith("//") -> "https:$thumbnail"
                t.id.isNotBlank() -> "https://i.ytimg.com/vi/${t.id}/hqdefault.jpg"
                else -> ""
            }
        }
        val largeImage = when (s.largeImage) {
            "Album artwork" -> artwork
            "App icon" -> AURALIS_LOGO_URL
            else -> ""
        }
        val largeText = when (s.largeText) {
            "Album name" -> album.ifBlank { t.title }
            "Song title" -> t.title
            "Auralis" -> "Auralis"
            else -> ""
        }
        val (smallImage, smallText) = when (s.smallImage) {
            "Artist artwork" -> artistPhoto(t.artist).orEmpty() to t.artist
            "Play state" -> AURALIS_LOGO_URL to if (playing) "Playing" else "Paused"
            "App logo" -> AURALIS_LOGO_URL to "Auralis"
            else -> "" to ""
        }
        val details = text(s.activityDetails).let { if (!playing && it.isNotBlank()) "$it [Paused]" else it }
        return DiscordPresence(
            name = s.activityName.ifBlank { "Auralis" },
            details = details,
            state = text(s.activityState),
            largeImage = largeImage,
            largeText = if (largeImage.isBlank()) "" else largeText,
            smallImage = smallImage,
            smallText = if (smallImage.isBlank()) "" else smallText,
            activityType = s.activityType,
            isPlaying = playing,
            positionMs = positionMs,
            durationMs = songLengthMs(t),
            showWhenPaused = s.showRpcWhenPaused
        )
    }

    /** The player's length, unless it is clearly still the previous song's; then the song's own. */
    private fun songLengthMs(t: Track): Long {
        val metadataMs = t.duration * 1000L
        return when {
            durationMs <= 0L -> metadataMs
            metadataMs > 0L && abs(durationMs - metadataMs) > 10_000L -> metadataMs
            else -> durationMs
        }
    }

    /** The artist's photo if already known; otherwise looked up once, then the presence is re-sent. */
    private fun artistPhoto(artist: String): String? {
        val primary = artist.split(",", "&", " feat.", " ft.").first().trim()
        if (primary.isBlank()) return null
        com.auralis.music.data.network.ArtistPhotoProvider.getCachedPhoto(primary)?.let { return it }
        if (artistLookups.add(primary)) {
            scope.launch {
                val photo = runCatching { com.auralis.music.data.network.ArtistPhotoProvider.resolveArtistPhoto(primary) }.getOrNull()
                if (!photo.isNullOrBlank() && track?.artist == artist) pushPresence(force = true)
            }
        }
        return null
    }

    private fun applyOnlineStatus() {
        val status = when (settings.activityStatus) {
            "Idle" -> 3
            "Do Not Disturb", "DND" -> 4
            "Invisible" -> 5
            else -> 0
        }
        if (status == lastAppliedStatus || !sdkInitialized) return
        lastAppliedStatus = status
        runCatching { DiscordSocialClient.setOnlineStatus(status) }
    }

    fun disconnect() {
        clearPresence()
        DiscordSocialClient.forgetAuthorization()
    }

    private fun clearPresence() {
        synchronized(presenceLock) {
            lastPresenceKey = ""
            pendingPresence = null
            sendJob?.cancel()
        }
        if (sdkInitialized) runCatching { DiscordSocialClient.clearPresence() }
    }

    private fun initializeSdk(): Boolean {
        if (sdkInitialized) return true
        val initialized = runCatching { DiscordSocialClient.initialize(context) }
            .onFailure { Log.e(TAG, "Unable to initialize Discord Social SDK", it) }
            .getOrDefault(false)
        sdkInitialized = initialized
        return initialized
    }
}
