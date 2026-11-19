package com.auralis.music

import com.auralis.music.data.network.LyricsClient
import com.auralis.music.data.parser.WordTiming
import com.auralis.music.data.repository.LyricsRepositoryImpl
import com.auralis.music.domain.model.*
import com.auralis.music.ui.viewmodel.selectSettledLyrics
import io.mockk.mockk
import io.mockk.coEvery
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class SpicyAndroidUpgradeTest {
    @Test fun duetEnrichmentDoesNotReplaceCachedSpicy() = runBlocking {
        val spicy = lyrics(LyricsProvider.SPICY_LYRICS)
        val duet = lyrics(LyricsProvider.PAXSENIX).let { data -> data.copy(
            lines = data.lines.mapIndexed { index, line -> line.copy(agent = if (index % 2 == 0) "v1" else "v2") }) }
        val client = mockk<LyricsClient>()
        coEvery { client.getLyrics(any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns duet
        val repo = LyricsRepositoryImpl(client, spicyEnabled = { true })
        repo.useLyrics(spicy, "Fixture", "Artist", 180, "abcdefghijk")
        assertNull(repo.enrichSpeakerMetadata("Fixture", "Artist", 180, "abcdefghijk",
            durationMs = 180000))
        assertEquals(spicy, repo.cached())
    }

    @Test fun slowerNormalLookupCannotOverwriteSpicyThatFinishedFirst() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val client = mockk<LyricsClient>()
        val other = lyrics(LyricsProvider.BETTER_LYRICS)
        coEvery { client.getLyrics(any(), any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            started.complete(Unit)
            release.await()
            other
        }
        val spicy = lyrics(LyricsProvider.SPICY_LYRICS)
        val repo = LyricsRepositoryImpl(client, spicyEnabled = { true }, spicyLookup = { spicy })
        val normal = async { repo.getLyrics("Fixture", "Artist", 180, "abcdefghijk", forceRefresh = true,
            durationMs = 180000) }
        started.await()
        assertEquals(spicy, repo.upgrade())
        release.complete(Unit)
        assertEquals(spicy, normal.await())
        assertEquals(spicy, repo.cached())
    }

    @Test fun automaticSelectionPreservesDuetCreditsUnlessSpicyIsAvailable() {
        val solo = lyrics(LyricsProvider.BETTER_LYRICS)
        val duet = solo.copy(provider = LyricsProvider.PAXSENIX,
            lines = solo.lines.mapIndexed { index, line -> line.copy(agent = if (index % 2 == 0) "v1" else "v2") })
        val spicy = lyrics(LyricsProvider.SPICY_LYRICS)
        assertSame(spicy, LyricsRepositoryImpl.chooseSpicy(duet, spicy))
        assertSame(spicy, selectSettledLyrics(duet, duet, spicy))
        assertSame(duet, selectSettledLyrics(solo, solo, duet))
        assertSame(spicy, selectSettledLyrics(spicy, spicy, duet))
        assertSame(duet, selectSettledLyrics(null, duet, solo))
        val spicyDuet = duet.copy(provider = LyricsProvider.SPICY_LYRICS)
        assertSame(spicyDuet, LyricsRepositoryImpl.chooseSpicy(solo, spicyDuet))
        assertSame(spicyDuet, selectSettledLyrics(duet, duet, spicyDuet))
        val lineDuet = duet.copy(syncType = SyncType.LINE_SYNC, lines = duet.lines.map { it.copy(words = null) })
        assertSame(solo, selectSettledLyrics(solo, solo, lineDuet))
    }

    private fun lyrics(provider: LyricsProvider) = LyricsData(SyncType.RICHSYNC,
        (0..3).map { i -> LyricLine(1000L + i * 3000, "Hello world", words = listOf(
            LyricWord("Hello ", 1000L + i * 3000, 800), LyricWord("world", 2000L + i * 3000, 700)),
            endTime = 2700L + i * 3000) }, provider = provider, trackName = "Fixture", artistName = "Artist",
        durationMs = 180000, fetchedAtMs = System.currentTimeMillis())

    private suspend fun LyricsRepositoryImpl.upgrade() = upgradeSpicy("Fixture", "Artist", 180,
        "abcdefghijk", null, null, 180000, null, "abcdefghijklmnopqrstuv")
    private suspend fun LyricsRepositoryImpl.cached() = getCachedLyrics("Fixture", "Artist", 180,
        "abcdefghijk", null, null, 180000, null)

    @Test fun cachedWordsRemainVisibleWhileSpicyReplacesThemInTheBackground() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val spicy = lyrics(LyricsProvider.SPICY_LYRICS)
        val repo = LyricsRepositoryImpl(mockk(), spicyEnabled = { true }, spicyLookup = { query ->
            assertEquals("abcdefghijklmnopqrstuv", query.spotifyId)
            entered.complete(Unit); release.await(); spicy
        })
        val fallback = lyrics(LyricsProvider.BETTER_LYRICS)
        repo.useLyrics(fallback, "Fixture", "Artist", 180, "abcdefghijk")
        val result = async { repo.upgrade() }
        entered.await()
        assertEquals(fallback, repo.cached())
        release.complete(Unit)
        assertEquals(spicy, result.await())
        assertEquals(spicy, repo.cached())
        assertEquals(spicy, selectSettledLyrics(null, fallback, spicy))
    }

    @Test fun disabledAndUnavailableProvidersKeepCacheAndFailedLookupsCanRetry() = runBlocking {
        var calls = 0
        var now = System.currentTimeMillis()
        var enabled = false
        val repo = LyricsRepositoryImpl(mockk(), spicyEnabled = { enabled }, spicyLookup = { calls++; null }, clock = { now })
        val fallback = lyrics(LyricsProvider.BETTER_LYRICS)
        repo.useLyrics(fallback, "Fixture", "Artist", 180, "abcdefghijk")
        assertNull(repo.upgrade()); assertEquals(0, calls)
        enabled = true
        assertNull(repo.upgrade()); assertNull(repo.upgrade()); assertEquals(1, calls)
        assertEquals(fallback, repo.cached())
        now += 5 * 60_000
        repo.upgrade(); assertEquals(2, calls)
    }

    @Test fun cancellationOnSongChangeDoesNotSuppressTheNextAttempt() = runBlocking {
        var calls = 0
        val entered = CompletableDeferred<Unit>()
        val repo = LyricsRepositoryImpl(mockk(), spicyEnabled = { true }, spicyLookup = {
            if (++calls == 1) { entered.complete(Unit); awaitCancellation() }
            lyrics(LyricsProvider.SPICY_LYRICS)
        })
        val first = launch { repo.upgrade() }
        entered.await(); first.cancelAndJoin()
        assertNotNull(repo.upgrade()); assertEquals(2, calls)
    }

    @Test fun syncedSpicyTakesPriorityOverWordTimingForEveryStyle() {
        val word = lyrics(LyricsProvider.BETTER_LYRICS)
        val spicy = lyrics(LyricsProvider.SPICY_LYRICS)
        val line = spicy.copy(syncType = SyncType.LINE_SYNC, lines = spicy.lines.map { it.copy(words = null) })
        assertSame(line, LyricsRepositoryImpl.chooseSpicy(word, line))
        assertSame(spicy, LyricsRepositoryImpl.chooseSpicy(word, spicy))
        assertSame(spicy, selectSettledLyrics(word, word, spicy))
        assertSame(spicy, selectSettledLyrics(word, word, spicy))
        assertSame(spicy, selectSettledLyrics(null, spicy, word))
        assertSame(line, selectSettledLyrics(word, word, line))
        assertSame(line, selectSettledLyrics(null, line, word))
        assertSame(line, selectSettledLyrics(line, word, word))
        val plain = line.copy(syncType = SyncType.PLAIN, lines = line.lines.map { it.copy(time = 0L) })
        assertSame(word, selectSettledLyrics(word, word, plain))
        assertSame(word, LyricsRepositoryImpl.chooseSpicy(word, plain))
        assertTrue(LyricsClient.outranks(LyricsClient.TIER_LINE, 100.0, LyricsClient.TIER_WORD, 200.0,
            provider = LyricsProvider.SPICY_LYRICS, bestProvider = LyricsProvider.BETTER_LYRICS))
        assertFalse(LyricsClient.outranks(LyricsClient.TIER_WORD, 200.0, LyricsClient.TIER_LINE, 100.0,
            provider = LyricsProvider.BETTER_LYRICS, bestProvider = LyricsProvider.SPICY_LYRICS))
        assertFalse(LyricsClient.outranks(LyricsClient.TIER_WORD, 200.0, LyricsClient.TIER_LINE, 100.0,
            masterMatch = com.auralis.music.domain.lyrics.MasterMatchStatus.MASTER_MISMATCH,
            provider = LyricsProvider.SPICY_LYRICS, bestProvider = LyricsProvider.BETTER_LYRICS))
    }

    @Test fun nestedSourceTimingsAndBackingFlagsSurviveRoomAndSpellingRepair() {
        val source = lyrics(LyricsProvider.SPICY_LYRICS)
        val pieces = listOf(LyricWord("I", 1000, 200, true), LyricWord("want ", 1400, 1700, true))
        val joined = LyricWord("Iwant ", 1000, 2100, true, pieces)
        val data = source.copy(lines = source.lines.mapIndexed { i, line -> if (i == 0)
            line.copy(text = "Iwant world", isBackground = true, endTime = 5000,
                words = listOf(joined, LyricWord("world", 4000, 1000, true))) else line })
        val restored = LyricsRepositoryImpl.entityToDomain(
            LyricsRepositoryImpl.domainToEntity("abcdefghijk", data, "Fixture", "Artist"), "Fixture", "Artist")!!
        assertEquals(data.lines, restored.lines)
        assertEquals(listOf(joined), WordTiming.splitMergedLyricWord(joined))
        assertEquals(pieces, WordTiming.splitMergedWordsInLine(restored.lines.first()).words!!.first().syllables)
        assertEquals(pieces.map { it.time + 400 }, joined.shiftedBy(400).syllables!!.map { it.time })
    }

    @Test fun backingWrapperRemovalPreservesEveryNestedTimingAndBackgroundFlag() {
        val parts = listOf(LyricWord("(O", 1000, 300, true), LyricWord("h)", 1400, 1700, true))
        val word = LyricWord("(Oh)", 1000, 2100, true, parts)
        val displayed = com.auralis.music.ui.lyrics.renderers.SpicyBackingText.displayWords("Oh", listOf(word), true)!!.single()
        assertEquals("Oh", displayed.word)
        assertEquals(listOf("O", "h"), displayed.syllables!!.map { it.word })
        assertEquals(parts.map { it.time to it.duration }, displayed.syllables!!.map { it.time to it.duration })
        assertTrue(displayed.isBackground && displayed.syllables!!.all { it.isBackground })
        assertEquals("(Oh)", word.word)
    }
}
