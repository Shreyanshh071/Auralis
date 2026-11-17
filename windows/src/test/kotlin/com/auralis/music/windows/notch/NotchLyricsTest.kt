package com.auralis.music.windows.notch

import com.auralis.music.desktop.lyrics.DesktopLyricsController
import com.auralis.music.domain.model.*
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import kotlin.test.*

class NotchLyricsTest {
    private fun state(lines: List<LyricLine>, sync: SyncType = SyncType.RICHSYNC) = DesktopLyricsController.State(
        trackId = "song", lyrics = LyricsData(syncType = sync, lines = lines, provider = LyricsProvider.LOCAL))

    @Test fun nestedSyllablesRetainExactTimingSpacesAndUnknownEnds() {
        val word = LyricWord("syllable ", 1000, 900, syllables = listOf(
            LyricWord("syl", 1000, 200), LyricWord("la", 1400, 100), LyricWord("ble", 1600, null)))
        val pieces = flattenNotchWord(word)
        assertEquals(listOf("syl", "la", "ble", " "), pieces.map { it.text })
        assertEquals(listOf(1000L, 1400L, 1600L, null), pieces.map { it.timeMs })
        assertEquals(listOf(200L, 100L, null, null), pieces.map { it.durationMs })
        val encoded = notchLyrics(state(listOf(LyricLine(1000, "syllable ", words = listOf(word)))), "song", 1450)
        assertEquals("syllable ", encoded.lines.single().text)
        assertEquals(1900L, encoded.lines.single().endMs, "The parent's genuine end may bound the line without inventing a syllable end")
        assertNull(encoded.lines.single().pieces[2].durationMs)
    }

    @Test fun syllablesAreNeverInventedFromSpelling() {
        val words = flattenNotchWord(LyricWord("ordinary ", 1000, 600))
        assertEquals(1, words.size)
        assertEquals("ordinary ", words.single().text)
        val mismatched = flattenNotchWord(LyricWord("original", 1000, null,
            syllables = listOf(LyricWord("unrelated", 1000, 100))))
        assertEquals("original", mismatched.single().text)
        assertNull(mismatched.single().durationMs)
    }

    @Test fun clearOldTracksAndRepresentMissingAndUnsyncedDataHonestly() {
        assertEquals("loading", notchLyrics(state(listOf(LyricLine(0, "old"))), "different", 0).status)
        assertEquals("unavailable", notchLyrics(DesktopLyricsController.State("song"), "song", 0).status)
        assertEquals("loading", notchLyrics(DesktopLyricsController.State("song", isLoading = true), "song", 0).status)
        val plain = notchLyrics(state(listOf(LyricLine(0, "plain"), LyricLine(0, "lyrics")), SyncType.PLAIN), "song", 0)
        assertEquals("static", plain.status)
        assertEquals("plain\nlyrics", plain.lines.single().text)
        assertTrue(plain.lines.single().pieces.isEmpty())
    }

    @Test fun prefetchNextLinesAndRecomputeWindowImmediatelyAfterBackwardSeek() {
        val lyrics = state((1..7).map { LyricLine(it * 1000L, "line $it") })
        assertEquals(listOf(4000L, 5000L, 6000L), notchLyrics(lyrics, "song", 4500).lines.map { it.timeMs })
        assertEquals(listOf(1000L, 2000L, 3000L), notchLyrics(lyrics, "song", 100).lines.map { it.timeMs })
        assertEquals(listOf(7000L), notchLyrics(lyrics, "song", 9000).lines.map { it.timeMs })
    }

    @Test fun realVocalEndsAndLeadVocalsArePreservedWithoutDoubleApplyingOffset() {
        val lyrics = state(listOf(
            LyricLine(1500, "lead", words = listOf(LyricWord("lead", 1500, 200)), endTime = 2000),
            LyricLine(1600, "background", isBackground = true),
            LyricLine(4000, "next")))
        val aligned = lyrics.copy(lyrics = lyrics.lyrics!!.copy(appliedOffsetMs = 500))
        val result = notchLyrics(aligned, "song", 1800)
        assertEquals(listOf("lead", "next"), result.lines.map { it.text })
        assertEquals(1500, result.lines.first().pieces.first().timeMs)
        assertEquals(2000, result.lines.first().endMs)
    }

    @Test fun lineSyncDoesNotAcquireFakeWordTimingAndPayloadIsBounded() {
        val result = notchLyrics(state(listOf(LyricLine(1000, "Line one"), LyricLine(2000, "Line two")), SyncType.LINE_SYNC), "song", 1500)
        assertTrue(result.lines.first().pieces.isEmpty())
        assertEquals(2000, result.lines.first().endMs)
        val snapshot = NotchSnapshot(true, "song", "Title", "Artist", "", true, false, 1500, 3000, false, false,
            lyrics = NotchLyrics("ready", listOf(NotchLyricLine(0, null, "x".repeat(130_000), emptyList(), false))))
        val json = snapshot.json()
        assertTrue(json.toByteArray().size < 120_000)
        assertEquals("unavailable", JSONObject(json).getJSONObject("lyrics").getString("status"))
    }

    @Test fun richTimingAndPlaybackRateSurviveTheKotlinSwiftBoundary() {
        val binary = nativeNotchExecutable() ?: return
        val payload = notchLyrics(state(listOf(LyricLine(1000, "hello ", words = listOf(
            LyricWord("hello ", 1000, 500, syllables = listOf(LyricWord("hel", 1000, 200), LyricWord("lo ", 1350, null))))))), "song", 1100)
        val snapshot = NotchSnapshot(true, "song", "Title", "Artist", "", false, false, 1100, 5000, false, false,
            lyrics = payload, playbackRate = 1.5)
        val process = ProcessBuilder(binary.absolutePath, "--check-snapshot").start()
        try {
            process.outputStream.bufferedWriter().use { it.write(snapshot.json()) }
            assertTrue(process.waitFor(5, TimeUnit.SECONDS))
            assertEquals(0, process.exitValue(), process.errorStream.bufferedReader().readText())
            val decoded = JSONObject(process.inputStream.bufferedReader().readText())
            assertEquals(1.5, decoded.getDouble("playbackRate"))
            val pieces = decoded.getJSONObject("lyrics").getJSONArray("lines").getJSONObject(0).getJSONArray("pieces")
            assertEquals("hel", pieces.getJSONObject(0).getString("text"))
            assertEquals(1350, pieces.getJSONObject(1).getInt("timeMs"))
            assertTrue(pieces.getJSONObject(1).isNull("durationMs"))
        } finally { process.destroyForcibly() }
    }
}
