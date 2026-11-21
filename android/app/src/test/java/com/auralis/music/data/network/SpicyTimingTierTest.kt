package com.auralis.music.data.network

import com.auralis.music.domain.model.LyricsProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spicy leads among equal timing only: another source's word sync beats line-synced Spicy. */
class SpicyTimingTierTest {
    private fun outranks(tier: Int, provider: LyricsProvider, bestTier: Int, bestProvider: LyricsProvider) =
        LyricsClient.outranks(tier, 100.0, bestTier, 100.0, provider = provider, bestProvider = bestProvider)

    @Test fun wordSyncFromAnotherSourceBeatsLineSyncedSpicy() {
        assertTrue(outranks(LyricsClient.TIER_WORD, LyricsProvider.UNISON, LyricsClient.TIER_LINE, LyricsProvider.SPICY_LYRICS))
        assertFalse(outranks(LyricsClient.TIER_LINE, LyricsProvider.SPICY_LYRICS, LyricsClient.TIER_WORD, LyricsProvider.BETTER_LYRICS))
    }

    @Test fun spicyStillLeadsAtEqualTiming() {
        assertTrue(outranks(LyricsClient.TIER_LINE, LyricsProvider.SPICY_LYRICS, LyricsClient.TIER_LINE, LyricsProvider.LRCLIB))
        assertTrue(outranks(LyricsClient.TIER_WORD, LyricsProvider.SPICY_LYRICS, LyricsClient.TIER_WORD, LyricsProvider.UNISON))
        assertFalse(outranks(LyricsClient.TIER_WORD, LyricsProvider.UNISON, LyricsClient.TIER_WORD, LyricsProvider.SPICY_LYRICS))
    }
}
