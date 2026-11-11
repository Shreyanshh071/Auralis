package com.auralis.music.windows.player

import com.auralis.music.desktop.lyrics.*
import com.auralis.music.desktop.playback.*
import com.auralis.music.domain.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.nio.file.Files
import kotlin.test.*

class LyricsStartupTest {
    @Test fun cachedFallbackAppearsWhileSpicyLoadsWithoutWaitingForDecoderDuration() = runBlocking {
        val folder = Files.createTempDirectory("lyrics-startup").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val playback = DesktopPlaybackController(assertNotNull(VlcAudioPlayer.createOrNull()), scope,
            resolveStream = { awaitCancellation() })
        playback.autoLoadMore = false
        playback.preloadNext = false
        val entered = CompletableDeferred<Long?>()
        val release = CompletableDeferred<Unit>()
        val cached = LyricsData(SyncType.RICHSYNC, listOf(LyricLine(1000, "Cached phrase", words = listOf(LyricWord("Cached ", 1000, 400), LyricWord("phrase", 1400, 400)))),
            provider = LyricsProvider.BETTER_LYRICS)
        val spicy = cached.copy(provider = LyricsProvider.SPICY_LYRICS, fetchedAtMs = System.currentTimeMillis())
        try {
            val disk = LyricsDiskCache(folder)
            disk.write("startup", cached)
            val repository = DesktopLyricsRepository(disk, spicyEnabled = { true }, fallbackLookup = { _, _, _ -> cached }, spicyLookup = { query ->
                entered.complete(query.durationMs)
                release.await()
                spicy
            })
            val lyrics = DesktopLyricsController(playback, repository, scope)
            playback.restoreQueue(listOf(Track("startup", "Fixture", "Artist", duration = 180)))
            // No audio file is opened. The old duration wait prevented Spicy even starting.
            assertEquals(180_000L, withTimeout(1_500) { entered.await() })
            val initial = withTimeout(1_500) { lyrics.state.first { it.lyrics != null } }
            assertEquals(LyricsProvider.BETTER_LYRICS, initial.lyrics?.provider)
            assertFalse(initial.isLoading)
            release.complete(Unit)
            val upgraded = withTimeout(3_000) { lyrics.state.first { it.lyrics?.provider == LyricsProvider.SPICY_LYRICS } }
            assertFalse(upgraded.isLoading)
        } finally {
            release.complete(Unit)
            scope.coroutineContext[Job]?.cancelAndJoin()
            playback.release()
            folder.deleteRecursively()
        }
    }

    @Test fun uncachedFallbackIsDisplayedBeforeSlowSpicyCompletes() = runBlocking {
        val folder = Files.createTempDirectory("lyrics-parallel").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val playback = DesktopPlaybackController(assertNotNull(VlcAudioPlayer.createOrNull()), scope,
            resolveStream = { awaitCancellation() })
        playback.autoLoadMore = false
        playback.preloadNext = false
        val spicyStarted = CompletableDeferred<Unit>()
        val releaseSpicy = CompletableDeferred<Unit>()
        val releaseFallback = CompletableDeferred<Unit>()
        val interim = LyricsData(SyncType.LINE_SYNC, listOf(LyricLine(1000, "Available now")), provider = LyricsProvider.LRCLIB)
        try {
            val repository = DesktopLyricsRepository(LyricsDiskCache(folder), spicyEnabled = { true },
                spicyLookup = { spicyStarted.complete(Unit); releaseSpicy.await(); null },
                fallbackLookup = { _, skipSpicy, onInterim ->
                    assertTrue(skipSpicy, "The parallel fallback must not request Spicy a second time")
                    onInterim(interim)
                    releaseFallback.await()
                    interim
                })
            val lyrics = DesktopLyricsController(playback, repository, scope)
            playback.restoreQueue(listOf(Track("parallel", "Fixture", "Artist", duration = 180)))
            withTimeout(1_500) { spicyStarted.await() }
            val initial = withTimeout(1_500) { lyrics.state.first { it.lyrics != null } }
            assertEquals(LyricsProvider.LRCLIB, initial.lyrics?.provider)
            assertFalse(initial.isLoading)
            assertFalse(releaseSpicy.isCompleted, "Fallback must be visible while Spicy is still outstanding")
        } finally {
            releaseSpicy.complete(Unit)
            releaseFallback.complete(Unit)
            scope.coroutineContext[Job]?.cancelAndJoin()
            playback.release()
            folder.deleteRecursively()
        }
    }

