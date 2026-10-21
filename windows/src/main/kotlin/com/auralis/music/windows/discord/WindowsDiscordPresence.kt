package com.auralis.music.windows.discord

import com.auralis.music.desktop.discord.DiscordIpc
import com.auralis.music.desktop.playback.DesktopPlaybackController
import com.auralis.music.desktop.playback.VlcAudioPlayer
import com.auralis.music.domain.repository.SearchRepository
import com.auralis.music.data.datastore.DiscordRpcDataStore
import com.auralis.music.domain.model.DiscordRpcSettings
import com.auralis.music.data.network.InnerTubeClient
import com.auralis.music.data.network.AlbumMetadataResolver
import com.auralis.music.domain.search.SearchQueryMatcher
import com.auralis.music.ui.settings.DiscordControl
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import kotlin.math.abs

internal data class DiscordNow(val track: DiscordTrack? = null, val playing: Boolean = false,
    val played: Boolean = false, val position: Long = 0, val duration: Long = 0)

internal interface DiscordTransport {
    val connected: Boolean
    fun connect(): Boolean
    fun send(activity: JSONObject?): Boolean
    fun close()
}
private class LocalDiscordTransport : DiscordTransport {
    private val ipc = DiscordIpc(com.auralis.music.desktop.discord.DesktopDiscordPresence.APPLICATION_ID)
    override val connected get() = ipc.isConnected
    override fun connect() = ipc.connect() != null
    override fun send(activity: JSONObject?) = ipc.setActivity(activity)
    override fun close() = ipc.close()
}

internal class WindowsDiscordPresence(private val playback: DesktopPlaybackController?,
    legacy: DiscordRpcDataStore?, private val search: SearchRepository,
    private val store: DiscordCardStore = DiscordCardPreferences.store,
    private val ipc: DiscordTransport = LocalDiscordTransport(),
    input: StateFlow<DiscordNow>? = null) : DiscordControl, AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableNow = MutableStateFlow(DiscordNow())
    val now = mutableNow.asStateFlow()
    private val mutableStatus = MutableStateFlow("Disabled")
    val status = mutableStatus.asStateFlow()
    private val artistIds = java.util.concurrent.ConcurrentHashMap<String,String>()
    private var artistJob: Job? = null
    private var lookingFor: String? = null
    private var artistRetryAt = 0L
    override val note = "Uses the Discord desktop app on this PC. No account sign-in or token is required."

    init {
        if (legacy != null) scope.launch {
            store.migrate(legacy.settingsFlow.first().enableRichPresence)
            legacy.updateSettings { DiscordRpcSettings() }
        }
        if (input != null) scope.launch { input.collect { mutableNow.value = it } }
        if (playback != null) scope.launch {
            var played = false
            combine(playback.queue, playback.state, playback.positionMs, playback.lengthMs) { q,s,pos,len ->
                val playing = s == VlcAudioPlayer.State.PLAYING
                played = played || playing
                val t = q.current?.let { original ->
                    val cached = InnerTubeClient.cachedQueueAlbum(original.id)
                    val resolved = AlbumMetadataResolver.getCached(original.title, original.artist, original.album)
                    original.copy(album = original.album ?: cached?.first ?: resolved?.albumTitle,
                        albumId = original.albumId ?: cached?.second ?: resolved?.albumId)
                }
                DiscordNow(t?.let { DiscordTrack(it, artistIds[it.artist.lowercase()]) }, playing, played, pos,
                    if (len > 0) len else (t?.duration ?: 0) * 1000)
            }.collect { mutableNow.value = it }
        }
        scope.launch { runPresence() }
    }

    private fun resolveArtist(cfg: DiscordCardSettings, now: DiscordNow) {
        val t = now.track?.track ?: return
        val needs = cfg.button1 == "artist" || cfg.button2 == "artist" ||
            cfg.linkLine1 && cfg.line1 in listOf("artist","artist_album") || cfg.linkLine2 && cfg.line2 in listOf("artist","artist_album")
        if (!needs || t.source == com.auralis.music.domain.model.TrackSource.LOCAL || cfg.hideDetails) return
        val key = t.artist.lowercase()
        artistIds[key]?.let { id -> mutableNow.update { if (it.track?.track?.artist?.lowercase() == key) it.copy(track = it.track.copy(artistId=id)) else it }; return }
        if (lookingFor == key && (artistJob?.isActive == true || System.currentTimeMillis() < artistRetryAt)) return
        lookingFor = key
        artistJob?.cancel()
        artistJob = scope.launch {
            val artist = runCatching { search.searchArtists(t.artist).firstOrNull {
                it.id.startsWith("UC") && SearchQueryMatcher.isAuthorMatch(it.name,t.artist)
            } }.getOrNull()
            if (artist != null) {
                artistIds[key] = artist.id
                mutableNow.update { if (it.track?.track?.artist?.lowercase() == key) it.copy(track = it.track.copy(artistId=artist.id)) else it }
            } else artistRetryAt = System.currentTimeMillis() + 30000
        }
    }

    private suspend fun runPresence() {
        var lastKey = ""
        var sentAt = 0L
        var sentPosition = 0L
        var sentPlaying = false
        var retryAt = 0L
        var retryDelay = 1000L
        var hadCard = false
        while (currentCoroutineContext().isActive) {
            val cfg = store.settings.value
            if (!cfg.enabled) {
                if (ipc.connected) { ipc.send(null); ipc.close() }
                mutableStatus.value = "Disabled"; lastKey = ""; hadCard = false
                delay(500); continue
            }
            val time = System.currentTimeMillis()
            if (!ipc.connected && time >= retryAt) {
                if (!ipc.connect()) {
                    mutableStatus.value = "Waiting for Discord desktop…"
                    retryAt = time + retryDelay; retryDelay = (retryDelay * 2).coerceAtMost(30000)
                } else {
                    mutableStatus.value = "Connected to Discord desktop"
                    retryDelay = 1000; lastKey = ""; sentAt = 0
                }
            }
            val n = mutableNow.value
            resolveArtist(cfg,n)
            val activity = discordActivity(n.track,cfg,n.playing,n.played,n.position,n.duration,time)
            val key = (activity?.let { JSONObject(it.toString()).apply { remove("timestamps") }.toString() } ?: "clear") + "|${n.playing}|${n.duration}"
            val seeked = abs(n.position - sentPosition - if (sentPlaying) time - sentAt else 0) > 3000
            val due = key != lastKey || seeked || time - sentAt >= 45000
            if (ipc.connected && due && time - sentAt >= 6000 && (activity != null || hadCard)) {
                // Read enable again at send time so a pending update cannot republish after disabling.
                if (store.settings.value.enabled) {
                    if (ipc.send(activity)) {
                        lastKey = key; sentAt = time; sentPosition = n.position; sentPlaying = n.playing; hadCard = activity != null
                    } else { ipc.close(); sentAt = time; retryAt = time + retryDelay; mutableStatus.value = "Reconnecting to Discord…" }
                }
            }
            delay(500)
        }
    }
    override fun connect(onResult: (Boolean,String,String) -> Unit) {
        store.update { it.copy(enabled=true) }
        onResult(true,"","")
    }
    override fun disconnect() { store.update { it.copy(enabled=false) } }
    override fun refresh() { /* Automatic reconnect and trailing updates keep the presence current. */ }
    override fun close() { scope.cancel(); ipc.close() }
}
