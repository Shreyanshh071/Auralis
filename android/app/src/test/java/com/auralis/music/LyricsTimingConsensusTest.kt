package com.auralis.music

import com.auralis.music.data.network.LyricsTimingConsensus
import com.auralis.music.data.local.LyricsProviderChoices
import com.auralis.music.domain.model.*
import org.junit.Assert.*
import org.junit.Test

class LyricsTimingConsensusTest {
    private fun lyrics(provider: LyricsProvider, offset: Long = 0, word: Boolean = true) = LyricsData(
        if (word) SyncType.RICHSYNC else SyncType.LINE_SYNC,
        (0..7).map { index ->
            val start = 10000L + index * 5000 + offset
            LyricLine(start, "Unique verse number $index here", words = if (word) listOf(
                LyricWord("Unique verse ", start, 1000), LyricWord("number $index here", start + 1200, 1000)) else null)
        }, provider = provider)
    private val fallback = lyrics(LyricsProvider.BETTER_LYRICS, 4000)
    private val lineA = lyrics(LyricsProvider.LRCLIB, word = false)
    private val lineB = lyrics(LyricsProvider.NETEASE, word = false)

    @Test fun accurateKuGouBeatsDriftingHigherPriorityWordSources() {
        val result = LyricsTimingConsensus.select(fallback, listOf(fallback,
            lyrics(LyricsProvider.QQMUSIC, 4000), lyrics(LyricsProvider.KUGOU), lineA, lineB))
        assertEquals(LyricsProvider.KUGOU, result.provider)
        assertEquals(1, result.timingConfidence)
    }
    @Test fun verifiedLineSyncBeatsClearlyDriftingWordSync() {
        val result = LyricsTimingConsensus.select(fallback, listOf(fallback, lineA, lineB,
            lyrics(LyricsProvider.MUSIXMATCH, word = false)))
        assertEquals(SyncType.LINE_SYNC, result.syncType)
        assertEquals(1, result.timingConfidence)
    }
    @Test fun comparableWordTimingKeepsProviderPriorityAndBeatsLineDuets() {
        val good = lyrics(LyricsProvider.BETTER_LYRICS)
        val duet = lineA.copy(lines = lineA.lines.mapIndexed { i, line -> line.copy(agent = "v${i % 2 + 1}") })
        assertEquals(good.provider, LyricsTimingConsensus.select(good,
            listOf(good, lyrics(LyricsProvider.KUGOU), duet, lineB)).provider)
    }
    @Test fun smallDisagreementsDoNotInventAConfidentWinner() {
        assertEquals(fallback.provider, LyricsTimingConsensus.select(fallback,
            listOf(fallback, lyrics(LyricsProvider.KUGOU), lineA)).provider)
    }
    @Test fun appleMirrorsDoNotCountAsIndependentVotes() {
        val result = LyricsTimingConsensus.select(fallback, listOf(fallback,
            lyrics(LyricsProvider.AMLL), lyrics(LyricsProvider.PAXSENIX), lyrics(LyricsProvider.YOULYPLUS)))
        assertEquals(fallback.provider, result.provider)
        assertEquals(0, result.timingConfidence)
    }
    @Test fun repeatedChorusesAndShortFragmentsAreNotAnchors() {
        val repeated = lineA.copy(lines = lineA.lines.map { it.copy(text = "Repeated chorus") })
        assertEquals(0, LyricsTimingConsensus.compare(repeated, repeated.copy(provider = LyricsProvider.NETEASE)))
        assertEquals(0, LyricsTimingConsensus.compare(lineA.copy(lines = lineA.lines.take(3)), lineB))
    }
    @Test fun clearOffsetsAndProgressiveDriftAreDetectedWithoutChangingTimestamps() {
        assertEquals(-1, LyricsTimingConsensus.compare(lineA, fallback))
        val drift = lineB.copy(lines = lineB.lines.mapIndexed { i, line -> line.copy(time = line.time + i * 1100) })
        assertEquals(-1, LyricsTimingConsensus.compare(lineA, drift))
        val selected = LyricsTimingConsensus.select(fallback, listOf(fallback, lyrics(LyricsProvider.KUGOU), lineA, lineB))
        assertEquals(10000L, selected.lines.first().words!!.first().time)
    }
    @Test fun validatedSpicyRemainsFirstEvenWhenOnlyLineSynced() {
        val spicy = lyrics(LyricsProvider.SPICY_LYRICS, word = false)
        assertEquals(spicy.provider, LyricsTimingConsensus.select(fallback, listOf(fallback, spicy, lineA, lineB)).provider)
    }
    @Test fun cachedTimingEvidenceSurvivesSerialization() {
        val data = lineA.copy(timingConfidence = 1, timingSelectionVersion = LyricsTimingConsensus.VERSION)
        val entity = com.auralis.music.data.repository.LyricsRepositoryImpl.domainToEntity("abcdefghijk", data, "Song", "Artist")
        val restored = com.auralis.music.data.repository.LyricsRepositoryImpl.entityToDomain(entity, "Song", "Artist")!!
        assertEquals(data.timingConfidence, restored.timingConfidence)
        assertEquals(data.timingSelectionVersion, restored.timingSelectionVersion)
    }
    @Test fun manualChoicesSurviveRestartAndStayScopedToRecording() {
        val directory = kotlin.io.path.createTempDirectory("lyrics-choices").toFile()
        try {
            LyricsProviderChoices.init(directory)
            LyricsProviderChoices.save("track", "recording", "Song", "Artist", 180000, LyricsProvider.KUGOU)
            LyricsProviderChoices.init(directory)
            assertEquals(LyricsProvider.KUGOU, LyricsProviderChoices.read("track", "recording", "Song", "Artist", 180300))
            assertNull(LyricsProviderChoices.read("track", "live-recording", "Song", "Artist", 180000))
            assertNull(LyricsProviderChoices.read("track", "recording", "Song", "Artist", 220000))
            assertNull(LyricsProviderChoices.read("track", "recording", "Song (Live)", "Artist", 180000))
            assertEquals(LyricsProvider.KUGOU, LyricsProviderChoices.read("track", "recording", "Song", "Artist", 180000))
        } finally { directory.deleteRecursively() }
    }
}
