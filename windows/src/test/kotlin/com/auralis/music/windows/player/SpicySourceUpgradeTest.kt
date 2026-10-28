package com.auralis.music.windows.player

import com.auralis.music.desktop.lyrics.*
import com.auralis.music.data.network.provider.LyricsSearchQuery
import com.auralis.music.domain.model.*
import kotlinx.coroutines.*
import java.nio.file.Files
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.*

class SpicySourceUpgradeTest {
    private val videoId = "source-upgrade"
    private val query = LyricsSearchQuery("Fixture", "Artist", 180, "source-upgrade", durationMs = 180000)
    private fun lyrics(provider: LyricsProvider, wordSync: Boolean = true) = LyricsData(
        if (wordSync) SyncType.RICHSYNC else SyncType.LINE_SYNC,
        List(3) { i -> LyricLine(10000L + i * 5000, "velora shines", words = if (wordSync) listOf(
            LyricWord("velora ", 10000L + i * 5000, 2000, syllables = listOf(
                LyricWord("vel", 10000L + i * 5000, 500), LyricWord("ora ", 10500L + i * 5000, 1500))),
            LyricWord("shines", 12000L + i * 5000, 1000)) else null) },
        provider = provider, trackName = "Fixture", artistName = "Artist", durationMs = 180000,
        fetchedAtMs = System.currentTimeMillis())

    @Test fun controllerWordCacheEarlyExitDoesNotCancelASlowSpicyUpgrade() = runBlocking {
        val folder = Files.createTempDirectory("auralis-controller-upgrade").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val vlc = assertNotNull(com.auralis.music.desktop.playback.VlcAudioPlayer.createOrNull(
            java.io.File(System.getenv("LOCALAPPDATA"), "Auralis/app/resources/vlc")))
        val playback = com.auralis.music.desktop.playback.DesktopPlaybackController(vlc, scope,
            offlineFile = { java.io.File(folder, "unused") }, resolveStream = { null })
        try {
            val disk = LyricsDiskCache(folder)
            disk.write(videoId, lyrics(LyricsProvider.BETTER_LYRICS))
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val repository = DesktopLyricsRepository(disk, spicyEnabled = { true }, spicyLookup = {
                entered.complete(Unit); release.await(); lyrics(LyricsProvider.SPICY_LYRICS)
            })
            val mode = MutableStateFlow(true)
            val controller = DesktopLyricsController(playback, repository, scope, mode)
            playback.restoreQueue(listOf(Track(videoId, "Fixture", "Artist", duration = 180)))
            withTimeout(3000) { entered.await() }
            assertEquals(LyricsProvider.BETTER_LYRICS, controller.state.value.lyrics?.provider)
            // The normal no-audio-length wait ends at 2.5s, followed by the old
            // word-cache return. Its child upgrade must remain alive after it.
            delay(2800)
            release.complete(Unit)
            val result = withTimeout(3000) { controller.state.first { it.lyrics?.provider == LyricsProvider.SPICY_LYRICS } }
            assertFalse(result.isLoading)
            assertEquals(LyricsProvider.SPICY_LYRICS, repository.cached(videoId, 180000)?.provider)
            val original = assertNotNull(result.lyrics)
            val translated = original.copy(lines = original.lines.map { it.copy(translatedText = "Translated") })
            assertTrue(controller.applyTranslation(result.trackId, original, translated))
            mode.value = false
            delay(100)
            assertEquals(translated, controller.state.value.lyrics, "Changing renderer must preserve the selected translation")
        } finally {
            scope.coroutineContext[Job]?.cancelAndJoin(); playback.release()
            folder.listFiles()?.forEach { it.delete() }; folder.delete()
        }
    }

    @Test fun wordCacheStaysVisibleWhileSpicyUpgradesAndLateFallbackCannotUndoIt() = runBlocking {
        val folder = Files.createTempDirectory("auralis-source-upgrade").toFile()
        try {
            val disk = LyricsDiskCache(folder)
            val fallback = lyrics(LyricsProvider.BETTER_LYRICS)
            val spicy = lyrics(LyricsProvider.SPICY_LYRICS)
            disk.write(videoId, fallback)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val repository = DesktopLyricsRepository(disk, spicyEnabled = { true }, spicyLookup = {
                entered.complete(Unit); release.await(); spicy
            })
            val upgrade = async { repository.upgradeSpicy(query) }
            entered.await()
            assertEquals(fallback, repository.cached(videoId, 180000), "An in-flight API lookup must not blank cached lyrics")
            release.complete(Unit)
            assertEquals(spicy, upgrade.await())
            assertEquals(spicy, repository.cached(videoId, 180000))
            assertEquals(spicy, disk.read(videoId))
            assertEquals(spicy, DesktopLyricsRepository.choose(spicy, fallback, preferSpicy = true))
            assertEquals(spicy.lines.first().words!!.first().syllables,
                disk.read(videoId)!!.lines.first().words!!.first().syllables)
        } finally { folder.listFiles()?.forEach { it.delete() }; folder.delete() }
    }

    @Test fun disabledProviderMakesNoRequestAndOfflineFailuresKeepTheWordCache() = runBlocking {
        var calls = 0
        val disabled = DesktopLyricsRepository(spicyEnabled = { false }, spicyLookup = { calls++; null })
        assertNull(disabled.upgradeSpicy(query)); assertEquals(0, calls)
        var now = 1000L
        val unavailable = DesktopLyricsRepository(spicyEnabled = { true }, spicyLookup = { calls++; null }, clock = { now })
        assertNull(unavailable.upgradeSpicy(query))
        assertNull(unavailable.upgradeSpicy(query))
        assertEquals(1, calls, "Reopening a track must not hammer an unavailable provider")
        now += 5 * 60_000L
        unavailable.upgradeSpicy(query)
        assertEquals(2, calls, "A failed lookup is retryable, not cached forever")
    }

    @Test fun lineOnlySpicyDoesNotDowngradeWordTimingAndOtherStylesKeepNormalSelection() {
        val word = lyrics(LyricsProvider.BETTER_LYRICS)
        val line = lyrics(LyricsProvider.SPICY_LYRICS, false)
        assertSame(word, DesktopLyricsRepository.choose(word, line, true))
        assertSame(word, DesktopLyricsRepository.choose(line, word, true))
        assertSame(word, DesktopLyricsRepository.choose(lyrics(LyricsProvider.SPICY_LYRICS), word, false))
    }

    @Test fun cancelledSongChangeDoesNotPermanentlySuppressTheNextAttempt() = runBlocking {
        var calls = 0
        val entered = CompletableDeferred<Unit>()
        val repository = DesktopLyricsRepository(spicyEnabled = { true }, spicyLookup = {
            calls++
            if (calls == 1) { entered.complete(Unit); awaitCancellation() }
            lyrics(LyricsProvider.SPICY_LYRICS)
        })
        val first = launch { repository.upgradeSpicy(query) }
        entered.await(); first.cancelAndJoin()
        assertNotNull(repository.upgradeSpicy(query))
        assertEquals(2, calls)
    }
}
