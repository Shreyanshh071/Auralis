package com.auralis.music.windows.player

import kotlin.test.*

/** Animated covers must come from the same recording, not just a song with the same name. */
class AnimatedCoverMatchTest {
    private fun same(a: String, b: String) = CanvasLookup.titleKey(a) == CanvasLookup.titleKey(b)

    @Test
    fun versionTagsMakeADifferentRecording() {
        // Apple's search put a DJ mix's "Let Down (Mixed)" (with that compilation's video) next to Radiohead's.
        assertFalse(same("Let Down", "Let Down (Mixed)"))
        assertFalse(same("Creep", "Creep (Acoustic)"))
        assertFalse(same("Wonderwall", "Wonderwall - Live"))
        assertFalse(same("Blinding Lights", "Blinding Lights (Chromatics Remix)"))
        assertFalse(same("Heat Waves", "Heat Waves [Sped Up]"))
    }

    @Test
    fun tagsThatKeepTheRecordingAreIgnored() {
        assertTrue(same("Where Is My Mind? - 2007 Remaster", "Where Is My Mind?"))
        assertTrue(same("Let Down (Remastered)", "Let Down"))
        assertTrue(same("Bitter Sweet Symphony - Remastered 2016", "Bitter Sweet Symphony"))
        assertTrue(same("Stay (feat. Justin Bieber)", "Stay"))
        assertTrue(same("Counting Stars (Official Video)", "Counting Stars"))
        assertTrue(same("Feel Good Inc.", "Feel Good Inc"))
        assertTrue(same("Mr. Brightside [Explicit]", "Mr. Brightside"))
    }

    @Test
    fun aMatchingTagOnBothSidesStillMatches() {
        assertTrue(same("Wonderwall (Live)", "Wonderwall - Live"))
    }

    @Test
    fun stageCanvasRequiresNineSixteenAspect() {
        assertTrue(isStageCanvasAspect(720, 1280))
        assertTrue(isStageCanvasAspect(1080, 1920))
        // Slight macroblock padding around 9:16 is still accepted.
        assertTrue(isStageCanvasAspect(560, 960))
        // 3:4 album-header motion art (e.g. Currents 1078x1438) and 1:1 square covers are rejected.
        assertFalse(isStageCanvasAspect(1078, 1438))
        assertFalse(isStageCanvasAspect(768, 768))
        assertFalse(isStageCanvasAspect(1280, 720))
        assertFalse(isStageCanvasAspect(0, 1280))
    }

    @Test
    fun appleStageLookupExcludesThreeByFourAndSquareAssets() {
        assertEquals(listOf("motionDetailTall"), CanvasLookup.appleAssetKeys(preferTall = true))
        assertEquals(
            listOf("motionDetailSquare", "motionSquareVideo1x1", "motionDetailTall", "motionTallVideo3x4"),
            CanvasLookup.appleAssetKeys(preferTall = false)
        )
    }
}