    @Test fun nextSongIsPreparedWithoutChangingVisibleLyricsOrFetchingTheWholeQueue() = runBlocking {
        val folder = Files.createTempDirectory("lyrics-next").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val playback = DesktopPlaybackController(assertNotNull(VlcAudioPlayer.createOrNull()), scope,
            resolveStream = { awaitCancellation() })
        playback.autoLoadMore = false
        playback.preloadNext = false
        val tracks = listOf("current", "upcoming", "later").map { Track(it, it, "Artist", duration = 180) }
        val fixture = LyricsData(SyncType.RICHSYNC, listOf(LyricLine(1000, "Two words",
            words = listOf(LyricWord("Two ", 1000, 400), LyricWord("words", 1400, 400)))),
            provider = LyricsProvider.BETTER_LYRICS)
        val requests = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        try {
            val disk = LyricsDiskCache(folder)
            disk.write("current", fixture)
            val repository = DesktopLyricsRepository(disk, spicyEnabled = { true },
                spicyLookup = { query ->
                    requests += query.videoId!!
                    fixture.copy(provider = LyricsProvider.SPICY_LYRICS, fetchedAtMs = System.currentTimeMillis())
                }, fallbackLookup = { _, _, _ -> fixture })
            val lyrics = DesktopLyricsController(playback, repository, scope)
            playback.restoreQueue(tracks)
            withTimeout(5_000) {
                while (repository.cached("upcoming", 180_000) == null) delay(10)
            }
            assertEquals("current", lyrics.state.value.trackId)
            assertFalse("later" in requests, "Only the next song should be prepared")
            playback.connectLoad(tracks, 1, 0L, false)
            val shown = withTimeout(1_500) { lyrics.state.first { it.trackId == "upcoming" && it.lyrics != null } }
            assertFalse(shown.isLoading)
        } finally {
            scope.coroutineContext[Job]?.cancelAndJoin()
            playback.release()
            folder.deleteRecursively()
        }
    }

    @Test fun validSpicyCacheIsReusedForItsAllowedLifetimeIncludingProviderSpecificEntries() = runBlocking {
        val folder = Files.createTempDirectory("lyrics-reuse").toFile()
        val now = System.currentTimeMillis()
        val best = LyricsData(SyncType.RICHSYNC, listOf(LyricLine(1000, "Words")), provider = LyricsProvider.BETTER_LYRICS)
        val spicy = best.copy(provider = LyricsProvider.SPICY_LYRICS, fetchedAtMs = now - 2 * 86_400_000L)
        try {
            val disk = LyricsDiskCache(folder)
            disk.write("reuse", best)
            disk.write("reuse-provider-SPICY_LYRICS", spicy)
            val repository = DesktopLyricsRepository(disk, spicyEnabled = { true },
                spicyLookup = { error("A valid cached response must not need a network refresh") })
            val result = repository.upgradeSpicy(com.auralis.music.data.network.provider.LyricsSearchQuery(
                "Fixture", "Artist", videoId = "reuse", durationMs = 180_000))
            assertEquals(LyricsProvider.SPICY_LYRICS, result?.provider)
            assertEquals(spicy.fetchedAtMs, result?.fetchedAtMs, "Reading must not renew the provider's expiry")
        } finally { folder.deleteRecursively() }
    }
}
