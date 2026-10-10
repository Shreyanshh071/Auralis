package com.auralis.music.desktop.discord

import com.auralis.music.data.datastore.DiscordRpcDataStore
import com.auralis.music.data.network.discord.DiscordPresence
import com.auralis.music.data.network.discord.DiscordPresenceBuilder
import com.auralis.music.desktop.playback.DesktopPlaybackController
import com.auralis.music.desktop.playback.VlcAudioPlayer
import com.auralis.music.domain.model.DiscordRpcSettings
import com.auralis.music.domain.model.Track
import com.auralis.music.ui.settings.DiscordControl
import com.auralis.music.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.json.JSONObject
import kotlin.math.abs

/**
 * Discord status on Windows through the Discord desktop app (local IPC), with the phone's
 * presence rules ([DiscordPresenceBuilder]) and the same Discord settings. Connecting checks that
 * the Discord app is running and remembers its signed-in user; Online/Idle status stays phone-only
 * (it needs Discord's Social SDK).
 */
class DesktopDiscordPresence(
    private val store: DiscordRpcDataStore,
    private val playback: DesktopPlaybackController?,
    clientId: String = APPLICATION_ID
) : DiscordControl {
    private val ipc = DiscordIpc(clientId)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var settings = DiscordRpcSettings()
    @Volatile private var track: Track? = null
    @Volatile private var isPlaying = false
    @Volatile private var positionMs = 0L
    @Volatile private var durationMs = 0L

    private var lastKey = ""
    private var lastSentAtMs = 0L
    private var lastSentPositionMs = 0L
    private var lastSentPlaying = false
    private val recentSends = ArrayDeque<Long>()
    private var sendJob: Job? = null
    @Volatile private var pending: Pair<DiscordPresence, Long>? = null

    override val note: String =
        "On Windows, Auralis shows your status through the Discord app on this PC (keep it open). " +
            "The Online / Idle / Do Not Disturb choice is on the phone for now."

    init {
        scope.launch {
            store.settingsFlow.collectLatest { updated ->
                settings = updated
                if (updated.isLoggedIn && updated.enableRichPresence) push(force = true) else clear()
            }
        }
        if (playback != null) {
            scope.launch {
                combine(playback.queue, playback.state, playback.lengthMs) { q, s, len -> Triple(q.current, s, len) }
                    .distinctUntilChanged()
                    .collect { (current, state, length) ->
                        track = current
                        isPlaying = state == VlcAudioPlayer.State.PLAYING || state == VlcAudioPlayer.State.BUFFERING
                        durationMs = length
                        positionMs = playback.positionMs.value
                        push()
                    }
            }
            // Seeks: the position jumps without a state change.
            scope.launch {
                playback.positionMs.collect { position ->
                    positionMs = position
                    push()
                }
            }
        }
    }

    override fun connect(onResult: (Boolean, String, String) -> Unit) {
        scope.launch {
            val ready = ipc.connect()
            if (ready == null) {
                launchMain { onResult(false, "", "Open the Discord app on this PC, then connect again.") }
                return@launch
            }
            val user = ready.optJSONObject("user")
            val username = user?.optString("global_name")?.takeIf { it.isNotBlank() && it != "null" }
                ?: user?.optString("username").orEmpty()
            val avatar = user?.let { u ->
                val id = u.optString("id"); val hash = u.optString("avatar")
                if (id.isNotBlank() && hash.isNotBlank() && hash != "null") "https://cdn.discordapp.com/avatars/$id/$hash.png" else ""
            }.orEmpty()
            store.setLoginState(isLoggedIn = true, username = username.ifBlank { "Discord user" }, avatarUrl = avatar, token = "")
            store.setEnableRichPresence(true)
            push(force = true)
            launchMain { onResult(true, username, "") }
        }
    }

    override fun disconnect() {
        scope.launch {
            clear()
            ipc.close()
        }
    }

    override fun refresh() = push(force = true)

    private fun push(force: Boolean = false) {
        val activeTrack = track
        val s = settings
        if (activeTrack == null || !s.isLoggedIn || !s.enableRichPresence) {
            if (activeTrack == null) clear()
            return
        }
        val playing = isPlaying
        val presence = DiscordPresenceBuilder.build(
            activeTrack, s, playing, positionMs, DiscordPresenceBuilder.songLengthMs(activeTrack, durationMs)
        ) { null }
        val now = System.currentTimeMillis()
        val key = presence.copy(positionMs = 0L, durationMs = presence.durationMs / 5_000L).toString()
        val refreshMs = s.updateIntervalSeconds.coerceAtLeast(0) * 1000L
        synchronized(this) {
            val elapsed = now - lastSentAtMs
            val expected = lastSentPositionMs + if (lastSentPlaying) elapsed else 0L
            val seeked = abs(positionMs - expected) > 3_000L
            val refreshDue = refreshMs > 0L && elapsed >= refreshMs && rateWaitMs(now) == 0L && recentSends.isEmpty()
            if (!force && key == lastKey && !seeked && !refreshDue) return
            lastKey = key
            lastSentAtMs = now
            lastSentPositionMs = positionMs
            lastSentPlaying = playing
            pending = presence to now
            if (sendJob?.isActive == true) return
            sendJob = scope.launch { sendPending() }
        }
    }

    /** Settles a burst of changes into one update and keeps to Discord's ~5 updates per 20 s. */
    private suspend fun sendPending() {
        delay(SETTLE_MS)
        while (true) {
            val wait = synchronized(this) { rateWaitMs(System.currentTimeMillis()) }
            if (wait > 0L) { delay(wait); continue }
            val (presence, capturedAt) = synchronized(this) {
                val p = pending ?: return
                pending = null
                recentSends.addLast(System.currentTimeMillis())
                p
            }
            val waited = System.currentTimeMillis() - capturedAt
            val now = if (presence.isPlaying) presence.copy(positionMs = presence.positionMs + waited) else presence
            if (!ipc.isConnected && ipc.connect() == null) {
                Log.w(TAG, "Discord app isn't running; status not sent")
                return
            }
            if (!ipc.setActivity(activityJson(now)) && ipc.connect() != null) ipc.setActivity(activityJson(now))
        }
    }

    private fun rateWaitMs(now: Long): Long {
        while (recentSends.isNotEmpty() && now - recentSends.first() >= RATE_WINDOW_MS) recentSends.removeFirst()
        return if (recentSends.size < RATE_COUNT) 0L else recentSends.first() + RATE_WINDOW_MS - now
    }

    /** Discord's activity object; null clears it (paused, unless "show when paused"). */
    private fun activityJson(p: DiscordPresence): JSONObject? {
        if (!p.isPlaying && !p.showWhenPaused) return null
        val activity = JSONObject()
        if (p.details.isNotBlank()) activity.put("details", p.details.take(128))
        if (p.state.isNotBlank()) activity.put("state", p.state.take(128))
        activity.put("type", when (p.activityType) {
            "Playing" -> 0
            "Watching" -> 3
            "Competing" -> 5
            else -> 2 // Listening (Streaming needs a stream URL)
        })
        if (p.isPlaying && p.durationMs > 0L) {
            val start = System.currentTimeMillis() - p.positionMs
            activity.put("timestamps", JSONObject().put("start", start).put("end", start + p.durationMs))
        }
        val assets = JSONObject()
        if (p.largeImage.isNotBlank()) assets.put("large_image", p.largeImage)
        if (p.largeText.isNotBlank()) assets.put("large_text", p.largeText.take(128))
        if (p.smallImage.isNotBlank()) assets.put("small_image", p.smallImage)
        if (p.smallText.isNotBlank()) assets.put("small_text", p.smallText.take(128))
        if (assets.length() > 0) activity.put("assets", assets)
        return activity
    }

    private fun clear() {
        synchronized(this) {
            lastKey = ""
            pending = null
            sendJob?.cancel()
        }
        scope.launch { if (ipc.isConnected) ipc.setActivity(null) }
    }

    private fun launchMain(block: () -> Unit) {
        CoroutineScope(Dispatchers.Main).launch { block() }
    }

    companion object {
        private const val TAG = "DesktopDiscord"
        /** The phone's Discord application (the same "Auralis" activity name and assets). */
        const val APPLICATION_ID = "1553114990909591732"
        private const val SETTLE_MS = 150L
        private const val RATE_COUNT = 5
        private const val RATE_WINDOW_MS = 20_000L
    }
}
