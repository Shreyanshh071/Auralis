package com.auralis.music

import android.graphics.SurfaceTexture
import android.view.Surface
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.test.platform.app.InstrumentationRegistry
import com.auralis.music.data.network.NetworkClientProvider
import com.auralis.music.domain.model.Track
import com.auralis.music.ui.player.CanvasLookup
import com.auralis.music.ui.player.CanvasSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Exercises a real provider and HLS/MP4 decoding on the phone, without touching its music queue. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class AnimatedCoverDeviceTest {
    @Test fun liveCoverRendersSilentlyAndPauses() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val url = CanvasLookup.find(context, Track(id = "motion-test", title = "Blinding Lights",
            artist = "The Weeknd", album = "After Hours"), CanvasSource.AUTO)
        assertNotNull("No animated cover returned by the live providers", url)
        val firstFrame = CountDownLatch(1)
        var error: PlaybackException? = null
        lateinit var player: ExoPlayer
        lateinit var texture: SurfaceTexture
        lateinit var surface: Surface
        instrumentation.runOnMainSync {
            texture = SurfaceTexture(false).apply { setDefaultBufferSize(600, 600) }
            surface = Surface(texture)
            player = ExoPlayer.Builder(context).setMediaSourceFactory(DefaultMediaSourceFactory(
                DefaultDataSource.Factory(context, OkHttpDataSource.Factory(NetworkClientProvider.okHttpClient)))).build()
            player.volume = 0f
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).build()
            player.repeatMode = Player.REPEAT_MODE_ONE
            player.setVideoSurface(surface)
            player.addListener(object : Player.Listener {
                override fun onRenderedFirstFrame() { firstFrame.countDown() }
                override fun onPlayerError(e: PlaybackException) { error = e; firstFrame.countDown() }
            })
            player.setMediaItem(MediaItem.fromUri(url!!))
            player.prepare()
            player.play()
        }
        try {
            assertTrue("No video frame within 45 seconds", firstFrame.await(45, TimeUnit.SECONDS))
            assertNull("Video decoder error: $error", error)
            var pausedAt = 0L
            instrumentation.runOnMainSync {
                assertTrue(player.videoFormat != null)
                assertEquals(0f, player.volume, 0f)
                player.pause()
                pausedAt = player.currentPosition
            }
            Thread.sleep(400)
            instrumentation.runOnMainSync {
                assertFalse(player.playWhenReady)
                assertEquals(pausedAt, player.currentPosition)
            }
        } finally {
            instrumentation.runOnMainSync { player.release(); surface.release(); texture.release() }
        }
    }
}
