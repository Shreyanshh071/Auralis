package com.auralis.music.desktop.playback

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.auralis.music.util.Log
import java.nio.ByteBuffer

/** A silent, looping VLC player per video, drawing its frames into the cover. */
object CanvasVideoEngine {
    private val factory by lazy {
        runCatching {
            uk.co.caprica.vlcj.factory.MediaPlayerFactory("--quiet", "--intf=dummy", "--no-audio", "--no-osd", "--no-video-title-show",
                // The best version of the stream (Apple's HLS starts low and a 720 cap kept it there:
                // ~500px video stretched over the cover), up to 1440 square.
                "--no-snapshot-preview", "--adaptive-logic=highest", "--adaptive-maxwidth=1440", "--adaptive-maxheight=1440")
        }.onFailure { Log.w("AnimatedCovers", "No video playback available: ${it.message}") }.getOrNull()
    }
    val available: Boolean get() = factory != null

    /**
     * Frames are scaled by VLC to at most this on their shorter side: covers show them at 460dp or
     * less, so the full 1440 square was ~4x the pixels needed. Each such 8MB frame was copied into a
     * new array and uploaded to the GPU 30 times a second, and made full-window frames miss the
     * display's refresh, with a 30ms hitch every few frames.
     */
    private const val FRAME_SHORT_SIDE = 960

    /** Starts [url] looping; frames (at most ~30 a second) go to [onFrame]. Returns a stop function. */
    fun play(url: String, clock: com.auralis.music.data.service.PlaybackClockSource? = null, onFrame: (ImageBitmap) -> Unit): (() -> Unit)? {
        val factory = factory ?: return null
        val player = factory.mediaPlayers().newEmbeddedMediaPlayer()
        var width = 0; var height = 0; var lastFrame = 0L
        // One copy buffer per player: Skia copies the pixels into each frame's own image.
        var bytes = ByteArray(0)
        val surface = factory.videoSurfaces().newVideoSurface(
            object : uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormatCallback {
                override fun getBufferFormat(sourceWidth: Int, sourceHeight: Int): uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormat {
                    val scale = minOf(1f, FRAME_SHORT_SIDE.toFloat() / minOf(sourceWidth, sourceHeight).coerceAtLeast(1))
                    width = (sourceWidth * scale).toInt().coerceAtLeast(1); height = (sourceHeight * scale).toInt().coerceAtLeast(1)
                    return uk.co.caprica.vlcj.player.embedded.videosurface.callback.format.RV32BufferFormat(width, height)
                }
                override fun allocatedBuffers(buffers: Array<out ByteBuffer>) {}
            },
            object : uk.co.caprica.vlcj.player.embedded.videosurface.callback.RenderCallback {
                override fun display(mediaPlayer: uk.co.caprica.vlcj.player.base.MediaPlayer, nativeBuffers: Array<out ByteBuffer>,
                    bufferFormat: uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormat) {
                    val now = System.nanoTime()
                    if (now - lastFrame < 33_000_000L || width <= 0 || height <= 0) return
                    lastFrame = now
                    val buffer = nativeBuffers[0]
                    if (bytes.size != width * height * 4) bytes = ByteArray(width * height * 4)
                    buffer.rewind(); buffer.get(bytes, 0, minOf(bytes.size, buffer.remaining()))
                    val image = org.jetbrains.skia.Image.makeRaster(
                        org.jetbrains.skia.ImageInfo(width, height, org.jetbrains.skia.ColorType.BGRA_8888, org.jetbrains.skia.ColorAlphaType.OPAQUE),
                        bytes, width * 4)
                    onFrame(image.toComposeImageBitmap())
                }
            },
            true)
        player.videoSurface().set(surface)
        val active = java.util.concurrent.atomic.AtomicBoolean(true)
        player.controls().setRepeat(clock == null)
        player.media().play(url, ":no-audio")
        if (clock != null) Thread({
            while (active.get()) {
                runCatching {
                    val position = clock.rawPositionMs()
                    if (kotlin.math.abs(player.status().time() - position) > 600) player.controls().setTime(position)
                    player.controls().setPause(!clock.isPlaying())
                }
                Thread.sleep(200)
            }
        }, "music-video-sync").apply { isDaemon = true; start() }
        return {
            active.set(false)
            // Stopping blocks briefly; never on the UI thread.
            Thread({ runCatching { player.controls().stop(); player.release() } }, "canvas-stop").apply { isDaemon = true }.start()
        }
    }
}

