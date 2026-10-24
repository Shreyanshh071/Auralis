package com.auralis.music.windows.player

import com.auralis.music.desktop.playback.DesktopPlaybackController
import com.auralis.music.desktop.playback.VlcAudioPlayer
import com.auralis.music.domain.model.Track
import kotlinx.coroutines.*
import java.io.File
import kotlin.test.*

class ConnectResumeClockTest {
    @Test fun handoffClockHoldsBookmarkWhileResolvingAndAcceptsSeeks() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val native = File(System.getProperty("user.home"), "AppData/Local/Auralis/app/resources/vlc")
        val audio = assertNotNull(VlcAudioPlayer.createOrNull(native))
        val playback = DesktopPlaybackController(audio, scope, resolveStream = { awaitCancellation() })
        playback.autoLoadMore = false
        playback.preloadNext = false
        try {
            val tracks = listOf(Track("InD68CDGT9Q", "Fixture", "Artist", duration = 180))
            playback.connectLoad(tracks, 0, 67_000, false)
            assertEquals(67_000L, playback.clock.rawPositionMs())
            assertFalse(playback.clock.isPlaying())
            playback.connectLoad(tracks, 0, 67_000, true)
            delay(100)
            assertEquals(67_000L, playback.clock.rawPositionMs(), "Loading must never expose zero to lyrics")
            assertTrue(playback.clock.isBuffering())
            playback.seekTo(81_000)
            assertEquals(81_000L, playback.clock.rawPositionMs(), "Lyric-line seek updates the held start position")
            playback.connectSilence()
            assertFalse(playback.clock.isPlaying())
            assertEquals(81_000L, playback.clock.rawPositionMs(), "Pausing while loading preserves the latest seek")
        } finally {
            playback.release()
            scope.coroutineContext[Job]?.cancelAndJoin()
        }
    }
}
