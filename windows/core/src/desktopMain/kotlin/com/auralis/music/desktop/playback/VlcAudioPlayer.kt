package com.auralis.music.desktop.playback

import com.auralis.music.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter

/**
 * Audio playback through libVLC (VLCJ). It plays the stream URLs NewPipe returns (Opus/WebM and
 * AAC/MP4) and reports state, position and length as flows.
 *
 * libVLC calls the event listener on its own thread, and calling back into libVLC from there can
 * deadlock, so the listener only updates flows and [onFinished] must hand off to another thread.
 */
class VlcAudioPlayer private constructor(
    private val factory: MediaPlayerFactory,
    @Volatile private var player: MediaPlayer,
    private val nativeLog: uk.co.caprica.vlcj.log.NativeLog?
) {
    enum class State { IDLE, BUFFERING, PLAYING, PAUSED, ENDED, ERROR }

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state.asStateFlow()
    data class MediaState(val trackId: String?, val state: State)
    private val _mediaState = MutableStateFlow(MediaState(null, State.IDLE))
    val mediaState: StateFlow<MediaState> = _mediaState.asStateFlow()
    @Volatile private var mediaTrackId: String? = null
    private fun publishState(state: State) {
        _state.value = state
        _mediaState.value = MediaState(mediaTrackId, state)
    }
    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs.asStateFlow()
    private val _lengthMs = MutableStateFlow(0L)
    val lengthMs: StateFlow<Long> = _lengthMs.asStateFlow()

    // The last position libVLC reported and when (System.nanoTime), for [clock].
    @Volatile private var anchorMs = 0L
    @Volatile private var anchorNanos = System.nanoTime()
    @Volatile private var lastClockMs = 0L
    private val seekGate = PlaybackSeekGate()

    private fun anchor(ms: Long) {
        anchorMs = ms
        anchorNanos = System.nanoTime()
        lastClockMs = ms
        _positionMs.value = ms
    }
    @Volatile private var playbackRate = 1f
    fun setRate(rate: Float) {
        if (rate == playbackRate) return
        anchor(clock.rawPositionMs())
        playbackRate = rate.coerceIn(0.5f, 2f)
        player.controls().setRate(playbackRate)
    }

    /**
     * The playback clock for word-synced lyrics. libVLC reports the position only every few
     * hundred milliseconds, while the phone's lyrics clock carries a reading forward by at most
     * 100 ms. So this interpolates between reports with the wall clock (up to [MAX_INTERPOLATION_MS])
     * and never steps backwards on a late report; a seek or a new song re-anchors it.
     */
    val clock = object : com.auralis.music.data.service.PlaybackClockSource {
        override fun rawPositionMs(): Long {
            if (_state.value != State.PLAYING || seekGate.isPending()) return lastClockMs
            val elapsed = ((System.nanoTime() - anchorNanos) / 1_000_000L).coerceIn(0L, MAX_INTERPOLATION_MS)
            val ms = maxOf(anchorMs + (elapsed * playbackRate).toLong(), lastClockMs)
            lastClockMs = ms
            return ms
        }
        override fun isPlaying(): Boolean = _state.value == State.PLAYING
        override fun speed(): Float = playbackRate
        override fun isBuffering(): Boolean = _state.value == State.BUFFERING
    }

    /** Called (on libVLC's thread) when the current media plays to the end. */
    @Volatile var onFinished: (() -> Unit)? = null
    /** Called (on libVLC's thread) when the current media fails to play. */
    @Volatile var onError: (() -> Unit)? = null

    private val events = object : MediaPlayerEventAdapter() {
            override fun opening(mediaPlayer: MediaPlayer) { if (mediaPlayer !== player) return; publishState(State.BUFFERING) }
            override fun buffering(mediaPlayer: MediaPlayer, newCache: Float) { if (mediaPlayer !== player) return;
                if (newCache < 100f && _state.value != State.PAUSED) publishState(State.BUFFERING)
                else if (newCache >= 100f && _state.value == State.BUFFERING && mediaPlayer.status().isPlaying) publishState(State.PLAYING)
            }
            override fun playing(mediaPlayer: MediaPlayer) { if (mediaPlayer !== player) return;
                // A newly opened audio output can restore VLC's own volume; apply our saved
                // value once it is ready, outside the native event thread.
                mediaPlayer.submit { requestedVolume?.let { mediaPlayer.audio().setVolume((it * 100).toInt()) } }
                if (roomStartHeld) {
                    // Readiness begins on the paused callback, after the native pause.
                    publishState(State.BUFFERING)
                    // Native controls must run outside the libVLC event thread.
                    mediaPlayer.submit {
                        if (roomStartHeld) mediaPlayer.controls().setPause(true)
                        else { anchorNanos = System.nanoTime(); publishState(State.PLAYING) }
                    }
                    return
                }
                // libVLC opens a fresh audio output per song; keep the device the user picked.
                preferredDevice?.let { runCatching { mediaPlayer.audio().setOutputDevice(null, it) } }
                anchorNanos = System.nanoTime()
                publishState(State.PLAYING)
            }
            override fun paused(mediaPlayer: MediaPlayer) { if (mediaPlayer !== player) return;
                anchor(lastClockMs.coerceAtLeast(anchorMs))
                publishState(State.PAUSED)
            }
            override fun stopped(mediaPlayer: MediaPlayer) { if (mediaPlayer !== player) return;
                if (_state.value != State.ENDED && _state.value != State.ERROR) publishState(State.IDLE)
            }
            override fun finished(mediaPlayer: MediaPlayer) { if (mediaPlayer !== player) return;
                publishState(State.ENDED)
                onFinished?.invoke()
            }
            override fun error(mediaPlayer: MediaPlayer) { if (mediaPlayer !== player) return;
                publishState(State.ERROR)
                onError?.invoke()
            }
            override fun timeChanged(mediaPlayer: MediaPlayer, newTime: Long) { if (mediaPlayer !== player) return;
                synchronized(seekGate) {
                    val landingSeek = seekGate.isPending()
                    if (!seekGate.accept(newTime, System.nanoTime(), playbackRate)) return
                    // The first decoded seek report replaces the optimistic target, even
                    // when the decoder landed a little before it. Monotonic playback
                    // smoothing must not preserve that timing error after a seek.
                    if (!landingSeek && newTime < lastClockMs && lastClockMs - newTime < SEEK_THRESHOLD_MS) {
                        anchorMs = newTime
                        anchorNanos = System.nanoTime()
                        _positionMs.value = newTime
                    } else {
                        anchor(newTime)
                    }
                }
            }
            override fun lengthChanged(mediaPlayer: MediaPlayer, newLength: Long) { if (mediaPlayer !== player) return; _lengthMs.value = newLength }
    }
    init { player.events().addMediaPlayerEventListener(events) }

    @Volatile var crossfadeSeconds: Float = 0f
    @Volatile private var fading: MediaPlayer? = null
    @Volatile private var fadeRevision = 0L
    private fun stopFade() {
        fadeRevision++
        val previous = fading; fading = null
        if (previous != null) runCatching { previous.controls().stop(); previous.release() }
    }

    private val keepAlive = AudioOutputKeepAlive({ _state.value == State.PLAYING || _state.value == State.BUFFERING })
    /** Wakes the audio device ahead of a song (see [AudioOutputKeepAlive]); returns how long it has been awake, in ms. */
    fun warmOutput(): Long = keepAlive.warm()

    @Volatile var normalizeVolume: Boolean = false
    fun play(url: String, startMs: Long = 0L, trackId: String? = null) {
        seekGate.reset()
        stopFade()
        keepAlive.warm()
        val outgoing = player.takeIf { crossfadeSeconds > 0 && startMs == 0L && it.status().isPlaying }
        if (outgoing != null) {
            outgoing.events().removeMediaPlayerEventListener(events)
            player = factory.mediaPlayers().newMediaPlayer()
            player.events().addMediaPlayerEventListener(events)
            fading = outgoing
            player.audio().setVolume(0)
        }
        mediaTrackId = trackId
        anchor(startMs)
        _lengthMs.value = 0L
        publishState(State.BUFFERING)
        val options = buildList {
            if (normalizeVolume) { add(":audio-filter=normvol"); add(":norm-max-level=2.0") }
            add(":http-user-agent=$USER_AGENT")
            // The loopback relay caches and prefetches audio; keep a smooth 800ms buffer
            // to avoid underruns on small IO fluctuations while keeping seeks responsive.
            add(":network-caching=${if (url.startsWith("http://127.0.0.1:")) RELAY_CACHING_MS else 1500}")
            if (startMs > 0) add(":start-time=${startMs / 1000.0}")
        }
        if (!player.media().play(url, *options.toTypedArray())) {
            Log.w(TAG, "libVLC refused the stream")
            publishState(State.ERROR)
        } else player.controls().setRate(playbackRate)
        if (outgoing != null) {
            val revision = fadeRevision
            val incoming = player
            val duration = (crossfadeSeconds.coerceIn(.2f, 12f) * 1000).toLong()
            Thread({
                val waiting = System.nanoTime()
                while (fadeRevision == revision && !incoming.status().isPlaying && (System.nanoTime()-waiting)/1_000_000 < 15_000) Thread.sleep(25)
                val started = System.nanoTime()
                while (fadeRevision == revision) {
                    val fraction = (((System.nanoTime()-started)/1_000_000).toFloat()/duration).coerceIn(0f,1f)
                    val volume = requestedVolume ?: 1f
                    runCatching { outgoing.audio().setVolume((volume*100*(1-fraction)).toInt()); incoming.audio().setVolume((volume*100*fraction).toInt()) }
                    if (fraction >= 1f) break
                    Thread.sleep(25)
                }
                if (fadeRevision == revision) { fading = null; runCatching { outgoing.controls().stop(); outgoing.release() } }
            }, "audio-crossfade").apply { isDaemon=true;start() }
        }
    }

    fun pause() { stopFade(); player.controls().setPause(true) }
    fun resume() { keepAlive.warm(); player.controls().setPause(false) }
    @Volatile private var roomStartHeld = false
    fun holdStartForRoom() { roomStartHeld = true }
    fun releaseRoomStart() {
        val held = roomStartHeld
        roomStartHeld = false
        if (held && _state.value == State.PAUSED) resume()
    }

    fun seekTo(ms: Long) {
        val request = synchronized(seekGate) {
            seekGate.request(ms, System.nanoTime()).also { anchor(it.targetMs) }
        }
        val seekingPlayer = player
        // Native seeking may wait for IO. Keep it off Compose's UI thread, and skip
        // queued clicks superseded by a newer click rather than seeking through them.
        seekingPlayer.submit {
            if (seekingPlayer === player && seekGate.isCurrent(request)) {
                stopFade()
                seekingPlayer.controls().setTime(request.targetMs)
            }
        }
    }

    fun stop() {
        seekGate.reset()
        stopFade()
        player.controls().stop()
        publishState(State.IDLE)
        _lengthMs.value = 0L
        anchor(0L)
    }

    /** The output chosen in the player bar; null follows Windows' default device. */
    @Volatile var preferredDevice: String? = null
        private set

    /** MMDevice also works before VLC has opened an audio stream or while paused. */
    fun outputDevices(): List<Pair<String, String>> {
        val native = runCatching { WindowsAudioOutputs.read() }.onFailure {
            Log.w("AudioOutputs", "Windows endpoint lookup failed: ${it.message}")
        }.getOrDefault(emptyList())
        val vlc = if (native.isEmpty()) runCatching {
            player.audio().outputDevices().map { it.deviceId to it.longName }
        }.getOrDefault(emptyList()) else emptyList()
        return WindowsAudioOutputs.menu(native, vlc)
    }

    fun setOutputDevice(id: String?) {
        preferredDevice = id
        keepAlive.deviceName = id?.let { wanted -> outputDevices().firstOrNull { it.first == wanted }?.second }
        runCatching { player.audio().setOutputDevice(null, id ?: "") }
    }

    /** 0..1 */
    @Volatile private var requestedVolume: Float? = null
    fun currentVolume(): Float = runCatching { player.audio().volume().takeIf { it in 0..100 }?.div(100f) }.getOrNull() ?: 1f
    fun setVolume(volume: Float) {
        requestedVolume = volume.coerceIn(0f, 1f)
        player.audio().setVolume((volume.coerceIn(0f, 1f) * 100).toInt())
    }

    fun release() {
        seekGate.reset()
        stopFade()
        keepAlive.close()
        runCatching { player.release() }
        runCatching { nativeLog?.release() }
        runCatching { factory.release() }
    }

    companion object {
        private const val TAG = "VlcAudioPlayer"
        private const val MAX_INTERPOLATION_MS = 1000L
        private const val SEEK_THRESHOLD_MS = 600L
        private val RELAY_CACHING_MS = System.getProperty("auralis.relayCachingMs")?.toIntOrNull() ?: 800

        /** Which libVLC was loaded: "bundled (<dir>)" or "installed VLC"; null before [createOrNull]. */
        @Volatile var loadedFrom: String? = null
            private set
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

        /**
         * Finds libVLC: a copy bundled with the app (the installer's `vlc` folder, see
         * [bundledVlcDir]) first, then an installed VLC. Returns null when there is none.
         */
        /**
         * Without plugins.dat libVLC loads every plugin DLL at startup (each one scanned by the
         * antivirus, under the process-wide loader lock), which measured 5-20s inside the app and
         * held up its window. The cache stores each plugin's file time, which packaging and
         * installing change, so it is written here, on the installed copy: when it is missing or
         * older than any bundled file, this start rebuilds it; later starts load only what they use.
         */
        internal fun pluginCacheArgs(vlcDir: java.io.File): List<String> {
            val plugins = java.io.File(vlcDir, "plugins")
            if (!plugins.isDirectory) return emptyList()
            val cache = java.io.File(plugins, "plugins.dat")
            val newest = plugins.walkTopDown().filter { it.isFile && it.extension.equals("dll", true) }
                .plus(java.io.File(vlcDir, "libvlccore.dll")).maxOfOrNull { it.lastModified() } ?: 0L
            if (cache.isFile && cache.lastModified() >= newest) return emptyList()
            Log.i(TAG, "Rebuilding libVLC plugin cache in ${plugins.absolutePath}")
            return listOf("--reset-plugins-cache")
        }

        fun createOrNull(bundledVlcDir: java.io.File? = null): VlcAudioPlayer? {
            val started = System.currentTimeMillis()
            return try {
                if (bundledVlcDir != null && java.io.File(bundledVlcDir, "libvlc.dll").exists()) {
                    System.setProperty("jna.library.path", bundledVlcDir.absolutePath)
                    loadedFrom = "bundled (${bundledVlcDir.absolutePath})"
                } else if (NativeDiscovery().discover()) {
                    loadedFrom = "installed VLC"
                } else {
                    Log.w(TAG, "libVLC not found")
                    return null
                }
                Log.i(TAG, "Using libVLC: $loadedFrom")
                val cacheArgs = bundledVlcDir?.let(::pluginCacheArgs).orEmpty()
                val factory = MediaPlayerFactory("--no-video", "--quiet", "--intf=dummy", *cacheArgs.toTypedArray())
                // libVLC's own errors (e.g. a stream it can't open) go to the app log.
                // Kept in a field: libVLC calls back into it, so it must not be garbage collected.
                val nativeLog = factory.application().newLog()?.apply {
                    level = uk.co.caprica.vlcj.log.LogLevel.ERROR
                    addLogListener { _, module, _, _, _, _, _, message ->
                        // vlcj sets video options (marquee, logo, vmem) whose plugins the bundled
                        // audio-only copy leaves out; those complaints are expected.
                        if (!(message.startsWith("option ") && message.endsWith("does not exist"))) {
                            Log.w("libVLC", "$module: $message")
                        }
                    }
                }
                VlcAudioPlayer(factory, factory.mediaPlayers().newMediaPlayer(), nativeLog).also {
                    Log.i(TAG, "libVLC ready in ${System.currentTimeMillis() - started}ms")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "libVLC failed to start: ${t.javaClass.simpleName} ${t.message}")
                null
            }
        }
    }
}
