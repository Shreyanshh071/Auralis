package com.auralis.music.data.network

import com.auralis.music.domain.lyrics.LyricsAlignmentEngine
import com.auralis.music.domain.lyrics.MasterMatchStatus
import com.auralis.music.domain.model.LyricLine
import com.auralis.music.domain.model.LyricWord
import com.auralis.music.domain.model.LyricsData
import com.auralis.music.domain.model.LyricsProvider
import com.auralis.music.domain.model.SyncType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** Real cases from the Windows log where same-length word sync lost to line sync. */
class MasterMatchRulesTest {
    private fun lyrics(firstLine: LyricLine, durationMs: Long?, track: String, artist: String) = LyricsData(
        syncType = SyncType.RICHSYNC, provider = LyricsProvider.BETTER_LYRICS, durationMs = durationMs,
        trackName = track, artistName = artist,
        lines = listOf(firstLine) + (1..14).map { LyricLine(it * 15_000L, "line $it", words = listOf(LyricWord("line ", it * 15_000L, 400))) })

    private fun timedOpening(start: Long) = LyricLine(start, "Find your dreams come true", words = listOf(
        LyricWord("Find ", start, 300), LyricWord("your ", start + 350, 300),
        LyricWord("dreams ", start + 700, 400), LyricWord("come ", start + 1150, 300), LyricWord("true", start + 1500, 600)))

    @Test fun songThatOpensOnItsFirstWordIsAccepted() {
        // "I Wonder" (Kanye West) is sung from 0:00; Better Lyrics states 243440 ms for 243461 ms audio.
        val data = lyrics(timedOpening(0), 243_440, "I Wonder", "Kanye West")
        assertNotEquals(MasterMatchStatus.MASTER_MISMATCH,
            LyricsAlignmentEngine.evaluateMasterMatch(data, 243_461, "I Wonder", "I Wonder", null, null, "Kanye West", "Kanye West"))
    }

    @Test fun zeroStartWithoutWordTimingOrExactLengthStaysRejected() {
        val untimed = lyrics(LyricLine(0, "Find your dreams come true"), 243_440, "I Wonder", "Kanye West")
        assertEquals(MasterMatchStatus.MASTER_MISMATCH, LyricsAlignmentEngine.evaluateMasterMatch(untimed, 243_461))
        val otherLength = lyrics(timedOpening(0), 239_000, "I Wonder", "Kanye West")
        assertEquals(MasterMatchStatus.MASTER_MISMATCH, LyricsAlignmentEngine.evaluateMasterMatch(otherLength, 243_461))
    }

    @Test fun guestMissingFromLeadOnlyCreditIsAcceptedAtTheSameLength() {
        // YouTube: "We Don't Talk Anymore (feat. Selena Gomez)"; providers credit only "Charlie Puth".
        val data = lyrics(timedOpening(500), 217_707, "We Don't Talk Anymore", "Charlie Puth")
        assertNotEquals(MasterMatchStatus.MASTER_MISMATCH, LyricsAlignmentEngine.evaluateMasterMatch(data, 217_721,
            "We Don't Talk Anymore (feat. Selena Gomez)", "We Don't Talk Anymore", null, null, "Charlie Puth, Selena Gomez", "Charlie Puth"))
    }

    @Test fun soloCutOfAFeatSongIsStillRejectedWhenTheLengthDiffers() {
        // "Moral of the Story": the solo and feat. Niall Horan cuts are only 2.5 s apart.
        val solo = lyrics(timedOpening(9_000), 198_500, "Moral of the Story", "Ashe")
        assertEquals(MasterMatchStatus.MASTER_MISMATCH, LyricsAlignmentEngine.evaluateMasterMatch(solo, 201_000,
            "Moral of the Story (feat. Niall Horan)", "Moral of the Story", null, null, "Ashe", "Ashe"))
    }

    @Test fun guestNamedAmongCreditedArtistsCounts() {
        val data = lyrics(timedOpening(500), 214_000, "We Don't Talk Anymore", "Charlie Puth, Selena Gomez")
        assertNotEquals(MasterMatchStatus.MASTER_MISMATCH, LyricsAlignmentEngine.evaluateMasterMatch(data, 217_000,
            "We Don't Talk Anymore (feat. Selena Gomez)", "We Don't Talk Anymore", null, null, "Charlie Puth", "Charlie Puth, Selena Gomez"))
    }
}
