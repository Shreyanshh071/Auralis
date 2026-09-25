package com.auralis.music

import com.auralis.music.domain.model.LyricLine
import com.auralis.music.domain.model.LyricWord
import com.auralis.music.domain.model.LyricsData
import com.auralis.music.domain.model.LyricsProvider
import com.auralis.music.domain.model.SyncType
import com.auralis.music.ui.viewmodel.selectSettledLyrics
import com.auralis.music.ui.viewmodel.searchForTimedLyrics
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LyricsSettlingTest {
    private val plain = LyricsData(
        syncType = SyncType.PLAIN,
        lines = listOf(LyricLine(0L, "A line")),
        provider = LyricsProvider.GENIUS
    )
    private val line = LyricsData(
        syncType = SyncType.LINE_SYNC,
        lines = listOf(LyricLine(1_000L, "A line")),
        provider = LyricsProvider.LRCLIB
    )
    private val word = LyricsData(
        syncType = SyncType.RICHSYNC,
        lines = listOf(LyricLine(1_000L, "A line", words = listOf(
            LyricWord("A", 1_000L, 200L), LyricWord("line", 1_200L, 400L)
        ))),
        provider = LyricsProvider.BETTER_LYRICS
    )

    @Test fun `a synced interim survives a lower-tier final fallback`() {
        assertSame(line, selectSettledLyrics(null, line, plain))
        assertSame(word, selectSettledLyrics(line, word, line))
        assertSame(word, selectSettledLyrics(line, word, null))
    }

    @Test fun `a better final result replaces the interim`() {
        assertSame(word, selectSettledLyrics(null, line, word))
        assertSame(line, selectSettledLyrics(null, plain, line))
    }

    @Test fun `an equal-tier network result does not replace a usable cache`() {
        val otherLine = line.copy(provider = LyricsProvider.NETEASE)
        assertSame(line, selectSettledLyrics(line, line, otherLine))
    }

    @Test fun `plain-only final and cached fallback stay off the lyric screen`() {
        assertNull(selectSettledLyrics(null, null, plain))
        assertNull(selectSettledLyrics(plain, plain, null))
        assertSame(line, selectSettledLyrics(plain, null, line))
    }

    @Test fun `a plain first answer keeps searching until timed lyrics arrive`() = runTest {
        val secondAnswer = CompletableDeferred<LyricsData?>()
        var attempts = 0
        val result = async {
            searchForTimedLyrics(timedLyricsVisible = { false }) {
                attempts++
                if (attempts == 1) plain else secondAnswer.await()
            }
        }
        runCurrent()
        assertEquals(1, attempts)
        assertFalse(result.isCompleted)
        advanceTimeBy(500L)
        runCurrent()
        assertEquals(2, attempts)
        assertFalse(result.isCompleted)
        secondAnswer.complete(line)
        runCurrent()
        assertSame(line, result.await())
        assertEquals(2, attempts)
    }

    @Test fun `plain answers exhaust bounded retries before unavailable state`() = runTest {
        var attempts = 0
        val result = searchForTimedLyrics(retryDelayMs = 0L, timedLyricsVisible = { false }) {
            attempts++
            plain
        }
        assertEquals(3, attempts)
        assertNull(selectSettledLyrics(null, null, result))
    }

    @Test fun `visible timed interim avoids another lookup`() = runTest {
        var attempts = 0
        val result = searchForTimedLyrics(timedLyricsVisible = { true }) {
            attempts++
            plain
        }
        assertSame(plain, result)
        assertEquals(1, attempts)
    }
}
