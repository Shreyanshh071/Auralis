package com.auralis.music.windows.player

import com.auralis.music.desktop.playback.DesktopAutoRadio
import com.auralis.music.desktop.playback.DesktopPlaybackController.Queue
import com.auralis.music.domain.model.Track
import kotlinx.coroutines.*
import kotlin.test.*

class DesktopAutoRadioTest {
    private fun song(n: Int) = Track("song$n", "Song $n", "Artist")

    @Test fun turningOffAutoplayDuringFetchRejectsTheBatchAndCanRetryLater() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var q = Queue(listOf(song(0)), 0)
        var allowed = true
        var calls = 0
        val ready = CompletableDeferred<Unit>()
        val radio = DesktopAutoRadio(scope, { q }, { allowed }, {
            calls++; if (calls == 1) ready.await()
            (1..20).map(::song)
        }, { q = q.copy(tracks = q.tracks + it) }, { fail("Disabled autoplay must not advance") })
        try {
            radio.reset(true); radio.queueChanged(); radio.request(true)
            allowed = false; ready.complete(Unit); yield()
            assertEquals(1, q.tracks.size)
            allowed = true; radio.queueChanged()
            assertEquals(15, q.tracks.size)
            assertEquals(2, calls)
        } finally { scope.cancel() }
    }

    @Test fun singleSongStartsWithFifteenAndRefillsByFourteen() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var q = Queue(listOf(song(0)), 0)
        var calls = 0
        val radio = DesktopAutoRadio(scope, { q }, { true }, {
            calls++
            (0..60).map(::song) + song(20) // Seed, previous songs and duplicate uploads by ID are excluded.
        }, { q = q.copy(tracks = q.tracks + it) }, {})
        try {
            radio.reset(true); radio.queueChanged()
            assertEquals(15, q.tracks.size)
            assertEquals(song(0), q.current)
            q = q.copy(index = 12); radio.queueChanged()
            assertEquals(1, calls)
            q = q.copy(index = 13); radio.queueChanged()
            assertEquals(29, q.tracks.size)
            assertEquals(song(13), q.current)
            assertEquals(2, calls)
            assertEquals(q.tracks.size, q.tracks.map { it.id }.toSet().size)
            q = q.copy(index = 27); radio.queueChanged()
            assertEquals(43, q.tracks.size)
        } finally { scope.cancel() }
    }

    @Test fun pendingRefillRunsOncePreservesManualAddsAndAdvancesAtEnd() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var q = Queue(listOf(song(0)), 0)
        val ready = CompletableDeferred<List<Track>>()
        var calls = 0
        var advances = 0
        val radio = DesktopAutoRadio(scope, { q }, { true }, { calls++; ready.await() },
            { q = q.copy(tracks = q.tracks + it) }, { advances++; if (q.hasNext) q = q.copy(index = q.index + 1) })
        try {
            radio.reset(true); radio.queueChanged(); radio.queueChanged(); radio.request(true)
            assertEquals(1, calls)
            q = q.copy(tracks = q.tracks + song(1)) // User adds a song while recommendations load.
            ready.complete((0..30).map(::song)); yield()
            assertEquals(16, q.tracks.size)
            assertEquals(1, q.tracks.count { it.id == song(1).id })
            assertEquals(song(1), q.current)
            assertEquals(1, advances)
        } finally { scope.cancel() }
    }

    @Test fun settingsRoomsAndNewUserQueuesRejectPendingRecommendations() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var q = Queue(listOf(song(0)), 0)
        var allowed = false
        var calls = 0
        val ready = CompletableDeferred<List<Track>>()
        val radio = DesktopAutoRadio(scope, { q }, { allowed }, {
            calls++; withContext(NonCancellable) { ready.await() }
        }, { q = q.copy(tracks = q.tracks + it) }, { fail("A replaced queue must never auto-advance") })
        try {
            radio.reset(true); radio.queueChanged()
            assertEquals(0, calls, "Disabled autoplay or a room must not start requests")
            allowed = true; radio.queueChanged()
            assertEquals(1, calls)
            radio.reset(false); q = Queue(listOf(song(100)), 0)
            ready.complete((1..30).map(::song)); yield()
            assertEquals(listOf(song(100)), q.tracks)
            radio.queueChanged()
            assertEquals(1, calls, "Explicit user playlists must remain unchanged")
        } finally { scope.cancel() }
    }
}
