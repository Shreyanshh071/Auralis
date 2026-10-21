package com.auralis.music.desktop.playback

import com.auralis.music.util.Log
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine

/**
 * Keeps the audio device streaming silence while music plays and for [lingerMs] after.
 *
 * libVLC closes its audio stream at the end of every song and opens a new one for the next (and
 * the first song opens one from cold). Bluetooth earbuds, HDMI sinks and some audio enhancers
 * suspend when no stream is open and take 1-3 s to wake, so the start of every song went unheard
 * although libVLC played it from 0:00. A silent stream that stays open between songs keeps the
 * device awake; [warm] opens it as soon as a song is requested, before its stream resolves.
 */
internal class AudioOutputKeepAlive(
    private val needed: () -> Boolean,
    private val lingerMs: Long = 60_000L
) {
    @Volatile private var thread: Thread? = null
    @Volatile private var startedNanos = 0L
    @Volatile private var lastNeededNanos = 0L
    @Volatile private var closed = false
    /**
     * The output's readable name (as libVLC lists it); null follows Windows' default device.
     * Checked on every write, so a change reopens the stream on the new device within 20 ms.
     */
    @Volatile var deviceName: String? = null

    /** Opens the silent stream if it isn't open; returns how long it has been open, in ms. */
    @Synchronized fun warm(): Long {
        lastNeededNanos = System.nanoTime()
        if (closed) return Long.MAX_VALUE
        if (thread?.isAlive != true) {
            startedNanos = System.nanoTime()
            thread = Thread(::run, "audio-keep-alive").apply { isDaemon = true; start() }
            return 0L
        }
        return (System.nanoTime() - startedNanos) / 1_000_000L
    }

    fun close() { closed = true }

    private fun run() {
        val format = AudioFormat(48_000f, 16, 2, true, false)
        val silence = ByteArray(format.frameSize * 48_000 / 50) // 20 ms
        while (!closed) {
            val name = deviceName
            val line = runCatching { openLine(format, name) }.getOrElse {
                Log.w(TAG, "No silent stream for the audio device: ${it.message}")
                return
            }
            try {
                line.start()
                while (!closed && name == deviceName) {
                    if (needed()) lastNeededNanos = System.nanoTime()
                    else if ((System.nanoTime() - lastNeededNanos) / 1_000_000L > lingerMs) return
                    line.write(silence, 0, silence.size) // Blocks for about 20 ms once the buffer is full.
                }
            } finally {
                runCatching { line.stop(); line.close() }
            }
        }
    }

    private fun openLine(format: AudioFormat, name: String?): SourceDataLine {
        val mixer = name?.let { wanted ->
            AudioSystem.getMixerInfo().firstOrNull { it.name.equals(wanted, true) }
                ?: AudioSystem.getMixerInfo().firstOrNull { it.name.startsWith(wanted.take(31), true) }
        }
        val line = if (mixer != null) AudioSystem.getSourceDataLine(format, mixer) else AudioSystem.getSourceDataLine(format)
        line.open(format, format.frameSize * 48_000 / 10) // 100 ms buffer
        return line
    }

    private companion object { const val TAG = "AudioKeepAlive" }
}
