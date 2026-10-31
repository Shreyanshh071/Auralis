package com.auralis.music.bot

import com.auralis.music.util.Log
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventAdapter
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason
import com.sedmelluq.discord.lavaplayer.track.playback.MutableAudioFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.dv8tion.jda.api.audio.AudioSendHandler
import net.dv8tion.jda.api.audio.hooks.ConnectionListener
import net.dv8tion.jda.api.audio.hooks.ConnectionStatus
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel
import java.nio.ByteBuffer

enum class IdleReason(val goodbye: String) {
    ALONE("👋 Nobody's been listening for 5 minutes, so I left the voice channel."),
    NOTHING_PLAYING("💤 Nothing's played for 5 minutes, so I left the voice channel."),
    PAUSED("😴 I've been paused for 5 minutes, so I left the voice channel.")
}

/** Why the bot is idle in voice, or null while someone is listening to music. */
fun idleReason(listeners: Int, playing: Boolean, paused: Boolean): IdleReason? = when {
    listeners == 0 -> IdleReason.ALONE
    !playing -> IdleReason.NOTHING_PLAYING
    paused -> IdleReason.PAUSED
    else -> null
}

/** Everything playing in one server: its queue, its audio player and its voice connection. */
class GuildPlayer(
    private val guild: Guild,
    private val manager: AudioPlayerManager,
    private val catalog: AuralisCatalog,
    private val scope: CoroutineScope,
    private val onClosed: (GuildPlayer) -> Unit
) : AudioEventAdapter() {
    private val audio: AudioPlayer = manager.createPlayer().also { it.addListener(this) }
    private val queue = TrackQueue()
    private val messages = NowPlayingMessages()
    /** Bumped whenever the song changes, so a lookup for a skipped song can't start it. */
    private var generation = 0L
    private var autoplayJob: Job? = null
    private var leaveJob: Job? = null
    private var idleFor: IdleReason? = null
    private var closed = false
    @Volatile private var requestedChannel: AudioChannel? = null

    val paused: Boolean get() = audio.isPaused
    val volume: Int get() = audio.volume
    val positionMs: Long get() = audio.playingTrack?.position ?: 0L
    val durationMs: Long get() = audio.playingTrack?.duration ?: 0L
    @get:Synchronized val current: QueuedTrack? get() = queue.current
    @get:Synchronized val upcoming: List<QueuedTrack> get() = queue.tracks
    var loop: LoopMode
        @Synchronized get() = queue.loop
        @Synchronized set(value) { queue.loop = value }
    @Volatile var autoplay = false
        private set

    /** The bot's voice channel, including one it's still joining, when Discord reports none yet. */
    val voiceChannel: AudioChannel? get() = guild.audioManager.connectedChannel ?: requestedChannel

    fun connect(channel: AudioChannel) {
        guild.audioManager.sendingHandler = SendHandler(audio)
        guild.audioManager.isSelfDeafened = true
        guild.audioManager.connectionListener = object : ConnectionListener {
            override fun onStatusChange(status: ConnectionStatus) {
                if (status.name.startsWith("ERROR_")) messages.say("📡 I'm having trouble with the voice connection.")
            }
        }
        guild.audioManager.openAudioConnection(channel)
        requestedChannel = channel
    }

    fun announce(text: String) = messages.say(text)

    /** Sends right away, waiting for it: for when the bot is about to shut down. */
    fun announceNow(text: String) = messages.sayNow(text)

    fun announceIn(channel: GuildMessageChannel) { messages.channel = channel }

    /** Queues [items]; returns the queue position of the first, or 0 when it starts right away. */
    @Synchronized fun enqueue(items: List<QueuedTrack>, next: Boolean = false): Int {
        val idle = queue.current == null
        val position = if (next) 1 else queue.tracks.size + 1
        queue.add(items, next)
        if (!idle) return position
        autoplayJob?.cancel()
        advance()
        return 0
    }

    @Synchronized fun skip() = advance(skipped = true)

    @Synchronized fun setPaused(paused: Boolean) {
        audio.isPaused = paused
        messages.refresh(queue.current, this)
        updateIdleTimer()
    }

    @Synchronized fun togglePause() = setPaused(!audio.isPaused)

    fun setVolume(percent: Int) { audio.volume = percent }

    fun seek(positionMs: Long): Boolean {
        val track = audio.playingTrack?.takeIf { it.isSeekable } ?: return false
        track.position = positionMs.coerceIn(0L, track.duration)
        return true
    }

    @Synchronized fun remove(position: Int) = queue.remove(position)
    @Synchronized fun move(from: Int, to: Int) = queue.move(from, to)
    @Synchronized fun shuffle() = queue.shuffle()

    @Synchronized fun toggleAutoplay(): Boolean {
        autoplay = !autoplay
        return autoplay
    }

    /** Stops playback and empties the queue, staying in the channel for a while. */
    @Synchronized fun stop() {
        generation++
        autoplayJob?.cancel()
        queue.clear()
        audio.stopTrack()
        audio.isPaused = false
        messages.finish()
        updateIdleTimer()
    }

    /** Leaves the voice channel and forgets this server's player. */
    @Synchronized fun close() {
        if (closed) return
        closed = true
        generation++
        autoplayJob?.cancel()
        leaveJob?.cancel()
        queue.clear()
        messages.finish()
        audio.destroy()
        guild.audioManager.closeAudioConnection()
        guild.audioManager.sendingHandler = null
        onClosed(this)
    }

    /**
     * Leaves once idle for [IDLE_LEAVE_MS]; called on every change. The same reason carrying on
     * keeps its countdown, or voice activity elsewhere in the server would keep resetting it.
     */
    @Synchronized fun updateIdleTimer() {
        val listeners = guild.audioManager.connectedChannel?.members?.count { !it.user.isBot } ?: 1
        val reason = idleReason(listeners, playing = queue.current != null, paused = audio.isPaused)
        if (reason == idleFor && leaveJob?.isActive == true) return
        leaveJob?.cancel()
        idleFor = reason
        if (reason == null) return
        leaveJob = scope.launch {
            delay(IDLE_LEAVE_MS)
            messages.say(reason.goodbye)
            close()
        }
    }

    private fun advance(skipped: Boolean = false) {
        val started = ++generation
        val item = queue.advance(skipped)
        audio.stopTrack()
        if (item == null) {
            messages.finish()
            if (autoplay) {
                messages.say("📻 The queue's finished, finding more songs.")
                extendWithRadio(started)
            } else {
                messages.say("🏁 That's the end of the queue.")
                updateIdleTimer()
            }
            return
        }
        audio.isPaused = false
        updateIdleTimer()
        scope.launch { load(item, started) }
    }

    private fun extendWithRadio(generation: Long) {
        val seed = lastPlayed ?: return updateIdleTimer()
        autoplayJob = scope.launch {
            val found = runCatching { catalog.radio(seed.track) }.getOrDefault(emptyList())
            synchronized(this@GuildPlayer) {
                if (generation != this@GuildPlayer.generation) return@launch
                val recent = queue.recentIds
                val batch = found.filter { it.id.isNotBlank() && it.id !in recent }.distinctBy { it.id }.take(RADIO_BATCH)
                if (batch.isEmpty()) {
                    messages.say("🤷 Autoplay couldn't find anything else.")
                    updateIdleTimer()
                } else enqueue(batch.map { QueuedTrack(it, null) })
            }
        }
    }

    private var lastPlayed: QueuedTrack? = null

    private suspend fun load(item: QueuedTrack, generation: Long) {
        val url = runCatching { catalog.streamUrl(item.track) }
            .onFailure { Log.w(TAG, "Lookup failed for ${item.track.id}: ${it.message}") }.getOrNull()
        if (url == null) return failed(item, generation)
        manager.loadItemOrdered(this, url, object : AudioLoadResultHandler {
            override fun trackLoaded(track: AudioTrack) = start(item, track, generation)
            override fun playlistLoaded(playlist: AudioPlaylist) {
                playlist.tracks.firstOrNull()?.let { start(item, it, generation) } ?: failed(item, generation)
            }
            override fun noMatches() = failed(item, generation)
            override fun loadFailed(exception: FriendlyException) {
                Log.w(TAG, "Couldn't open ${item.track.id}: ${exception.message}")
                failed(item, generation)
            }
        })
    }

    @Synchronized private fun start(item: QueuedTrack, track: AudioTrack, generation: Long) {
        if (generation != this.generation) return
        lastPlayed = item
        track.userData = item
        audio.playTrack(track)
        messages.nowPlaying(item, this)
    }

    @Synchronized private fun failed(item: QueuedTrack, generation: Long) {
        if (generation != this.generation) return
        messages.say("⚠️ Couldn't play **${item.track.title.escapedMarkdown()}**, skipping it.")
        advance(skipped = true)
    }

    override fun onTrackEnd(player: AudioPlayer, track: AudioTrack, endReason: AudioTrackEndReason) {
        // Only natural ends move on; a skip or stop already chose what happens next.
        if (!endReason.mayStartNext) return
        synchronized(this) { advance(skipped = endReason != AudioTrackEndReason.FINISHED) }
    }

    override fun onTrackException(player: AudioPlayer, track: AudioTrack, exception: FriendlyException) {
        Log.w(TAG, "Playback failed: ${exception.message}")
        synchronized(this) { messages.say("💥 Playback broke off, moving on.") }
    }

    /** Hands JDA Opus frames straight from the player, re-encoding nothing. */
    private class SendHandler(private val player: AudioPlayer) : AudioSendHandler {
        private val buffer = ByteBuffer.allocate(1024)
        private val frame = MutableAudioFrame().also { it.setBuffer(buffer) }

        override fun canProvide(): Boolean = player.provide(frame)
        override fun provide20MsAudio(): ByteBuffer = buffer.flip()
        override fun isOpus() = true
    }

    private companion object {
        const val TAG = "GuildPlayer"
        const val IDLE_LEAVE_MS = 5 * 60_000L
        const val RADIO_BATCH = 14
    }
}
