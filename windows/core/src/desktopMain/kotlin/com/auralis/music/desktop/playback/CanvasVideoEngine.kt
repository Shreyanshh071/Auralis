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

    /** Starts [url] looping; frames (at most ~30 a second) go to [onFrame]. Returns a stop function. */
    fun play(url: String, onFrame: (ImageBitmap) -> Unit): (() -> Unit)? {
        val factory = factory ?: return null
        val player = factory.mediaPlayers().newEmbeddedMediaPlayer()
        var width = 0; var height = 0; var lastFrame = 0L
        val surface = factory.videoSurfaces().newVideoSurface(
            object : uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormatCallback {
                override fun getBufferFormat(sourceWidth: Int, sourceHeight: Int): uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormat {
                    width = sourceWidth; height = sourceHeight
                    return uk.co.caprica.vlcj.player.embedded.videosurface.callback.format.RV32BufferFormat(sourceWidth, sourceHeight)
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
                    val bytes = ByteArray(width * height * 4)
                    buffer.rewind(); buffer.get(bytes, 0, minOf(bytes.size, buffer.remaining()))
                    val image = org.jetbrains.skia.Image.makeRaster(
                        org.jetbrains.skia.ImageInfo(width, height, org.jetbrains.skia.ColorType.BGRA_8888, org.jetbrains.skia.ColorAlphaType.OPAQUE),
                        bytes, width * 4)
                    onFrame(image.toComposeImageBitmap())
                }
            },
            true)
        player.videoSurface().set(surface)
        player.controls().setRepeat(true)
        player.media().play(url, ":no-audio")
        return {
            // Stopping blocks briefly; never on the UI thread.
            Thread({ runCatching { player.controls().stop(); player.release() } }, "canvas-stop").apply { isDaemon = true }.start()
        }
    }
}

