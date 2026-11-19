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
        val errors = mutableListOf<DesktopPlaybackController.PlaybackError>()
        val collecting = scope.launch { playback.errors.collect { errors += it } }
        try {
            playback.playFrom(songs[0], songs)
            delay(2_500)
            assertEquals(0, playback.queue.value.index, "every song would fail alike; skipping only burns the queue")
            assertEquals(1, lookups.get())
            val error = errors.single()
            assertContains(error.message, "requires verification")
            assertEquals(DesktopPlaybackController.YouTubeFallback("AAAAAAAAAAA", "AAAAAAAAAAA", 0), error.youtubeFallback)
            assertFalse(YouTubeBackoff.active)
            playback.togglePlayPause()
            withTimeout(2000) { while (lookups.get() < 2) delay(25) }
            assertEquals(0, playback.queue.value.index)
        } finally {
            YouTubeBackoff.recordSuccess()
            collecting.cancel()
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
            withTimeout(15_000) { assertContains(error.await().message, "requires verification") }
            assertEquals(1, youtubeLookups.get())
            assertEquals(1, saavnLookups.get())
            assertEquals(songs[0].id, playback.queue.value.current?.id)
        } finally {
            playback.release()
            scope.cancel()
            stream.stop(0)
        }
    }

    private fun testAudio(): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        javax.sound.sampled.AudioInputStream(java.io.ByteArrayInputStream(ByteArray(8 * 8000 * 2)),
            javax.sound.sampled.AudioFormat(8000f, 16, 1, true, false), 64000).use {
            javax.sound.sampled.AudioSystem.write(it, javax.sound.sampled.AudioFileFormat.Type.WAVE, output)
        }
        return output.toByteArray()
    }

    private fun streamServer(audio: ByteArray?): HttpServer =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/track.wav") { exchange ->
                exchange.use {
                    if (audio == null) {
                        exchange.sendResponseHeaders(404, -1)
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
