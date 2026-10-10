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
    private val player: MediaPlayer
) {
    enum class State { IDLE, BUFFERING, PLAYING, PAUSED, ENDED, ERROR }

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state.asStateFlow()
    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs.asStateFlow()
    private val _lengthMs = MutableStateFlow(0L)
    val lengthMs: StateFlow<Long> = _lengthMs.asStateFlow()

    /** Called (on libVLC's thread) when the current media plays to the end. */
    @Volatile var onFinished: (() -> Unit)? = null
    /** Called (on libVLC's thread) when the current media fails to play. */
    @Volatile var onError: (() -> Unit)? = null

    init {
        player.events().addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
            override fun opening(mediaPlayer: MediaPlayer) { _state.value = State.BUFFERING }
            override fun buffering(mediaPlayer: MediaPlayer, newCache: Float) {
                if (newCache < 100f && _state.value != State.PAUSED) _state.value = State.BUFFERING
                else if (newCache >= 100f && _state.value == State.BUFFERING && mediaPlayer.status().isPlaying) _state.value = State.PLAYING
            }
            override fun playing(mediaPlayer: MediaPlayer) { _state.value = State.PLAYING }
            override fun paused(mediaPlayer: MediaPlayer) { _state.value = State.PAUSED }
            override fun stopped(mediaPlayer: MediaPlayer) {
                if (_state.value != State.ENDED && _state.value != State.ERROR) _state.value = State.IDLE
            }
            override fun finished(mediaPlayer: MediaPlayer) {
                _state.value = State.ENDED
                onFinished?.invoke()
            }
            override fun error(mediaPlayer: MediaPlayer) {
                _state.value = State.ERROR
                onError?.invoke()
            }
            override fun timeChanged(mediaPlayer: MediaPlayer, newTime: Long) { _positionMs.value = newTime }
            override fun lengthChanged(mediaPlayer: MediaPlayer, newLength: Long) { _lengthMs.value = newLength }
        })
    }

    fun play(url: String, startMs: Long = 0L) {
        _positionMs.value = startMs
        _lengthMs.value = 0L
        _state.value = State.BUFFERING
        val options = buildList {
            add(":http-user-agent=$USER_AGENT")
            add(":network-caching=1500")
            if (startMs > 0) add(":start-time=${startMs / 1000.0}")
        }
        if (!player.media().play(url, *options.toTypedArray())) {
            Log.w(TAG, "libVLC refused the stream")
            _state.value = State.ERROR
        }
    }

    fun pause() = player.controls().setPause(true)
    fun resume() = player.controls().setPause(false)

    fun seekTo(ms: Long) {
        player.controls().setTime(ms.coerceAtLeast(0L))
        _positionMs.value = ms.coerceAtLeast(0L)
    }

    fun stop() {
        player.controls().stop()
        _state.value = State.IDLE
        _positionMs.value = 0L
    }

    /** 0..1 */
    fun setVolume(volume: Float) {
        player.audio().setVolume((volume.coerceIn(0f, 1f) * 100).toInt())
    }

    fun release() {
        runCatching { player.release() }
        runCatching { factory.release() }
    }

    companion object {
        private const val TAG = "VlcAudioPlayer"
        @Volatile private var nativeLog: uk.co.caprica.vlcj.log.NativeLog? = null
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"

        /**
         * Finds libVLC: a copy bundled with the app (the installer's `vlc` folder, see
         * [bundledVlcDir]) first, then an installed VLC. Returns null when there is none.
         */
        fun createOrNull(bundledVlcDir: java.io.File? = null): VlcAudioPlayer? {
            return try {
                if (bundledVlcDir != null && java.io.File(bundledVlcDir, "libvlc.dll").exists()) {
                    System.setProperty("jna.library.path", bundledVlcDir.absolutePath)
                } else if (!NativeDiscovery().discover()) {
                    Log.w(TAG, "libVLC not found")
                    return null
                }
                val factory = MediaPlayerFactory("--no-video", "--quiet", "--intf=dummy")
                // libVLC's own errors (e.g. a stream it can't open) go to the app log.
                // Kept in a field: libVLC calls back into it, so it must not be garbage collected.
                nativeLog = factory.application().newLog()?.apply {
                    level = uk.co.caprica.vlcj.log.LogLevel.ERROR
                    addLogListener { _, module, _, _, _, _, _, message -> Log.w("libVLC", "$module: $message") }
                }
                VlcAudioPlayer(factory, factory.mediaPlayers().newMediaPlayer())
            } catch (t: Throwable) {
                Log.w(TAG, "libVLC failed to start: ${t.javaClass.simpleName} ${t.message}")
                null
            }
        }
    }
}
