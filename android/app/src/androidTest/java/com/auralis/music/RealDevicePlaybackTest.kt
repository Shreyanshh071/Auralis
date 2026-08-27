package com.auralis.music

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.auralis.music.data.network.AudioStreamResolver
import com.auralis.music.data.network.NetworkClientProvider
import com.auralis.music.data.network.PlayerJsCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class RealDevicePlaybackTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setup() {
        AudioStreamResolver.init(context)
        PlayerJsCache.init(context)
    }

    @Test
    fun testRealDeviceEndToEndPlaybackTimings() = runBlocking {
        val testTracks = listOf(
            Triple("opwZ_PJ-F_E", "Choo Lo", "The Local Train"),
            Triple("EwLgGHAxTa8", "Raanjhanaa", "A.R. Rahman"),
            Triple("1lyu1KKwC74", "Bitter Sweet Symphony", "The Verve"),
            Triple("34Na4j8AVgA", "Starboy", "The Weeknd"),
            Triple("H5v3kku4y6Q", "As It Was", "Harry Styles")
        )

        println("==========================================================================")
        println("REAL PHYSICAL DEVICE (ZA222LJBW2) END-TO-END PLAYBACK TIMINGS")
        println("==========================================================================")

        for ((id, title, artist) in testTracks) {
            val t0Tap = System.currentTimeMillis()

            // 1. Resolver stage
            val streamUrl = AudioStreamResolver.resolveAudioStream(id, title, artist)
            val tResolved = System.currentTimeMillis()
            val resolveMs = tResolved - t0Tap

            assertNotNull("Stream URL must not be null for $title", streamUrl)

            // 2. Real ExoPlayer playback on Android Main Looper
            var tPrepared = 0L
            var tBuffering = 0L
            var tReady = 0L
            var tFirstAudio = 0L
            var playbackError: String? = null

            val latch = CountDownLatch(1)

            withContext(Dispatchers.Main) {
                val httpDataSourceFactory = androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(
                    NetworkClientProvider.okHttpClient
                ).setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")

                val mediaSourceFactory = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(context)
                    .setDataSourceFactory(httpDataSourceFactory)

                val player = ExoPlayer.Builder(context)
                    .setMediaSourceFactory(mediaSourceFactory)
                    .build()

                player.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        when (playbackState) {
                            Player.STATE_BUFFERING -> {
                                if (tBuffering == 0L) tBuffering = System.currentTimeMillis()
                            }
                            Player.STATE_READY -> {
                                if (tReady == 0L) tReady = System.currentTimeMillis()
                            }
                            Player.STATE_ENDED, Player.STATE_IDLE -> {}
                        }
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        if (isPlaying && tFirstAudio == 0L) {
                            tFirstAudio = System.currentTimeMillis()
                            player.stop()
                            player.release()
                            latch.countDown()
                        }
                    }

                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        playbackError = "${error.errorCodeName}: ${error.message}"
                        player.release()
                        latch.countDown()
                    }
                })

                val mediaItem = MediaItem.fromUri(Uri.parse(streamUrl))
                player.setMediaItem(mediaItem)
                player.prepare()
                tPrepared = System.currentTimeMillis()
                player.play()
            }

            // Wait up to 15 seconds for audible audio
            val completed = latch.await(15, TimeUnit.SECONDS)

            val totalTapToAudioMs = if (tFirstAudio > 0) tFirstAudio - t0Tap else -1L
            val exoPrepMs = if (tPrepared > 0) tPrepared - tResolved else -1L
            val exoBufferMs = if (tBuffering > 0) tBuffering - tResolved else -1L
            val exoReadyMs = if (tReady > 0) tReady - tResolved else -1L
            val exoPlayMs = if (tFirstAudio > 0) tFirstAudio - tPrepared else -1L

            println("Track: '$title' ($id)")
            println("  Resolution Time:    ${resolveMs}ms")
            println("  ExoPlayer Prepare:  ${exoPrepMs}ms")
            println("  ExoPlayer Buffer:   ${exoBufferMs}ms")
            println("  ExoPlayer Ready:    ${exoReadyMs}ms")
            println("  ExoPlayer Audio:    ${exoPlayMs}ms")
            println("  Total Tap-To-Audio: ${totalTapToAudioMs}ms")
            if (playbackError != null) {
                println("  ERROR: $playbackError")
            }
            println("--------------------------------------------------------------------------")

            assertTrue("Playback must not have error for $title: $playbackError", playbackError == null)
            assertTrue("Audio must start playing for $title", totalTapToAudioMs > 0)
        }
    }

    @Test
    fun testWarmAndRapidSwitching() = runBlocking {
        val idA = "opwZ_PJ-F_E" // Choo Lo
        val idB = "EwLgGHAxTa8" // Raanjhanaa

        // 1. Initial resolution to populate memory cache
        AudioStreamResolver.resolveAudioStream(idA, "Choo Lo", "The Local Train")

        // 2. Warm Cached Playback Test (Must be instant < 50ms)
        val t0Warm = System.currentTimeMillis()
        val warmUrl = AudioStreamResolver.resolveAudioStream(idA, "Choo Lo", "The Local Train")
        val warmResolveMs = System.currentTimeMillis() - t0Warm
        println("WARM Track 'Choo Lo' Resolution Time: ${warmResolveMs}ms")
        assertNotNull(warmUrl)
        assertTrue("Warm resolution must be under 50ms (was ${warmResolveMs}ms)", warmResolveMs < 50)

        // 3. Rapid A -> B switching
        println("RAPID SWITCHING: A -> B")
        val streamA = AudioStreamResolver.resolveAudioStream(idA, "Choo Lo", "The Local Train")
        assertNotNull(streamA)
        val streamB = AudioStreamResolver.resolveAudioStream(idB, "Raanjhanaa", "A.R. Rahman")
        assertNotNull(streamB)
        println("Rapid switching successfully resolved both streams.")
    }
}
