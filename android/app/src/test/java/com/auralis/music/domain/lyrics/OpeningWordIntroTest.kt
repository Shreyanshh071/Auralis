package com.auralis.music.domain.lyrics

import com.auralis.music.domain.model.LyricLine
import com.auralis.music.domain.model.LyricWord
import com.auralis.music.domain.model.LyricsData
import com.auralis.music.domain.model.LyricsProvider
import com.auralis.music.domain.model.SyncType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Can't Tell Me Nothing" on the phone: Spicy's copy opens "La, la, la, la" at 99ms (measured from
 * the PC's cached response), states 271600ms, and the phone's decoder reported 273630ms with 697ms
 * of measured leading silence. The PC accepts it; the phone rejected it as a corrupt intro.
 */
class OpeningWordIntroTest {
    private fun lyrics(firstWords: List<LyricWord>?, firstLineTime: Long = 99L) = LyricsData(
        syncType = SyncType.RICHSYNC,
        provider = LyricsProvider.SPICY_LYRICS,
        trackName = "Can't Tell Me Nothing",
        artistName = "Kanye West",
        durationMs = 271_600L,
        lines = listOf(LyricLine(firstLineTime, "La, la, la, la, wait till I get my money right", words = firstWords)) +
            (1..20).map { i -> LyricLine(7_208L + i * 2_500L, "line $i",
                words = listOf(LyricWord("line", 7_208L + i * 2_500L), LyricWord("$i", 7_508L + i * 2_500L))) }
    )

    private val timedOpening = listOf(LyricWord("La,", 99), LyricWord("la,", 1_638), LyricWord("la,", 2_383),
        LyricWord("la,", 2_758), LyricWord("wait", 3_191))

    @Test fun genuineTimedOpeningIsAcceptedOnceTheStreamsLeadingSilenceIsAccountedFor() {
        assertTrue(LyricsAlignmentEngine.isAcceptableMasterMatch(lyrics(timedOpening), 273_630L,
            playbackTitle = "Can't Tell Me Nothing", playbackArtist = "Kanye West", audioLeadingSilenceMs = 697L))
    }

    @Test fun sameLengthMasterIsAcceptedLikeThePc() {
        assertTrue(LyricsAlignmentEngine.isAcceptableMasterMatch(lyrics(timedOpening), 272_000L))
    }

    @Test fun lineStampedAtZeroWithoutWordTimingIsStillRejected() {
        assertFalse(LyricsAlignmentEngine.isAcceptableMasterMatch(lyrics(null, firstLineTime = 0L), 272_000L,
            audioLeadingSilenceMs = 697L))
    }

    // Unison's copy for the playing video opens at 39ms and states the album's 267720ms.
    @Test fun unisonSyncedToThePlayingVideoKeepsItsTimedOpening() {
        val unison = lyrics(timedOpening.map { it.copy(time = (it.time - 60).coerceAtLeast(39)) }, firstLineTime = 39L)
            .copy(provider = LyricsProvider.UNISON, durationMs = 267_720L, isExactVideoMatch = true, matchedVideoId = "hqvcww4ydh8")
        assertFalse(com.auralis.music.data.network.LyricsClient.hasUntrustedIntro(unison, 273_630L, 697L, "hqvcww4ydh8"))
        // Another video's Unison copy gets no such trust.
        assertTrue(com.auralis.music.data.network.LyricsClient.hasUntrustedIntro(unison, 273_630L, 697L, "someOtherVid"))
    }

    @Test fun timedOpeningOnAMasterOfAnotherLengthIsStillRejected() {
        assertFalse(LyricsAlignmentEngine.isAcceptableMasterMatch(lyrics(timedOpening), 273_630L))
    }
}
