package com.auralis.music.windows.player

import com.auralis.music.data.network.YouTubeBackoff
import com.auralis.music.desktop.playback.DesktopPlaybackController
import com.auralis.music.desktop.playback.VlcAudioPlayer
import com.auralis.music.domain.model.Track
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class PlaybackIntentGatingTest {
    private val native = testVlcDirectory()
    private val songs = listOf(
        Track("AAAAAAAAAAA", "One", "Artist", duration = 180),
        Track("BBBBBBBBBBB", "Two", "Artist", duration = 180),
        Track("CCCCCCCCCCC", "Three", "Artist", duration = 180))

    @Test fun restoringAnIdleQueueAsksYouTubeForNothing() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val audio = assertNotNull(VlcAudioPlayer.createOrNull(native))
        val lookups = AtomicInteger()
        val playback = DesktopPlaybackController(audio, scope, offlineFile = { null },
            resolveStream = { lookups.incrementAndGet(); null })
        playback.autoLoadMore = false
        try {
            playback.restoreQueue(songs, 1)
            // Longer than the old delayed restored-song lookup (4s).
            delay(5_000)
            assertEquals(0, lookups.get(), "an idle restored queue must not resolve streams")
            assertEquals("BBBBBBBBBBB", playback.queue.value.current?.id)
            assertNull(playback.establishedTrackId.value, "no optional video work before audio plays")
        } finally {
            playback.release()
            scope.cancel()
        }
    }

    @Test fun aRefusedNetworkKeepsTheQueueInsteadOfSkippingThroughIt() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val audio = assertNotNull(VlcAudioPlayer.createOrNull(native))
        val lookups = AtomicInteger()
        val playback = DesktopPlaybackController(audio, scope, offlineFile = { null },
            resolveStream = { lookups.incrementAndGet(); throw java.io.IOException("Sign in to confirm that you're not a bot") })
        playback.autoLoadMore = false
        playback.preloadNext = false
        val firstError = scope.async(start = CoroutineStart.UNDISPATCHED) { playback.errors.first() }
        try {
            playback.playFrom(songs[0], songs)
            val error = withTimeout(10_000) { firstError.await() }
            assertEquals(0, playback.queue.value.index, "every song would fail alike; skipping only burns the queue")
            assertEquals(1, lookups.get())
            assertContains(error.message, "requires verification")
            assertEquals(DesktopPlaybackController.YouTubeFallback("AAAAAAAAAAA", "AAAAAAAAAAA", 0), error.youtubeFallback)
            assertFalse(YouTubeBackoff.active)
            playback.togglePlayPause()
            withTimeout(2000) { while (lookups.get() < 2) delay(25) }
            assertEquals(0, playback.queue.value.index)
        } finally {
            YouTubeBackoff.recordSuccess()
            firstError.cancel()
            playback.release()
            scope.cancel()
        }
    }

    @Test fun aRefusedCrossfadeStopsTheOutgoingSongAndHoldsTheQueue() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val wave = File.createTempFile("crossfade-refusal", ".wav")
        javax.sound.sampled.AudioInputStream(java.io.ByteArrayInputStream(ByteArray(8 * 8000 * 2)),
            javax.sound.sampled.AudioFormat(8000f, 16, 1, true, false), 64000).use {
            javax.sound.sampled.AudioSystem.write(it, javax.sound.sampled.AudioFileFormat.Type.WAVE, wave)
        }
        val audio = assertNotNull(VlcAudioPlayer.createOrNull(native))
        val playback = DesktopPlaybackController(audio, scope,
            offlineFile = { if (it == songs[0].id) wave else null },
            resolveStream = { throw java.io.IOException("Sign in to confirm that you're not a bot") })
        playback.autoLoadMore = false
        playback.preloadNext = false
        playback.crossfadeSeconds = 1f
        playback.setVolume(0f)
        try {
            playback.playFrom(songs[0], songs)
            withTimeout(15_000) { while (playback.state.value != VlcAudioPlayer.State.PLAYING) delay(25) }
            playback.next()
            withTimeout(5_000) { while (playback.state.value != VlcAudioPlayer.State.IDLE) delay(25) }
            assertEquals(1, playback.queue.value.index)
        } finally {
            YouTubeBackoff.recordSuccess()
            playback.release()
            scope.cancel()
            wave.delete()
        }
    }

    @Test fun workingYouTubeAudioDoesNotLookUpJioSaavn() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val stream = streamServer(testAudio())
        val audio = assertNotNull(VlcAudioPlayer.createOrNull(native))
        val saavnLookups = AtomicInteger()
        val playback = DesktopPlaybackController(audio, scope, offlineFile = { null },
            resolveStream = { "http://127.0.0.1:${stream.address.port}/track.wav" },
            resolveJioSaavn = { saavnLookups.incrementAndGet(); null })
        playback.autoLoadMore = false
        playback.preloadNext = false
        playback.setVolume(0f)
        try {
            playback.playSingle(songs[0])
            withTimeout(15_000) { playback.state.first { it == VlcAudioPlayer.State.PLAYING } }
            assertEquals(0, saavnLookups.get())
        } finally {
            playback.release()
            scope.cancel()
            stream.stop(0)
        }
    }

    @Test fun matchedJioSaavnAudioStartsAfterYouTubeVerificationFails() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val stream = streamServer(testAudio())
        val audio = assertNotNull(VlcAudioPlayer.createOrNull(native))
        val youtubeLookups = AtomicInteger()
        val playback = DesktopPlaybackController(audio, scope, offlineFile = { null },
            resolveStream = {
                youtubeLookups.incrementAndGet()
                throw java.io.IOException("Sign in to confirm that you're not a bot")
            },
            resolveJioSaavn = {
                assertEquals(1, youtubeLookups.get())
                "http://127.0.0.1:${stream.address.port}/track.wav"
            })
        playback.autoLoadMore = false
        playback.preloadNext = false
        playback.setVolume(0f)
        try {
            playback.playFrom(songs[0], songs)
            withTimeout(15_000) { playback.state.first { it == VlcAudioPlayer.State.PLAYING } }
            assertEquals(1, youtubeLookups.get())
            assertEquals(songs[0].id, playback.queue.value.current?.id)
        } finally {
            playback.release()
            scope.cancel()
            stream.stop(0)
        }
    }

    @Test fun unusableJioSaavnAudioReportsTheOriginalYouTubeFailure() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val audio = assertNotNull(VlcAudioPlayer.createOrNull(native))
        val stream = streamServer(null)
        val youtubeLookups = AtomicInteger()
        val saavnLookups = AtomicInteger()
        val playback = DesktopPlaybackController(audio, scope, offlineFile = { null },
            resolveStream = {
                youtubeLookups.incrementAndGet()
                throw java.io.IOException("Sign in to confirm that you're not a bot")
            },
            resolveJioSaavn = { saavnLookups.incrementAndGet(); "http://127.0.0.1:${stream.address.port}/track.wav" })
        playback.autoLoadMore = false
        playback.preloadNext = false
        try {
            val error = async(start = CoroutineStart.UNDISPATCHED) { playback.errors.first() }
            playback.playSingle(songs[0])
            withTimeout(15_000) {
                val message = error.await().message
                assertContains(message, "requires verification")
                assertContains(message, "JioSaavn couldn't play it either")
            }
            assertEquals(1, youtubeLookups.get())
            // The dead stream is reopened, not looked up again.
            assertEquals(1, saavnLookups.get())
            assertEquals(songs[0].id, playback.queue.value.current?.id)
        } finally {
            playback.release()
            scope.cancel()
            stream.stop(0)
        }
    }

    @Test fun soundCloudPlaysWhenJioSaavnHasNoMatch() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val stream = streamServer(testAudio())
        val audio = assertNotNull(VlcAudioPlayer.createOrNull(native))
        val saavnLookups = AtomicInteger()
        val soundCloudLookups = AtomicInteger()
        val playback = DesktopPlaybackController(audio, scope, offlineFile = { null },
            resolveStream = { throw java.io.IOException("Sign in to confirm that you're not a bot") },
            resolveJioSaavn = { saavnLookups.incrementAndGet(); null },
            resolveSoundCloud = { soundCloudLookups.incrementAndGet(); "http://127.0.0.1:${stream.address.port}/track.wav" })
        playback.autoLoadMore = false
        playback.preloadNext = false
        playback.setVolume(0f)
        try {
            playback.playSingle(songs[0])
            withTimeout(15_000) { playback.state.first { it == VlcAudioPlayer.State.PLAYING } }
            assertEquals(1, saavnLookups.get())
            assertEquals(1, soundCloudLookups.get())
        } finally {
            playback.release()
            scope.cancel()
            stream.stop(0)
        }
    }

    @Test fun aDeadJioSaavnStreamIsReopenedOnceThenSoundCloudTakesOver() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val dead = streamServer(null)
        val good = streamServer(testAudio())
        val audio = assertNotNull(VlcAudioPlayer.createOrNull(native))
        val saavnLookups = AtomicInteger()
        val soundCloudLookups = AtomicInteger()
        val playback = DesktopPlaybackController(audio, scope, offlineFile = { null },
            resolveStream = { throw java.io.IOException("Sign in to confirm that you're not a bot") },
            resolveJioSaavn = { saavnLookups.incrementAndGet(); "http://127.0.0.1:${dead.address.port}/track.wav" },
            resolveSoundCloud = { soundCloudLookups.incrementAndGet(); "http://127.0.0.1:${good.address.port}/track.wav" })
        playback.autoLoadMore = false
        playback.preloadNext = false
        playback.setVolume(0f)
        val errors = mutableListOf<DesktopPlaybackController.PlaybackError>()
        val collecting = scope.launch { playback.errors.collect { errors += it } }
        try {
            playback.playSingle(songs[0])
            withTimeout(20_000) { while (playback.state.value != VlcAudioPlayer.State.PLAYING || soundCloudLookups.get() == 0) delay(25) }
            assertEquals(1, saavnLookups.get())
            assertEquals(1, soundCloudLookups.get())
            assertEquals(emptyList(), errors.map { it.message })
        } finally {
            collecting.cancel()
            playback.release()
            scope.cancel()
            dead.stop(0)
            good.stop(0)
        }
    }

    @Test fun youTubeRefusingTheStreamMidSongContinuesOnJioSaavn() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        // 10 s at 48 kHz stereo: the relay's first 256 KiB piece is about 1.4 s of it.
        val long = testAudio(seconds = 10, sampleRate = 48_000f, channels = 2)
        val youtube = streamServer(long, refuseFrom = 256 * 1024)
        val saavn = streamServer(long)
        val audio = assertNotNull(VlcAudioPlayer.createOrNull(native))
        val youtubeLookups = AtomicInteger()
        val saavnLookups = AtomicInteger()
        val playback = DesktopPlaybackController(audio, scope, offlineFile = { null },
            resolveStream = { youtubeLookups.incrementAndGet(); "http://127.0.0.1:${youtube.address.port}/track.wav" },
            resolveJioSaavn = { saavnLookups.incrementAndGet(); "http://127.0.0.1:${saavn.address.port}/track.wav" })
        playback.autoLoadMore = false
        playback.preloadNext = false
        playback.setVolume(0f)
        val errors = mutableListOf<DesktopPlaybackController.PlaybackError>()
        val collecting = scope.launch { playback.errors.collect { errors += it } }
        try {
            playback.playSingle(songs[0])
            withTimeout(25_000) { while (saavnLookups.get() == 0 || playback.state.value != VlcAudioPlayer.State.PLAYING) delay(25) }
            assertEquals(1, youtubeLookups.get(), "a network-wide refusal goes to the other catalogs, not back to YouTube")
            assertEquals(0, playback.queue.value.index)
            assertEquals(emptyList(), errors.map { it.message })
        } finally {
            YouTubeBackoff.recordSuccess()
            collecting.cancel()
            playback.release()
            scope.cancel()
            youtube.stop(0)
            saavn.stop(0)
        }
    }

    @Test fun aBrokenDownloadStreamsInstead() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val stream = streamServer(testAudio())
        val missing = File(System.getProperty("java.io.tmpdir"), "auralis-missing-${System.nanoTime()}.wav")
        val audio = assertNotNull(VlcAudioPlayer.createOrNull(native))
        val youtubeLookups = AtomicInteger()
        val playback = DesktopPlaybackController(audio, scope, offlineFile = { missing },
            resolveStream = { youtubeLookups.incrementAndGet(); "http://127.0.0.1:${stream.address.port}/track.wav" })
        playback.autoLoadMore = false
        playback.preloadNext = false
        playback.setVolume(0f)
        try {
            playback.playSingle(songs[0])
            withTimeout(20_000) { while (youtubeLookups.get() == 0 || playback.state.value != VlcAudioPlayer.State.PLAYING) delay(25) }
            assertEquals(1, youtubeLookups.get())
        } finally {
            playback.release()
            scope.cancel()
            stream.stop(0)
        }
    }

    @Test fun aJioSaavnLookupThatTimesOutHoldsWithAMessage() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val audio = assertNotNull(VlcAudioPlayer.createOrNull(native))
        val playback = DesktopPlaybackController(audio, scope, offlineFile = { null },
            resolveStream = { throw java.io.IOException("Sign in to confirm that you're not a bot") },
            // A request's own timeout surfaces as a cancellation; it must not cancel the song's load.
            resolveJioSaavn = { withTimeout(50) { delay(5_000) }; null })
        playback.autoLoadMore = false
        playback.preloadNext = false
        try {
            val error = async(start = CoroutineStart.UNDISPATCHED) { playback.errors.first() }
            playback.playSingle(songs[0])
            withTimeout(10_000) { assertContains(error.await().message, "requires verification") }
        } finally {
            YouTubeBackoff.recordSuccess()
            playback.release()
            scope.cancel()
        }
    }

    @Test fun aSlowYouTubeLookupLetsJioSaavnStartTheSong() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val stream = streamServer(testAudio())
        val audio = assertNotNull(VlcAudioPlayer.createOrNull(native))
        val playback = DesktopPlaybackController(audio, scope, offlineFile = { null },
            resolveStream = { delay(60_000); null },
            resolveJioSaavn = { "http://127.0.0.1:${stream.address.port}/track.wav" })
        playback.autoLoadMore = false
        playback.preloadNext = false
        playback.setVolume(0f)
        try {
            playback.playSingle(songs[0])
            withTimeout(18_000) { playback.state.first { it == VlcAudioPlayer.State.PLAYING } }
        } finally {
            playback.release()
            scope.cancel()
            stream.stop(0)
        }
    }

    private fun testAudio(seconds: Int = 8, sampleRate: Float = 8000f, channels: Int = 1): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val frames = (seconds * sampleRate).toLong()
        javax.sound.sampled.AudioInputStream(java.io.ByteArrayInputStream(ByteArray((frames * 2 * channels).toInt())),
            javax.sound.sampled.AudioFormat(sampleRate, 16, channels, true, false), frames).use {
            javax.sound.sampled.AudioSystem.write(it, javax.sound.sampled.AudioFileFormat.Type.WAVE, output)
        }
        return output.toByteArray()
    }

    /** Serves [audio] by range; ranges starting at [refuseFrom] or later get a 429, as a refusing CDN would. */
    private fun streamServer(audio: ByteArray?, refuseFrom: Int = Int.MAX_VALUE): HttpServer =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/track.wav") { exchange ->
                exchange.use {
                    val requested = exchange.requestHeaders.getFirst("Range")?.removePrefix("bytes=")?.substringBefore('-')?.toIntOrNull() ?: 0
                    if (audio == null) {
                        exchange.sendResponseHeaders(404, -1)
                    } else if (requested >= refuseFrom) {
                        exchange.sendResponseHeaders(429, -1)
                    } else {
                        val range = exchange.requestHeaders.getFirst("Range").removePrefix("bytes=")
                        val start = range.substringBefore('-').toInt()
                        val end = range.substringAfter('-').toInt().coerceAtMost(audio.lastIndex)
                        exchange.responseHeaders.set("Content-Range", "bytes $start-$end/${audio.size}")
                        exchange.responseHeaders.set("Content-Type", "audio/wav")
                        exchange.sendResponseHeaders(206, (end - start + 1).toLong())
                        exchange.responseBody.write(audio, start, end - start + 1)
                    }
                }
            }
            start()
        }
}
