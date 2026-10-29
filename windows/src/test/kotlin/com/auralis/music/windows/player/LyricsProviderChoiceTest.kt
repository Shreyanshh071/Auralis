package com.auralis.music.windows.player

import com.auralis.music.desktop.lyrics.*
import com.auralis.music.desktop.playback.*
import com.auralis.music.domain.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.nio.file.Files
import kotlin.test.*

class LyricsProviderChoiceTest {
    @Test fun explicitChoiceCanReplaceWordSyncAndFailureKeepsTheCurrentLyrics() = runBlocking {
        val folder = Files.createTempDirectory("auralis-provider-choice").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val vlc = assertNotNull(VlcAudioPlayer.createOrNull(java.io.File(System.getenv("LOCALAPPDATA"), "Auralis/app/resources/vlc")))
        val playback = DesktopPlaybackController(vlc, scope, offlineFile = { java.io.File(folder, "unused") }, resolveStream = { null })
        val cached = LyricsData(SyncType.RICHSYNC, listOf(LyricLine(1000, "Original")), provider = LyricsProvider.BETTER_LYRICS)
        val chosen = LyricsData(SyncType.PLAIN, listOf(LyricLine(0, "User choice")), provider = LyricsProvider.QQMUSIC)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        try {
            val disk = LyricsDiskCache(folder)
            disk.write("choice", cached)
            val repository = DesktopLyricsRepository(disk, spicyEnabled = { false }, providerLookup = { provider, _ ->
                when (provider) {
                    LyricsProvider.QQMUSIC -> chosen
                    LyricsProvider.BETTER_LYRICS -> withContext(NonCancellable) { entered.complete(Unit); release.await(); cached }
                    LyricsProvider.AMLL -> throw java.io.IOException("rate limited")
                    else -> null
                }
            })
            val controller = DesktopLyricsController(playback, repository, scope)
            playback.restoreQueue(listOf(Track("choice", "Fixture", "Artist", duration = 180)))
            withTimeout(3000) { controller.state.first { it.lyrics != null } }
            controller.pickProvider(LyricsProvider.QQMUSIC)
            val selected = withTimeout(3000) { controller.state.first { it.lyrics?.provider == LyricsProvider.QQMUSIC } }
            assertEquals(SyncType.PLAIN, selected.lyrics?.syncType)
            controller.pickProvider(LyricsProvider.GENIUS)
            val missing = withTimeout(3000) { controller.state.first {
                it.providerStatus[LyricsProvider.GENIUS] == DesktopLyricsController.ProviderStatus.NOT_FOUND
            } }
            assertEquals(selected.lyrics, missing.lyrics)
            controller.pickProvider(LyricsProvider.AMLL)
            val unavailable = withTimeout(3000) { controller.state.first {
                it.providerStatus[LyricsProvider.AMLL] == DesktopLyricsController.ProviderStatus.UNAVAILABLE
            } }
            assertEquals(selected.lyrics, unavailable.lyrics)
            controller.pickProvider(LyricsProvider.BETTER_LYRICS)
            withTimeout(3000) { entered.await() }
            controller.pickProvider(LyricsProvider.QQMUSIC)
            val switched = withTimeout(3000) { controller.state.first {
                it.providerStatus[LyricsProvider.QQMUSIC] == DesktopLyricsController.ProviderStatus.FOUND
            } }
            assertNull(switched.providerStatus[LyricsProvider.BETTER_LYRICS], "Cancelled source must not keep spinning")
            release.complete(Unit)
            delay(100)
            assertEquals(LyricsProvider.QQMUSIC, controller.state.value.lyrics?.provider, "A late cancelled lookup cannot undo the latest choice")
        } finally {
            release.complete(Unit)
            scope.coroutineContext[Job]?.cancelAndJoin()
            playback.release()
            folder.listFiles()?.forEach { it.delete() }; folder.delete()
        }
    }
}
