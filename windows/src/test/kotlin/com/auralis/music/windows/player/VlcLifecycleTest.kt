package com.auralis.music.windows.player

import com.auralis.music.desktop.playback.DesktopPlaybackController
import com.auralis.music.desktop.playback.VlcAudioPlayer
import com.auralis.music.domain.model.Track
import kotlinx.coroutines.*
import kotlin.test.*

class VlcLifecycleTest {
    @Test fun releasedPlayerIgnoresLateNativeControls() {
        val audio = assertNotNull(VlcAudioPlayer.createOrNull(testVlcDirectory()))
        audio.release()
        audio.setVolume(.4f)
        audio.play("not-a-stream")
        audio.pause()
        audio.resume()
        audio.stop()
        audio.setRate(1.2f)
        audio.seekTo(2000)
        audio.release()
        assertEquals(VlcAudioPlayer.State.IDLE, audio.state.value)
    }

    @Test fun lookupCompletingAfterReleaseCannotStartPlayback() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val audio = assertNotNull(VlcAudioPlayer.createOrNull(testVlcDirectory()))
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val playback = DesktopPlaybackController(audio, scope, resolveStream = {
            withContext(NonCancellable) { entered.complete(Unit); finish.await(); "https://example.com/late" }
        })
        playback.autoLoadMore = false
        playback.preloadNext = false
        try {
            val track = Track("InD68CDGT9Q", "Fixture", "Artist")
            playback.playFrom(track, listOf(track))
            withTimeout(5000) { entered.await() }
            playback.release()
            finish.complete(Unit)
            scope.cancel()
            withTimeout(5000) { scope.coroutineContext[Job]!!.join() }
            assertEquals(VlcAudioPlayer.State.IDLE, audio.state.value)
        } finally {
            finish.complete(Unit)
            playback.release()
            scope.cancel()
        }
    }
}
