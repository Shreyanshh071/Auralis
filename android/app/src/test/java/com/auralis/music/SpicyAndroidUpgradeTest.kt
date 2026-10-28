package com.auralis.music

import com.auralis.music.data.network.LyricsClient
import com.auralis.music.data.parser.WordTiming
import com.auralis.music.data.repository.LyricsRepositoryImpl
import com.auralis.music.domain.model.*
import com.auralis.music.ui.viewmodel.selectSettledLyrics
import io.mockk.mockk
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class SpicyAndroidUpgradeTest {
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
        assertEquals(spicy, selectSettledLyrics(null, fallback, spicy, preferSpicy = true))
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

    @Test fun lineOnlySpicyCannotDowngradeWordTimingAndOrdinaryStylesKeepTheirSelection() {
        val word = lyrics(LyricsProvider.BETTER_LYRICS)
        val spicy = lyrics(LyricsProvider.SPICY_LYRICS)
        val line = spicy.copy(syncType = SyncType.LINE_SYNC, lines = spicy.lines.map { it.copy(words = null) })
        assertSame(word, LyricsRepositoryImpl.chooseSpicy(word, line))
        assertSame(spicy, LyricsRepositoryImpl.chooseSpicy(word, spicy))
        assertSame(word, selectSettledLyrics(word, word, spicy))
        assertSame(spicy, selectSettledLyrics(word, word, spicy, preferSpicy = true))
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
