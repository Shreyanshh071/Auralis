package com.auralis.music.windows.player

import com.auralis.music.desktop.playback.DesktopPlaybackController
import com.auralis.music.desktop.playback.VlcAudioPlayer
import com.auralis.music.domain.model.Track
import kotlinx.coroutines.*
import kotlin.test.*

class ShortsPlaybackAdmissionTest {
    private val song = Track("songfixture", "Short song", duration = 49)
    private val short = Track("short", "A YouTube Short", duration = 49)

    @Test fun liveReportedShortIsRejectedByTheRealPlaybackPolicy() = runBlocking {
        org.junit.Assume.assumeTrue(System.getenv("AURALIS_LIVE_SHORTS") == "1")
        val reported = Track("H467W9K843E", "Trigger Discipline", "Horcus", duration = 49)
        val safeSong = song.copy(source = com.auralis.music.domain.model.TrackSource.LOCAL)
        assertTrue(com.auralis.music.data.network.YouTubeShortsFilter.filter(listOf(reported)).isEmpty())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val audio = assertNotNull(VlcAudioPlayer.createOrNull(testVlcDirectory()))
        val lookups = java.util.concurrent.CopyOnWriteArrayList<String>()
        val playback = DesktopPlaybackController(audio, scope, offlineFile = { null }, resolveStream = { lookups += it; null })
        playback.autoLoadMore = false; playback.preloadNext = false
        try {
            playback.restoreQueue(listOf(reported, safeSong))
            playback.playFrom(reported, listOf(reported))
            playback.addToQueue(reported)
            playback.playNext(reported)
            assertEquals(listOf(safeSong), playback.queue.value.tracks)
            playback.fromConnect { playback.connectLoad(listOf(reported), 0, 0, play = true) }
            assertNull(playback.queue.value.current)
            assertTrue(lookups.isEmpty(), "The real Short must never reach the stream resolver")
            println("Reported Short blocked by real policy in restore, direct play, queue additions and Connect")
        } finally { playback.release(); scope.cancel() }
    }

    @Test fun restoreDirectPlayQueueEditsAndConnectCannotAdmitShorts() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val audio = assertNotNull(VlcAudioPlayer.createOrNull(testVlcDirectory()))
        val lookups = java.util.concurrent.CopyOnWriteArrayList<String>()
        val playback = DesktopPlaybackController(audio, scope, offlineFile = { null },
            resolveStream = { lookups += it; null }, filterTracks = { tracks -> tracks.filterNot { it.id == short.id } })
        playback.autoLoadMore = false; playback.preloadNext = false
        try {
            playback.restoreQueue(listOf(short, song), 0)
            assertEquals(listOf(song), playback.queue.value.tracks)
            assertTrue(lookups.isEmpty(), "Restoring never starts an audio stream")
            playback.playFrom(short, listOf(short, song))
            playback.playNext(short)
            playback.addToQueue(short)
            assertEquals(listOf(song), playback.queue.value.tracks)
            assertTrue(lookups.isEmpty(), "Direct selection of a Short must never resolve audio")
            playback.fromConnect { playback.connectLoad(listOf(short, song), 0, 10000, play = false) }
            assertEquals(song, playback.queue.value.current)
            assertEquals(listOf(song), playback.queue.value.tracks)
            playback.fromConnect { playback.connectLoad(listOf(short), 0, 0, play = true) }
            assertNull(playback.queue.value.current)
            assertTrue(lookups.isEmpty())
            playback.fromConnect { playback.connectLoad(listOf(song, short), 1, 0, play = true) }
            assertNull(playback.queue.value.current, "Skipping a final Short must not restart an earlier song")
            assertTrue(lookups.isEmpty())
            playback.playFrom(song, listOf(song, short), automatic = false)
            withTimeout(5000) { while (lookups.isEmpty()) delay(10) }
            assertEquals(listOf(song.id), lookups.toList())
            assertEquals(listOf(song), playback.queue.value.tracks)
        } finally { playback.release(); scope.cancel() }
    }

    @Test fun newerSelectionSupersedesPendingClassificationAndKeepsConnectRoutingContext() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val audio = assertNotNull(VlcAudioPlayer.createOrNull(testVlcDirectory()))
        val gate = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val playback = DesktopPlaybackController(audio, scope, offlineFile = { null }, resolveStream = { null },
            filterTracks = { tracks ->
                if (tracks.any { it.id == short.id }) { started.complete(Unit); gate.await() }
                else delay(20)
                tracks.filterNot { it.id == short.id }
            })
        playback.autoLoadMore = false
        var routed = 0
        playback.connectControl = { routed++; true }
        try {
            playback.fromConnect { playback.playFrom(short, listOf(short)) }
            started.await()
            playback.fromConnect { playback.playFrom(song, listOf(song)) }
            gate.complete(Unit)
            withTimeout(5000) { while (playback.queue.value.current?.id != song.id) delay(10) }
            assertEquals(song, playback.queue.value.current)
            assertEquals(0, routed, "Asynchronous checks must not route a Connect command back out")
        } finally { playback.release(); scope.cancel() }
    }

    @Test fun pausingDuringConnectValidationKeepsTheBookmarkAndCannotStartAudio() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val audio = assertNotNull(VlcAudioPlayer.createOrNull(testVlcDirectory()))
        val gate = CompletableDeferred<Unit>()
        val lookups = java.util.concurrent.CopyOnWriteArrayList<String>()
        val playback = DesktopPlaybackController(audio, scope, offlineFile = { null },
            resolveStream = { lookups += it; null }, filterTracks = { gate.await(); it })
        playback.autoLoadMore = false
        try {
            playback.connectLoad(listOf(song), 0, 67000, play = true)
            assertEquals(67000L, playback.clock.rawPositionMs())
            playback.connectSilence()
            gate.complete(Unit)
            withTimeout(5000) { while (playback.queue.value.current == null) delay(10) }
            assertEquals(67000L, playback.clock.rawPositionMs())
            assertTrue(lookups.isEmpty())
            playback.connectPlay()
            withTimeout(5000) { while (lookups.isEmpty()) delay(10) }
            assertEquals(listOf(song.id), lookups.toList())
        } finally { playback.release(); scope.cancel() }
    }
}
