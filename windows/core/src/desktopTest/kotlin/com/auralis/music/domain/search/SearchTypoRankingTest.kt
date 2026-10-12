package com.auralis.music.domain.search

import com.auralis.music.domain.model.Track
import kotlin.test.Test
import kotlin.test.assertEquals

class SearchTypoRankingTest {
    private fun song(id: String, title: String, artist: String, plays: String) =
        Track(id = id, title = title, artist = artist, views = plays)

    @Test fun popularNearSpellingRanksFirst() {
        val (ranked, _) = SearchQueryMatcher.partitionResults(listOf(
            song("lime", "Imposter Syndrome", "Lime Cordiale", "363K plays"),
            song("gish", "Impostor Syndrome", "Sidney Gish", "45M plays"),
            song("whores", "Imposter Syndrome", "Whores", "78K plays"),
        ), "imposter syndrome")
        assertEquals("gish", ranked.first().id)
    }

    @Test fun exactSpellingStillWinsWhenTheTypoIsNotFarMorePlayed() {
        val (ranked, _) = SearchQueryMatcher.partitionResults(listOf(
            song("typo", "Impostor Syndrome", "Someone", "500K plays"),
            song("exact", "Imposter Syndrome", "Lime Cordiale", "363K plays"),
        ), "imposter syndrome")
        assertEquals("exact", ranked.first().id)
    }
}
