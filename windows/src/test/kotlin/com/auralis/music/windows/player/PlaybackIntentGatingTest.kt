package com.auralis.music.windows.player

import com.auralis.music.data.network.YouTubeBackoff
import com.auralis.music.desktop.playback.DesktopPlaybackController
import com.auralis.music.desktop.playback.VlcAudioPlayer
import com.auralis.music.domain.model.Track
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class PlaybackIntentGatingTest {
    private val native = File(System.getProperty("user.home"), "AppData/Local/Auralis/app/resources/vlc")
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
            resolveStream = { lookups.incrementAndGet(); null })
        playback.autoLoadMore = false
        playback.preloadNext = false
        val errors = mutableListOf<String>()
        val collecting = scope.launch { playback.errors.collect { errors += it } }
        try {
            YouTubeBackoff.trip()
            playback.playFrom(songs[0], songs)
            delay(2_500)
            assertEquals(0, playback.queue.value.index, "every song would fail alike; skipping only burns the queue")
            assertEquals(1, lookups.get())
            assertTrue(errors.any { it.contains("confirm it isn't a bot") }, "the message names the actual reason: $errors")
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
            resolveStream = { YouTubeBackoff.trip(); null })
        playback.autoLoadMore = false
        playback.preloadNext = false
        playback.crossfadeSeconds = 1f
        playback.setVolume(0f)
        try {
            playback.playFrom(songs[0], songs)
            withTimeout(15_000) { while (playback.state.value != VlcAudioPlayer.State.PLAYING) delay(25) }
            playback.next()
            withTimeout(5_000) { while (!YouTubeBackoff.active || playback.state.value != VlcAudioPlayer.State.IDLE) delay(25) }
            assertEquals(1, playback.queue.value.index)
        } finally {
            YouTubeBackoff.recordSuccess()
            playback.release()
            scope.cancel()
            wave.delete()
        }
    }
}
