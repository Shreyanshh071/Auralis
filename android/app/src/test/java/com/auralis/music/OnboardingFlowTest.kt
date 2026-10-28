package com.auralis.music

import com.auralis.music.data.network.ChartsArtistsSource
import com.auralis.music.domain.model.Artist
import com.auralis.music.domain.model.Track
import com.auralis.music.domain.onboarding.OnboardingFlow
import com.auralis.music.domain.onboarding.OnboardingFlow.Step
import org.junit.Assert.*
import org.junit.Test

class OnboardingFlowTest {
    @Test fun `returning users skip tour even when installation has no tour flag`() {
        assertEquals(Step.APP, OnboardingFlow.step(false, true, true, true))
        assertEquals(Step.APP, OnboardingFlow.step(true, true, true, null))
        assertEquals(Step.PICK_ARTISTS, OnboardingFlow.step(true, true, false, false))
        assertEquals(Step.SIGN_IN, OnboardingFlow.step(true, false, true, true))
    }
    @Test fun `signed out never shows tour for any cached onboarding state`() {
        for (seen in listOf(false, true)) for (picked in listOf(false, true)) {
            for (history in listOf(null, false, true)) {
                assertEquals(Step.SIGN_IN, OnboardingFlow.step(seen, false, picked, history))
            }
        }
    }

    @Test fun `missing tour flag cannot interrupt restore or returning listener`() {
        assertEquals(Step.SETTLING, OnboardingFlow.step(false, true, false, null))
        assertEquals(Step.APP, OnboardingFlow.step(false, true, false, true))
        assertEquals(Step.APP, OnboardingFlow.step(false, true, true, null))
    }

    @Test fun `regional and international artist suggestions interleave without duplicates`() {
        val regional = listOf(Artist("a", "A"), Artist("b", "B"))
        val international = listOf(Artist("c", "C"), Artist("a2", "A"))
        assertEquals(listOf("A", "C", "B"), OnboardingFlow.suggestions(regional, international).map { it.name })
    }

    @Test fun `first install signs in and restores before newcomer tour and picks`() {
        assertEquals(Step.SIGN_IN, OnboardingFlow.step(false, false, false, null))
        assertEquals(Step.SETTLING, OnboardingFlow.step(false, true, false, null))
        assertEquals(Step.TOUR, OnboardingFlow.step(false, true, false, false))
        assertEquals(Step.SIGN_IN, OnboardingFlow.step(true, false, false, null))
        assertEquals(Step.SETTLING, OnboardingFlow.step(true, true, false, null))
        assertEquals(Step.PICK_ARTISTS, OnboardingFlow.step(true, true, false, false))
        assertEquals(Step.APP, OnboardingFlow.step(true, true, true, false))
    }
    @Test fun `restore finishes before picker decision and returning users skip`() {
        assertEquals(Step.SETTLING, OnboardingFlow.step(true, true, false, null))
        assertEquals(Step.APP, OnboardingFlow.step(true, true, false, true))
    }
    @Test fun `related artists insert beside pick without duplicates`() {
        val a = Artist("a", "A")
        val b = Artist("b", "B")
        val c = Artist("c", "C")
        assertEquals(listOf(a, c, b), OnboardingFlow.insertRelated(listOf(a,b), a, listOf(b,c,c)))
        assertEquals(emptyList<Artist>(), OnboardingFlow.togglePick(listOf(a), a.copy(id = "another-id")))
    }
    @Test fun `seeds interleave artists and remove repeated songs`() {
        val a = Artist("a", "A"); val b = Artist("b", "B")
        val first = Track("one", "First", "A"); val second = Track("two", "Second", "A")
        val third = Track("three", "Third", "B")
        assertEquals(listOf(first, third, second), OnboardingFlow.seedTracks(listOf(a to listOf(first, second), b to listOf(third, first))))
    }
    @Test fun `real chart fixture excludes podcasts and duplicate rows`() {
        val raw = javaClass.getResource("/charts/top_artists_us.json")!!.readText()
        val artists = ChartsArtistsSource.parseTopArtists(raw)
        assertEquals(listOf("Drake", "YoungBoy Never Broke Again", "Fuerza Regida"), artists.map { it.name })
        assertTrue(artists.all { it.id.startsWith("UC") })
        assertTrue(artists.first().thumbnail!!.contains("w400-h400"))
        assertTrue(ChartsArtistsSource.parseTopArtists("not json").isEmpty())
    }
    @Test fun `fallback suggestions follow music region`() {
        assertTrue(ChartsArtistsSource.fallbackNames("IN").contains("Arijit Singh"))
        assertFalse(ChartsArtistsSource.fallbackNames("US").contains("Arijit Singh"))
        assertTrue(ChartsArtistsSource.fallbackNames("KR").contains("BTS"))
    }
    @Test fun `picked artists replace the original home seeds`() {
        val provider = com.auralis.music.domain.recommendations.NewUserSeedProvider
        val song = Track("picked", "Chosen song", "Chosen artist")
        try {
            provider.setStarter(listOf("Chosen artist"), listOf(song))
            assertEquals(listOf("Chosen artist"), provider.SEED_ARTISTS)
            assertEquals(listOf(song), provider.getInitialSeedTracks())
        } finally { provider.setStarter(emptyList(), emptyList()) }
    }
}
